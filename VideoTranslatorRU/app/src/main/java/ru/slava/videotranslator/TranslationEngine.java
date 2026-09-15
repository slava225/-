package ru.slava.videotranslator;

import android.content.Context;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class TranslationEngine {
    private static final String CONTEXT_MARKER = "◆◆◆";

    private final Translator translator;
    private final AtomicBoolean ready = new AtomicBoolean(false);

    public TranslationEngine(Context context) {
        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.CHINESE)
                .setTargetLanguage(TranslateLanguage.RUSSIAN)
                .build();
        translator = Translation.getClient(options);
    }

    public void prepare(Runnable onReady, Consumer<Exception> onError) {
        DownloadConditions conditions = new DownloadConditions.Builder().build();
        translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener(unused -> {
                    ready.set(true);
                    onReady.run();
                })
                .addOnFailureListener(onError::accept);
    }

    public void translate(String chinese, Consumer<String> onResult) {
        if (chinese == null || chinese.trim().isEmpty() || !ready.get()) return;
        translator.translate(normalize(chinese))
                .addOnSuccessListener(result -> onResult.accept(cleanRussian(result)))
                .addOnFailureListener(e -> { });
    }

    /**
     * Accuracy-oriented translation. For short/ambiguous utterances we give ML Kit the previous
     * confirmed Chinese utterance as context, separated by a marker. We only return text after the
     * marker. If ML Kit removes the marker, we fall back to translating the current utterance alone.
     */
    public void translateAccurate(String previousChinese, String currentChinese, Consumer<String> onResult) {
        if (currentChinese == null || currentChinese.trim().isEmpty() || !ready.get()) return;

        String current = normalize(currentChinese);
        String previous = normalize(previousChinese);
        if (previous.isEmpty() || countHan(current) > 22) {
            translate(current, onResult);
            return;
        }

        if (previous.length() > 90) previous = previous.substring(previous.length() - 90);
        String combined = ensureSentence(previous) + "\n" + CONTEXT_MARKER + "\n" + ensureSentence(current);

        translator.translate(combined)
                .addOnSuccessListener(result -> {
                    String extracted = extractAfterMarker(result);
                    if (!extracted.isEmpty()) {
                        onResult.accept(cleanRussian(extracted));
                    } else {
                        // Safer than accidentally showing translation of the previous sentence too.
                        translate(current, onResult);
                    }
                })
                .addOnFailureListener(e -> translate(current, onResult));
    }

    private String extractAfterMarker(String translated) {
        if (translated == null) return "";
        String compact = translated.replace("◆ ◆ ◆", CONTEXT_MARKER)
                .replace("◆ ◆◆", CONTEXT_MARKER)
                .replace("◆◆ ◆", CONTEXT_MARKER);
        int marker = compact.lastIndexOf(CONTEXT_MARKER);
        if (marker < 0) {
            int lastDiamond = compact.lastIndexOf('◆');
            if (lastDiamond < 0) return "";
            marker = lastDiamond;
            return compact.substring(marker + 1).trim();
        }
        return compact.substring(marker + CONTEXT_MARKER.length()).trim();
    }

    private String normalize(String text) {
        String value = text == null ? "" : text
                .replace('\u3000', ' ')
                .replaceAll("[\\t\\r ]+", " ")
                .replaceAll("\\n{2,}", "\n")
                .trim();
        // ASR occasionally produces obvious 3+ character stutters; keep natural doubles intact.
        value = value.replaceAll("([\\p{IsHan}])\\1{2,}", "$1$1");
        return value;
    }

    private String ensureSentence(String text) {
        if (text.isEmpty()) return text;
        char last = text.charAt(text.length() - 1);
        if (last == '。' || last == '！' || last == '？' || last == '!' || last == '?') return text;
        return text + "。";
    }

    private String cleanRussian(String text) {
        if (text == null) return "";
        return text.replaceAll("[\\t\\r ]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private int countHan(String value) {
        int count = 0;
        for (int offset = 0; offset < value.length();) {
            int cp = value.codePointAt(offset);
            offset += Character.charCount(cp);
            if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN) count++;
        }
        return count;
    }

    public void close() {
        translator.close();
    }
}
