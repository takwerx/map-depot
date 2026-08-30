
package com.atakmap.android.mapdepot;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;

import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Reads the UAS Wildland Fire Consortium's drone products.
 *
 * Like NIFC's, the name says FTP and the server is HTTPS. Unlike NIFC's, it
 * answers JSON: append {@code ?format=json} to any folder and it returns the
 * folders and files with exact byte counts, so there is no HTML to scrape and
 * no {@code HEAD} request needed before a download.
 *
 * <pre>
 * {"path":"...","folders":[{"name","url"}],
 *  "files":[{"name","size","uploaded","kind","url"}]}
 * </pre>
 *
 * <h3>The server's own file classification cannot be trusted</h3>
 *
 * Every file carries a {@code kind} -- {@code map_aerial}, {@code map_topo},
 * {@code kmz}, {@code log}, {@code shapefiles}, {@code gdb}, {@code other} --
 * and it is tempting to route on it. Measured across all 876 files in the
 * archive on 2026-08-29, it splits by whether the file has a trailing sortie
 * number rather than by what the file is:
 *
 * <pre>
 * 20260728_..._UAS_IR_11x17_Aerial.pdf        kind = map_aerial
 * 20260810_Bologna_UAS_IR_11x17_Aerial_141.pdf kind = other
 * 20260728_..._UAS_IR_Log.pdf                  kind = log
 * 20260810_Bologna_UAS_IR_Log_141.pdf          kind = other
 * </pre>
 *
 * Every category splits the same way, 76 classified and 70 as {@code other}. So
 * routing on {@code kind} would hide 70 real maps *and* file 70 flight logs into
 * {@code grg/} as though they were georeferenced. The filename is right for all
 * 876, so that is what decides, and {@code kind} is carried through as advisory
 * only.
 *
 * <h3>What is offered</h3>
 *
 * Aerial and topo PDFs and the KMZ: 438 of the 876. Geodatabases and shapefile
 * bundles are zips and never were candidates. Flight logs are PDFs and would
 * otherwise pass an extension test -- they are a sortie's paperwork, not a map,
 * and one filed as a GRG drapes a flat page over the operator's map.
 */
public final class UaswfcClient implements MapSource {

    public static final String TAG = "MapDepotUaswfc";

    /**
     * A preference rather than a constant, for the same reason every other
     * endpoint here is one: once tak.gov signs a build, whatever is compiled in
     * is the address every device in the fleet uses.
     */
    public static final String PREF_ROOT = "mapdepot.uaswfcRoot";

    public static final String DEFAULT_ROOT = "https://uaswfc.org/ftp/";

    /**
     * The site refuses a request that does not look like a browser. Sending a
     * plausible one is the difference between a listing and a 403 with no
     * explanation.
     */
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36";

    private static final int CONNECT_TIMEOUT = 20000;
    private static final int READ_TIMEOUT = 60000;

    /** A folder listing far larger than this is not a folder listing. */
    private static final int MAX_LISTING = 4 * 1024 * 1024;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    public String id() {
        return "uaswfc";
    }

    @Override
    public String label() {
        return "UASWFC";
    }

    @Override
    public void shutdown() {
        worker.shutdownNow();
    }

    public static String root() {
        final MapView mv = MapView.getMapView();
        if (mv == null)
            return DEFAULT_ROOT;

        final SharedPreferences prefs = PreferenceManager
                .getDefaultSharedPreferences(mv.getContext());
        final String v = prefs.getString(PREF_ROOT, DEFAULT_ROOT);
        if (v == null || v.trim().isEmpty())
            return DEFAULT_ROOT;
        final String t = v.trim();
        return t.endsWith("/") ? t : t + "/";
    }

