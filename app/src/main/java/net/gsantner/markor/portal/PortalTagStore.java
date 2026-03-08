package net.gsantner.markor.portal;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class PortalTagStore {
    private static final String PREF = "portal_tag_store";
    private static final String PREFIX_COUNT = "count_";
    private static final String PREFIX_TIME = "time_";

    private final SharedPreferences _pref;

    public PortalTagStore(@NonNull Context context) {
        _pref = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public void recordTagUse(@NonNull List<String> tags) {
        final long now = System.currentTimeMillis();
        final SharedPreferences.Editor e = _pref.edit();
        for (String tag : tags) {
            final String countKey = PREFIX_COUNT + tag;
            final int count = _pref.getInt(countKey, 0) + 1;
            e.putInt(countKey, count);
            e.putLong(PREFIX_TIME + tag, now);
        }
        e.apply();
    }

    public List<String> getTopTags(int max) {
        final List<TagStats> stats = new ArrayList<>();
        for (Map.Entry<String, ?> entry : _pref.getAll().entrySet()) {
            final String key = entry.getKey();
            if (!key.startsWith(PREFIX_COUNT)) {
                continue;
            }
            final String tag = key.substring(PREFIX_COUNT.length());
            final int count = _pref.getInt(key, 0);
            final long last = _pref.getLong(PREFIX_TIME + tag, 0);
            stats.add(new TagStats(tag, count, last));
        }
        Collections.sort(stats, Comparator
                .comparingInt((TagStats s) -> s.count).reversed()
                .thenComparingLong((TagStats s) -> s.lastUsed).reversed());

        final List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(max, stats.size()); i++) {
            out.add(stats.get(i).tag);
        }
        return out;
    }

    public void removeTag(@NonNull String tag) {
        _pref.edit()
                .remove(PREFIX_COUNT + tag)
                .remove(PREFIX_TIME + tag)
                .apply();
    }

    private static class TagStats {
        final String tag;
        final int count;
        final long lastUsed;

        TagStats(String tag, int count, long lastUsed) {
            this.tag = tag;
            this.count = count;
            this.lastUsed = lastUsed;
        }
    }
}
