package com.bimvo.app;

import android.app.Activity;
import android.Manifest;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import java.io.File;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.content.SharedPreferences;
import android.text.InputFilter;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import java.util.ArrayList;
import java.util.List;
import java.text.SimpleDateFormat;
import java.util.Date;
import android.text.format.Formatter;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.util.TypedValue;
import android.view.inputmethod.EditorInfo;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Arrays;
import java.text.Collator;


/** Native, offline library with explicitly labelled UI-only transcription examples. */
public class MainActivity extends Activity implements BimvoApplication.Listener {
    private enum Page { SPLASH, LOGIN, HOME, UPLOAD, PROCESSING, TRANSCRIPT, MAP, RECORDS, EXPORT, PROFILE, DETAILS, DEMOS }
    private static final int PICK_AUDIO = 100, CREATE_EXPORT = 101, PICK_TRANSCRIPT = 102, REQUEST_MICROPHONE = 103;
    private static final int[] SORT_LABELS = {
        R.string.sort_newest, R.string.sort_oldest, R.string.sort_shortest,
        R.string.sort_longest, R.string.sort_name_az, R.string.sort_name_za
    };
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Page> navigation = new ArrayDeque<>();
    private FrameLayout root;
    private Page page = Page.SPLASH;
    private BimvoApplication model;
    private SharedPreferences preferences;
    private String currentRecordId = "demo-team";
    private int recordSort;
    private String recordQuery = "", loginName = "", loginPassword = "";
    private int selectedTurn;
    private long audioPosition;
    private boolean audioSeeking;
    private TextView playbackButton;
    private boolean audioPlaying, editing;
    private AudioPlayerController realPlayer;
    private String exportFormat = "JSON";
    private int processingProgress;
    private Runnable processingTick, playbackTick;
    private ProgressBar progressBar, importProgress;
    private TextView percentLabel, playbackTime, importStatus, importFileLabel;
    private TextView[] phaseLabels;
    private View completedCta;
    private SeekBar playbackSeekBar;
    private LinearLayout intervalContainer, recordsContainer;
    private long seenNotice;
    private boolean onboardingPending;
    private int onboardingChoice = -1;
    private AlertDialog onboardingDialog;
    private QwenSummaryUi qwenSummary;
    private static final int PICK_QWEN_MODEL = 104;
    private String pendingExportToken = "";
    private static final int CREATE_SUMMARY_EXPORT = 105;
    private String pendingSummaryText = "", pendingSummaryName = "";
    private TextView recordingStatus;
    private RecordingWaveView recordingWave;
    private final Runnable recordingClock = new Runnable() {
        @Override public void run() {
            if (model == null || !model.voiceRecorder.isRecording()) return;
            updateRecordingStatus();
            handler.postDelayed(this, 250);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        model = (BimvoApplication) getApplication();
        qwenSummary = new QwenSummaryUi(this);
        realPlayer = new AudioPlayerController();
        preferences = getSharedPreferences("bimvo-settings", MODE_PRIVATE);
        recordSort = Math.max(0, Math.min(5, preferences.getInt("sort", 0)));
        getWindow().setStatusBarColor(BimvoStyle.BG);
        getWindow().setNavigationBarColor(BimvoStyle.BG);
        if (Build.VERSION.SDK_INT >= 29) getWindow().setNavigationBarContrastEnforced(false);
        getWindow().getDecorView().setSystemUiVisibility(0);
        root = new FrameLayout(this);
        root.setBackgroundColor(BimvoStyle.BG);
        setContentView(root);
        if (state != null) restore(state);
        render();
        if (page == Page.SPLASH) handler.postDelayed(() -> {
            if (page == Page.SPLASH && !isFinishing()) enterLogin();
        }, 2100);
        else if (page == Page.PROCESSING && processingProgress < 100 && current() != null && current().demo) animateProcessing();
    }

    @Override protected void onStart() {
        super.onStart();
        model.observe(this);
        onLibraryChanged();
        model.resumeInterruptedJobs();
        if (onboardingPending) showOnboarding();
        handler.removeCallbacks(recordingClock);
        if (model.voiceRecorder.isRecording()) handler.post(recordingClock);
    }

    @Override protected void onStop() {
        handler.removeCallbacks(recordingClock);
        model.removeObserver(this);
        stopPlayback();
        super.onStop();
    }

    @Override protected void onDestroy() {
        if (qwenSummary != null) qwenSummary.close();
        handler.removeCallbacksAndMessages(null);
        if (realPlayer != null) realPlayer.release();
        if (onboardingDialog != null) onboardingDialog.dismiss();
        // Recorder is application-owned: rotating the screen must not lose a recording.
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("page", page.name());
        out.putString("recordId", currentRecordId);
        out.putString("query", recordQuery);
        out.putString("loginName", loginName);
        // Demo credentials are never persisted or placed in the saved state.
        out.putString("format", exportFormat);
        out.putInt("turn", selectedTurn);
        out.putLong("audioPosition", audioPosition);
        out.putInt("processing", processingProgress);
        out.putLong("notice", seenNotice);
        out.putBoolean("onboarding", onboardingPending);
        out.putInt("onboardingChoice", onboardingChoice);
        out.putString("exportToken", pendingExportToken);
        out.putString("summaryExportText", pendingSummaryText);
        out.putString("summaryExportName", pendingSummaryName);
        String[] history = new String[navigation.size()];
        int i = 0;
        for (Page item : navigation) history[i++] = item.name();
        out.putStringArray("history", history);
    }

    private void restore(Bundle data) {
        try { page = Page.valueOf(data.getString("page", "LOGIN")); }
        catch (IllegalArgumentException ignored) { page = Page.LOGIN; }
        currentRecordId = data.getString("recordId", "demo-team");
        recordQuery = data.getString("query", "");
        loginName = data.getString("loginName", "");
        exportFormat = data.getString("format", "JSON");
        if (!Arrays.asList("JSON", "TXT", "SRT").contains(exportFormat)) exportFormat = "JSON";
        selectedTurn = Math.max(0, data.getInt("turn", 0));
        audioPosition = Math.max(0, data.getLong("audioPosition", 0));
        processingProgress = Math.max(0, Math.min(100, data.getInt("processing", 0)));
        seenNotice = data.getLong("notice", 0);
        onboardingPending = data.getBoolean("onboarding", false) && !preferences.getBoolean("onboardingDone", false);
        onboardingChoice = Math.max(-1, Math.min(2, data.getInt("onboardingChoice", -1)));
        pendingExportToken = data.getString("exportToken", "");
        pendingSummaryText = data.getString("summaryExportText", "");
        pendingSummaryName = data.getString("summaryExportName", "");
        String[] history = data.getStringArray("history");
        if (history != null) for (int i = history.length - 1; i >= 0; i--) {
            try { navigation.push(Page.valueOf(history[i])); } catch (IllegalArgumentException ignored) { }
        }
    }

    @Override public void onLibraryChanged() {
        if (isFinishing() || isDestroyed()) return;
        if (model.notice != 0 && seenNotice != model.noticeSerial) {
            seenNotice = model.noticeSerial;
            toast(model.notice);
            if ((page == Page.UPLOAD || page == Page.HOME || page == Page.RECORDS)
                    && (model.notice == R.string.import_done || model.notice == R.string.import_duplicate
                        || model.notice == R.string.voice_record_saved))
                currentRecordId = model.lastImportedId;
        }
        if (model.ready && model.find(currentRecordId) == null) {
            currentRecordId = "demo-team";
            if (page == Page.DETAILS || page == Page.TRANSCRIPT || page == Page.MAP) {
                page = Page.RECORDS;
                navigation.clear();
            }
        }
        if (page != Page.SPLASH && page != Page.LOGIN) render();
        if (model.ready) model.resumeInterruptedJobs();
        BimvoApplication.ExportDraft draft = model.claimExport();
        if (draft != null) {
            pendingExportToken = draft.token;
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE).setType(draft.mime)
                    .putExtra(Intent.EXTRA_TITLE, draft.name).putExtra(Intent.EXTRA_LOCAL_ONLY, true);
            try { startActivityForResult(intent, CREATE_EXPORT); }
            catch (ActivityNotFoundException e) {
                model.discardExport(draft.token);
                pendingExportToken = "";
                toast(R.string.picker_missing);
            }
        }
    }