    /**
     * @param path a path rooted at the site, as the API hands them back
     *        ({@code /ftp/pacific_nw/}). Empty means the top.
     */
    @Override
    public void list(final String path, final ListingCallback cb) {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final String url = path == null || path.isEmpty()
                            ? root()
                            : origin() + path;
                    final JSONObject doc = new JSONObject(
                            get(url + "?format=json"));

                    if (doc.has("error"))
                        throw new IllegalStateException(
                                doc.optString("error", "not found"));

                    final List<Entry> entries = new ArrayList<>();

                    final JSONArray folders = doc.optJSONArray("folders");
                    for (int i = 0; folders != null && i < folders.length(); i++) {
                        final JSONObject f = folders.optJSONObject(i);
                        if (f == null)
                            continue;
                        final String href = f.optString("url");
                        if (!safePath(href))
                            continue;
                        entries.add(new Entry(f.optString("name"), href, true,
                                "", 0L, ""));
                    }

                    int hidden = 0;
                    final JSONArray files = doc.optJSONArray("files");
                    for (int i = 0; files != null && i < files.length(); i++) {
                        final JSONObject f = files.optJSONObject(i);
                        if (f == null)
                            continue;
                        final String name = f.optString("name");
                        final String href = f.optString("url");
                        if (name.isEmpty() || !safePath(href)) {
                            hidden++;
                            continue;
                        }
                        if (!offered(name)) {
                            hidden++;
                            continue;
                        }
                        entries.add(new Entry(name, href, false,
                                f.optString("uploaded"), f.optLong("size", 0L),
                                f.optString("kind")));
                    }

                    postListing(cb, path, entries, hidden);
                } catch (final Exception e) {
                    Log.w(TAG, "listing failed for " + path + ": " + describe(e));
                    postError(cb, describe(e));
                }
            }
        });
    }

    /** The API returns each folder's path already rooted at the site. */
    @Override
    public String childPath(String parentPath, Entry child) {
        return child.href;
    }

    /**
     * The listing already carries exact byte counts, so this answers from what
     * the caller was given rather than making a second request. It exists to
     * satisfy the contract, not because this source needs it.
     */
    @Override
    public void exactSize(final String url, final SizeCallback cb) {
        main.post(new Runnable() {
            @Override
            public void run() {
                cb.onSize(0L);
            }
        });
    }

    /**
     * Whether this file is worth offering.
     *
     * Decided by name, never by the server's {@code kind} -- see the class note.
     * A flight log is a PDF and would otherwise pass an extension test.
     */
    static boolean offered(String name) {
        if (name == null || !MAP_FILE.matcher(name).matches())
            return false;
        return !UaswfcNaming.isFlightLog(name);
    }

    @Override
    public List<Posting> postingsFor(List<Entry> entries, String path,
            String decodedPath) {

        final List<String> names = new ArrayList<>();
        final Map<String, Entry> byName = new LinkedHashMap<>();
        for (final Entry e : entries) {
            if (e.directory || !e.isMap())
                continue;
            names.add(e.name);
            byName.put(e.name, e);
        }

        final String incident = NifcClient.incidentFolderOf(decodedPath);
        final String below = NifcClient.folderBelowIncident(decodedPath);
        final Map<String, String> translated = UaswfcNaming.translateAll(
                names, incident, below);

        final List<Posting> out = new ArrayList<>();
        for (final Map.Entry<String, String> t : translated.entrySet()) {
            final Entry e = byName.get(t.getKey());
            if (e == null)
                continue;
            out.add(new Posting(origin() + e.href, e.name, t.getValue(),
                    e.bytes, Depot.bytes(e.bytes) + "  ·  " + e.name));
        }
        return out;
    }

    // -------------------------------------------------------------- plumbing

    /** {@code https://uaswfc.org} -- the API's paths are rooted at the site. */
    private static String origin() {
        try {
            final URL u = new URL(root());
            return u.getProtocol() + "://" + u.getAuthority();
        } catch (Exception malformed) {
            return "https://uaswfc.org";
        }
    }

    /**
     * Only a path on this site is followed. A scheme would leave the host
     * entirely and {@code ..} would climb out of the archive quietly.
     */
    private static boolean safePath(String path) {
        if (path == null || path.isEmpty() || !path.startsWith("/"))
            return false;
        return !path.contains("://") && !path.contains("..");
    }

    private static String get(String url) throws Exception {
        final URL u = new URL(url);
        if (!"https".equalsIgnoreCase(u.getProtocol()))
            throw new IllegalStateException("UASWFC must be https");

        final HttpURLConnection conn = (HttpURLConnection) u.openConnection();
        try {
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", "application/json");

            final int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK)
                throw new IllegalStateException("HTTP " + code);

            final java.io.ByteArrayOutputStream out =
                    new java.io.ByteArrayOutputStream();
            final byte[] buf = new byte[8192];
            try (InputStream in = conn.getInputStream()) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > MAX_LISTING)
                        throw new IllegalStateException("listing too large");
                }
            }
            return out.toString("UTF-8");
        } finally {
            conn.disconnect();
        }
    }

    private void postListing(final ListingCallback cb, final String path,
            final List<Entry> entries, final int hidden) {
        main.post(new Runnable() {
            @Override
            public void run() {
                cb.onListing(path, entries, hidden);
            }
        });
    }

    private void postError(final ListingCallback cb, final String msg) {
        main.post(new Runnable() {
            @Override
            public void run() {
                cb.onError(msg);
            }
        });
    }

    private static String describe(Exception e) {
        final String m = e.getMessage();
        return m != null && !m.isEmpty() ? m : e.getClass().getSimpleName();
    }
}
