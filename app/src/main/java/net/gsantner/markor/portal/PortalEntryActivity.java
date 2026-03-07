package net.gsantner.markor.portal;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import net.gsantner.markor.R;

import java.io.File;

public class PortalEntryActivity extends AppCompatActivity {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            final Intent launchIntent = getIntent();
            final String action = launchIntent != null ? launchIntent.getAction() : null;
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
                final boolean isShareImport = PortalShareImport.isSupportedShareIntent(launchIntent);
                final String path = launchIntent != null ? launchIntent.getStringExtra(PortalActions.EXTRA_SESSION_PATH) : null;
                if (!isShareImport && path != null) {
                    final File f = new File(path);
                    if (f.isFile()) {
                        session = f;
                    }
                }
                if (session == null) {
                    session = storage.createNewSessionFile();
                }
                if (isShareImport && launchIntent != null) {
                    PortalShareImport.seedSessionFromShareIntent(this, storage, session, launchIntent);
                }

                final Intent openInput = new Intent(this, PortalInputActivity.class)
                        .setAction((action == null || isShareImport) ? PortalActions.ACTION_TEXT : action)
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
}
