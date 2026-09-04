package com.atakmap.android.mapdepot;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Names Beacon Box maps for a phone screen.
 *
 * <pre>
 * BB_Coldwater_Canyon_MonteCieloDr_Structure_Vulnerability.pdf
 *   -&gt; COLDWATER-CANYON-MONTE-CIELO-DR-STRUCTURE-VULNERABILITY.pdf
 * BB_Trousdale_Estates_NHillcrest_IMG.pdf
 *   -&gt; TROUSDALE-ESTATES-N-HILLCREST-AERIAL.pdf
 * </pre>
 *
 * The files are named by one person for a desktop: a {@code BB_} prefix,
 * underscores between some words and CamelCase between others, and a suffix
 * that says which of the four products it is. The rule here is the same one
 * the incident archives get -- parse what is there, do not guess at what is
 * not -- and the only vocabulary is the four suffixes. Everything else is
 * carried through, split and upper-cased, so a name the rule does not
 * understand still comes out readable rather than wrong.
 *
 * {@code grg/} is flat, so two files an operator can both tick must not land
 * on one name; {@link #translateAll} numbers a collision rather than losing
 * a map.
 */
public final class BeaconNaming {

    private BeaconNaming() {
    }

    /** The four products, by the suffix the files carry. */
    public static String kindOf(String fileName) {
        final String base = stem(fileName).toUpperCase(Locale.US);
        if (base.endsWith("_STRUCTURE_VULNERABILITY"))
            return "Structure vulnerability";
        if (base.endsWith("_FIRE_SCIENCE"))
            return "Fire science";
        if (base.endsWith("_IMG") || base.endsWith("_IMAGE")
                || base.contains("_IMAGE_"))
            return "Aerial";
        return "Base map";
    }

    public static String translate(String fileName) {
        String base = stem(fileName);
        if (base.regionMatches(true, 0, "BB_", 0, 3))
            base = base.substring(3);

        final StringBuilder out = new StringBuilder();
        for (final String token : base.split("_+")) {
            if (token.isEmpty())
                continue;
            for (final String word : splitCamel(token)) {
                if (out.length() > 0)
                    out.append('-');
                out.append(word.toUpperCase(Locale.US));
            }
        }
        String name = out.toString();
        // The suffix vocabulary, normalized so the four products read alike.
        if (name.endsWith("-IMG") || name.endsWith("-IMAGE"))
            name = name.substring(0, name.lastIndexOf('-')) + "-AERIAL";
        if (name.isEmpty())
            name = "BEACON-BOX";
        return name + extension(fileName);
    }

    /** Every name unique, in the order given. */
    public static Map<String, String> translateAll(Collection<String> fileNames) {
        final Map<String, String> out = new LinkedHashMap<>();
        final Set<String> taken = new HashSet<>();
        for (final String f : fileNames) {
            String name = translate(f);
            if (taken.contains(name)) {
                final int dot = name.lastIndexOf('.');
                final String stem = name.substring(0, dot);
                final String ext = name.substring(dot);
                int n = 2;
                while (taken.contains(stem + "-" + n + ext))
                    n++;
                name = stem + "-" + n + ext;
            }
            taken.add(name);
            out.put(f, name);
        }
        return out;
    }

    /**
     * {@code MonteCieloDr} to Monte, Cielo, Dr; {@code NHillcrest} to N,
     * Hillcrest; {@code D1} stays D1. A boundary sits between a lower-case
     * letter or digit and an upper-case one, and between an upper-case letter
     * and an upper-case letter that starts a lower-case run.
     */
    static List<String> splitCamel(String token) {
        final List<String> words = new ArrayList<>();
        int start = 0;
        for (int i = 1; i < token.length(); i++) {
            final char prev = token.charAt(i - 1);
            final char c = token.charAt(i);
            final boolean lowerToUpper = Character.isUpperCase(c)
                    && (Character.isLowerCase(prev) || Character.isDigit(prev));
            final boolean upperRunEnds = Character.isUpperCase(c)
                    && Character.isUpperCase(prev)
                    && i + 1 < token.length()
                    && Character.isLowerCase(token.charAt(i + 1));
            if (lowerToUpper || upperRunEnds) {
                words.add(token.substring(start, i));
                start = i;
            }
        }
        words.add(token.substring(start));
        return words;
    }

    private static String stem(String fileName) {
        final int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static String extension(String fileName) {
        final int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(dot).toLowerCase(Locale.US) : "";
    }
}
