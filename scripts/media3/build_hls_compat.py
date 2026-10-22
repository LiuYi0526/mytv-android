"""Rebuild only HlsSampleStreamWrapper in the official Media3 1.4.1 AAR.

Requires JDK 17, the original AAR/sources JAR and an Android compile classpath.
No network access; pinned input hashes prevent applying this patch to another version.
"""
import argparse
import hashlib
import io
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

CLASS = "androidx/media3/exoplayer/hls/HlsSampleStreamWrapper"
AAR_SHA256 = "43546b6b2ef0c2816f5d3af181c2179a5c1bddb9df85b7b84b3428cd37c887de"
SOURCE_SHA256 = "1c8a3923aa4235569c3bad775ad85ad3406e3521933128774c88d585229accca"


def checked(data, digest):
    if hashlib.sha256(data).hexdigest() != digest:
        raise ValueError("Unexpected upstream Media3 1.4.1 input")
    return data


def patch(source):
    # This SOURCE-retained IntDef is stripped from the published AAR.
    source = source.replace("    @HlsChunkSource.ChunkPublicationState\n", "")
    old = "  private int[] sampleQueueTrackIds;"
    new = old + "\n  private final List<Integer> audioSampleQueueIndices = new ArrayList<>();"
    assert source.count(old) == 1
    source = source.replace(old, new)
    old = "    int sampleQueueIndex = sampleQueueIndicesByType.get(type, C.INDEX_UNSET);"
    new = """    // TS MODE_HLS uses stable stream-type IDs (e.g. AAC 15 and AV3A 213).
    // Keep separate audio queues instead of discarding every audio ID after the first.
    if (type == C.TRACK_TYPE_AUDIO) {
      for (int index : audioSampleQueueIndices) {
        if (sampleQueueTrackIds[index] == id) {
          sampleQueueMappingDoneByType.add(type);
          return sampleQueues[index];
        }
      }
      // Preserve upstream remapping for a single audio track after a new extractor
      // (e.g. a fragmented-MP4 variant whose track ID changes).
      if (audioSampleQueueIndices.size() == 1 && sampleQueueMappingDoneByType.add(type)) {
        int index = audioSampleQueueIndices.get(0);
        sampleQueueTrackIds[index] = id;
        return sampleQueues[index];
      }
      // track() creates new queues only before endTracks; unknown later IDs are discarded.
      return null;
    }
""" + old
    assert source.count(old) == 1
    source = source.replace(old, new)
    old = "    sampleQueueMappingDoneByType.add(type);\n    sampleQueueIndicesByType.append(type, trackCount);"
    new = """    if (type == C.TRACK_TYPE_AUDIO) {
      audioSampleQueueIndices.add(trackCount);
    }
""" + old
    assert source.count(old) == 1
    return source.replace(old, new)


def archive(entries):
    result = io.BytesIO()
    with zipfile.ZipFile(result, "w", zipfile.ZIP_DEFLATED) as output:
        for name, data in sorted(entries.items()):
            info = zipfile.ZipInfo(name, (2024, 8, 27, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            output.writestr(info, data)
    return result.getvalue()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--aar", type=Path, required=True)
    parser.add_argument("--sources", type=Path, required=True)
    parser.add_argument("--classpath", type=Path, required=True, help="UTF-8 file with platform-separated JAR paths, including android.jar")
    parser.add_argument("--javac", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    original = checked(args.aar.read_bytes(), AAR_SHA256)
    with zipfile.ZipFile(args.sources) as sources:
        source = checked(sources.read(CLASS + ".java"), SOURCE_SHA256).decode()
    with zipfile.ZipFile(io.BytesIO(original)) as aar:
        entries = {name: aar.read(name) for name in aar.namelist()}
    with zipfile.ZipFile(io.BytesIO(entries["classes.jar"])) as jar:
        classes = {name: jar.read(name) for name in jar.namelist()}
    with tempfile.TemporaryDirectory(prefix="mytv-hls-") as directory:
        work = Path(directory)
        java = work / (CLASS + ".java")
        java.parent.mkdir(parents=True)
        java.write_text(patch(source), encoding="utf-8")
        upstream = work / "upstream.jar"
        upstream.write_bytes(entries["classes.jar"])
        output = work / "classes"
        output.mkdir()
        classpath = str(upstream) + os.pathsep + args.classpath.read_text(encoding="utf-8-sig").strip()
        # javac argfile also handles long Windows classpaths and spaces.
        arguments = ["-proc:none", "-encoding", "UTF-8", "--release", "8", "-classpath", classpath, "-d", str(output), str(java)]
        argfile = work / "javac.args"
        argfile.write_text("\n".join('"' + arg.replace('\\', '/').replace('"', '\\"') + '"' for arg in arguments), encoding="utf-8")
        subprocess.run([args.javac, "-J-Dfile.encoding=UTF-8", "@" + str(argfile)], check=True)
        classes = {name: data for name, data in classes.items()
                   if not (name == CLASS + ".class" or name.startswith(CLASS + "$"))}
        for compiled in output.rglob("*.class"):
            classes[compiled.relative_to(output).as_posix()] = compiled.read_bytes()
        entries["classes.jar"] = archive(classes)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(archive(entries))
    print("Built", args.output.name, hashlib.sha256(args.output.read_bytes()).hexdigest())


if __name__ == "__main__":
    main()
