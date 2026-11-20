import copy
import unittest
from unittest import mock
from pathlib import Path
import hashlib
import json
import subprocess
import tempfile
from types import SimpleNamespace
import update_metadata as publisher
from update_metadata import make_manifest, validate_manifest, validate_previous, validate_release, https, encoded


class UpdatePublisherTest(unittest.TestCase):
    def setUp(self):
        self.release = dict(draft=False, prerelease=False, published_at="2026-11-20T00:00:00Z",
                            tag_name="v0.6.0", body="## 更新\n\n- 修复", html_url="https://github.com/daxiaamu/Mobile-CPE/releases/tag/v0.6.0",
                            assets=[dict(name="PhoneCPE.apk", size=123, browser_download_url="https://github.com/daxiaamu/Mobile-CPE/releases/download/v0.6.0/PhoneCPE.apk")])
        self.cdns = ["https://cdn{}.example/app.apk".format(i) for i in range(5)]
        self.policy = dict(policyRevision=1, maxForcedVersionCode=0, reason="initial policy")

    def manifest(self):
        return make_manifest(self.release, 11, "0.6.0", "a" * 64, 123, self.cdns, self.policy)

    def test_generates_stable_without_resetting_forced_policy(self):
        self.policy.update(policyRevision=8, maxForcedVersionCode=10)
        m = self.manifest()
        self.assertEqual((m["channel"], m["policyRevision"], m["maxForcedVersionCode"]), ("stable", 8, 10))
        self.assertEqual(len(m["urls"]), 6)
        self.assertIn("更新".encode(), encoded(m))

    def test_beta_channel(self):
        self.release.update(prerelease=True, tag_name="v0.7.0-beta1")
        m = make_manifest(self.release, 12, "0.7.0-beta1", "a" * 64, 123, self.cdns, self.policy)
        self.assertEqual(m["channel"], "beta")

    def test_multiple_apks_rejects_without_arbitrary_selection(self):
        self.release["assets"].append(copy.deepcopy(self.release["assets"][0]))
        with self.assertRaises(ValueError):
            validate_release(self.release)

    def test_tag_and_channel_mismatch(self):
        self.release["tag_name"] = "v9.9.9"
        with self.assertRaises(ValueError):
            self.manifest()
        self.release.update(tag_name="v0.6.0", prerelease=True)
        with self.assertRaises(ValueError):
            self.manifest()

    def test_five_distinct_non_origin_cdn_hosts_required(self):
        self.cdns = ["https://same.example/{}.apk".format(i) for i in range(5)]
        with self.assertRaises(ValueError):
            self.manifest()

    def test_hash_and_https_required(self):
        m = self.manifest()
        m["sha256"] = "invalid"
        with self.assertRaises(ValueError):
            validate_manifest(m)
        with self.assertRaises(ValueError):
            https("http://cdn.example/app.apk")

    def test_policy_revision_and_forced_floor_are_monotonic(self):
        previous = self.manifest()
        current = dict(previous, policyRevision=2, versionCode=12, maxForcedVersionCode=10)
        validate_previous(previous, current)
        with self.assertRaises(ValueError):
            validate_previous(previous, previous)
        with self.assertRaises(ValueError):
            validate_previous(current, dict(current, policyRevision=3, maxForcedVersionCode=0))

    def test_same_version_cannot_replace_bytes(self):
        previous = self.manifest()
        with self.assertRaises(ValueError):
            validate_previous(previous, dict(previous, policyRevision=2, sha256="b" * 64))

    def test_redirect_aliases_cannot_count_as_distinct_cdns(self):
        publisher.validate_cdn_destination("https://cdn.example/old", "https://cdn.example/new")
        with self.assertRaises(ValueError):
            publisher.validate_cdn_destination("https://alias.example/app", "https://github.com/app")
        with self.assertRaises(ValueError):
            publisher.validate_cdn_destination("https://cdn.example/app", "http://cdn.example/app")

    def test_timestamp_and_numbers_are_strict(self):
        m = self.manifest()
        for key, value in (("versionCode", "11"), ("size", True), ("publishedAt", "2026-11-20T00:00:00")):
            broken = dict(m, **{key: value})
            with self.assertRaises(ValueError):
                validate_manifest(broken)

    def test_publication_atomically_pins_real_git_manifest_commit(self):
        # Entirely local bare repository; no GitHub writes or network requests.
        with tempfile.TemporaryDirectory(prefix="cpe-publisher-test-") as temporary:
            directory = Path(temporary)
            source = directory / "source"
            remote = directory / "remote.git"
            source.mkdir()

            def git(*args, cwd=source):
                return subprocess.check_output(["git", "-C", str(cwd), *args], text=True, encoding="utf-8", stderr=subprocess.DEVNULL).strip()

            subprocess.check_call(["git", "init", "--bare", str(remote)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            git("init", "-b", "main")
            git("config", "user.name", "Publisher Test")
            git("config", "user.email", "test@example.invalid")
            git("remote", "add", "origin", str(remote))
            (source / "updates").mkdir()
            (source / "updates/policy.json").write_text(json.dumps(dict(stable=self.policy, beta=self.policy)))
            (source / "updates/cdn-sources.json").write_text(json.dumps(self.cdns))
            git("add", "updates")
            git("commit", "-m", "test source")
            release_dir = directory / "release"
            release_dir.mkdir()
            (release_dir / "PhoneCPE.apk").write_bytes(b"x" * 123)
            (release_dir / "release.json").write_text(json.dumps(self.release))
            args = SimpleNamespace(release=str(release_dir / "release.json"), apk_dir=str(release_dir), aapt2="fixture", apksigner="fixture-signer")
            original_run = subprocess.run

            def signer_only(command, *a, **kw):
                if command[0] == "fixture-signer":
                    return subprocess.CompletedProcess(command, 0)
                return original_run(command, *a, **kw)

            with mock.patch.object(publisher, "ROOT", source), mock.patch.object(publisher, "apk_identity", return_value=(11, "0.6.0")), \
                    mock.patch.object(publisher, "healthy", side_effect=lambda url, *_: (url, 1)), mock.patch.object(subprocess, "run", side_effect=signer_only):
                with mock.patch.object(publisher, "healthy", return_value=None):
                    with self.assertRaises(ValueError):
                        publisher.publish(args)
                self.assertEqual(git("ls-remote", "--heads", "origin", "updates"), "")
                publisher.publish(args)
            pointer = json.loads(git("show", "updates:latest-stable.json", cwd=remote))
            raw = subprocess.check_output(["git", "-C", str(remote), "show", pointer["commit"] + ":" + pointer["path"]])
            self.assertEqual(hashlib.sha256(raw).hexdigest(), pointer["manifestSha256"])
            self.assertEqual(pointer["policyRevision"], 1)
            self.assertEqual(json.loads(raw)["maxForcedVersionCode"], 0)


if __name__ == "__main__":
    unittest.main()
