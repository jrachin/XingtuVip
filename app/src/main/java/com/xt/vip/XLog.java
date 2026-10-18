package com.xt.vip;

import android.util.Log;

/**
 * 统一日志出口。
 * <p>libxposed 的 log() 走框架日志（可在 LSPosed 日志页看到），Log.* 走 logcat，
 * 两条都打，方便现场排查。
 */
final class XLog {

    static final String TAG = "XingTuVip";

    private static volatile Logger sSink;

    interface Logger {
        void write(int priority, String tag, String msg, Throwable tr);
    }

    private XLog() {
    }

    static void bind(Logger sink) {
        sSink = sink;
    }

    static void i(String msg) {
        d(msg);
    }

    static void d(String msg) {
        Log.d(TAG, msg);
        Logger sink = sSink;
        if (sink != null) {
            try {
                sink.write(Log.INFO, TAG, msg, null);
            } catch (Throwable ignored) {
            }
        }
    }

    static void e(String msg, Throwable tr) {
        Log.e(TAG, msg, tr);
        Logger sink = sSink;
        if (sink != null) {
            try {
                sink.write(Log.ERROR, TAG, msg, tr);
            } catch (Throwable ignored) {
            }
        }
    }
}
