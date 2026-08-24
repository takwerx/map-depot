package com.atakmap.android.mapdepot;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;

import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Reads the catalog and region manifests from the depot.
 *
 * The catalog is cached to disk the moment it parses, and the cache is served
 * whenever the network is not there. An operator opening this plugin on a hilltop
 * with no signal should still see what they already downloaded, and what a region
 * would cost if they had signal, rather than an error where the list should be.
 */
public final class DepotClient {

    public static final String TAG = "MapDepotClient";

    /**
     * Where the depot lives. A preference, not a constant, because moving hosts
     * or adding a mirror must never require a plugin release: once tak.gov signs
     * a build, whatever is compiled in is the address every device uses.
     */
    public static final String PREF_BASE_URL = "mapdepot.baseUrl";

    public static final String DEFAULT_BASE_URL =
            "https://mapdepot.takwerx.org";

    private static final String CATALOG = "catalog.json";

    private static final int CONNECT_TIMEOUT = 20000;
    private static final int READ_TIMEOUT = 60000;

    /** A catalog far larger than this is not a catalog. */
    private static final int MAX_DOCUMENT = 8 * 1024 * 1024;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final File cacheDir;

    public DepotClient(File cacheDir) {
        this.cacheDir = cacheDir;
    }

    public void shutdown() {
        worker.shutdownNow();
    }

    public interface CatalogCallback {
        /** {@code cached} is true when the network failed and disk was used. */
        void onCatalog(List<Depot.Region> regions, boolean cached);

        void onError(String message);
    }

    public interface BaseMapCallback {
        void onBaseMaps(List<Depot.BaseMap> maps);

        void onError(String message);
    }

    public interface ForestCallback {
        /** Both package kinds come from the one catalog document, so both arrive together. */
        void onForests(List<Depot.Forest> forests, List<Depot.RecMap> recMaps);

        void onError(String message);
    }

    public interface ManifestCallback {
        void onManifest(Depot.Manifest manifest);

        void onError(String message);
    }

    public static String baseUrl() {
        final MapView mv = MapView.getMapView();
        if (mv == null)
            return DEFAULT_BASE_URL;

        final SharedPreferences prefs = PreferenceManager
                .getDefaultSharedPreferences(mv.getContext());
        final String v = prefs.getString(PREF_BASE_URL, DEFAULT_BASE_URL);
        return (v == null || v.trim().isEmpty()) ? DEFAULT_BASE_URL : v.trim();
    }

    // ---------------------------------------------------------------- catalog

