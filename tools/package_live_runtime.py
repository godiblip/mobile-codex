"""Package the patched ARM64 engine for private device testing; never publish."""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def package(source, ndk, output, commit):
    if not re.fullmatch(r'[0-9a-f]{40}', commit):
        raise ValueError('A full source commit is required')
    patch_dir = ROOT / 'tools/runtime-patches'
    manifest = json.loads((patch_dir / 'live-voices-v3.json').read_text())
    patch = patch_dir / manifest['patch']
    if hashlib.sha256(patch.read_bytes()).hexdigest() != manifest['sha256']:
        raise ValueError('Voice patch checksum mismatch')
    base = subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip()
    if base != manifest['baseCommit']:
        raise ValueError('Unexpected engine base revision')
    subprocess.run(['git', '-C', str(source), 'apply', '--reverse', '--check', str(patch)], check=True)
    toolchain = ndk / 'toolchains/llvm/prebuilt/linux-x86_64'
    target = source / 'codex-rs/target/aarch64-linux-android/release'
    files = {
        'bin/codex.bin': target / 'codex',
        'bin/codex-code-mode-host': target / 'codex-code-mode-host',
        'bin/libc++_shared.so': toolchain / 'sysroot/usr/lib/aarch64-linux-android/libc++_shared.so',
        'LICENSE': source / 'npm-package/LICENSE',
        'NOTICE': source / 'npm-package/NOTICE',
    }
    output.mkdir(parents=True, exist_ok=True)
    archive = output / 'codex-live-runtime.tgz'
    with tempfile.TemporaryDirectory() as staging:
        staging = Path(staging)
        for name, original in files.items():
            destination = staging / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(original, destination)
            if name.startswith('bin/'):
                header = destination.read_bytes()[:20]
                if header[:6] != b'\x7fELF\x02\x01' or int.from_bytes(header[18:20], 'little') != 183:
                    raise ValueError('Expected an ARM64 ELF: ' + name)
                subprocess.run([str(toolchain / 'bin/llvm-strip'), '--strip-all', str(destination)], check=True)
                destination.chmod(0o755)
        if (staging / 'bin/codex.bin').read_bytes().count(b'codex-code-mode-host') != 3:
            raise ValueError('Review the changed code-mode host layout before packaging')
        with tarfile.open(archive, 'w:gz') as tar:
            for name in files:
                tar.add(staging / name, arcname='package/' + name, recursive=False)
    lock = json.loads((ROOT / 'tools/runtime-lock.json').read_text())
    lock.update({
        'version': manifest['baseVersion'] + '-live-v3.' + commit[:12],
        'url': '',
        'integrity': 'sha512-' + base64.b64encode(hashlib.sha512(archive.read_bytes()).digest()).decode(),
        'source': manifest['repository'].removesuffix('.git') + '/tree/' + base,
        'patchSource': 'https://github.com/SeeUSoon93/mobile-codex/blob/' + commit + '/tools/runtime-patches/' + manifest['patch'],
        'patchSha256': manifest['sha256'],
        'testOnly': True,
    })
    (output / 'runtime-lock.json').write_text(json.dumps(lock, indent=2) + '\n')
    (output / 'SHA256SUMS').write_text(hashlib.sha256(archive.read_bytes()).hexdigest() + '  ' + archive.name + '\n')
    shutil.copyfile(patch, output / manifest['patch'])
    print('Prepared private test runtime:', archive)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--ndk', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--commit', required=True)
    args = parser.parse_args()
    package(args.source.resolve(), args.ndk.resolve(), args.output.resolve(), args.commit)
