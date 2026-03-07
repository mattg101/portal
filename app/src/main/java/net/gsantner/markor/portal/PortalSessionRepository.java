package net.gsantner.markor.portal;

import android.text.TextUtils;

import androidx.annotation.NonNull;

import net.gsantner.opoc.util.GsFileUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class PortalSessionRepository {
    public static class SessionItem {
        public final File file;
        public final long modifiedAt;
        public final String preview;
        public final boolean hasAudio;
        public final boolean hasImage;

        public SessionItem(File file, long modifiedAt, String preview, boolean hasAudio, boolean hasImage) {
            this.file = file;
            this.modifiedAt = modifiedAt;
            this.preview = preview;
            this.hasAudio = hasAudio;
            this.hasImage = hasImage;
        }
    }

    private final PortalStorage _storage;

    public PortalSessionRepository(@NonNull PortalStorage storage) {
        _storage = storage;
    }

    public File createSession() throws IOException {
        return _storage.createNewSessionFile();
    }

    public List<SessionItem> listSessions(String query) {
        final File dir = _storage.getSessionsDir();
        if (!dir.isDirectory()) {
            return Collections.emptyList();
        }
        final List<File> list = new ArrayList<>();
        collectMarkdownFiles(dir, list);
        final String q = query == null ? "" : query.toLowerCase(Locale.ENGLISH).trim();
        final List<SessionItem> out = new ArrayList<>();
        list.sort(Comparator.comparingLong(File::lastModified).reversed());
        for (File file : list) {
            final String preview = readFirstLines(file, 2);
            if (!TextUtils.isEmpty(q) && !file.getName().toLowerCase(Locale.ENGLISH).contains(q)
                    && !preview.toLowerCase(Locale.ENGLISH).contains(q)) {
                continue;
            }
            final File attachmentDir = new File(file.getParentFile(), "assets");
            boolean hasAudio = false;
            boolean hasImage = false;
            final File[] attachments = attachmentDir.listFiles();
            if (attachments != null) {
                for (File att : attachments) {
                    final String ext = GsFileUtils.getFilenameExtension(att).toLowerCase(Locale.ENGLISH);
                    if (ext.equals(".m4a") || ext.equals(".mp3") || ext.equals(".wav") || ext.equals(".ogg")) {
                        hasAudio = true;
                    } else if (ext.equals(".jpg") || ext.equals(".jpeg") || ext.equals(".png") || ext.equals(".webp")) {
                        hasImage = true;
                    }
                }
            }
            out.add(new SessionItem(file, file.lastModified(), preview, hasAudio, hasImage));
        }
        return out;
    }

    private void collectMarkdownFiles(@NonNull File root, @NonNull List<File> out) {
        final File[] children = root.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collectMarkdownFiles(child, out);
            } else if (child.getName().endsWith(".md")) {
                out.add(child);
            }
        }
    }

    public String readContent(@NonNull File file) {
        return GsFileUtils.readTextFile(file);
    }

    public boolean saveContent(@NonNull File file, @NonNull String content) {
        return GsFileUtils.writeFile(file, content, null);
    }

    private String readFirstLines(File file, int maxLines) {
        final StringBuilder sb = new StringBuilder();
        try {
            final String sanitized = PortalAttachmentPreviewHelper.stripLegacyAttachmentMarkup(GsFileUtils.readTextFile(file));
            if (TextUtils.isEmpty(sanitized)) {
                return "";
            }
            final String[] lines = sanitized.split("\\r?\\n");
            int added = 0;
            for (String line : lines) {
                final String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                sb.append(trimmed);
                if (++added >= maxLines) {
                    break;
                }
            }
        } catch (Exception ignored) {
        }
        return sb.toString();
    }
}
