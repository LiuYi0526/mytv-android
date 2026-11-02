#!/usr/bin/env python3
"""Rebuild the 64-bit RTMP JNI libraries with NDK r27d; preserve published Java/32-bit code."""
import argparse
import hashlib
from pathlib import Path
import subprocess
import tempfile
import zipfile

AAR_SHA256 = 'f929f7ec4689d5481dcf609429b34ed3cc046afe9a118730f78b1d69478a7edb'
SOURCE_SHA256 = '2dc71ded3ac2eceeab74625471095abe4afe4f335236a3edd0465bb1cdc94552'
SOURCE_COMMIT = '3af67842f517f9c6bbd22b961878624d0c44b713'
SOURCES = ['rtmpmuxer.c', 'librtmp-jni.c', 'librtmp/amf.c', 'librtmp/hashswf.c',
           'librtmp/log.c', 'librtmp/parseurl.c', 'librtmp/rtmp.c', 'flvmuxer/xiecc_rtmp.c']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('aar', 'source-zip', 'ndk', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    for path, expected in [(args.aar, AAR_SHA256), (args.source_zip, SOURCE_SHA256)]:
        if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            raise ValueError('Unexpected pinned input: ' + path.name)
    if '27.3.13750724' not in (args.ndk / 'source.properties').read_text():
        raise ValueError('Use NDK r27d (27.3.13750724)')
    with zipfile.ZipFile(args.aar) as archive:
        entries = {n: archive.read(n) for n in archive.namelist()}
    with tempfile.TemporaryDirectory(prefix='mytv-rtmp-') as directory:
        work = Path(directory)
        with zipfile.ZipFile(args.source_zip) as archive:
            prefix = archive.namelist()[0].split('/')[0] + '/'
            cpp = prefix + 'rtmp-client/src/main/cpp/'
            for name in archive.namelist():
                if name.startswith(cpp) and not name.endswith('/'):
                    relative = Path(name[len(cpp):])
                    if '..' in relative.parts or relative.is_absolute():
                        raise ValueError('Invalid source entry')
                    target = work / relative
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(archive.read(name))
            entries['META-INF/licenses/rtmp-client/LICENSE'] = archive.read(prefix + 'LICENSE')
            entries['META-INF/licenses/librtmp/COPYING'] = archive.read(cpp + 'librtmp/COPYING')
        binaries = args.ndk.resolve() / 'toolchains/llvm/prebuilt/linux-x86_64/bin'
        for abi, target in [('arm64-v8a', 'aarch64-linux-android'), ('x86_64', 'x86_64-linux-android')]:
            output = work / (abi + '.so')
            command = [str(binaries / (target + '21-clang')), '-shared', '-fPIC', '-O2',
                       '-std=gnu99', '-DNO_CRYPTO', '-DANDROID', '-Wno-error=implicit-function-declaration',
                       '-Wno-incompatible-pointer-types', '-I' + str(work / 'librtmp'),
                       '-Wl,-soname,librtmp-jni.so', '-Wl,-z,max-page-size=16384',
                       '-Wl,-z,common-page-size=16384', '-Wl,--build-id=sha1',
                       *[str(work / source) for source in SOURCES], '-llog', '-o', str(output)]
            subprocess.run(command, check=True)
            subprocess.run([str(binaries / 'llvm-strip'), '--strip-unneeded', str(output)], check=True)
            entries['jni/' + abi + '/librtmp-jni.so'] = output.read_bytes()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.output, 'w', zipfile.ZIP_DEFLATED) as archive:
        for name, data in sorted(entries.items()):
            info = zipfile.ZipInfo(name, (2026, 11, 2, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, data)
    print('Built', args.output.name, hashlib.sha256(args.output.read_bytes()).hexdigest())


if __name__ == '__main__':
    main()
