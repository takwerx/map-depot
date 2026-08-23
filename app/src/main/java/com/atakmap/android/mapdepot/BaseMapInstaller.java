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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Downloads a base map's source XML and puts it where ATAK reads map sources.
 *
 * These are a few hundred bytes each, so none of the machinery the elevation side
 * needs applies: no resume, no progress, no free-space check. What does carry over
 * is the digest check -- a map source that arrives truncated produces a map layer
 * that silently draws nothing, and the operator has no way to tell that from a
 * server being down.
 */
public final class BaseMapInstaller {

    public static final String TAG = "MapDepotBaseMaps";

    private static final int CONNECT_TIMEOUT = 20000;
    private static final int READ_TIMEOUT = 30000;

    /** A map source XML far larger than this is not a map source XML. */
    private static final int MAX_BYTES = 512 * 1024;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    public interface Callback {
        void onInstalled(Depot.BaseMap map, File dest);

        void onRemoved(Depot.BaseMap map);

        void onError(Depot.BaseMap map, String message);
    }

    public void shutdown() {
        worker.shutdownNow();
    }

    /**
     * {@code <atak>/imagery/mobile/mapsources} — the directory ATAK scans for
     * custom map sources. Resolved from ATAK's own root rather than hardcoded.
     */
    public static File mapSourceDir() {
        return new File(FileSystemUtils.getItem("imagery"),
                "mobile" + File.separator + "mapsources");
    }

    /** Whether this source is already on the device at the right size. */
    public static boolean isInstalled(Depot.BaseMap map) {
        final File f = new File(mapSourceDir(), map.fileName());
        return f.isFile() && f.length() == map.bytes;
    }

    public void install(final Depot.BaseMap map, final Callback cb) {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final File dest = fetch(map);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            cb.onInstalled(map, dest);
                        }
                    });
                } catch (final Exception e) {
                    Log.w(TAG, "install failed for " + map.id, e);
                    final String msg = describe(e);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            cb.onError(map, msg);
                        }
                    });
                }
            }
        });
    }

    /**
     * Deletes an installed map source and tells ATAK to forget it.
     *
     * Worth having because a catalog entry can outlive the server behind it --
     * a source whose tile host has been retired still installs cleanly and then
     * draws nothing, and without this the only way off the device was a file
     * manager.
     */
    public void uninstall(final Depot.BaseMap map, final Callback cb) {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                final File f = new File(mapSourceDir(), map.fileName());
                final boolean gone = !f.exists() || f.delete();
                if (gone)
                    notifyAtak(f);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (gone)
                            cb.onRemoved(map);
                        else
                            cb.onError(map, "could not delete " + f.getName());
                    }
                });
            }
        });
    }

    private File fetch(Depot.BaseMap map) throws Exception {
        final File dir = mapSourceDir();
        if (!dir.exists() && !dir.mkdirs())
            throw new IllegalStateException("cannot create " + dir);

        final URL u = new URL(DepotClient.baseUrl() + "/" + map.file);
        if (!"https".equalsIgnoreCase(u.getProtocol()))
            throw new IllegalStateException("depot must be https");

        final byte[] body;
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
                    if (out.size() > MAX_BYTES)
                        throw new IllegalStateException("map source too large");
                }
            }
            body = out.toByteArray();
        } finally {
            conn.disconnect();
        }

        final MessageDigest md = MessageDigest.getInstance("SHA-256");
        final StringBuilder hex = new StringBuilder();
        for (byte b : md.digest(body))
            hex.append(String.format("%02x", b));
        if (!map.sha256.equalsIgnoreCase(hex.toString()))
            throw new IllegalStateException("checksum mismatch for " + map.id);

        // Write beside the destination and rename, so a source is never half
        // written where ATAK might scan it.
        final File tmp = new File(dir, map.fileName() + ".part");
        guardInside(dir, tmp);
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(body);
        }

        final File dest = new File(dir, map.fileName());
        guardInside(dir, dest);
        if (dest.exists() && !dest.delete())
            throw new IllegalStateException("cannot replace " + dest);
        if (!tmp.renameTo(dest))
            throw new IllegalStateException("cannot install " + dest);

        notifyAtak(dest);
        Log.d(TAG, "installed " + dest + " (" + dest.length() + " bytes)");
        return dest;
    }

    /**
     * Action ATAK's own layer scanner listens for. Written as a literal rather
     * than read off {@code ScanLayersService}, which lives in the obfuscated
     * {@code com.atakmap.android.*} half of the API: an intent action is part of
     * ATAK's external contract and survives the renaming that class does not.
     */
    private static final String SCAN_LAYERS_START =
            "com.atakmap.android.layers.SCAN_LAYERS_START";

    /**
     * Tells ATAK to look for the map source that just landed, so it shows up in
     * the layer list without restarting the app.
     *
     * The stable importer was the obvious route and it does nothing here --
     * {@code ImportLayersResolver} moves a file into place, and this file is
     * already in place. Nothing then tells the running layer registry it exists,
     * which is why an installed source only appeared after a restart. The scan
     * broadcast is what ATAK sends itself when imagery arrives.
     */
    /** Shared with {@link ForestInstaller}: anything dropped into ATAK's imagery
     *  tree needs the same nudge to be noticed without a restart. */
    static void requestLayerScan(File dest) {
        notifyAtak(dest);
    }

    private static void notifyAtak(File dest) {
        try {
            com.atakmap.android.ipc.AtakBroadcast.getInstance().sendBroadcast(
                    new android.content.Intent(SCAN_LAYERS_START));
        } catch (LinkageError | RuntimeException notThisBuild) {
            Log.w(TAG, "could not request a layer scan; " + dest.getName()
                    + " will appear after a restart: " + notThisBuild);
        }
    }

    private static void guardInside(File dir, File f) throws Exception {
        final String base = dir.getCanonicalPath();
        if (!f.getCanonicalPath().startsWith(base + File.separator))
            throw new IllegalStateException("path escapes " + base + ": " + f);
    }

    private static String describe(Exception e) {
        final String m = e.getMessage();
        return m != null && !m.isEmpty() ? m : e.getClass().getSimpleName();
    }
}
