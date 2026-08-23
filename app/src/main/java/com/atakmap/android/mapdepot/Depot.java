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
