package com.bimvo.app;

/** Raw diarization in PCM16/16 kHz samples. Overlapping intervals are intentional. */
public final class SpeakerInterval {
    public final long start, end;
    public final String speakerId;
    public SpeakerInterval(long start, long end, String speakerId) {
        if (start < 0 || end <= start || speakerId == null || !speakerId.matches("speaker_[1-9][0-9]*"))
            throw new IllegalArgumentException("Invalid speaker interval");
        this.start = start; this.end = end; this.speakerId = speakerId;
    }
}
