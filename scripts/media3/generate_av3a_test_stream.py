"""Generate synthetic TS fixtures: no broadcast content or playable audio."""
from pathlib import Path


def section(data):
    crc = 0xffffffff
    for value in data:
        crc ^= value << 24
        for _ in range(8):
            crc = ((crc << 1) ^ (0x04c11db7 if crc & 0x80000000 else 0)) & 0xffffffff
    return bytes([0]) + data + crc.to_bytes(4, "big")


def packets(pid, payload):
    result = bytearray()
    index = 0
    while payload:
        data, payload = payload[:184], payload[184:]
        start = 0x40 if index == 0 else 0
        if len(data) == 184:
            result.extend(bytes([0x47, start | (pid >> 8), pid & 255, 0x10 | (index & 15)]) + data)
        else:
            padding = 183 - len(data)
            adaptation = bytes([padding]) + (bytes([0]) + b'\xff' * (padding - 1) if padding else b'')
            result.extend(bytes([0x47, start | (pid >> 8), pid & 255, 0x30 | (index & 15)]) + adaptation + data)
        index += 1
    return result


def pes(data):
    # PTS = 90000 (one second); timestamp adjuster makes the first sample zero.
    return bytes.fromhex('000001c0') + (len(data) + 8).to_bytes(2, 'big') + bytes.fromhex('808005210005bf21') + data


def main():
    assets = Path(__file__).resolve().parents[2] / 'tv/src/androidTest/assets'
    pat = packets(0, section(bytes.fromhex('00b00d0001c100000001e100')))
    pmt = packets(256, section(bytes.fromhex('02b0170001c10000e101f000d5e101f0000fe102f000')))
    av3a = bytes.fromhex('fff2104d2211960083') + bytes(1024 - 9)
    aac = bytes.fromhex('fff14c80041ffc') + bytes(25)
    (assets / 'aac-av3a.ts').write_bytes(pat + pmt + packets(257, pes(av3a * 4)) + packets(258, pes(aac * 4)))
    (assets / 'aac-av3a.m3u8').write_text('#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:1,\naac-av3a.ts\n#EXT-X-ENDLIST\n', encoding='utf-8')


if __name__ == '__main__':
    main()
