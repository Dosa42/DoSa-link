import argparse
import ast
from collections import Counter, defaultdict
import hashlib
import json
from pathlib import Path
import re
import struct


def name_hash(name):
    value = 0xffffffff
    for byte in name.encode('ascii')[:14].ljust(14, b'\0'):
        value ^= byte
        for unused in range(32):
            value = ((value << 1) ^ (0x04c11db7 if value & 0x80000000 else 0)) & 0xffffffff
    return value


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('source', type=Path)
    parser.add_argument('analysis', type=Path)
    parser.add_argument('attribute_source', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    raw = args.source.read_bytes()
    report = json.loads(args.analysis.read_text())
    if hashlib.sha256(raw).hexdigest() != report['sha256']:
        raise ValueError('Screen identity mismatch')
    tree = ast.parse(args.attribute_source.read_text())
    names = next(ast.literal_eval(n.value) for n in tree.body if isinstance(n, ast.AnnAssign) and isinstance(n.target, ast.Name) and n.target.id == 'APP_ATT_NAMES')
    header = report['decryptedHeader2']
    base = header['strdataaddr']
    blocks = {x['offset']: x for x in report['bytecode']['blocks']}
    ordered = report['bytecode']['blocks']
    noinit = struct.unpack_from('<I', raw, ordered[0]['offset'] + 4)[0]
    public_start = ordered[1]['offset'] + 4
    public_size = ordered[1]['bytes']
    private_base = public_size + noinit
    candidates = {'main', 'boot', 'page', 'starting', 'start', 'setting', 'settings', 'screenSaver', 'Upgrading'}
    for block in ordered:
        value = block.get('decodedCandidate')
        if isinstance(value, str):
            candidates.update(re.findall(r'[A-Za-z_][A-Za-z_0-9]{0,13}', value))
    for prefix in ['page', 'p', 't', 'b', 'n', 'x', 'va', 'v', 'tm', 'm', 'h', 'j', 'g', 'q', 's', 'c', 'r', 'z']:
        candidates.update(prefix + str(index) for index in range(128))
    hashes = defaultdict(list)
    for name in sorted(candidates):
        if len(name) <= 14:
            hashes[name_hash(name)].append(name)

    def lookup_names(block, expected):
        if block['bytes'] != expected * 6:
            raise ValueError('Name-hash table size mismatch')
        data = raw[block['offset'] + 4:block['offset'] + 4 + block['bytes']]
        entries = {}
        for crc, index in struct.iter_unpack('<IH', data):
            if index in entries or index >= expected:
                raise ValueError('Name-hash index mismatch')
            entries[index] = {'hash': hex(crc), 'candidates': hashes.get(crc, [])}
        return entries

    page_names = lookup_names(ordered[2], len(report['pages']))
    pages = []
    references = defaultdict(list)
    unresolved = []
    event_count = 0

    def chain(start):
        result = []
        cursor = start
        while True:
            block = blocks.get(cursor)
            if block is None:
                raise ValueError('Event chain escaped bytecode boundary: ' + hex(cursor))
            if block['bytes'] == 0:
                return result, cursor
            result.append(cursor)
            cursor += 4 + block['bytes']

    for page_id, source_page in enumerate(report['pages']):
        metadata = blocks[base + source_page['hexpos']]
        object_names = lookup_names(metadata, source_page['objqyt'])
        frame = blocks[metadata['offset'] + 4 + metadata['bytes']]
        frame_start = frame['offset'] + 4
        frame_size = frame['bytes']
        init_start = frame_start + frame_size
        init_chain, init_end = chain(init_start)
        record_start = base + source_page['attdataaddr_rel']
        record_end = base + report['pages'][page_id + 1]['attdataaddr_rel'] if page_id + 1 < len(report['pages']) else header['pageadd']
        if (record_end - record_start) % 24:
            raise ValueError('Attribute table stride mismatch')
        record_count = (record_end - record_start) // 24
        global_record_base = (record_start - (base + header['attdataaddr'])) // 24

        def read_memory(address, length):
            if 0 <= address and address + length <= public_size:
                offset = public_start + address
                return raw[offset:offset + length], offset, public_size - address
            relative = address - private_base
            if relative >= 0 and relative + length <= frame_size:
                offset = frame_start + relative
                return raw[offset:offset + length], offset, frame_size - relative
            raise ValueError('Uninitialized or unmapped RAM address ' + hex(address))

        objects = []
        for index in range(source_page['objstar'], source_page['objstar'] + source_page['objqyt']):
            obj = report['objects'][index]
            address = header['objxinxiadd'] + index * 232
            object_id = obj['id']
            object_name = object_names[object_id]
            label = 'p' + str(page_id) + '.o' + str(object_id)
            attributes = {}
            for slot, record_index in enumerate(obj['slots']):
                if record_index == 65535:
                    continue
                if record_index >= record_count:
                    raise ValueError('Attribute reference outside page table')
                name = names[slot] if slot < len(names) else 'slot_' + str(slot)
                offset = record_start + record_index * 24
                ram_owner, encoded, maximum, minimum, source_page_id, source_obj_id, encoding, change, packed = struct.unpack_from('<IiiiBBBBI', raw, offset)
                if (source_page_id, source_obj_id) not in [(page_id, object_id), (255, 255)]:
                    raise ValueError('Attribute owner mismatch')
                width = packed >> 8
                kind = packed & 15
                stored = {'slot': slot, 'recordIndex': record_index, 'globalRecordIndex': global_record_base + record_index, 'recordOffset': offset, 'memoryOrInline': encoded, 'storageKind': kind, 'bytes': width}
                if source_page_id == 255:
                    stored['ownerSentinel'] = [255, 255]
                references[stored['globalRecordIndex']].append(label + '.' + name)
                if packed & 0x20:
                    try:
                        data, value_offset, remaining = read_memory(encoded, width)
                        stored['valueOffset'] = value_offset
                    except ValueError as error:
                        stored['unresolved'] = str(error)
                        unresolved.append({'object': label, 'attribute': name, 'error': str(error)})
                        attributes[name] = stored
                        continue
                else:
                    data = struct.pack('<i', encoded)[:min(width, 4)]
                if kind == 15:
                    data = data.split(b'\0')[0]
                    try:
                        stored['value'] = data.decode('utf-8')
                        stored['textEncoding'] = 'utf-8'
                    except UnicodeDecodeError:
                        stored['value'] = data.decode('gb18030', errors='replace')
                        stored['textEncoding'] = 'gb18030-candidate'
                elif len(data) in (1, 2, 4):
                    stored['value'] = int.from_bytes(data, 'little', signed=kind in [8, 9])
                else:
                    stored['valueHex'] = data.hex()
                attributes[name] = stored
            events = []
            for slot, relative in enumerate(struct.unpack_from('<6I', raw, address + 4)):
                if relative == 0xffffffff:
                    continue
                absolute = base + relative
                event_blocks, terminator = chain(absolute)
                kind = ['draw', 'background', 'press', 'release', 'timer' if obj['lei'] == 51 else 'slide' if obj['lei'] == 1 else 'slot4', 'unload'][slot]
                events.append({'slot': slot, 'kindCandidate': kind, 'offset': absolute, 'blockOffsets': event_blocks, 'terminatorOffset': terminator})
                event_count += 1
            objects.append({'id': object_id, 'recordOffset': address, 'nameHash': object_name['hash'], 'nameCandidates': object_name['candidates'], 'type': obj['lei'], 'x': obj['x'], 'y': obj['y'], 'width': obj['w'], 'height': obj['h'], 'attributes': attributes, 'events': events})
        page_object = objects[0]
        pages.append({'id': page_id, 'nameHash': page_names[page_id]['hash'], 'nameCandidates': page_names[page_id]['candidates'], 'width': page_object['width'], 'height': page_object['height'], 'frameOffset': frame_start, 'frameBytes': frame_size, 'frameRamBase': private_base, 'attributeRecords': record_count, 'globalRecordBase': global_record_base, 'initBlockOffsets': init_chain, 'initTerminatorOffset': init_end, 'objects': objects})
    report = {'sourceSha256': report['sha256'], 'publicRam': {'offset': public_start, 'bytes': public_size, 'noinitBytes': noinit}, 'pageCount': len(pages), 'objectCount': sum(len(p['objects']) for p in pages), 'eventChains': event_count, 'attributeCount': sum(len(o['attributes']) for p in pages for o in p['objects']), 'pageNamesHashMatched': sum(bool(p['nameCandidates']) for p in pages), 'objectNamesHashMatched': sum(bool(o['nameCandidates']) for p in pages for o in p['objects']), 'pages': pages, 'recordReferences': dict(references), 'unresolvedAttributes': unresolved, 'attributeSourceSha256': hashlib.sha256(args.attribute_source.read_bytes()).hexdigest(), 'limitations': ['Names are dictionary candidates that match the stored 32-bit padded-name hash; hashes do not uniquely restore arbitrary source names.', 'Event role names and opcode names are compatible-family interpretations. Every reported event pointer and chain boundary is checked against the recovered bytecode.', 'Attribute aliases follow the recovered editor table. Device rendering, timers and runtime values are not executed.']}
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({k: v for k, v in report.items() if k not in ['pages', 'recordReferences', 'unresolvedAttributes', 'limitations']} | {'unresolvedAttributes': len(unresolved)}))


if __name__ == '__main__':
    main()
