package net.gsantner.markor.portal;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;

import net.gsantner.markor.R;

import java.io.File;

public class PortalEntryActivity extends AppCompatActivity {
    private static boolean _shownCrashLogForProcess;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            maybeShowRecentCrashLog();

            final String action = getIntent() != null ? getIntent().getAction() : null;
            final PortalStorage storage = new PortalStorage(this);
            if (!storage.ensureWritableRoot()) {
                Toast.makeText(this, R.string.error_cannot_create_notebook_dir__appspecific, Toast.LENGTH_LONG).show();
                finish();
                return;
            }

            if (PortalActions.ACTION_BROWSE.equals(action)) {
                startActivity(new Intent(this, PortalSessionBrowserActivity.class));
                finish();
                return;
            }

            try {
                File session = null;
                final String path = getIntent().getStringExtra(PortalActions.EXTRA_SESSION_PATH);
                if (path != null) {
                    final File f = new File(path);
                    if (f.isFile()) {
                        session = f;
                    }
                }
                if (session == null) {
                    session = storage.createNewSessionFile();
                }

                final Intent openInput = new Intent(this, PortalInputActivity.class)
                        .setAction(action == null ? PortalActions.ACTION_TEXT : action)
                        .putExtra(PortalActions.EXTRA_SESSION_PATH, session.getAbsolutePath());
                startActivity(openInput);
            } catch (Exception e) {
                PortalCrashLogger.logThrowable(this, "PortalEntryActivity.innerLaunch", e);
                Toast.makeText(this, getString(R.string.error_could_not_open_file) + " " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            PortalCrashLogger.logThrowable(this, "PortalEntryActivity.onCreate", e);
            // Final fallback: open classic main screen so the app still launches.
            startActivity(new Intent(this, net.gsantner.markor.activity.MainActivity.class));
        }
        finish();
    }

    private void maybeShowRecentCrashLog() {
        if (_shownCrashLogForProcess) {
            return;
        }
        final long lastModified = PortalCrashLogger.getLastModified(this);
        if (lastModified <= 0) {
            return;
        }
        final long ageMs = System.currentTimeMillis() - lastModified;
        if (ageMs > 24L * 60L * 60L * 1000L) {
            return;
        }
        final String logTail = PortalCrashLogger.readLogTail(this, 3500);
        if (logTail == null || logTail.trim().isEmpty()) {
            return;
        }
        _shownCrashLogForProcess = true;
        new AlertDialog.Builder(this)
                .setTitle(R.string.portal_recent_crash_title)
                .setMessage(logTail)
                .setPositiveButton(R.string.portal_copy_crash, (dialog, which) -> {
                    try {
                        ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cb != null) {
                            cb.setPrimaryClip(ClipData.newPlainText("portal-crash-log", logTail));
                            Toast.makeText(this, R.string.portal_copied_crash, Toast.LENGTH_LONG).show();
                        }
                    } catch (Exception ignored) {
                    }
                })
                .setNegativeButton(android.R.string.ok, null)
                .show();
    }
}
