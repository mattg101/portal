package net.gsantner.markor.portal;

import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import net.gsantner.opoc.util.GsFileUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class PortalAttachmentPreviewHelper {
    public static final class AttachmentItem {
        public final File file;
        public final String title;
        public final String relativePath;
        public final boolean isImage;
        public final boolean isAudio;

        private AttachmentItem(@NonNull File file, @NonNull String title, @NonNull String relativePath, boolean isImage, boolean isAudio) {
            this.file = file;
            this.title = title;
            this.relativePath = relativePath;
            this.isImage = isImage;
            this.isAudio = isAudio;
        }
    }

    private static final List<String> IMAGE_EXTENSIONS = Arrays.asList(".jpg", ".jpeg", ".png", ".webp", ".gif", ".heic");
    private static final List<String> AUDIO_EXTENSIONS = Arrays.asList(".m4a", ".mp3", ".wav", ".ogg", ".aac");

    private PortalAttachmentPreviewHelper() {
    }

    @NonNull
    public static String stripLegacyAttachmentMarkup(@NonNull String content) {
        String cleaned = content.replaceAll("(?m)^!\\[[^\\]]*]\\([^\\)]*\\)\\s*$\\n?", "");
        cleaned = cleaned.replaceAll("(?ms)^<audio\\s+src='[^']+'\\s+controls>.*?</audio>\\s*$\\n?", "");
        return cleaned.replaceAll("\\n{3,}", "\n\n");
    }

    @NonNull
    public static List<AttachmentItem> listAttachmentItems(@NonNull File sessionFile) {
        final File parent = sessionFile.getParentFile();
        if (parent == null) {
            return Collections.emptyList();
        }
        final File attachmentDir = new File(parent, "assets");
        final File[] attachments = attachmentDir.listFiles();
        if (attachments == null || attachments.length == 0) {
            return Collections.emptyList();
        }
        final List<File> sorted = new ArrayList<>(Arrays.asList(attachments));
        Collections.sort(sorted, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        final List<AttachmentItem> out = new ArrayList<>();
        for (File file : sorted) {
            final String ext = GsFileUtils.getFilenameExtension(file).toLowerCase(Locale.ENGLISH);
            final boolean isImage = IMAGE_EXTENSIONS.contains(ext);
            final boolean isAudio = AUDIO_EXTENSIONS.contains(ext);
            if (!isImage && !isAudio) {
                continue;
            }
            out.add(new AttachmentItem(
                    file,
                    GsFileUtils.getFilenameWithoutExtension(file),
                    Uri.fromFile(file).toString(),
                    isImage,
                    isAudio
            ));
        }
        return out;
    }

    @NonNull
    public static String buildAttachmentCardsCss() {
        return ".portal-preview-attachments{margin-top:24px;display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:18px;}"
                + ".portal-preview-card{overflow:hidden;border-radius:24px;box-shadow:0 18px 36px rgba(17,24,39,.16);}"
                + ".portal-preview-card--image{background:#111827;min-height:240px;}"
                + ".portal-preview-card--image img{display:block;width:100%;height:100%;min-height:240px;object-fit:cover;}"
                + ".portal-preview-card--audio{padding:18px;background:linear-gradient(180deg,#5e4545 0%,#433236 100%);color:#fff;}"
                + ".portal-preview-audio-title{font-size:14px;font-weight:700;letter-spacing:.01em;opacity:.96;margin-bottom:14px;word-break:break-word;}"
                + ".portal-preview-audio-wave{display:flex;align-items:flex-end;gap:6px;height:56px;margin-bottom:14px;}"
                + ".portal-preview-audio-wave span{display:block;width:6px;border-radius:999px;background:linear-gradient(180deg,#f6beb3 0%,#f07b72 100%);opacity:.95;}"
                + ".portal-preview-card--audio audio{width:100%;filter:sepia(.18) saturate(.85) brightness(1.08);}";
    }

    @NonNull
    public static String buildAttachmentCardsHtml(@NonNull File sessionFile) {
        final List<AttachmentItem> attachments = listAttachmentItems(sessionFile);
        if (attachments.isEmpty()) {
            return "";
        }
        final StringBuilder html = new StringBuilder("<section class='portal-preview-attachments'>");
        for (AttachmentItem attachment : attachments) {
            if (attachment.isImage) {
                html.append("<figure class='portal-preview-card portal-preview-card--image'>")
                        .append("<img src='").append(escapeHtml(attachment.relativePath)).append("' alt='")
                        .append(escapeHtml(attachment.title)).append("' />")
                        .append("</figure>");
            } else if (attachment.isAudio) {
                html.append("<section class='portal-preview-card portal-preview-card--audio'>")
                        .append("<div class='portal-preview-audio-title'>")
                        .append(escapeHtml(attachment.file.getName()))
                        .append("</div>")
                        .append("<div class='portal-preview-audio-wave' aria-hidden='true'>")
                        .append(buildWaveBarsSvgHtml(attachment.file.getName()))
                        .append("</div>")
                        .append("<audio controls preload='metadata' src='")
                        .append(escapeHtml(attachment.relativePath))
                        .append("'></audio>")
                        .append("</section>");
            }
        }
        html.append("</section>");
        return html.toString();
    }

    @NonNull
    private static String buildWaveBarsSvgHtml(@NonNull String seedText) {
        final int[] heights = buildWaveHeights(seedText);
        final StringBuilder html = new StringBuilder();
        for (int height : heights) {
            html.append("<span style='height:")
                    .append(height)
                    .append("px'></span>");
        }
        return html.toString();
    }

    @NonNull
    private static int[] buildWaveHeights(@NonNull String seedText) {
        final int[] heights = new int[24];
        int hash = seedText.hashCode();
        for (int i = 0; i < heights.length; i++) {
            hash = (hash * 1103515245) + 12345;
            final int normalized = Math.abs(hash % 28);
            heights[i] = 12 + normalized;
        }
        return heights;
    }

    @NonNull
    private static String escapeHtml(@NonNull String value) {
        if (TextUtils.isEmpty(value)) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
