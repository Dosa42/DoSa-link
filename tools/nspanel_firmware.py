import argparse
import hashlib
import json
from pathlib import Path
import struct

CONTROLLER_SHA = '976d6957593c18577fd58b988f9a52e92540c56d5391b7a61e1ac36e60cde851'


def sha(data):
    return hashlib.sha256(data).hexdigest()


def wrap(source, output):
    data = source.read_bytes()
    if sha(data) != CONTROLLER_SHA or data[0] != 0xe9:
        raise ValueError('Controller input identity mismatch')
    count = data[1]
    entry = struct.unpack_from('<I', data, 4)[0]
    cursor = 24
    segments = []
    names = b'\0.shstrtab\0'
    for index in range(count):
        address, size = struct.unpack_from('<II', data, cursor)
        cursor += 8
        if cursor + size > len(data):
            raise ValueError('Segment outside image')
        executable = address >= 0x40080000
        writable = 0x3ff00000 <= address < 0x40000000
        name = ('.text' if executable else '.data' if writable else '.rodata') + str(index)
        name_offset = len(names)
        names += name.encode() + b'\0'
        segments.append({'address': address, 'sourceOffset': cursor, 'bytes': size, 'name': name, 'nameOffset': name_offset, 'flags': 5 if executable else 6 if writable else 4, 'sectionFlags': 6 if executable else 3 if writable else 2})
        cursor += size
    image = bytearray(52 + 32 * count)
    for segment in segments:
        image.extend(b'\0' * (-len(image) % 4))
        segment['elfOffset'] = len(image)
        block = data[segment['sourceOffset']:segment['sourceOffset'] + segment['bytes']]
        image.extend(block)
        segment['sha256'] = sha(block)
    names_offset = len(image)
    image.extend(names)
    image.extend(b'\0' * (-len(image) % 4))
    section_offset = len(image)
    image.extend(bytes(40))
    for segment in segments:
        image.extend(struct.pack('<IIIIIIIIII', segment['nameOffset'], 1, segment['sectionFlags'], segment['address'], segment['elfOffset'], segment['bytes'], 0, 0, 4, 0))
    image.extend(struct.pack('<IIIIIIIIII', 1, 3, 0, 0, names_offset, len(names), 0, 0, 1, 0))
    struct.pack_into('<16sHHIIIIIHHHHHH', image, 0, b'\x7fELF\x01\x01\x01' + bytes(9), 2, 94, 1, entry, 52, section_offset, 0, 52, 32, count, 40, count + 2, count + 1)
    for index, segment in enumerate(segments):
        struct.pack_into('<IIIIIIII', image, 52 + index * 32, 1, segment['elfOffset'], segment['address'], segment['address'], segment['bytes'], segment['bytes'], segment['flags'], 4)
        assert image[segment['elfOffset']:segment['elfOffset'] + segment['bytes']] == data[segment['sourceOffset']:segment['sourceOffset'] + segment['bytes']]
    output.write_bytes(image)
    receipt = {'source': str(source), 'sourceSha256': sha(data), 'wrapper': str(output), 'wrapperSha256': sha(image), 'entry': hex(entry), 'machine': 'EM_XTENSA (94)', 'segments': segments, 'originalModified': False}
    output.with_suffix('.json').write_text(json.dumps(receipt, indent=2) + '\n')
    return receipt



def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    print(json.dumps(wrap(args.source, args.output)))


if __name__ == "__main__":
    main()
