package ru.slava.videotranslator;

import android.content.Context;
import android.content.SharedPreferences;

public final class SubtitleSettings {
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

    public static boolean speechEnabled(Context context) {
        return prefs(context).getBoolean("speech_enabled", true);
    }

    public static boolean ocrEnabled(Context context) {
        return prefs(context).getBoolean("ocr_enabled", true);
    }
}
