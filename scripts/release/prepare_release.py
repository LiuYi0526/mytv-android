#!/usr/bin/env python3
"""Verify signed APKs, collect the three public packages, and write release metadata."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import ssl
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.dont_write_bytecode = True
sys.path.insert(0, str(ROOT / 'scripts'))
from check_native_alignment import check_archive


def run(command, cwd=None):
    result = subprocess.run([str(x) for x in command], cwd=cwd, capture_output=True, text=True, encoding='utf8', errors='replace')
    if result.returncode:
        raise RuntimeError(result.stdout + result.stderr)
    return result.stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build-tools', type=Path, required=True)
    parser.add_argument('--repository', default='LiuYi0526/mytv-android')
    parser.add_argument('--output', type=Path, default=ROOT / 'dist')
    args = parser.parse_args()
    version = dict(line.split('=', 1) for line in (ROOT / 'version.properties').read_text().splitlines() if '=' in line)
    name, code = version['versionName'], int(version['versionCode'])
    if not re.fullmatch(r'\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?', name):
        raise ValueError('Invalid release version')
    cert = ssl.PEM_cert_to_DER_cert((ROOT / 'docs/release-certificate.pem').read_text())
    expected_digest = hashlib.sha256(cert).hexdigest()
    tools = args.build_tools.resolve()
    java_home = os.environ.get('JAVA_HOME')
    java = Path(java_home) / 'bin' / ('java.exe' if os.name == 'nt' else 'java') if java_home else 'java'
    aapt = tools / ('aapt.exe' if os.name == 'nt' else 'aapt')
    packages = {'armeabi-v7a': {'armeabi-v7a'}, 'arm64-v8a': {'arm64-v8a'},
                'universal': {'armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64'}}
    source = ROOT / 'tv/build/outputs/apk/original/release'
    args.output.mkdir(parents=True, exist_ok=True)
    sums, assets = [], []
    for abi, expected_abis in packages.items():
        filename = f'liuguang-tv-{name}-{abi}.apk'
        apk = source / filename
        if not apk.is_file():
            raise FileNotFoundError(apk)
        signature = run([java, '-jar', tools / 'lib/apksigner.jar', 'verify', '--verbose', '--print-certs', apk])
        digest = re.search(r'Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]+)', signature)
        if not digest or digest.group(1).lower() != expected_digest:
            raise ValueError('Unexpected APK signer: ' + filename)
        # Windows aapt does not reliably accept non-ASCII absolute paths in argv.
        badging = run([aapt, 'dump', 'badging', apk.name], cwd=apk.parent)
        if not re.search(r"package: name='io.github.liuyi0526.mytv' versionCode='" + str(code) + r"' versionName='" + re.escape(name) + "'", badging):
            raise ValueError('Unexpected package/version: ' + filename)
        if 'application-debuggable' in badging or "sdkVersion:'23'" not in badging:
            raise ValueError('Unexpected debug/minSdk configuration: ' + filename)
        with zipfile.ZipFile(apk) as archive:
            abis = {n.split('/')[1] for n in archive.namelist() if n.startswith('lib/') and n.endswith('.so')}
        if abis != expected_abis:
            raise ValueError(f'Unexpected ABIs in {filename}: {abis}')
        if abi != 'armeabi-v7a' and not check_archive(apk):
            raise ValueError('16 KB alignment failed: ' + filename)
        shutil.copy2(apk, args.output / filename)
        sums.append(hashlib.sha256(apk.read_bytes()).hexdigest() + '  ' + filename)
        assets.append({'name': filename, 'browser_download_url': f'https://github.com/{args.repository}/releases/download/v{name}/{filename}'})
        print('Verified:', filename)
    (args.output / 'SHA256SUMS.txt').write_text('\n'.join(sums) + '\n', encoding='utf8')
    notes = ROOT / 'docs/release-notes' / (name + '.md')
    manifest = {'tag_name': 'v' + name, 'body': notes.read_text(encoding='utf8'), 'assets': assets}
    (args.output / 'stable.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf8')
    mapping = ROOT / 'tv/build/outputs/mapping/originalRelease/mapping.txt'
    if not mapping.is_file():
        raise FileNotFoundError('R8 mapping is missing')
    symbols = args.output / 'debug-symbols'
    symbols.mkdir(exist_ok=True)
    shutil.copy2(mapping, symbols / 'mapping.txt')
    commit = run(['git', '-c', 'safe.directory=' + ROOT.as_posix(), '-C', ROOT, 'rev-parse', 'HEAD']).strip()
    dirty = bool(run(['git', '-c', 'safe.directory=' + ROOT.as_posix(), '-C', ROOT, 'status', '--porcelain']).strip())
    info = {'applicationId': 'io.github.liuyi0526.mytv', 'versionName': name, 'versionCode': code,
            'certificateSha256': expected_digest, 'sourceCommit': commit, 'workingTreeDirty': dirty}
    (args.output / 'build-info.json').write_text(json.dumps(info, indent=2) + '\n', encoding='utf8')
    shutil.copy2(ROOT / 'LICENSE', args.output / 'LICENSE')
    shutil.copy2(ROOT / 'docs/THIRD_PARTY.md', args.output / 'THIRD_PARTY.md')
    shutil.copy2(notes, args.output / 'RELEASE_NOTES.md')
    print('Release files:', args.output)


if __name__ == '__main__':
    main()
