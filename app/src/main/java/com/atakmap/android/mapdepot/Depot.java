package com.atakmap.android.mapdepot;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * What the depot publishes, as the plugin sees it.
 *
 * Two documents: a catalog listing regions, and a manifest per region listing the
 * cells that region needs. Cells are stored once and named by their own geography,
 * so a cell on a state line is one object referenced by two manifests rather than
 * two copies.
 */
public final class Depot {

    private Depot() {
    }

    /**
     * A cell key becomes a filesystem path and a URL, and it arrives from the
     * network, so it is matched against the closed form it is drawn from before it
     * is allowed to be either. The SHA-256 alongside it is no defence here, because
     * it comes from the same document.
     */
    private static final Pattern CELL_KEY =
            Pattern.compile("[we]\\d{3}/[ns]\\d{2}\\.dt2");

    /** Same reasoning for a base map id, which becomes a filename on the device. */
    /**
     * A map id becomes a query parameter on data.fs.usda.gov. Letters, digits,
     * spaces, periods and hyphens are everything the Forest Service uses; an
     * ampersand or equals here would append a parameter of someone else's
     * choosing.
     */
    // Commas are in here because forests have them: "Grand Mesa, Uncompahgre and
    // Gunnison National Forests" was rejected on every catalog load and was
    // therefore invisible and undownloadable, while the Forest Service serves it
    // perfectly well. The value is URL-encoded before it reaches a query string,
    // so a comma cannot separate a parameter; the pattern is defence in depth,
    // not the escaping.
    private static final Pattern MAP_ID = Pattern.compile("[A-Za-z0-9 .,'-]{1,120}");

