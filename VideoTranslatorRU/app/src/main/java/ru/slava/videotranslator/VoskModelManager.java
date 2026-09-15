package ru.slava.videotranslator;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class VoskModelManager {
    public static final String MODEL_DIR = "vosk-model-small-cn-0.22";
    private static final String MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip";
    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public interface Progress { void update(String message); }

    public VoskModelManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public void ensureModel(Progress progress, Consumer<File> onReady, Consumer<Exception> onError) {
        executor.execute(() -> {
            try {
                File base = new File(context.getFilesDir(), "models");
                if (!base.exists() && !base.mkdirs()) throw new IllegalStateException("Не удалось создать папку моделей");
                File model = new File(base, MODEL_DIR);
                File marker = new File(model, "am/final.mdl");
                if (marker.exists()) { onReady.accept(model); return; }

                File zip = new File(base, MODEL_DIR + ".zip");
                progress.update("Скачиваю китайскую модель речи (~42 МБ)…");
                download(MODEL_URL, zip, progress);
                progress.update("Распаковываю модель речи…");
                unzip(zip, base);
                zip.delete();
                if (!marker.exists()) throw new IllegalStateException("Модель распаковалась некорректно");
                onReady.accept(model);
            } catch (Exception e) { onError.accept(e); }
        });
    }

    private void download(String urlString, File out, Progress progress) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlString).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.connect();
        if (c.getResponseCode() / 100 != 2) throw new IllegalStateException("HTTP " + c.getResponseCode());
        long total = c.getContentLengthLong();
        try (InputStream in = new BufferedInputStream(c.getInputStream());
             BufferedOutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
            byte[] buf = new byte[64 * 1024];
            long done = 0;
            int n;
            int lastPct = -1;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                done += n;
                if (total > 0) {
                    int pct = (int) (done * 100 / total);
                    if (pct >= lastPct + 10) {
                        lastPct = pct;
                        progress.update("Скачиваю модель речи: " + pct + "%");
                    }
                }
            }
        } finally { c.disconnect(); }
    }

    private void unzip(File zipFile, File targetDir) throws Exception {
        String root = targetDir.getCanonicalPath() + File.separator;
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new java.io.FileInputStream(zipFile)))) {
            ZipEntry entry;
            byte[] buf = new byte[64 * 1024];
            while ((entry = zis.getNextEntry()) != null) {
                File out = new File(targetDir, entry.getName());
                if (!out.getCanonicalPath().startsWith(root)) throw new SecurityException("Bad zip path");
                if (entry.isDirectory()) {
                    out.mkdirs();
                } else {
                    File parent = out.getParentFile();
                    if (parent != null) parent.mkdirs();
                    try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(out))) {
                        int n;
                        while ((n = zis.read(buf)) > 0) bos.write(buf, 0, n);
                    }
                }
                zis.closeEntry();
            }
        }
    }
}
