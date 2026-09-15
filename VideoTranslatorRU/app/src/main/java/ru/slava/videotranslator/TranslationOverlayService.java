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
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.nio.ByteBuffer;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class TranslationOverlayService extends Service {
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";
    public static final String EXTRA_AUDIO_SOURCE = "audio_source";
    public static final String EXTRA_ENABLE_SPEECH = "enable_speech";
    public static final String EXTRA_ENABLE_OCR = "enable_ocr";
    public static final String EXTRA_MODE = "app_mode";
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
    private String pendingOcrSource = "";
    private int pendingOcrHits = 0;
    private String lastSpeechSource = "";
    private String lastConfirmedChinese = "";

    private final AtomicBoolean ocrBusy = new AtomicBoolean(false);
    private final AtomicLong speechRequestSeq = new AtomicLong(0L);
    private final AtomicLong ocrRequestSeq = new AtomicLong(0L);

    private boolean enableSpeech = true;
    private boolean enableOcr = false;
    private boolean showOriginal = false;
    private boolean useMic = false;
    private String mode = SubtitleSettings.MODE_LIVE;

    private final Runnable hideSpeech = () -> {
        if (speechText != null) speechText.setText("");
        setCardVisible(speechCard, false);
    };
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
        if (Build.VERSION.SDK_INT >= 33) data = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        else {
            //noinspection deprecation
            data = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        }

        mode = SubtitleSettings.normalizeMode(intent.getStringExtra(EXTRA_MODE));
        useMic = "mic".equals(intent.getStringExtra(EXTRA_AUDIO_SOURCE));
        enableSpeech = intent.getBooleanExtra(EXTRA_ENABLE_SPEECH, SubtitleSettings.speechEnabled(this, mode));
        enableOcr = intent.getBooleanExtra(EXTRA_ENABLE_OCR, SubtitleSettings.ocrEnabled(this, mode));
        showOriginal = SubtitleSettings.showOriginal(this);

        startAsForeground(useMic);

        if (resultCode != Activity.RESULT_OK || data == null) {
            showFatal("Android не передал разрешение на захват экрана. Выдай доступ ещё раз.");
            return START_NOT_STICKY;
        }
        if (!Settings.canDrawOverlays(this)) {
            showFatal("Нет разрешения на показ субтитров поверх других приложений.");
            return START_NOT_STICKY;
        }

        createOverlay();
        setStatus(modeLabel() + " • ТОЧНЫЙ ПЕРЕВОД");

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
            prepareTranslationAndFeatures();
        } catch (SecurityException e) {
            showFatal("Android отклонил захват экрана. Разрешение нужно выдать заново.");
        } catch (Exception e) {
            showFatal("Ошибка запуска: " + safeMessage(e));
        }
        return START_NOT_STICKY;
    }

    private void prepareTranslationAndFeatures() {
        setStatus(modeLabel() + " • готовлю точный переводчик…");
        translation.prepare(() -> {
            if (enableOcr) {
                try {
                    startScreenCapture();
                } catch (Exception e) {
                    enableOcr = false;
                    setStatus(modeLabel() + " • OCR не запустился: " + safeMessage(e));
                }
            }

            if (!enableSpeech) {
                setStatus(modeLabel() + (enableOcr ? " • китайский текст с изображения" : " • готово"));
                return;
            }

            modelManager.ensureModel(this::setStatus, modelFile -> {
                setStatus(modeLabel() + " • ТОЧНО • " + (useMic ? "микрофон" : "системный звук"));
                transcriber = new AudioTranscriber(this, projection, useMic);
                transcriber.start(modelFile, this::onChineseTranscript, message -> {
                    setStatus(message);
                    if (message != null && message.startsWith("Ошибка распознавания речи") && !useMic) {
                        setStatus("Системный звук недоступен — попробуй режим «Микрофон»");
                    }
                });
            }, e -> setStatus("Не удалось скачать модель речи: " + safeMessage(e)));
        }, e -> setStatus("Не удалось скачать модель перевода: " + safeMessage(e)));
    }

    private void onChineseTranscript(AudioTranscriber.Transcript transcript) {
        if (!enableSpeech || transcript == null) return;

        String chinese = normalizeSpeechChinese(transcript.text);
        if (chinese.isEmpty()) {
            speechRequestSeq.incrementAndGet();
            lastSpeechSource = "";
            mainHandler.removeCallbacks(hideSpeech);
            runOnMain(hideSpeech);
            return;
        }

        int han = countHan(chinese);
        if (han < 2 && chinese.length() < 4) return;

        // Partial results must be reasonably substantial; otherwise incomplete Chinese produces
        // a grammatically plausible but semantically wrong Russian sentence.
        if (!transcript.isFinal) {
            if (han < 5) return;
            if (transcript.confidence > 0f && transcript.confidence < 0.52f) return;
        } else {
            // Low confidence short final hypotheses are the most common source of completely wrong
            // translations. Longer phrases have more context, so allow a slightly lower score.
            float minFinalConfidence = han >= 9 ? 0.34f : 0.42f;
            if (transcript.confidence > 0f && transcript.confidence < minFinalConfidence) {
                setStatus(modeLabel() + " • пропустил сомнительную фразу");
                return;
            }
        }

        if (chinese.equals(lastSpeechSource) && !transcript.isFinal) return;
        lastSpeechSource = chinese;

        final String source = transcript.isFinal ? ensureChineseSentence(chinese) : chinese;
        final String previousContext = lastConfirmedChinese;
        final boolean finalHypothesis = transcript.isFinal;
        final long requestId = speechRequestSeq.incrementAndGet();

        // As soon as a newer phrase appears, an old translation is no longer allowed to win.
        mainHandler.postDelayed(() -> {
            if (requestId == speechRequestSeq.get() && speechText != null
                    && speechText.getText().length() > 0 && !finalHypothesis) {
                setCardVisible(speechCard, false);
            }
        }, 850L);

        translation.translateAccurate(previousContext, source, ru -> runOnMain(() -> {
            if (requestId != speechRequestSeq.get()) return;
            if (ru == null || ru.trim().isEmpty()) return;

            String cleanRu = ru.trim();
            String text = showOriginal ? source + "\n" + cleanRu : cleanRu;
            speechText.setText(text);
            setCardVisible(speechCard, true);
            mainHandler.removeCallbacks(hideSpeech);
            mainHandler.postDelayed(hideSpeech, finalHypothesis ? 3200L : 1700L);

            if (finalHypothesis) {
                lastConfirmedChinese = source;
                if (lastConfirmedChinese.length() > 100) {
                    lastConfirmedChinese = lastConfirmedChinese.substring(lastConfirmedChinese.length() - 100);
                }
                setStatus(modeLabel() + " • ТОЧНО • перевод подтверждён");
            }
        }));
    }

    private String ensureChineseSentence(String text) {
        if (text == null || text.isEmpty()) return "";
        char last = text.charAt(text.length() - 1);
        if (last == '。' || last == '！' || last == '？' || last == '!' || last == '?') return text;
        return text + "。";
    }

    private String normalizeSpeechChinese(String raw) {
        String cleaned = raw == null ? "" : raw.replaceAll("[\\t\\r\\n ]+", " ").trim();
        if (cleaned.isEmpty()) return "";
        String[] parts = cleaned.split(" ");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (out.length() > 0) {
                int prev = out.codePointBefore(out.length());
                int next = part.codePointAt(0);
                if (!(isHan(prev) && isHan(next))) out.append(' ');
            }
            out.append(part);
        }
        return out.toString().replaceAll("([\\p{IsHan}])\\1{2,}", "$1$1").trim();
    }

    private int countHan(String value) {
        int count = 0;
        for (int offset = 0; offset < value.length();) {
            int cp = value.codePointAt(offset);
            offset += Character.charCount(cp);
            if (isHan(cp)) count++;
        }
        return count;
    }

    private boolean isHan(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }

    private void startScreenCapture() {
        if (virtualDisplay != null || projection == null) return;
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
                long interval = SubtitleSettings.MODE_LIVE.equals(mode) ? 750L : 1050L;
                if (now - lastOcrAt < interval || !ocrBusy.compareAndSet(false, true)) return;
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
                "ChineseImageTranslator", width, height, density,
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
                    String src = extractChineseOnly(result);
                    if (src.isEmpty()) {
                        pendingOcrSource = "";
                        pendingOcrHits = 0;
                        return;
                    }
                    if (similarEnough(src, pendingOcrSource)) pendingOcrHits++;
                    else {
                        pendingOcrSource = src;
                        pendingOcrHits = 1;
                    }

                    int requiredHits = SubtitleSettings.MODE_LIVE.equals(mode) ? 2 : 1;
                    if (pendingOcrHits < requiredHits || similarEnough(src, lastOcrSource)) return;

                    lastOcrSource = src;
                    pendingOcrHits = 0;
                    final String source = src;
                    final long requestId = ocrRequestSeq.incrementAndGet();
                    translation.translate(src, ru -> runOnMain(() -> {
                        if (requestId != ocrRequestSeq.get()) return;
                        if (ru == null || ru.trim().isEmpty()) return;
                        String text = showOriginal ? source + "\n" + ru.trim() : ru.trim();
                        ocrText.setText(text);
                        setCardVisible(ocrCard, true);
                        mainHandler.removeCallbacks(hideOcr);
                        mainHandler.postDelayed(hideOcr, 6500L);
                    }));
                })
                .addOnCompleteListener(task -> {
                    bitmap.recycle();
                    ocrBusy.set(false);
                });
    }

    private String extractChineseOnly(Text result) {
        if (result == null) return "";
        Set<String> lines = new LinkedHashSet<>();
        int totalChars = 0;
        outer:
        for (Text.TextBlock block : result.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                String value = normalizeOcrLine(line.getText());
                if (!isChineseDominantLine(value)) continue;
                if (!lines.add(value)) continue;
                totalChars += value.length();
                if (totalChars > 260 || lines.size() >= 6) break outer;
            }
        }

        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            if (out.length() > 0) out.append('\n');
            if (out.length() + line.length() > 260) {
                int remain = 260 - out.length();
                if (remain > 0) out.append(line, 0, Math.min(remain, line.length()));
                break;
            }
            out.append(line);
        }
        return out.toString().trim();
    }

    private String normalizeOcrLine(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("https?://\\S+", "")
                .replaceAll("www\\.\\S+", "")
                .replaceAll("[\\t\\r ]+", " ")
                .trim();
    }

    private boolean isChineseDominantLine(String value) {
        if (value == null || value.length() < 2) return false;
        int han = 0;
        int lettersOrDigits = 0;
        int latinOrDigits = 0;
        for (int offset = 0; offset < value.length();) {
            int cp = value.codePointAt(offset);
            offset += Character.charCount(cp);
            if (isHan(cp)) {
                han++;
                lettersOrDigits++;
            } else if (Character.isLetterOrDigit(cp)) {
                lettersOrDigits++;
                latinOrDigits++;
            }
        }
        if (han < 2) return false;
        double ratio = han / (double) Math.max(1, lettersOrDigits);
        if (han < 4 && ratio < 0.30d) return false;
        return latinOrDigits <= han * 3 || han >= 5;
    }

    private boolean similarEnough(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return false;
        String x = hanOnly(a);
        String y = hanOnly(b);
        if (x.isEmpty() || y.isEmpty()) return false;
        if (x.equals(y)) return true;
        if (Math.min(x.length(), y.length()) >= 5 && (x.contains(y) || y.contains(x))) return true;
        int max = Math.max(x.length(), y.length());
        if (max > 180) return false;
        int distance = levenshtein(x, y);
        return 1.0d - distance / (double) max >= 0.82d;
    }

    private String hanOnly(String value) {
        StringBuilder out = new StringBuilder();
        for (int offset = 0; offset < value.length();) {
            int cp = value.codePointAt(offset);
            offset += Character.charCount(cp);
            if (isHan(cp)) out.appendCodePoint(cp);
        }
        return out.toString();
    }

    private int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] swap = prev;
            prev = cur;
            cur = swap;
        }
        return prev[b.length()];
    }

    private void createOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        int alpha = Math.max(35, Math.min(90, SubtitleSettings.backgroundAlpha(this)));
        int bg = (alpha * 255 / 100 << 24) | 0x0010141d;

        ocrCard = overlayCard(bg, 16);
        ocrText = overlayText(SubtitleSettings.ocrSize(this), Gravity.START);
        ocrText.setMaxLines(7);
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
                commonFlags, PixelFormat.TRANSLUCENT);
        ocrLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        ocrLp.y = dp(52);

        WindowManager.LayoutParams speechLp = new WindowManager.LayoutParams(
                Math.min(getResources().getDisplayMetrics().widthPixels - dp(24), dp(680)),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                commonFlags, PixelFormat.TRANSLUCENT);
        speechLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        speechLp.y = dp(72);

        WindowManager.LayoutParams chipLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                commonFlags, PixelFormat.TRANSLUCENT);
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
        mainHandler.postDelayed(this::stopSelf, 3500L);
    }

    private void setStatus(String text) {
        runOnMain(() -> {
            if (statusChip != null) statusChip.setText(text == null ? modeLabel() : text);
        });
    }

    private String modeLabel() {
        return SubtitleSettings.MODE_VIDEO.equals(mode) ? "ВИДЕО" : "ЭФИР";
    }

    private String safeMessage(Exception e) {
        String m = e.getMessage();
        return (m == null || m.trim().isEmpty()) ? e.getClass().getSimpleName() : m;
    }

    private void runOnMain(Runnable r) {
        mainHandler.post(r);
    }

    private void startAsForeground(boolean mic) {
        String title = SubtitleSettings.MODE_VIDEO.equals(mode)
                ? "Точный перевод видео: Китайский → Русский"
                : "Точный перевод трансляции: Китайский → Русский";
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentTitle(title)
                .setContentText(enableOcr ? "Точный перевод речи + китайский текст" : "Точный перевод речи активен")
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            if (mic) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            startForeground(NOTIFICATION_ID, n, type);
        } else startForeground(NOTIFICATION_ID, n);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CHANNEL_ID, "Live перевод", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(c);
        }
    }

    @Override
    public void onDestroy() {
        speechRequestSeq.incrementAndGet();
        ocrRequestSeq.incrementAndGet();
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
