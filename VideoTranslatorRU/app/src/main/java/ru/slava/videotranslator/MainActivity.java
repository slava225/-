package ru.slava.videotranslator;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {
    private static final int ID_SYSTEM = 2001;
    private static final int ID_MIC = 2002;

    private MediaProjectionManager projectionManager;
    private Intent projectionData;
    private int projectionResultCode = Activity.RESULT_CANCELED;
    private Uri selectedVideoUri;
    private String currentMode = SubtitleSettings.MODE_LIVE;
    private boolean updatingModeUi;

    private RadioGroup sourceGroup;
    private TextView overlayState;
    private TextView micState;
    private TextView captureState;
    private TextView modelState;
    private TextView status;
    private TextView modeHint;
    private TextView selectedVideoState;
    private Button captureButton;
    private Button startButton;
    private Button videoTab;
    private Button liveTab;
    private LinearLayout videoPickerCard;
    private SwitchCompat speechToggle;
    private SwitchCompat ocrToggle;
    private SwitchCompat originalToggle;

    private final ActivityResultLauncher<Intent> projectionLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
                    projectionData = null;
                    projectionResultCode = Activity.RESULT_CANCELED;
                    setStatus("Доступ к экрану не выдан. Нажми кнопку ещё раз и подтверди системное окно.", true);
                } else {
                    projectionResultCode = result.getResultCode();
                    projectionData = new Intent(result.getData());
                    setStatus("Доступ к экрану выдан. Теперь можно запускать перевод.", false);
                    Toast.makeText(this, "Захват экрана разрешён", Toast.LENGTH_SHORT).show();
                }
                updatePermissionCards();
            });

    private final ActivityResultLauncher<String[]> permissionsLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                updatePermissionCards();
                if (speechToggle != null && speechToggle.isChecked()
                        && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
                    setStatus("Для перевода речи нужно разрешение на микрофон. Перевод изображения может работать без него.", true);
                }
            });

    private final ActivityResultLauncher<String[]> openVideoLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                selectedVideoUri = uri;
                try {
                    getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) { }
                if (selectedVideoState != null) {
                    selectedVideoState.setText("✓  Выбрано: " + displayName(uri));
                    selectedVideoState.setTextColor(0xff7ce3ac);
                }
                setStatus("Видео выбрано. Выдай доступ к экрану и запускай перевод.", false);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        currentMode = SubtitleSettings.lastMode(this);
        setContentView(buildUi());
        requestBasicPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionCards();
    }

    private View buildUi() {
        FrameLayout shell = new FrameLayout(this);
        shell.setBackgroundColor(Color.rgb(10, 14, 24));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        shell.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(20), dp(18), dp(32));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        TextView badge = label("CHINESE → RUSSIAN", 12, true, 0xff9cb6ff);
        badge.setPadding(dp(10), dp(6), dp(10), dp(6));
        badge.setBackground(rounded(0xff18213a, 99, 0xff2e416f));
        root.addView(badge, new LinearLayout.LayoutParams(-2, -2));

        TextView title = label("Перевод видео и эфиров", 29, true, Color.WHITE);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, -2);
        titleLp.topMargin = dp(14);
        root.addView(title, titleLp);

        TextView intro = label(
                "Выбери режим ниже. Перевод речи и перевод китайского текста с изображения управляются отдельно.",
                16, false, 0xffaeb9cf);
        LinearLayout.LayoutParams introLp = new LinearLayout.LayoutParams(-1, -2);
        introLp.topMargin = dp(7);
        introLp.bottomMargin = dp(16);
        root.addView(intro, introLp);

        root.addView(sectionTitle("Режим"));
        LinearLayout tabCard = card();
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        videoTab = tabButton("▶  Видео");
        liveTab = tabButton("●  Трансляция");
        tabs.addView(videoTab, new LinearLayout.LayoutParams(0, dp(52), 1f));
        LinearLayout.LayoutParams liveLp = new LinearLayout.LayoutParams(0, dp(52), 1f);
        liveLp.leftMargin = dp(8);
        tabs.addView(liveTab, liveLp);
        tabCard.addView(tabs);

        modeHint = label("", 13, false, 0xff9eabc2);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(-1, -2);
        hintLp.topMargin = dp(10);
        tabCard.addView(modeHint, hintLp);
        root.addView(tabCard, cardLp());

        videoTab.setOnClickListener(v -> setMode(SubtitleSettings.MODE_VIDEO));
        liveTab.setOnClickListener(v -> setMode(SubtitleSettings.MODE_LIVE));

        videoPickerCard = card();
        TextView videoTitle = label("Видео с телефона", 16, true, 0xffe8efff);
        videoPickerCard.addView(videoTitle);
        selectedVideoState = label("Видео пока не выбрано", 13, false, 0xff8e9bb2);
        LinearLayout.LayoutParams selectedLp = new LinearLayout.LayoutParams(-1, -2);
        selectedLp.topMargin = dp(6);
        selectedLp.bottomMargin = dp(5);
        videoPickerCard.addView(selectedVideoState, selectedLp);
        Button selectVideo = primaryButton("Выбрать видео", false);
        selectVideo.setOnClickListener(v -> openVideoLauncher.launch(new String[]{"video/*"}));
        videoPickerCard.addView(selectVideo, buttonLp());
        root.addView(videoPickerCard, cardLp());

        root.addView(sectionTitle("Готовность"));
        LinearLayout readiness = card();
        overlayState = stateRow(readiness, "Поверх других приложений");
        micState = stateRow(readiness, "Доступ к микрофону");
        captureState = stateRow(readiness, "Захват экрана");
        modelState = stateRow(readiness, "Модель китайской речи");
        root.addView(readiness, cardLp());

        root.addView(sectionTitle("Разрешения"));
        LinearLayout permissionsCard = card();
        Button overlayButton = primaryButton("Разрешить показ поверх окон", false);
        overlayButton.setOnClickListener(v -> requestOverlayPermission());
        permissionsCard.addView(overlayButton, buttonLp());

        captureButton = primaryButton("Выдать доступ к захвату экрана", false);
        captureButton.setOnClickListener(v -> requestScreenCapture());
        permissionsCard.addView(captureButton, buttonLp());
        root.addView(permissionsCard, cardLp());

        root.addView(sectionTitle("Источник речи"));
        LinearLayout sourceCard = card();
        sourceGroup = new RadioGroup(this);
        sourceGroup.setOrientation(RadioGroup.VERTICAL);
        RadioButton system = radio("Системный звук", "Лучшее качество для видео, Bilibili, Weibo и браузера", ID_SYSTEM);
        RadioButton mic = radio("Микрофон", "Запасной режим, если приложение блокирует внутренний звук", ID_MIC);
        sourceGroup.addView(system);
        sourceGroup.addView(mic);
        sourceGroup.check(ID_SYSTEM);
        sourceCard.addView(sourceGroup, new LinearLayout.LayoutParams(-1, -2));
        root.addView(sourceCard, cardLp());

        root.addView(sectionTitle("Что переводить"));
        LinearLayout modeCard = card();
        speechToggle = switchLike("Речь → русские субтитры", true);
        ocrToggle = switchLike("Перевод китайского текста с изображения", false);
        originalToggle = switchLike("Показывать китайский оригинал над переводом", SubtitleSettings.showOriginal(this));
        modeCard.addView(speechToggle);
        modeCard.addView(ocrToggle);

        TextView ocrNote = label(
                "Перевод изображения выключен по умолчанию. Включай его только когда в кадре действительно нужно переводить китайские надписи. Латиница, цифры и обычный интерфейс отбрасываются.",
                12, false, 0xff8492aa);
        ocrNote.setPadding(dp(4), 0, dp(4), dp(8));
        modeCard.addView(ocrNote);
        modeCard.addView(originalToggle);
        root.addView(modeCard, cardLp());

        root.addView(sectionTitle("Вид субтитров"));
        LinearLayout styleCard = card();
        styleCard.addView(sliderBlock("Размер речи", 16, 28, SubtitleSettings.speechSize(this), "speech_size"));
        styleCard.addView(sliderBlock("Размер текста с изображения", 12, 22, SubtitleSettings.ocrSize(this), "ocr_size"));
        styleCard.addView(sliderBlock("Прозрачность фона", 35, 90, SubtitleSettings.backgroundAlpha(this), "background_alpha"));
        root.addView(styleCard, cardLp());

        root.addView(sectionTitle("Запуск"));
        LinearLayout launchCard = card();
        startButton = primaryButton("▶  Запустить перевод", true);
        startButton.setOnClickListener(v -> startTranslation());
        launchCard.addView(startButton, buttonLp());

        Button stop = dangerButton("Остановить перевод");
        stop.setOnClickListener(v -> stopTranslation());
        launchCard.addView(stop, buttonLp());
        root.addView(launchCard, cardLp());

        status = label("Готово к настройке.", 14, false, 0xffc6d0e5);
        status.setPadding(dp(14), dp(12), dp(14), dp(12));
        status.setBackground(rounded(0xff121a2b, 16, 0xff283653));
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(-1, -2);
        statusLp.topMargin = dp(14);
        root.addView(status, statusLp);

        TextView help = label(
                "Для трансляции: запусти перевод и открой эфир. Для видео: сначала выбери файл — после запуска приложение само откроет его в видеоплеере. Если системный звук не ловится, используй «Микрофон».",
                13, false, 0xff7f8da7);
        LinearLayout.LayoutParams helpLp = new LinearLayout.LayoutParams(-1, -2);
        helpLp.topMargin = dp(14);
        root.addView(help, helpLp);

        speechToggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!updatingModeUi) SubtitleSettings.setSpeechEnabled(this, currentMode, isChecked);
        });
        ocrToggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!updatingModeUi) SubtitleSettings.setOcrEnabled(this, currentMode, isChecked);
        });
        originalToggle.setOnCheckedChangeListener((buttonView, isChecked) ->
                SubtitleSettings.prefs(this).edit().putBoolean("show_original", isChecked).apply());

        applyModeUi();
        updatePermissionCards();
        return shell;
    }

    private void setMode(String mode) {
        currentMode = SubtitleSettings.normalizeMode(mode);
        SubtitleSettings.setLastMode(this, currentMode);
        applyModeUi();
        setStatus(SubtitleSettings.MODE_VIDEO.equals(currentMode)
                ? "Режим видео выбран. Выбери файл, если ещё не выбрал."
                : "Режим трансляции выбран. После запуска открой нужный прямой эфир.", false);
    }

    private void applyModeUi() {
        if (videoTab == null || liveTab == null) return;
        boolean video = SubtitleSettings.MODE_VIDEO.equals(currentMode);
        styleTab(videoTab, video);
        styleTab(liveTab, !video);

        if (modeHint != null) {
            modeHint.setText(video
                    ? "Видео: выбираешь файл на телефоне, запускаешь перевод — видео откроется автоматически."
                    : "Трансляция: запускаешь перевод, сворачиваешь приложение и открываешь Bilibili, Weibo, браузер или другой эфир.");
        }
        if (videoPickerCard != null) videoPickerCard.setVisibility(video ? View.VISIBLE : View.GONE);

        updatingModeUi = true;
        if (speechToggle != null) speechToggle.setChecked(SubtitleSettings.speechEnabled(this, currentMode));
        if (ocrToggle != null) {
            ocrToggle.setChecked(SubtitleSettings.ocrEnabled(this, currentMode));
            ocrToggle.setText(video
                    ? "Перевод китайского текста/надписей в кадре"
                    : "Перевод китайского текста/картинок в эфире");
        }
        updatingModeUi = false;

        if (startButton != null) {
            startButton.setText(video ? "▶  Запустить перевод видео" : "●  Запустить перевод трансляции");
        }
    }

    private void requestScreenCapture() {
        if (!Settings.canDrawOverlays(this)) {
            setStatus("Сначала разреши показ поверх других приложений.", true);
            requestOverlayPermission();
            return;
        }
        setStatus("Подтверди системное окно Android для захвата экрана.", false);
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent());
    }

    private void startTranslation() {
        saveUiSettings();
        if (!Settings.canDrawOverlays(this)) {
            setStatus("Не разрешён показ поверх приложений.", true);
            requestOverlayPermission();
            return;
        }
        if (speechToggle.isChecked()
                && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsLauncher.launch(new String[]{Manifest.permission.RECORD_AUDIO});
            setStatus("Для перевода речи нужно разрешение на микрофон. Если нужна только картинка — выключи перевод речи.", true);
            return;
        }
        if (projectionResultCode != Activity.RESULT_OK || projectionData == null) {
            setStatus("Сначала нажми «Выдать доступ к захвату экрана».", true);
            requestScreenCapture();
            return;
        }
        if (!speechToggle.isChecked() && !ocrToggle.isChecked()) {
            setStatus("Включи хотя бы перевод речи или перевод китайского текста с изображения.", true);
            return;
        }
        if (SubtitleSettings.MODE_VIDEO.equals(currentMode) && selectedVideoUri == null) {
            setStatus("Во вкладке «Видео» сначала выбери видео с телефона.", true);
            openVideoLauncher.launch(new String[]{"video/*"});
            return;
        }

        Intent service = new Intent(this, TranslationOverlayService.class);
        service.putExtra(TranslationOverlayService.EXTRA_RESULT_CODE, projectionResultCode);
        service.putExtra(TranslationOverlayService.EXTRA_RESULT_DATA, new Intent(projectionData));
        service.putExtra(TranslationOverlayService.EXTRA_AUDIO_SOURCE,
                sourceGroup.getCheckedRadioButtonId() == ID_MIC ? "mic" : "system");
        service.putExtra(TranslationOverlayService.EXTRA_ENABLE_SPEECH, speechToggle.isChecked());
        service.putExtra(TranslationOverlayService.EXTRA_ENABLE_OCR, ocrToggle.isChecked());
        service.putExtra(TranslationOverlayService.EXTRA_MODE, currentMode);
        ContextCompat.startForegroundService(this, service);

        projectionData = null;
        projectionResultCode = Activity.RESULT_CANCELED;
        updatePermissionCards();

        if (SubtitleSettings.MODE_VIDEO.equals(currentMode)) {
            setStatus("Перевод видео запущен. Открываю выбранный файл…", false);
            startButton.postDelayed(this::launchSelectedVideo, 450);
        } else {
            setStatus("Перевод трансляции запущен. Теперь открой нужный прямой эфир.", false);
            Toast.makeText(this, "Перевод эфира запущен", Toast.LENGTH_LONG).show();
        }
    }

    private void launchSelectedVideo() {
        if (selectedVideoUri == null) return;
        Intent view = new Intent(Intent.ACTION_VIEW);
        view.setDataAndType(selectedVideoUri, "video/*");
        view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(view);
        } catch (Exception e) {
            setStatus("Не найден видеоплеер для выбранного файла.", true);
        }
    }

    private void stopTranslation() {
        Intent i = new Intent(this, TranslationOverlayService.class);
        i.setAction(TranslationOverlayService.ACTION_STOP);
        try { startService(i); } catch (Exception ignored) { }
        setStatus("Перевод остановлен. Для следующего запуска снова выдай доступ к экрану.", false);
        updatePermissionCards();
    }

    private void requestBasicPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 33) {
            permissionsLauncher.launch(new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS});
        } else {
            permissionsLauncher.launch(new String[]{Manifest.permission.RECORD_AUDIO});
        }
    }

    private void requestOverlayPermission() {
        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
        startActivity(i);
    }

    private void updatePermissionCards() {
        if (overlayState == null) return;
        setState(overlayState, Settings.canDrawOverlays(this), "Разрешено", "Не разрешено");
        boolean mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        setState(micState, mic, "Разрешено", "Не разрешено");
        setState(captureState, projectionResultCode == Activity.RESULT_OK && projectionData != null,
                "Выдано на текущий запуск", "Нужно выдать");

        java.io.File model = new java.io.File(getFilesDir(), "models/" + VoskModelManager.MODEL_DIR + "/am/final.mdl");
        setState(modelState, model.exists(), "Установлена", "Скачается при первом переводе речи");

        if (captureButton != null) {
            captureButton.setText((projectionData != null) ? "✓ Доступ к экрану выдан" : "Выдать доступ к захвату экрана");
        }
    }

    private String displayName(Uri uri) {
        String name = "video";
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) name = cursor.getString(index);
            }
        } catch (Exception ignored) { }
        return name == null ? "video" : name;
    }

    private void saveUiSettings() {
        SubtitleSettings.setSpeechEnabled(this, currentMode, speechToggle.isChecked());
        SubtitleSettings.setOcrEnabled(this, currentMode, ocrToggle.isChecked());
        SubtitleSettings.prefs(this).edit().putBoolean("show_original", originalToggle.isChecked()).apply();
    }

    private void setState(TextView tv, boolean ok, String yes, String no) {
        tv.setText(ok ? "●  " + yes : "●  " + no);
        tv.setTextColor(ok ? 0xff5fe3a1 : 0xffffb86b);
    }

    private TextView stateRow(LinearLayout parent, String title) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));
        TextView left = label(title, 15, false, 0xffdce5f7);
        TextView right = label("●  Проверяю…", 13, true, 0xffffb86b);
        row.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(right, new LinearLayout.LayoutParams(-2, -2));
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return right;
    }

    private RadioButton radio(String title, String subtitle, int id) {
        RadioButton rb = new RadioButton(this);
        rb.setId(id);
        rb.setText(title + "\n" + subtitle);
        rb.setTextColor(0xffe9effb);
        rb.setTextSize(15);
        rb.setButtonTintList(android.content.res.ColorStateList.valueOf(0xff7c9cff));
        rb.setPadding(0, dp(8), 0, dp(8));
        return rb;
    }

    private SwitchCompat switchLike(String text, boolean checked) {
        SwitchCompat cb = new SwitchCompat(this);
        cb.setText(text);
        cb.setTextColor(0xffe9effb);
        cb.setTextSize(15);
        cb.setChecked(checked);
        cb.setPadding(0, dp(7), 0, dp(7));
        return cb;
    }

    private Button tabButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(8), dp(8), dp(8), dp(8));
        return b;
    }

    private void styleTab(Button button, boolean active) {
        button.setTextColor(active ? Color.WHITE : 0xff94a2bb);
        button.setBackground(rounded(active ? 0xff5f7fff : 0xff151d2d, 15,
                active ? 0xff8aa0ff : 0xff2d3b54));
    }

    private View sliderBlock(String title, int min, int max, int current, String key) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(0, dp(8), 0, dp(8));
        TextView value = label(title + ": " + current + (key.contains("alpha") ? "%" : ""), 14, true, 0xffdce5f7);
        block.addView(value, new LinearLayout.LayoutParams(-1, -2));
        SeekBar seek = new SeekBar(this);
        seek.setMax(max - min);
        seek.setProgress(Math.max(0, Math.min(max - min, current - min)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int actual = min + progress;
                value.setText(title + ": " + actual + (key.contains("alpha") ? "%" : ""));
                SubtitleSettings.prefs(MainActivity.this).edit().putInt(key, actual).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        block.addView(seek, new LinearLayout.LayoutParams(-1, -2));
        return block;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(13), dp(16), dp(13));
        card.setBackground(rounded(0xff111827, 20, 0xff28344b));
        return card;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(18);
        return lp;
    }

    private TextView sectionTitle(String text) {
        TextView tv = label(text, 14, true, 0xff8fa5c9);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(7);
        tv.setLayoutParams(lp);
        return tv;
    }

    private Button primaryButton(String text, boolean strong) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setTextColor(Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
        b.setBackground(rounded(strong ? 0xff5f7fff : 0xff1c2942, 16, strong ? 0xff7893ff : 0xff344664));
        return b;
    }

    private Button dangerButton(String text) {
        Button b = primaryButton(text, false);
        b.setTextColor(0xffff9b9b);
        b.setBackground(rounded(0xff2a171c, 16, 0xff63323d));
        return b;
    }

    private LinearLayout.LayoutParams buttonLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(54));
        lp.topMargin = dp(7);
        lp.bottomMargin = dp(2);
        return lp;
    }

    private GradientDrawable rounded(int fill, int radiusDp, int stroke) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fill);
        gd.setCornerRadius(dp(radiusDp));
        gd.setStroke(dp(1), stroke);
        return gd;
    }

    private TextView label(String value, int sp, boolean bold, int color) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        if (bold) tv.setTypeface(tv.getTypeface(), Typeface.BOLD);
        return tv;
    }

    private void setStatus(String message, boolean error) {
        if (status == null) return;
        status.setText((error ? "⚠  " : "✓  ") + message);
        status.setTextColor(error ? 0xffffc0a3 : 0xffcdebdc);
        status.setBackground(rounded(error ? 0xff2a2019 : 0xff12241f, 16,
                error ? 0xff6d4c32 : 0xff315747));
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
