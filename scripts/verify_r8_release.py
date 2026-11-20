"""Verify externally loaded entry points in a final minified APK (stdlib only)."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import zipfile


def dex_classes(data):
    def u32(offset): return struct.unpack_from('<I', data, offset)[0]
    def uleb(offset):
        value = shift = 0
        while True:
            byte = data[offset]
            offset += 1
            value |= (byte & 127) << shift
            if byte < 128: return value, offset
            shift += 7
    def string(index):
        offset = u32(u32(60) + index * 4)
        _, offset = uleb(offset)
        return data[offset:data.index(b'\0', offset)].decode('utf-8', errors='replace')
    def typename(index): return string(u32(u32(68) + index * 4))
    for index in range(u32(96)):
        entry = u32(100) + index * 32
        name = typename(u32(entry))
        offset = u32(entry + 24)
        methods = {}
        if offset:
            counts = []
            for _ in range(4):
                count, offset = uleb(offset)
                counts.append(count)
            for _ in range(counts[0] + counts[1]):
                _, offset = uleb(offset)
                _, offset = uleb(offset)
            for count in counts[2:]:
                method_index = 0
                for _ in range(count):
                    delta, offset = uleb(offset)
                    method_index += delta
                    access, offset = uleb(offset)
                    code, offset = uleb(offset)
                    method = u32(92) + method_index * 8
                    label = string(u32(method + 4))
                    proto = u32(76) + struct.unpack_from('<H', data, method + 2)[0] * 12
                    parameters = u32(proto + 8)
                    args = [] if not parameters else [typename(struct.unpack_from('<H', data, parameters + 4 + i * 2)[0]) for i in range(u32(parameters))]
                    methods[(label, '(' + ''.join(args) + ')' + typename(u32(proto + 4)))] = (access, code)
        yield name, methods


def verify(apk, mapping):
    classes = {}
    with zipfile.ZipFile(apk) as archive:
        entry = archive.read('META-INF/xposed/java_init.list').decode().strip()
        assert entry == 'dev.autohotspot.CpeModule', 'Xposed entry metadata changed'
        for name in archive.namelist():
            if re.fullmatch(r'classes\d*\.dex', name): classes.update(dex_classes(archive.read(name)))
    for name in ('RootMain', 'RootUpdateWorker'):
        access, code = classes['Ldev/autohotspot/' + name + ';'][('main', '([Ljava/lang/String;)V')]
        assert access & 9 == 9 and code, 'Missing public static main: ' + name
    module = classes['Ldev/autohotspot/CpeModule;']
    assert module[('<init>', '()V')][0] & 1, 'Missing public module constructor'
    for callback in ('onPackageReady', 'onSystemServerStarting'):
        assert any(name == callback and access & 1 and code for (name, _), (access, code) in module.items()), 'Missing module callback: ' + callback
    assert not any(name.startswith('Lio/github/libxposed/api/') for name in classes), 'Framework API must remain compileOnly'
    renamed = re.findall(r'^dev\.autohotspot\.([\w$]+) -> ([\w.$]+):$', mapping.read_text(encoding='utf-8'), re.M)
    obfuscated = sum(target != 'dev.autohotspot.' + source for source, target in renamed)
    assert obfuscated >= 5, 'Expected real R8 obfuscation'
    return dict(apk=str(apk), size=apk.stat().st_size, sha256=hashlib.sha256(apk.read_bytes()).hexdigest(),
                dexClasses=len(classes), obfuscatedAppClasses=obfuscated, rootEntrypoints=True, moduleEntrypoint=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--mapping', type=Path, required=True)
    parser.add_argument('--out', type=Path)
    args = parser.parse_args()
    result = verify(args.apk, args.mapping)
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps(result))
