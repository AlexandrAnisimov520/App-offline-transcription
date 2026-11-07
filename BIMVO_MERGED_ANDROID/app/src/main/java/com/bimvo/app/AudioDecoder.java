package com.bimvo.app;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.function.BooleanSupplier;

/** Platform decoding to 16 kHz mono signed PCM16 on disk, never materialising the full file in RAM. */
final class AudioDecoder {
    interface Progress { void onProgress(int percent); }

    static long decode(File source, File output, BooleanSupplier cancelled, Progress progress) throws IOException {
        if (!source.isFile() || source.length() == 0) throw new IOException("Аудиофайл отсутствует или пуст");
        File partial = new File(output.getAbsolutePath() + ".part");
        partial.delete();
        boolean success = false;
        try {
            long samples;
            try {
                samples = decodePlatform(source, partial, cancelled, progress);
            } catch (IOException | RuntimeException platformError) {
                partial.delete();
                if (!isWav(source)) throw new IOException("Неподдерживаемый или повреждённый аудиоформат", platformError);
                samples = decodeWav(source, partial, cancelled);
            }
            if (samples < 320 || partial.length() != samples * 2L)
                throw new IOException("Не удалось декодировать звуковые данные");
            if (!partial.renameTo(output)) throw new IOException("Не удалось сохранить PCM");
            success = true;
            return samples;
        } finally {
            if (!success) partial.delete();
        }
    }

    private static boolean isWav(File source) {
        try (InputStream in = new FileInputStream(source)) {
            byte[] h = new byte[12];
            if (in.read(h) != h.length) return false;
            return h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                    && h[8] == 'W' && h[9] == 'A' && h[10] == 'V' && h[11] == 'E';
        } catch (IOException e) { return false; }
    }

