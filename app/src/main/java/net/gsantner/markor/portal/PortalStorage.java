package net.gsantner.markor.portal;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import androidx.annotation.NonNull;

import net.gsantner.opoc.util.GsFileUtils;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class PortalStorage {
    public static final String DIR_PORTAL = "Portal";
    public static final String DIR_OUTBOX = "Portal Outbox";
    public static final String DIR_DRAFTS = "Portal Drafts";
    public static final String DIR_SESSIONS = "Portal Sessions";
    private static final String PREF = "portal_storage";
    private static final String KEY_SAVE_ROOT = "save_root_path";
    private static final String KEY_DRAFT_ROOT = "draft_root_path";
    private static final String KEY_RENDER_MARKDOWN = "render_markdown";
    private static final String KEY_CUSTOM_CLASSIFICATIONS = "custom_classifications";
    private static final String KEY_HIDDEN_DEFAULT_CLASSIFICATIONS = "hidden_default_classifications";

    private final SharedPreferences _pref;
    private static final SimpleDateFormat TS = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ENGLISH);

    public PortalStorage(@NonNull Context context) {
        _pref = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public File getNotebookRoot() {
        final String custom = _pref.getString(KEY_SAVE_ROOT, "");
        if (custom != null && !custom.trim().isEmpty()) {
            return new File(custom.trim());
        }
        return new File(getPortalRoot(), DIR_OUTBOX);
    }

    public File getPortalRoot() {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), DIR_PORTAL);
    }

    public String getConfiguredRootPath() {
        final String custom = _pref.getString(KEY_SAVE_ROOT, "");
        return custom == null ? "" : custom;
    }

    public void setConfiguredRootPath(String path) {
        _pref.edit().putString(KEY_SAVE_ROOT, path == null ? "" : path.trim()).apply();
    }

    public File getDraftRoot() {
        final String custom = _pref.getString(KEY_DRAFT_ROOT, "");
        if (custom != null && !custom.trim().isEmpty()) {
            return new File(custom.trim());
        }
        return new File(getPortalRoot(), DIR_DRAFTS);
    }

    public String getConfiguredDraftPath() {
        final String custom = _pref.getString(KEY_DRAFT_ROOT, "");
        return custom == null ? "" : custom;
    }

    public void setConfiguredDraftPath(String path) {
        _pref.edit().putString(KEY_DRAFT_ROOT, path == null ? "" : path.trim()).apply();
    }

    public boolean isRenderMarkdownEnabled() {
        return _pref.getBoolean(KEY_RENDER_MARKDOWN, false);
    }

    public void setRenderMarkdownEnabled(boolean enabled) {
        _pref.edit().putBoolean(KEY_RENDER_MARKDOWN, enabled).apply();
    }

    public List<String> getCustomClassifications() {
        final java.util.Set<String> stored = _pref.getStringSet(KEY_CUSTOM_CLASSIFICATIONS, java.util.Collections.emptySet());
        return new java.util.ArrayList<>(stored);
    }

    public void recordCustomClassification(@NonNull String slug) {
        final java.util.LinkedHashSet<String> next = new java.util.LinkedHashSet<>(getCustomClassifications());
        if (slug.trim().isEmpty()) {
            return;
        }
        next.add(slug.trim());
        _pref.edit().putStringSet(KEY_CUSTOM_CLASSIFICATIONS, next).apply();
    }

    public void removeCustomClassification(@NonNull String slug) {
        final java.util.LinkedHashSet<String> next = new java.util.LinkedHashSet<>(getCustomClassifications());
        next.remove(slug.trim());
        _pref.edit().putStringSet(KEY_CUSTOM_CLASSIFICATIONS, next).apply();
    }

    public java.util.Set<String> getHiddenDefaultClassifications() {
        return new java.util.LinkedHashSet<>(
                _pref.getStringSet(KEY_HIDDEN_DEFAULT_CLASSIFICATIONS, java.util.Collections.emptySet())
        );
    }

    public void hideDefaultClassification(@NonNull String slug) {
        final String trimmed = slug.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        final java.util.LinkedHashSet<String> next = new java.util.LinkedHashSet<>(getHiddenDefaultClassifications());
        next.add(trimmed);
        _pref.edit().putStringSet(KEY_HIDDEN_DEFAULT_CLASSIFICATIONS, next).apply();
    }

    public void restoreDefaultClassification(@NonNull String slug) {
        final java.util.LinkedHashSet<String> next = new java.util.LinkedHashSet<>(getHiddenDefaultClassifications());
        next.remove(slug.trim());
        _pref.edit().putStringSet(KEY_HIDDEN_DEFAULT_CLASSIFICATIONS, next).apply();
    }

    public File getSessionsDir() {
        return new File(getPortalRoot(), DIR_SESSIONS);
    }

    public boolean ensureWritableRoot() {
        final File root = getNotebookRoot();
        return (root.isDirectory() || root.mkdirs()) && root.canWrite();
    }

    public boolean ensureDraftRoot() {
        final File root = getDraftRoot();
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

    public File createImageFileForSession(@NonNull File session) {
        final File dir = getAttachmentDirForSession(session);
        final String name = "image-" + TS.format(new Date()) + ".jpg";
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
