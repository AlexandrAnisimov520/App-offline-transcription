"""Export a PCM/fbank/embedding parity fixture for the Android implementation."""
import json
from pathlib import Path

import soundfile as sf

from features import extract_fbank
from model_benchmark import MODEL
from pipeline import embedding_input, make_session


def main():
    folder = Path('reports/android_fixture')
    folder.mkdir(parents=True, exist_ok=True)
    audio, rate = sf.read('runs/controlled/test/clean/audio.wav', frames=16000, dtype='int16')
    sf.write(folder / 'pcm16.wav', audio, rate)
    fbank = extract_fbank(audio.astype('float32') / 32768)
    session = make_session(MODEL, 1, 'off')
    embedding = session.run(None, {embedding_input(session): fbank[None, :, :]})[0]
    fbank.astype('<f4').tofile(folder / 'fbank.f32le')
    embedding.astype('<f4').tofile(folder / 'embedding.f32le')
    (folder / 'contract.json').write_text(json.dumps(dict(
        sample_rate=rate, samples=len(audio), input='x', output='embedding',
        fbank_shape=[1, *fbank.shape], embedding_shape=list(embedding.shape),
        dtype='float32 little-endian, row-major', model=MODEL.name, graph_optimization='off',
    ), indent=2), encoding='utf-8')


if __name__ == '__main__':
    main()
