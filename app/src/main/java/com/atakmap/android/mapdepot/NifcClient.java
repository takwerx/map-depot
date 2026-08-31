
package com.atakmap.android.mapdepot;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;

import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the NIFC incident map postings.
 *
 * Despite the name every fire person calls it by, this is not FTP: it is an
 * Apache {@code mod_autoindex} listing over HTTPS. That matters, because real
 * FTP has no platform support on Android and would mean vendoring Apache Commons
 * Net, a third-party security review and its own proguard rules. Instead this is
 * the same {@code HttpURLConnection} the rest of the plugin already uses, plus a
 * regex over {@code href="..."}.
 *
 * <h3>The tree is walked, never assumed</h3>
 *
 * The eleven GACCs do not agree on a layout. Checked 2026-08-28:
 *
 * <pre>
 * most GACCs           alaska/2026/2026_Mukluk/
 * pacific_nw           pacific_nw/2026_Incidents_Oregon/2026_RoweCreekComplex/
 * southern             southern/2026/ holds incidents AND a Florida/ folder
 * california_statewide no year folder at all
 * </pre>
 *
 * Every one of them also carries folders that are not fires -- {@code Fuels/},
 * {@code Aviation/}, {@code Reference_Maps/}, {@code Training/}, state names. So
 * the plugin browses the directory tree as it finds it rather than modelling it,
 * and {@link #looksLikeIncident} is only ever used to work out which folder to
 * name a download after.
 *
 * <h3>The listing's sizes are approximate and the integrity check is not</h3>
 *
 * Apache prints {@code 4.6M} and {@code 817K}, which is right for showing an
 * operator what a download will cost and useless as a checksum.
 * {@link PackageInstaller} compares what it received against
 * {@link Depot.Package#bytes()} and fails the install on a mismatch, so a
 * rounded size there would fail every download. {@link #exactSize} asks the
 * server for the real {@code Content-Length} first.
 */
public final class NifcClient implements MapSource {

    public static final String TAG = "MapDepotNifc";

    /**
     * A preference rather than a constant, for the same reason the depot's own
     * base URL is one: once tak.gov signs a build, whatever is compiled in is
     * the address every device in the fleet uses.
     */
    public static final String PREF_ROOT = "mapdepot.nifcRoot";

    public static final String DEFAULT_ROOT =
            "https://ftp.wildfire.gov/public/incident_specific_maps/";

    private static final int CONNECT_TIMEOUT = 20000;
    private static final int READ_TIMEOUT = 60000;

    /** A directory listing far larger than this is not a directory listing. */
    private static final int MAX_LISTING = 4 * 1024 * 1024;

    /**
     * One row of the table. The name column is a link whose text is the decoded
     * name and whose href is the encoded one; both are kept, because the href is
     * what the next request has to be built from and the name is what the
     * operator reads.
     */
    private static final Pattern ROW = Pattern.compile(
            "<a href=\"([^\"]+)\">([^<]*)</a>\\s*</td>"
                    + "\\s*<td[^>]*>\\s*([^<]*?)\\s*</td>"
                    + "\\s*<td[^>]*>\\s*([^<]*?)\\s*</td>");

    /** {@code 4.6M}, {@code 817K}, {@code 12M}, or {@code -} for a directory. */
    private static final Pattern SIZE = Pattern
            .compile("^([0-9]+(?:\\.[0-9]+)?)([KMGT])?$");

    /** A fire's folder is year-prefixed: {@code 2026_RoweCreekComplex}. */
    private static final Pattern YEAR_PREFIXED = Pattern.compile("^\\d{4}[_\\-].+");

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    public String id() {
        return "nifc";
    }

    @Override
    public String label() {
        return "NIFC FTP";
    }

    @Override
    public void shutdown() {
        worker.shutdownNow();
    }

    // ----------------------------------------------------------------- model

    // ------------------------------------------------------------------- API

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
     * @param path encoded path relative to the root, empty for the top, always
     *        ending in {@code /}
     */
    @Override
    public void list(final String path, final MapSource.ListingCallback cb) {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final String body = get(root() + path);
                    final List<MapSource.Entry> all = parse(body);

                    final List<MapSource.Entry> keep = new ArrayList<>();
                    int hidden = 0;
                    for (final MapSource.Entry e : all) {
                        if (e.directory || e.isMap())
                            keep.add(e);
                        else
                            hidden++;
                    }
                    postListing(cb, path, keep, hidden);
                } catch (final Exception e) {
                    Log.w(TAG, "listing failed for " + path + ": " + describe(e));
                    postError(cb, describe(e));
                }
            }
        });
    }

    /** Apache writes hrefs relative to the folder being listed. */
    @Override
    public String childPath(String parentPath, MapSource.Entry child) {
        return (parentPath == null ? "" : parentPath) + child.href;
    }

    /**
     * The real content length, which the listing does not give.
     *
     * A server that will not say is not an error: zero tells
     * {@link PackageInstaller} to skip both the free-space estimate and the
     * length check rather than fail a download over a missing header.
     */
    @Override
    public void exactSize(final MapSource.Posting posting,
            final MapSource.SizeCallback cb) {
        final String url = posting.url();
        worker.execute(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    final URL u = new URL(url);
                    requireHttps(u);
                    conn = (HttpURLConnection) u.openConnection();
                    conn.setRequestMethod("HEAD");
                    conn.setConnectTimeout(CONNECT_TIMEOUT);
                    conn.setReadTimeout(READ_TIMEOUT);

                    final int code = conn.getResponseCode();
                    if (code != HttpURLConnection.HTTP_OK)
                        throw new IllegalStateException("HTTP " + code);

                    final long len = contentLength(conn);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            cb.onSize(Math.max(0L, len));
                        }
                    });
                } catch (final Exception e) {
                    final String msg = describe(e);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            cb.onError(msg);
                        }
                    });
                } finally {
                    if (conn != null)
                        conn.disconnect();
                }
            }
        });
    }

    /**
     * Whether this folder is a fire rather than a container of fires.
     *
     * The obvious rule -- a year prefix -- is wrong on its own, and wrongly in a
     * way that is easy to miss: {@code 2026_Incidents_Oregon} is year-prefixed
     * and holds fifty fires. Naming every Oregon map after it produces fifty
     * files called {@code INCIDENTS-OREGON-...}. So a fire is a year-prefixed
     * folder whose children are not themselves year-prefixed.
     *
     * @param children the folder's own entries, already listed
     */
    public static boolean looksLikeIncident(String name, List<MapSource.Entry> children) {
        if (name == null || !YEAR_PREFIXED.matcher(strip(name)).matches())
            return false;
        if (children == null)
            return true;
        for (final MapSource.Entry c : children) {
            if (c.directory && YEAR_PREFIXED.matcher(strip(c.name)).matches())
                return false;
        }
        return true;
    }

    /** The deepest year-prefixed segment of a path, which is the fire. */
    public static String incidentFolderOf(String decodedPath) {
        if (decodedPath == null)
            return null;
        String found = null;
        for (final String seg : decodedPath.split("/")) {
            if (!seg.isEmpty() && YEAR_PREFIXED.matcher(seg).matches())
                found = seg;
        }
        return found;
    }

    /** The path below the incident, which is where a date sometimes hides. */
    public static String folderBelowIncident(String decodedPath) {
        final String incident = incidentFolderOf(decodedPath);
        if (incident == null || decodedPath == null)
            return decodedPath;
        final int at = decodedPath.indexOf(incident + "/");
        if (at < 0)
            return decodedPath;
        return decodedPath.substring(at + incident.length() + 1);
    }

    // --------------------------------------------------------------- parsing

    /**
     * Apache writes one table row per entry. Rows whose href leaves this tree --
     * the Parent Directory link, the column sort links, anything absolute -- are
     * dropped rather than followed, so a browse can never walk off the server it
     * was pointed at.
     */
    static List<MapSource.Entry> parse(String body) {
        final List<MapSource.Entry> dirs = new ArrayList<>();
        final List<MapSource.Entry> files = new ArrayList<>();

        final Matcher m = ROW.matcher(body);
        while (m.find()) {
            final String href = m.group(1);
            final String text = m.group(2).trim();
            final String modified = m.group(3).trim();
            final String size = m.group(4).trim();

            if (!safeHref(href))
                continue;

            final boolean dir = href.endsWith("/");
            // decode() turns %2F back into a separator, so the check has to
            // happen after it, and has to be for a name rather than a path.
            final String name = strip(decode(text.isEmpty() ? href : text));
            if (!MapSource.safeName(name))
                continue;

            final MapSource.Entry e = new MapSource.Entry(name, href, dir,
                    modified, dir ? 0L : parseSize(size), "");
            if (dir)
                dirs.add(e);
            else
                files.add(e);
        }

        final List<MapSource.Entry> out = new ArrayList<>(dirs.size() + files.size());
        out.addAll(dirs);
        out.addAll(files);
        return out;
    }

    /**
     * Only a plain relative link is followed. An absolute path would climb out of
     * the incident tree, a scheme would leave the host entirely, and {@code ..}
     * would do either quietly.
     */
    private static boolean safeHref(String href) {
        if (href == null || href.isEmpty())
            return false;
        if (href.startsWith("/") || href.startsWith("?") || href.startsWith("#"))
            return false;
        if (href.contains("://") || href.contains(".."))
            return false;
        return true;
    }

    /** {@code 4.6M} to bytes. Approximate by construction -- Apache rounds. */
    static long parseSize(String s) {
        if (s == null)
            return 0L;
        final Matcher m = SIZE.matcher(s.trim());
        if (!m.matches())
            return 0L;
        try {
            final double n = Double.parseDouble(m.group(1));
            final String unit = m.group(2);
            long mult = 1L;
            if ("K".equals(unit))
                mult = 1024L;
            else if ("M".equals(unit))
                mult = 1024L * 1024L;
            else if ("G".equals(unit))
                mult = 1024L * 1024L * 1024L;
            else if ("T".equals(unit))
                mult = 1024L * 1024L * 1024L * 1024L;
            return (long) (n * mult);
        } catch (NumberFormatException notASize) {
            return 0L;
        }
    }

    private static String strip(String s) {
        String t = s == null ? "" : s.trim();
        while (t.endsWith("/"))
            t = t.substring(0, t.length() - 1);
        return t;
    }

    private static String decode(String s) {
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Exception notEncoded) {
            return s;
        }
    }

    // -------------------------------------------------------------- plumbing

    private static void requireHttps(URL u) {
        if (!"https".equalsIgnoreCase(u.getProtocol()))
            throw new IllegalStateException("NIFC must be https");
    }

    /**
     * {@code getContentLengthLong} is API 24 and the plugin builds below that in
     * places, so the header is read directly rather than through it.
     */
    private static long contentLength(HttpURLConnection conn) {
        final String v = conn.getHeaderField("Content-Length");
        if (v == null)
            return 0L;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException notALength) {
            return 0L;
        }
    }

    private static String get(String url) throws Exception {
        final URL u = new URL(url);
        requireHttps(u);

        final HttpURLConnection conn = (HttpURLConnection) u.openConnection();
        try {
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setInstanceFollowRedirects(true);

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

    private void postListing(final MapSource.ListingCallback cb, final String path,
            final List<MapSource.Entry> entries, final int hidden) {
        main.post(new Runnable() {
            @Override
            public void run() {
                cb.onListing(path, entries, hidden);
            }
        });
    }

    private void postError(final MapSource.ListingCallback cb, final String msg) {
        main.post(new Runnable() {
            @Override
            public void run() {
                cb.onError(msg);
            }
        });
    }

    private static String describe(Exception e) {
        return MapSource.explain(e, "the NIFC server");
    }

    // -------------------------------------------------------------- postings

    /**
     * Builds the postings for one listing in one go.
     *
     * Batched deliberately: {@link NifcNaming#translateAll} can only guarantee
     * that two files do not land on the same name if it sees the whole folder at
     * once. Translating one file at a time silently loses maps -- one IR date
     * carries ten PDFs that differ only by area and rendering.
     */
    @Override
    public List<MapSource.Posting> postingsFor(List<MapSource.Entry> entries,
            String basePath, String decodedPath) {

        final List<String> names = new ArrayList<>();
        final Map<String, String> modified = new LinkedHashMap<>();
        final Map<String, MapSource.Entry> byName = new LinkedHashMap<>();
        for (final MapSource.Entry e : entries) {
            if (!e.isMap())
                continue;
            names.add(e.name);
            modified.put(e.name, e.modified);
            byName.put(e.name, e);
        }

        final String incident = incidentFolderOf(decodedPath);
        final String below = folderBelowIncident(decodedPath);
        final Map<String, String> translated = NifcNaming.translateAll(
                names, incident, below, modified);

        final List<MapSource.Posting> out = new ArrayList<>();
        for (final Map.Entry<String, String> t : translated.entrySet()) {
            final MapSource.Entry e = byName.get(t.getKey());
            if (e == null)
                continue;
            // Zero, not the listing's figure. Apache rounds -- "6.1M" for a
            // 6,146,536-byte file -- and PackageInstaller treats a size that
            // does not match the file on disk as a different file, so carrying
            // the rounded number here made every already-downloaded NIFC map
            // read as not installed. The real length is fetched with a HEAD
            // before the download, which is where an integrity check belongs.
            // The approximate size still reaches the operator, in the row text.
            out.add(new MapSource.Posting(root() + basePath + e.href, e.name,
                    t.getValue(), 0L,
                    "~" + Depot.bytes(e.bytes) + "  ·  " + e.name));
        }
        return out;
    }
}
