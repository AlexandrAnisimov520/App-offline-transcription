"""Evaluate automatic sherpa diarization; reference labels never enter inference."""
import os
import ctypes
import argparse
import csv
import hashlib
import json
from pathlib import Path
import sys
import time
import wave
import xml.etree.ElementTree as ET
import zipfile
import numpy as np


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--audio', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--meeting')
    p.add_argument('--annotations', type=Path, default=Path('D:/BIMVO-AMI/ami_public_manual_1.6.2.zip'))
    p.add_argument('--seconds', type=float)
    p.add_argument('--threshold', type=float, default=.85)
    p.add_argument('--runtime', default='D:/BIMVO-diarization-runtime-v2')
    p.add_argument('--segmentation', default='D:/ProtocolVoice-Codex-25s/app/src/main/assets/asr/segmentation.onnx')
    p.add_argument('--embedding', default='D:/Nuclear-hack-Bimboteam/diarization_models/eres2net_voxceleb.onnx')
    args = p.parse_args()
    sys.path.insert(0, args.runtime)
    sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'speaker_benchmark'))
    dll_handle = os.add_dll_directory(str(Path(args.runtime) / 'Scripts')) if os.name == 'nt' else None
    if os.name == 'nt': ctypes.WinDLL(str(Path(args.runtime) / 'Scripts/onnxruntime.dll'))
    import sherpa_onnx as sherpa
    from score import score
    with wave.open(str(args.audio)) as audio:
        if (audio.getnchannels(), audio.getsampwidth(), audio.getframerate()) != (1, 2, 16000):
            raise ValueError('Expected mono PCM16/16 kHz WAV')
        samples = np.frombuffer(audio.readframes(audio.getnframes()), dtype='<i2').astype(np.float32) / 32768
    if args.seconds:
        samples = samples[:round(args.seconds * 16000)]
    duration = len(samples) / 16000
    config = sherpa.OfflineSpeakerDiarizationConfig(
        segmentation=sherpa.OfflineSpeakerSegmentationModelConfig(
            pyannote=sherpa.OfflineSpeakerSegmentationPyannoteModelConfig(model=args.segmentation, window_shift_ratio=.1), num_threads=2),
        embedding=sherpa.SpeakerEmbeddingExtractorConfig(model=args.embedding, num_threads=2),
        clustering=sherpa.FastClusteringConfig(num_clusters=-1, threshold=args.threshold),
        min_duration_on=.1, min_duration_off=.1)
    if not config.validate():
        raise ValueError('Invalid sherpa config')
    diarizer = sherpa.OfflineSpeakerDiarization(config)
    last = [-1]
    def progress(done, total):
        value = int(done * 10 / max(1, total))
        if value != last[0]:
            print(f'{args.audio.stem}: {value * 10}%', flush=True)
            last[0] = value
        return 0
    started = time.perf_counter()
    result = diarizer.process(samples, callback=progress).sort_by_start_time()
    elapsed = time.perf_counter() - started
    prediction = [(max(0, float(r.start)), min(duration, float(r.end)), f'speaker_{r.speaker + 1}')
                  for r in result if min(duration, r.end) > max(0, r.start)]
    args.output.mkdir(parents=True, exist_ok=True)
    with (args.output / 'prediction.csv').open('w', encoding='utf-8', newline='') as stream:
        writer = csv.writer(stream); writer.writerow(['start', 'end', 'speaker']); writer.writerows(prediction)
    report = {'audio': str(args.audio), 'duration': duration, 'threshold': args.threshold, 'num_clusters': -1,
              'seconds': elapsed, 'rtf': elapsed / duration, 'speakers': len({r[2] for r in prediction}),
              'segments': len(prediction), 'backend': 'sherpa-onnx ' + sherpa.__version__, 'metrics': {},
              'protocol': 'zero collar; overlaps included; optimal global mapping; full supplied audio extent',
              'hashes': {p: hashlib.file_digest(open(p, 'rb'), 'sha256').hexdigest()
                         for p in [args.embedding, args.segmentation]}}
    if args.meeting:
        with zipfile.ZipFile(args.annotations) as archive:
            for kind in ['segments', 'words']:
                reference = []
                for speaker in 'ABCD':
                    local = []
                    for element in ET.fromstring(archive.read(f'{kind}/{args.meeting}.{speaker}.{kind}.xml')):
                        if kind == 'words':
                            if element.tag != 'w' or element.get('punc') == 'true': continue
                            a, b = element.get('starttime'), element.get('endtime')
                        else: a, b = element.get('transcriber_start'), element.get('transcriber_end')
                        if a is not None and b is not None and min(duration, float(b)) > max(0, float(a)):
                            local.append((max(0, float(a)), min(duration, float(b))))
                    merged = []
                    for a, b in sorted(local):
                        if merged and a <= merged[-1][1]: merged[-1][1] = max(merged[-1][1], b)
                        else: merged.append([a, b])
                    reference.extend((a, b, 'AMI_' + speaker) for a, b in merged)
                report['metrics'][kind] = score(sorted(reference), prediction)
    (args.output / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False), flush=True)


if __name__ == '__main__':
    main()
