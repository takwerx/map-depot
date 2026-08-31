package com.atakmap.android.mapdepot;

import android.os.Handler;
import android.os.Looper;

import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Downloads a large map package into ATAK's imagery folder -- a Forest Service
 * vector basemap, or a ranger district GeoPDF.
 *
 * These are big -- 55 MB for a grassland, 1.4 GB for George Washington and
 * Jefferson -- so unlike a map source XML this reports progress, checks free
 * space before it starts, and can be cancelled. It downloads to a staging file
 * and moves the result into place, so ATAK's imagery scanner never sees a
 * half-written file and registers a broken layer.
 *
 * There is no checksum to verify against: neither ArcGIS Online nor the Forest
 * Service gateway publishes a digest. The length the server declares is the only
 * integrity check available, so a truncated transfer is caught and a corrupted
 * one is not.
 */
public final class PackageInstaller {

    public static final String TAG = "MapDepotPackages";

    private static final int CONNECT_TIMEOUT = 30000;
    private static final int READ_TIMEOUT = 60000;

    /**
     * Raised when the operator cancels. Named so the caller can tell it
     * apart from a failure: cancelling is something they did, not
     * something that went wrong, and reporting it as "failed" reads like
     * the plugin broke.
     */
    public static final String CANCELLED = "cancelled";

    /** Leave room for the move as well as the download itself. */
    private static final double FREE_SPACE_HEADROOM = 1.15;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    /**
     * Shared by goTo, which is static because a row tap is not tied to any
     * one download. One thread is enough: only one row can be tapped at a
     * time, and a second tap should replace the wait, not race it.
     */
    private static final ExecutorService WORKER =
            Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public interface Callback {
        void onProgress(Depot.Package pkg, long done, long total);

        void onInstalled(Depot.Package pkg, File dest);

        void onRemoved(Depot.Package pkg);

        void onError(Depot.Package pkg, String message);
    }

    public void cancel() {
        cancelled.set(true);
    }

    public void shutdown() {
        cancelled.set(true);
        worker.shutdownNow();
    }

    /**
     * {@code <atak>/imagery} -- the directory ATAK's ImageryScanner walks. A
     * package here is found by extension, so nothing else has to be told about it.
     */
    /** Where this package belongs, which is what tells ATAK what it is. */
    public static File dirFor(Depot.Package pkg) {
        return FileSystemUtils.getItem(pkg.destination());
    }

    /** Staging lives outside the scanned tree so a partial file is never seen. */
    private static File stagingDir() {
        return new File(FileSystemUtils.getItem(FileSystemUtils.TMP_DIRECTORY),
                "mapdepot-packages");
    }

    /**
     * Whether this ATAK can display a vector tile package at all.
     *
     * ATAK 5.6 cannot: {@code CompactCacheDatasetDescriptorSpi} and {@code VTPK}
     * arrived in 5.7. On 5.6 a forest basemap downloads, verifies and installs
     * perfectly, and then nothing happens -- no dataset, no entry in any layer
     * list, and "tap to go there" waiting for something that will never appear.
     * That is what it did to the first person outside this room who tried it.
     *
     * Checked by name rather than by referencing the class, because the 5.6
     * build compiles against a 5.6 SDK where it does not exist.
     */
    private static Boolean vtpkSupport;

    public static synchronized boolean supportsVectorPackages() {
        if (vtpkSupport == null) {
            boolean ok;
            try {
                Class.forName("com.atakmap.map.formats.esri.VTPK");
                ok = true;
            } catch (Throwable notThisBuild) {
                ok = false;
            }
            vtpkSupport = ok;
            Log.i(TAG, "vector tile packages supported: " + ok);
        }
        return vtpkSupport;
    }

    public static boolean isInstalled(Depot.Package pkg) {
        return held(pkg) != null;
    }

    /**
     * The file holding this package, under either its current or its former
     * name, or null. Earlier builds named these after the catalog id, and an
     * operator who already spent 200 MB should not spend it again because the
     * label got tidier.
     */
    private static File held(Depot.Package pkg) {
        final File dir = dirFor(pkg);
        for (String candidate : new String[] {
                pkg.fileName(), pkg.legacyFileName() }) {
            final File f = new File(dir, candidate);
            // Guarded for the same reason fetch() guards its destination, and
            // it matters more here: this file gets deleted. A name carrying a
            // separator would otherwise resolve outside the directory, and
            // because a listing without an exact size skips the length check
            // below, any existing file there would match and be offered with a
            // Remove button.
            try {
                guardInside(dir, f);
            } catch (Exception escapes) {
                Log.w(TAG, "ignoring " + candidate + ": " + escapes);
                continue;
            }
            if (f.isFile() && (pkg.bytes() <= 0 || f.length() == pkg.bytes()))
                return f;
        }
        return null;
    }