    @Override public void onImportProgress() {
        if (page != Page.UPLOAD) return;
        if (importFileLabel != null) importFileLabel.setText(model.importName);
        if (importProgress != null) {
            importProgress.setIndeterminate(model.expectedBytes <= 0);
            if (model.expectedBytes > 0)
                importProgress.setProgress((int)Math.min(100, model.importedBytes * 100L / model.expectedBytes));
        }
        if (importStatus != null) importStatus.setText(model.expectedBytes > 0
                ? getString(R.string.import_copying, size(model.importedBytes), size(model.expectedBytes))
                : size(model.importedBytes));
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == CREATE_SUMMARY_EXPORT) {
            String content = pendingSummaryText;
            pendingSummaryText = "";
            pendingSummaryName = "";
            if (result == RESULT_OK && data != null && data.getData() != null && !content.isEmpty()) {
                final android.net.Uri destination = data.getData();
                final android.content.Context app = getApplicationContext();
                new Thread(() -> {
                    String message;
                    try (java.io.OutputStream stream = app.getContentResolver().openOutputStream(destination, "wt")) {
                        if (stream == null) throw new java.io.IOException("Файл недоступен");
                        stream.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        stream.flush();
                        message = "Конспект сохранён";
                    } catch (Exception e) { message = "Не удалось сохранить конспект: " + e.getMessage(); }
                    final String notice = message;
                    new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(app, notice, Toast.LENGTH_LONG).show());
                }, "bimvo-summary-export").start();
            }
            return;
        }
        if (request == PICK_QWEN_MODEL) {
            if (result == RESULT_OK && data != null && data.getData() != null)
                qwenSummary.importModel(data.getData());
            return;
        }
        if (request == PICK_AUDIO) {
            if (result == RESULT_OK && data != null && data.getData() != null)
                if (!model.importAudio(data.getData(), data.getFlags())) toast(R.string.operation_busy);
        } else if (request == PICK_TRANSCRIPT) {
            if (result == RESULT_OK && data != null && data.getData() != null)
                if (!model.importTranscriptJson(currentRecordId, data.getData())) toast(R.string.operation_busy);
        } else if (request == CREATE_EXPORT && !pendingExportToken.isEmpty()) {
            String token = pendingExportToken;
            pendingExportToken = "";
            if (result == RESULT_OK && data != null && data.getData() != null) {
                if (!model.saveExport(data.getData(), token)) {
                    model.discardExport(token);
                    toast(R.string.export_failed);
                }
            } else model.discardExport(token);
        }
    }

    private String s(int res) { return getString(res); }
    private void toast(int res) { if (res != 0) Toast.makeText(this, res, Toast.LENGTH_LONG).show(); }
    private String size(long bytes) { return Formatter.formatFileSize(this, Math.max(0, bytes)); }
    private Recording current() { return model.find(currentRecordId); }
    private String[] sortOptions() {
        String[] labels = new String[SORT_LABELS.length];
        for (int i = 0; i < labels.length; i++) labels[i] = s(SORT_LABELS[i]);
        return labels;
    }
    private void stopPlayback() {
        audioPlaying = false;
        if (realPlayer != null) realPlayer.pause();
        if (playbackTick != null) handler.removeCallbacks(playbackTick);
    }
    private void hideKeyboard() {
        View focus = getCurrentFocus();
        if (focus != null) ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(focus.getWindowToken(), 0);
        root.clearFocus();
    }
    private void enterLogin() {
        stopPlayback();
        navigation.clear();
        loginPassword = "";
        page = Page.LOGIN;
        render();
    }
    private void enterHome() {
        hideKeyboard();
        stopPlayback();
        navigation.clear();
        page = Page.HOME;
        render();
    }
    private void enterLibrary() {
        enterHome();
        if (!preferences.getBoolean("onboardingDone", false)) {
            onboardingPending = true;
            onboardingChoice = -1;
            showOnboarding();
        }
    }
    private void forward(Page next) {
        if (page == next) return;
        hideKeyboard();
        stopPlayback();
        navigation.push(page);
        page = next;
        if (next == Page.PROCESSING) processingProgress = 0;
        render();
        if (next == Page.PROCESSING && current() != null && current().demo) animateProcessing();
    }
    private void bottomTab(Page next) {
        hideKeyboard();
        stopPlayback();
        navigation.clear();
        page = next;
        render();
    }
    private void selectTextTab() {
        if (page == Page.MAP && !navigation.isEmpty() && navigation.peek() == Page.TRANSCRIPT) navigation.pop();
        stopPlayback();
        page = Page.TRANSCRIPT;
        render();
    }
    @Override public void onBackPressed() { back(); }
    private void back() {
        hideKeyboard();
        stopPlayback();
        if (page == Page.SPLASH) { enterLogin(); return; }
        if (!navigation.isEmpty()) { page = navigation.pop(); render(); }
        else if (page == Page.LOGIN) finish();
        else if (page == Page.HOME) enterLogin();
        else enterHome();
    }

    private int dp(float v) { return Math.round(getResources().getDisplayMetrics().density * v); }

    private GradientDrawable shape(int bg, int border, float corners) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(bg);
        d.setCornerRadius(dp(corners));
        if (border != Color.TRANSPARENT) d.setStroke(Math.max(1, dp(.6f)), border);
        return d;
    }

    private GradientDrawable oval(int bg, int border) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(bg);
        d.setShape(GradientDrawable.OVAL);
        if (border != Color.TRANSPARENT) d.setStroke(Math.max(1, dp(.7f)), border);
        return d;
    }

    private void clickable(View target, int bg, int border, float corners, Runnable onClick) {
        target.setBackground(new RippleDrawable(ColorStateList.valueOf(0x24ffffff),
                shape(bg, border, corners), shape(Color.WHITE, Color.TRANSPARENT, corners)));
        target.setClickable(true);
        target.setFocusable(true);
        target.setOnClickListener(v -> onClick.run());
    }

    private LinearLayout vertical() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        return box;
    }

    private LinearLayout horizontal() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        return box;
    }

    private LinearLayout.LayoutParams lp(int width, int height) {
        return new LinearLayout.LayoutParams(width < 0 ? width : dp(width), height < 0 ? height : dp(height));
    }

    private void withMargin(LinearLayout parent, View child, int w, int h, int top, int bottom) {
        LinearLayout.LayoutParams params = lp(w, h);
        params.topMargin = dp(top);
        params.bottomMargin = dp(bottom);
        parent.addView(child, params);
    }

    private void spacer(LinearLayout box, int height) {
        box.addView(new View(this), lp(1, height));
    }

    private TextView text(String value, float sp, int color, boolean medium) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setFontFeatureSettings("kern");
        view.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        view.setIncludeFontPadding(false);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private TextView headline(String label) {
        TextView t = text(label, 30, BimvoStyle.WHITE, false);
        t.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        return t;
    }

    private TextView section(String label) { return text(label, 19, BimvoStyle.WHITE, false); }
    private TextView caption(String label) { return text(label, 10, BimvoStyle.MUTED, false); }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(BimvoStyle.BORDER);
        return v;
    }

    private GlyphView glyph(GlyphView.Icon icon, int color, int size) {
        GlyphView g = new GlyphView(this, icon, color);
        g.setLayoutParams(lp(size, size));
        return g;
    }

    private void render() {
        root.setOnClickListener(null);
        root.setClickable(false);
        root.removeAllViews();
        recordingStatus = null; recordingWave = null;
        recordsContainer = null;
        intervalContainer = null;
        importProgress = null;
        importStatus = importFileLabel = null;
        playbackTime = null; playbackButton = null; audioSeeking = false;
        playbackSeekBar = null;
        root.addView(new DotGridView(this), new FrameLayout.LayoutParams(-1,-1));
        if (model.ready && current() == null) {
            currentRecordId = "demo-team";
            if (page == Page.DETAILS || page == Page.TRANSCRIPT || page == Page.MAP || page == Page.PROCESSING) {
                page = Page.RECORDS;
                navigation.clear();
            }
        }
        if (!model.ready && page != Page.SPLASH && page != Page.LOGIN && page != Page.PROFILE) {
            LinearLayout loading = content(true);
            logoHeader(loading, false);
            spacer(loading, 40);
            withMargin(loading, text(s(model.loadError == 0 ? R.string.library_loading : model.loadError),
                    15, BimvoStyle.MUTED, false), -1, -2, 0, 0);
            return;
        }
        if ((page == Page.TRANSCRIPT || page == Page.MAP)
                && current() != null && current().transcript.isEmpty()) page = Page.DETAILS;
        switch (page) {
            case SPLASH: splash(); break;
            case LOGIN: login(); break;
            case HOME: home(); break;
            case UPLOAD: upload(); break;
            case PROCESSING: processing(); break;
            case TRANSCRIPT: transcript(); break;
            case MAP: map(); break;
            case RECORDS: records(); break;
            case EXPORT: export(); break;
            case PROFILE: profile(); break;
            case DETAILS: details(); break;
            case DEMOS: demos(); break;
        }
    }

    private LinearLayout content(boolean bottomBar) {
        LinearLayout scaffold = vertical();
        root.addView(scaffold, new FrameLayout.LayoutParams(-1,-1));
        if (model.voiceRecorder.isRecording()) {
            LinearLayout indicator = recordingPanel(page != Page.HOME);
            LinearLayout.LayoutParams indicatorParams = lp(-1, -2);
            indicatorParams.setMargins(dp(16), dp(8), dp(16), dp(8));
            scaffold.addView(indicator, indicatorParams);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scaffold.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        LinearLayout body = vertical();
        body.setPadding(dp(26), dp(12), dp(26), dp(24));
        scroll.addView(body, new ScrollView.LayoutParams(-1,-2));
        if (bottomBar) scaffold.addView(bottomNavigation(), lp(-1, 61));
        return body;
    }

    private LinearLayout recordingPanel(boolean compact) {
        LinearLayout panel = card(14, 12);
        panel.setBackground(shape(BimvoStyle.CARD, BimvoStyle.TEAL, 12));
        LinearLayout row = horizontal();
        recordingWave = new RecordingWaveView(this);
        row.addView(recordingWave, lp(compact ? 60 : 100, compact ? 60 : 100));
        LinearLayout copy = vertical();
        recordingStatus = text("", compact ? 14 : 17, BimvoStyle.TEAL, true);
        copy.addView(recordingStatus, lp(-1, 32));
        if (!compact) copy.addView(text("Запись с микрофона", 12, BimvoStyle.MUTED, false));
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0, -2, 1f);
        copyParams.leftMargin = dp(12);
        row.addView(copy, copyParams);
        panel.addView(row, lp(-1, -2));
        withMargin(panel, secondaryButton("Остановить и сохранить", this::toggleVoiceRecording), -1, 48, 4, 0);
        updateRecordingStatus();
        return panel;
    }

    private void demos() {
        LinearLayout body = content(true);
        logoHeader(body, true);
        withMargin(body, headline("Демопримеры"), -1, -2, 26, 12);
        withMargin(body, text("Примеры интерфейса без аудио. Не входят в вашу библиотеку.",
                13, BimvoStyle.MUTED, false), -1, -2, 0, 18);
        for (Recording record : model.recordings()) if (record.demo) addRecordingCard(body, record);
        withMargin(body, secondaryButton("Мои записи", () -> bottomTab(Page.HOME)), -1, 48, 18, 0);
    }

    private void updateRecordingStatus() {
        if (recordingStatus != null)
            recordingStatus.setText("● Идёт запись · " + TranscriptText.clock(model.voiceRecorder.elapsedMs()));
        if (recordingWave != null) recordingWave.setLevel(model.voiceRecorder.level());
    }

    private void logoHeader(LinearLayout body, boolean hasBack) {
        LinearLayout row = horizontal();
        if (hasBack) {
            FrameLayout hit = new FrameLayout(this);
            GlyphView backIcon = glyph(GlyphView.Icon.BACK, BimvoStyle.WHITE, 21);
            FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(dp(21),dp(21), Gravity.CENTER);
            hit.addView(backIcon, p);
            hit.setContentDescription("Назад");
            hit.setOnClickListener(v -> back());
            hit.setFocusable(true);
            row.addView(hit, lp(40, 40));
            View gap = new View(this);
            row.addView(gap, lp(4, 1));
        }
        PrismView prism = new PrismView(this);
        row.addView(prism, lp(22, 27));
        TextView brand = text("BIMVO", 15, BimvoStyle.WHITE, true);
        LinearLayout.LayoutParams brandLp = lp(-2, 32);
        brandLp.leftMargin = dp(8);
        row.addView(brand, brandLp);
        withMargin(body, row, -1, 36, 0, 0);
    }

    private View primaryButton(String label, Runnable action) {
        LinearLayout button = horizontal();
        button.setPadding(dp(13),0,dp(10),0);
        clickable(button, BimvoStyle.WHITE, Color.TRANSPARENT, 6, action);
        TextView t = text(label, 16, BimvoStyle.BG, true);
        button.addView(t, new LinearLayout.LayoutParams(0,-1,1f));
        button.addView(glyph(GlyphView.Icon.ARROW, BimvoStyle.BG, 22), lp(22,22));
        button.setContentDescription(label);
        button.setMinimumHeight(dp(50));
        return button;
    }

    private void addPrimary(LinearLayout body, String label, Runnable action, int top) {
        withMargin(body, primaryButton(label, action), -1, 50, top, 0);
    }

    private View secondaryButton(String label, Runnable action) {
        TextView v = text(label, 13, BimvoStyle.WHITE, true);
        v.setGravity(Gravity.CENTER);
        v.setMinHeight(dp(45));
        v.setContentDescription(label);
        v.setOnClickListener(view -> action.run());
        return v;
    }

    private LinearLayout card(int insetX, int insetY) {
        LinearLayout box = vertical();
        box.setPadding(dp(insetX),dp(insetY),dp(insetX),dp(insetY));
        box.setBackground(shape(BimvoStyle.CARD,BimvoStyle.BORDER,7));
        return box;
    }

    private LinearLayout bottomNavigation() {
        LinearLayout container = vertical();
        container.setBackgroundColor(BimvoStyle.BG);
        container.addView(divider(), lp(-1, 1));
        LinearLayout tabs = horizontal();
        container.addView(tabs, new LinearLayout.LayoutParams(-1,0,1f));
        addNavTab(tabs, "Главная", GlyphView.Icon.HOME, Page.HOME);
        addNavTab(tabs, "Записи", GlyphView.Icon.LIST, Page.RECORDS);
        addNavTab(tabs, "Экспорт", GlyphView.Icon.EXPORT, Page.EXPORT);
        addNavTab(tabs, "Профиль", GlyphView.Icon.USER, Page.PROFILE);
        return container;
    }

    private void addNavTab(LinearLayout tabs, String label, GlyphView.Icon icon, Page target) {
        boolean selected = page == target;
        int color = selected ? BimvoStyle.WHITE : BimvoStyle.DIM;
        LinearLayout item = vertical();
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        item.setPadding(0,dp(6),0,dp(2));
        GlyphView g = glyph(icon, color, 19);
        item.addView(g,lp(19,19));
        TextView title = text(label,10,color,false);
        title.setGravity(Gravity.CENTER);
        withMargin(item,title,-1,17,2,0);
        View marker = new View(this);
        marker.setBackground(shape(selected ? BimvoStyle.WHITE : Color.TRANSPARENT, Color.TRANSPARENT,2));
        withMargin(item,marker,12,2,2,0);
        item.setContentDescription("Вкладка " + label + (selected ? ", выбрана" : ""));
        item.setOnClickListener(v -> bottomTab(target));
        tabs.addView(item,new LinearLayout.LayoutParams(0,-1,1f));
    }

    /** Exact approved splash art, with the status and gesture bars excluded from the bitmap. */
    private void splash() {
        ImageView artwork = new ImageView(this);
        artwork.setImageResource(R.drawable.splash_reference);
        artwork.setScaleType(ImageView.ScaleType.FIT_XY);
        artwork.setContentDescription("BIMVO. Расшифровка и анализ разговоров");
        artwork.setOnClickListener(v -> enterLogin());
        root.addView(artwork, new FrameLayout.LayoutParams(-1, -1));
    }

    private void addLoginBackground() {
        ImageView background = new ImageView(this);
        background.setImageResource(R.drawable.login_ref_lights);
        background.setScaleType(ImageView.ScaleType.FIT_XY);
        background.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(background, new FrameLayout.LayoutParams(-1, -1));
    }

    private EditText loginInput(String hint, boolean password) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setTextColor(BimvoStyle.WHITE);
        field.setHintTextColor(0xFFA5A2AD);
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        field.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        field.setPadding(dp(17), 0, dp(14), 0);
        field.setHint(hint);
        field.setBackground(shape(0x3812111B, 0xFF595564, 15));
        field.setSelectAllOnFocus(false);
        field.setInputType(password ? InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PERSON_NAME);
        field.setImeOptions(password ? EditorInfo.IME_ACTION_DONE : EditorInfo.IME_ACTION_NEXT);
        return field;
    }

    private View loginPrimary(Runnable action) {
        LinearLayout button = horizontal();
        button.setGravity(Gravity.CENTER);
        clickable(button, BimvoStyle.WHITE, Color.TRANSPARENT, 15, action);
        TextView title = text("Войти", 20, BimvoStyle.BG, false);
        button.addView(title, lp(-2, -1));
        GlyphView arrow = glyph(GlyphView.Icon.ARROW, BimvoStyle.BG, 23);
        LinearLayout.LayoutParams arrowLp = lp(23, 23);
        arrowLp.leftMargin = dp(14);
        button.addView(arrow, arrowLp);
        button.setMinimumHeight(dp(59));
        button.setContentDescription("Войти в демо-приложение");
        return button;
    }

    private void login() {
        addLoginBackground();
        LinearLayout body = content(false);
        // Unlike the compact main-app pages, the approved login has wide side gutters.
        body.setPadding(dp(20), dp(13), dp(20), dp(22));

        FrameLayout backHit = new FrameLayout(this);
        backHit.setContentDescription("Назад");
        backHit.addView(glyph(GlyphView.Icon.BACK, BimvoStyle.WHITE, 27),
                new FrameLayout.LayoutParams(dp(27), dp(27), Gravity.CENTER));
        backHit.setOnClickListener(v -> back());
        withMargin(body, backHit, 42, 43, 0, 0);

        LinearLayout inner = vertical();
        inner.setPadding(dp(30), 0, dp(30), 0);
        withMargin(body, inner, -1, -2, 35, 0);

        LinearLayout brand = horizontal();
        brand.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        brand.setPadding(dp(19), 0, 0, 0);
        ImageView prism = new ImageView(this);
        prism.setImageResource(R.drawable.login_prism);
        prism.setScaleType(ImageView.ScaleType.FIT_CENTER);
        prism.setContentDescription("Кристалл BIMVO");
        brand.addView(prism, lp(54, 57));
        TextView brandName = text("BIMVO", 22, BimvoStyle.WHITE, false);
        brandName.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        brandName.setLetterSpacing(0.20f);
        LinearLayout.LayoutParams nameLp = lp(-2, 57);
        nameLp.leftMargin = dp(10);
        brand.addView(brandName, nameLp);
        withMargin(inner, brand, -1, 61, 0, 0);

        spacer(inner, 49);
        TextView title = text("BIMVO", 37, BimvoStyle.WHITE, false);
        title.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        title.setMaxLines(1);
        // Fit long Russian title on narrower phones without altering typography on standard phones.
        float titleMaxWidth = getResources().getDisplayMetrics().widthPixels - dp(100);
        float titleMeasuredWidth = title.getPaint().measureText("BIMVO");
        if (titleMeasuredWidth > titleMaxWidth) {
            title.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                    title.getTextSize() * titleMaxWidth / titleMeasuredWidth);
        }
        withMargin(inner, title, -1, -2, 0, 13);
        TextView description = text("Продолжайте работу\nс вашими записями", 17, BimvoStyle.MUTED, false);
        description.setLineSpacing(dp(4), 1f);
        withMargin(inner, description, -1, -2, 0, 0);

        spacer(inner, 28);
        withMargin(inner, primaryButton("Открыть мои записи", this::enterLibrary), -1, 60, 0, 0);
        TextView demo = text("Посмотреть демопримеры", 16, BimvoStyle.MUTED, false);
        demo.setGravity(Gravity.CENTER);
        demo.setOnClickListener(v -> forward(Page.DEMOS));
        withMargin(inner, demo, -1, 51, 18, 0);
        spacer(inner, 30);
    }


    private void home() {
        LinearLayout body = content(true);
        logoHeader(body, false);
        spacer(body, 32);
        withMargin(body, text(s(R.string.home_greeting), 13, BimvoStyle.MUTED, false), -1, -2, 0, 6);
        withMargin(body, headline(s(R.string.home_title)), -1, -2, 0, 0);
        spacer(body, 18);
        addLibrarySummary(body);
        spacer(body, 18);
        LinearLayout uploadCard = horizontal();
        uploadCard.setPadding(dp(16), dp(13), dp(13), dp(13));
        clickable(uploadCard, BimvoStyle.CARD, BimvoStyle.BORDER, 8, () -> forward(Page.UPLOAD));
        uploadCard.addView(glyph(GlyphView.Icon.PLUS, BimvoStyle.WHITE, 22), lp(22,22));
        LinearLayout column = vertical();
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0, -2, 1f);
        colLp.leftMargin = dp(16);
        uploadCard.addView(column, colLp);
        column.addView(text(s(R.string.home_import), 19, BimvoStyle.WHITE, false));
        uploadCard.addView(glyph(GlyphView.Icon.ARROW, BimvoStyle.WHITE, 19), lp(19,19));
        withMargin(body, uploadCard, -1, -2, 0, 10);
        if (model.voiceRecorder.isRecording()) {
            // Live controls stay pinned above the scroll area while recording.
        } else {
            LinearLayout record = horizontal();
            record.setPadding(dp(15), 0, dp(14), 0);
            clickable(record, BimvoStyle.BG, BimvoStyle.BORDER, 8, this::toggleVoiceRecording);
            record.addView(glyph(GlyphView.Icon.MIC, BimvoStyle.MUTED, 21), lp(21,21));
            TextView startRecord = text(s(R.string.home_record), 16, BimvoStyle.WHITE, false);
            LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, -1, 1f);
            titleLp.leftMargin = dp(16);
            record.addView(startRecord, titleLp);
            record.addView(glyph(GlyphView.Icon.ARROW, BimvoStyle.MUTED, 19), lp(19,19));
            withMargin(body, record, -1, 56, 0, 0);
        }
        spacer(body, 23);
        LinearLayout recentHeading = horizontal();
        recentHeading.addView(section(s(R.string.home_recent)), new LinearLayout.LayoutParams(0, -1, 1f));
        TextView all = text(s(R.string.home_all), 12, BimvoStyle.MUTED, false);
        all.setGravity(Gravity.CENTER);
        all.setContentDescription(s(R.string.records_title));
        all.setOnClickListener(v -> bottomTab(Page.RECORDS));
        recentHeading.addView(all, lp(48, 44));
        withMargin(body, recentHeading, -1, 44, 0, 12);
        int shown = 0;
        for (Recording recordItem : personalRecordings()) {
            addRecordingCard(body, recordItem);
            if (++shown == 3) break;
        }
        if (shown == 0) {
            withMargin(body, text("Пока нет записей", 15, BimvoStyle.MUTED, false), -1, -2, 8, 0);
        }
    }

    private List<Recording> personalRecordings() {
        List<Recording> records = new ArrayList<>();
        for (Recording record : model.recordings()) if (!record.demo) records.add(record);
        records.sort((a, b) -> {
            int result = Long.compare(b.createdAt, a.createdAt);
            return result == 0 ? a.id.compareTo(b.id) : result;
        });
        return records;
    }

    private void addLibrarySummary(LinearLayout body) {
        long duration = 0;
        List<Recording> personal = personalRecordings();
        for (Recording r : personal) duration += Math.max(0, r.durationMs);
        LinearLayout panel = card(15, 12);
        panel.addView(text(s(R.string.home_library), 10, BimvoStyle.MUTED, true));
        LinearLayout figures = horizontal();
        LinearLayout count = vertical();
        count.addView(text(String.valueOf(personal.size()), 22, BimvoStyle.WHITE, false));
        withMargin(count, text(s(R.string.library_records), 11, BimvoStyle.MUTED, false), -1,-2,2,0);
        figures.addView(count, new LinearLayout.LayoutParams(0,-2,1f));
        View line = divider();
        LinearLayout.LayoutParams lineLp = lp(1,39);
        lineLp.rightMargin = dp(23);
        figures.addView(line,lineLp);
        LinearLayout total = vertical();
        total.addView(text(getString(R.string.minutes_total, duration / 60_000L), 22, BimvoStyle.WHITE, false));
        withMargin(total, text(s(R.string.library_audio), 11, BimvoStyle.MUTED, false), -1,-2,2,0);
        figures.addView(total, new LinearLayout.LayoutParams(0,-2,1f));
        withMargin(panel, figures, -1,-2,10,0);
        withMargin(body, panel, -1,-2,0,0);
    }

    private String recordingMeta(Recording r) {
        String duration = r.durationMs < 0 ? s(R.string.duration_unknown) : TranscriptText.clock(r.durationMs);
        LibraryStore.Job job = model.processingJob(r.id);
        String state = r.demo ? s(R.string.demo_badge) : job == null ? s(R.string.imported_status)
                : "complete".equals(job.status) ? "Расшифровано"
                : "failed".equals(job.status) ? "Ошибка обработки"
                : "paused".equals(job.status) || "interrupted".equals(job.status) ? "Приостановлено"
                : "Обработка " + job.percent + "%";
        return duration + "  \u00b7  " + state;
    }

    private String date(long milliseconds) {
        return new SimpleDateFormat("dd.MM.yyyy, HH:mm", new Locale("ru", "RU")).format(new Date(milliseconds));
    }

    private void openRecording(Recording r) {
        currentRecordId = r.id;
        selectedTurn = r.transcript.isEmpty() ? 0 : r.transcript.get(0).id;
        audioPosition = 0;
        editing = false;
        if (!r.demo && r.transcript.isEmpty() && model.processingJob(r.id) == null)
            model.requestProcessing(r.id);
        forward(r.transcript.isEmpty() ? Page.DETAILS : Page.TRANSCRIPT);
    }

    private void addRecordingCard(LinearLayout parent, Recording record) {
        LinearLayout row = horizontal();
        row.setPadding(dp(12), dp(12), dp(12), dp(12));
        clickable(row, BimvoStyle.CARD, BimvoStyle.BORDER, 7, () -> openRecording(record));
        View stripe = new View(this);
        stripe.setBackgroundColor(record.demo ? BimvoStyle.PURPLE : BimvoStyle.TEAL);
        row.addView(stripe, lp(2,37));
        LinearLayout copy = vertical();
        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(0,-2,1f);
        copyLp.leftMargin = dp(13);
        row.addView(copy, copyLp);
        TextView title = text(record.title, 15, BimvoStyle.WHITE, false);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        copy.addView(title);
        withMargin(copy, text(record.demo ? s(R.string.demo_record_label) : date(record.createdAt),
                11, BimvoStyle.MUTED, false), -1,-2,5,0);
        withMargin(copy, text(recordingMeta(record), 10, BimvoStyle.DIM, false), -1,-2,4,0);
        TextView actions = text("⋮", 26, BimvoStyle.WHITE, true);
        actions.setGravity(Gravity.CENTER);
        actions.setContentDescription("Действия: " + record.title);
        actions.setOnClickListener(v -> recordingActions(record));
        row.setOnLongClickListener(v -> { recordingActions(record); return true; });
        row.addView(actions, lp(48,48));
        withMargin(parent, row, -1,-2,0,9);
    }

    private void note(LinearLayout body, String message, boolean important) {
        if (!important) return;
        TextView label = text(message, 12, BimvoStyle.MUTED, false);
        label.setLineSpacing(dp(4), 1f);
        withMargin(body, label, -1,-2,16,0);
    }

    private void upload() {
        LinearLayout body = content(false);
        logoHeader(body,true);
        spacer(body,28);
        withMargin(body,headline(s(R.string.new_audio_title)),-1,-2,0,10);
        withMargin(body,text(s(R.string.import_subtitle),13,BimvoStyle.MUTED,false),-1,-2,0,0);
        spacer(body,28);
        if (model.importing()) {
            LinearLayout panel = card(16,18);
            panel.addView(section(s(R.string.import_busy)));
            importFileLabel = text(model.importName, 14, BimvoStyle.WHITE, false);
            importFileLabel.setMaxLines(2);
            withMargin(panel,importFileLabel,-1,-2,15,8);
            importProgress = new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
            importProgress.setMax(100);
            importProgress.setProgressTintList(ColorStateList.valueOf(BimvoStyle.WHITE));
            importProgress.setIndeterminateTintList(ColorStateList.valueOf(BimvoStyle.WHITE));
            withMargin(panel,importProgress,-1,12,6,10);
            importStatus = text("",12,BimvoStyle.MUTED,false);
            panel.addView(importStatus);
            withMargin(body,panel,-1,-2,0,12);
            withMargin(body,secondaryButton(s(R.string.import_cancel),model::cancelImport),-1,48,8,0);
            onImportProgress();
        } else {
            LinearLayout choose = vertical();
            choose.setGravity(Gravity.CENTER);
            choose.setPadding(dp(16),dp(20),dp(16),dp(20));
            clickable(choose,BimvoStyle.CARD,BimvoStyle.BORDER,8,this::chooseAudio);
            FrameLayout circle = new FrameLayout(this);
            circle.setBackground(oval(BimvoStyle.CARD,BimvoStyle.MUTED));
            circle.addView(glyph(GlyphView.Icon.PLUS,BimvoStyle.WHITE,22),
                    new FrameLayout.LayoutParams(dp(22),dp(22),Gravity.CENTER));
            choose.addView(circle,lp(38,38));
            withMargin(choose,text(s(R.string.choose_audio),17,BimvoStyle.WHITE,false),-2,-2,12,0);
            withMargin(choose,text(s(R.string.choose_audio_types),11,BimvoStyle.MUTED,false),-2,-2,8,0);
            choose.setContentDescription(s(R.string.choose_audio));
            withMargin(body,choose,-1,-2,0,5);
            Recording imported = model.find(model.lastImportedId);
            if (imported != null && !imported.demo) {
                withMargin(body,caption(s(R.string.import_current_file)),-1,-2,26,12);
                addRecordingCard(body,imported);
                addPrimary(body,s(R.string.import_open_library),() -> bottomTab(Page.RECORDS),14);
            }

        }
        TextView privacy = caption(s(R.string.import_security));
        privacy.setGravity(Gravity.CENTER);
        withMargin(body,privacy,-1,-2,24,0);
        withMargin(body,secondaryButton(s(R.string.import_demo_link),() -> {
            currentRecordId = "demo-team";
            selectedTurn = 0;
            forward(Page.PROCESSING);
        }),-1,48,20,0);
    }

    private void toggleVoiceRecording() {
        if (model.voiceRecorder.isRecording()) {
            handler.removeCallbacks(recordingClock);
            try {
                File capture = model.voiceRecorder.stop();
                if (!model.saveVoiceRecording(capture)) {
                    capture.delete();
                    toast(R.string.operation_busy);
                } else {
                    toast(R.string.voice_record_saving);
                }
            } catch (java.io.IOException error) {
                toastText(error.getMessage());
            }
            render();
            return;
        }
        if (!model.ready || model.busy()) { toast(R.string.operation_busy); return; }
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_MICROPHONE);
            return;
        }
        startVoiceRecording();
    }

    private void startVoiceRecording() {
        try {
            model.voiceRecorder.start(this);
            render();
            handler.removeCallbacks(recordingClock);
            handler.postDelayed(recordingClock, 1000);
        } catch (java.io.IOException | RuntimeException error) {
            toast(R.string.voice_record_start_failed);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_MICROPHONE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)
                startVoiceRecording();
            else toast(R.string.voice_record_permission_denied);
        }
    }

    private void chooseAudio() {
        if (!model.ready || model.busy()) { toast(R.string.operation_busy); return; }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/*", "application/ogg"})
                .putExtra(Intent.EXTRA_LOCAL_ONLY, true)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(intent, PICK_AUDIO); }
        catch (ActivityNotFoundException e) { toast(R.string.picker_missing); }
    }

    private void details() {
        Recording r = current();
        LinearLayout body = content(false);
        logoHeader(body,true);
        spacer(body,28);
        withMargin(body,headline(r.title),-1,-2,0,12);
        addProcessingStatus(body, r);
        LinearLayout metadata = card(15,15);
        metadataLine(metadata,R.string.file_name_label,r.originalName);
        metadataLine(metadata,R.string.file_size_label,size(r.sizeBytes));
        metadataLine(metadata,R.string.file_duration_label,r.durationMs < 0 ? s(R.string.duration_unknown) : TranscriptText.clock(r.durationMs));
        metadataLine(metadata,R.string.file_date_label,date(r.createdAt));
        metadataLine(metadata,R.string.file_storage_label,s(R.string.file_storage_value));
        withMargin(body,metadata,-1,-2,0,0);
        if (!r.demo && model.audioFile(r).isFile()) {
            withMargin(body,section("Оригинал записи"),-1,-2,16,8);
            addAudioPlayer(body, r);
        }
        if (!r.demo && !model.audioFile(r).isFile()) note(body,s(R.string.file_missing_notice),true);
        if (!r.demo) {

            withMargin(body,secondaryButton("Импорт JSON результата ML", () -> {
                Intent json = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE).setType("application/json");
                try { startActivityForResult(json, PICK_TRANSCRIPT); }
                catch (ActivityNotFoundException e) { toast(R.string.picker_missing); }
            }),-1,48,12,0);
            if (!r.transcript.isEmpty())
                withMargin(body,secondaryButton("Открыть расшифровку", () -> forward(Page.TRANSCRIPT)),-1,48,8,0);
            else withMargin(body,text("Готовый текст появится после локального распознавания.",12,BimvoStyle.MUTED,false),-1,-2,8,0);
        }
        addPrimary(body,s(R.string.import_open_library),() -> bottomTab(Page.RECORDS),26);
        if (!r.demo) {
            withMargin(body,secondaryButton(s(R.string.rename_action),() -> renameRecording(r)),-1,48,8,0);
            withMargin(body,secondaryButton(s(R.string.delete_action),() -> confirmDelete(r)),-1,48,0,0);
        }
    }

    private void addProcessingStatus(LinearLayout body, Recording r) {
        if (r.demo) return;
        LibraryStore.Job job = model.processingJob(r.id);
        boolean active = job != null && job.active();
        boolean failed = job != null && ("failed".equals(job.status) || "paused".equals(job.status) || "interrupted".equals(job.status));
        String status = job != null && "complete".equals(job.status) ? "Готово · 100%"
                : active ? ("speakers".equals(job.stage) || "clustering".equals(job.stage) ? "Определение говорящих · " : "vad".equals(job.stage) ? "Поиск речи · " : "Распознавание · ") + job.percent + "%"
                : failed ? "Обработка приостановлена · " + job.percent + "%"
                : !r.transcript.isEmpty() ? "Расшифровка готова" : "Подготовка · 0%";
        withMargin(body, text(status, 13, BimvoStyle.TEAL, true), -1, -2, 0, 12);
        if (active) {
            ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(100); bar.setProgress(job.percent);
            bar.setProgressTintList(ColorStateList.valueOf(BimvoStyle.TEAL));
            withMargin(body, bar, -1, 6, 0, 8);
            withMargin(body, secondaryButton("Остановить обработку", () -> model.cancelProcessing(r.id)), -1, 48, 0, 12);
        } else if (failed) {
            if (job.error != null && !job.error.isEmpty())
                withMargin(body, text(job.error, 12, BimvoStyle.MUTED, false), -1, -2, 0, 8);
            withMargin(body, secondaryButton("Продолжить обработку", () -> {
                if (!model.requestProcessing(r.id)) toast(R.string.operation_busy);
            }), -1, 48, 0, 12);
        }
    }

    private void recordingActions(Recording r) {
        if (r.demo) { openRecording(r); return; }
        new AlertDialog.Builder(this).setTitle(r.title)
                .setItems(new String[]{"Открыть", "Сведения о записи", "Переименовать", "Удалить", "Обработать с диаризацией"}, (d, which) -> {
                    if (which == 0) openRecording(r);
                    else if (which == 1) { currentRecordId = r.id; audioPosition = 0; stopPlayback(); forward(Page.DETAILS); }
                    else if (which == 2) renameRecording(r);
                    else if (which == 3) confirmDelete(r);
                    else new AlertDialog.Builder(this).setTitle("Обработать с диаризацией?")
                            .setMessage("Аудио будет обработано заново. Текущая расшифровка сохранится до успешного завершения. Сохранённый конспект не пересоздаётся.")
                            .setPositiveButton("Обработать", (choice, button) -> {
                                if (!model.reprocessWithSpeakers(r.id)) toast(R.string.operation_busy);
                            }).setNegativeButton(R.string.cancel, null).show();
                }).setNegativeButton(R.string.cancel, null).show();
    }

    private void confirmDelete(Recording r) {
        if (r.demo) return;
        LibraryStore.Job job = model.processingJob(r.id);
        if (model.isProcessing(r.id) || (job != null && job.active())) {
            toastText("Сначала остановите обработку записи"); return;
        }
        new AlertDialog.Builder(this).setTitle("Удалить запись?").setMessage(r.title)
                .setPositiveButton("Удалить", (d, w) -> {
                    if (realPlayer.isLoaded(r.id)) { stopPlayback(); realPlayer.release(); }
                    if (!model.delete(r)) toast(R.string.operation_busy);
                }).setNegativeButton(R.string.cancel, null).show();
    }

    private void metadataLine(LinearLayout parent,int label,String value) {
        withMargin(parent,caption(s(label)),-1,-2,0,5);
        TextView text = text(value,14,BimvoStyle.WHITE,false);
        text.setTextIsSelectable(true);
        withMargin(parent,text,-1,-2,0,15);
    }

    private void renameRecording(Recording r) {
        stopPlayback();
        EditText editor = dialogInput(r.title,1);
        editor.setFilters(new InputFilter[]{new InputFilter.LengthFilter(200)});
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.rename_action)
                .setView(inputWrapper(editor)).setPositiveButton(R.string.save,null)
                .setNegativeButton(R.string.cancel,null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = editor.getText().toString().trim();
            if (value.isEmpty()) { editor.setError(s(R.string.name_required)); return; }
            if (model.rename(r.id,value)) dialog.dismiss(); else toast(R.string.operation_busy);
        }));
        dialog.show();
    }

    private LinearLayout settingRow(String title, String value, Runnable action) {
        LinearLayout row = horizontal();
        row.setPadding(dp(11),0,dp(13),0);
        clickable(row,BimvoStyle.CARD,BimvoStyle.BORDER,7,action);
        TextView name = text(title,13,BimvoStyle.WHITE,false);
        row.addView(name,new LinearLayout.LayoutParams(0,-1,1f));
        TextView selected = text(value,12,BimvoStyle.MUTED,false);
        selected.setSingleLine(true);
        selected.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
        selected.setMaxWidth(getResources().getDisplayMetrics().widthPixels / 2);
        selected.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(selected,lp(-2,-1));
        row.setContentDescription(title+": "+value);
        return row;
    }

    private void processing() {
        if (current() != null && !current().demo) { realProcessing(); return; }
        LinearLayout body = content(false);
        logoHeader(body,true);
        spacer(body,31);
        withMargin(body,headline(s(R.string.demo_processing_title)),-1,-2,0,9);
        withMargin(body,text(current().originalName,14,BimvoStyle.MUTED,false),-1,-2,0,0);
        withMargin(body,divider(),-1,1,23,31);
        withMargin(body,section(s(R.string.demo_stages)),-1,-2,0,9);
        TextView explanation = text(s(R.string.demo_processing_description),13,BimvoStyle.MUTED,false);
        explanation.setLineSpacing(dp(5),1f);
        withMargin(body,explanation,-1,-2,0,0);
        spacer(body,39);
        LinearLayout top = horizontal();
        TextView progressLabel = text("Общий прогресс",12,BimvoStyle.MUTED,false);
        top.addView(progressLabel,new LinearLayout.LayoutParams(0,-1,1f));
        percentLabel = text(processingProgress+"%",14,BimvoStyle.WHITE,true);
        top.addView(percentLabel,lp(-2,-1));
        body.addView(top,lp(-1,28));
        progressBar = new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(processingProgress);
        progressBar.setProgressTintList(ColorStateList.valueOf(BimvoStyle.WHITE));
        progressBar.setProgressBackgroundTintList(ColorStateList.valueOf(BimvoStyle.TRACK));
        progressBar.setScaleY(.65f);
        withMargin(body,progressBar,-1,9,5,0);
        spacer(body,33);
        withMargin(body,caption("ЭТАПЫ"),-1,-2,0,10);
        phaseLabels = new TextView[4];
        String[] steps = {"Подготовка аудио", "Распознавание речи", "Определение говорящих", "Формирование расшифровки"};
        for (int i = 0; i < steps.length; i++) {
            withMargin(body,divider(),-1,1,0,0);
            LinearLayout line = horizontal();
            line.setPadding(dp(2),dp(7),dp(2),dp(7));
            TextView num = text(String.format(Locale.ROOT,"%02d",i+1),11,BimvoStyle.MUTED,false);
            line.addView(num,lp(28,44));
            LinearLayout words = vertical();
            LinearLayout.LayoutParams wordLp = new LinearLayout.LayoutParams(0,-2,1f);
            wordLp.leftMargin=dp(1);
            line.addView(words,wordLp);
            words.addView(text(steps[i],14,BimvoStyle.WHITE,false));
            phaseLabels[i] = text("",10,BimvoStyle.MUTED,false);
            phaseLabels[i].setGravity(Gravity.RIGHT);
            withMargin(words,phaseLabels[i],-1,-2,6,0);
            body.addView(line,lp(-1,59));
        }
        updateProcessingLabels();
        spacer(body,30);
        LinearLayout box = card(12,10);
        box.addView(text(s(R.string.demo_progress_title),12,BimvoStyle.WHITE,true));
        withMargin(box,text(s(R.string.demo_progress_note),10,BimvoStyle.MUTED,false),-1,-2,7,0);
        withMargin(body,box,-1,-2,0,0);
        completedCta = primaryButton("Открыть расшифровку",() -> {
            currentRecordId="demo-team";
            forward(Page.TRANSCRIPT);
        });
        withMargin(body,completedCta,-1,50,17,0);
        completedCta.setVisibility(processingProgress>=100 ? View.VISIBLE : View.GONE);
        TextView simulation = caption("ДЕМО: распознавание не выполняется");
        simulation.setGravity(Gravity.CENTER);
        withMargin(body,simulation,-1,-2,14,0);
    }

    /** Genuine persisted audio progress; demo simulation above remains unchanged. */
    private void realProcessing() {
        Recording r = current();
        LibraryStore.Job job = model.processingJob(r.id);
        LinearLayout body = content(false);
        logoHeader(body,true);
        spacer(body,31);
        withMargin(body,headline("Обработка аудио"),-1,-2,0,9);
        withMargin(body,text(r.title,14,BimvoStyle.MUTED,false),-1,-2,0,23);
        LinearLayout panel = card(16,15);
        int percent = job == null ? 0 : job.percent;
        boolean complete = job != null && "complete".equals(job.status);
        String status = job == null ? "Обработка ещё не запущена"
                : complete ? "Расшифровка готова"
                : "failed".equals(job.status) ? "Ошибка обработки"
                : "paused".equals(job.status) || "interrupted".equals(job.status) ? "Обработка приостановлена"
                : "Идёт локальная обработка";
        panel.addView(text(status,15,BimvoStyle.WHITE,true));
        withMargin(panel,text(percent + "%",14,BimvoStyle.MUTED,false),-1,-2,14,8);
        ProgressBar bar = new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100); bar.setProgress(percent);
        bar.setProgressTintList(ColorStateList.valueOf(BimvoStyle.WHITE));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(BimvoStyle.TRACK));
        withMargin(panel,bar,-1,9,0,16);
        String stage = job == null ? "Ожидание" : switch (job.stage) {
            case "decode", "prepare" -> "Подготовка PCM 16 кГц";
            case "vad" -> "Определение пауз · Silero";
            case "speakers", "clustering" -> "Определение говорящих";
            case "language" -> "Определение языка";
            case "asr" -> "Распознавание речи · GigaAM";
            case "done" -> "Готово";
            default -> "Ожидание или восстановление";
        };
        panel.addView(text(stage,12,BimvoStyle.MUTED,false));
        if (job != null && job.total > 0)
            withMargin(panel,text("Обработано частей: " + job.completed + " / " + job.total,12,BimvoStyle.MUTED,false),-1,-2,10,0);
        if (job != null && job.error != null && !job.error.isEmpty())
            withMargin(panel,text(job.error,12,BimvoStyle.WHITE,false),-1,-2,14,0);
        withMargin(body,panel,-1,-2,0,17);
        if (job != null && job.active())
            withMargin(body,secondaryButton("Остановить обработку",() -> model.cancelProcessing(r.id)),-1,48,0,9);
        else if (!complete)
            withMargin(body,secondaryButton(job == null ? "Начать обработку" : "Продолжить обработку",() -> {
                if (!model.requestProcessing(r.id)) toast(R.string.operation_busy);
            }),-1,48,0,9);
        if (!r.transcript.isEmpty())
            addPrimary(body,"Открыть расшифровку",() -> forward(Page.TRANSCRIPT),8);
        withMargin(body,caption("Аудио, распознавание и определение говорящих остаются на устройстве."),-1,-2,16,0);
    }

    private void updateProcessingLabels() {
        if (percentLabel != null) percentLabel.setText(processingProgress+"%");
        if (progressBar != null) progressBar.setProgress(processingProgress);
        if (phaseLabels != null) {
            int[] starts = {0, 20, 70, 86};
            int[] ends = {20, 70, 86, 100};
            for (int i=0; i<4; i++) {
                String status = processingProgress>=ends[i] ? "Завершено" :
                        processingProgress >= starts[i] ? "Выполняется" : "Ожидание";
                phaseLabels[i].setText(status);
                phaseLabels[i].setTextColor(status.equals("Выполняется") ? BimvoStyle.WHITE : BimvoStyle.MUTED);
            }
        }
        if (completedCta != null) completedCta.setVisibility(processingProgress>=100?View.VISIBLE:View.GONE);
    }

    private void animateProcessing() {
        if (processingTick!=null) handler.removeCallbacks(processingTick);
        processingTick = new Runnable() {
            @Override public void run() {
                if (page != Page.PROCESSING || isFinishing() || isDestroyed()) return;
                processingProgress = Math.min(100,processingProgress+3);
                updateProcessingLabels();
                if (processingProgress<100) handler.postDelayed(this,260);
            }
        };
        handler.postDelayed(processingTick,400);
    }


    private String speakerLabel(String id) {
        if ("speaker_unknown".equals(id)) return "Говорящий не определён";
        return getString(R.string.speaker_label, id.startsWith("speaker_") ? id.substring(8) : id);
    }

    private void resultHeader(LinearLayout body, boolean onMap) {
        Recording record = current();
        logoHeader(body,true);
        spacer(body,23);
        withMargin(body,headline(onMap ? s(R.string.map_title) : record.title),-1,-2,0,9);
        if (record.demo) {
            withMargin(body,text(getString(R.string.demo_fragment,
                    TranscriptText.clock(record.transcriptDurationMs()), record.speakers().size()),
                    12,BimvoStyle.MUTED,false),-1,-2,0,0);
        } else {
            int speakers = 0;
            for (String id : record.speakers()) if (!"speaker_unknown".equals(id)) speakers++;
            String duration = TranscriptText.clock(record.durationMs > 0 ? record.durationMs : record.transcriptDurationMs());
            withMargin(body,text(duration + (speakers == 0 ? " · Говорящие не определены" : " · Говорящих: " + speakers),
                    12,BimvoStyle.MUTED,false),-1,-2,0,8);
            addProcessingStatus(body, record);
        }
        spacer(body,13);
        LinearLayout switcher = horizontal();
        switcher.setPadding(dp(2),dp(2),dp(2),dp(2));
        switcher.setBackground(shape(BimvoStyle.CARD,BimvoStyle.BORDER,6));
        TextView transcriptTab = text(s(R.string.text_tab),12,onMap ? BimvoStyle.MUTED : BimvoStyle.BG,true);
        transcriptTab.setGravity(Gravity.CENTER);
        clickable(transcriptTab,onMap ? BimvoStyle.CARD : BimvoStyle.WHITE,Color.TRANSPARENT,5,this::selectTextTab);
        switcher.addView(transcriptTab,new LinearLayout.LayoutParams(0,dp(40),1f));
        TextView mapTab = text(s(R.string.map_title),12,onMap ? BimvoStyle.BG : BimvoStyle.MUTED,true);
        mapTab.setGravity(Gravity.CENTER);
        clickable(mapTab,onMap ? BimvoStyle.WHITE : BimvoStyle.CARD,Color.TRANSPARENT,5,() -> {
            if (page != Page.MAP) forward(Page.MAP);
        });
        switcher.addView(mapTab,new LinearLayout.LayoutParams(0,dp(40),1f));
        withMargin(body,switcher,-1,44,0,0);
    }

    private void transcript() {
        Recording r = current();
        LinearLayout body = content(false);
        resultHeader(body,false);
        spacer(body,16);
        addAudioPlayer(body, r);
        View summaryButton = primaryButton("Конспект", () -> qwenSummary.show(r));
        LibraryStore.Job summaryJob = model.processingJob(r.id);
        boolean transcriptReady = summaryJob == null || "complete".equals(summaryJob.status);
        summaryButton.setEnabled(transcriptReady);
        summaryButton.setAlpha(transcriptReady ? 1f : .45f);
        summaryButton.setContentDescription(transcriptReady ? "Открыть конспект" : "Конспект доступен после завершения распознавания");
        withMargin(body,summaryButton,-1,64,4,16);
        LinearLayout header = horizontal();
        header.addView(section(s(R.string.transcript_title)),new LinearLayout.LayoutParams(0,-1,1f));
        TextView edit = text(s(editing ? R.string.done : R.string.edit),11,BimvoStyle.MUTED,false);
        edit.setGravity(Gravity.CENTER);
        edit.setContentDescription(s(R.string.edit));
        edit.setOnClickListener(v -> { editing = !editing; render(); });
        header.addView(edit,lp(104,44));
        withMargin(body,header,-1,44,12,12);
        for (Utterance turn : r.transcript) addSpeakerCard(body,turn);
        addPrimary(body,s(R.string.transcript_export),() -> forward(Page.EXPORT),15);
        note(body,s(R.string.demo_player_hint),false);
    }

    private void startPlayback() {
        if (playbackTick != null) handler.removeCallbacks(playbackTick);
        playbackTick = new Runnable() {
            @Override public void run() {
                if (!audioPlaying || isFinishing() || isDestroyed()) return;
                if (!audioSeeking) {
                    audioPosition = realPlayer.positionMs();
                    if (playbackSeekBar != null) playbackSeekBar.setProgress((int)audioPosition);
                    if (playbackTime != null) playbackTime.setText(TranscriptText.clock(audioPosition));
                }
                if (!realPlayer.isPlaying()) { audioPlaying=false; render(); }
                else handler.postDelayed(this,250);
            }
        };
        handler.postDelayed(playbackTick,250);
    }

    private void addAudioPlayer(LinearLayout body, Recording r) {
        if (r.demo || !model.audioFile(r).isFile()) {
            if (r.demo) note(body, "Демопример · аудио не прикреплено", true);
            return;
        }
        LinearLayout player = card(12, 10);
        long duration = realPlayer.isLoaded(r.id) ? realPlayer.durationMs()
                : r.durationMs > 0 ? r.durationMs : r.transcriptDurationMs();
        audioPosition = Math.max(0, Math.min(audioPosition, duration));
        LinearLayout controls = horizontal();
        TextView play = text(audioPlaying ? "Пауза" : "Слушать", 14, BimvoStyle.WHITE, true);
        playbackButton = play;
        play.setGravity(Gravity.CENTER);
        play.setOnClickListener(v -> {
            if (audioPlaying) { stopPlayback(); render(); }
            else { seekAudio(r, audioPosition, true); render(); }
        });
        controls.addView(play, lp(96, 48));
        playbackTime = text(TranscriptText.clock(audioPosition), 12, BimvoStyle.WHITE, false);
        controls.addView(playbackTime, new LinearLayout.LayoutParams(0, -1, 1f));
        controls.addView(text(TranscriptText.clock(duration), 12, BimvoStyle.MUTED, false), lp(-2, 48));
        player.addView(controls, lp(-1, 48));
        playbackSeekBar = demoSeek((int)audioPosition, (int)Math.min(Integer.MAX_VALUE, duration));
        playbackSeekBar.setContentDescription("Позиция воспроизведения");
        playbackSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (!fromUser) return;
                audioPosition = value;
                if (playbackTime != null) playbackTime.setText(TranscriptText.clock(audioPosition));
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { audioSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                audioPosition = bar.getProgress();
                audioSeeking = false;
                if (realPlayer.isLoaded(r.id)) realPlayer.seekTo(audioPosition);
            }
        });
        player.addView(playbackSeekBar, lp(-1, 44));
        LinearLayout jumps = horizontal();
        for (int delta : new int[]{-10, 10}) {
            TextView jump = text(delta < 0 ? "−10 сек" : "+10 сек", 13, BimvoStyle.MUTED, true);
            jump.setGravity(Gravity.CENTER);
            jump.setOnClickListener(v -> {
                audioPosition = Math.max(0, Math.min(duration, audioPosition + delta * 1000L));
                if (realPlayer.isLoaded(r.id)) realPlayer.seekTo(audioPosition);
                playbackSeekBar.setProgress((int)audioPosition);
                playbackTime.setText(TranscriptText.clock(audioPosition));
            });
            jumps.addView(jump, new LinearLayout.LayoutParams(0, dp(48), 1f));
        }
        player.addView(jumps);
        withMargin(body, player, -1, -2, 0, 14);
    }

    private void seekAudio(Recording r, long position, boolean play) {
        if (r.demo) { toastText("В демопримере нет звукового файла"); return; }
        try {
            if (!realPlayer.isLoaded(r.id)) realPlayer.load(r.id, model.audioFile(r));
            audioPosition = play && position >= realPlayer.durationMs() ? 0 : Math.max(0, Math.min(position, realPlayer.durationMs()));
            realPlayer.seekTo(audioPosition);
            if (play) {
                realPlayer.play(); audioPlaying = true;
                if (playbackButton != null) playbackButton.setText("Пауза");
                startPlayback();
            }
            if (playbackTime != null) playbackTime.setText(TranscriptText.clock(audioPosition));
            if (playbackSeekBar != null) playbackSeekBar.setProgress((int)audioPosition);
        } catch (Exception e) { audioPlaying = false; toastText("Не удалось открыть аудио: " + e.getMessage()); }
    }

    void exportSummary(String title, String content) {
        if (!pendingSummaryText.isEmpty()) { toastText("Сначала завершите сохранение файла"); return; }
        pendingSummaryText = content;
        String filename = ImportFiles.safeDisplayName(title);
        while (filename.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 180)
            filename = filename.substring(0, filename.offsetByCodePoints(filename.length(), -1));
        pendingSummaryName = filename + "_конспект.txt";
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/plain").putExtra(Intent.EXTRA_TITLE, pendingSummaryName);
        try { startActivityForResult(intent, CREATE_SUMMARY_EXPORT); }
        catch (ActivityNotFoundException e) { pendingSummaryText = ""; toast(R.string.picker_missing); }
    }

    private void toastText(String text) { Toast.makeText(this,text,Toast.LENGTH_LONG).show(); }



    void pickQwenModel() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_LOCAL_ONLY, true);
        try { startActivityForResult(intent, PICK_QWEN_MODEL); }
        catch (ActivityNotFoundException e) { toast(R.string.picker_missing); }
    }
    private void addSpeakerCard(LinearLayout body,Utterance turn) {
        LinearLayout row = horizontal();
        row.setPadding(dp(12),dp(11),dp(11),dp(12));
        clickable(row,BimvoStyle.CARD,BimvoStyle.BORDER,7,() -> {
            selectedTurn = turn.id;
            if (editing) editFragment(turn);
            else {
                audioPosition = turn.startMs;
                if (!current().demo && model.audioFile(current()).isFile()) {
                    try {
                        if (!realPlayer.isLoaded(current().id)) realPlayer.load(current().id, model.audioFile(current()));
                        realPlayer.seekTo(audioPosition);
                        realPlayer.play();
                        audioPlaying = true;
                        render();
                        startPlayback();
                    } catch (Exception e) { toastText("Не удалось перейти к реплике: " + e.getMessage()); }
                } else { render(); toastText("У демо нет звукового файла"); }
            }
        });
        View stripe = new View(this);
        stripe.setBackgroundColor(BimvoStyle.speakerColor(turn.speakerId));
        row.addView(stripe,lp(2,69));
        LinearLayout copy = vertical();
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0,-2,1f);
        cp.leftMargin=dp(11);
        row.addView(copy,cp);
        copy.addView(text(speakerLabel(turn.speakerId),14,BimvoStyle.WHITE,true));
        TextView timing = text(TranscriptText.preciseClock(turn.startMs) + " \u2014 "
                + TranscriptText.preciseClock(turn.endMs),11,BimvoStyle.MUTED,false);
        withMargin(copy,timing,-1,-2,6,0);
        TextView sentence = text(turn.hasRecognizedText() ? turn.text : "Речь не распознана",13,BimvoStyle.WHITE,false);
        sentence.setLineSpacing(dp(4),1f);
        withMargin(copy,sentence,-1,-2,9,0);
        LinearLayout footer = horizontal();
        footer.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
        FrameLayout edit = new FrameLayout(this);
        edit.addView(glyph(GlyphView.Icon.PENCIL,BimvoStyle.MUTED,16),
                new FrameLayout.LayoutParams(dp(16),dp(16),Gravity.CENTER));
        edit.setContentDescription(s(R.string.edit) + ": " + speakerLabel(turn.speakerId));
        edit.setOnClickListener(v -> editFragment(turn));
        footer.addView(edit,lp(44,40));
        withMargin(copy,footer,-1,40,2,0);
        withMargin(body,row,-1,-2,0,10);
    }

    private EditText dialogInput(String value,int lines) {
        EditText editor = new EditText(this);
        editor.setText(value);
        editor.setTextColor(BimvoStyle.WHITE);
        editor.setTextSize(TypedValue.COMPLEX_UNIT_SP,15);
        editor.setMinLines(lines);
        editor.setMaxLines(Math.max(lines,6));
        editor.setInputType(InputType.TYPE_CLASS_TEXT | (lines > 1
                ? InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES : 0));
        editor.setSingleLine(lines == 1);
        return editor;
    }

    private View inputWrapper(EditText editor) {
        LinearLayout wrapper = vertical();
        wrapper.setPadding(dp(20),dp(8),dp(20),dp(8));
        wrapper.addView(editor,lp(-1,-2));
        return wrapper;
    }

    private void editFragment(Utterance turn) {
        EditText editor = dialogInput(turn.text,3);
        editor.setFilters(new InputFilter[]{new InputFilter.LengthFilter(8_000)});
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(speakerLabel(turn.speakerId))
                .setView(inputWrapper(editor)).setPositiveButton(R.string.save,null)
                .setNegativeButton(R.string.cancel,null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = editor.getText().toString().trim();
            if (value.isEmpty()) { editor.setError(s(R.string.empty_text)); return; }
            if (model.updateUtterance(currentRecordId,turn.withText(value))) dialog.dismiss();
            else toast(R.string.operation_busy);
        }));
        dialog.show();
    }

    private Utterance selectedUtterance() {
        Recording record = current();
        Utterance turn = record.utterance(selectedTurn);
        if (turn == null) { turn = record.transcript.get(0); selectedTurn = turn.id; }
        return turn;
    }

    private void map() {
        LinearLayout body = content(false);
        resultHeader(body,true);
        spacer(body,16);
        addAudioPlayer(body, current());
        LinearLayout graph = card(11,13);
        withMargin(graph,text(s(R.string.map_fragments),15,BimvoStyle.WHITE,false),-1,-2,0,12);
        TimelineView timeline = new TimelineView(this);
        timeline.setData(current().transcript,selectedUtterance().id,current().transcriptDurationMs());
        timeline.setOnIntervalSelected(turnId -> {
            selectedTurn = turnId;
            updateIntervalCard();
            seekAudio(current(), selectedUtterance().startMs, true);
        });
        graph.addView(timeline,lp(-1,-2));
        withMargin(body,graph,-1,-2,0,19);
        withMargin(body,caption(s(R.string.map_interval)),-1,-2,0,10);
        intervalContainer = card(12,12);
        withMargin(body,intervalContainer,-1,-2,0,0);
        updateIntervalCard();
        TextView changeTime = text(s(R.string.map_edit_time),13,BimvoStyle.MUTED,false);
        changeTime.setContentDescription(s(R.string.map_edit_time));
        changeTime.setOnClickListener(v -> editInterval());
        withMargin(body,changeTime,-1,48,14,0);
        addPrimary(body,s(R.string.map_save),() -> {
            if (model.busy()) { toast(R.string.operation_busy); return; }
            selectTextTab();
        },5);

        if (current().demo) note(body,s(R.string.demo_notice),true);
    }

    private void updateIntervalCard() {
        if (intervalContainer == null || page != Page.MAP) return;
        Utterance turn = selectedUtterance();
        intervalContainer.removeAllViews();
        LinearLayout header = horizontal();
        View stripe = new View(this);
        stripe.setBackgroundColor(BimvoStyle.speakerColor(turn.speakerId));
        header.addView(stripe,lp(3,34));
        LinearLayout words = vertical();
        LinearLayout.LayoutParams w = new LinearLayout.LayoutParams(0,-2,1f);
        w.leftMargin=dp(12);
        header.addView(words,w);
        words.addView(text(speakerLabel(turn.speakerId),16,BimvoStyle.WHITE,false));
        withMargin(words,text(TranscriptText.preciseClock(turn.startMs) + " \u2014 "
                + TranscriptText.preciseClock(turn.endMs),12,BimvoStyle.MUTED,false),-1,-2,6,0);
        withMargin(intervalContainer,header,-1,-2,0,12);
        TextView snippet = text(turn.hasRecognizedText() ? turn.text : "Речь не распознана",12,BimvoStyle.MUTED,false);
        snippet.setMaxLines(3);
        snippet.setEllipsize(android.text.TextUtils.TruncateAt.END);
        withMargin(intervalContainer,snippet,-1,-2,0,12);
        withMargin(intervalContainer,divider(),-1,1,0,0);
        LinearLayout change = horizontal();
        change.addView(text(s(R.string.map_change_speaker),13,BimvoStyle.WHITE,false),
                new LinearLayout.LayoutParams(0,-1,1f));
        change.addView(glyph(GlyphView.Icon.ARROW,BimvoStyle.MUTED,18),lp(18,18));
        change.setContentDescription(s(R.string.map_change_speaker));
        change.setOnClickListener(v -> changeSpeaker());
        withMargin(intervalContainer,change,-1,48,0,0);
    }

    private void changeSpeaker() {
        Utterance turn = selectedUtterance();
        int count = currentRecordId.equals("demo-interview") ? 2 : currentRecordId.equals("demo-project") ? 4 : 3;
        String[] labels = new String[count];
        int chosen = 0;
        for (int i=0;i<count;i++) {
            labels[i]=speakerLabel("speaker_"+(i+1));
            if (turn.speakerId.equals("speaker_"+(i+1))) chosen=i;
        }
        new AlertDialog.Builder(this).setTitle(R.string.map_change_speaker)
                .setSingleChoiceItems(labels,chosen,(dialog,index) -> {
                    if (model.updateUtterance(currentRecordId,
                            turn.withTiming("speaker_"+(index+1),turn.startMs,turn.endMs))) dialog.dismiss();
                    else toast(R.string.operation_busy);
                }).setNegativeButton(R.string.cancel,null).show();
    }

    private SeekBar demoSeek(int progress,int max) {
        SeekBar bar = new SeekBar(this);
        bar.setMax(max);
        bar.setProgress(progress);
        bar.setProgressTintList(ColorStateList.valueOf(BimvoStyle.WHITE));
        bar.setThumbTintList(ColorStateList.valueOf(BimvoStyle.WHITE));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(BimvoStyle.TRACK));
        return bar;
    }

    private void editInterval() {
        Utterance turn = selectedUtterance();
        int duration = Math.max(1,(int)(current().transcriptDurationMs()/1_000));
        LinearLayout area = vertical();
        area.setPadding(dp(20),dp(8),dp(20),dp(5));
        TextView startLabel = text(getString(R.string.time_start,TranscriptText.clock(turn.startMs)),13,BimvoStyle.WHITE,false);
        area.addView(startLabel,lp(-1,27));
        SeekBar start = demoSeek((int)(turn.startMs/1_000),duration-1);
        area.addView(start,lp(-1,44));
        TextView endLabel = text(getString(R.string.time_end,TranscriptText.clock(turn.endMs)),13,BimvoStyle.WHITE,false);
        withMargin(area,endLabel,-1,27,11,0);
        SeekBar end = demoSeek((int)(turn.endMs/1_000),duration);
        area.addView(end,lp(-1,44));
        start.setContentDescription(s(R.string.time_start_accessible));
        end.setContentDescription(s(R.string.time_end_accessible));
        Runnable updateLabels = () -> {
            startLabel.setText(getString(R.string.time_start,TranscriptText.clock(start.getProgress()*1_000L)));
            endLabel.setText(getString(R.string.time_end,TranscriptText.clock(end.getProgress()*1_000L)));
        };
        start.setOnSeekBarChangeListener(seekCallback(value -> {
            if (value >= end.getProgress()) end.setProgress(value+1);
            updateLabels.run();
        }));
        end.setOnSeekBarChangeListener(seekCallback(value -> {
            if (value == 0) end.setProgress(1);
            if (end.getProgress() <= start.getProgress()) start.setProgress(Math.max(0,end.getProgress()-1));
            updateLabels.run();
        }));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.time_boundaries)
                .setView(area).setPositiveButton(R.string.apply,null)
                .setNegativeButton(R.string.cancel,null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            long begin = Math.min(start.getProgress(),duration-1)*1_000L;
            long finish = Math.max(start.getProgress()+1,end.getProgress())*1_000L;
            if (model.updateUtterance(currentRecordId,turn.withTiming(turn.speakerId,begin,finish))) dialog.dismiss();
            else toast(R.string.operation_busy);
        }));
        dialog.show();
    }

    private interface ValueChanged { void changed(int value); }
    private SeekBar.OnSeekBarChangeListener seekCallback(ValueChanged callback) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar,int value,boolean fromUser) {
                if (fromUser) callback.changed(value);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        };
    }

    private List<Recording> orderedRecordings() {
        List<Recording> order = new ArrayList<>(personalRecordings());
        Collator russian = Collator.getInstance(new Locale("ru","RU"));
        order.sort((a,b) -> {
            int result;
            switch (recordSort) {
                case 1: result=Long.compare(a.createdAt,b.createdAt); break;
                case 2: case 3:
                    if (a.durationMs < 0 && b.durationMs >= 0) return 1;
                    if (b.durationMs < 0 && a.durationMs >= 0) return -1;
                    result=recordSort==2 ? Long.compare(a.durationMs,b.durationMs) : Long.compare(b.durationMs,a.durationMs);
                    break;
                case 4: result=russian.compare(a.title,b.title); break;
                case 5: result=russian.compare(b.title,a.title); break;
                default: result=Long.compare(b.createdAt,a.createdAt);
            }
            return result == 0 ? a.id.compareTo(b.id) : result;
        });
        return order;
    }

    private void chooseRecordSort() {
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.sort_title)
                .setSingleChoiceItems(sortOptions(), recordSort, (choice, which) -> {
                    recordSort = which;
                    preferences.edit().putInt("sort", which).apply();
                    choice.dismiss();
                    populateRecords();
                    render();
                }).setNegativeButton(R.string.cancel, null).create();
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(shape(BimvoStyle.CARD, BimvoStyle.BORDER, 20));
            window.setGravity(Gravity.BOTTOM);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.getListView().setPadding(dp(8), dp(4), dp(8), dp(8));
        dialog.getListView().setChoiceMode(android.widget.ListView.CHOICE_MODE_SINGLE);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(BimvoStyle.WHITE);
    }

    private void records() {
        LinearLayout body=content(true);
        logoHeader(body,false);
        spacer(body,30);
        withMargin(body,headline(s(R.string.records_title)),-1,-2,0,9);
        EditText search=dialogInput(recordQuery,1);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP,13);
        search.setHint(R.string.search_hint);
        search.setHintTextColor(BimvoStyle.MUTED);
        search.setPadding(dp(12),0,dp(12),0);
        search.setBackground(shape(BimvoStyle.CARD,BimvoStyle.BORDER,7));
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setContentDescription(s(R.string.search_hint));
        search.setOnEditorActionListener((v,action,event) -> {
            if (action==EditorInfo.IME_ACTION_SEARCH) { hideKeyboard(); return true; }
            return false;
        });
        withMargin(body,search,-1,46,0,8);
        LinearLayout sorting=horizontal();
        sorting.setPadding(dp(14),0,dp(14),0);
        sorting.setBackground(shape(BimvoStyle.CARD,BimvoStyle.BORDER,10));
        sorting.setMinimumHeight(dp(48));
        sorting.setContentDescription(s(R.string.sort_title)+". "+s(SORT_LABELS[recordSort]));
        sorting.setOnClickListener(v -> chooseRecordSort());
        sorting.addView(text(s(R.string.sort_label),10,BimvoStyle.MUTED,true),new LinearLayout.LayoutParams(0,-1,1f));
        sorting.addView(text(s(SORT_LABELS[recordSort])+"  \u2304",12,BimvoStyle.WHITE,false),lp(-2,-1));
        withMargin(body,sorting,-1,48,0,12);
        recordsContainer=vertical();
        body.addView(recordsContainer,lp(-1,-2));
        populateRecords();
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value,int start,int count,int after) { }
            @Override public void onTextChanged(CharSequence value,int start,int before,int count) {
                recordQuery=value.toString(); populateRecords();
            }
            @Override public void afterTextChanged(Editable value) { }
        });
        addPrimary(body,s(R.string.new_audio_title),() -> forward(Page.UPLOAD),14);

    }

    private void populateRecords() {
        if (recordsContainer==null) return;
        recordsContainer.removeAllViews();
        int count=0;
        for (Recording r:orderedRecordings()) if (r.matches(recordQuery)) {
            addRecordingCard(recordsContainer,r); count++;
        }
        if (count==0) {
            withMargin(recordsContainer,section(s(R.string.search_empty)),-1,-2,20,8);
            note(recordsContainer,s(R.string.search_empty_detail),true);
            spacer(recordsContainer,24);
        }
    }

    private void export() {
        Recording r=current();
        LinearLayout body=content(navigation.isEmpty());
        logoHeader(body,!navigation.isEmpty());
        spacer(body,30);
        withMargin(body,headline(s(R.string.export_title)),-1,-2,0,10);
        withMargin(body,text(s(R.string.export_subtitle),13,BimvoStyle.MUTED,false),-1,-2,0,0);
        spacer(body,26);
        withMargin(body,caption(s(R.string.export_record)),-1,-2,0,10);
        withMargin(body,settingRow(s(R.string.export_audio),r.title,() -> {
            List<Recording> records = new ArrayList<>();
            for (Recording item : model.recordings())
                if (item.demo == (current() != null && current().demo)) records.add(item);
            String[] labels=new String[records.size()];
            int selected=0;
            for (int i=0;i<records.size();i++) {
                Recording item=records.get(i);
                labels[i]=item.title+" \u00b7 "+s(item.demo ? R.string.demo_badge : R.string.imported_status);
                if (item.id.equals(currentRecordId)) selected=i;
            }
            new AlertDialog.Builder(this).setTitle(R.string.export_record)
                    .setSingleChoiceItems(labels,selected,(dialog,index) -> {
                        currentRecordId=records.get(index).id;
                        dialog.dismiss(); render();
                    }).setNegativeButton(R.string.cancel,null).show();
        }),-1,53,0,26);
        withMargin(body,section(s(R.string.export_format)),-1,-2,0,15);
        for (String format:new String[]{"TXT","SRT","JSON"}) {
            LinearLayout row=horizontal();
            row.setPadding(dp(14),0,dp(13),0);
            clickable(row,BimvoStyle.CARD,format.equals(exportFormat) ? 0xFF777080 : BimvoStyle.BORDER,7,() -> {
                exportFormat=format; render();
            });
            row.addView(text(format,15,BimvoStyle.WHITE,false),new LinearLayout.LayoutParams(0,-1,1f));
            TextView sub=text(s(format.equals("TXT") ? R.string.export_txt
                    : format.equals("SRT") ? R.string.export_srt : R.string.export_json),11,BimvoStyle.MUTED,false);
            LinearLayout.LayoutParams subLp=lp(-2,-1);
            subLp.rightMargin=dp(12);
            row.addView(sub,subLp);
            FrameLayout selected=new FrameLayout(this);
            boolean active=format.equals(exportFormat);
            selected.setBackground(oval(active ? BimvoStyle.WHITE : BimvoStyle.CARD,
                    active ? Color.TRANSPARENT : BimvoStyle.MUTED));
            if (active) selected.addView(glyph(GlyphView.Icon.CHECK,BimvoStyle.BG,13),
                    new FrameLayout.LayoutParams(dp(13),dp(13),Gravity.CENTER));
            row.addView(selected,lp(20,20));
            row.setContentDescription(format+(active ? s(R.string.selected_suffix) : ""));
            withMargin(body,row,-1,57,0,9);
        }
        View action=primaryButton(getString(R.string.export_button,exportFormat),() -> {
            if (!model.prepareExport(r,exportFormat)) toast(R.string.operation_busy);
        });
        action.setEnabled(!r.transcript.isEmpty() && !model.busy());
        action.setAlpha(action.isEnabled() ? 1f : .45f);
        withMargin(body,action,-1,50,24,0);
        if (r.transcript.isEmpty()) note(body,s(R.string.export_no_transcript),true);
        else {
            if (r.demo) note(body,s(R.string.export_demo_notice),true);

        }
    }


    /** Optional, local-only introduction. The choice never gates features. */
    private void showOnboarding() {
        if (!onboardingPending || isFinishing() || isDestroyed()
                || (onboardingDialog != null && onboardingDialog.isShowing())) return;
        int[] choices={R.string.onboarding_lecture,R.string.onboarding_meeting,R.string.onboarding_personal};
        int[] summaries={R.string.onboarding_lecture_hint,R.string.onboarding_meeting_hint,R.string.onboarding_personal_hint};
        int[] titles={R.string.benefit_lecture_title,R.string.benefit_meeting_title,R.string.benefit_personal_title};
        int[][] benefitTitles={
                {R.string.benefit_lecture_1,R.string.benefit_lecture_2,R.string.benefit_lecture_3},
                {R.string.benefit_meeting_1,R.string.benefit_meeting_2,R.string.benefit_meeting_3},
                {R.string.benefit_personal_1,R.string.benefit_personal_2,R.string.benefit_personal_3}};
        int[][] details={
                {R.string.benefit_lecture_1_detail,R.string.benefit_lecture_2_detail,R.string.benefit_lecture_3_detail},
                {R.string.benefit_meeting_1_detail,R.string.benefit_meeting_2_detail,R.string.benefit_meeting_3_detail},
                {R.string.benefit_personal_1_detail,R.string.benefit_personal_2_detail,R.string.benefit_personal_3_detail}};
        LinearLayout panel=vertical();
        panel.setPadding(dp(22),dp(14),dp(22),dp(16));
        LinearLayout top=horizontal();
        top.addView(text("BIMVO",12,BimvoStyle.MUTED,true),new LinearLayout.LayoutParams(0,-1,1f));
        TextView skip=text(s(R.string.onboarding_skip),12,BimvoStyle.MUTED,false);
        skip.setGravity(Gravity.CENTER);
        skip.setOnClickListener(v -> finishOnboarding());
        top.addView(skip,lp(100,44));
        panel.addView(top,lp(-1,44));
        ScrollView scroll=new ScrollView(this) {
            @Override protected void onMeasure(int width,int height) {
                int available=Math.max(dp(150),getResources().getDisplayMetrics().heightPixels-dp(230));
                super.onMeasure(width,View.MeasureSpec.makeMeasureSpec(Math.min(dp(480),available),View.MeasureSpec.AT_MOST));
            }
        };
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout body=vertical();
        body.setPadding(0,dp(10),0,dp(5));
        scroll.addView(body,new ScrollView.LayoutParams(-1,-2));
        panel.addView(scroll,lp(-1,-2));
        boolean selecting=onboardingChoice<0;
        TextView title=text(s(selecting ? R.string.onboarding_title : titles[onboardingChoice]),26,BimvoStyle.WHITE,false);
        title.setLineSpacing(dp(3),1f);
        withMargin(body,title,-1,-2,0,12);
        TextView description=text(s(selecting ? R.string.onboarding_subtitle : choices[onboardingChoice]),13,BimvoStyle.MUTED,false);
        description.setLineSpacing(dp(4),1f);
        withMargin(body,description,-1,-2,0,22);
        for (int i=0;i<3;i++) {
            final int choice=i;
            LinearLayout item=horizontal();
            item.setPadding(dp(13),dp(15),dp(13),dp(15));
            if (selecting) clickable(item,BimvoStyle.BG,BimvoStyle.BORDER,9,() -> {
                onboardingChoice=choice;
                onboardingDialog.dismiss();
                onboardingDialog=null;
                showOnboarding();
            });
            else item.setBackground(shape(BimvoStyle.BG,BimvoStyle.BORDER,9));
            TextView number=text(String.format(Locale.ROOT,"%02d",i+1),11,BimvoStyle.MUTED,true);
            LinearLayout.LayoutParams numberLp=lp(29,-2);
            numberLp.gravity=Gravity.TOP;
            numberLp.topMargin=dp(3);
            item.addView(number,numberLp);
            LinearLayout words=vertical();
            words.addView(text(s(selecting ? choices[i] : benefitTitles[onboardingChoice][i]),16,BimvoStyle.WHITE,true));
            TextView explanation=text(s(selecting ? summaries[i] : details[onboardingChoice][i]),12,BimvoStyle.MUTED,false);
            explanation.setLineSpacing(dp(4),1f);
            withMargin(words,explanation,-1,-2,7,0);
            item.addView(words,new LinearLayout.LayoutParams(0,-2,1f));
            if (selecting) {
                LinearLayout.LayoutParams arrow=lp(16,16);
                arrow.leftMargin=dp(6);
                item.addView(glyph(GlyphView.Icon.ARROW,BimvoStyle.MUTED,16),arrow);
                item.setContentDescription(s(choices[i]));
            }
            withMargin(body,item,-1,-2,0,10);
        }
        if (!selecting) {
            withMargin(panel,primaryButton(s(R.string.onboarding_start),this::finishOnboarding),-1,50,16,0);
        }
        onboardingDialog = new AlertDialog.Builder(this).create();
        onboardingDialog.setView(panel, 0, 0, 0, 0);
        onboardingDialog.setCanceledOnTouchOutside(false);
        onboardingDialog.setOnCancelListener(dialog -> finishOnboarding());
        onboardingDialog.show();
        Window window=onboardingDialog.getWindow();
        if (window!=null) {
            window.setBackgroundDrawable(shape(BimvoStyle.CARD,BimvoStyle.BORDER,16));
            window.setDimAmount(.70f);
            window.setLayout(Math.min(dp(420),getResources().getDisplayMetrics().widthPixels-dp(32)),WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    private void finishOnboarding() {
        preferences.edit().putBoolean("onboardingDone",true).putInt("useCase",onboardingChoice).apply();
        onboardingPending=false;
        if (onboardingDialog!=null) { onboardingDialog.dismiss(); onboardingDialog=null; }
    }

    private void profile() {
        LinearLayout body = content(true);
        logoHeader(body,false);
        spacer(body,35);
        withMargin(body,headline("Профиль"),-1,-2,0,24);
        LinearLayout user = horizontal();
        user.setPadding(dp(14),dp(12),dp(13),dp(12));
        user.setBackground(shape(BimvoStyle.CARD,BimvoStyle.BORDER,7));
        FrameLayout avatar = new FrameLayout(this);
        avatar.setBackground(oval(BimvoStyle.BG,BimvoStyle.BORDER));
        avatar.addView(glyph(GlyphView.Icon.USER,BimvoStyle.WHITE,24),
                new FrameLayout.LayoutParams(dp(24),dp(24),Gravity.CENTER));
        user.addView(avatar,lp(46,46));
        LinearLayout name = vertical();
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0,-2,1f);
        nameLp.leftMargin=dp(12);
        user.addView(name,nameLp);
        name.addView(text("Личный профиль",16,BimvoStyle.WHITE,true));
        withMargin(name,text("На этом устройстве · без аккаунта",11,BimvoStyle.MUTED,false),-1,-2,5,0);
        withMargin(body,user,-1,73,0,18);
        addLibrarySummary(body);
        withMargin(body, settingRow("Демопримеры", "Отдельно от ваших записей", () -> forward(Page.DEMOS)), -1, 60, 18, 0);
        spacer(body,24);
        withMargin(body,section("Параметры"),-1,-2,0,16);

        withMargin(body,settingRow("О приложении","Версия " + BuildConfig.VERSION_NAME,() -> {
            new AlertDialog.Builder(this).setTitle("BIMVO")
                    .setMessage("BIMVO · версия " + BuildConfig.VERSION_NAME + "\n\n" + s(R.string.about_updated))
                    .setPositiveButton("Закрыть",null).show();
        }),-1,54,0,24);
        withMargin(body,section("Приватность"),-1,-2,0,12);
        LinearLayout privacy = card(13,13);
        privacy.addView(text("Локально · конфиденциально",14,BimvoStyle.WHITE,true));
        TextView note = text(s(R.string.privacy_updated),12,BimvoStyle.MUTED,false);
        note.setLineSpacing(dp(4),1f);
        withMargin(privacy,note,-1,-2,9,0);
        withMargin(body,privacy,-1,-2,0,0);
        withMargin(body,secondaryButton("К стартовому экрану",this::enterLogin),-1,48,30,0);
    }
}
