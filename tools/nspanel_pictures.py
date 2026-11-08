import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import struct
import zlib

SOURCE_SHA = 'cb8b16964ff022ee9d4f38d5ed29a3f2ea5db07508f2fbb1961f3237ad9963e9'


def chunk(kind, data):
    return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))


def png(width, height, rgb):
    if len(rgb) != width * height * 3:
        raise ValueError('RGB buffer size mismatch')
    pixels = b''.join(b'\0' + rgb[y * width * 3:(y + 1) * width * 3] for y in range(height))
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(pixels, 9)) + chunk(b'IEND', b'')


def rgb565(pixels):
    result = bytearray()
    for value, in struct.iter_unpack('<H', pixels):
        r, g, b = value >> 11, value >> 5 & 63, value & 31
        result.extend((r << 3 | r >> 2, g << 2 | g >> 4, b << 3 | b >> 2))
    return result


def decode(record, width, height):
    mode, index, reserved1, reserved2, reserved3 = struct.unpack_from('<5I', record)
    if any((reserved1, reserved2, reserved3)):
        raise ValueError('Unmapped picture header fields')
    if mode == 0:
        if len(record) != 20 + width * height * 2 or index != 0:
            raise ValueError('Raw picture size mismatch')
        return record[20:], {'mode': 'raw-rgb565', 'rows': height, 'packets': 0}
    if mode != 1 or index < 20 or index + height * 8 > len(record):
        raise ValueError('Unmapped picture mode or index bounds')
    table = [struct.unpack_from('<II', record, index + y * 8) for y in range(height)]
    if table[0] != (20, index + height * 8):
        raise ValueError('Row stream start mismatch')
    result = bytearray()
    packet_count = 0
    literal_count = 0
    for y, (colors, counts) in enumerate(table):
        colors_end = table[y + 1][0] if y + 1 < height else index
        counts_end = table[y + 1][1] if y + 1 < height else len(record)
        if not 20 <= colors <= colors_end <= index or not index + height * 8 <= counts <= counts_end <= len(record):
            raise ValueError('Row stream bounds mismatch')
        row = bytearray()
        for expected_count in record[counts:counts_end]:
            if colors + 20 > colors_end:
                raise ValueError('Packet exceeds row stream')
            descriptor = struct.unpack_from('<I', record, colors)[0]
            runs = [1] * 8 if descriptor == 0x1111111f else [(descriptor >> (k * 4)) & 15 for k in range(8)]
            if sum(runs) != expected_count:
                raise ValueError('Packet expansion count mismatch')
            for k, count in enumerate(runs):
                pixel = record[colors + 4 + k * 2:colors + 6 + k * 2]
                row.extend(pixel * count)
            literal_count += descriptor == 0x1111111f
            packet_count += 1
            colors += 20
        if len(row) != width * 2 or colors != colors_end:
            raise ValueError('Expanded row width mismatch')
        result.extend(row)
    return result, {'mode': 'eight-color-nibble-runs', 'rows': height, 'packets': packet_count, 'literalPackets': literal_count}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('source', type=Path)
    parser.add_argument('inventory', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    raw = args.source.read_bytes()
    if hashlib.sha256(raw).hexdigest() != SOURCE_SHA:
        raise ValueError('Screen input identity mismatch')
    inventory = json.loads(args.inventory.read_text())
    args.output.mkdir(parents=True, exist_ok=True)
    pictures = []
    sheet_width, cell_width, cell_height, columns = 1024, 128, 112, 8
    sheet_height = ((len(inventory['pictures']) + columns - 1) // columns) * cell_height
    sheet = bytearray(b'\x18\x1b\x22' * sheet_width * sheet_height)
    for picture in inventory['pictures']:
        start, size = picture['dataOffset'], picture['bytes']
        pixels, checks = decode(raw[start:start + size], picture['width'], picture['height'])
        rgb = rgb565(pixels)
        encoded = png(picture['width'], picture['height'], rgb)
        name = 'picture-' + str(picture['id']).zfill(3) + '.png'
        (args.output / name).write_bytes(encoded)
        reconstructed = bytearray()
        for r, g, b in zip(rgb[::3], rgb[1::3], rgb[2::3]):
            reconstructed.extend(struct.pack('<H', (r >> 3) << 11 | (g >> 2) << 5 | b >> 3))
        if reconstructed != pixels:
            raise ValueError('RGB565 color conversion roundtrip mismatch')
        pictures.append({'id': picture['id'], 'file': name, 'width': picture['width'], 'height': picture['height'], 'inputOffset': start, 'inputBytes': size, 'checks': checks, 'rgb565Sha256': hashlib.sha256(pixels).hexdigest(), 'pngSha256': hashlib.sha256(encoded).hexdigest(), 'pngBytes': len(encoded)})
        thumb_width = min(120, picture['width'])
        thumb_height = max(1, picture['height'] * thumb_width // picture['width'])
        if thumb_height > 104:
            thumb_height = 104
            thumb_width = max(1, picture['width'] * thumb_height // picture['height'])
        cell_x = picture['id'] % columns * cell_width + (cell_width - thumb_width) // 2
        cell_y = picture['id'] // columns * cell_height + (cell_height - thumb_height) // 2
        for y in range(thumb_height):
            source_y = y * picture['height'] // thumb_height
            for x in range(thumb_width):
                source_x = x * picture['width'] // thumb_width
                source_offset = (source_y * picture['width'] + source_x) * 3
                target_offset = ((cell_y + y) * sheet_width + cell_x + x) * 3
                sheet[target_offset:target_offset + 3] = rgb[source_offset:source_offset + 3]
    (args.output / 'contact-sheet.png').write_bytes(png(sheet_width, sheet_height, sheet))
    report = {'sourceSha256': SOURCE_SHA, 'pictureCount': len(pictures), 'modes': dict(Counter(x['checks']['mode'] for x in pictures)), 'compressedPacketsValidated': sum(x['checks']['packets'] for x in pictures), 'compressedRowsValidated': sum(x['checks']['rows'] for x in pictures if x['checks']['mode'] != 'raw-rgb565'), 'rgb565ConversionRoundtripsPassed': len(pictures), 'pictures': pictures, 'limitations': ['PNG contains recovered RGB565 colors. No alpha or device-side blending behavior is asserted.', 'Derived codec is validated against all packet counts, row widths, stream boundaries and RGB565 conversion roundtrips in this vendor image; visual samples are checked separately.']}
    (args.output / 'pictures.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({k: v for k, v in report.items() if k not in ['pictures', 'limitations']}))


if __name__ == '__main__':
    main()
