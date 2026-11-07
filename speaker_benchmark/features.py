"""Kaldi fbank for the supplied 3D-Speaker ERes2NetV2 export."""
import kaldi_native_fbank as knf
import numpy as np

from intervals import RATE

OPTIONS = knf.FbankOptions()
OPTIONS.frame_opts.samp_freq = RATE
OPTIONS.frame_opts.frame_length_ms = 25
OPTIONS.frame_opts.frame_shift_ms = 10
OPTIONS.frame_opts.dither = 0.0
OPTIONS.mel_opts.num_bins = 80


def extract_fbank(wave, sample_rate=RATE):
    wave = np.asarray(wave, dtype=np.float32)
    if sample_rate != RATE or wave.ndim != 1:
        raise ValueError('Expected mono PCM at 16000 Hz; resample before calling')
    if len(wave) < 400:
        raise ValueError('A 25 ms frame requires 400 samples')
    fbank = knf.OnlineFbank(OPTIONS)
    # The binding accepts a float sequence. Bulk conversion avoids per-sample
    # NumPy scalar wrappers inside pybind11.
    fbank.accept_waveform(RATE, wave.tolist())
    frames = np.stack([fbank.get_frame(i) for i in range(fbank.num_frames_ready)])
    frames -= frames.mean(axis=0, keepdims=True)
    return frames
