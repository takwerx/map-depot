package com.atakmap.android.mapdepot;

import android.os.Handler;
import android.os.Looper;

import com.atakmap.coremap.log.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Beacon Box: FlameMapper's neighborhood pre-plans -- a base map, an aerial,
 * a fire-science sheet and a structure-vulnerability sheet per neighborhood,
 * as GeoPDFs -- for the Santa Monica Mountains communities and a few others.
 *
 * Read from the depot, not from the maps' own site. That site sits behind a
 * bot check that a phone cannot pass (measured 2026-09-03: page and PDF
 * alike answer 403 to anything that is not a person in a browser), so the
 * owner's folder is mirrored on the depot with their permission, and every
 * folder carries an {@code index.json} written by the mirroring tool:
 *
 * <pre>
 * {"folders":[{"name":"Beverly Hills","path":"Beverly Hills/"}],
 *  "files":[{"name":"BB_x.pdf","size":5304696,"uploaded":"2026-09-03",
 *            "georef":true}]}
 * </pre>
 *
 * The browser is the one the incident archives use: areas at the top,
 * neighborhoods under them, maps with a size and a Download. A PDF the tool
 * found no georeferencing in is counted as hidden rather than offered,
 * because ATAK could only drape it flat over the wrong place.
 */
public final class BeaconClient implements MapSource {

    public static final String TAG = "MapDepotBeacon";

    /** Under the depot's base URL. Fixed: it is our own bucket. */
    static final String ROOT = "beacon/";
    private static final String INDEX = "index.json";

    private static final int CONNECT_TIMEOUT = 20000;
    private static final int READ_TIMEOUT = 60000;
    private static final int MAX_LISTING = 4 * 1024 * 1024;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    public String id() {
        return "beacon";
    }

    @Override
    public String label() {
        return "Beacon Box";
    }

    @Override
    public void shutdown() {
        worker.shutdownNow();
    }

    /**
     * @param path folder path from the mirror's root, plain text with a
     *        trailing slash ({@code Beverly Hills/Coldwater Canyon/}); empty
     *        means the top.
     */
    @Override
    public void list(final String path, final ListingCallback cb) {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!safePath(path))
                        throw new IllegalStateException("bad folder path");
                    final String here = path == null ? "" : path;
                    final JSONObject doc = new JSONObject(get(urlFor(here) + INDEX));
                    final List<Entry> entries = new ArrayList<>();
                    final JSONArray folders = doc.optJSONArray("folders");
                    for (int i = 0; folders != null && i < folders.length(); i++) {
                        final JSONObject f = folders.optJSONObject(i);
                        if (f == null)
                            continue;
                        final String href = f.optString("path");
                        if (href.isEmpty() || !safePath(href))
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
                        // A name that is only an extension (".pdf") passes the
                        // shared checks and would install as a dotfile ATAK
                        // never looks at; it is not a map name.
                        if (!MapSource.safeName(name)
                                || !MAP_FILE.matcher(name).matches()
                                || name.lastIndexOf('.') <= 0
                                || !f.optBoolean("georef", true)) {
                            hidden++;
                            continue;
                        }
                        entries.add(new Entry(name, here + name, false,
                                f.optString("uploaded"), f.optLong("size", 0L),
                                BeaconNaming.kindOf(name)));
                    }
                    postListing(cb, path, entries, hidden);
                } catch (final Exception e) {
                    Log.w(TAG, "listing failed for " + path + ": " + describe(e));
                    postError(cb, describe(e));
                }
            }
        });
    }

    /** Folder paths in the index are already rooted at the mirror. */
    @Override
    public String childPath(String parentPath, Entry child) {
        return child.href;
    }

    /** The index carries exact sizes, written from the files themselves. */
    @Override
    public void exactSize(final Posting posting, final SizeCallback cb) {
        main.post(new Runnable() {
            @Override
            public void run() {
                cb.onSize(posting.bytes());
            }
        });
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
        final Map<String, String> translated = BeaconNaming.translateAll(names);
        final List<Posting> out = new ArrayList<>();
        for (final Map.Entry<String, String> t : translated.entrySet()) {
            final Entry e = byName.get(t.getKey());
            if (e == null)
                continue;
            out.add(new Posting(urlFor(e.href), e.name, t.getValue(), e.bytes,
                    BeaconNaming.kindOf(e.name) + "  ·  " + Depot.bytes(e.bytes)
                            + "  ·  " + e.name));
        }
        return out;
    }

    // -------------------------------------------------------------- plumbing

    /** The depot URL for a mirror path, each segment encoded on its own. */
    static String urlFor(String path) {
        final StringBuilder sb = new StringBuilder(DepotClient.baseUrl())
                .append('/').append(ROOT);
        final String[] segments = path.split("/");
        for (int i = 0; i < segments.length; i++) {
            if (segments[i].isEmpty())
                continue;
            sb.append(encode(segments[i]));
            if (i < segments.length - 1 || path.endsWith("/"))
                sb.append('/');
        }
        return sb.toString();
    }

    private static String encode(String segment) {
        try {
            return URLEncoder.encode(segment, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException never) {
            throw new IllegalStateException(never);
        }
    }

    /**
     * Only a path inside the mirror is followed: no scheme, no climbing out,
     * nothing rooted elsewhere. Folder names carry spaces and periods, which
     * are fine; empty is the top and is fine too.
     */
    private static boolean safePath(String path) {
        if (path == null || path.isEmpty())
            return true;
        return !path.startsWith("/") && !path.contains("://")
                && !path.contains("..") && path.indexOf('\\') < 0
                && path.indexOf('%') < 0 && path.indexOf('?') < 0
                && path.indexOf('#') < 0;
    }

    private static String get(String url) throws Exception {
        final URL u = new URL(url);
        if (!"https".equalsIgnoreCase(u.getProtocol()))
            throw new IllegalStateException("the depot must be https");
        final HttpURLConnection conn = (HttpURLConnection) u.openConnection();
        try {
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setRequestProperty("User-Agent", "MapDepot/1");
            conn.setRequestProperty("Accept", "application/json");
            final int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_FOUND)
                throw new FileNotFoundException(url);
            if (code != HttpURLConnection.HTTP_OK)
                throw new IllegalStateException("HTTP " + code);
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
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
        String host = "the depot";
        try {
            host = new URL(DepotClient.baseUrl()).getHost();
        } catch (Exception ignored) {
            // The default is fine for a message.
        }
        return MapSource.explain(e, host);
    }
}
