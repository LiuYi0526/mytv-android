#!/usr/bin/env python3
"""Check 64-bit ELF LOAD segments and uncompressed APK entries for 16 KB pages."""

import argparse
from pathlib import Path
import struct
import zipfile

PAGE_SIZE = 16 * 1024


def check_archive(path: Path) -> bool:
    failures = []
    checked = 0
    with zipfile.ZipFile(path) as archive, path.open("rb") as raw:
        for entry in archive.infolist():
            if not entry.filename.endswith(".so"):
                continue
            data = archive.read(entry)
            if data[:4] != b"\x7fELF":
                failures.append(f"{entry.filename}: not an ELF library")
                continue
            if data[4] != 2:  # Android's 16 KB devices use 64-bit native code.
                continue
            checked += 1
            endian = "<" if data[5] == 1 else ">"
            offset = struct.unpack_from(endian + "Q", data, 32)[0]
            size, count = struct.unpack_from(endian + "HH", data, 54)
            loads = 0
            for index in range(count):
                kind, _, file_offset, address, _, _, _, alignment = struct.unpack_from(
                    endian + "IIQQQQQQ", data, offset + size * index
                )
                if kind != 1:  # PT_LOAD
                    continue
                loads += 1
                if alignment < PAGE_SIZE or (address - file_offset) % PAGE_SIZE:
                    failures.append(
                        f"{entry.filename}: LOAD {index} alignment={alignment}, "
                        f"offset=0x{file_offset:x}, address=0x{address:x}"
                    )
            if not loads:
                failures.append(f"{entry.filename}: no LOAD segments")
            if path.suffix.lower() == ".apk" and entry.compress_type == zipfile.ZIP_STORED:
                raw.seek(entry.header_offset)
                header = raw.read(30)
                name_size, extra_size = struct.unpack_from("<HH", header, 26)
                data_offset = entry.header_offset + 30 + name_size + extra_size
                if data_offset % PAGE_SIZE:
                    failures.append(f"{entry.filename}: ZIP data offset {data_offset} is not 16 KB aligned")
    for failure in failures:
        print(f"FAIL {failure}")
    if not checked:
        print(f"FAIL {path.name}: no 64-bit libraries found")
        return False
    print(f"{path.name}: {checked} 64-bit libraries, {len(failures)} alignment errors")
    return not failures


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archives", type=Path, nargs="+", help="APK or AAR files")
    args = parser.parse_args()
    results = [check_archive(path) for path in args.archives]
    raise SystemExit(0 if all(results) else 1)
