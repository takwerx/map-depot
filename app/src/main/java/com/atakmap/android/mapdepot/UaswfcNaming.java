
package com.atakmap.android.mapdepot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Names UASWFC drone products for a phone screen.
 *
 * <pre>
 * 20260810_Bologna_UAS_IR_11x17_Aerial_141.pdf
 *   -&gt; BOLOGNA-MAP-UAS-IR-AERIAL-141-081026.pdf
 * </pre>
 *
 * Separate from {@link NifcNaming} because the two archives write names
 * differently enough that one parser would serve neither well. NIFC leads with a
 * product type and hides the date in the middle; UASWFC always leads with the
 * date and separates every word with an underscore, so there is no CamelCase to
 * split and no vocabulary to guess at. What the two do share is the shape of the
 * result and the rule that two files an operator can both tick must both survive
 * on disk.
 *
 * <h3>The date in the name is when the imagery was acquired</h3>
 *
 * A sortie flown on the 28th is often posted on the 29th, so the filename and
 * its folder disagree. The name follows the filename: the flight date is the day
 * the ground looked like this, and the day it reached a web server is not
 * something a crew asks about.
 *
 * One consequence, deliberately accepted. Fifteen files in the archive are the
 * same flight reprocessed and posted again the next day, so they share a name:
 *
 * <pre>
 * IR/20260817/20260817_Bologna_UAS_IR_11x17_Aerial.pdf   3,529,506 bytes
 * IR/20260818/20260817_Bologna_UAS_IR_11x17_Aerial.pdf   3,355,390 bytes
 * </pre>
 *
 * {@code grg/} is flat, so taking both replaces the first with the second. That
 * is the right way round -- the later posting is the reprocessed one -- and it is
 * the same map of the same flight either way.
 *
 * <h3>The sortie number is part of the name, not a tiebreaker</h3>
 *
 * A fire flown twice in a day posts {@code _141} and {@code _142}, and a crew
 * asks for a sortie by that number. It is always kept, rather than being spent
 * only when a collision forces it.
 */
public final class UaswfcNaming {

    private UaswfcNaming() {
    }

    /** Every posting leads with the flight date. */
    private static final Pattern LEADING_DATE = Pattern
            .compile("^(20\\d{2})(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])$");

    /** A trailing sortie number: two to four digits at the end of the name. */
    private static final Pattern SORTIE = Pattern.compile("^\\d{1,4}$");

    /**
     * Sheet size and orientation, which describe the paper rather than the map.
     * Dropped, as they are for NIFC.
     */
    private static final Set<String> SIZE = new HashSet<>(Arrays.asList(
            "11x17", "11by17", "8x11", "8by11", "85x11", "17x22", "22x34",
            "24x36", "36x48", "arch", "ansi", "letter", "tabloid",
            "port", "portrait", "land", "landscape"));

    /**
     * A sheet size however it is written, including the spellings that run the
     * size and the orientation together -- {@code 11x17land}, {@code 8x11port}.
     * The set above only knows them apart, so those came through into the name.
     */
    private static final Pattern SHEET = Pattern.compile(
            "^\\d{1,2}(x|by)\\d{1,2}(land|port|landscape|portrait)?$",
            Pattern.CASE_INSENSITIVE);

    /**
     * The product. These are the words that decide what an operator is looking
     * at, so they are kept and ordered last before the dates.
     */
    private static final Set<String> PRODUCT = new HashSet<>(Arrays.asList(
            "aerial", "topo", "topographic", "ortho", "orthophoto"));

    /**
     * Fire names are spelled one way in the folder and another in the filename:
     * {@code 2026_FielderMtn} against {@code Fielder_Mountain}, so "Mountain"
     * survives as though it were a separate word and the name reads
     * FIELDER-MTN-MAP-MOUNTAIN. These are the contractions the archive actually
     * uses; anything not here simply stays, which is the safe direction.
     */
    private static final Map<String, String> ABBREVIATION = new LinkedHashMap<>();
    static {
        ABBREVIATION.put("MTN", "MOUNTAIN");
        ABBREVIATION.put("MT", "MOUNT");
        ABBREVIATION.put("CRK", "CREEK");
        ABBREVIATION.put("CYN", "CANYON");
        ABBREVIATION.put("RD", "ROAD");
        ABBREVIATION.put("CO", "COUNTY");
        ABBREVIATION.put("SPR", "SPRING");
        ABBREVIATION.put("PT", "POINT");
    }

