package net.gsantner.markor.portal;

import android.content.Context;

import androidx.annotation.NonNull;

import net.gsantner.markor.model.AppSettings;
import net.gsantner.opoc.util.GsFileUtils;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class PortalStorage {
    public static final String DIR_PORTAL = "portal";
    public static final String DIR_SESSIONS = "sessions";
    public static final String DIR_ATTACHMENTS = "attachments";

    private final AppSettings _appSettings;
    private static final SimpleDateFormat TS = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ENGLISH);

    public PortalStorage(@NonNull Context context) {
        _appSettings = AppSettings.get(context);
    }

    public File getNotebookRoot() {
        return _appSettings.getNotebookDirectory();
    }

    public File getPortalRoot() {
        return new File(getNotebookRoot(), DIR_PORTAL);
    }

    public File getSessionsDir() {
        return new File(getPortalRoot(), DIR_SESSIONS);
    }

    public File getAttachmentsDir() {
        return new File(getPortalRoot(), DIR_ATTACHMENTS);
    }

    public boolean ensureWritableRoot() {
        final File root = getNotebookRoot();
        return (root.isDirectory() || root.mkdirs()) && root.canWrite();
    }

    public File createNewSessionFile() throws IOException {
        final File sessionsDir = getSessionsDir();
        //noinspection ResultOfMethodCallIgnored
        sessionsDir.mkdirs();
        final String name = "session-" + TS.format(new Date()) + ".md";
        final File nonConflict = GsFileUtils.findNonConflictingDest(sessionsDir, name);
        if (!nonConflict.exists() && !nonConflict.createNewFile()) {
            throw new IOException("Could not create session file");
        }
        return nonConflict;
    }

    public File getAttachmentDirForSession(@NonNull File session) {
        final String id = GsFileUtils.getFilenameWithoutExtension(session);
        final File attachmentDir = new File(getAttachmentsDir(), id);
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
