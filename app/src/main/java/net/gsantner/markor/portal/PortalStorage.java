package net.gsantner.markor.portal;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import net.gsantner.markor.model.AppSettings;
import net.gsantner.opoc.util.GsFileUtils;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class PortalStorage {
    public static final String DIR_INBOX = "INBOX";
    private static final String PREF = "portal_storage";
    private static final String KEY_CUSTOM_ROOT = "custom_root_path";

    private final AppSettings _appSettings;
    private final SharedPreferences _pref;
    private static final SimpleDateFormat TS = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ENGLISH);

    public PortalStorage(@NonNull Context context) {
        _appSettings = AppSettings.get(context);
        _pref = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public File getNotebookRoot() {
        final String custom = _pref.getString(KEY_CUSTOM_ROOT, "");
        if (custom != null && !custom.trim().isEmpty()) {
            return new File(custom.trim());
        }
        return _appSettings.getNotebookDirectory();
    }

    public String getConfiguredRootPath() {
        final String custom = _pref.getString(KEY_CUSTOM_ROOT, "");
        return custom == null ? "" : custom;
    }

    public void setConfiguredRootPath(String path) {
        _pref.edit().putString(KEY_CUSTOM_ROOT, path == null ? "" : path.trim()).apply();
    }

    public File getSessionsDir() {
        return new File(getNotebookRoot(), DIR_INBOX);
    }

    public boolean ensureWritableRoot() {
        final File root = getNotebookRoot();
        return (root.isDirectory() || root.mkdirs()) && root.canWrite();
    }

    public File createNewSessionFile() throws IOException {
        final File sessionsDir = getSessionsDir();
        //noinspection ResultOfMethodCallIgnored
        sessionsDir.mkdirs();
        final String base = TS.format(new Date()) + "__quick-note";
        final File sessionDir = GsFileUtils.findNonConflictingDest(sessionsDir, base);
        //noinspection ResultOfMethodCallIgnored
        sessionDir.mkdirs();

        final File nonConflict = new File(sessionDir, sessionDir.getName() + ".md");
        if (!nonConflict.exists() && !nonConflict.createNewFile()) {
            throw new IOException("Could not create session file");
        }
        return nonConflict;
    }

    public File getAttachmentDirForSession(@NonNull File session) {
        final File attachmentDir = new File(session.getParentFile(), "assets");
        //noinspection ResultOfMethodCallIgnored
        attachmentDir.mkdirs();
        return attachmentDir;
    }

    public File createAudioFileForSession(@NonNull File session) {
        final File dir = getAttachmentDirForSession(session);
        final String name = "audio-" + TS.format(new Date()) + ".m4a";
        return GsFileUtils.findNonConflictingDest(dir, name);
    }

    public File copyIntoSessionAttachmentDir(@NonNull File session, @NonNull File source) {
        final File dir = getAttachmentDirForSession(session);
        final File dest = GsFileUtils.findNonConflictingDest(dir, source.getName());
        GsFileUtils.copyFile(source, dest);
        return dest;
    }

    public String relativeToSession(@NonNull File session, @NonNull File target) {
        return GsFileUtils.relativePath(session, target);
    }
}
