
package com.atakmap.android.mapdepot;

import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The fires an operator is working, pinned so they are one tap from the top.
 *
 * A crew assigned to a fire opens the same folder twenty times a day, and
 * reaching it means a region, a year folder, the fire, and then the product
 * folder -- four taps, every time, to arrive somewhere they never leave. This is
 * the shortcut: pin it once and it sits at the top of the list.
 *
 * <p>Modelled on CamDepot's {@code Favorites}, and for the same reasons. One flat
 * list rather than anything scoped to the region currently showing, because the
 * point is a fire in Oregon sitting next to one in California. Kept in ATAK's own
 * default preferences on the <em>MapView</em> context: the plugin context's
 * preferences do not survive the plugin being reloaded, and a pin that forgets
 * itself on every update is worse than no pin.
 *
 * <p>A pin stores the path rather than a name, because the path is what the
 * browser needs to go there and a fire's display name is not unique -- two
 * regions can both have a Bear.
 */
public final class Pinned {

    public static final String TAG = "MapDepotPinned";

    private static final String PREF_KEY = "mapdepot_pinned";

    /** One pinned folder, and enough to both show it and walk to it. */
    public static final class Entry {
        public final String sourceId;
        /** The path the source browses with, opaque to everything else. */
        public final String path;
        /** The same path decoded, for naming downloads and the breadcrumb. */
        public final String decodedPath;
        /** What to call it in the list. */
        public final String label;

        public Entry(String sourceId, String path, String decodedPath,
                String label) {
            this.sourceId = sourceId;
            this.path = path;
            this.decodedPath = decodedPath;
            this.label = label;
        }
    }

    private Pinned() {
    }

    private static SharedPreferences prefs() {
        final MapView mv = MapView.getMapView();
        if (mv == null)
            return null;
        return PreferenceManager.getDefaultSharedPreferences(mv.getContext());
    }

    public static List<Entry> all(String sourceId) {
        final List<Entry> out = new ArrayList<>();
        final SharedPreferences p = prefs();
        if (p == null)
            return out;
        try {
            final JSONArray arr = new JSONArray(p.getString(PREF_KEY, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                final JSONObject o = arr.optJSONObject(i);
                if (o == null)
                    continue;
                final Entry e = new Entry(o.optString("source"),
                        o.optString("path"), o.optString("decoded"),
                        o.optString("label"));
                if (e.path.isEmpty())
                    continue;
                if (sourceId == null || sourceId.equals(e.sourceId))
                    out.add(e);
            }
        } catch (Exception unreadable) {
            Log.w(TAG, "pins unreadable, starting over: " + unreadable);
            p.edit().remove(PREF_KEY).apply();
        }
        return out;
    }

    public static boolean isPinned(String sourceId, String path) {
        for (final Entry e : all(sourceId)) {
            if (e.path.equals(path))
                return true;
        }
        return false;
    }

    public static void pin(Entry e) {
        final List<Entry> kept = new ArrayList<>();
        for (final Entry existing : all(null)) {
            if (!(existing.sourceId.equals(e.sourceId)
                    && existing.path.equals(e.path)))
                kept.add(existing);
        }
        kept.add(e);
        write(kept);
    }

    public static void unpin(String sourceId, String path) {
        final List<Entry> kept = new ArrayList<>();
        for (final Entry e : all(null)) {
            if (e.sourceId.equals(sourceId) && e.path.equals(path))
                continue;
            kept.add(e);
        }
        write(kept);
    }

    private static void write(List<Entry> entries) {
        final SharedPreferences p = prefs();
        if (p == null)
            return;
        final JSONArray arr = new JSONArray();
        for (final Entry e : entries) {
            try {
                final JSONObject o = new JSONObject();
                o.put("source", e.sourceId);
                o.put("path", e.path);
                o.put("decoded", e.decodedPath);
                o.put("label", e.label);
                arr.put(o);
            } catch (Exception skip) {
                Log.w(TAG, "could not store pin " + e.label + ": " + skip);
            }
        }
        p.edit().putString(PREF_KEY, arr.toString()).apply();
    }

    /**
     * The fire a path belongs to, which is what a pin should be called.
     *
     * Falls back to the last segment when there is no year-prefixed folder in
     * the path -- a pin on {@code Reference_Maps} is still worth naming.
     */
    public static String labelFor(String decodedPath) {
        if (decodedPath == null || decodedPath.isEmpty())
            return "";
        final String incident = NifcClient.incidentFolderOf(decodedPath);
        if (incident != null)
            return incident;
        final String[] parts = decodedPath.replaceAll("/+$", "").split("/");
        return parts.length == 0 ? decodedPath : parts[parts.length - 1];
    }

    static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.US);
    }
}
