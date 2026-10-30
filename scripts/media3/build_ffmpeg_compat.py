"""Recompile the FFmpeg audio renderer for Media3 1.11.1, preserving JNI/AV3A.

Input is the project's 16 KB aligned FFmpeg AAR from before the Media3 upgrade.
Only FfmpegAudioRenderer.class is replaced; all native libraries and decoder
classes retain their original bytes and JNI interfaces.
"""
import argparse
import hashlib
import io
from pathlib import Path
import os
import subprocess
import tempfile
import zipfile

from build_hls_compat import archive

CLASS = "androidx/media3/decoder/ffmpeg/FfmpegAudioRenderer"
INPUT_SHA256 = "3de0b39bc1aa36accb49c62f7189ad4612c8923f0584f72b0b8088ca0410ff26"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--aar", type=Path, required=True)
    parser.add_argument("--classpath", type=Path, required=True)
    parser.add_argument("--javac", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    original = args.aar.read_bytes()
    if hashlib.sha256(original).hexdigest() != INPUT_SHA256:
        raise ValueError("Unexpected input: use the pre-upgrade 16 KB FFmpeg AAR")
    with zipfile.ZipFile(io.BytesIO(original)) as aar:
        entries = {name: aar.read(name) for name in aar.namelist()}
    with zipfile.ZipFile(io.BytesIO(entries["classes.jar"])) as jar:
        classes = {name: jar.read(name) for name in jar.namelist()}
    with tempfile.TemporaryDirectory(prefix="mytv-ffmpeg-") as directory:
        work = Path(directory)
        upstream = work / "upstream.jar"
        upstream.write_bytes(entries["classes.jar"])
        output = work / "classes"
        output.mkdir()
        source = Path(__file__).parent / "ffmpeg/FfmpegAudioRenderer.java"
        classpath = str(upstream) + os.pathsep + args.classpath.read_text(encoding="utf-8-sig").strip()
        arguments = ["-proc:none", "-encoding", "UTF-8", "--release", "8",
                     "-classpath", classpath, "-d", str(output), str(source.resolve())]
        argfile = work / "javac.args"
        argfile.write_text("\n".join('"' + arg.replace('\\', '/').replace('"', '\\"') + '"'
                                     for arg in arguments), encoding="utf-8")
        subprocess.run([args.javac, "-J-Dfile.encoding=UTF-8", "@" + str(argfile)], check=True)
        replacement = output / (CLASS + ".class")
        classes[CLASS + ".class"] = replacement.read_bytes()
        entries["classes.jar"] = archive(classes)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(archive(entries))
    print("Built", args.output.name, hashlib.sha256(args.output.read_bytes()).hexdigest())


if __name__ == "__main__":
    main()
