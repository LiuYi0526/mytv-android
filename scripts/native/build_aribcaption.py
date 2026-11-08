#!/usr/bin/env python3
"""Build the pinned ARIB decoder/renderer for four ABIs using Linux NDK r27d."""
import argparse
import hashlib
import io
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

ARIB_SHA256 = '709ca0d5c4076de645dc8a1f1546c5c4c1214058a0ee86cab061a94221e366b4'
FREETYPE_SHA256 = '3f624983444f9e9149707853a792dc5a897f3afc9f23a9b8669c03ac564779d1'

def extract(source, target, digest):
    if hashlib.sha256(source.read_bytes()).hexdigest() != digest:
        raise ValueError('Unexpected pinned source: ' + source.name)
    with zipfile.ZipFile(source) as archive:
        for name in archive.namelist():
            path = Path(name)
            if path.is_absolute() or '..' in path.parts:
                raise ValueError('Invalid source path')
        archive.extractall(target)
        return target / archive.namelist()[0].split('/')[0]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('source-zip', 'freetype-zip', 'ndk', 'cmake', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    if '27.3.13750724' not in (args.ndk / 'source.properties').read_text():
        raise ValueError('Use NDK r27d')
    entries = {}
    with tempfile.TemporaryDirectory(prefix='liuguang-arib-') as directory:
        root = Path(directory)
        arib = extract(args.source_zip, root / 'arib', ARIB_SHA256)
        freetype = extract(args.freetype_zip, root / 'freetype', FREETYPE_SHA256)
        wrapper = root / 'wrapper'
        shutil.copytree(Path(__file__).resolve().parent / 'arib', wrapper)
        for abi in ('armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64'):
            build = root / abi
            subprocess.run([str(args.cmake), '-S', str(wrapper), '-B', str(build),
                '-DCMAKE_BUILD_TYPE=Release', '-DANDROID_PLATFORM=android-23', '-DANDROID_ABI=' + abi,
                '-DANDROID_STL=c++_static', '-DCMAKE_TOOLCHAIN_FILE=' + str(args.ndk / 'build/cmake/android.toolchain.cmake'),
                '-DARIB_SOURCE=' + str(arib), '-DFETCHCONTENT_SOURCE_DIR_FREETYPE=' + str(freetype)], check=True)
            subprocess.run([str(args.cmake), '--build', str(build), '--parallel', '2'], check=True)
            library = build / 'libliuguang_arib.so'
            subprocess.run([str(args.ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip'), '--strip-unneeded', str(library)], check=True)
            entries[f'jni/{abi}/libliuguang_arib.so'] = library.read_bytes()
        entries['META-INF/licenses/libaribcaption/LICENSE'] = (arib / 'LICENSE').read_bytes()
        entries['META-INF/licenses/libaribcaption/ISC.txt'] = (arib / 'include/aribcaption/caption.hpp').read_bytes().split(b'#ifndef')[0]
        entries['META-INF/licenses/freetype/FTL.TXT'] = (freetype / 'docs/FTL.TXT').read_bytes()
        entries['META-INF/licenses/freetype/LICENSE.TXT'] = (freetype / 'LICENSE.TXT').read_bytes()
        entries['META-INF/licenses/libaribcaption/tinyxml2-LICENSE'] = (arib / 'src/base/tinyxml2.h').read_bytes().split(b'#ifndef')[0]
    entries['AndroidManifest.xml'] = b'<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="io.github.liuyi0526.arib"><uses-sdk android:minSdkVersion="23"/></manifest>'
    jar = io.BytesIO()
    with zipfile.ZipFile(jar, 'w') as archive:
        archive.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\n\n')
    entries['classes.jar'] = jar.getvalue()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.output, 'w', zipfile.ZIP_DEFLATED) as archive:
        for name, data in sorted(entries.items()):
            entry = zipfile.ZipInfo(name, (2026, 11, 7, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(entry, data)
    print('Built', args.output.name, hashlib.sha256(args.output.read_bytes()).hexdigest())

if __name__ == '__main__':
    main()
