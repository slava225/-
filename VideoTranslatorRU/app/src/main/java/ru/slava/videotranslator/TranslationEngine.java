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
        translator.translate(chinese)
                .addOnSuccessListener(onResult::accept)
                .addOnFailureListener(e -> { });
    }

    public void close() {
        translator.close();
    }
}
