from collections import Counter
from dataclasses import asdict
import hashlib
import json
from pathlib import Path
import re
import struct
import sys

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'toolchain/nextion-hmi'))
sys.path.insert(0, str(ROOT / 'toolchain/nspanel-tfttool'))
from scripts.lib.h2_cipher import decrypt
from scripts.lib.page_crc import crc32_bytewise
from scripts.lib.tft_attrs import parse_appinf1_corrected, parse_page_directory, parse_objxinxi
from scripts.lib.tft_format import parse
from TFTTool import Usercode
from NextionInstructionSets import all_instruction_sets


def main():
    source = ROOT / 'devices/nspanel/firmware/display-hmix86.tft'
    raw = source.read_bytes()
    digest = hashlib.sha256(raw).hexdigest()
    if digest != 'cb8b16964ff022ee9d4f38d5ed29a3f2ea5db07508f2fbb1961f3237ad9963e9':
        raise ValueError('HMIX86 identity mismatch')
    header = parse(raw)
    fields = parse_appinf1_corrected(header.h2)
    checks = {
        'header1CrcMatches': crc32_bytewise(0xffffffff, raw[:196]) == header.h1_crc,
        'header2CiphertextCrcMatches': crc32_bytewise(0xffffffff, raw[200:396]) == header.h2_crc,
        'header2CipherRoundtripMatches': decrypt(header.h2, header.model_crc) == raw[200:396],
        'declaredFileSizeMatches': struct.unpack_from('<I', raw, 0x3c)[0] == len(raw),
        'resourceAddressMatches': fields['resourcesfileddr'] == 65536,
        'resourceEndMatchesCodeStart': fields['resourcesfileddr'] + struct.unpack_from('<I', raw, 0x40)[0] == fields['strdataaddr'],
    }
    pages = parse_page_directory(raw, fields['pageadd'], fields['pageqyt'])
    objects = parse_objxinxi(raw, fields['objxinxiadd'], fields['objqyt'])
    checks['pageDirectoryRangeMatches'] = fields['pageadd'] + fields['pageqyt'] * 16 == fields['objxinxiadd']
    checks['objectDirectoryFits'] = fields['objxinxiadd'] + fields['objqyt'] * 232 <= len(raw) - 4
    checks['pageObjectTotalMatches'] = sum(p['objqyt'] for p in pages) == len(objects)
    checks['pageObjectIdsMatch'] = all(objects[p['objstar'] + i].id == i for p in pages for i in range(p['objqyt']))
    resources = []
    for index in range(struct.unpack_from('<I', raw, 0x38)[0]):
        offset, size, reserved = struct.unpack_from('<III', raw, fields['resourcesfileddr'] + index * 12)
        if size:
            absolute = fields['resourcesfileddr'] + offset
            if absolute + size > fields['strdataaddr']:
                raise ValueError('Resource outside resource region')
            resources.append({'slot': index, 'offset': absolute, 'bytes': size, 'sha256': hashlib.sha256(raw[absolute:absolute + size]).hexdigest(), 'reserved': reserved})
    pictures = []
    for index in range(fields['picqyt']):
        offset = fields['picxinxiadd'] + index * 24
        record = raw[offset:offset + 24]
        picture_id, relative, width, height, size = struct.unpack_from('<H2xIHHI', record, 4)
        absolute = fields['picxinxiadd'] + relative
        if picture_id != index or absolute + size > fields['strdataaddr']:
            raise ValueError('Invalid picture record')
        pictures.append({'id': picture_id, 'recordOffset': offset, 'dataOffset': absolute, 'bytes': size, 'width': width, 'height': height, 'formatBytes': record[:4].hex(), 'smallerThanRawRgb565': size < width * height * 2, 'decodedPixels': False})
    instructions = next(x['models'][100] for x in all_instruction_sets if 'nxt-1.63.3' in x['versions'])
    start = fields['strdataaddr']
    end = start + fields['staticstrBeg']
    cursor = start
    blocks = []
    errors = []
    unknowns = []
    opcodes = Counter()
    while cursor + 4 <= end:
        size = struct.unpack_from('<I', raw, cursor)[0]
        if cursor + 4 + size > end:
            raise ValueError('Bytecode block outside declared bytecode region')
        block = raw[cursor + 4:cursor + 4 + size]
        item = {'offset': cursor, 'bytes': size}
        if size:
            try:
                text = Usercode.CodeBlock(instructions, block).decoded
                item['decodedCandidate'] = text
                if isinstance(text, str):
                    unknowns.extend({'offset': cursor, 'marker': value} for value in re.findall(r'op:\d+:0x[0-9a-f]+|system:0x[0-9a-f]+', text))
                    first = re.match(r'^[a-zA-Z_]+', text)
                    if first:
                        opcodes[first.group()] += 1
            except Exception as error:
                item['decodeError'] = type(error).__name__ + ': ' + str(error)
                errors.append({'offset': cursor, 'error': item['decodeError']})
        blocks.append(item)
        cursor += 4 + size
    checks['bytecodeBlocksExactlyCoverRegion'] = cursor == end
    fonts = []
    for index in range(fields['zimoqyt']):
        offset = fields['zimoxinxiadd'] + index * 44
        relative = struct.unpack_from('<I', raw, offset + 24)[0]
        name_start = fields['zimoxinxiadd'] + relative
        name = re.match(rb'[ -~]{1,64}', raw[name_start:name_start + 64])
        fonts.append({'id': index, 'headerOffset': offset, 'dataOffset': name_start, 'nameCandidate': name.group().decode().strip() if name else None, 'rawHeaderHex': raw[offset:offset + 44].hex()})
    if not all(checks.values()):
        raise ValueError(json.dumps(checks))
    object_rows = []
    for item in objects:
        row = asdict(item)
        row.pop('raw')
        row['x'] = row['endx'] - row['w'] + 1
        row['y'] = row['endy'] - row['h'] + 1
        object_rows.append(row)
    result = {
        'input': str(source.relative_to(ROOT)), 'sha256': digest, 'bytes': len(raw),
        'model': 'NX4832F035_011', 'modelCrc': hex(header.model_crc), 'editor': 'nxt-1.63.5',
        'screen': {'width': 320, 'height': 480, 'orientation': raw[0x14]},
        'decoderSources': [json.loads((ROOT / 'toolchain/nextion-hmi/provenance.json').read_text()), json.loads((ROOT / 'toolchain/nspanel-tfttool/provenance.json').read_text())],
        'checks': checks, 'decryptedHeader2': fields, 'header2PlainHex': header.h2.hex(),
        'pages': pages, 'objects': object_rows, 'objectTypes': dict(Counter(x.lei for x in objects)),
        'resources': resources, 'pictures': pictures, 'fonts': fonts,
        'bytecode': {'start': start, 'end': end, 'blocks': blocks, 'blockCount': len(blocks), 'nonemptyBlocks': sum(x['bytes'] > 0 for x in blocks), 'decodeErrors': errors, 'unknownMarkers': unknowns, 'candidateOperations': dict(opcodes)},
        'limitations': ['Opcode names use the model-100 table shared by nxt-1.61.1 through nxt-1.65.1; nxt-1.63.5 is not explicitly listed. Decoded text is a candidate interpretation, not restored editor source.', 'Compressed picture payloads are inventoried with exact offsets; pixels are not decoded.', 'This is the recovered vendor EU candidate, not a readback or version match of the working panel.'],
        'panelWritten': False,
    }
    target = ROOT / 'devices/nspanel/decoded/tft-analysis.json'
    target.write_text(json.dumps(result, indent=2, ensure_ascii=True) + '\n')
    print(json.dumps({'report': str(target), 'checks': checks, 'pages': len(pages), 'objects': len(objects), 'pictures': len(pictures), 'fonts': len(fonts), 'blocks': len(blocks), 'decodeErrors': len(errors), 'unknownMarkers': len(unknowns)}))


if __name__ == '__main__':
    main()
