package ru.slava.videotranslator;

import android.content.Context;
import android.content.SharedPreferences;

public final class SubtitleSettings {
    public static final String MODE_VIDEO = "video";
    public static final String MODE_LIVE = "live";

    private static final String PREFS = "subtitle_settings";

    private SubtitleSettings() {}

    public static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static int speechSize(Context context) {
        return prefs(context).getInt("speech_size", 20);
    }

    public static int ocrSize(Context context) {
        return prefs(context).getInt("ocr_size", 15);
    }

    public static int backgroundAlpha(Context context) {
        return prefs(context).getInt("background_alpha", 66);
    }

    public static boolean showOriginal(Context context) {
        return prefs(context).getBoolean("show_original", false);
    }

    public static boolean speechEnabled(Context context, String mode) {
        return prefs(context).getBoolean(modeKey("speech_enabled", mode), true);
    }

    public static boolean ocrEnabled(Context context, String mode) {
        // Image/screen OCR is intentionally opt-in in v0.3.
        return prefs(context).getBoolean(modeKey("ocr_enabled", mode), false);
    }

    public static void setSpeechEnabled(Context context, String mode, boolean enabled) {
        prefs(context).edit().putBoolean(modeKey("speech_enabled", mode), enabled).apply();
    }

    public static void setOcrEnabled(Context context, String mode, boolean enabled) {
        prefs(context).edit().putBoolean(modeKey("ocr_enabled", mode), enabled).apply();
    }

    public static String lastMode(Context context) {
        String mode = prefs(context).getString("last_mode", MODE_LIVE);
        return normalizeMode(mode);
    }

    public static void setLastMode(Context context, String mode) {
        prefs(context).edit().putString("last_mode", normalizeMode(mode)).apply();
    }

    public static String normalizeMode(String mode) {
        return MODE_VIDEO.equals(mode) ? MODE_VIDEO : MODE_LIVE;
    }

    // Compatibility helpers for any older call sites.
    public static boolean speechEnabled(Context context) {
        return speechEnabled(context, lastMode(context));
    }

    public static boolean ocrEnabled(Context context) {
        return ocrEnabled(context, lastMode(context));
    }

    private static String modeKey(String base, String mode) {
        return base + "_" + normalizeMode(mode);
    }
}
