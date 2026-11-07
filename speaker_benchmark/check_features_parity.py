"""Check the new frontend against the original TorchAudio Kaldi frontend."""
import json
from pathlib import Path
import statistics
import time

import numpy as np
import torch
import torchaudio

from evaluate import CASES, load_case
from features import extract_fbank
from intervals import RATE
from runs.baseline.features import extract_fbank as original


def main():
    torch.set_num_threads(1)
    torch.set_num_interop_threads(1)
    waveforms = []
    for case in sorted((CASES / 'test').iterdir()):
        audio, turns, _ = load_case(case)
        waveforms.extend(audio[t.start:t.end] for t in turns
                         if not t.overlap and t.end - t.start >= RATE // 4)
    rng = np.random.default_rng(431)
    waveforms.extend([np.zeros(RATE, dtype=np.float32),
                      rng.normal(0, .015, RATE).astype(np.float32)])
    original(waveforms[0])
    extract_fbank(waveforms[0])
    maxima, squared = [], []
    times = [[], []]
    for wave in waveforms:
        outputs = []
        for index, fn in enumerate((original, extract_fbank)):
            start = time.perf_counter()
            outputs.append(fn(wave))
            times[index].append((time.perf_counter() - start) * 1000)
        delta = outputs[0] - outputs[1]
        maxima.append(float(np.max(np.abs(delta))))
        squared.append(float(np.mean(delta ** 2)))
    summary = dict(windows=len(waveforms), torch=torch.__version__, torchaudio=torchaudio.__version__,
                   max_abs=max(maxima), max_window_rmse=max(squared) ** .5,
                   torch_fbank_median_ms=statistics.median(times[0]),
                   native_fbank_median_ms=statistics.median(times[1]))
    Path('reports/features_parity.json').write_text(json.dumps(summary, indent=2), encoding='utf-8')
    print(json.dumps(summary, indent=2))
    if summary['max_abs'] > 1e-3:
        raise RuntimeError('Kaldi frontend difference exceeds 1e-3')


if __name__ == '__main__':
    main()
