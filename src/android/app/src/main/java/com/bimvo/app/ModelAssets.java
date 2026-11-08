package com.bimvo.app;

import android.content.Context;
import android.content.res.AssetFileDescriptor;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

/** Direct read-only mmap of uncompressed APK assets: avoids a second 225 MB model copy. */
final class ModelAssets {
    private ModelAssets() { }
    static MappedByteBuffer map(Context context, String asset) throws IOException {
        try (AssetFileDescriptor descriptor = context.getAssets().openFd("models/" + asset);
             FileInputStream input = new FileInputStream(descriptor.getFileDescriptor())) {
            if (descriptor.getLength() <= 0) throw new IOException("Файл модели пуст: " + asset);
            return input.getChannel().map(FileChannel.MapMode.READ_ONLY,
                    descriptor.getStartOffset(), descriptor.getLength());
        } catch (IOException e) {
            throw new IOException("В APK отсутствует модель " + asset + ". Выполните scripts/prepare_models.py до сборки.", e);
        }
    }
}
