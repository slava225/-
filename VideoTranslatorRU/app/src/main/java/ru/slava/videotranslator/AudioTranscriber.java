package ru.slava.videotranslator;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;

import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class AudioTranscriber {
    private static final long PARTIAL_INTERVAL_MS = 520L;
    private static final long TRANSCRIPT_SILENCE_MS = 900L;

    public static final class Transcript {
        public final String text;
        public final boolean isFinal;
        public final float confidence;

        Transcript(String text, boolean isFinal, float confidence) {
            this.text = text == null ? "" : text;
            this.isFinal = isFinal;
            this.confidence = confidence;
        }
    }

    public interface TranscriptListener {
        void onTranscript(Transcript transcript);
    }

    private final Context context;
    private final MediaProjection projection;
    private final boolean useMic;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean running;
    private AudioRecord audioRecord;
    private Model model;
    private Recognizer recognizer;

    public AudioTranscriber(Context context, MediaProjection projection, boolean useMic) {
        this.context = context.getApplicationContext();
        this.projection = projection;
        this.useMic = useMic;
    }

    /** Compatibility entry point used by the overlay. It now applies accuracy filtering. */
    public void start(File modelDir, Consumer<String> onChinese, Consumer<String> onStatus) {
        start(modelDir, transcript -> {
            if (transcript.text.isEmpty()) {
                onChinese.accept("");
                return;
            }
            float conf = transcript.confidence;
            if (transcript.isFinal) {
                // Keep strong final hypotheses. If Vosk did not expose confidence, keep it too.
                if (conf <= 0f || conf >= 0.40f) onChinese.accept(transcript.text);
            } else {
                // Partial speech is much noisier, so require higher confidence.
                if (conf <= 0f || conf >= 0.50f) onChinese.accept(transcript.text);
            }
        }, onStatus);
    }

    public void start(File modelDir, TranscriptListener onTranscript, Consumer<String> onStatus) {
        executor.execute(() -> {
            try {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
                    onStatus.accept("Нет разрешения RECORD_AUDIO");
                    return;
                }

                model = new Model(modelDir.getAbsolutePath());
                recognizer = new Recognizer(model, 16000.0f);
                recognizer.setWords(true);
                recognizer.setPartialWords(true);

                audioRecord = useMic ? createMicRecord() : createPlaybackRecord();
                if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                    throw new IllegalStateException("AudioRecord не инициализирован");
                }

                running = true;
                audioRecord.startRecording();
                onStatus.accept(useMic ? "ТОЧНО • слушаю микрофон" : "ТОЧНО • слушаю системный звук");

                int nativeRate = useMic ? 16000 : 48000;
                int chunkBytes = Math.max(3200, (nativeRate / 5) * 2);
                byte[] buffer = new byte[chunkBytes];

                long lastPartialEmitAt = 0L;
                long lastTranscriptAt = 0L;
                long startedAt = System.currentTimeMillis();
                long lastAudibleAt = startedAt;
                boolean silenceHintShown = false;
                boolean transcriptSilenceEmitted = true;
                String lastEmitted = "";
                String previousPartial = "";
                int stablePartialHits = 0;

                while (running) {
                    int n = audioRecord.read(buffer, 0, buffer.length);
                    if (n <= 0) continue;
                    long now = System.currentTimeMillis();

                    if (hasAudibleSignal(buffer, n)) {
                        lastAudibleAt = now;
                        silenceHintShown = false;
                    } else if (!useMic && !silenceHintShown
                            && now - startedAt > 7000
                            && now - lastAudibleAt > 7000) {
                        silenceHintShown = true;
                        onStatus.accept("Не слышу системный звук. Если видео уже играет — попробуй режим «Микрофон»");
                    }

                    byte[] pcm16 = useMic ? copyOf(buffer, n) : downsample48to16(buffer, n);
                    boolean finalResult = recognizer.acceptWaveForm(pcm16, pcm16.length);

                    if (finalResult) {
                        String json = recognizer.getResult();
                        String text = jsonField(json, "text");
                        float confidence = averageConfidence(json, "result");
                        previousPartial = "";
                        stablePartialHits = 0;

                        if (!text.isEmpty()) {
                            lastTranscriptAt = now;
                            transcriptSilenceEmitted = false;
                            lastEmitted = text;
                            onTranscript.onTranscript(new Transcript(text, true, confidence));
                        }
                    } else {
                        String json = recognizer.getPartialResult();
                        String partial = jsonField(json, "partial");
                        float confidence = averageConfidence(json, "partial_result");

                        if (!partial.isEmpty()) {
                            lastTranscriptAt = now;
                            transcriptSilenceEmitted = false;

                            if (isStableProgress(previousPartial, partial)) stablePartialHits++;
                            else stablePartialHits = 1;
                            previousPartial = partial;

                            boolean confidenceOk = confidence <= 0f || confidence >= 0.38f;
                            if (stablePartialHits >= 2
                                    && confidenceOk
                                    && now - lastPartialEmitAt >= PARTIAL_INTERVAL_MS
                                    && !partial.equals(lastEmitted)) {
                                lastPartialEmitAt = now;
                                lastEmitted = partial;
                                onTranscript.onTranscript(new Transcript(partial, false, confidence));
                            }
                        } else if (lastTranscriptAt > 0
                                && now - lastTranscriptAt >= TRANSCRIPT_SILENCE_MS
                                && !transcriptSilenceEmitted) {
                            transcriptSilenceEmitted = true;
                            lastEmitted = "";
                            previousPartial = "";
                            stablePartialHits = 0;
                            onTranscript.onTranscript(new Transcript("", true, 1f));
                        }
                    }
                }
            } catch (Exception e) {
                onStatus.accept("Ошибка распознавания речи: " + safeMessage(e));
            } finally {
                release();
            }
        });
    }

    private boolean isStableProgress(String previous, String current) {
        if (previous == null || previous.isEmpty()) return false;
        if (previous.equals(current)) return true;
        if (current.startsWith(previous) || previous.startsWith(current)) return true;
        int min = Math.min(previous.length(), current.length());
        if (min < 3) return false;
        int same = 0;
        for (int i = 0; i < min; i++) {
            if (previous.charAt(i) == current.charAt(i)) same++;
            else break;
        }
        return same >= Math.max(3, (int) (min * 0.72f));
    }

    private float averageConfidence(String json, String arrayKey) {
        try {
            JSONArray words = new JSONObject(json).optJSONArray(arrayKey);
            if (words == null || words.length() == 0) return 0f;
            float total = 0f;
            int count = 0;
            for (int i = 0; i < words.length(); i++) {
                JSONObject word = words.optJSONObject(i);
                if (word == null || !word.has("conf")) continue;
                total += (float) word.optDouble("conf", 0.0);
                count++;
            }
            return count == 0 ? 0f : total / count;
        } catch (Exception e) {
            return 0f;
        }
    }

    private AudioRecord createPlaybackRecord() {
        android.media.AudioPlaybackCaptureConfiguration config =
                new android.media.AudioPlaybackCaptureConfiguration.Builder(projection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build();
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(48000)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build();
        int min = Math.max(48000, AudioRecord.getMinBufferSize(48000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2);
        return new AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(min)
                .setAudioPlaybackCaptureConfig(config)
                .build();
    }

    private AudioRecord createMicRecord() {
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(16000)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build();
        int min = Math.max(16000, AudioRecord.getMinBufferSize(16000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2);
        return new AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(format)
                .setBufferSizeInBytes(min)
                .build();
    }

    private boolean hasAudibleSignal(byte[] data, int len) {
        int max = 0;
        for (int i = 0; i + 1 < len; i += 16) {
            int sample = Math.abs(shortAt(data, i));
            if (sample > max) max = sample;
            if (max > 220) return true;
        }
        return false;
    }

    private byte[] downsample48to16(byte[] in, int len) {
        int samples = len / 2;
        int groups = samples / 3;
        byte[] out = new byte[groups * 2];
        for (int g = 0; g < groups; g++) {
            int s0 = shortAt(in, (g * 3) * 2);
            int s1 = shortAt(in, (g * 3 + 1) * 2);
            int s2 = shortAt(in, (g * 3 + 2) * 2);
            int avg = (s0 + s1 + s2) / 3;
            out[g * 2] = (byte) (avg & 0xff);
            out[g * 2 + 1] = (byte) ((avg >> 8) & 0xff);
        }
        return out;
    }

    private int shortAt(byte[] b, int i) {
        return (short) ((b[i] & 0xff) | (b[i + 1] << 8));
    }

    private byte[] copyOf(byte[] in, int len) {
        byte[] out = new byte[len];
        System.arraycopy(in, 0, out, 0, len);
        return out;
    }

    private String jsonField(String json, String key) {
        try {
            return new JSONObject(json).optString(key, "").trim();
        } catch (Exception e) {
            return "";
        }
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.trim().isEmpty() ? e.getClass().getSimpleName() : message;
    }

    public void stop() {
        running = false;
        try {
            if (audioRecord != null) audioRecord.stop();
        } catch (Exception ignored) { }
    }

    private void release() {
        try {
            if (audioRecord != null) audioRecord.release();
        } catch (Exception ignored) { }
        try {
            if (recognizer != null) recognizer.close();
        } catch (Exception ignored) { }
        try {
            if (model != null) model.close();
        } catch (Exception ignored) { }
        audioRecord = null;
        recognizer = null;
        model = null;
    }
}
