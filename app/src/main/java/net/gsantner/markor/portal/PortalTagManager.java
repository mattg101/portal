package net.gsantner.markor.portal;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PortalTagManager {
    private static final Pattern HASHTAG = Pattern.compile("#([a-zA-Z0-9_-]+)");
    private static final Pattern TAGS_LINE = Pattern.compile("(?m)^Tags:\\s*(?:#[a-zA-Z0-9_-]+\\s*)*$");

    public List<String> parseAllTags(@NonNull String content) {
        final LinkedHashSet<String> tags = new LinkedHashSet<>();
        tags.addAll(parseYamlTags(content));
        tags.addAll(parseInlineTags(content));
        return new ArrayList<>(tags);
    }

    public List<String> normalize(@NonNull List<String> input) {
        final LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String t : input) {
            final String n = normalizeOne(t);
            if (!n.isEmpty()) {
                unique.add(n);
            }
        }
        return new ArrayList<>(unique);
    }

    public String applyTags(@NonNull String content, @NonNull List<String> rawTags) {
        final List<String> tags = normalize(rawTags);
        final String yamlLine = "tags: [" + String.join(", ", tags) + "]";
        final String inlineLine = "Tags: " + buildHashTagLine(tags);

        String out = upsertYamlTags(content, yamlLine);
        out = TAGS_LINE.matcher(out).replaceAll("").replaceAll("\\n{3,}", "\\n\\n");

        final int insertPos = findInsertPosAfterFrontMatter(out);
        final String before = out.substring(0, insertPos);
        final String after = out.substring(insertPos).replaceFirst("^\\n+", "");
        return before + "\n" + inlineLine + "\n\n" + after;
    }

    private List<String> parseYamlTags(String content) {
        final List<String> tags = new ArrayList<>();
        if (!content.startsWith("---\n")) {
            return tags;
        }
        final int end = content.indexOf("\n---", 4);
        if (end <= 0) {
            return tags;
        }
        final String fm = content.substring(4, end);
        final Matcher m = Pattern.compile("(?m)^tags:\\s*\\[(.*)]\\s*$").matcher(fm);
        if (m.find()) {
            final String[] parts = m.group(1).split(",");
            for (String p : parts) {
                final String n = normalizeOne(p);
                if (!n.isEmpty()) {
                    tags.add(n);
                }
            }
        }
        return tags;
    }

    private List<String> parseInlineTags(String content) {
        final LinkedHashSet<String> tags = new LinkedHashSet<>();
        final Matcher m = HASHTAG.matcher(content);
        while (m.find()) {
            final String n = normalizeOne(m.group(1));
            if (!n.isEmpty()) {
                tags.add(n);
            }
        }
        return new ArrayList<>(tags);
    }

    private String upsertYamlTags(String content, String tagsLine) {
        if (content.startsWith("---\n")) {
            final int endMarker = content.indexOf("\n---", 4);
            if (endMarker > 0) {
                String fm = content.substring(4, endMarker);
                if (Pattern.compile("(?m)^tags:.*$").matcher(fm).find()) {
                    fm = fm.replaceAll("(?m)^tags:.*$", tagsLine);
                } else {
                    fm = fm + "\n" + tagsLine;
                }
                return "---\n" + fm + content.substring(endMarker);
            }
        }
        return "---\n" + tagsLine + "\n---\n\n" + content;
    }

    private int findInsertPosAfterFrontMatter(String content) {
        if (content.startsWith("---\n")) {
            final int endMarker = content.indexOf("\n---", 4);
            if (endMarker > 0) {
                final int lineEnd = content.indexOf('\n', endMarker + 1);
                if (lineEnd > 0) {
                    return lineEnd + 1;
                }
                return content.length();
            }
        }
        return 0;
    }

    private String buildHashTagLine(List<String> tags) {
        final StringBuilder sb = new StringBuilder();
        for (String t : tags) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append('#').append(t);
        }
        return sb.toString();
    }

    private String normalizeOne(String tag) {
        String n = tag == null ? "" : tag.trim().toLowerCase(Locale.ENGLISH);
        n = n.replaceAll("[^a-z0-9_-]+", "-");
        n = n.replaceAll("-+", "-");
        n = n.replaceAll("^-|-$", "");
        return n;
    }
}
