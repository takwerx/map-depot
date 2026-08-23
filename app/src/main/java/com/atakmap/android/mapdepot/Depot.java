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
    /** ArcGIS Online issues 32 hex characters and nothing else. */
    private static final Pattern AGOL_ID = Pattern.compile("[0-9a-f]{32}");

    private static final Pattern SOURCE_ID =
            Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");

    /** A downloadable region — a US state or a Canadian province. */
    public static final class Region {
        public final String id;
        public final String name;
        public final String country;
        public final String manifestPath;
        public final int cellCount;
        public final int needCount;
        public final boolean complete;
        public final long downloadBytes;
        public final long installedBytes;

        Region(JSONObject o) {
            id = o.optString("id");
            name = o.optString("name", id);
            country = o.optString("country", "");
            manifestPath = o.optString("manifest");
            cellCount = o.optInt("cellCount");
            needCount = o.optInt("needCount", o.optInt("cellCount"));
            complete = o.optBoolean("complete", false);
            downloadBytes = o.optLong("downloadBytes");
            installedBytes = o.optLong("bytes");

            if (id.isEmpty() || manifestPath.isEmpty())
                throw new IllegalArgumentException("region missing id or manifest");
        }

        /**
         * What the operator is deciding on is whether it fits on the device, so
         * the row shows the installed size and nothing else. Cell counts and
         * compressed transfer sizes are engineering detail; a partial region says
         * "Partial" rather than quoting a fraction nobody can act on.
         */
        public String describe() {
            final String size = Depot.bytes(installedBytes);
            return complete ? size : size + " · " + "Partial";
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

        /** The filename ATAK stores it under. */
        public String fileName() {
            return id + ".xml";
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
    public static final class Forest {
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
        public String url() {
            return "https://www.arcgis.com/sharing/rest/content/items/"
                    + agolId + "/data";
        }

        /** The filename ATAK stores it under; the extension is what it scans for. */
        public String fileName() {
            return id + ".vtpk";
        }

        public String describe() {
            return bytes > 0 ? Depot.bytes(bytes) : "size unknown";
        }
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
