#!/usr/bin/env python3
"""Optional manual model preparation if Gradle's automatic download fails.

Run from project root: py scripts/prepare_models.py  (Windows), or python3 scripts/prepare_models.py.
The Android application itself NEVER downloads any models or uses a network.
"""
import hashlib
import pathlib
import urllib.request

DEST = pathlib.Path(__file__).resolve().parents[1] / 'app/src/main/assets/models'
MODELS = [
    ('silero_vad.onnx', 'https://raw.githubusercontent.com/snakers4/silero-vad/60b7ffa243625ebdc1070275a29f18c87843786a/src/silero_vad/data/silero_vad.onnx', '1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3', 1_000_000),
    ('v3_e2e_ctc.int8.onnx', 'https://huggingface.co/istupakov/gigaam-v3-onnx/resolve/main/v3_e2e_ctc.int8.onnx?download=true', '2e3fcb7a7b66030336fd10c2fcfb033bd1dc7e1bf238fe5cfd83b1d0cfc9d28e', 100_000_000),
    ('v3_e2e_ctc_vocab.txt', 'https://huggingface.co/istupakov/gigaam-v3-onnx/resolve/main/v3_e2e_ctc_vocab.txt?download=true', '', 1000),
]

def valid(path, sha, minimum):
    if not path.is_file() or path.stat().st_size <= minimum:
        return False
    if not sha:
        return True
    h = hashlib.sha256()
    with path.open('rb') as file:
        for block in iter(lambda: file.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest().lower() == sha.lower()

def main():
    DEST.mkdir(parents=True, exist_ok=True)
    for filename, url, sha, minimum in MODELS:
        final = DEST / filename
        if valid(final, sha, minimum):
            print('OK:', filename)
            continue
        partial = final.with_suffix(final.suffix + '.download')
        print('Downloading:', filename, flush=True)
        try:
            request = urllib.request.Request(url, headers={'User-Agent': 'BIMVO-Android-Offline-Build'})
            with urllib.request.urlopen(request, timeout=90) as source, partial.open('wb') as out:
                for block in iter(lambda: source.read(1024 * 1024), b''):
                    out.write(block)
            if not valid(partial, sha, minimum):
                raise ValueError('SHA256/size mismatch: ' + filename)
            partial.replace(final)
        finally:
            partial.unlink(missing_ok=True)
    print('All offline assets verified.')

if __name__ == '__main__':
    main()