    /** Says nothing once the fire's own name is already in the result. */
    private static final Set<String> NOISE = new HashSet<>(Arrays.asList(
            "fire", "complex", "incident", "map", "maps", "final", "copy",
            "pdf", "kmz", "zip"));

    /**
     * @param fileName the posting's own name
     * @param incidentFolder the fire's folder, e.g. {@code 2026_RoweCreekComplex}
     * @param folderPath the path below the fire, e.g. {@code IR/20260729}.
     *        Unused: the name takes its date from the filename, which is when
     *        the sortie flew. Kept so the signature does not change if a later
     *        rule needs the folder again.
     */
    public static String translate(String fileName, String incidentFolder,
            String folderPath) {
        final Parts p = parse(fileName, incidentFolder, folderPath);
        return p == null ? fileName : render(p);
    }

    /**
     * Translates a whole folder at once so no two results collide. The sortie
     * number already makes that almost impossible here, but "almost" is not a
     * guarantee and losing a map is not an acceptable failure.
     */
    public static Map<String, String> translateAll(Collection<String> fileNames,
            String incidentFolder, String folderPath) {

        final Map<String, String> out = new LinkedHashMap<>();
        final Set<String> taken = new HashSet<>();
        for (final String file : fileNames) {
            final Parts p = parse(file, incidentFolder, folderPath);
            out.put(file, unique(p == null ? file : render(p), taken));
        }
        return out;
    }

    /**
     * Whether this is a flight log rather than a map.
     *
     * Read from the filename, not from the server's {@code kind} field. The API
     * classifies the same product differently depending on whether it carries a
     * sortie number -- of 146 flight logs in the archive, {@code kind} labels 76
     * of them {@code "log"} and the other 70 {@code "other"}. Trusting it files
     * 70 flight logs into {@code grg/} as if they were georeferenced maps.
     */
    public static boolean isFlightLog(String fileName) {
        return fileName != null
                && fileName.toLowerCase(Locale.US).contains("_log");
    }

    // -------------------------------------------------------------- internals

    private static final class Parts {
        final List<String> incident = new ArrayList<>();
        final List<String> words = new ArrayList<>();
        final List<String> product = new ArrayList<>();
        String sortie;
        String flown;
        String ext;
    }

    private static Parts parse(String fileName, String incidentFolder,
            String folderPath) {

        if (fileName == null || fileName.isEmpty())
            return null;
        final int dot = fileName.lastIndexOf('.');
        if (dot <= 0)
            return null;

        final Parts p = new Parts();
        p.ext = fileName.substring(dot + 1).toLowerCase(Locale.US);

        final String[] tokens = fileName.substring(0, dot).split("[_\\s\\-.]+");
        if (tokens.length == 0)
            return null;

        final Matcher d = LEADING_DATE.matcher(tokens[0]);
        if (!d.matches())
            return null;
        p.flown = d.group(2) + d.group(3) + d.group(1).substring(2);

        final String incident = NifcNaming.incidentName(incidentFolder);
        if (incident != null)
            p.incident.addAll(Arrays.asList(incident.split("-")));

        for (int i = 1; i < tokens.length; i++) {
            final String raw = tokens[i];
            if (raw.isEmpty())
                continue;
            final String t = raw.toLowerCase(Locale.US);

            // The fire's own name is already carried by the incident folder,
            // which is authoritative; the filename's spelling of it is not.
            if (isIncidentWord(raw, p.incident))
                continue;
            if (SIZE.contains(t) || NOISE.contains(t)
                    || SHEET.matcher(raw).matches())
                continue;
            if (PRODUCT.contains(t)) {
                p.product.add(raw);
                continue;
            }
            if (i == tokens.length - 1 && SORTIE.matcher(raw).matches()) {
                p.sortie = raw;
                continue;
            }
            p.words.add(raw);
        }

        if (p.words.isEmpty() && p.product.isEmpty())
            return null;
        return p;
    }

