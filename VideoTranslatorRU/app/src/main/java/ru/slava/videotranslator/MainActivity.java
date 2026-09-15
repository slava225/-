package ru.slava.videotranslator;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {
    private RadioGroup sourceGroup;
    private TextView status;
    private MediaProjectionManager projectionManager;

    private final ActivityResultLauncher<Intent> projectionLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
                    setStatus("Захват экрана не разрешён.");
                    return;
                }
                Intent service = new Intent(this, TranslationOverlayService.class);
                service.putExtra(TranslationOverlayService.EXTRA_RESULT_CODE, result.getResultCode());
                service.putExtra(TranslationOverlayService.EXTRA_RESULT_DATA, result.getData());
                service.putExtra(TranslationOverlayService.EXTRA_AUDIO_SOURCE,
                        sourceGroup.getCheckedRadioButtonId() == 2002 ? "mic" : "system");
                ContextCompat.startForegroundService(this, service);
                setStatus("Перевод запущен. Теперь открой видео, Bilibili, Weibo, браузер или другую трансляцию.");
                Toast.makeText(this, "Перевод запущен поверх экрана", Toast.LENGTH_LONG).show();
            });

    private final ActivityResultLauncher<String[]> permissionsLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> { });

    private final ActivityResultLauncher<String> openVideoLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri == null) return;
                Intent view = new Intent(Intent.ACTION_VIEW);
                view.setDataAndType(uri, "video/*");
                view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    startActivity(view);
                } catch (Exception e) {
                    Toast.makeText(this, "Не найден видеоплеер для этого файла", Toast.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        setContentView(buildUi());
        requestBasicPermissions();
    }

    private View buildUi() {
        int pad = dp(18);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        scroll.addView(root);

        TextView title = text("Китайский → Русский LIVE", 26, true);
        root.addView(title, matchWrap());

        TextView intro = text(
                "Переводит китайскую речь в русские субтитры и китайский текст с экрана — поверх видео и прямых эфиров.\n\n" +
                "Первый запуск скачает: китайскую модель речи Vosk (~42 МБ) и модель перевода ML Kit.", 16, false);
        intro.setPadding(0, dp(12), 0, dp(16));
        root.addView(intro, matchWrap());

        TextView sourceLabel = text("Источник речи", 18, true);
        root.addView(sourceLabel, matchWrap());

        sourceGroup = new RadioGroup(this);
        sourceGroup.setOrientation(RadioGroup.VERTICAL);
        RadioButton system = new RadioButton(this);
        system.setId(2001);
        system.setText("Системный звук — для видео/стримов (лучшее качество)");
        system.setTextSize(16);
        sourceGroup.addView(system);
        RadioButton mic = new RadioButton(this);
        mic.setId(2002);
        mic.setText("Микрофон — если приложение запрещает захват своего звука");
        mic.setTextSize(16);
        sourceGroup.addView(mic);
        sourceGroup.check(2001);
        root.addView(sourceGroup, matchWrap());

        Button overlay = button("1. Разрешить показ поверх приложений");
        overlay.setOnClickListener(v -> requestOverlayPermission());
        root.addView(overlay, buttonLp());

        Button start = button("2. Запустить перевод экрана + речи");
        start.setOnClickListener(v -> startTranslation());
        root.addView(start, buttonLp());

        Button openVideo = button("3. Открыть видео с телефона");
        openVideo.setOnClickListener(v -> openVideoLauncher.launch("video/*"));
        root.addView(openVideo, buttonLp());

        Button stop = button("Остановить перевод");
        stop.setOnClickListener(v -> {
            Intent i = new Intent(this, TranslationOverlayService.class);
            i.setAction(TranslationOverlayService.ACTION_STOP);
            startService(i);
            setStatus("Перевод остановлен.");
        });
        root.addView(stop, buttonLp());

        status = text("Готово к запуску.", 15, false);
        status.setPadding(0, dp(16), 0, dp(12));
        root.addView(status, matchWrap());

        TextView help = text(
                "Как смотреть трансляцию:\n" +
                "• нажми «Запустить перевод» и подтверди захват экрана;\n" +
                "• сверни приложение и открой стрим;\n" +
                "• русский перевод речи появится снизу, перевод текста с картинки — сверху.\n\n" +
                "Важно: Android разрешает захват внутреннего звука только если приложение-источник его не запретило. Если снизу нет субтитров — выбери режим «Микрофон».",
                14, false);
        help.setTextColor(Color.LTGRAY);
        root.addView(help, matchWrap());

        return scroll;
    }

    private void startTranslation() {
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission();
            setStatus("Сначала разреши показ поверх приложений, затем нажми запуск ещё раз.");
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsLauncher.launch(new String[]{Manifest.permission.RECORD_AUDIO});
            setStatus("Нужно разрешение на запись звука. После разрешения нажми запуск ещё раз.");
            return;
        }
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent());
    }

    private void requestBasicPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            permissionsLauncher.launch(new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS});
        } else {
            permissionsLauncher.launch(new String[]{Manifest.permission.RECORD_AUDIO});
        }
    }

    private void requestOverlayPermission() {
        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        startActivity(i);
    }

    private void setStatus(String s) {
        if (status != null) status.setText(s);
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextSize(sp);
        tv.setTextColor(Color.WHITE);
        if (bold) tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        return tv;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(16);
        return b;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams buttonLp() {
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(10);
        return lp;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
