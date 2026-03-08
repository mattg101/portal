package net.gsantner.markor.portal;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.Patterns;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.gsantner.markor.format.FormatRegistry;
import net.gsantner.markor.frontend.AttachLinkOrFileDialog;
import net.gsantner.opoc.util.GsFileUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PortalShareImport {
    private static final Pattern TITLE_TAG = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");

    private PortalShareImport() {
    }

    public static boolean isSupportedShareIntent(@Nullable Intent intent) {
        if (intent == null) {
            return false;
        }
        final String action = intent.getAction();
        return Intent.ACTION_SEND.equals(action) || Intent.ACTION_SEND_MULTIPLE.equals(action);
    }

    public static void seedSessionFromShareIntent(
            @NonNull Context context,
            @NonNull PortalStorage storage,
            @NonNull File sessionFile,
            @NonNull Intent intent
    ) throws Exception {
        final String sharedText = extractSharedText(intent);
        final String firstUrl = findFirstUrl(sharedText);
        final boolean isLinkShare = !TextUtils.isEmpty(firstUrl);

        final List<Uri> imageUris = collectImageUris(intent);
        if (!isLinkShare && !imageUris.isEmpty()) {
            copyImagesIntoSession(context, storage, sessionFile, imageUris);
        }

        if (TextUtils.isEmpty(sharedText)) {
            return;
        }
        if (TextUtils.isEmpty(firstUrl)) {
            GsFileUtils.writeFile(sessionFile, sharedText.trim(), null);
            return;
        }

        final String sanitizedUrl = sanitize(firstUrl);
        final String linkTitle = firstNonEmpty(
                cleanTitle(intent.getStringExtra(Intent.EXTRA_SUBJECT)),
                fetchHtmlTitleBlocking(sanitizedUrl),
                deriveFallbackTitle(sanitizedUrl)
        );
        final String linkMarkup = AttachLinkOrFileDialog.formatLink(linkTitle, sanitizedUrl, FormatRegistry.FORMAT_MARKDOWN);
        final StringBuilder content = new StringBuilder();
        if (!TextUtils.isEmpty(linkTitle)) {
            content.append("# ").append(linkTitle).append("\n\n");
        }
        content.append(linkMarkup).append("\n\n");
        GsFileUtils.writeFile(sessionFile, content.toString(), null);
    }

    @NonNull
    private static String extractSharedText(@NonNull Intent intent) {
        final String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        return text == null ? "" : text.trim();
    }

    @Nullable
    private static String findFirstUrl(@NonNull String text) {
        final Matcher matcher = Patterns.WEB_URL.matcher(text);
        if (matcher.find()) {
            return matcher.group();
        }
        return null;
    }

    @NonNull
    private static List<Uri> collectImageUris(@NonNull Intent intent) {
        final List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            final Uri single = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (single != null) {
                uris.add(single);
            }
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            final ArrayList<Uri> multiple = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (multiple != null) {
                uris.addAll(multiple);
            }
        }
        if (uris.isEmpty() && intent.getClipData() != null) {
            for (int i = 0; i < intent.getClipData().getItemCount(); i++) {
                final Uri uri = intent.getClipData().getItemAt(i).getUri();
                if (uri != null) {
                    uris.add(uri);
                }
            }
        }
        return uris;
    }

    private static void copyImagesIntoSession(
            @NonNull Context context,
            @NonNull PortalStorage storage,
            @NonNull File sessionFile,
            @NonNull List<Uri> uris
    ) throws Exception {
        final ContentResolver resolver = context.getContentResolver();
        final File attachmentDir = storage.getAttachmentDirForSession(sessionFile);
        for (Uri uri : uris) {
            final String name = resolveDisplayName(resolver, uri, guessNameFromUri(uri));
            final File dest = GsFileUtils.findNonConflictingDest(attachmentDir, ensureImageExtension(name, resolver.getType(uri)));
            try (InputStream in = resolver.openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(dest)) {
                final byte[] buffer = new byte[8192];
                int read;
                while (in != null && (read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
            }
        }
    }

    @NonNull
    private static String resolveDisplayName(@NonNull ContentResolver resolver, @NonNull Uri uri, @NonNull String fallback) {
        Cursor cursor = null;
        try {
            cursor = resolver.query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                final int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    final String displayName = cursor.getString(idx);
                    if (!TextUtils.isEmpty(displayName)) {
                        return displayName;
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return fallback;
    }

    @NonNull
    private static String guessNameFromUri(@NonNull Uri uri) {
        final String segment = uri.getLastPathSegment();
        if (!TextUtils.isEmpty(segment)) {
            return segment.replaceAll("[^a-zA-Z0-9._-]+", "_");
        }
        return "shared-image-" + System.currentTimeMillis() + ".jpg";
    }

    @NonNull
    private static String ensureImageExtension(@NonNull String name, @Nullable String mime) {
        final String lower = name.toLowerCase(Locale.ENGLISH);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".heic")) {
            return name;
        }
        if (mime == null) {
            return name + ".jpg";
        }
        if (mime.contains("png")) {
            return name + ".png";
        }
        if (mime.contains("webp")) {
            return name + ".webp";
        }
        if (mime.contains("gif")) {
            return name + ".gif";
        }
        if (mime.contains("heic")) {
            return name + ".heic";
        }
        return name + ".jpg";
    }

    @NonNull
    private static String sanitize(@NonNull String link) {
        String dropGetParams = "utm_|source|si|__mk_|ref|sprefix|crid|partner|promo|ad_sub|gclid|fbclid|msclkid|dib";
        if (link.contains("amazon.")) {
            dropGetParams += "|qid|sr";
        }
        return link.replaceAll("(?m)(?<=&|\\?)(" + dropGetParams + ").*?(&|$|\\s|\\))", "");
    }

    @NonNull
    private static String cleanTitle(@Nullable String title) {
        return title == null ? "" : title.trim();
    }

    @NonNull
    private static String fetchHtmlTitleBlocking(@NonNull String urlString) {
        final AtomicReference<String> result = new AtomicReference<>("");
        final Thread worker = new Thread(() -> result.set(fetchHtmlTitle(urlString)), "portal-share-title");
        worker.start();
        try {
            worker.join(1800);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        return result.get() == null ? "" : result.get();
    }

    @NonNull
    private static String fetchHtmlTitle(@NonNull String urlString) {
        HttpURLConnection connection = null;
        InputStream in = null;
        try {
            connection = (HttpURLConnection) new URL(urlString).openConnection();
            connection.setConnectTimeout(1500);
            connection.setReadTimeout(1500);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0");
            in = connection.getInputStream();
            final byte[] buffer = new byte[32768];
            final int read = in.read(buffer);
            if (read <= 0) {
                return "";
            }
            final String html = new String(buffer, 0, read);
            final Matcher matcher = TITLE_TAG.matcher(html);
            if (matcher.find()) {
                return matcher.group(1)
                        .replaceAll("(?s)<[^>]+>", "")
                        .replace("&amp;", "&")
                        .replace("&quot;", "\"")
                        .replace("&#39;", "'")
                        .replace("&lt;", "<")
                        .replace("&gt;", ">")
                        .trim();
            }
        } catch (Exception ignored) {
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Exception ignored) {
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
        return "";
    }

    @NonNull
    private static String deriveFallbackTitle(@NonNull String urlString) {
        try {
            final URL url = new URL(urlString);
            final String host = url.getHost() == null ? "" : url.getHost().replaceFirst("^www\\.", "");
            final String path = url.getPath() == null ? "" : url.getPath();
            if (!TextUtils.isEmpty(path) && path.length() > 1) {
                final String[] parts = path.split("/");
                final String last = parts[parts.length - 1].replace('-', ' ').replace('_', ' ').trim();
                if (!TextUtils.isEmpty(last)) {
                    return last;
                }
            }
            return host;
        } catch (Exception ignored) {
            return urlString;
        }
    }

    @NonNull
    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (!TextUtils.isEmpty(value)) {
                return value;
            }
        }
        return "";
    }
}
