
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
 * Turns a NIFC posting's filename into something an operator can read on a phone.
 *
 * The server's own names are built for a GIS shop's file browser, not for a
 * 5" screen in a truck:
 *
 * <pre>
 * ops_arch_e_port_20260828_0115_RoweCreekComplex_ORPRD000491_0828day.pdf
 *   -&gt; OR-PRD-ROWE-CREEK-COMPLEX-MAP-OPS-082826.pdf
 * </pre>
 *
 * Target shape, hyphens throughout including before the date:
 *
 * <pre>
 * [ST-UNIT-]INCIDENT-MAP-TYPE[-QUALIFIER]-MMDDYY.ext
 * </pre>
 *
 * <h3>Parse outward from the date, and get the date from wherever it is</h3>
 *
 * The product vocabulary is not stable, so field position cannot be trusted. The
 * same fire writes {@code ops_div_...} in one folder and {@code div_ops_...} in
 * another, the paper size varies across {@code arch_e}, {@code arch_c},
 * {@code 11x17}, {@code two_panel} and {@code combined}, and some postings lead
 * with the date instead of the type. So the {@code YYYYMMDD} token is the anchor:
 * before it is product type plus paper size plus orientation, after it is time,
 * incident, unit ID, operational period and qualifiers.
 *
 * Measured against 978 real postings, though, **272 of them carry no such token
 * at all** -- {@code brief_MilePost16_0714day.pdf} has only an operational
 * period, {@code ops_Div_A_breakout_..._2026_Biscar_CANOD004474.pdf} has only a
 * year, and {@code AirOps.pdf} has nothing. So the date is taken from the first
 * of these that answers:
 *
 * <ol>
 * <li>a {@code YYYYMMDD} token in the name</li>
 * <li>an operational period ({@code 0714day}), with the year from the name or
 *     the incident folder</li>
 * <li>a {@code YYYYMMDD} folder the file sits in</li>
 * <li>the listing's own Last-Modified date, which the server gives for every
 *     file and which therefore always answers</li>
 * </ol>
 *
 * <h3>The operational period wins over the posting time</h3>
 *
 * {@code ops_arch_c_land_20260818_2308_Mukluk_AKTAS613487_0819day.pdf} was
 * plotted at 23:08 on the 18th for the shift on the 19th. It is tomorrow's map
 * and it is named for tomorrow. Where the two agree -- which is most of the time
 * -- this changes nothing.
 *
 * <h3>Why the incident name is a parameter and not parsed out of the filename</h3>
 *
 * One fire, one server, three spellings: {@code RoweCreekComplex} in the KMZ,
 * {@code RoweCreekCopmlex} in all ten IR PDFs (a transposition typo, theirs), and
 * {@code RoweCreek} under GIS. The containing folder is consistent and
 * authoritative, so the caller passes it in. Parsing the name out of the file
 * would bake their typo into the operator's map library.
 *
 * <h3>Why {@link #translateAll} exists, and why per-file translation is not enough</h3>
 *
 * Because the operator can tick any two files, any two files must be able to
 * coexist on disk. Dropping paper size and posting time -- which say nothing to
 * an operator on their own -- collapses genuinely different files onto one name:
 * the same Mukluk ops map posted at 14:50, 17:07 and 17:11, and again as both
 * {@code arch_c} and {@code arch_e}, is five files and one name. Across the
 * 978-posting sample that is 152 files eating each other.
 *
 * So the discriminators are not discarded, they are held back and spent only
 * where a listing actually needs them: a name is built plain, and only if
 * something else in the same folder claims it does it grow a paper size, then a
 * time, then a number. Nothing pays for a distinction it does not need.
 */
public final class NifcNaming {

    private NifcNaming() {
    }

    /** The anchor. Any {@code YYYYMMDD} in a plausible year range. */
    private static final Pattern DATE = Pattern
            .compile("^(20\\d{2})(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])$");

    /** A bare year, which some postings carry instead of a date. */
    private static final Pattern YEAR = Pattern.compile("^20\\d{2}$");

    /**
     * A dispatch/unit identifier such as {@code ORPRD000491} or
     * {@code OR953S000587}: a state and unit code, then the incident number.
     * The incident number is dropped and the code kept, so the name says which
     * outfit's map it is without carrying a dispatch serial an operator will
     * never read.
     *
     * Deliberately looser than "two letters then three": the unit code is not
     * always three letters and is not always letters. Six real variants in the
     * sample -- {@code OR953S000587}, {@code DFFP611234}, {@code PRD000497},
     * {@code SUF002394}, {@code HIA000583}, {@code YAA000090} -- were missed by
     * the stricter rule and leaked the whole raw identifier into the name.
     *
     * So the trailing run of digits is the incident number and what precedes it
     * is the code, split by {@link #claimIdentifier}.
     */
    private static final Pattern UNIT_ID = Pattern
            .compile("^([A-Za-z][A-Za-z0-9]*?)(\\d{5,7})$");

    /** {@code 0115}, the plot time. */
    private static final Pattern TIME = Pattern.compile("^\\d{3,4}$");

    /** {@code 0828day}, the operational period this map is for. */
    private static final Pattern OP_PERIOD = Pattern
            .compile("^(\\d{2})(\\d{2})(?:day|night|am|pm)$",
                    Pattern.CASE_INSENSITIVE);

    /**
     * Paper size and orientation. These describe the sheet, not the map, so they
     * are dropped -- unless two files in one folder differ only by them, in which
     * case the size is the only thing telling them apart and it comes back.
     */
    private static final Set<String> SIZE = new HashSet<>(Arrays.asList(
            "arch", "ansi", "letter", "tabloid", "ledger",
            "11x17", "11by17", "8x11", "8by11", "85x11", "17x22", "22x34",
            "24x36", "36x48", "34x44", "30x42",
            "two", "panel", "twopanel", "combined", "multi", "multipanel"));

    private static final Set<String> ORIENT = new HashSet<>(Arrays.asList(
            "port", "portrait", "land", "landscape"));

    /**
     * How a raster was rendered, as opposed to which piece of ground it covers.
     * Kept, but ordered after the area: an operator looks for Crosswhite first
     * and only then decides between the ortho and the topo of it.
     */
    private static final Set<String> RENDERING = new HashSet<>(Arrays.asList(
            "ortho", "topo", "topographic", "orthophoto", "hillshade"));

    /**
     * Tokens that carry no information once the rest of the name is built.
     * {@code day}/{@code night} survive separately as part of the op period.
     */
    private static final Set<String> NOISE = new HashSet<>(Arrays.asList(
            "map", "maps", "final", "draft", "copy", "new", "v1", "v2",
            "incident", "fire",
            // left behind when a posting is named "....pdf.pdf", which happens
            "pdf", "kmz", "zip"));

    // ------------------------------------------------------------------ API

    /**
     * One file, with no listing to compare against. Convenience for callers that
     * genuinely have a single name; prefer {@link #translateAll} anywhere a whole
     * folder is on screen, because only that can guarantee two files do not land
     * on one name.
     */
    public static String translate(String fileName, String incidentFolder) {
        return translate(fileName, incidentFolder, null, null);
    }

    /**
     * @param fileName the posting's own name, already URL-decoded
     * @param incidentFolder the containing incident folder, e.g.
     *        {@code 2026_RoweCreekComplex}; may be null
     * @param folderPath the product folder below the incident, e.g.
     *        {@code IR/20260721}; may be null. Read only for a date.
     * @param modified the listing's Last-Modified text, e.g.
     *        {@code 2026-08-28 03:28}; may be null. The last resort for a date.
     */
    public static String translate(String fileName, String incidentFolder,
            String folderPath, String modified) {
        final Parts p = parse(fileName, incidentFolder, folderPath, modified);
        return p == null ? fileName : render(p, false, false);
    }

    /**
     * Translates a whole listing at once, guaranteeing distinct results.
     *
     * @return original name to translated name, in the order given
     */
    public static Map<String, String> translateAll(Collection<String> fileNames,
            String incidentFolder, String folderPath,
            Map<String, String> modifiedByName) {

        final Map<String, String> out = new LinkedHashMap<>();
        final Set<String> taken = new HashSet<>();

        for (final String file : fileNames) {
            final String modified = modifiedByName == null ? null
                    : modifiedByName.get(file);
            final Parts p = parse(file, incidentFolder, folderPath, modified);

            if (p == null) {
                out.put(file, unique(file, taken));
                continue;
            }

            // Spend a discriminator only when the plain name is already claimed.
            String name = render(p, false, false);
            if (taken.contains(lower(name)))
                name = render(p, true, false);
            if (taken.contains(lower(name)))
                name = render(p, true, true);

            out.put(file, unique(name, taken));
        }
        return out;
    }

    /**
     * The incident folder, less its year prefix, as hyphenated caps.
     * {@code 2026_RoweCreekComplex} to {@code ROWE-CREEK-COMPLEX}.
     */
    public static String incidentName(String folder) {
        if (folder == null || folder.isEmpty())
            return null;
        String s = folder.trim();
        while (s.endsWith("/"))
            s = s.substring(0, s.length() - 1);
        final int slash = s.lastIndexOf('/');
        if (slash >= 0)
            s = s.substring(slash + 1);
        s = s.replaceFirst("^\\d{4}[_\\-\\s]+", "");
        final String name = join(tokenize(s));
        return name.isEmpty() ? null : name;
    }

    // -------------------------------------------------------------- parsing

    /** Everything pulled out of one filename, before a name is rendered from it. */
    private static final class Parts {
        String state;
        String unit;
        List<String> incident = new ArrayList<>();
        List<String> type = new ArrayList<>();
        List<String> areas = new ArrayList<>();
        List<String> renders = new ArrayList<>();
        List<String> size = new ArrayList<>();
        String time;
        String mmddyy;
        String ext;
    }

    private static Parts parse(String fileName, String incidentFolder,
            String folderPath, String modified) {

        if (fileName == null || fileName.isEmpty())
            return null;

        final int dot = fileName.lastIndexOf('.');
        if (dot <= 0)
            return null;

        final Parts p = new Parts();
        p.ext = fileName.substring(dot + 1).toLowerCase(Locale.US);

        final List<String> tokens = tokenize(fileName.substring(0, dot));
        if (tokens.isEmpty())
            return null;

        final String incident = incidentName(incidentFolder);
        if (incident != null)
            p.incident.addAll(Arrays.asList(incident.split("-")));

        // ---- find the date anchor, if the name has one
        int dateAt = -1;
        String year = null;
        for (int i = 0; i < tokens.size(); i++) {
            final Matcher m = DATE.matcher(tokens.get(i));
            if (m.matches()) {
                dateAt = i;
                year = m.group(1);
                p.mmddyy = m.group(2) + m.group(3) + m.group(1).substring(2);
                break;
            }
        }

        // An operational period is only half a date. The year comes from a bare
        // year token if the posting carries one, and otherwise from the incident
        // folder, which always does -- so brief_MilePost16_0714day.pdf under
        // 2026_MilePost16 still dates to July 14th rather than losing its date.
        if (year == null) {
            for (final String t : tokens) {
                if (YEAR.matcher(t).matches()) {
                    year = t;
                    break;
                }
            }
        }
        if (year == null)
            year = yearOf(incidentFolder);

        final List<String> before;
        final List<String> after;
        if (dateAt >= 0) {
            before = new ArrayList<>(tokens.subList(0, dateAt));
            after = new ArrayList<>(tokens.subList(dateAt + 1, tokens.size()));
        } else {
            // No anchor: everything is fair game, and the date comes from
            // elsewhere. The type still leads, so treat the whole name as
            // "before" and let the classifier below sort it out.
            before = new ArrayList<>(tokens);
            after = new ArrayList<>();
        }

        // ---- classify what follows the date
        final List<String> tail = new ArrayList<>();
        for (final String raw : after) {
            if (claimIdentifier(p, raw, year))
                continue;
            tail.add(raw);
        }

        // ---- and, when there was no anchor, what precedes it too
        //
        // The incident's own name is dropped from the head either way:
        // "Pineland_Road_Portrait 11x17_20260501_Ortho.pdf" leads with the fire
        // name, and without this it is said twice.
        final List<String> head = new ArrayList<>();
        for (final String raw : before) {
            if (dateAt < 0 && claimIdentifier(p, raw, year))
                continue;
            if (dateAt >= 0 && isIncidentWord(raw, p.incident))
                continue;
            head.add(raw);
        }

        p.type.addAll(strip(p, head));
        final List<String> rest = strip(p, tail);

        // A date-led posting (the IR pattern) carries its type after the date
        // instead of before it. First surviving token is the type.
        if (p.type.isEmpty() && !rest.isEmpty())
            p.type.add(rest.remove(0));

        for (final String t : rest) {
            if (RENDERING.contains(t.toLowerCase(Locale.US)))
                p.renders.add(t);
            else
                p.areas.add(t);
        }

        if (p.mmddyy == null)
            p.mmddyy = dateFromFolder(folderPath);
        if (p.mmddyy == null)
            p.mmddyy = dateFromModified(modified);

        // Nothing left to say about it. Their name is better than "MAP".
        if (p.type.isEmpty() && p.areas.isEmpty() && p.renders.isEmpty())
            return null;

        return p;
    }

    /**
     * Recognizes the tokens that are identifiers rather than description --
     * unit ID, plot time, operational period, the incident's own name, a bare
     * year -- and files them on {@code p}. Returns true when the token was
     * claimed and should not reach the type.
     */
    private static boolean claimIdentifier(Parts p, String raw, String year) {
        final Matcher u = UNIT_ID.matcher(raw);
        if (u.matches()) {
            final String code = u.group(1).toUpperCase(Locale.US);
            // Five or more means state and unit are both in there
            // (ORPRD -> OR + PRD, OR953S -> OR + 953S). Shorter is a unit on
            // its own, which some GACCs post without a state at all.
            if (code.length() >= 5) {
                p.state = code.substring(0, 2);
                p.unit = code.substring(2);
            } else if (code.length() >= 3) {
                p.unit = code;
            } else {
                p.state = code;
            }
            return true;
        }
        final Matcher op = OP_PERIOD.matcher(raw);
        if (op.matches()) {
            // The period this map is for beats the moment it was plotted.
            final String yy = year != null ? year.substring(2) : null;
            if (yy != null)
                p.mmddyy = op.group(1) + op.group(2) + yy;
            return true;
        }
        if (TIME.matcher(raw).matches()) {
            if (p.time == null)
                p.time = raw;
            return true;
        }
        if (YEAR.matcher(raw).matches())
            return true;
        return isIncidentWord(raw, p.incident);
    }

    /** {@code 2026_RoweCreekComplex} to {@code 2026}. */
    private static String yearOf(String incidentFolder) {
        if (incidentFolder == null)
            return null;
        final Matcher m = Pattern.compile("(20\\d{2})").matcher(incidentFolder);
        return m.find() ? m.group(1) : null;
    }

    /** {@code IR/20260721} to {@code 072126}. */
    private static String dateFromFolder(String folderPath) {
        if (folderPath == null)
            return null;
        final Matcher m = Pattern.compile("(20\\d{2})(\\d{2})(\\d{2})")
                .matcher(folderPath);
        if (!m.find())
            return null;
        return m.group(2) + m.group(3) + m.group(1).substring(2);
    }

    /** {@code 2026-08-28 03:28} to {@code 082826}. */
    private static String dateFromModified(String modified) {
        if (modified == null)
            return null;
        final Matcher m = Pattern.compile("(20\\d{2})-(\\d{2})-(\\d{2})")
                .matcher(modified);
        if (!m.find())
            return null;
        return m.group(2) + m.group(3) + m.group(1).substring(2);
    }

    // ------------------------------------------------------------ rendering

    private static String render(Parts p, boolean withSize, boolean withTime) {
        final List<String> out = new ArrayList<>();
        if (p.state != null)
            out.add(p.state);
        if (p.unit != null)
            out.add(p.unit);
        out.addAll(p.incident);
        out.add("MAP");
        out.addAll(p.type);
        out.addAll(p.areas);
        out.addAll(p.renders);
        if (withSize)
            out.addAll(p.size);
        if (withTime && p.time != null)
            out.add(p.time);
        if (p.mmddyy != null)
            out.add(p.mmddyy);

        final String name = join(out);
        return name.isEmpty() ? null : name + "." + p.ext;
    }

    /**
     * Last resort. A name that is still claimed after size and time have been
     * spent gets a number, because losing a file is worse than an ugly name.
     */
    private static String unique(String name, Set<String> taken) {
        if (name == null)
            return null;
        if (!taken.contains(lower(name))) {
            taken.add(lower(name));
            return name;
        }
        final int dot = name.lastIndexOf('.');
        final String stem = dot > 0 ? name.substring(0, dot) : name;
        final String ext = dot > 0 ? name.substring(dot) : "";
        for (int n = 2; n < 1000; n++) {
            final String candidate = stem + "-" + n + ext;
            if (!taken.contains(lower(candidate))) {
                taken.add(lower(candidate));
                return candidate;
            }
        }
        taken.add(lower(name));
        return name;
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.US);
    }

    // -------------------------------------------------------------- tokens

    /**
     * Splits on the separators these postings actually use -- underscore, space,
     * hyphen and dot -- so {@code DIV B} and {@code DIV_B} land in the same
     * place, and a doubled extension ({@code ..._0801day.pdf.pdf}, which is real)
     * does not weld itself onto the operational period.
     * CamelCase runs are split too, which is what turns {@code RoweCreekComplex}
     * into three words.
     */
    private static List<String> tokenize(String s) {
        final List<String> out = new ArrayList<>();
        for (final String part : s.split("[_\\s\\-.]+")) {
            if (part.isEmpty())
                continue;
            for (final String w : splitCamel(part)) {
                if (!w.isEmpty())
                    out.add(w);
            }
        }
        return out;
    }

    /**
     * {@code RoweCreekComplex} to {@code Rowe Creek Complex}, while leaving
     * {@code ORPRD000491} and {@code 11x17} alone -- an all-caps run or a
     * digit-bearing identifier is a code, not prose.
     */
    private static String[] splitCamel(String s) {
        if (s.equals(s.toUpperCase(Locale.US))
                || s.equals(s.toLowerCase(Locale.US)))
            return new String[] {
                    s
            };
        if (UNIT_ID.matcher(s).matches())
            return new String[] {
                    s
            };
        // 0829Day is one operational period, not "0829" and "Day".
        if (OP_PERIOD.matcher(s).matches())
            return new String[] {
                    s
            };
        return s.split("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Za-z])(?=\\d{3,})");
    }

    /**
     * Moves paper size, orientation and noise off the type and onto
     * {@code p.size}, where they wait in case the folder turns out to need them.
     * {@code arch} takes the single letter after it with it, because
     * {@code arch_e} is one sheet size split across two tokens.
     */
    private static List<String> strip(Parts p, List<String> tokens) {
        final List<String> out = new ArrayList<>();
        boolean afterArch = false;
        for (final String raw : tokens) {
            final String t = raw.toLowerCase(Locale.US);
            if (afterArch && t.length() == 1 && Character.isLetter(t.charAt(0))) {
                afterArch = false;
                p.size.add(raw);
                continue;
            }
            afterArch = "arch".equals(t) || "ansi".equals(t);
            if (SIZE.contains(t)) {
                p.size.add(raw);
                continue;
            }
            if (ORIENT.contains(t) || NOISE.contains(t))
                continue;
            out.add(raw);
        }
        return out;
    }

    private static boolean isIncidentWord(String token, List<String> words) {
        if (words.isEmpty())
            return false;
        for (final String w : words) {
            if (w.equalsIgnoreCase(token))
                return true;
        }
        // The whole incident run as one token, which is how the filename spells
        // it -- RoweCreekComplex against ROWE, CREEK, COMPLEX.
        final StringBuilder joined = new StringBuilder();
        for (final String w : words)
            joined.append(w);
        return joined.toString().equalsIgnoreCase(token.replace("-", ""));
    }

    /**
     * Uppercases, reduces anything that is not a letter or digit to a hyphen,
     * and refuses to repeat a word that is already there. The repeat check is
     * what keeps {@code ops_div ... _DIV B} from becoming {@code OPS-DIV-DIV-B}:
     * the type and the qualifier both legitimately say "div".
     */
    private static String join(List<String> parts) {
        final StringBuilder sb = new StringBuilder();
        String previous = null;
        for (final String raw : parts) {
            if (raw == null)
                continue;
            final String clean = raw.toUpperCase(Locale.US)
                    .replaceAll("[^A-Z0-9]+", "-")
                    .replaceAll("^-+|-+$", "");
            if (clean.isEmpty())
                continue;
            if (clean.equals(previous))
                continue;
            if (sb.length() > 0)
                sb.append('-');
            sb.append(clean);
            previous = clean;
        }
        return sb.toString();
    }
}
