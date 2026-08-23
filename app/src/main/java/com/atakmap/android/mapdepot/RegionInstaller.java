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
import java.security.MessageDigest;
import java.util.EnumSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;

/**
 * Downloads a region's elevation cells and puts them where ATAK looks for them.
 *
 * A region is tens to hundreds of one-degree cells rather than one large archive.
 * That is deliberate: a cell on a state line is stored once and referenced by both
 * states, a half-finished region resumes at cell granularity instead of restarting,
 * and a region an operator already partly holds only fetches the difference.
 *
 * Every cell is verified against the digest in the manifest, after decompression,
 * before it is allowed to take its real name. That check is the reason this plugin
 * exists: the collection this depot was built from contained a cell truncated to
 * 30% of its length sitting beside a good copy, and nothing about it looked wrong.
 * A silently short elevation cell feeds line-of-sight and viewshed with plausible
 * numbers, which is worse than having no cell at all.
 */
public final class RegionInstaller {

    public static final String TAG = "MapDepotInstaller";

    private static final int CONNECT_TIMEOUT = 20000;
    private static final int READ_TIMEOUT = 60000;
    private static final int BUFFER = 64 * 1024;

    /** Attempts per cell; each resumes the compressed transfer where it stopped. */
    private static final int ATTEMPTS = 4;

    private static final long BACKOFF_MS = 1500L;

    /**
     * Refuse to start unless the region fits with room to spare. A plugin that
     * fills a device's storage has not inconvenienced someone, it has taken their
     * map away mid-task.
     */
    private static final double FREE_SPACE_HEADROOM = 1.15;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public interface Callback {
        /** Overall position through the region. */
        void onProgress(int cellsDone, int cellsTotal, long bytesDone,
                long bytesTotal, String currentCell);

        /** A cell already held and verified was skipped. */
        void onSkipped(String key);

        /** A transfer dropped and will resume from {@code haveBytes}. */
        void onRetry(String key, int attempt, long haveBytes);

        void onComplete(int installed, int skipped);

        void onError(String message);
    }

    public void cancel() {
        cancelled.set(true);
    }

    public void shutdown() {
        cancelled.set(true);
        worker.shutdownNow();
    }

    /** {@code <atak>/DTED} — resolved through ATAK rather than assumed. */
    public static File dtedRoot() {
        return FileSystemUtils.getItem(FileSystemUtils.DTED_DIRECTORY);
    }

    /** How many of a manifest's cells are already installed at the right size. */
    public static int installedCount(Depot.Manifest manifest) {
        final File root = dtedRoot();
        int n = 0;
        for (Depot.Cell c : manifest.cells) {
            final File f = new File(new File(root, c.dirName()), c.fileName());
            if (f.isFile() && f.length() == c.bytes)
                n++;
        }
        return n;
    }

