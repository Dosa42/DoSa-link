import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
SEED = ROOT / '.build/debugger-seed'

def digest(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()

def main():
    if os.environ.get('GITHUB_ACTIONS') != 'true':
        raise RuntimeError('Debugger builds run only in GitHub Actions')
    SEED.mkdir(parents=True, exist_ok=True)
    ndk = Path(os.environ['ANDROID_HOME']) / 'ndk/27.2.12479018'
    llvm = ndk / 'toolchains/llvm/prebuilt/linux-x86_64'
    includes = ROOT / '.build/debugger-include'
    includes.mkdir(exist_ok=True)
    shutil.copyfile(Path(os.environ['JAVA_HOME']) / 'include/jvmti.h', includes / 'jvmti.h')
    library = SEED / 'libworkshop_debug.so'
    with (ROOT / 'evidence/debugger-build.log').open('w') as log:
        command = [str(llvm / 'bin/aarch64-linux-android26-clang++'), '-std=c++17', '-O0', '-g', '-fPIC', '-shared', '-static-libstdc++', '-fno-omit-frame-pointer', '-fno-optimize-sibling-calls', '-Wall', '-Wextra', '-Werror', '-I', str(includes), '-Wl,-z,max-page-size=16384', '-Wl,--build-id=sha1', str(ROOT / 'debugger/agent.cpp'), '-ldl', '-o', str(library)]
        subprocess.run(command, check=True, stdout=log, stderr=subprocess.STDOUT)
        subprocess.run([str(llvm / 'bin/aarch64-linux-android26-clang'), '-O2', '-fPIE', '-pie', '-Wall', '-Wextra', '-Werror', '-Wl,-z,max-page-size=16384', str(ROOT / 'debugger/launcher.c'), '-o', str(SEED / 'libworkshop_lldb_launch.so')], check=True, stdout=log, stderr=subprocess.STDOUT)
    servers = list((llvm / 'lib/clang').glob('*/lib/linux/aarch64/lldb-server'))
    if len(servers) != 1:
        raise RuntimeError('Expected one NDK ARM64 lldb-server: ' + str(servers))
    shutil.copyfile(servers[0], SEED / 'libworkshop_lldb.so')
    shutil.copyfile(ndk / 'NOTICE', SEED / 'NOTICE')
    symbols = []
    text = subprocess.check_output([str(llvm / 'bin/llvm-nm'), '-S', '-n', '--defined-only', str(library)], text=True)
    for line in text.splitlines():
        match = re.fullmatch(r'([0-9a-fA-F]+)\s+([0-9a-fA-F]+)\s+([a-zA-Z])\s+(.+)', line)
        if match and ('workshop_debug_' in match[4] or match[4].startswith('Java_com_ayrvokan_wireless_NativeDebug_')):
            symbols.append({'name': match[4], 'address': '0x' + match[1], 'bytes': int(match[2], 16), 'kind': match[3], 'addressKind': 'ELF virtual address before runtime load bias'})
    (SEED / 'symbols.json').write_text(json.dumps({'library': library.name, 'symbols': symbols, 'source': 'agent.cpp', 'sourceSha256': digest(ROOT / 'debugger/agent.cpp'), 'dwarfRetained': True}, indent=2) + '\n')
    shutil.copyfile(ROOT / 'debugger/agent.cpp', SEED / 'agent.cpp')
    files = {p.name: digest(p) for p in sorted(SEED.iterdir()) if p.is_file() and p.name != 'tool.json'}
    report = {'schema': 'wireless-workshop.debugger/1', 'ndk': '27.2.12479018', 'abi': 'arm64-v8a', 'javaBackend': 'ART JVMTI via Debug.attachJvmtiAgent; API28+', 'nativeBackend': 'Bundled NDK lldb-server with in-app GDB remote client', 'files': files, 'symbols': len(symbols), 'sourceSha256': digest(ROOT / 'debugger/agent.cpp')}
    (SEED / 'tool.json').write_text(json.dumps(report, indent=2) + '\n')
    (ROOT / 'evidence/debugger-package.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report), flush=True)

if __name__ == '__main__':
    main()