    public void fetchCatalog(final CatalogCallback cb) {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                final File cache = new File(cacheDir, CATALOG);
                try {
                    final String body = get(baseUrl() + "/" + CATALOG);
                    final List<Depot.Region> regions = Depot.parseCatalog(body);

                    // Only cache what parsed. Caching the raw response would let a
                    // truncated or error body poison every later offline open.
                    write(cache, body);

                    post(cb, regions, false);
                } catch (final Exception e) {
                    Log.w(TAG, "catalog fetch failed: " + describe(e));
                    try {
                        final String body = read(cache);
                        post(cb, Depot.parseCatalog(body), true);
                    } catch (Exception noCache) {
                        postError(cb, describe(e));
                    }
                }
            }
        });
    }

    /**
     * Base maps live in the same catalog document the region list came from, but
     * this fetches it rather than assuming a previous call left a cache behind.
     * Reading only the cache meant an operator who opened Base Maps first -- never
     * touching Elevation -- got an empty list, because nothing had populated it.
     */
    public void fetchBaseMaps(final BaseMapCallback cb) {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                final File cache = new File(cacheDir, CATALOG);
                try {
                    final String body = get(baseUrl() + "/" + CATALOG);
                    final List<Depot.BaseMap> maps = Depot.parseBaseMaps(body);
                    write(cache, body);
                    postMaps(cb, maps);
                } catch (final Exception e) {
                    Log.w(TAG, "base map fetch failed: " + describe(e));
                    try {
                        postMaps(cb, Depot.parseBaseMaps(read(cache)));
                    } catch (Exception noCache) {
                        final String msg = describe(e);
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                cb.onError(msg);
                            }
                        });
                    }
                }
            }
        });
    }

    private void postMaps(final BaseMapCallback cb,
            final List<Depot.BaseMap> maps) {
        main.post(new Runnable() {
            @Override
            public void run() {
                cb.onBaseMaps(maps);
            }
        });
    }

    /**
     * Forest packages are listed in the same catalog document as everything else,
     * fetched here rather than read from cache for the same reason base maps are:
     * whichever section the operator opens first must work on its own.
     */
    public void fetchForests(final ForestCallback cb) {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                final File cache = new File(cacheDir, CATALOG);
                try {
                    final String body = get(baseUrl() + "/" + CATALOG);
                    write(cache, body);
                    postForests(cb, Depot.parseForests(body),
                            Depot.parseRecMaps(body));
                } catch (final Exception e) {
                    Log.w(TAG, "package fetch failed: " + describe(e));
                    try {
                        final String cached = read(cache);
                        postForests(cb, Depot.parseForests(cached),
                                Depot.parseRecMaps(cached));
                    } catch (Exception noCache) {
                        final String msg = describe(e);
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                cb.onError(msg);
                            }
                        });
                    }
                }
            }
        });
    }

    private void postForests(final ForestCallback cb,
            final List<Depot.Forest> forests, final List<Depot.RecMap> recMaps) {
        main.post(new Runnable() {
            @Override
            public void run() {
                cb.onForests(forests, recMaps);
            }
        });
    }

    public void fetchManifest(final Depot.Region region,
            final ManifestCallback cb) {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                final File cache = new File(cacheDir, "manifest-"
                        + region.id + ".json");
                try {
                    final String body = get(baseUrl() + "/" + region.manifestPath);
                    final Depot.Manifest m = Depot.parseManifest(body);
                    write(cache, body);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            cb.onManifest(m);
                        }
                    });
                } catch (final Exception e) {
                    try {
                        final Depot.Manifest m = Depot.parseManifest(read(cache));
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                cb.onManifest(m);
                            }
                        });
                    } catch (Exception noCache) {
                        final String msg = describe(e);
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                cb.onError(msg);
                            }
                        });
                    }
                }
            }
        });
    }

    // ------------------------------------------------------------------ plumbing

    private void post(final CatalogCallback cb, final List<Depot.Region> regions,
            final boolean cached) {
        main.post(new Runnable() {
            @Override
            public void run() {
                cb.onCatalog(regions, cached);
            }
        });
    }

    private void postError(final CatalogCallback cb, final String msg) {
        main.post(new Runnable() {
            @Override
            public void run() {
                cb.onError(msg);
            }
        });
    }

    private static String get(String url) throws Exception {
        final URL u = new URL(url);
        if (!"https".equalsIgnoreCase(u.getProtocol()))
            throw new IllegalStateException("depot must be https");

        final HttpURLConnection conn = (HttpURLConnection) u.openConnection();
        try {
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);

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
                    if (out.size() > MAX_DOCUMENT)
                        throw new IllegalStateException("document too large");
                }
            }
            return out.toString("UTF-8");
        } finally {
            conn.disconnect();
        }
    }

    private static void write(File f, String body) {
        try {
            final File parent = f.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs())
                return;
            try (OutputStream out = new FileOutputStream(f)) {
                out.write(body.getBytes("UTF-8"));
            }
        } catch (Exception e) {
            // A cache that cannot be written is a smaller problem than a crash.
            Log.w(TAG, "could not cache " + f.getName() + ": " + describe(e));
        }
    }

    private static String read(File f) throws Exception {
        final java.io.ByteArrayOutputStream out =
                new java.io.ByteArrayOutputStream();
        final byte[] buf = new byte[8192];
        try (InputStream in = new java.io.FileInputStream(f)) {
            int n;
            while ((n = in.read(buf)) > 0)
                out.write(buf, 0, n);
        }
        return out.toString("UTF-8");
    }

    private static String describe(Exception e) {
        final String m = e.getMessage();
        return m != null && !m.isEmpty() ? m : e.getClass().getSimpleName();
    }
}
