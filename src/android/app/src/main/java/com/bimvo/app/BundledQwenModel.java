package com.bimvo.app;

import android.content.Context;
import android.content.res.AssetManager;
import android.system.Os;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Called on the Qwen worker, which serializes model preparation and generation. */
final class BundledQwenModel {
    private static final String NAME = "Qwen3-1.7B-Q4_K_M.gguf";
    private static final long SIZE = 1_282_439_264L;
    private static final String SHA256 = "d2387ca2dbfee2ffabce7120d3770dadca0b293052bc2f0e138fdc940d9bc7b5";

    private BundledQwenModel() {}

    static File file(Context context) {
        return new File(context.getNoBackupFilesDir(), "models/" + SHA256.substring(0, 12) + "/" + NAME);
    }

    static boolean ready(File file) {
        return file.isFile() && file.length() == SIZE;
    }

    static void prepare(Context context) throws IOException {
        File target = file(context);
        if (ready(target)) return;
        File directory = target.getParentFile();
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new IOException("Не удалось создать папку модели.");
        File partial = new File(directory, NAME + ".part");
        // A previous process can have stopped during the first copy.
        if (partial.exists() && !partial.delete())
            throw new IOException("Не удалось удалить незавершённую копию модели.");
        if (directory.getUsableSpace() < SIZE + 16L * 1024 * 1024)
            throw new IOException("Освободите ещё место. Для подготовки модели нужно около 1,3 ГБ.");

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long copied = 0;
            try (InputStream input = context.getAssets().open("models/" + NAME, AssetManager.ACCESS_STREAMING);
                 FileOutputStream output = new FileOutputStream(partial)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    copied += count;
                    if (copied > SIZE) throw new IOException("Некорректный размер встроенной модели.");
                    output.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                }
                if (copied != SIZE || !SHA256.equals(hex(digest.digest())))
                    throw new IOException("Проверка встроенной модели не прошла. Переустановите APK.");
                output.getFD().sync();
            }
            Os.rename(partial.getAbsolutePath(), target.getAbsolutePath());
        } catch (NoSuchAlgorithmException | android.system.ErrnoException e) {
            throw new IOException("Не удалось подготовить встроенную модель.", e);
        } finally {
            if (partial.isFile()) partial.delete();
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(Character.forDigit((value >>> 4) & 15, 16));
            result.append(Character.forDigit(value & 15, 16));
        }
        return result.toString();
    }
}
