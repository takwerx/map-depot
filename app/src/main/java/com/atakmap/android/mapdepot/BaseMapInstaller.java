package com.atakmap.android.mapdepot;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;

import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

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

    /**
     * Raised when a download does not match the digest the catalog promised.
     * Almost always staleness rather than corruption: the depot was republished
     * after this catalog was read.
     */
    public static final String STALE_CATALOG = "catalog out of date";

    /** A map source XML far larger than this is not a map source XML. */
    private static final int MAX_BYTES = 512 * 1024;

    /**
     * The slot in a keyed source's XML that the operator's key goes into.
     * Spelled like MOBAC's own {@code {$x}} tokens so the file reads as one
     * of them, though ATAK never sees it: the digest is checked on the
     * template and the slot is filled before anything is written.
     */
    public static final String KEY_SLOT = "{$key}";

    /** Raised when a keyed source is installed with no key on the device. */
    public static final String NO_KEY = "no API key";

    /**
     * What a key may look like. The key is pasted into a URL inside an XML
     * file, so anything outside the URL-unreserved set is refused rather
     * than escaped -- no provider issues keys that need it.
     */
    private static final Pattern KEY_SHAPE =
            Pattern.compile("[A-Za-z0-9._~-]{8,256}");

    private static final String PREF_KEY_PREFIX = "mapdepot.key.";

    /** The operator's key for a provider, or empty. Never logged. */
    public static String keyFor(String provider) {
        final SharedPreferences p = prefs();
        if (p == null || provider == null || provider.isEmpty())
            return "";
        final String k = p.getString(PREF_KEY_PREFIX + provider, "");
        return k == null ? "" : k.trim();
    }

    /** Stores a key, or forgets it when empty. False when it is not a key. */
    public static boolean setKey(String provider, String key) {
        final SharedPreferences p = prefs();
        if (p == null)
            return false;
        final String k = key == null ? "" : key.trim();
        if (k.isEmpty()) {
            p.edit().remove(PREF_KEY_PREFIX + provider).apply();
            return true;
        }
        if (!KEY_SHAPE.matcher(k).matches())
            return false;
        p.edit().putString(PREF_KEY_PREFIX + provider, k).apply();
        return true;
    }

    /** On the MapView context, so the key outlives a plugin reload. */
    private static SharedPreferences prefs() {
        final MapView mv = MapView.getMapView();
        return mv == null ? null
                : PreferenceManager.getDefaultSharedPreferences(mv.getContext());
    }

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

    /**
     * Where a source belongs, which depends on what kind it is.
     *
     * A MOBAC {@code .xml} goes in the mapsources folder. A streaming
     * {@code .json} config is read by a different provider entirely -- ATAK's own
     * "TAK Maps" is one of these -- and is matched by parsing any file in the
     * imagery tree rather than by extension, so it sits at the imagery root
     * alongside the packages.
     */
    public static File dirFor(Depot.BaseMap map) {
        if (map.fileName().endsWith(".json"))
            return FileSystemUtils.getItem("imagery");
        // An overlay in the mapsources folder is one more base map to pick,
        // and picking it replaces the imagery with roads on a black screen.
        // In grg/ ATAK lists it as an image overlay and draws it on top.
        return map.overlay() ? FileSystemUtils.getItem("grg") : mapSourceDir();
    }

    /**
     * Where an overlay landed before 1.12, when every source went to the
     * base map folder. Cleaned up on install and remove so a phone that
     * downloaded one as a base map does not keep a second copy of it there.
     */
    private static File legacyFile(Depot.BaseMap map) {
        return map.overlay() ? new File(mapSourceDir(), map.fileName()) : null;
    }

    private static void removeLegacy(Depot.BaseMap map) {
        final File old = legacyFile(map);
        if (old != null && old.isFile() && old.delete()) {
            Log.d(TAG, "removed base map copy of overlay " + old.getName());
            notifyAtak(old);
        }
    }

    /**
     * Whether an added overlay is drawing, or null when ATAK will not say
     * (not an overlay, not added, or not registered yet). The roads overlay
     * lives under Overlay Manager > Image Overlay, which nobody looks for;
     * the operator (2026-10-11): "people are not going to know where to turn
     * that off". So the row carries the switch.
     */
    public static Boolean isVisible(Depot.BaseMap map) {
        if (!map.overlay())
            return null;
        final com.atakmap.android.hierarchy.action.Visibility v =
                PackageInstaller.visibilityOf(new File(dirFor(map), map.fileName()));
        return v == null ? null : Boolean.valueOf(v.isVisible());
    }

    public static boolean setVisible(Depot.BaseMap map, boolean visible) {
        final com.atakmap.android.hierarchy.action.Visibility v =
                PackageInstaller.visibilityOf(new File(dirFor(map), map.fileName()));
        if (v == null)
            return false;
        try {
            return v.setVisible(visible);
        } catch (RuntimeException notThisBuild) {
            Log.w(TAG, "could not set visibility on " + map.fileName() + ": "
                    + notThisBuild);
            return false;
        }
    }

    /**
     * Whether this source is already on the device at the right size.
     *
     * A keyed source is sized after substitution, so it is checked for the
     * key instead: a file written with a key the operator has since replaced
     * reads as not installed, and Add rewrites it with the current one.
     */
    public static boolean isInstalled(Depot.BaseMap map) {
        final File f = new File(dirFor(map), map.fileName());
        if (!f.isFile())
            return false;
        if (!map.needsKey())
            return f.length() == map.bytes;
        final String key = keyFor(map.keyProvider);
        if (key.isEmpty() || f.length() > MAX_BYTES)
            return false;
        try (InputStream in = new java.io.FileInputStream(f)) {
            final byte[] b = new byte[(int) f.length()];
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0)
                off += n;
            return new String(b, 0, off, StandardCharsets.UTF_8).contains(key);
        } catch (Exception unreadable) {
            return false;
        }
    }

    public void install(final Depot.BaseMap map, final Callback cb) {
        if (map.needsKey() && keyFor(map.keyProvider).isEmpty()) {
            cb.onError(map, NO_KEY);
            return;
        }
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
                final File f = new File(dirFor(map), map.fileName());
                if (map.overlay() && f.exists())
                    PackageInstaller.unload(f, "External GRG Data",
                            "application/octet-stream");
                final boolean gone = !f.exists() || f.delete();
                if (gone) {
                    if (map.overlay())
                        removeLegacy(map);
                    else
                        notifyAtak(f);
                }
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
        final File dir = dirFor(map);
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
            // Named so the caller can tell this apart from a corrupt download.
            // It nearly always means the catalog held in memory is older than
            // the depot, not that anything is wrong with the file.
            throw new IllegalStateException(STALE_CATALOG);

        final byte[] written = map.needsKey() ? withKey(map, body) : body;

        // Write beside the destination and rename, so a source is never half
        // written where ATAK might scan it.
        final File tmp = new File(dir, map.fileName() + ".part");
        guardInside(dir, tmp);
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(written);
        }

        final File dest = new File(dir, map.fileName());
        guardInside(dir, dest);
        if (dest.exists() && !dest.delete())
            throw new IllegalStateException("cannot replace " + dest);
        if (!tmp.renameTo(dest))
            throw new IllegalStateException("cannot install " + dest);

        if (map.overlay()) {
            // GRG discovery runs once at ATAK start; the import pipeline
            // registers it now, the same way the district maps arrive.
            PackageInstaller.importGrg(dest);
            removeLegacy(map);
        } else {
            notifyAtak(dest);
        }
        Log.d(TAG, "installed " + dest + " (" + dest.length() + " bytes)");
        return dest;
    }

    /**
     * The verified template with the operator's key in its slot. A keyed
     * entry whose XML has no slot would install a URL ATAK sends with a
     * literal "{$key}" and draw watermarks, so that is refused here.
     */
    private static byte[] withKey(Depot.BaseMap map, byte[] template) {
        final String key = keyFor(map.keyProvider);
        if (key.isEmpty())
            throw new IllegalStateException(NO_KEY);
        final String xml = new String(template, StandardCharsets.UTF_8);
        if (!xml.contains(KEY_SLOT))
            throw new IllegalStateException("source has no key slot");
        return xml.replace(KEY_SLOT, key).getBytes(StandardCharsets.UTF_8);
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