    /**
     * The word that says what kind of thing this is. A PDF is a raster map and
     * lands in {@code grg/}; a KMZ is a vector overlay and lands in
     * {@code overlays/}. They behave differently on the map, so the name says
     * which one an operator is about to get.
     */
    private static String kindWord(String ext) {
        return "kmz".equals(ext) || "kml".equals(ext) ? "OVERLAY" : "MAP";
    }

    private static String render(Parts p) {
        final List<String> out = new ArrayList<>(p.incident);
        out.add(kindWord(p.ext));
        out.addAll(p.words);
        out.addAll(p.product);
        if (p.sortie != null)
            out.add(p.sortie);
        out.add(p.flown);
        final String name = join(out);
        return name.isEmpty() ? null : name + "." + p.ext;
    }

    /**
     * Whether this filename token is just the fire saying its own name again.
     *
     * Three ways it can be, all seen in the archive: written identically, written
     * out where the folder contracts it ({@code Fielder_Mountain} against
     * {@code FielderMtn}), or written short where the folder runs it together
     * ({@code I5_Fire} against {@code I5MM57NB}). The folder is authoritative
     * either way, so a match here means the token is dropped.
     */
    private static boolean isIncidentWord(String token, List<String> words) {
        if (words.isEmpty())
            return false;
        final String t = token.toUpperCase(Locale.US);
        for (final String w : words) {
            if (w.equalsIgnoreCase(token))
                return true;
            // The folder contracts what the filename spells out.
            final String expanded = ABBREVIATION.get(w);
            if (expanded != null && expanded.equals(t))
                return true;
            // The folder runs the name together and the filename gives a piece.
            if (t.length() >= 2 && w.length() > t.length() && w.startsWith(t))
                return true;
        }
        final StringBuilder joined = new StringBuilder();
        for (final String w : words)
            joined.append(w);
        return joined.toString().equalsIgnoreCase(token.replace("-", ""));
    }

    private static String unique(String name, Set<String> taken) {
        if (name == null)
            return null;
        final String key = name.toLowerCase(Locale.US);
        if (!taken.contains(key)) {
            taken.add(key);
            return name;
        }
        final int dot = name.lastIndexOf('.');
        final String stem = dot > 0 ? name.substring(0, dot) : name;
        final String ext = dot > 0 ? name.substring(dot) : "";
        for (int n = 2; n < 1000; n++) {
            final String candidate = stem + "-" + n + ext;
            if (!taken.contains(candidate.toLowerCase(Locale.US))) {
                taken.add(candidate.toLowerCase(Locale.US));
                return candidate;
            }
        }
        taken.add(key);
        return name;
    }

    /**
     * Uppercase, hyphenated, and never the same word twice.
     *
     * Not merely twice in a row: one real posting is named
     * {@code ..._UAS_IR_DIV_Y_X_North_UAS_IR_11x17_Aerial.pdf}, which says UAS
     * and IR at both ends and would otherwise read UAS-IR-DIV-Y-X-NORTH-UAS-IR.
     * A word already in the name adds nothing the second time.
     */
    private static String join(List<String> parts) {
        final StringBuilder sb = new StringBuilder();
        final Set<String> already = new HashSet<>();
        for (final String raw : parts) {
            if (raw == null)
                continue;
            final String clean = raw.toUpperCase(Locale.US)
                    .replaceAll("[^A-Z0-9]+", "-")
                    .replaceAll("^-+|-+$", "");
            if (clean.isEmpty() || !already.add(clean))
                continue;
            if (sb.length() > 0)
                sb.append('-');
            sb.append(clean);
        }
        return sb.toString();
    }
}
