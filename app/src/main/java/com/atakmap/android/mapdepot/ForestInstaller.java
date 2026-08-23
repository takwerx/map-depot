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
 * Downloads a Forest Service basemap package into ATAK's imagery folder.
 *
 * These are big -- 55 MB for a grassland, 1.4 GB for George Washington &amp;
 * Jefferson -- so unlike a map source XML this reports progress, checks free
 * space before it starts, and can be cancelled. It downloads to a staging file
 * and moves the result into place, so ATAK's imagery scanner never sees a
 * half-written package and registers a broken layer.
 *
 * There is no checksum to verify against: ArcGIS Online publishes no digest for
 * these and reports {@code size: 0} in the item record. The length the server
 * declares is the only integrity check available, so a truncated transfer is
 * caught and a corrupted one is not.
 */
public final class ForestInstaller {

    public static final String TAG = "MapDepotForests";

    private static final int CONNECT_TIMEOUT = 30000;
    private static final int READ_TIMEOUT = 60000;

    /** Leave room for the move as well as the download itself. */
    private static final double FREE_SPACE_HEADROOM = 1.15;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public interface Callback {
        void onProgress(Depot.Forest forest, long done, long total);

        void onInstalled(Depot.Forest forest, File dest);

        void onRemoved(Depot.Forest forest);

        void onError(Depot.Forest forest, String message);
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
    public static File imageryDir() {
        return FileSystemUtils.getItem("imagery");
    }

    /** Staging lives outside the scanned tree so a partial file is never seen. */
    private static File stagingDir() {
        return new File(FileSystemUtils.getItem(FileSystemUtils.TMP_DIRECTORY),
                "mapdepot-forests");
    }

    public static boolean isInstalled(Depot.Forest forest) {
        final File f = new File(imageryDir(), forest.fileName());
        return f.isFile() && (forest.bytes <= 0 || f.length() == forest.bytes);
    }

    public void install(final Depot.Forest forest, final Callback cb) {
        cancelled.set(false);
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final File dest = fetch(forest, cb);
                    postInstalled(cb, forest, dest);
                } catch (final Exception e) {
                    Log.w(TAG, "install failed for " + forest.id, e);
                    postError(cb, forest, describe(e));
                }
            }
        });
    }

    public void uninstall(final Depot.Forest forest, final Callback cb) {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                final File f = new File(imageryDir(), forest.fileName());
                final boolean gone = !f.exists() || f.delete();
                if (gone)
                    BaseMapInstaller.requestLayerScan(f);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (gone)
                            cb.onRemoved(forest);
                        else
                            cb.onError(forest, "could not delete " + f.getName());
                    }
                });
            }
        });
    }

    private File fetch(Depot.Forest forest, Callback cb) throws Exception {
        final File dir = imageryDir();
        if (!dir.exists() && !dir.mkdirs())
            throw new IllegalStateException("cannot create " + dir);

        final File staging = stagingDir();
        if (!staging.exists() && !staging.mkdirs())
            throw new IllegalStateException("cannot create " + staging);

        if (forest.bytes > 0) {
            final long free = dir.getUsableSpace();
            final long needed = (long) (forest.bytes * FREE_SPACE_HEADROOM);
            if (free < needed)
                throw new IllegalStateException("needs " + Depot.bytes(needed)
                        + ", only " + Depot.bytes(free) + " free");
        }

        final File part = new File(staging, forest.fileName() + ".part");
        guardInside(staging, part);
        deleteQuietly(part);

        final long written = download(forest, part, cb);

        // The declared length is the only integrity check on offer, so hold it.
        if (forest.bytes > 0 && written != forest.bytes) {
            deleteQuietly(part);
            throw new IllegalStateException("expected " + Depot.bytes(forest.bytes)
                    + ", got " + Depot.bytes(written));
        }

        final File dest = new File(dir, forest.fileName());
        guardInside(dir, dest);
        if (dest.exists() && !dest.delete())
            throw new IllegalStateException("cannot replace " + dest);
        if (!part.renameTo(dest)) {
            deleteQuietly(part);
            throw new IllegalStateException("cannot install " + dest);
        }

        BaseMapInstaller.requestLayerScan(dest);
        Log.d(TAG, "installed " + dest + " (" + dest.length() + " bytes)");
        return dest;
    }

    private long download(Depot.Forest forest, File part, Callback cb)
            throws Exception {
        final URL u = new URL(forest.url());
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

            final long total = forest.bytes > 0
                    ? forest.bytes : conn.getContentLength();

            long done = 0;
            long lastPost = 0;
            final byte[] buf = new byte[64 * 1024];
            try (InputStream in = conn.getInputStream();
                    OutputStream out = new FileOutputStream(part)) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (cancelled.get())
                        throw new IllegalStateException("cancelled");
                    out.write(buf, 0, n);
                    done += n;

                    // Once per megabyte. A callback per 64 KB buffer would post
                    // thousands of runnables to the main thread and stutter the map.
                    if (done - lastPost >= 1024L * 1024L) {
                        lastPost = done;
                        postProgress(cb, forest, done, total);
                    }
                }
            }
            return done;
        } finally {
            conn.disconnect();
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

    private void postProgress(final Callback cb, final Depot.Forest forest,
            final long done, final long total) {
        main.post(new Runnable() {
            @Override
            public void run() {
                cb.onProgress(forest, done, total);
            }
        });
    }

    private void postInstalled(final Callback cb, final Depot.Forest forest,
            final File dest) {
        main.post(new Runnable() {
            @Override
            public void run() {
                cb.onInstalled(forest, dest);
            }
        });
    }

    private void postError(final Callback cb, final Depot.Forest forest,
            final String msg) {
        main.post(new Runnable() {
            @Override
            public void run() {
                cb.onError(forest, msg);
            }
        });
    }

    private static String describe(Exception e) {
        final String m = e.getMessage();
        return m != null && !m.isEmpty() ? m : e.getClass().getSimpleName();
    }
}
