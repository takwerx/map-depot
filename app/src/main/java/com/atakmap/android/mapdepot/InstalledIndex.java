
package com.atakmap.android.mapdepot;

import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Remembers where each downloaded incident map came from.
 *
 * The list can only filter what it is currently looking at, and a fire's folder
 * is mostly subfolders -- so standing at the top of an archive, or at a fire with
 * one folder per day, "Installed" had nothing to count and showed an empty list
 * even with a dozen maps on the device. What the operator wants is the opposite:
 * everything installed when they are high up, narrowing to the folder as they
 * walk down into it.
 *
 * A downloaded file does not say where it came from -- {@code grg/} is flat and
 * holds only a filename -- so it has to be written down at install time. This is
 * that record: one entry per installed map, carrying enough to rebuild its row
 * without the server being reachable.
 *
 * <h3>The disk is still the truth</h3>
 *
 * The index says what was downloaded and from where; whether it is *still there*
 * is asked of the filesystem every time, because a map can be removed from
 * ATAK's overlay manager, deleted by a file browser, or lost with the SD card,
 * and none of those tell the plugin. An entry whose file has gone is dropped on
 * sight rather than shown as installed.
 *
 * Kept in ATAK's own preferences on the MapView context, not the plugin's: the
 * plugin context's preferences do not survive a plugin reload, and an index that
 * forgets itself on every update is worse than no index at all.
 */
public final class InstalledIndex {

    public static final String TAG = "MapDepotInstalled";

    private static final String PREF_KEY = "mapdepot_installed_index";

    /** One installed map, and where it came from. */
    public static final class Record {
        public final String sourceId;
        /** The folder path within that source, as the browser walks it. */
        public final String path;
        public final String url;
        public final String originalName;
        public final String installName;
        public final String destination;
        public final long bytes;

        public Record(String sourceId, String path, String url,
                String originalName, String installName, String destination,
                long bytes) {
            this.sourceId = sourceId;
            this.path = path;
            this.url = url;
            this.originalName = originalName;
            this.installName = installName;
            this.destination = destination;
            this.bytes = bytes;
        }

        /** Where the file should be, so its continued existence can be checked. */
        public File file() {
            return new File(
                    com.atakmap.coremap.filesystem.FileSystemUtils
                            .getItem(destination),
                    installName);
        }
    }

    private InstalledIndex() {
    }

    private static SharedPreferences prefs() {
        final MapView mv = MapView.getMapView();
        if (mv == null)
            return null;
        return PreferenceManager.getDefaultSharedPreferences(mv.getContext());
    }

    /**
     * Every remembered map whose file is still on the device.
     *
     * Entries whose file has gone are not returned, and are written out of the
     * index as a side effect, so a device that has been tidied up elsewhere
     * settles rather than accumulating ghosts.
     */
    public static List<Record> all() {
        final List<Record> out = new ArrayList<>();
        final SharedPreferences p = prefs();
        if (p == null)
            return out;

        boolean stale = false;
        try {
            final JSONArray arr = new JSONArray(p.getString(PREF_KEY, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                final JSONObject o = arr.optJSONObject(i);
                if (o == null)
                    continue;
                final Record r = new Record(
                        o.optString("source"), o.optString("path"),
                        o.optString("url"), o.optString("original"),
                        o.optString("name"), o.optString("dest", "grg"),
                        o.optLong("bytes", 0L));
                if (r.installName.isEmpty())
                    continue;
                if (r.file().isFile())
                    out.add(r);
                else
                    stale = true;
            }
        } catch (Exception unreadable) {
            // A corrupt index is a smaller problem than a plugin that will not
            // open. Start again rather than throwing on every listing.
            Log.w(TAG, "index unreadable, starting over: " + unreadable);
            p.edit().remove(PREF_KEY).apply();
            return out;
        }

        if (stale)
            write(out);
        return out;
    }

    /**
     * Everything installed at or below {@code path} within one source.
     *
     * An empty path means the whole archive, which is what the top of the
     * browser shows.
     */
    public static List<Record> under(String sourceId, String path) {
        final List<Record> out = new ArrayList<>();
        final String want = path == null ? "" : path;
        for (final Record r : all()) {
            if (sourceId != null && !sourceId.equals(r.sourceId))
                continue;
            if (want.isEmpty() || r.path.startsWith(want))
                out.add(r);
        }
        return out;
    }

    public static void add(Record r) {
        final List<Record> kept = new ArrayList<>();
        for (final Record existing : all()) {
            if (!sameFile(existing, r))
                kept.add(existing);
        }
        kept.add(r);
        write(kept);
    }

    public static void remove(String installName, String destination) {
        final List<Record> kept = new ArrayList<>();
        for (final Record r : all()) {
            if (r.installName.equalsIgnoreCase(installName)
                    && r.destination.equals(destination))
                continue;
            kept.add(r);
        }
        write(kept);
    }

    /**
     * Two records are the same map when they land on the same file, whatever
     * folder they were found in -- the same posting appears in more than one
     * date folder, and the device holds one file either way.
     */
    private static boolean sameFile(Record a, Record b) {
        return a.installName.equalsIgnoreCase(b.installName)
                && a.destination.equals(b.destination);
    }

    private static void write(List<Record> records) {
        final SharedPreferences p = prefs();
        if (p == null)
            return;
        final JSONArray arr = new JSONArray();
        for (final Record r : records) {
            try {
                final JSONObject o = new JSONObject();
                o.put("source", r.sourceId);
                o.put("path", r.path);
                o.put("url", r.url);
                o.put("original", r.originalName);
                o.put("name", r.installName);
                o.put("dest", r.destination);
                o.put("bytes", r.bytes);
                arr.put(o);
            } catch (Exception skip) {
                Log.w(TAG, "could not record " + r.installName + ": " + skip);
            }
        }
        p.edit().putString(PREF_KEY, arr.toString()).apply();
    }

    /** Rebuilds a row for a map the current folder does not itself list. */
    public static MapSource.Posting toPosting(Record r) {
        return new MapSource.Posting(r.url, r.originalName, r.installName,
                r.bytes, Depot.bytes(r.bytes) + "  ·  "
                        + shortPath(r.path));
    }

    /**
     * The folder a map came from, for a row being shown somewhere other than
     * where it was found. The full path is too long for a phone; the last two
     * segments say which fire and which day, which is what identifies it.
     */
    private static String shortPath(String path) {
        if (path == null || path.isEmpty())
            return "";
        final String[] parts = path.replaceAll("/+$", "").split("/");
        if (parts.length <= 2)
            return path.replaceAll("/+$", "");
        return parts[parts.length - 2] + "/" + parts[parts.length - 1];
    }

    /** Case-insensitive, because the filesystem under ATAK may be too. */
    public static boolean holds(List<Record> records, String installName) {
        for (final Record r : records) {
            if (r.installName.equalsIgnoreCase(installName))
                return true;
        }
        return false;
    }

    public static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.US);
    }
}