    public void install(final Depot.Manifest manifest, final Callback cb) {
        cancelled.set(false);
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    runInstall(manifest, cb);
                } catch (final Exception e) {
                    Log.e(TAG, "region install failed", e);
                    postError(cb, describe(e));
                }
            }
        });
    }

    private void runInstall(Depot.Manifest manifest, Callback cb)
            throws Exception {

        final File root = dtedRoot();
        if (!root.exists() && !root.mkdirs())
            throw new IllegalStateException("cannot create " + root);

        // Work out what is genuinely outstanding before quoting a size or
        // touching the network -- a region half-held should not claim to need
        // the whole thing.
        long outstandingInstalled = 0, outstandingDownload = 0;
        int todo = 0, skipped = 0;
        for (Depot.Cell c : manifest.cells) {
            final File f = new File(new File(root, c.dirName()), c.fileName());
            if (f.isFile() && f.length() == c.bytes) {
                skipped++;
            } else {
                todo++;
                outstandingInstalled += c.bytes;
                outstandingDownload += c.gzBytes;
            }
        }

        if (todo == 0) {
            postComplete(cb, 0, skipped);
            return;
        }

        final long free = root.getUsableSpace();
        final long needed = (long) (outstandingInstalled * FREE_SPACE_HEADROOM);
        if (free < needed) {
            throw new IllegalStateException(String.format(
                    "not enough space: %s needs %s, %s free",
                    manifest.name, Depot.bytes(needed), Depot.bytes(free)));
        }

        Log.d(TAG, manifest.id + ": " + todo + " cells to fetch, " + skipped
                + " already held, " + Depot.bytes(outstandingDownload)
                + " to download");

        final String base = DepotClient.baseUrl() + "/dted/v1/";
        final File staging = new File(root, ".mapdepot");
        if (!staging.exists() && !staging.mkdirs())
            throw new IllegalStateException("cannot create " + staging);

        int installed = 0;
        long bytesDone = 0;

        for (Depot.Cell cell : manifest.cells) {
            if (cancelled.get()) {
                postError(cb, "cancelled");
                return;
            }

            final File dest = new File(new File(root, cell.dirName()),
                    cell.fileName());
            if (dest.isFile() && dest.length() == cell.bytes) {
                postSkipped(cb, cell.key);
                continue;
            }

            postProgress(cb, installed, todo, bytesDone, outstandingDownload,
                    cell.key);

            fetchCell(base, cell, staging, dest, cb);

            installed++;
            bytesDone += cell.gzBytes;
            postProgress(cb, installed, todo, bytesDone, outstandingDownload,
                    cell.key);
        }

        // The staging directory lives under DTED so a move is a rename on the
        // same filesystem rather than a copy; leaving it behind would make ATAK
        // scan files that are not cells.
        deleteQuietly(staging);

        postComplete(cb, installed, skipped);
    }

    /**
     * Fetches one cell, retrying with a resumed range request, and installs it
     * only once the decompressed bytes match the manifest digest.
     */
    private void fetchCell(String base, Depot.Cell cell, File staging, File dest,
            Callback cb) throws Exception {

        final File gz = new File(staging, cell.dirName() + "-"
                + cell.fileName() + ".gz.part");
        guardInside(staging, gz);

        Exception last = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            if (cancelled.get())
                throw new IllegalStateException("cancelled");
            try {
                downloadCompressed(base + cell.key + ".gz", gz, cell);

                final File plain = new File(staging, cell.fileName() + ".part");
                guardInside(staging, plain);

                final String digest = inflate(gz, plain);
                if (!cell.sha256.equalsIgnoreCase(digest)) {
                    // Wrong bytes cannot be resumed towards right ones, so the
                    // compressed copy goes rather than being extended forever.
                    deleteQuietly(gz);
                    deleteQuietly(plain);
                    throw new IllegalStateException("checksum mismatch for "
                            + cell.key);
                }
                if (plain.length() != cell.bytes) {
                    deleteQuietly(gz);
                    deleteQuietly(plain);
                    throw new IllegalStateException("size mismatch for "
                            + cell.key);
                }

                place(plain, dest, cell);
                deleteQuietly(gz);
                return;
            } catch (Exception e) {
                last = e;
                Log.w(TAG, "cell " + cell.key + " attempt " + attempt + "/"
                        + ATTEMPTS + ": " + describe(e));
                if (attempt == ATTEMPTS)
                    break;
                postRetry(cb, cell.key, attempt, gz.exists() ? gz.length() : 0);
                try {
                    Thread.sleep(BACKOFF_MS * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ie;
                }
            }
        }
        throw last != null ? last
                : new IllegalStateException("could not fetch " + cell.key);
    }

    /**
     * Hands the verified cell to ATAK's own importer, which knows where DTED
     * belongs and tells the rest of the app it arrived. If that path is not
     * available on this ATAK build the file is moved into the documented layout
     * directly -- the file watcher picks it up on its next scan, a little later
     * but no less correctly.
     */
    private void place(File verified, File dest, Depot.Cell cell)
            throws Exception {

        try {
            final gov.tak.api.importfiles.ImportDTEDResolver resolver =
                    new gov.tak.api.importfiles.ImportDTEDResolver(".dt2",
                            dtedRoot(), null);

            // The importer keys off the name, so give it the cell's real one.
            final File named = new File(verified.getParentFile(),
                    cell.fileName());
            if (!verified.equals(named)) {
                deleteQuietly(named);
                if (!verified.renameTo(named))
                    throw new IllegalStateException("cannot stage " + named);
            }

            if (resolver.beginImport(named, EnumSet.of(
                    gov.tak.api.importfiles.ImportResolver.SortFlags.IMPORT_MOVE))) {
                return;
            }
            Log.w(TAG, "importer declined " + cell.key + "; placing directly");
            moveInto(named, dest);
        } catch (LinkageError notThisBuild) {
            // A different ATAK build; the layout is still the layout.
            Log.w(TAG, "import API unavailable, placing directly: "
                    + notThisBuild);
            moveInto(verified, dest);
        }
    }

    private void moveInto(File from, File dest) throws Exception {
        final File parent = dest.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs())
            throw new IllegalStateException("cannot create " + parent);
        guardInside(dtedRoot(), dest);

        if (dest.exists() && !dest.delete())
            throw new IllegalStateException("cannot replace " + dest);
        if (!from.renameTo(dest))
            throw new IllegalStateException("cannot install " + dest);
    }

    /** Streams the gzipped cell, resuming an earlier partial transfer. */
    private void downloadCompressed(String url, File dest, Depot.Cell cell)
            throws Exception {

        long have = dest.exists() ? dest.length() : 0;
        if (have > 0 && cell.gzBytes > 0 && have >= cell.gzBytes) {
            // Already whole, or stale and longer than it should be. Inflation
            // verifies either way, so only discard what cannot be a prefix.
            if (have > cell.gzBytes) {
                deleteQuietly(dest);
                have = 0;
            } else {
                return;
            }
        }

        final URL u = new URL(url);
        if (!"https".equalsIgnoreCase(u.getProtocol()))
            throw new IllegalStateException("depot must be https");

        final HttpURLConnection conn = (HttpURLConnection) u.openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        if (have > 0)
            conn.setRequestProperty("Range", "bytes=" + have + "-");

        try {
            final int code = conn.getResponseCode();
            boolean append;
            if (code == HttpURLConnection.HTTP_PARTIAL) {
                append = true;
            } else if (code == HttpURLConnection.HTTP_OK) {
                append = false;   // server ignored the range
            } else {
                throw new IllegalStateException("HTTP " + code);
            }

            final byte[] buf = new byte[BUFFER];
            try (InputStream in = conn.getInputStream();
                    OutputStream out = new FileOutputStream(dest, append)) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (cancelled.get())
                        throw new IllegalStateException("cancelled");
                    out.write(buf, 0, n);
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    /** Inflates to {@code out} and returns the SHA-256 of what came out. */
    private static String inflate(File gz, File out) throws Exception {
        final MessageDigest md = MessageDigest.getInstance("SHA-256");
        final byte[] buf = new byte[BUFFER];

        try (InputStream in = new GZIPInputStream(
                new java.io.FileInputStream(gz), BUFFER);
                OutputStream os = new FileOutputStream(out)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                md.update(buf, 0, n);
            }
        }

        final StringBuilder hex = new StringBuilder();
        for (byte b : md.digest())
            hex.append(String.format("%02x", b));
        return hex.toString();
    }

    /**
     * Proves a path stays inside the directory it is meant to. The keys that build
     * these paths come off the network, and the plugin writes with ATAK's uid, so
     * a traversal here would reach ATAK's own storage.
     */
    private static void guardInside(File dir, File f) throws Exception {
        final String base = dir.getCanonicalPath();
        if (!f.getCanonicalPath().startsWith(base + File.separator))
            throw new IllegalStateException("path escapes " + base + ": " + f);
    }

    private static void deleteQuietly(File f) {
        if (f == null || !f.exists())
            return;
        if (f.isDirectory()) {
            final File[] kids = f.listFiles();
            if (kids != null)
                for (File k : kids)
                    deleteQuietly(k);
        }
        if (!f.delete())
            Log.w(TAG, "could not remove " + f);
    }

    private static String describe(Exception e) {
        final String m = e.getMessage();
        return m != null && !m.isEmpty() ? m : e.getClass().getSimpleName();
    }

    // ------------------------------------------------------------- callbacks

    private void postProgress(final Callback cb, final int done, final int total,
            final long bytesDone, final long bytesTotal, final String key) {
        main.post(new Runnable() {
            @Override
            public void run() {
                cb.onProgress(done, total, bytesDone, bytesTotal, key);
            }
        });
    }

    private void postSkipped(final Callback cb, final String key) {
        main.post(new Runnable() {
            @Override
            public void run() {
                cb.onSkipped(key);
            }
        });
    }

    private void postRetry(final Callback cb, final String key,
            final int attempt, final long have) {
        main.post(new Runnable() {
            @Override
            public void run() {
                cb.onRetry(key, attempt, have);
            }
        });
    }

    private void postComplete(final Callback cb, final int installed,
            final int skipped) {
        main.post(new Runnable() {
            @Override
            public void run() {
                cb.onComplete(installed, skipped);
            }
        });
    }

    private void postError(final Callback cb, final String msg) {
        main.post(new Runnable() {
            @Override
            public void run() {
                cb.onError(msg);
            }
        });
    }
}
