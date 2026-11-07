package com.bimvo.app;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.TextView;
import com.bimvo.llm.LlamaSession;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Activity-scoped presentation. Native work is serialized across activity recreation. */
final class QwenSummaryUi {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private final MainActivity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences saved;
    private final File model;
    private AlertDialog dialog;
    private boolean destroyed;
    private Task task;

    static void prepareBundledModel(Context context) {
        if (!BuildConfig.BUNDLED_QWEN) return;
        Context application = context.getApplicationContext();
        WORKER.execute(() -> {
            try {
                BundledQwenModel.prepare(application);
            } catch (java.io.IOException e) {
                // Opening the summary retries preparation and displays any error.
                android.util.Log.w("BimvoQwen", "Bundled model preparation failed", e);
            }
        });
    }
    private static final class Task {
        final AtomicBoolean cancelled = new AtomicBoolean();
        LlamaSession session;
        synchronized void cancel() {
            cancelled.set(true);
            if (session != null) session.cancel();
        }
        synchronized void attach(LlamaSession value) {
            session = value;
            if (cancelled.get()) session.cancel();
        }
        synchronized void release() {
            if (session != null) { session.close(); session = null; }
        }
    }
    QwenSummaryUi(MainActivity activity) {
        this.activity = activity;
        saved = activity.getSharedPreferences("qwen-summaries-v1", 0);
        model = BuildConfig.BUNDLED_QWEN ? BundledQwenModel.file(activity)
                : new File(activity.getFilesDir(), "models/Qwen3-1.7B-Q4_K_M.gguf");
    }
    void show(Recording recording) {
        if (dialog != null) dialog.dismiss();
        StringBuilder source = new StringBuilder();
        for (Utterance u : recording.transcript) if (u.hasRecognizedText())
            source.append('[').append(TranscriptText.clock(u.startMs)).append("] ")
                    .append(u.speakerId).append(": ").append(u.text).append('\n');
        final String transcript = source.toString();
        final String key = (BuildConfig.BUNDLED_QWEN ? model.getParentFile().getName() + ":" : "")
                + recording.id + ":" + fingerprint(transcript);
        TextView output = new TextView(activity);
        output.setPadding(32, 24, 32, 24);
        output.setTextIsSelectable(true);
        final String stableKey = "summary:" + recording.id;
        String previous = saved.getString(stableKey, "");
        if (previous.isEmpty()) previous = saved.getString(key, "");
        final String cached = previous;
        if (!cached.isEmpty()) saved.edit().putString(stableKey, cached).apply();
        output.setText(!cached.isEmpty() ? cached : recording.transcript.isEmpty()
                ? "Сначала дождитесь готовности расшифровки."
                : model.isFile() ? "Нажмите «Создать конспект»."
                : BuildConfig.BUNDLED_QWEN ? "Модель готовится при первом запуске. Можно нажать «Создать конспект», обработка начнётся после подготовки."
                : "Выберите Qwen3-1.7B Q4_K_M.gguf кнопкой «Модель GGUF». Файл будет скопирован во внутреннее хранилище приложения.");
        android.widget.ScrollView scroll = new android.widget.ScrollView(activity);
        scroll.addView(output);
        AlertDialog d = new AlertDialog.Builder(activity).setTitle("Конспект")
                .setView(scroll).setPositiveButton("Создать конспект", null)
                .setNeutralButton("Модель GGUF", (a, b) -> activity.pickQwenModel())
                .setNegativeButton("Закрыть", (a, b) -> { }).create();
        dialog = d;
        d.setOnDismissListener(a -> { cancel(); if (dialog == d) dialog = null; });
        d.show();
        output.setTextColor(BimvoStyle.WHITE);
        output.setTextSize(16);
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(BimvoStyle.CARD);
        background.setCornerRadius(16 * activity.getResources().getDisplayMetrics().density);
        if (d.getWindow() != null) d.getWindow().setBackgroundDrawable(background);
        if (!cached.isEmpty()) {
            showFinished(d, recording.title, cached, output);
            return;
        }
        if (BuildConfig.BUNDLED_QWEN)
            d.getButton(AlertDialog.BUTTON_NEUTRAL).setVisibility(android.view.View.GONE);
        d.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!recording.transcript.isEmpty());
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (task != null) return;
            if (!BuildConfig.BUNDLED_QWEN && !model.isFile()) {
                output.setText("Сначала выберите модель GGUF.");
                return;
            }
            Task current = new Task(); task = current;
            d.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            d.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(false);
            d.getButton(AlertDialog.BUTTON_NEGATIVE).setText("Отмена");
            d.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            output.setText(BuildConfig.BUNDLED_QWEN && !BundledQwenModel.ready(model)
                    ? "Подготовка встроенной модели…" : "Составляем конспект…");
            WORKER.execute(() -> {
                String result;
                String complete = null;
                try {
                    if (current.cancelled.get()) return;
                    if (BuildConfig.BUNDLED_QWEN) BundledQwenModel.prepare(activity);
                    if (current.cancelled.get()) return;
                    main.post(() -> {
                        if (!active(current, d)) return;
                        output.setText("Составляем конспект…");
                        watchMemory(current, d, output);
                    });
                    // Attempt inference on lower-memory phones; the runtime monitor below stops only if memory becomes critical.

                    LlamaSession session = new LlamaSession();
                    current.attach(session);
                    long[] last = {0};
                    String summary = session.generate(model.getAbsolutePath(), transcript, (utf8, tokens) -> {
                        long now = android.os.SystemClock.elapsedRealtime();
                        if (now - last[0] < 150) return;
                        last[0] = now;
                        String partial = new String(utf8, StandardCharsets.UTF_8);
                        main.post(() -> { if (active(current, d))
                            output.setText("Составляем конспект…\n\n" + partial); });
                    });
                    if (current.cancelled.get()) return;
                    if (summary.trim().isEmpty()) throw new IllegalStateException("Получен пустой конспект. Попробуйте ещё раз.");
                    // Keep the completed summary for this recording; subsequent opens offer export only.
                    if (!saved.edit().putString(key, summary).putString(stableKey, summary).commit())
                        throw new IllegalStateException("Не удалось сохранить конспект");
                    result = summary;
                    complete = summary;
                } catch (LinkageError e) {
                    result = "Нативная библиотека не загружена. Нужен 64-битный ARM64 Android-телефон и соответствующий APK: " + e.getMessage();
                } catch (Exception | OutOfMemoryError e) {
                    result = "Не удалось создать конспект: " + e.getMessage();
                } finally { current.release(); }
                final String message = result;
                final String completedSummary = complete;
                main.post(() -> {
                    if (!active(current, d)) return;
                    task = null;
                    output.setText(message);
                    if (completedSummary != null) {
                        showFinished(d, recording.title, completedSummary, output);
                        return;
                    }
                    d.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                    d.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(true);
                    d.getButton(AlertDialog.BUTTON_NEGATIVE).setText("Закрыть");
                    d.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                });
            });
        });
    }
    private void showFinished(AlertDialog d, String title, String summary, TextView output) {
        output.setText(summary);
        d.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        d.getButton(AlertDialog.BUTTON_POSITIVE).setText("Сохранить TXT");
        d.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> activity.exportSummary(title, summary));
        d.getButton(AlertDialog.BUTTON_NEUTRAL).setVisibility(android.view.View.VISIBLE);
        d.getButton(AlertDialog.BUTTON_NEUTRAL).setText("Копировать");
        d.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(true);
        d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                    activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Конспект", summary));
                android.widget.Toast.makeText(activity, "Конспект скопирован", android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        d.getButton(AlertDialog.BUTTON_NEGATIVE).setText("Закрыть");
    }

    private boolean active(Task t, AlertDialog d) {
        return !destroyed && !t.cancelled.get() && task == t && dialog == d && d.isShowing();
    }
    private void watchMemory(Task current, AlertDialog d, TextView output) {
        main.postDelayed(() -> {
            if (!active(current, d)) return;
            android.app.ActivityManager.MemoryInfo memory = new android.app.ActivityManager.MemoryInfo();
            ((android.app.ActivityManager) activity.getSystemService(android.content.Context.ACTIVITY_SERVICE))
                    .getMemoryInfo(memory);
            if (memory.lowMemory || memory.availMem < 128L * 1024 * 1024) {
                current.cancel(); task = null;
                output.setText("Генерация остановлена из-за нехватки ОЗУ. Закройте другие приложения и повторите.");
                d.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                d.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(true);
                d.getButton(AlertDialog.BUTTON_NEGATIVE).setText("Закрыть");
                d.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } else watchMemory(current, d, output);
        }, 1000);
    }
    void importModel(Uri uri) {
        if (dialog != null) dialog.dismiss();
        Task current = new Task(); task = current;
        AlertDialog d = new AlertDialog.Builder(activity).setTitle("Модель Qwen3")
                .setMessage("Копирование GGUF… Требуется место для файла модели во внутреннем хранилище.")
                .setNegativeButton("Отмена", (a,b) -> { }).create();
        dialog = d;
        d.setOnDismissListener(a -> { cancel(); if (dialog == d) dialog = null; });
        d.show();
        WORKER.execute(() -> {
            File partial = new File(model.getParentFile(), "model.part");
            String message;
            try {
                if (!model.getParentFile().isDirectory() && !model.getParentFile().mkdirs())
                    throw new IllegalStateException("Нет доступа к папке моделей");
                try (InputStream in = activity.getContentResolver().openInputStream(uri);
                     FileOutputStream out = new FileOutputStream(partial)) {
                    if (in == null) throw new IllegalStateException("Не удалось открыть GGUF");
                    byte[] b = new byte[64 * 1024]; int n; long total = 0;
                    while ((n = in.read(b)) != -1) {
                        if (current.cancelled.get()) throw new IllegalStateException("Отменено");
                        out.write(b, 0, n); total += n;
                        if (total > 1536L * 1024 * 1024) throw new IllegalStateException("Выберите модель размером не более 1,5 ГиБ");
                    }
                    out.getFD().sync();
                }
                try (FileInputStream in = new FileInputStream(partial)) {
                    byte[] magic = new byte[4];
                    if (in.read(magic) != 4 || !java.util.Arrays.equals(magic, new byte[]{'G','G','U','F'}))
                        throw new IllegalArgumentException("Это не GGUF");
                }
                if (partial.length() < 100 * 1024 * 1024) throw new IllegalArgumentException("GGUF слишком мал для Qwen3-1.7B");
                if (current.cancelled.get()) throw new IllegalStateException("Отменено");
                // Same filesystem: POSIX rename atomically replaces the old model after a complete copy.
                android.system.Os.rename(partial.getAbsolutePath(), model.getAbsolutePath());
                message = "GGUF сохранён. Откройте «Конспект» и нажмите «Создать конспект». Архитектура и целостность проверяются движком при загрузке.";
            } catch (Exception | OutOfMemoryError e) { message = "Не удалось импортировать модель: " + e.getMessage(); }
            finally { partial.delete(); }
            final String result = message;
            main.post(() -> { if (active(current, d)) { task = null; d.setMessage(result);
                d.getButton(AlertDialog.BUTTON_NEGATIVE).setText("Закрыть"); } });
        });
    }
    private static String fingerprint(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return result.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private void cancel() { if (task != null) { task.cancel(); task = null; } }
    void close() {
        destroyed = true; cancel();
        if (dialog != null) dialog.dismiss();
        main.removeCallbacksAndMessages(null);
    }
}