    /**
     * A region id becomes part of a filename in the cache directory, so it is
     * held to the same closed alphabet as every other id here. Uppercase is
     * allowed because these are postal codes: US-CA, CA-AB.
     */
    private static final Pattern REGION_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9-]{0,63}");

    /**
     * A manifest path is appended to the depot URL. It cannot reach another host
     * -- it is always appended after a slash, so the authority has already been
     * parsed -- but it has no business containing a traversal either.
     */
    private static final Pattern MANIFEST_PATH =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,191}");

    /** ArcGIS Online issues 32 hex characters and nothing else. */
    private static final Pattern AGOL_ID = Pattern.compile("[0-9a-f]{32}");

    // 128, not 64. The limit exists to bound something that becomes a filename
    // and a URL path segment, not to be tight for its own sake -- and at 64 it
    // silently dropped seventeen ranger district maps whose names are genuinely
    // long ("Samuel R. McKelvie National Forest and Nebraska National Forest -
    // East"). A rejected id is a map the operator cannot download.
    private static final Pattern SOURCE_ID =
            Pattern.compile("[a-z0-9][a-z0-9-]{0,127}");

    /** A downloadable region — a US state or a Canadian province. */
    public static final class Region {
        public final String id;
        public final String name;
        public final String country;
        public final String manifestPath;
        public final int cellCount;
        public final int needCount;
        /** True when the depot holds every cell this region needs. */
        public final boolean complete;
        public final long downloadBytes;

        /** Size once fully installed. Not what this device holds -- see {@link #held}. */
        public final long fullBytes;

        /** Cell keys ("w116/n33.dt2"), so the device can be asked what it has. */
        public final List<String> cells = new ArrayList<>();

        /** Bytes of this region actually present on the device. */
        private long heldBytes;

        /** Cells of this region actually present on the device. */
        private int heldCells;

        Region(JSONObject o) {
            id = o.optString("id");
            name = o.optString("name", id);
            country = o.optString("country", "");
            manifestPath = o.optString("manifest");
            cellCount = o.optInt("cellCount");
            needCount = o.optInt("needCount", o.optInt("cellCount"));
            complete = o.optBoolean("complete", false);
            downloadBytes = o.optLong("downloadBytes");
            fullBytes = o.optLong("bytes");

            final JSONArray keys = o.optJSONArray("cells");
            if (keys != null)
                for (int i = 0; i < keys.length(); i++) {
                    final String k = keys.optString(i);
                    if (CELL_KEY.matcher(k).matches())
                        cells.add(k);
                }

            // Checked as strictly as every other id in this file. This one was
            // written first and was long the exception: only tested for
            // emptiness, while becoming a filename in ATAK's cache directory.
            if (!REGION_ID.matcher(id).matches())
                throw new IllegalArgumentException("invalid region id: " + id);
            if (!MANIFEST_PATH.matcher(manifestPath).matches()
                    || manifestPath.contains(".."))
                throw new IllegalArgumentException(
                        "region " + id + " has an unusable manifest path");
        }

        /**
         * Works out what this device holds, from one scan of the DTED tree.
         *
         * A cell counts as held only at its full length: a truncated .dt2 reads
         * as valid to ATAK and quietly poisons line of sight, so a short file is
         * treated as absent and will be fetched again.
         */
        public void measureAgainst(java.util.Map<String, Long> onDevice) {
            heldBytes = 0;
            heldCells = 0;
            for (String key : cells) {
                final Long len = onDevice.get(key);
                if (len != null && len > 0) {
                    heldBytes += len;
                    heldCells++;
                }
            }
        }

        public long held() {
            return heldBytes;
        }

        public boolean fullyHeld() {
            return !cells.isEmpty() && heldCells == cells.size();
        }

        /**
         * What the operator is deciding is whether to spend the download, so the
         * row leads with what they already hold. Cell counts and compressed
         * transfer sizes are engineering detail.
         *
         * "Partial" here means the depot itself does not have the whole region --
         * Alaska above 60 degrees, most of Canada. That is a different statement
         * from "you have some of it", and conflating the two is what made every
         * row claim an install that had never happened.
         */
        public String describe() {
            final String whole = Depot.bytes(fullBytes);
            final String suffix = complete ? "" : " · Partial";

            if (cells.isEmpty())
                return whole + suffix;
            if (heldCells == 0)
                return whole + " to download" + suffix;
            if (fullyHeld())
                return "Installed · " + Depot.bytes(heldBytes) + suffix;

            // "1.7 GB of 1.8 GB" is what this used to say, and for a state
            // missing two cells out of sixty-eight the two numbers round to
            // look identical. What the operator can act on is the shortfall.
            return Depot.bytes(heldBytes) + " installed · "
                    + Depot.bytes(Math.max(0L, fullBytes - heldBytes))
                    + " missing" + suffix;
        }

        /** "US" / "CA" rendered for a person. */
        public String countryName() {
            if ("US".equals(country))
                return "United States";
            if ("CA".equals(country))
                return "Canada";
            return country;
        }
    }

    /**
     * A base map: one MOBAC map source XML, a few hundred bytes, that ATAK reads
     * to draw live imagery and to cache areas for offline use.
     */
    public static final class BaseMap {
        public final String id;
        public final String name;
        public final String category;
        public final String description;
        public final String provider;
        public final String host;
        public final String rights;
        public final String maxZoom;
        public final String file;
        public final long bytes;
        public final String sha256;

        BaseMap(JSONObject o) {
            id = o.optString("id");
            if (!SOURCE_ID.matcher(id).matches())
                throw new IllegalArgumentException("invalid base map id: " + id);

            name = o.optString("name", id);
            category = o.optString("category", "Other");
            description = o.optString("description", "");
            provider = o.optString("provider", "");
            host = o.optString("host", "");
            rights = o.optString("rights", "unverified");
            maxZoom = o.optString("maxZoom", "");
            file = o.optString("file");
            bytes = o.optLong("bytes");
            sha256 = o.optString("sha256");

            if (file.isEmpty() || sha256.length() != 64)
                throw new IllegalArgumentException(
                        "base map " + id + " has no file or digest");
        }

        /** What the row says under the name: where the tiles come from. */
        public String describe() {
            final StringBuilder sb = new StringBuilder();
            if (!description.isEmpty())
                sb.append(description);
            else if (!host.isEmpty())
                sb.append(host);
            if (!maxZoom.isEmpty())
                sb.append(sb.length() > 0 ? " · " : "").append("zoom ")
                        .append(maxZoom);
            return sb.toString();
        }

        /**
         * The filename ATAK stores it under, and with it which reader picks the
         * source up: {@code .xml} is a MOBAC source, {@code .json} a streaming
         * config for the provider behind ATAK's own "TAK Maps".
         */
        public String fileName() {
            return id + (file.endsWith(".json") ? ".json" : ".xml");
        }
    }

    /** One elevation cell: a one-degree square, gzipped on the wire. */
    public static final class Cell {
        public final String key;
        public final long bytes;
        public final long gzBytes;
        public final String sha256;

        Cell(JSONObject o) {
            key = o.optString("key");
            if (!CELL_KEY.matcher(key).matches())
                throw new IllegalArgumentException("invalid cell key: " + key);

            bytes = o.optLong("bytes");
            gzBytes = o.optLong("gzBytes");
            sha256 = o.optString("sha256");

            if (sha256.length() != 64)
                throw new IllegalArgumentException(
                        "cell " + key + " has no usable digest");
        }

        /** "w116/n33.dt2" -> "n33.dt2", the name ATAK stores it under. */
        public String fileName() {
            return key.substring(key.indexOf('/') + 1);
        }

        /** "w116/n33.dt2" -> "w116", the longitude directory. */
        public String dirName() {
            return key.substring(0, key.indexOf('/'));
        }
    }

    /** A region's full cell list. */
    public static final class Manifest {
        public final String id;
        public final String name;
        public final List<Cell> cells;
        public final long downloadBytes;
        public final long installedBytes;

        Manifest(JSONObject o) {
            id = o.optString("id");
            name = o.optString("name", id);
            downloadBytes = o.optLong("downloadBytes");
            installedBytes = o.optLong("bytes");

            final List<Cell> list = new ArrayList<>();
            final JSONArray arr = o.optJSONArray("cells");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    final JSONObject c = arr.optJSONObject(i);
                    if (c == null)
                        continue;
                    // One malformed entry loses one cell, not the whole region.
                    try {
                        list.add(new Cell(c));
                    } catch (IllegalArgumentException bad) {
                        com.atakmap.coremap.log.Log.w("MapDepot",
                                "rejected cell in " + id + ": " + bad.getMessage());
                    }
                }
            }
            cells = Collections.unmodifiableList(list);
        }
    }

    /** Every region the depot offers, ordered for a human reading a list. */
    public static List<Region> parseCatalog(String json) throws Exception {
        final JSONObject root = new JSONObject(json);
        final JSONObject elevation = root.optJSONObject("elevation");
        if (elevation == null)
            throw new IllegalStateException("catalog has no elevation section");

        final JSONArray arr = elevation.optJSONArray("regions");
        if (arr == null)
            throw new IllegalStateException("catalog lists no regions");

        final List<Region> regions = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            final JSONObject o = arr.optJSONObject(i);
            if (o == null)
                continue;
            try {
                regions.add(new Region(o));
            } catch (IllegalArgumentException bad) {
                com.atakmap.coremap.log.Log.w("MapDepot",
                        "rejected catalog entry: " + bad.getMessage());
            }
        }

        // The operator is looking for a place name, so sort by country then name
        // rather than by whatever order the generator emitted.
        Collections.sort(regions, new Comparator<Region>() {
            @Override
            public int compare(Region a, Region b) {
                final int c = a.country.compareTo(b.country);
                return c != 0 ? c : a.name.compareToIgnoreCase(b.name);
            }
        });
        return regions;
    }

    /**
     * The base maps on offer, grouped the way the catalog groups them. Ordered by
     * category then name so the list reads like a menu rather than a directory.
     */
    /**
     * A Forest Service basemap package -- one administrative unit's worth of the
     * vector basemap that replaced the retired raster FSTopo service.
     *
     * The package itself is not on our depot. These are 55 MB to 1.4 GB each,
     * 35.5 GB for the set, and the Forest Service reissues them; mirroring would
     * cost storage to hand out a staler copy than the operator can get direct.
     * So the catalog carries the ArcGIS Online item id and nothing else.
     */
    public static final class Forest implements Package {
        public final String id;
        public final String name;
        public final String kind;
        public final long bytes;
        public final String agolId;

        Forest(JSONObject o) {
            id = o.optString("id");
            if (!SOURCE_ID.matcher(id).matches())
                throw new IllegalArgumentException("invalid forest id: " + id);

            // This becomes a URL path segment on a host we do not control, so it
            // is checked against the exact shape ArcGIS Online issues rather than
            // trusted because it arrived over https.
            agolId = o.optString("agolId");
            if (!AGOL_ID.matcher(agolId).matches())
                throw new IllegalArgumentException(
                        "forest " + id + " has no usable item id");

            name = o.optString("name", id);
            kind = o.optString("kind", "forest");
            bytes = o.optLong("bytes");
        }

        /** Where the package comes from. Built here so no caller can shape it. */
        @Override
        public String url() {
            return "https://www.arcgis.com/sharing/rest/content/items/"
                    + agolId + "/data";
        }

        @Override
        public String fileName() {
            return safeFileName(name, id, ".vtpk");
        }

        @Override
        public String legacyFileName() {
            return id + ".vtpk";
        }

        @Override
        public String destination() {
            return "imagery";
        }

        @Override
        public String describe() {
            return bytes > 0 ? Depot.bytes(bytes) : "size unknown";
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public long bytes() {
            return bytes;
        }
    }

    /**
     * Something the depot names but does not host: a large file fetched from the
     * agency that publishes it, dropped into ATAK's imagery folder.
     *
     * Forest basemaps and ranger district maps differ in almost nothing the
     * installer cares about, so they share one, and the download machinery is
     * written once.
     */
    public interface Package {
        String id();

        String name();

        /** Where the file comes from. Built by the implementation so no caller shapes it. */
        String url();

        /**
         * The filename ATAK stores it under.
         *
         * This is not housekeeping: ATAK names a package layer after its file and
         * nothing else -- {@code CompactCacheDatasetDescriptorSpi} passes
         * {@code file.getName()} straight through as the layer name -- so the
         * filename is what the operator reads in the layer list. The extension is
         * also what the imagery scanner matches on, so it has to stay.
         */
        String fileName();

        /**
         * What earlier builds called the file, so an install made before the
         * names were readable is still recognised instead of downloaded again
         * under a new one.
         */
        String legacyFileName();

        /**
         * The directory under ATAK's root this belongs in, which decides what
         * kind of thing ATAK thinks it is.
         *
         * Not arbitrary: {@code ImageryFileTypeBase} assigns every format a path,
         * and that path is the classification. A {@code .pdf} is
         * {@code setPath("grg")} -- an image overlay that composites over
         * whatever base map the operator is already using. A {@code .vtpk} is
         * {@code setPath("native")} and belongs with the imagery, because it is a
         * base map in its own right. Putting a district map in the imagery folder
         * makes it something you switch to instead of something you switch on.
         */
        String destination();

        /** Size the server will send, or 0 when unknown. */
        long bytes();

        /** The line under the name in a row. */
        String describe();
    }

    /**
     * A Forest Service ranger district map -- the GeoPDF visitor map handed out
     * one at a time by the Forest Service Digital Maps site.
     *
     * ATAK registers PDF as an imagery type and bundles the GDAL PDF driver, and
     * these carry real OGC geospatial PDF structure, so one lands in the imagery
     * folder and scans in georeferenced with no conversion.
     */
    public static final class RecMap implements Package {
        public final String id;
        public final String name;
        public final String unit;
        public final String state;
        public final long bytes;
        private final String mapId;

        RecMap(JSONObject o, String series) {
            id = o.optString("id");
            if (!SOURCE_ID.matcher(id).matches())
                throw new IllegalArgumentException("invalid rec map id: " + id);

            // mapId goes into a query string on a host we do not control, so it
            // is held to printable ASCII without separators that could smuggle
            // another parameter in.
            mapId = o.optString("mapId");
            if (mapId.isEmpty() || !MAP_ID.matcher(mapId).matches())
                throw new IllegalArgumentException(
                        "rec map " + id + " has no usable map id");

            name = o.optString("name", id);
            unit = o.optString("unit", "");
            state = o.optString("state", "");
            bytes = o.optLong("bytes");

            // Held to the same shape as mapId, and for the same reason: it is
            // now per-map catalog data going into a query string on a host we
            // do not control. URLEncoder alone would stop a smuggled
            // parameter, but the pattern is what the rest of this file relies
            // on and an unvalidated field here would be the odd one out.
            if (series == null || !MAP_ID.matcher(series).matches())
                throw new IllegalArgumentException(
                        "rec map " + id + " has no usable series: " + series);
            this.series = series;
        }

        private final String series;

        @Override
        public String id() {
            return id;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String url() {
            return "https://data.fs.usda.gov/geodata/rastergateway/downloadMap.php"
                    + "?mapID=" + enc(mapId)
                    + "&mapType=pdf&seriesType=" + enc(series);
        }

        @Override
        public String fileName() {
            return safeFileName(name, id, ".pdf");
        }

        @Override
        public String legacyFileName() {
            return id + ".pdf";
        }

        @Override
        public String destination() {
            return "grg";
        }

        @Override
        public long bytes() {
            return bytes;
        }

        @Override
        public String describe() {
            final StringBuilder sb = new StringBuilder();
            if (!unit.isEmpty())
                sb.append(spaceCommas(unit));
            if (!state.isEmpty())
                sb.append(sb.length() > 0 ? " · " : "").append(state);
            sb.append(sb.length() > 0 ? " · " : "")
                    .append(bytes > 0 ? Depot.bytes(bytes) : "size unknown");
            return sb.toString();
        }

        /**
         * A district can span several forests, and the catalog lists them with
         * bare commas -- "Chattahoochee National Forest,Nantahala National
         * Forest". Everything else on the row is separated with a space, so the
         * run-together names are the one thing that looks like a mistake.
         *
         * Fixed here rather than in the catalog: the data is what the Forest
         * Service publishes, and a display problem belongs in the display.
         */
        private static String spaceCommas(String s) {
            return s.replaceAll(",\\s*", ", ");
        }
    }

    /**
     * A display name reduced to something safe to write to disk, keeping the
     * spaces and periods that make it readable ("Mt. Hood National Forest").
     * Anything outside the allowed set is dropped rather than substituted, so two
     * different names cannot collapse onto the same file.
     */
    private static String safeFileName(String display, String id, String ext) {
        final StringBuilder sb = new StringBuilder(display.length());
        for (int i = 0; i < display.length(); i++) {
            final char c = display.charAt(i);
            final boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == ' ' || c == '.'
                    || c == '-' || c == '(' || c == ')';
            if (ok)
                sb.append(c);
        }
        final String cleaned = sb.toString().trim();
        return (cleaned.isEmpty() ? id : cleaned) + ext;
    }

    private static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException never) {
            throw new IllegalStateException(never);
        }
    }

    public static List<RecMap> parseRecMaps(String json) throws Exception {
        final JSONObject root = new JSONObject(json);
        final JSONObject section = root.optJSONObject("recmaps");
        if (section == null)
            return Collections.emptyList();

        // Section-level default, kept for a catalog that names the series once
        // for the whole list. Each map may override it, and most do: a
        // whole-forest sheet is series "Forest", a district sheet is series
        // "Ranger District", and the Forest Service gateway answers 204 No
        // Content for the wrong pairing rather than an error anyone could read.
        // Applying the section default to every map sent all 173 whole-forest
        // maps to the district series, so not one of them could ever download.
        final String fallbackSeries = section.optString("series", "Ranger District");
        final JSONArray arr = section.optJSONArray("maps");
        if (arr == null)
            return Collections.emptyList();

        final List<RecMap> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            final JSONObject o = arr.optJSONObject(i);
            if (o == null)
                continue;
            try {
                out.add(new RecMap(o, o.optString("series", fallbackSeries)));
            } catch (IllegalArgumentException bad) {
                com.atakmap.coremap.log.Log.w("MapDepot",
                        "rejected rec map: " + bad.getMessage());
            }
        }
        Collections.sort(out, new Comparator<RecMap>() {
            @Override
            public int compare(RecMap a, RecMap b) {
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        return out;
    }

    public static List<Forest> parseForests(String json) throws Exception {
        final JSONObject root = new JSONObject(json);
        final JSONObject section = root.optJSONObject("forests");
        if (section == null)
            return Collections.emptyList();

        final JSONArray arr = section.optJSONArray("forests");
        if (arr == null)
            return Collections.emptyList();

        final List<Forest> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            final JSONObject o = arr.optJSONObject(i);
            if (o == null)
                continue;
            try {
                out.add(new Forest(o));
            } catch (IllegalArgumentException bad) {
                com.atakmap.coremap.log.Log.w("MapDepot",
                        "rejected forest: " + bad.getMessage());
            }
        }

        Collections.sort(out, new Comparator<Forest>() {
            @Override
            public int compare(Forest a, Forest b) {
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        return out;
    }

    public static List<BaseMap> parseBaseMaps(String json) throws Exception {
        final JSONObject root = new JSONObject(json);
        final JSONObject section = root.optJSONObject("basemaps");
        if (section == null)
            return Collections.emptyList();

        final JSONArray arr = section.optJSONArray("sources");
        if (arr == null)
            return Collections.emptyList();

        final List<BaseMap> maps = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            final JSONObject o = arr.optJSONObject(i);
            if (o == null)
                continue;
            try {
                maps.add(new BaseMap(o));
            } catch (IllegalArgumentException bad) {
                com.atakmap.coremap.log.Log.w("MapDepot",
                        "rejected base map: " + bad.getMessage());
            }
        }

        Collections.sort(maps, new Comparator<BaseMap>() {
            @Override
            public int compare(BaseMap a, BaseMap b) {
                final int c = a.category.compareToIgnoreCase(b.category);
                return c != 0 ? c : a.name.compareToIgnoreCase(b.name);
            }
        });
        return maps;
    }

    public static Manifest parseManifest(String json) throws Exception {
        return new Manifest(new JSONObject(json));
    }

    /** Sizes an operator can act on: "679 MB", "1.7 GB". */
    public static String bytes(long n) {
        if (n >= 1024L * 1024L * 1024L)
            return String.format("%.1f GB", n / (1024.0 * 1024.0 * 1024.0));
        if (n >= 1024L * 1024L)
            return String.format("%.0f MB", n / (1024.0 * 1024.0));
        if (n >= 1024L)
            return String.format("%.0f KB", n / 1024.0);
        return n + " B";
    }
}
