"""Release-only metadata publisher. No APK upload, Release creation or policy mutation."""
import argparse
import concurrent.futures
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
REPO = "daxiaamu/Mobile-CPE"
MAX_APK = 512 * 1024 * 1024


def require(condition, message):
    if not condition:
        raise ValueError(message)


def https(url):
    u = urllib.parse.urlsplit(url)
    require(u.scheme == "https" and u.hostname and not u.username and not u.password and not u.fragment, "HTTPS URL required")
    return url


class HttpsRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        https(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def validate_cdn_destination(original, final):
    # Redirect aliases cannot count as distinct CDN endpoints.
    https(final)
    require(urllib.parse.urlsplit(original).hostname.lower() == urllib.parse.urlsplit(final).hostname.lower(),
            "Cross-host redirect is not an independent CDN endpoint")


def validate_release(release):
    require(not release.get("draft") and release.get("published_at"), "Release must be published")
    assets = [a for a in release["assets"] if a["name"].endswith(".apk")]
    require(len(assets) == 1, "Release must have exactly one APK")
    asset = assets[0]
    prefix = "https://github.com/" + REPO + "/releases/download/"
    require(https(asset["browser_download_url"]).startswith(prefix), "APK is outside configured repository")
    require(0 < asset["size"] <= MAX_APK, "Invalid APK size")
    return asset


def apk_identity(apk, aapt2):
    output = subprocess.check_output([aapt2, "dump", "badging", str(apk)], text=True)
    match = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", output, re.M)
    require(match is not None, "aapt2 cannot read APK identity")
    package, code, name = match.groups()
    require(package == "dev.autohotspot", "Wrong APK package")
    return int(code), name


def validate_policy(policy, code):
    require(type(policy["policyRevision"]) is int and policy["policyRevision"] > 0, "Invalid policyRevision")
    require(type(policy["maxForcedVersionCode"]) is int and 0 <= policy["maxForcedVersionCode"] < code, "Invalid forced boundary")
    require(isinstance(policy.get("reason"), str) and policy["reason"].strip(), "Policy audit reason required")


def validate_manifest(m):
    require(m["schemaVersion"] == 1 and m["channel"] in ("stable", "beta"), "Invalid schema/channel")
    require(type(m["versionCode"]) is int and m["versionCode"] > 0, "Invalid versionCode")
    validate_policy(dict(m, reason="generated"), m["versionCode"])
    require(re.fullmatch(r"[0-9a-f]{64}", m["sha256"]) is not None, "Invalid SHA-256")
    require(type(m["size"]) is int and 0 < m["size"] <= MAX_APK, "Invalid size")
    require(isinstance(m["versionName"], str) and 0 < len(m["versionName"]) <= 128, "Invalid versionName")
    urls = m["urls"]
    require(1 <= len(urls) <= 20 and len(set(urls)) == len(urls), "Invalid URL count/duplicates")
    hosts = {urllib.parse.urlsplit(https(url)).hostname.lower() for url in urls}
    require(len(hosts - {"github.com"}) >= 5 and "github.com" in hosts, "Five CDN hosts plus GitHub origin required")
    published = dt.datetime.fromisoformat(m["publishedAt"].replace("Z", "+00:00"))
    require(published.tzinfo is not None, "Timestamp requires a timezone")


def make_manifest(release, code, name, apk_hash, size, cdns, policy):
    asset = validate_release(release)
    require(release["tag_name"] in (name, "v" + name), "Release tag does not match APK versionName")
    beta = bool(re.search(r"beta|alpha|rc", name, re.I))
    require(beta == release["prerelease"], "Prerelease flag and APK channel disagree")
    require(size == asset["size"], "Release asset size disagrees with downloaded APK")
    validate_policy(policy, code)
    m = dict(schemaVersion=1, channel="beta" if beta else "stable", versionCode=code,
             versionName=name, publishedAt=release["published_at"], changelog=release.get("body") or "",
             maxForcedVersionCode=policy["maxForcedVersionCode"], policyRevision=policy["policyRevision"],
             sha256=apk_hash, size=size, urls=list(dict.fromkeys(cdns + [asset["browser_download_url"]])),
             releaseUrl=release["html_url"])
    validate_manifest(m)
    return m


def healthy(url, expected_hash, size):
    """HEAD is advisory; inclusion always requires a complete matching GET hash."""
    started = time.monotonic()
    opener = urllib.request.build_opener(HttpsRedirects())
    headers = {"User-Agent": "Mobile-CPE-release", "Cache-Control": "no-cache"}
    try:
        try:
            with opener.open(urllib.request.Request(https(url), headers=headers, method="HEAD"), timeout=10) as response:
                https(response.url)
                require(200 <= response.status < 300, "HEAD failed")
                require("text/html" not in response.headers.get("Content-Type", "").lower(), "HTML response")
                length = response.headers.get("Content-Length")
                require(length is None or int(length) == size, "HEAD size mismatch")
        except urllib.error.HTTPError as error:
            if error.code not in (403, 405, 501):
                raise
        digest = hashlib.sha256()
        total = 0
        with opener.open(urllib.request.Request(https(url), headers=headers), timeout=10) as response:
            validate_cdn_destination(url, response.url)
            require(200 <= response.status < 300, "GET failed")
            require("text/html" not in response.headers.get("Content-Type", "").lower(), "HTML response")
            while True:
                require(time.monotonic() - started < 180, "CDN download timeout")
                block = response.read(64 * 1024)
                if not block:
                    break
                total += len(block)
                require(total <= size, "Oversized CDN file")
                digest.update(block)
        require(total == size and digest.hexdigest() == expected_hash, "CDN SHA-256 mismatch")
        return url, time.monotonic() - started
    except Exception as error:
        print("CDN rejected:", urllib.parse.urlsplit(url).hostname, str(error)[:120], flush=True)
        return None


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def validate_previous(previous, m):
    require(m["policyRevision"] > previous["policyRevision"], "Increment the reviewed policyRevision before every publication")
    require(m["versionCode"] >= previous["versionCode"], "Version rollback rejected")
    require(m["maxForcedVersionCode"] >= previous["maxForcedVersionCode"], "Forced boundary rollback rejected")
    require(m["versionCode"] != previous["versionCode"] or m["sha256"] == previous["sha256"], "Same versionCode cannot replace APK bytes")


def publish(args):
    release = json.loads(Path(args.release).read_text(encoding="utf-8"))
    asset = validate_release(release)
    apks = list(Path(args.apk_dir).glob("*.apk"))
    require(len(apks) == 1 and apks[0].name == asset["name"], "Downloaded APK must match the unique asset")
    apk = apks[0]
    code, name = apk_identity(apk, args.aapt2)
    subprocess.run([args.apksigner, "verify", str(apk)], check=True)
    size = apk.stat().st_size
    require(0 < size <= MAX_APK and size == asset["size"], "Release APK size mismatch")
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    channel = "beta" if release["prerelease"] else "stable"
    policy = json.loads((ROOT / "updates/policy.json").read_text(encoding="utf-8"))[channel]
    validate_policy(policy, code)
    templates = json.loads((ROOT / "updates/cdn-sources.json").read_text(encoding="utf-8"))
    values = dict(officialUrl=asset["browser_download_url"], owner="daxiaamu", repo="Mobile-CPE",
                  tag=urllib.parse.quote(release["tag_name"], safe=""), apk=urllib.parse.quote(asset["name"], safe=""), sha256=digest)
    candidates = list(dict.fromkeys(https(t.format(**values)) for t in templates))
    with concurrent.futures.ThreadPoolExecutor(max_workers=7) as executor:
        accepted = list(executor.map(lambda u: healthy(u, digest, size), candidates))
    cdns = [r[0] for r in sorted((r for r in accepted if r), key=lambda r: r[1])]
    m = make_manifest(release, code, name, digest, size, cdns, policy)
    data = encoded(m); manifest_hash = hashlib.sha256(data).hexdigest()
    path = "manifests/{}-{}.json".format(m["policyRevision"], manifest_hash)

    def git(*words, directory=ROOT, input_text=None):
        return subprocess.check_output(["git", "-C", str(directory), *words], input=input_text, text=True).strip()

    # Build two commits locally, then move one remote ref only after both are complete.
    # Rebasing would invalidate the immutable commit URL, so concurrent writes fail and require a fresh run.
    existing = git("ls-remote", "--heads", "origin", "updates")
    if existing:
        git("fetch", "origin", "updates")
        base = git("rev-parse", "FETCH_HEAD")
    else:
        empty = git("hash-object", "-t", "tree", "--stdin", input_text="")
        base = git("commit-tree", empty, input_text="Initialize update metadata\n")
    with tempfile.TemporaryDirectory(prefix="mobile-cpe-update-") as temporary:
        tree = Path(temporary) / "metadata"
        require(tree.resolve().parent == Path(temporary).resolve(), "Worktree must remain inside its temporary directory")
        git("worktree", "add", "--detach", str(tree), base)
        try:
            previous_pointer = tree / ("latest-" + channel + ".json")
            if previous_pointer.exists():
                pointer = json.loads(previous_pointer.read_text(encoding="utf-8"))
                previous = json.loads((tree / pointer["path"]).read_text(encoding="utf-8"))
                validate_previous(previous, m)
            target = tree / path
            require(not target.exists(), "Immutable manifest already exists")
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            git("add", path, directory=tree)
            git("commit", "-m", "Add {} manifest revision {}".format(channel, m["policyRevision"]), directory=tree)
            commit = git("rev-parse", "HEAD", directory=tree)
            pointer = dict(schemaVersion=1, channel=channel, policyRevision=m["policyRevision"], commit=commit,
                           path=path, manifestSha256=manifest_hash,
                           expiresAt=(dt.datetime.now(dt.timezone.utc) + dt.timedelta(days=365)).strftime("%Y-%m-%dT%H:%M:%SZ"))
            (tree / ("latest-" + channel + ".json")).write_bytes(encoded(pointer))
            git("add", "latest-" + channel + ".json", directory=tree)
            git("commit", "-m", "Publish {} pointer revision {}".format(channel, m["policyRevision"]), directory=tree)
            require(git("ls-remote", "--heads", "origin", "updates") == existing, "Remote changed; rerun against its new head")
            git("push", "origin", "HEAD:refs/heads/updates", directory=tree)
            print("Published", channel, "versionCode", code, "revision", m["policyRevision"])
        finally:
            git("worktree", "remove", "--force", str(tree))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    check = commands.add_parser("validate-release")
    check.add_argument("--release", required=True)
    command = commands.add_parser("publish")
    command.add_argument("--release", required=True)
    command.add_argument("--apk-dir", required=True)
    command.add_argument("--aapt2", required=True)
    command.add_argument("--apksigner", required=True)
    args = parser.parse_args()
    if args.command == "validate-release":
        validate_release(json.loads(Path(args.release).read_text(encoding="utf-8")))
    else:
        require(os.environ.get("GITHUB_ACTIONS") == "true", "Publication is restricted to an explicitly triggered GitHub Actions run")
        publish(args)


if __name__ == "__main__":
    main()
