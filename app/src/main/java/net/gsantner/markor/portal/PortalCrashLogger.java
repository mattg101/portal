package net.gsantner.markor.portal;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class PortalCrashLogger {
    private static final String TAG = "PortalCrashLogger";
    private static final String CRASH_LOG_FILE = "portal-crash.log";
    private static volatile boolean _installed;

    private PortalCrashLogger() {
    }

    public static synchronized void install(@NonNull final Context context) {
        if (_installed) {
            return;
        }
        final Context appContext = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                logThrowable(appContext, "Uncaught exception on thread: " + thread.getName(), throwable);
            } catch (Throwable ignored) {
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            }
        });
        _installed = true;
    }

    public static void logThrowable(@NonNull final Context context, @NonNull final String label, @Nullable final Throwable throwable) {
        final File logFile = getLogFile(context);
        final String when = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        final StringBuilder sb = new StringBuilder();
        sb.append("[").append(when).append("] ").append(label).append('\n');
        if (throwable != null) {
            final StringWriter sw = new StringWriter();
            final PrintWriter pw = new PrintWriter(sw);
            throwable.printStackTrace(pw);
            pw.flush();
            sb.append(sw);
        } else {
            sb.append("(no throwable)\n");
        }
        sb.append("\n----------------------------------------\n\n");

        try (FileWriter fw = new FileWriter(logFile, true)) {
            fw.write(sb.toString());
            fw.flush();
        } catch (Exception e) {
            Log.e(TAG, "Failed writing crash log", e);
        }
    }

    @Nullable
    public static String readLogTail(@NonNull final Context context, final int maxChars) {
        final File logFile = getLogFile(context);
        if (!logFile.exists() || !logFile.isFile()) {
            return null;
        }
        try (FileInputStream fis = new FileInputStream(logFile)) {
            final byte[] bytes = new byte[(int) logFile.length()];
            //noinspection ResultOfMethodCallIgnored
            fis.read(bytes);
            final String text = new String(bytes);
            if (text.length() <= maxChars) {
                return text;
            }
            return text.substring(text.length() - maxChars);
        } catch (Exception e) {
            Log.e(TAG, "Failed reading crash log", e);
            return null;
        }
    }

    public static long getLastModified(@NonNull final Context context) {
        final File logFile = getLogFile(context);
        return logFile.exists() ? logFile.lastModified() : 0L;
    }

    @NonNull
    public static File getLogFile(@NonNull final Context context) {
        return new File(context.getFilesDir(), CRASH_LOG_FILE);
    }
}
