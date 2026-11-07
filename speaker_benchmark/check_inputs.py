from pathlib import Path
import importlib.util
p=Path(__file__).resolve().parent
for name in ('numpy','scipy','soundfile','onnxruntime','kaldi_native_fbank'):
 print(f'{name}:', 'installed' if importlib.util.find_spec(name) else 'MISSING')
print('speaker ONNX:', (p/'models/speaker_embedding_v2_basic.onnx').exists())
print('audio files:', sum(len(list((p/'audio').glob(pattern))) for pattern in ('*.wav', '*.flac')))
print('segments CSV:', len(list((p/'segments').glob('*.csv'))))