    public void install(final Depot.Package pkg, final Callback cb) {
        cancelled.set(false);
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final File dest = fetch(pkg, cb);
                    postInstalled(cb, pkg, dest);
                } catch (final Exception e) {
                    Log.w(TAG, "install failed for " + pkg.id(), e);
                    postError(cb, pkg, describe(e));
                }
            }
        });
    }

    public void uninstall(final Depot.Package pkg, final Callback cb) {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                final File f = held(pkg);
                final boolean gone = f == null || retire(pkg, f);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (gone)
                            cb.onRemoved(pkg);
                        else
                            cb.onError(pkg, "could not delete " + pkg.fileName());
                    }
                });
            }
        });
    }

    private File fetch(Depot.Package pkg, Callback cb) throws Exception {
        final File dir = dirFor(pkg);
        if (!dir.exists() && !dir.mkdirs())
            throw new IllegalStateException("cannot create " + dir);

        final File staging = stagingDir();
        if (!staging.exists() && !staging.mkdirs())
            throw new IllegalStateException("cannot create " + staging);

        if (pkg.bytes() > 0) {
            final long free = dir.getUsableSpace();
            final long needed = (long) (pkg.bytes() * FREE_SPACE_HEADROOM);
            if (free < needed)
                throw new IllegalStateException("needs " + Depot.bytes(needed)
                        + ", only " + Depot.bytes(free) + " free");
        }

        final File part = new File(staging, pkg.fileName() + ".part");
        guardInside(staging, part);
        deleteQuietly(part);

        final long written;
        try {
            written = downloadWithRetries(pkg, part, cb);
        } catch (Exception failed) {
            // Cancelling threw and left a part file behind -- 41 MB of a
            // gigabyte package, invisible to the operator and never resumed,
            // because this downloader always starts fresh.
            deleteQuietly(part);
            throw failed;
        }

        // The declared length is the only integrity check on offer, so hold it.
        if (pkg.bytes() > 0 && written != pkg.bytes()) {
            deleteQuietly(part);
            throw new IllegalStateException("expected " + Depot.bytes(pkg.bytes())
                    + ", got " + Depot.bytes(written));
        }

        final File dest = new File(dir, pkg.fileName());
        guardInside(dir, dest);
        if (dest.exists() && !dest.delete())
            throw new IllegalStateException("cannot replace " + dest);
        if (!part.renameTo(dest)) {
            deleteQuietly(part);
            throw new IllegalStateException("cannot install " + dest);
        }

        // A copy under the previous naming would otherwise sit there as a second
        // layer of the same place, costing the operator the space twice.
        final File legacy = new File(dir, pkg.legacyFileName());
        if (!legacy.equals(dest) && legacy.isFile()) {
            Log.d(TAG, "removing superseded " + legacy.getName());
            deleteQuietly(legacy);
        }

        announce(pkg, dest);
        Log.d(TAG, "installed " + dest + " (" + dest.length() + " bytes)");
        return dest;
    }

    /**
     * Retries a download the host has temporarily refused.
     *
     * The forests come from ArcGIS Online, which answers a plain GET with a
     * redirect to a presigned S3 URL. Pull enough of them in an evening and it
     * starts replying {@code 204 No Content} instead -- no body, no
     * explanation, and nothing wrong with the request: the same URL served
     * from another address at the same moment. Treating that as a hard failure
     * turned a busy hour into "it just failed", so it is now waited out.
     *
     * Only for the codes that mean "not now": a 404 is not going to improve.
     */
    private static final int[] BACKOFF_MS = { 5000, 15000, 40000 };

    private long downloadWithRetries(Depot.Package pkg, File part, Callback cb)
            throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt <= BACKOFF_MS.length; attempt++) {
            if (attempt > 0) {
                final long wait = BACKOFF_MS[attempt - 1];
                Log.d(TAG, pkg.id() + ": retrying in " + (wait / 1000) + "s");
                Thread.sleep(wait);
                if (cancelled.get())
                    throw new IllegalStateException(CANCELLED);
            }
            try {
                return download(pkg, part, cb);
            } catch (RetryLater temporary) {
                deleteQuietly(part);
                last = temporary;
            }
        }
        throw last;
    }

    /** A refusal worth waiting out rather than reporting. */
    private static class RetryLater extends IllegalStateException {
        RetryLater(String message) {
            super(message);
        }
    }

    /**
     * Turns a status code into something worth showing an operator. "HTTP 204"
     * told them nothing and read like a bug in the plugin.
     */
    private static IllegalStateException explain(int code) {
        switch (code) {
            case 204:
                // Not "busy". The Forest Service gateway answers 204 No Content
                // when it holds nothing for the map and series asked for, so it
                // means the map is not there -- retrying spent a minute proving
                // that three times over.
                return new IllegalStateException("this map is not available");
            case 429:
            case 503:
                return new RetryLater("the map server is busy — waiting");
            case 403:
                return new IllegalStateException("the map server refused (403)");
            case 404:
                return new IllegalStateException("no longer published (404)");
            default:
                return code >= 500
                        ? new RetryLater("the map server is down (" + code + ")")
                        : new IllegalStateException("HTTP " + code);
        }
    }

    /** {@code Content-Range: bytes 0-1023/4096} to 4096, or 0 if it is absent. */
    private static long totalFromContentRange(HttpURLConnection conn) {
        final String v = conn.getHeaderField("Content-Range");
        if (v == null)
            return 0L;
        final int slash = v.lastIndexOf('/');
        if (slash < 0 || slash + 1 >= v.length())
            return 0L;
        try {
            return Long.parseLong(v.substring(slash + 1).trim());
        } catch (NumberFormatException unknown) {
            return 0L;
        }
    }

    private long download(Depot.Package pkg, File part, Callback cb)
            throws Exception {
        final URL u = new URL(pkg.url());
        if (!"https".equalsIgnoreCase(u.getProtocol()))
            throw new IllegalStateException("package source must be https");

        HttpURLConnection conn = (HttpURLConnection) u.openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);

        try {
            final int code = conn.getResponseCode();
            // 206 is Partial Content and it is a success: a body arrives and the
            // length check below still has to pass, so a genuinely short read is
            // caught there rather than here. Some CDNs answer 206 to a plain GET,
            // and treating it as a failure refused a download that had worked.
            if (code != HttpURLConnection.HTTP_OK
                    && code != HttpURLConnection.HTTP_PARTIAL) {
                // Everything about the response, because a bare code has not
                // been enough to explain the 204s seen in the field: the same
                // URL serves 200 with the right length from a desktop JVM.
                Log.w(TAG, pkg.id() + ": HTTP " + code + " " + conn.getResponseMessage()
                        + " from " + conn.getURL()
                        + " (type=" + conn.getContentType()
                        + " length=" + conn.getContentLength()
                        + " server=" + conn.getHeaderField("server")
                        + " via=" + conn.getHeaderField("via") + ")");
                // `throw new IllegalStateException(explain(code))` would hit the
                // Throwable-cause constructor: the message becomes the cause's
                // toString(), which is how a class name reached the operator's
                // screen, and the thrown object stops being a RetryLater -- so
                // downloadWithRetries never caught it and the retries this was
                // all written for never ran once.
                throw explain(code);
            }

            // On a 206 the Content-Length describes the range, not the file, so
            // it would show progress against the wrong total. The package's own
            // size is preferred anyway; this only matters when it has none.
            final long total = pkg.bytes() > 0
                    ? pkg.bytes()
                    : (code == HttpURLConnection.HTTP_PARTIAL
                            ? totalFromContentRange(conn)
                            : conn.getContentLength());

            long done = 0;
            long lastPost = 0;
            final byte[] buf = new byte[64 * 1024];
            try (InputStream in = conn.getInputStream();
                    OutputStream out = new FileOutputStream(part)) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (cancelled.get())
                        throw new IllegalStateException(CANCELLED);
                    out.write(buf, 0, n);
                    done += n;

                    // Once per megabyte. A callback per 64 KB buffer would post
                    // thousands of runnables to the main thread and stutter the map.
                    if (done - lastPost >= 1024L * 1024L) {
                        lastPost = done;
                        postProgress(cb, pkg, done, total);
                    }
                }
            }
            return done;
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Goes to an installed package: the same thing tapping it in ATAK's own
     * Image Overlay list does.
     *
     * Asks the layer, and nothing else. {@code RasterLayer2.getSelectionOptions}
     * is answered by querying the layer's data store there and then -- it is
     * not a cache -- so a layer that offers the name can show it, and the
     * question needs no scan timing, no refresh, and no broadcast.
     *
     * Everything else tried tonight was a way of asking the wrong thing.
     * {@code SELECT_LAYER} goes through an adapter whose list is rebuilt on a
     * worker thread with no completion signal.
     * {@code LocalRasterDataStore.contains} is true from the moment the scan
     * opens the file. {@code getDatasetNames()} reads the database, which has
     * the row before the layers are told. And all three ask the *layers*
     * database, which a GeoPDF never enters: a GRG lives in GRGMapComponent's
     * own store, so a district map could not be found by any of them.
     *
     * Both stacks are searched, because the two kinds land in different
     * places -- a {@code .vtpk} on the mobile raster layer in MAP_LAYERS, a
     * GRG on "GRG rasters" in RASTER_OVERLAYS -- and both are polled until the
     * scan or import that is bringing the file in has finished.
     */
    public interface GoTo {
        /** Registered already; the map is moving. */
        void onGoing(Depot.Package pkg);

        /** Not registered yet -- ATAK is still scanning it in. */
        void onWaiting(Depot.Package pkg);

        /** Gave up, or this build has no layer database to ask. */
        void onUnavailable(Depot.Package pkg, String why);
    }

    /** How long to wait for a scan before admitting it is not coming. */
    private static final long GOTO_TIMEOUT_MS = 180000L;
    private static final long GOTO_POLL_MS = 1000L;

    public static void goTo(final Depot.Package pkg, final GoTo cb) {
        final File f = held(pkg);
        if (f == null) {
            post(cb, pkg, "unavailable", "it is not installed");
            return;
        }

        // A KMZ is not a raster dataset, so the layer-stack search below cannot
        // find it however long it polls. ATAK has a general answer for this:
        // ZOOM_TO_FILE_ACTION hands a path to URIContentManager, which finds
        // whichever handler owns that file and asks it to go there. It works for
        // anything ATAK has a handler for, and the KMZ is what needs it.
        //
        // The rasters keep the search below, which is proven and reports the
        // "still scanning" case the operator can act on.
        if (!"grg".equals(pkg.destination())) {
            try {
                final android.content.Intent i = new android.content.Intent(
                        "com.atakmap.android.importexport.ZOOM_TO_FILE_ACTION");
                i.putExtra("filepath", f.getAbsolutePath());
                com.atakmap.android.ipc.AtakBroadcast.getInstance()
                        .sendBroadcast(i);
                post(cb, pkg, "going", null);
            } catch (LinkageError | RuntimeException notThisBuild) {
                Log.w(TAG, "could not go to " + f.getName() + ": " + notThisBuild);
                post(cb, pkg, "unavailable", "this build cannot go there");
            }
            return;
        }

        final boolean overlay = "grg".equals(pkg.destination());
        final String name = f.getName();

        WORKER.execute(new Runnable() {
            @Override
            public void run() {
                if (selectOnMain(name, overlay)) {
                    post(cb, pkg, "going", null);
                    return;
                }

                // Not there yet: ATAK is still scanning or importing it.
                post(cb, pkg, "waiting", null);
                Log.d(TAG, "waiting for ATAK to register " + name
                        + " (up to " + (GOTO_TIMEOUT_MS / 1000) + "s)");
                refreshLayers();
                polling = true;

                final long deadline = System.currentTimeMillis() + GOTO_TIMEOUT_MS;
                while (System.currentTimeMillis() < deadline) {
                    try {
                        Thread.sleep(GOTO_POLL_MS);
                    } catch (InterruptedException stop) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (selectOnMain(name, overlay)) {
                        polling = false;
                        post(cb, pkg, "going", null);
                        return;
                    }
                }
                polling = false;
                Log.w(TAG, "gave up waiting for ATAK to register " + name);
                post(cb, pkg, "unavailable", "ATAK never added it");
            }
        });
    }

    /**
     * Runs the selection on the UI thread and waits for its answer. MapView
     * work belongs there, and the caller is a worker, so blocking is safe.
     */
    private static boolean selectOnMain(final String name, final boolean overlay) {
        final java.util.concurrent.atomic.AtomicBoolean done =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        final java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    done.set(selectDirect(name, overlay));
                } finally {
                    latch.countDown();
                }
            }
        });
        try {
            latch.await(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException stop) {
            Thread.currentThread().interrupt();
        }
        return done.get();
    }

    /**
     * Makes ATAK's raster layers pick up what the scan has added.
     *
     * This is the step that was missing, and the reason a freshly downloaded
     * package could be in the layer database and still not selectable. The
     * scan writes the dataset row, but the layers hold their own cached view
     * and only rebuild it when the store announces a change -- and the one
     * announcement a scan makes comes from the {@code refresh()} at its
     * *start*, before the new dataset exists. Nothing announces the end.
     *
     * {@code PersistentRasterDataStore.refresh()} clears those cached layer
     * references and dispatches the change itself, which is what switching a
     * map by hand ended up causing. It is {@code synchronized} and the running
     * scan holds the lock, so calling it also waits for that scan to finish --
     * which is why this must never run on the UI thread.
     */
    private static void refreshLayers() {
        try {
            final com.atakmap.map.layer.raster.LocalRasterDataStore db =
                    com.atakmap.android.layers.LayersMapComponent.getLayersDatabase();
            if (db != null)
                db.refresh();
        } catch (LinkageError | RuntimeException notThisBuild) {
            Log.w(TAG, "could not refresh the layer store: " + notThisBuild);
        }
    }

    /**
     * Selects the package on ATAK's raster layer directly, without the
     * broadcast.
     *
     * The broadcast route asks {@code LayersManagerBroadcastReceiver} to
     * resolve a name through a {@code LayerSelectionAdapter}, whose list is a
     * front buffer rebuilt on its own worker thread. Nothing says when that
     * rebuild has happened, so every attempt to use it amounted to guessing at
     * a delay -- and the guesses were wrong, in both directions, for hours.
     *
     * The layer underneath has no such buffer. {@code Layers.findLayers} walks
     * the map's layer tree, {@code RasterLayer2.getSelectionOptions} says what
     * that layer can actually show right now, and {@code setSelection} names
     * it. If the layer offers the name, the selection cannot fail to take.
     *
     * A {@code .vtpk} registers as {@code compactcache}, which
     * {@code CompactCacheDatasetDescriptorSpi} hands to
     * {@code MobileImageryRasterLayer2}; that layer names its selections from
     * imagery types, and the imagery type of one of these is the file name.
     * So the name asked for here is the name the layer knows it by.
     *
     * Returns false when no layer offers the name -- ATAK may still be
     * scanning, or the raster stack may be showing a different card -- and the
     * caller falls back to the broadcast.
     */
    /** True while the caller is polling, so the per-layer log stays quiet. */
    private static volatile boolean polling = false;

    private static boolean selectDirect(String name, boolean overlay) {
        try {
            final com.atakmap.android.maps.MapView mv =
                    com.atakmap.android.maps.MapView.getMapView();
            if (mv == null)
                return false;

            // Both stacks that can hold one of these. A .vtpk lands on the
            // mobile raster layer in MAP_LAYERS; a GeoPDF becomes a GRG, and
            // GRGMapComponent puts its "GRG rasters" layer in RASTER_OVERLAYS.
            // Searching only MAP_LAYERS is why a district map never went
            // anywhere while a forest did.
            final java.util.List<com.atakmap.map.layer.Layer> roots =
                    new java.util.ArrayList<>();
            roots.addAll(mv.getLayers(com.atakmap.android.maps.MapView
                    .RenderStack.MAP_LAYERS));
            roots.addAll(mv.getLayers(com.atakmap.android.maps.MapView
                    .RenderStack.RASTER_OVERLAYS));
            final java.util.List<com.atakmap.map.layer.Layer> found =
                    new java.util.ArrayList<>();
            com.atakmap.map.layer.Layers.findLayers(roots,
                    new com.atakmap.map.layer.LayerFilter() {
                        @Override
                        public boolean accept(com.atakmap.map.layer.Layer l) {
                            if (!(l instanceof com.atakmap.map.layer.raster.RasterLayer2))
                                return false;
                            final java.util.Collection<String> opts =
                                    ((com.atakmap.map.layer.raster.RasterLayer2) l)
                                            .getSelectionOptions();
                            final boolean hit = opts != null && opts.contains(name);
                            // Once, or when it succeeds. This runs once a second
                            // for as long as the wait lasts, and on a build that
                            // can never register the file it produced 3,534 lines
                            // in one session -- enough to bury everything else in
                            // a log someone was trying to read.
                            if (hit || !polling)
                                Log.d(TAG, "layer " + l.getName() + " offers "
                                        + (opts == null ? -1 : opts.size())
                                        + " selections, has ours: " + hit);
                            return hit;
                        }
                    }, found, 1);

            if (found.isEmpty()) {
                if (!polling)
                    Log.d(TAG, "no layer offers " + name + " yet");
                return false;
            }

            final com.atakmap.map.layer.raster.RasterLayer2 layer =
                    (com.atakmap.map.layer.raster.RasterLayer2) found.get(0);
            layer.setVisible(name, true);
            // A base map is chosen -- naming it replaces whatever was showing.
            // An overlay is not: GRGs stack, and several are normally on at
            // once, so selecting one would amount to turning the others off.
            if (!overlay)
                layer.setSelection(name);
            Log.d(TAG, (overlay ? "showed " : "selected ") + name
                    + " on " + layer.getName());

            zoomTo(mv, layer, name);
            return true;
        } catch (LinkageError | RuntimeException notThisBuild) {
            Log.w(TAG, "could not select directly: " + notThisBuild);
            return false;
        }
    }

    /** Fits the map to the selection's own coverage. */
    private static void zoomTo(com.atakmap.android.maps.MapView mv,
            com.atakmap.map.layer.raster.RasterLayer2 layer, String name) {
        final com.atakmap.map.layer.feature.geometry.Geometry g =
                layer.getGeometry(name);
        if (g == null)
            return;
        final com.atakmap.map.layer.feature.geometry.Envelope e = g.getEnvelope();
        com.atakmap.android.util.ATAKUtilities.scaleToFit(mv,
                new com.atakmap.coremap.maps.coords.GeoBounds(
                        e.minY, e.minX, e.maxY, e.maxX),
                mv.getWidth(), mv.getHeight());
    }

    private static void post(final GoTo cb, final Depot.Package pkg,
            final String what, final String why) {
        if (cb == null)
            return;
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                if ("going".equals(what))
                    cb.onGoing(pkg);
                else if ("waiting".equals(what))
                    cb.onWaiting(pkg);
                else
                    cb.onUnavailable(pkg, why);
            }
        });
    }

    /**
     * ATAK's GRG outlines layer -- the footprints drawn for every GRG on the
     * device, not for any one map.
     *
     * Found by name across the render stacks rather than by reaching for
     * GRGMapComponent, which a plugin has no handle on. GRGMapComponent calls it
     * "GRG Outlines" and puts it with the vector overlays.
     */
    private static final String OUTLINES_LAYER = "GRG Outlines";

    /**
     * ATAK's own preference for the same thing, kept in step so the state
     * survives a restart and matches what the overlay manager shows.
     */
    private static final String OUTLINES_PREF = "grgs.outlines-visible";

    private static com.atakmap.map.layer.Layer outlinesLayer() {
        final com.atakmap.android.maps.MapView mv =
                com.atakmap.android.maps.MapView.getMapView();
        if (mv == null)
            return null;
        try {
            for (com.atakmap.android.maps.MapView.RenderStack stack
                    : com.atakmap.android.maps.MapView.RenderStack.values()) {
                final com.atakmap.map.layer.Layer hit =
                        findLayer(mv.getLayers(stack), 0);
                if (hit != null)
                    return hit;
            }
            Log.w(TAG, "no layer named \"" + OUTLINES_LAYER + "\" is registered");
        } catch (LinkageError | RuntimeException notThisBuild) {
            Log.w(TAG, "no outlines layer on this build: " + notThisBuild);
        }
        return null;
    }

    /**
     * The outlines layer is not at the top of a stack. GRGMapComponent builds a
     * MultiLayer named "GRG", puts both the rasters and the outlines inside it,
     * and registers only the MultiLayer with RASTER_OVERLAYS -- so a flat scan
     * of the stacks finds the parent and never the child, and the control it
     * feeds simply never appears.
     */
    private static com.atakmap.map.layer.Layer findLayer(
            java.util.List<com.atakmap.map.layer.Layer> layers, int depth) {
        if (layers == null || depth > 4)
            return null;
        for (final com.atakmap.map.layer.Layer l : layers) {
            if (l == null)
                continue;
            if (OUTLINES_LAYER.equals(l.getName()))
                return l;
            if (l instanceof com.atakmap.map.layer.MultiLayer) {
                final com.atakmap.map.layer.Layer hit = findLayer(
                        ((com.atakmap.map.layer.MultiLayer) l).getLayers(),
                        depth + 1);
                if (hit != null)
                    return hit;
            }
        }
        return null;
    }

    /**
     * Whether GRG outlines are drawn, or null when this build has no such layer.
     *
     * Read from the preference rather than from the layer, because the layer is
     * always visible: the overlay manager hides the outlines by turning off the
     * feature sets *inside* it, not by turning the layer off. Asking the layer
     * therefore always answered "on", which is why the control did nothing and
     * never changed its label.
     */
    public static Boolean outlinesVisible() {
        if (outlinesLayer() == null)
            return null;
        final com.atakmap.android.maps.MapView mv =
                com.atakmap.android.maps.MapView.getMapView();
        if (mv == null)
            return null;
        return Boolean.valueOf(android.preference.PreferenceManager
                .getDefaultSharedPreferences(mv.getContext())
                .getBoolean(OUTLINES_PREF, true));
    }

    /**
     * Turns the footprints on or off, the way the overlay manager does it:
     * {@code setFeatureSetsVisible} across every feature set in the coverage
     * store, then ATAK's own preference so the state survives a restart and the
     * overlay manager's own toggle agrees with ours.
     */
    public static boolean setOutlinesVisible(boolean visible) {
        final com.atakmap.map.layer.Layer l = outlinesLayer();
        if (!(l instanceof com.atakmap.map.layer.feature.FeatureLayer3))
            return false;
        try {
            final com.atakmap.map.layer.feature.FeatureDataStore2 store =
                    ((com.atakmap.map.layer.feature.FeatureLayer3) l)
                            .getDataStore();
            if (store == null)
                return false;

            // No filter set on the parameters, so this is every feature set the
            // store holds -- one per GRG -- which is what the overlay manager's
            // own toggle does.
            store.setFeatureSetsVisible(
                    new com.atakmap.map.layer.feature.FeatureDataStore2
                            .FeatureSetQueryParameters(),
                    visible);

            final com.atakmap.android.maps.MapView mv =
                    com.atakmap.android.maps.MapView.getMapView();
            if (mv != null)
                android.preference.PreferenceManager
                        .getDefaultSharedPreferences(mv.getContext())
                        .edit().putBoolean(OUTLINES_PREF, visible).apply();
            return true;
        } catch (Exception notThisBuild) {
            Log.w(TAG, "could not set outlines: " + notThisBuild);
            return false;
        }
    }

    /**
     * Whether ATAK is currently drawing this map, or {@code null} when it cannot
     * say -- the file is not installed, ATAK has not registered it yet, or this
     * build has no handler for that kind of file.
     *
     * Asked through {@code URIContentManager}, the same route
     * ZOOM_TO_FILE_ACTION takes, so it works for a GeoPDF and a KMZ alike
     * without either being special-cased here.
     */
    public static Boolean isVisible(Depot.Package pkg) {
        final com.atakmap.android.hierarchy.action.Visibility v = visibility(pkg);
        return v == null ? null : Boolean.valueOf(v.isVisible());
    }

    /**
     * Turns the overlay on or off. Returns false when ATAK would not say -- the
     * caller should leave the control as it found it rather than showing a state
     * that is not real.
     */
    public static boolean setVisible(Depot.Package pkg, boolean visible) {
        final com.atakmap.android.hierarchy.action.Visibility v = visibility(pkg);
        if (v == null)
            return false;
        try {
            return v.setVisible(visible);
        } catch (RuntimeException notThisBuild) {
            Log.w(TAG, "could not set visibility on " + pkg.fileName() + ": "
                    + notThisBuild);
            return false;
        }
    }

    private static com.atakmap.android.hierarchy.action.Visibility visibility(
            Depot.Package pkg) {
        final File f = held(pkg);
        if (f == null)
            return null;
        try {
            final com.atakmap.android.data.URIContentHandler h =
                    com.atakmap.android.data.URIContentManager.getInstance()
                            .getHandler(f);
            if (h == null
                    || !h.isActionSupported(
                            com.atakmap.android.hierarchy.action.Visibility.class))
                return null;
            return (com.atakmap.android.hierarchy.action.Visibility) h;
        } catch (LinkageError | RuntimeException notThisBuild) {
            Log.w(TAG, "no visibility handler for " + f.getName() + ": "
                    + notThisBuild);
            return null;
        }
    }

    /**
     * Removes a package, telling ATAK before taking the file away.
     *
     * The order matters and is the opposite of ATAK's own. Deleting a GRG from
     * the overlay manager removes the file first and notifies the layer system
     * after, which leaves the tile reader holding a path that no longer exists;
     * disposing it then dereferences a freed GDAL dataset and the process dies
     * with SIGSEGV in {@code GdalTileReader.disposeImpl}. Observed on a 36 MB
     * district map, 2026-08-23.
     *
     * So: hand ATAK a delete first and let it unload the layer, then remove the
     * file. If ATAK has already removed it, that is fine -- the check is whether
     * it is gone, not who did it.
     */
    /**
     * What ATAK calls this kind of file, which its importers are registered
     * against. A DELETE_DATA naming a pair no importer claims is answered with
     * "no Importer found" and the layer is never unloaded -- the file goes and
     * the overlay stays on the map until the next restart.
     */
    private static String contentTypeOf(Depot.Package pkg) {
        return "grg".equals(pkg.destination()) ? "External GRG Data" : "KML";
    }

    /**
     * KML and KMZ are separate mime types and ATAK registers both; guessing
     * {@code application/octet-stream} matched neither.
     */
    private static String mimeTypeOf(Depot.Package pkg) {
        final String name = pkg.fileName().toLowerCase(java.util.Locale.US);
        if (name.endsWith(".kmz"))
            return "application/vnd.google-earth.kmz";
        if (name.endsWith(".kml"))
            return "application/vnd.google-earth.kml+xml";
        return "application/octet-stream";
    }

    private static boolean retire(Depot.Package pkg, File f) {
        final String path = f.getAbsolutePath();
        if ("grg".equals(pkg.destination())
                || "overlays".equals(pkg.destination())) {
            try {
                final android.content.Intent i = new android.content.Intent(
                        "com.atakmap.android.importexport.DELETE_DATA");
                i.putExtra("uri", path);
                i.putExtra("contentType", contentTypeOf(pkg));
                i.putExtra("mimeType", mimeTypeOf(pkg));
                com.atakmap.android.ipc.AtakBroadcast.getInstance().sendBroadcast(i);

                // Give the unload a moment to land before the file disappears.
                // Not a fix for a race we own -- it is ATAK's -- but it is the
                // difference between a clean removal and killing the app.
                Thread.sleep(750L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (LinkageError | RuntimeException notThisBuild) {
                Log.w(TAG, "could not ask ATAK to unload " + f.getName()
                        + " before deleting it: " + notThisBuild);
            }
        }

        final boolean gone = !f.exists() || f.delete();
        // Imagery is found by a scanner, so it has to be told to look again now
        // the file is gone. An overlay was handed to the import pipeline, and
        // handing that pipeline a file that no longer exists only logs "No file
        // to import" -- so it is asked to unload, not to import.
        if (gone && "imagery".equals(pkg.destination()))
            announce(pkg, new File(dirFor(pkg), pkg.fileName()));
        return gone;
    }

    /**
     * Tells ATAK the file arrived, by whichever route suits what it is.
     *
     * Imagery is found by the layer scanner. A GRG is not: its discovery thread
     * runs once at ATAK startup and never looks again, so a district map dropped
     * in afterwards would sit there unnoticed until the next restart. Handing it
     * to the import pipeline instead runs it through the same resolvers the
     * Import Manager uses, and registers it live.
     *
     * {@code importInPlace} because the file is already where it belongs, and
     * {@code promptOnMultipleMatch} off because the operator asked for this one
     * by name and does not need to be asked again.
     */
    private static void announce(Depot.Package pkg, File dest) {
        // Imagery is found by the scanner. A GRG is not, and neither is an
        // overlay -- both need the import pipeline to be registered live.
        final String where = pkg.destination();
        if (!"grg".equals(where) && !"overlays".equals(where)) {
            BaseMapInstaller.requestLayerScan(dest);
            return;
        }
        try {
            final android.content.Intent i = new android.content.Intent(
                    "com.atakmap.android.importfiles.USER_HANDLE_IMPORT_FILE_ACTION");
            i.putExtra("filepath", dest.getAbsolutePath());
            i.putExtra("importInPlace", true);
            i.putExtra("promptOnMultipleMatch", false);
            i.putExtra("showNotificationsDuringImport", false);
            com.atakmap.android.ipc.AtakBroadcast.getInstance().sendBroadcast(i);
        } catch (LinkageError | RuntimeException notThisBuild) {
            Log.w(TAG, "could not hand " + dest.getName()
                    + " to the importer; it will appear after a restart: "
                    + notThisBuild);
        }
    }

    private static void guardInside(File dir, File f) throws Exception {
        final String base = dir.getCanonicalPath();
        if (!f.getCanonicalPath().startsWith(base + File.separator))
            throw new IllegalStateException("path escapes " + base + ": " + f);
    }

    private static void deleteQuietly(File f) {
        if (f != null && f.exists() && !f.delete())
            Log.w(TAG, "could not delete " + f);
    }

    private void postProgress(final Callback cb, final Depot.Package pkg,
            final long done, final long total) {
        main.post(new Runnable() {
            @Override
            public void run() {
                cb.onProgress(pkg, done, total);
            }
        });
    }

    private void postInstalled(final Callback cb, final Depot.Package pkg,
            final File dest) {
        main.post(new Runnable() {
            @Override
            public void run() {
                cb.onInstalled(pkg, dest);
            }
        });
    }

    private void postError(final Callback cb, final Depot.Package pkg,
            final String msg) {
        main.post(new Runnable() {
            @Override
            public void run() {
                cb.onError(pkg, msg);
            }
        });
    }

    private static String describe(Exception e) {
        final String m = e.getMessage();
        return m != null && !m.isEmpty() ? m : e.getClass().getSimpleName();
    }
}
