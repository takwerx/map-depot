
package com.atakmap.android.mapdepot;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A browsable archive of incident maps.
 *
 * Two of them now -- NIFC's Apache listing and UASWFC's JSON API -- and they
 * have nothing in common on the wire: one is scraped HTML with rounded sizes,
 * the other is JSON with exact ones. What they do share is everything the
 * operator sees: walk folders, read what a map will cost, tick it, have it land
 * in ATAK. So the browser and the installer are written once against this, and
 * each source is only responsible for turning its own server into these types.
 *
 * A source is expected to hide what is not a map -- geodatabases, shapefile
 * bundles, flight logs -- and to report how many it hid, because a panel that
 * silently shows less than is there reads as a panel that is broken.
 */
public interface MapSource {

    /** Stable identifier, used to key the remembered starting folder. */
    String id();

    /** What the landing button and the panel call this archive. */
    String label();

    /** Lists one folder. {@code path} is source-specific and opaque to callers. */
    void list(String path, ListingCallback cb);

    /**
     * The path of a folder found inside {@code parentPath}.
     *
     * Sources disagree about this and the disagreement is invisible until it
     * 404s: NIFC's Apache listing gives hrefs relative to the folder being
     * listed, so the parent has to be prepended, while UASWFC's API returns each
     * one already rooted at the site, so prepending anything corrupts it. The
     * browser must not have to know which is which.
     */
    String childPath(String parentPath, Entry child);

    /**
     * The exact byte count for a posting, which not every source's listing gives.
     *
     * {@link PackageInstaller} treats a length mismatch as a corrupt download, so
     * a source whose listing rounds (Apache prints "4.6M") must answer this from
     * somewhere better -- and one whose listing is already exact should answer
     * with what it has rather than throwing it away. Zero means "do not check",
     * and costs the free-space estimate too, so it is a last resort.
     */
    void exactSize(Posting posting, SizeCallback cb);

    /**
     * Turns one folder's entries into installable maps, all at once.
     *
     * Batched rather than per-file because that is the only way to guarantee two
     * files do not land on the same name: one IR date carries several products
     * that differ solely by a qualifier, and per-file naming loses all but the
     * last of them.
     */
    List<Posting> postingsFor(List<Entry> entries, String path,
            String decodedPath);

    void shutdown();

    /**
     * What went wrong, in words an operator can act on.
     *
     * The raw exception text is written for a developer: a crew in a canyon
     * with no signal was told "Unable to resolve host uaswfc.org: No address
     * associated with hostname", which is Java's way of saying there is no
     * network. What the operator needs to know is whether to move, wait, or
     * stop trying.
     */
    static String explain(Exception e, String host) {
        if (e instanceof java.net.UnknownHostException)
            return "no network — cannot reach " + host;
        if (e instanceof java.net.SocketTimeoutException)
            return "timed out reaching " + host + " — signal may be weak";
        if (e instanceof javax.net.ssl.SSLException)
            return "secure connection to " + host + " failed";
        if (e instanceof java.net.ConnectException)
            return "could not connect to " + host;
        if (e instanceof java.io.FileNotFoundException)
            return "that folder is no longer there";
        final String m = e.getMessage();
        return m != null && !m.isEmpty() ? m : e.getClass().getSimpleName();
    }

    // ------------------------------------------------------------------ types

    /** What the plugin will install. Everything else is not offered. */
    Pattern MAP_FILE = Pattern.compile(".*\\.(pdf|kmz)$",
            Pattern.CASE_INSENSITIVE);

    /** One row of a listing: a folder to walk into, or a file to fetch. */
    final class Entry {
        /** Decoded, for reading. */
        public final String name;
        /** Whatever the source needs to build the next request from. */
        public final String href;
        public final boolean directory;
        /** A date the source knows for this entry, or empty. */
        public final String modified;
        /** Zero for a directory, or when the source does not say. */
        public final long bytes;
        /** The source's own classification, or empty. Advisory only. */
        public final String kind;

        public Entry(String name, String href, boolean directory,
                String modified, long bytes, String kind) {
            this.name = name;
            this.href = href;
            this.directory = directory;
            this.modified = modified;
            this.bytes = bytes;
            this.kind = kind == null ? "" : kind;
        }

        public boolean isMap() {
            return !directory && MAP_FILE.matcher(name).matches();
        }
    }

    interface ListingCallback {
        /**
         * @param path the folder that was listed, echoed back so a reply that
         *        arrives after the operator has moved on can be discarded
         * @param entries folders first, then files
         * @param hidden how many entries were dropped for not being maps, so the
         *        panel can say so rather than quietly showing less than is there
         */
        void onListing(String path, List<Entry> entries, int hidden);

        void onError(String message);
    }

    interface SizeCallback {
        void onSize(long bytes);

        void onError(String message);
    }

    /**
     * One downloadable map, dressed as a {@link Depot.Package} so it goes
     * through exactly the installer the forest maps do -- staging outside the
     * scanned tree, a free-space check, an atomic rename into place and,
     * crucially, {@code announce()}, which hands the file to ATAK's import
     * pipeline. A file merely copied into {@code grg/} is invisible until the
     * next ATAK restart, because the GRG discovery thread runs once at startup
     * and never looks again.
     */
    final class Posting implements Depot.Package {

        private final String url;
        private final String originalName;
        private final String installName;
        private final String detail;
        private long bytes;

        public Posting(String url, String originalName, String installName,
                long bytes, String detail) {
            this.url = url;
            this.originalName = originalName;
            this.installName = installName;
            this.bytes = bytes;
            this.detail = detail;
        }

        /** Set once the real Content-Length is known. */
        public void setBytes(long b) {
            bytes = Math.max(0L, b);
        }

        public String originalName() {
            return originalName;
        }

        @Override
        public String id() {
            return url;
        }

        @Override
        public String name() {
            return installName;
        }

        @Override
        public String url() {
            return url;
        }

        @Override
        public String fileName() {
            return installName;
        }

        @Override
        public String legacyFileName() {
            // Nothing shipped under an earlier name, so there is nothing to
            // supersede. Returning the current name makes the installer's
            // cleanup a no-op rather than a delete of something else.
            return installName;
        }

        /**
         * A GeoPDF is a GRG: an overlay that composites over whatever base map
         * is already up, rather than a base map you switch to. A KMZ is an
         * overlay in ATAK's own sense and goes where ATAK puts imported ones.
         */
        @Override
        public String destination() {
            return installName.toLowerCase(Locale.US).endsWith(".kmz")
                    ? "overlays"
                    : "grg";
        }

        @Override
        public long bytes() {
            return bytes;
        }

        @Override
        public String describe() {
            return detail;
        }
    }
}
