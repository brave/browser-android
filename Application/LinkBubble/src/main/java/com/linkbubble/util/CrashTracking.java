package com.linkbubble.util;

import android.content.Context;
import android.util.Log;

import com.crashlytics.android.Crashlytics;
import com.linkbubble.BuildConfig;

public class CrashTracking {

    public static void init(Context context) {
        Crashlytics.start(context);
    }

    public static void logHandledException(Throwable throwable) {
        Crashlytics.logException(throwable);
    }

    public static void setInt(String key, int value) {
        Crashlytics.setInt(key, value);
    }

    public static void setDouble(String key, double value) {
        Crashlytics.setDouble(key, value);
    }

    public static void setFloat(String key, float value) {
        Crashlytics.setFloat(key, value);
    }

    public static void setString(String key, String string) {
        Crashlytics.setString(key, string);
    }

    public static void setBool(String key, boolean value) {
        Crashlytics.setBool(key, value);
    }

    public static void log(String message) {
        Crashlytics.log(message);
        if (BuildConfig.DEBUG) {
            Log.d("CrashTracking", message);
        }
    }
}