    private static long decodePlatform(File source, File pcm, BooleanSupplier cancel, Progress progress) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(source.getAbsolutePath());
            int track = -1;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { track = i; break; }
            }
            if (track < 0) throw new IOException("Аудиодорожка отсутствует");
            extractor.selectTrack(track);
            MediaFormat sourceFormat = extractor.getTrackFormat(track);
            String mime = sourceFormat.getString(MediaFormat.KEY_MIME);
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(sourceFormat, null, null, 0);
            codec.start();
            boolean inputDone = false, outputDone = false;
            int rate = -1, channels = -1, encoding = AudioFormat.ENCODING_PCM_16BIT;
            long samples = 0, audioDuration = sourceFormat.containsKey(MediaFormat.KEY_DURATION)
                    ? sourceFormat.getLong(MediaFormat.KEY_DURATION) : -1;
            try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(pcm), 65536)) {
                PcmResampler converter = null;
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                int idle = 0;
                while (!outputDone) {
                    if (cancel.getAsBoolean()) throw new java.io.InterruptedIOException("Обработка отменена");
                    if (!inputDone) {
                        int input = codec.dequeueInputBuffer(10000);
                        if (input >= 0) {
                            ByteBuffer buffer = codec.getInputBuffer(input);
                            if (buffer == null) throw new IOException("Нет входного буфера декодера");
                            buffer.clear();
                            int length = extractor.readSampleData(buffer, 0);
                            if (length < 0) {
                                codec.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputDone = true;
                            } else {
                                long timestamp = Math.max(0, extractor.getSampleTime());
                                codec.queueInputBuffer(input, 0, length, timestamp, 0);
                                extractor.advance();
                                if (audioDuration > 0)
                                    progress.onProgress((int) Math.min(99, timestamp * 100L / audioDuration));
                            }
                        }
                    }
                    int output = codec.dequeueOutputBuffer(info, 10000);
                    if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat format = codec.getOutputFormat();
                        int nextRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                        int nextChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                        if (nextChannels <= 0 || nextChannels > 8) throw new IOException("Неизвестное число каналов");
                        if (converter != null && (rate != nextRate || channels != nextChannels))
                            throw new IOException("Формат аудио изменился при декодировании");
                        rate = nextRate;
                        channels = nextChannels;
                        encoding = format.containsKey(MediaFormat.KEY_PCM_ENCODING)
                                ? format.getInteger(MediaFormat.KEY_PCM_ENCODING) : AudioFormat.ENCODING_PCM_16BIT;
                        if (converter == null) converter = new PcmResampler(rate, out);
                    } else if (output >= 0) {
                        if (rate < 0) {
                            MediaFormat format = codec.getOutputFormat();
                            rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                            channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                            encoding = format.containsKey(MediaFormat.KEY_PCM_ENCODING)
                                    ? format.getInteger(MediaFormat.KEY_PCM_ENCODING) : AudioFormat.ENCODING_PCM_16BIT;
                            converter = new PcmResampler(rate, out);
                        }
                        ByteBuffer data = codec.getOutputBuffer(output);
                        if (data != null && info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            data.position(info.offset);
                            data.limit(info.offset + info.size);
                            samples += writePcmFrames(data, channels, encoding, converter);
                        }
                        outputDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        codec.releaseOutputBuffer(output, false);
                        idle = 0;
                    } else if (++idle > 6000) throw new IOException("Декодер не отвечает");
                }
                if (converter == null) throw new IOException("Декодер не выдал PCM");
                return converter.finish();
            }
        } catch (java.io.InterruptedIOException e) {
            throw e;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Ошибка системного декодера", e);
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) { }
                try { codec.release(); } catch (Exception ignored) { }
            }
            extractor.release();
        }
    }

    private static long writePcmFrames(ByteBuffer data, int channels, int encoding,
                                       PcmResampler converter) throws IOException {
        int sampleSize;
        if (encoding == AudioFormat.ENCODING_PCM_FLOAT || encoding == AudioFormat.ENCODING_PCM_32BIT) sampleSize = 4;
        else if (encoding == AudioFormat.ENCODING_PCM_24BIT_PACKED) sampleSize = 3;
        else if (encoding == AudioFormat.ENCODING_PCM_8BIT) sampleSize = 1;
        else if (encoding == AudioFormat.ENCODING_PCM_16BIT) sampleSize = 2;
        else throw new IOException("Неподдерживаемая разрядность PCM: " + encoding);
        int frameSize = sampleSize * channels;
        if (data.remaining() % frameSize != 0) throw new IOException("Повреждённый PCM-буфер");
        long count = 0;
        while (data.remaining() >= frameSize) {
            float sum = 0;
            for (int i = 0; i < channels; i++) sum += getSample(data, encoding);
            converter.add(sum / channels);
            count++;
        }
        return count;
    }

    private static float getSample(ByteBuffer buffer, int encoding) {
        int b0 = buffer.get() & 0xff;
        if (encoding == AudioFormat.ENCODING_PCM_8BIT) return (b0 - 128) / 128f;
        int b1 = buffer.get() & 0xff;
        int value = b0 | (b1 << 8);
        if (encoding == AudioFormat.ENCODING_PCM_16BIT) return (short) value / 32768f;
        int b2 = buffer.get() & 0xff;
        value |= (b2 << 16);
        if (encoding == AudioFormat.ENCODING_PCM_24BIT_PACKED) return (value << 8 >> 8) / 8388608f;
        int b3 = buffer.get() & 0xff;
        value |= b3 << 24;
        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            float f = Float.intBitsToFloat(value);
            return Float.isFinite(f) ? Math.max(-1f, Math.min(1f, f)) : 0f;
        }
        return value / 2147483648f;
    }

    /** PCM WAV fallback for containers Android's MediaExtractor cannot parse. */
    private static long decodeWav(File input, File pcm, BooleanSupplier cancelled) throws IOException {
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(input));
             BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(pcm), 65536)) {
            skipFully(in, 12);
            int channels = 0, rate = 0, bits = 0, format = 0;
            PcmResampler converter = null;
            while (true) {
                byte[] header = new byte[8];
                if (readFully(in, header, 8) != 8) break;
                String tag = new String(header, 0, 4, java.nio.charset.StandardCharsets.US_ASCII);
                long size = (header[4] & 255L) | ((header[5] & 255L) << 8)
                        | ((header[6] & 255L) << 16) | ((header[7] & 255L) << 24);
                if (size < 0 || size > Integer.MAX_VALUE * 2L) throw new IOException("Повреждённый WAV");
                if ("fmt ".equals(tag)) {
                    if (size < 16 || size > 1024) throw new IOException("Некорректный WAV fmt");
                    byte[] chunk = new byte[(int) size];
                    if (readFully(in, chunk, chunk.length) != chunk.length) throw new IOException("Повреждённый WAV fmt");
                    format = u16(chunk, 0); channels = u16(chunk, 2);
                    rate = (int) ((chunk[4] & 255L) | ((chunk[5] & 255L) << 8)
                            | ((chunk[6] & 255L) << 16) | ((chunk[7] & 255L) << 24));
                    bits = u16(chunk, 14);
                    if (format == 0xfffe && chunk.length >= 40) format = u16(chunk, 24);
                    if (!((format == 1 && (bits == 8 || bits == 16 || bits == 24 || bits == 32))
                            || (format == 3 && bits == 32)) || channels < 1 || channels > 8)
                        throw new IOException("Этот WAV PCM не поддерживается");
                    converter = new PcmResampler(rate, out);
                } else if ("data".equals(tag)) {
                    if (converter == null) throw new IOException("В WAV отсутствует fmt перед data");
                    int bytes = bits / 8;
                    int frame = bytes * channels;
                    byte[] block = new byte[frame];
                    long left = size;
                    while (left >= frame) {
                        if (cancelled.getAsBoolean()) throw new java.io.InterruptedIOException("Отменено");
                        if (readFully(in, block, frame) != frame) throw new IOException("Обрезанные WAV данные");
                        left -= frame;
                        float sum = 0;
                        for (int c = 0; c < channels; c++) {
                            int offset = c * bytes;
                            int value = 0;
                            for (int b = 0; b < bytes; b++) value |= (block[offset + b] & 255) << (8 * b);
                            if (format == 3) sum += Float.intBitsToFloat(value);
                            else if (bits == 8) sum += ((value & 255) - 128) / 128f;
                            else sum += (float) ((value << (32 - bits) >> (32 - bits)) / Math.pow(2d, bits - 1));
                        }
                        converter.add(Math.max(-1f, Math.min(1f, sum / channels)));
                    }
                    skipFully(in, left);
                    return converter.finish();
                } else skipFully(in, size);
                if ((size & 1) != 0) skipFully(in, 1);
            }
            throw new IOException("В WAV отсутствуют аудиоданные");
        }
    }

    private static int u16(byte[] b, int p) { return (b[p] & 255) | ((b[p + 1] & 255) << 8); }
    static float[] readPcm(RandomAccessFile audio, long start, long end) throws IOException {
        long samples = end - start;
        if (start < 0 || samples < ChunkPlanner.MIN_CHUNK || samples > ChunkPlanner.LIMIT)
            throw new IllegalArgumentException("Invalid ASR chunk size");
        float[] signal = new float[(int) samples];
        audio.seek(start * 2);
        byte[] read = new byte[Math.min(65536, (int) samples * 2)];
        for (int pos = 0; pos < signal.length;) {
            int count = Math.min(signal.length - pos, read.length / 2);
            audio.readFully(read, 0, count * 2);
            for (int j = 0; j < count; j++) {
                int at = j * 2;
                signal[pos + j] = (short) ((read[at] & 255) | (read[at + 1] << 8)) / 32768f;
            }
            pos += count;
        }
        return signal;
    }

    private static int readFully(InputStream in, byte[] b, int wanted) throws IOException {
        int total = 0;
        while (total < wanted) {
            int n = in.read(b, total, wanted - total);
            if (n < 0) break;
            total += n;
        }
        return total;
    }
    private static void skipFully(InputStream in, long count) throws IOException {
        while (count > 0) {
            long n = in.skip(count);
            if (n <= 0) { if (in.read() < 0) throw new IOException("Unexpected EOF"); n = 1; }
            count -= n;
        }
    }
}
