package ru.slava.videotranslator;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

public class TranslationOverlayService extends Service {
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";
    public static final String EXTRA_AUDIO_SOURCE = "audio_source";
    public static final String ACTION_STOP = "ru.slava.videotranslator.STOP";

    private static final int NOTIFICATION_ID = 42;
    private static final String CHANNEL_ID = "translator_live";

    private WindowManager windowManager;
    private LinearLayout overlay;
    private TextView screenText;
    private TextView speechText;
    private TextView miniStatus;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread imageThread;
    private Handler imageHandler;
    private TextRecognizer textRecognizer;
    private TranslationEngine translation;
    private VoskModelManager modelManager;
    private AudioTranscriber transcriber;
    private long lastOcrAt;
    private String lastOcrSource = "";
    private String lastSpeechSource = "";
    private final AtomicBoolean ocrBusy = new AtomicBoolean(false);

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        createOverlay();
        translation = new TranslationEngine(this);
        modelManager = new VoskModelManager(this);
        textRecognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startAsForeground(false);
        if (intent == null) return START_NOT_STICKY;
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1);
        Intent data;
        if (Build.VERSION.SDK_INT >= 33) {
            data = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        } else {
            data = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        }
        if (resultCode == -1 || data == null) {
            setStatus("Нет разрешения на захват экрана");
            return START_NOT_STICKY;
        }
        boolean useMic = "mic".equals(intent.getStringExtra(EXTRA_AUDIO_SOURCE));
        if (useMic) startAsForeground(true);

        try {
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = mpm.getMediaProjection(resultCode, data);
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { stopSelf(); }
            }, new Handler(getMainLooper()));
            startScreenCapture();
            prepareTranslationAndSpeech(useMic);
        } catch (Exception e) {
            setStatus("Ошибка запуска: " + e.getMessage());
        }
        return START_NOT_STICKY;
    }

    private void prepareTranslationAndSpeech(boolean useMic) {
        setStatus("Готовлю переводчик…");
        translation.prepare(() -> {
            setStatus("Модель перевода готова");
            modelManager.ensureModel(this::setStatus, modelFile -> {
                setStatus("Модель речи готова");
                transcriber = new AudioTranscriber(this, projection, useMic);
                transcriber.start(modelFile, this::onChineseSpeech, this::setStatus);
            }, e -> setStatus("Не удалось скачать модель речи: " + e.getMessage()));
        }, e -> setStatus("Не удалось скачать модель перевода: " + e.getMessage()));
    }

    private void onChineseSpeech(String chinese) {
        if (chinese.equals(lastSpeechSource)) return;
        lastSpeechSource = chinese;
        translation.translate(chinese, ru -> runOnMain(() -> speechText.setText(ru)));
    }

    private void startScreenCapture() {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int width = dm.widthPixels;
        int height = dm.heightPixels;
        int density = dm.densityDpi;
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        imageThread = new HandlerThread("screen-ocr");
        imageThread.start();
        imageHandler = new Handler(imageThread.getLooper());
        imageReader.setOnImageAvailableListener(reader -> {
            Image image = null;
            try {
                image = reader.acquireLatestImage();
                if (image == null) return;
                long now = System.currentTimeMillis();
                if (now - lastOcrAt < 900 || !ocrBusy.compareAndSet(false, true)) return;
                lastOcrAt = now;
                Bitmap bitmap = imageToBitmap(image, width, height);
                processOcr(bitmap);
            } catch (Exception e) {
                ocrBusy.set(false);
            } finally {
                if (image != null) image.close();
            }
        }, imageHandler);

        virtualDisplay = projection.createVirtualDisplay(
                "LiveTranslatorScreen",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, imageHandler);
    }

    private Bitmap imageToBitmap(Image image, int width, int height) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int rowPadding = rowStride - pixelStride * width;
        Bitmap padded = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(buffer);
        Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, width, height);
        if (cropped != padded) padded.recycle();
        return cropped;
    }

    private void processOcr(Bitmap bitmap) {
        InputImage input = InputImage.fromBitmap(bitmap, 0);
        textRecognizer.process(input)
                .addOnSuccessListener(result -> {
                    String src = result.getText().trim();
                    if (src.length() > 500) src = src.substring(0, 500);
                    if (!src.isEmpty() && !src.equals(lastOcrSource)) {
                        lastOcrSource = src;
                        translation.translate(src, ru -> runOnMain(() -> screenText.setText(ru)));
                    }
                })
                .addOnCompleteListener(task -> {
                    bitmap.recycle();
                    ocrBusy.set(false);
                });
    }

    private void createOverlay() {
        if (!Settings.canDrawOverlays(this)) return;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setPadding(dp(12), dp(8), dp(12), dp(8));
        overlay.setBackgroundColor(0x33000000);

        screenText = overlayText(15);
        speechText = overlayText(22);
        speechText.setGravity(Gravity.CENTER);
        miniStatus = overlayText(12);
        miniStatus.setTextColor(0xffdddddd);
        miniStatus.setGravity(Gravity.END);

        overlay.addView(screenText, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams spacer = new LinearLayout.LayoutParams(-1, 0, 1f);
        overlay.addView(new TextView(this), spacer);
        overlay.addView(speechText, new LinearLayout.LayoutParams(-1, -2));
        overlay.addView(miniStatus, new LinearLayout.LayoutParams(-1, -2));

        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_SECURE;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        windowManager.addView(overlay, lp);
    }

    private TextView overlayText(int sp) {
        TextView tv = new TextView(this);
        tv.setTextSize(sp);
        tv.setTextColor(Color.WHITE);
        tv.setShadowLayer(5f, 1f, 1f, Color.BLACK);
        tv.setPadding(dp(8), dp(6), dp(8), dp(6));
        tv.setBackgroundColor(0x66000000);
        return tv;
    }

    private void setStatus(String text) {
        runOnMain(() -> { if (miniStatus != null) miniStatus.setText(text); });
    }

    private void runOnMain(Runnable r) {
        new Handler(getMainLooper()).post(r);
    }

    private void startAsForeground(boolean mic) {
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentTitle("Китайский → Русский LIVE")
                .setContentText("Перевод речи и текста с экрана активен")
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            if (mic) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            startForeground(NOTIFICATION_ID, n, type);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CHANNEL_ID, "Live перевод", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(c);
        }
    }

    @Override
    public void onDestroy() {
        if (transcriber != null) transcriber.stop();
        if (virtualDisplay != null) virtualDisplay.release();
        if (imageReader != null) imageReader.close();
        if (projection != null) projection.stop();
        if (textRecognizer != null) textRecognizer.close();
        if (translation != null) translation.close();
        if (imageThread != null) imageThread.quitSafely();
        if (windowManager != null && overlay != null) {
            try { windowManager.removeView(overlay); } catch (Exception ignored) { }
        }
        super.onDestroy();
    }

    @Nullable @Override
    public IBinder onBind(Intent intent) { return null; }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
