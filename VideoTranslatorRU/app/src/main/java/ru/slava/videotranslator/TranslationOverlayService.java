package ru.slava.videotranslator;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
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
    public static final String EXTRA_ENABLE_SPEECH = "enable_speech";
    public static final String EXTRA_ENABLE_OCR = "enable_ocr";
    public static final String ACTION_STOP = "ru.slava.videotranslator.STOP";

    private static final int NOTIFICATION_ID = 42;
    private static final String CHANNEL_ID = "translator_live";

    private WindowManager windowManager;
    private LinearLayout speechCard;
    private LinearLayout ocrCard;
    private TextView speechText;
    private TextView ocrText;
    private TextView statusChip;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread imageThread;
    private Handler imageHandler;
    private final Handler mainHandler = new Handler(android.os.Looper.getMainLooper());
    private TextRecognizer textRecognizer;
    private TranslationEngine translation;
    private VoskModelManager modelManager;
    private AudioTranscriber transcriber;

    private long lastOcrAt;
    private String lastOcrSource = "";
    private String lastSpeechSource = "";
    private final AtomicBoolean ocrBusy = new AtomicBoolean(false);
    private boolean enableSpeech = true;
    private boolean enableOcr = true;
    private boolean showOriginal = false;
    private boolean useMic = false;

    private final Runnable hideSpeech = () -> setCardVisible(speechCard, false);
    private final Runnable hideOcr = () -> setCardVisible(ocrCard, false);

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
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
        if (intent == null) return START_NOT_STICKY;

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
        Intent data;
        if (Build.VERSION.SDK_INT >= 33) {
            data = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        } else {
            //noinspection deprecation
            data = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        }
        useMic = "mic".equals(intent.getStringExtra(EXTRA_AUDIO_SOURCE));
        enableSpeech = intent.getBooleanExtra(EXTRA_ENABLE_SPEECH, SubtitleSettings.speechEnabled(this));
        enableOcr = intent.getBooleanExtra(EXTRA_ENABLE_OCR, SubtitleSettings.ocrEnabled(this));
        showOriginal = SubtitleSettings.showOriginal(this);

        startAsForeground(useMic);

        // Activity.RESULT_OK equals -1. v0.1 mistakenly treated -1 as a denied result.
        if (resultCode != Activity.RESULT_OK || data == null) {
            showFatal("Android не передал разрешение на захват экрана. Вернись в приложение и выдай доступ ещё раз.");
            return START_NOT_STICKY;
        }
        if (!Settings.canDrawOverlays(this)) {
            showFatal("Нет разрешения на показ субтитров поверх других приложений.");
            return START_NOT_STICKY;
        }

        createOverlay();
        setStatus(useMic ? "Микрофон" : "Системный звук");

        try {
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = mpm.getMediaProjection(resultCode, data);
            if (projection == null) {
                showFatal("Не удалось получить MediaProjection. Выдай доступ к экрану ещё раз.");
                return START_NOT_STICKY;
            }
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { stopSelf(); }
            }, mainHandler);

            if (enableOcr) startScreenCapture();
            prepareTranslationAndSpeech();
        } catch (SecurityException e) {
            showFatal("Android отклонил захват экрана. На Android 14+ разрешение нужно выдавать заново перед каждым запуском.");
        } catch (Exception e) {
            showFatal("Ошибка запуска: " + safeMessage(e));
        }
        return START_NOT_STICKY;
    }

    private void prepareTranslationAndSpeech() {
        setStatus("Готовлю перевод…");
        translation.prepare(() -> {
            if (!enableSpeech) {
                setStatus(enableOcr ? "OCR активен" : "Готово");
                return;
            }
            modelManager.ensureModel(this::setStatus, modelFile -> {
                setStatus(useMic ? "Слушаю микрофон" : "Слушаю системный звук");
                transcriber = new AudioTranscriber(this, projection, useMic);
                transcriber.start(modelFile, this::onChineseSpeech, message -> {
                    setStatus(message);
                    if (message != null && message.startsWith("Ошибка распознавания речи") && !useMic) {
                        setStatus("Системный звук недоступен — попробуй режим «Микрофон»");
                    }
                });
            }, e -> setStatus("Не удалось скачать модель речи: " + safeMessage(e)));
        }, e -> setStatus("Не удалось скачать модель перевода: " + safeMessage(e)));
    }

    private void onChineseSpeech(String chinese) {
        if (!enableSpeech || chinese == null) return;
        chinese = chinese.trim();
        if (chinese.length() < 2 || chinese.equals(lastSpeechSource)) return;
        lastSpeechSource = chinese;
        final String source = chinese;
        translation.translate(chinese, ru -> runOnMain(() -> {
            String text = showOriginal ? source + "\n" + ru : ru;
            speechText.setText(text);
            setCardVisible(speechCard, true);
            mainHandler.removeCallbacks(hideSpeech);
            mainHandler.postDelayed(hideSpeech, 6500);
        }));
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
                if (now - lastOcrAt < 1200 || !ocrBusy.compareAndSet(false, true)) return;
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
                    String src = cleanOcr(result.getText());
                    if (src.length() > 420) src = src.substring(0, 420);
                    if (!src.isEmpty() && !similarEnough(src, lastOcrSource)) {
                        lastOcrSource = src;
                        final String source = src;
                        translation.translate(src, ru -> runOnMain(() -> {
                            String text = showOriginal ? source + "\n" + ru : ru;
                            ocrText.setText(text);
                            setCardVisible(ocrCard, true);
                            mainHandler.removeCallbacks(hideOcr);
                            mainHandler.postDelayed(hideOcr, 7000);
                        }));
                    }
                })
                .addOnCompleteListener(task -> {
                    bitmap.recycle();
                    ocrBusy.set(false);
                });
    }

    private String cleanOcr(String raw) {
        if (raw == null) return "";
        String text = raw.replaceAll("[\\t ]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        if (text.length() < 2) return "";
        if (!text.matches("(?s).*\\p{IsHan}.*")) return "";
        return text;
    }

    private boolean similarEnough(String a, String b) {
        if (b == null || b.isEmpty()) return false;
        if (a.equals(b)) return true;
        int min = Math.min(a.length(), b.length());
        if (min < 8) return false;
        int same = 0;
        for (int i = 0; i < min; i++) if (a.charAt(i) == b.charAt(i)) same++;
        return same >= min * 0.86;
    }

    private void createOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        int alpha = Math.max(35, Math.min(90, SubtitleSettings.backgroundAlpha(this)));
        int bg = (alpha * 255 / 100 << 24) | 0x0010141d;

        ocrCard = overlayCard(bg, 16);
        ocrText = overlayText(SubtitleSettings.ocrSize(this), Gravity.START);
        ocrText.setMaxLines(5);
        ocrCard.addView(ocrText, new LinearLayout.LayoutParams(-1, -2));
        setCardVisible(ocrCard, false);

        speechCard = overlayCard(bg, 20);
        speechText = overlayText(SubtitleSettings.speechSize(this), Gravity.CENTER);
        speechText.setTypeface(speechText.getTypeface(), android.graphics.Typeface.BOLD);
        speechText.setMaxLines(4);
        speechCard.addView(speechText, new LinearLayout.LayoutParams(-1, -2));
        setCardVisible(speechCard, false);

        statusChip = new TextView(this);
        statusChip.setTextColor(0xffd9e5ff);
        statusChip.setTextSize(11);
        statusChip.setGravity(Gravity.CENTER);
        statusChip.setPadding(dp(10), dp(5), dp(10), dp(5));
        statusChip.setBackground(rounded(0xcc111827, 99, 0xff344361));

        int commonFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_SECURE;

        WindowManager.LayoutParams ocrLp = new WindowManager.LayoutParams(
                Math.min(getResources().getDisplayMetrics().widthPixels - dp(28), dp(620)),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                commonFlags,
                PixelFormat.TRANSLUCENT);
        ocrLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        ocrLp.y = dp(48);

        WindowManager.LayoutParams speechLp = new WindowManager.LayoutParams(
                Math.min(getResources().getDisplayMetrics().widthPixels - dp(24), dp(680)),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                commonFlags,
                PixelFormat.TRANSLUCENT);
        speechLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        speechLp.y = dp(72);

        WindowManager.LayoutParams chipLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                commonFlags,
                PixelFormat.TRANSLUCENT);
        chipLp.gravity = Gravity.TOP | Gravity.END;
        chipLp.x = dp(12);
        chipLp.y = dp(18);

        windowManager.addView(ocrCard, ocrLp);
        windowManager.addView(speechCard, speechLp);
        windowManager.addView(statusChip, chipLp);
    }

    private LinearLayout overlayCard(int backgroundColor, int radius) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(10), dp(14), dp(10));
        card.setBackground(rounded(backgroundColor, radius, 0x55ffffff));
        card.setElevation(dp(6));
        return card;
    }

    private TextView overlayText(int sp, int gravity) {
        TextView tv = new TextView(this);
        tv.setTextSize(sp);
        tv.setTextColor(Color.WHITE);
        tv.setGravity(gravity);
        tv.setShadowLayer(4f, 0f, 2f, Color.BLACK);
        tv.setLineSpacing(0f, 1.08f);
        return tv;
    }

    private GradientDrawable rounded(int fill, int radiusDp, int stroke) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fill);
        gd.setCornerRadius(dp(radiusDp));
        gd.setStroke(dp(1), stroke);
        return gd;
    }

    private void setCardVisible(android.view.View view, boolean visible) {
        if (view != null) view.setVisibility(visible ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    private void showFatal(String text) {
        setStatus(text);
        mainHandler.postDelayed(this::stopSelf, 3500);
    }

    private void setStatus(String text) {
        runOnMain(() -> {
            if (statusChip != null) statusChip.setText(text == null ? "LIVE" : text);
        });
    }

    private String safeMessage(Exception e) {
        String m = e.getMessage();
        return (m == null || m.trim().isEmpty()) ? e.getClass().getSimpleName() : m;
    }

    private void runOnMain(Runnable r) {
        mainHandler.post(r);
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
        mainHandler.removeCallbacks(hideSpeech);
        mainHandler.removeCallbacks(hideOcr);
        if (transcriber != null) transcriber.stop();
        if (virtualDisplay != null) virtualDisplay.release();
        if (imageReader != null) imageReader.close();
        if (projection != null) projection.stop();
        if (textRecognizer != null) textRecognizer.close();
        if (translation != null) translation.close();
        if (imageThread != null) imageThread.quitSafely();
        removeView(ocrCard);
        removeView(speechCard);
        removeView(statusChip);
        super.onDestroy();
    }

    private void removeView(android.view.View v) {
        if (windowManager != null && v != null) {
            try { windowManager.removeView(v); } catch (Exception ignored) { }
        }
    }

    @Nullable @Override
    public IBinder onBind(Intent intent) { return null; }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
