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
            written = download(pkg, part, cb);
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
            if (code != HttpURLConnection.HTTP_OK)
                throw new IllegalStateException("HTTP " + code);

            final long total = pkg.bytes() > 0
                    ? pkg.bytes() : conn.getContentLength();

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
     * Selects an installed package in ATAK's layer manager and goes to it.
     *
     * {@code SELECT_LAYER} does the whole job: it finds which adapter holds a
     * selection by that name -- Mobile for a package, GRG for a district map --
     * activates that tab, selects the layer and zooms. Naming the tab is not
     * necessary and would only be a way to get it wrong.
     *
     * The earlier attempt used {@code ZOOM_TO_FILE_ACTION}, which moves the map
     * but leaves the layer unselected and lands the operator on whichever tab
     * they were already looking at. Going somewhere without turning the map on
     * is not what "take me to it" means.
     *
     * The selection name is the filename, which is also how ATAK labels these:
     * {@code CompactCacheDatasetDescriptorSpi} passes {@code file.getName()}
     * straight through.
     *
     * Quiet when nothing is installed -- the operator tapped a row, which is not
     * worth an error dialog.
     */
    public static void goTo(Depot.Package pkg) {
        final File f = held(pkg);
        if (f == null)
            return;
        try {
            final android.content.Intent i = new android.content.Intent(
                    "com.atakmap.android.maps.SELECT_LAYER");
            i.putExtra("selection", f.getName());
            i.putExtra("zoomTo", true);
            com.atakmap.android.ipc.AtakBroadcast.getInstance().sendBroadcast(i);
        } catch (LinkageError | RuntimeException notThisBuild) {
            Log.w(TAG, "could not select " + f.getName() + ": " + notThisBuild);
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
    private static boolean retire(Depot.Package pkg, File f) {
        final String path = f.getAbsolutePath();
        if ("grg".equals(pkg.destination())) {
            try {
                final android.content.Intent i = new android.content.Intent(
                        "com.atakmap.android.importexport.DELETE_DATA");
                i.putExtra("uri", path);
                i.putExtra("contentType", "External GRG Data");
                i.putExtra("mimeType", "application/octet-stream");
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
        if (gone && !"grg".equals(pkg.destination()))
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
        if (!"grg".equals(pkg.destination())) {
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
