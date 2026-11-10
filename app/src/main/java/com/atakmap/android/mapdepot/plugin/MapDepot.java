package com.atakmap.android.mapdepot.plugin;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.TextWatcher;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.atak.plugins.impl.PluginContextProvider;
import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.mapdepot.AtakBuild;
import com.atakmap.android.mapdepot.BaseMapInstaller;
import com.atakmap.android.mapdepot.BeaconClient;
import com.atakmap.android.mapdepot.Depot;
import com.atakmap.android.mapdepot.FlightOutlines;
import com.atakmap.android.mapdepot.DepotClient;
import com.atakmap.android.mapdepot.Distance;
import com.atakmap.android.mapdepot.InstalledIndex;
import com.atakmap.android.mapdepot.MapSource;
import com.atakmap.android.mapdepot.NifcClient;
import com.atakmap.android.mapdepot.UaswfcClient;
import com.atakmap.android.mapdepot.PackageInstaller;
import com.atakmap.android.mapdepot.Pinned;
import com.atakmap.android.mapdepot.RegionInstaller;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;

import java.io.File;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import gov.tak.api.plugin.IPlugin;
import gov.tak.api.plugin.IServiceController;
import gov.tak.api.ui.IHostUIService;
import gov.tak.api.ui.Pane;
import gov.tak.api.ui.PaneBuilder;
import gov.tak.api.ui.ToolbarItem;
import gov.tak.api.ui.ToolbarItemAdapter;
import gov.tak.platform.marshal.MarshalManager;

/**
 * Map Depot — map data arrives on the device from inside ATAK.
 *
 * Pick a region, tap Get, and its elevation cells land in ATAK's own DTED folder,
 * each one checksum-verified on the way in. No browser, no Downloads folder, no
 * Import Manager, and no need to know that a cell is a file called n33.dt2 that
 * belongs in a directory called w116.
 */
public class MapDepot implements IPlugin {

    private static final String TAG = "MapDepot";

    /** Identifies this plugin's entry in ATAK's Tool Preferences list. */
    private static final String PREFS_KEY = "mapdepot_preferences";

    private final IServiceController serviceController;
    private Context pluginContext;
    private IHostUIService uiService;
    private ToolbarItem toolbarItem;

    /**
     * Opens the pane from a broadcast, so a session driving the phone over
     * adb can open it without walking ATAK's Tools list (a stray tap there
     * opens another plugin's pane). System-registered: a plain
     * registerReceiver is process-local and {@code am broadcast} cannot
     * reach it.
     */
    public static final String ACTION_SHOW = "com.atakmap.android.mapdepot.SHOW";

    private final android.content.BroadcastReceiver showReceiver =
            new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context c, android.content.Intent i) {
                    if (uiService != null)
                        showPane();
                }
            };
    private Pane pane;

    private DepotClient client;
    private RegionInstaller installer;
    private BaseMapInstaller baseMaps;
    private PackageInstaller packages;

    /** Everything the catalog offers. */
    private final List<Depot.Region> allRegions = new ArrayList<>();

    /** Just the country currently selected — what the list actually shows. */
    private final List<Depot.Region> shown = new ArrayList<>();

    /** Country code per spinner position, in the order the spinner shows them. */
    private final List<String> countryCodes = new ArrayList<>();

    private RegionAdapter adapter;
    private TextView status;
    private View homeView, dtedView, baseMapView;
    private Button countryButton;

    /** Index into {@link #countryCodes} of the country being shown. */
    private int countryIndex;

    /** Display names, parallel to {@link #countryCodes}. */
    private final List<String> countryLabels = new ArrayList<>();

    /**
     * The catalog came from the depot. Not set by the saved copy, so the next
     * section opened asks again and the "No network" line goes once it is back.
     */
    private boolean catalogLoaded;

    /** When the depot last answered, so a section reopened later asks again. */
    private long catalogFetchedAt;

    /** A catalog older than this is fetched again when a section opens. */
    private static final long CATALOG_STALE_MS = 5L * 60L * 1000L;

    /**
     * Whether the catalog in memory is good enough to show. It is kept for
     * a session, which is right while nothing changes on the depot; a row
     * added or dropped on the server stayed invisible until a restart
     * (2026-10-11, two Google rows after the duplicate was dropped). So a
     * section opened a few minutes after the last fetch asks again.
     */
    private boolean catalogFresh() {
        return catalogLoaded
                && System.currentTimeMillis() - catalogFetchedAt < CATALOG_STALE_MS;
    }

    /** id of the region currently downloading, or null. One at a time on purpose. */
    private String activeRegionId;
    private final Map<String, Integer> progressById = new HashMap<>();

    /** Regions known to be fully installed, so a row can say so without rescanning. */
    private final Set<String> completeById = new HashSet<>();

    /** The last scan of the DTED tree: cell key to size on disk. */
    private final Map<String, Long> onDeviceCells = new HashMap<>();

    /**
     * Forests and ranger district maps are the same interaction -- search a long
     * list of units, download one large file -- so they share one screen, and a
     * mode says which list it is showing.
     */
    private enum PackageMode {
        FORESTS, RECMAPS, STORMS, SATELLITE
    }

    /**
     * Sentinel-2 passes over the area the operator searched, newest first, as
     * one layer each; see {@link Depot.Scene}. Live by nature, so held in
     * memory only: a list from last week reads as what the satellite saw today.
     */
    private final List<Depot.Scene> allScenes = new ArrayList<>();
    private String sceneStyle = Depot.STYLE_VISUAL;

    /**
     * Where the passes are searched and how they are ordered, the same three
     * choices as Disaster Aerials: newest first around the map center, or
     * nearest the map center, or nearest the operator. Each tap searches again
     * around that point, so panning and tapping is how "search here" is done.
     */
    private enum SatSort {
        NEWEST, MAP, ME
    }

    private SatSort satSort = SatSort.NEWEST;
    private boolean scenesLoading;
    private String scenesError;
    /** Where the passes were searched, so changing the style asks about the same place. */
    private double sceneLat = Double.NaN, sceneLon = Double.NaN;
    private static final int SCENE_DAYS = 14;

    /** NOAA post-disaster imagery, newest event first; see {@link Depot.Storm}. */
    private final List<Depot.Storm> allStorms = new ArrayList<>();

    /**
     * Index into {@link #allStorms} of the event whose flights are listed, or
     * -1 for every event at once: the operator's "all list" so the flights
     * on the phone from NOAA, CAP and Maxar can be hidden and shown together.
     */
    private int stormIndex = -1;

    /** Every flight of every event, for the All Events view. */
    private final List<Depot.Package> allFlights = new ArrayList<>();

    /** When the depot built the storms section, so the list can say how fresh "newest flight" is. */
    private String stormsBuiltAt = "";

    private PackageMode packageMode = PackageMode.FORESTS;

    /**
     * Which packages the list shows. Cycles on tap rather than opening a chooser:
     * three states is fewer than a dialog costs, and the button reads as its own
     * label.
     */
    private enum Shown {
        ALL, INSTALLED, AVAILABLE
    }

    private Shown packageShown = Shown.ALL;
    private Shown mapShown = Shown.ALL;
    private Shown regionShown = Shown.ALL;
    private Button packageFilter, basemapFilter, regionFilter;
    private Button cancelBar, cancelBarDted;

    /** Next state in the All / Installed / Available cycle. */
    private static Shown nextShown(Shown s) {
        return s == Shown.ALL ? Shown.INSTALLED
                : s == Shown.INSTALLED ? Shown.AVAILABLE : Shown.ALL;
    }

    private static int labelFor(Shown s) {
        return s == Shown.INSTALLED ? R.string.filter_installed
                : s == Shown.AVAILABLE ? R.string.filter_available
                        : R.string.filter_all;
    }

    private final List<Depot.Forest> allForests = new ArrayList<>();
    private final List<Depot.RecMap> allRecMaps = new ArrayList<>();

    /**
     * The Forest Service series that came after the district maps. Regional
     * and 100K arrive with the catalog; the FSTopo quads are their own
     * document, fetched the first time that series is chosen, and until then
     * only their count is known.
     */
    private final List<Depot.RecMap> allRegional = new ArrayList<>();
    private final List<Depot.RecMap> allK100 = new ArrayList<>();
    private final List<Depot.RecMap> allFstopo = new ArrayList<>();
    private int fstopoCount;
    private boolean fstopoLoading;

    private enum Series {
        DISTRICT, REGIONAL, K100, FSTOPO
    }

    private Series series = Series.DISTRICT;
    private Button seriesButton, nearButton;
    private View seriesRow;

    /**
     * What "nearest" is measured from. The map center by default -- the
     * operator pans to where the work is -- and their own position on
     * request. Said on the button and in the status line, because a list
     * sorted by an unstated distance reads as random.
     */
    private boolean nearMe;

    /**
     * Flights in date order, newest first, instead of nearest the map. The
     * default on the storms page: during a storm the flight from this morning
     * is the one wanted on top, wherever the map is. The distance still shows
     * on the row.
     */
    private boolean newestFirst = true;

    /**
     * Distance and bearing from the map center, by id, for the rows on
     * screen. Filled only for the series that carry a location, and only for
     * the rows shown, so it is never eighteen thousand entries.
     */
    private final Map<String, double[]> distances = new HashMap<>();

    /** How many of the nearest sheets a located series shows. */
    private static final int NEAREST_CAP = 300;

    private final List<Depot.Package> shownPackages = new ArrayList<>();
    private final Set<String> installedPackages = new HashSet<>();

    /**
     * The offer to park vector tile packages is made once per plugin start.
     * Refusing it is an answer; asking again every time the pane opens is not
     * a reminder, it is nagging about something the operator already decided.
     */
    private boolean offeredToPark;
    private PackageAdapter packageAdapter;
    private TextView forestStatus;
    private EditText forestSearch;
    private View forestView;

    /** id of the package currently downloading, or null. One at a time. */
    private String activeForestId;
    private long forestDone, forestTotal;

    private final List<Depot.BaseMap> allMaps = new ArrayList<>();
    private final List<Depot.BaseMap> shownMaps = new ArrayList<>();
    private final List<String> categories = new ArrayList<>();
    private int categoryIndex;
    private BaseMapAdapter mapAdapter;
    private TextView mapStatus;
    private Button categoryButton;
    private final Set<String> installedMaps = new HashSet<>();

    // ------------------------------------------------------------------ NIFC
    //
    // A directory browser rather than a fixed set of screens, because the eleven
    // GACCs do not agree on a layout and any wizard modelled on one of them is
    // wrong for the others. The stack is what Back walks up; the GACC is the
    // floor it stops at, so Back leaves the browser rather than stranding the
    // operator at the top of the whole server.
    private NifcClient nifc;
    private UaswfcClient uaswfc;
    private BeaconClient beacon;

    /** Whichever archive the browser is currently showing. */
    private MapSource source;

    private View nifcView;
    private ListView nifcList;
    private TextView nifcStatus;
    private Button nifcGaccButton, cancelBarNifc;
    private NifcAdapter nifcAdapter;

    /** Everything the folder holds; {@link #nifcRows} is this after filtering. */
    private final List<Object> nifcAllRows = new ArrayList<>();
    private final List<Object> nifcRows = new ArrayList<>();
    private Button nifcFilter, nifcOutlines;
    private Button forestOutlines;
    private Shown nifcShown = Shown.ALL;

    /**
     * The row text colour as ATAK's theme sets it, taken from the first row
     * inflated. A recycled row that was a pin has to be put back to this, and
     * hardcoding white would quietly change every other row's appearance.
     */
    private android.content.res.ColorStateList rowTextColor;

    /** The theme's row text colour, captured from the first row seen. */
    private android.content.res.ColorStateList rowTextColorOf(TextView tv) {
        if (rowTextColor == null)
            rowTextColor = tv.getTextColors();
        return rowTextColor;
    }

    /** Progress for the row currently downloading, so it shows on the row. */
    private long nifcDone, nifcTotal;

    /** How many entries the source withheld, kept for the status line. */
    private int nifcHidden;

    /** The list is the maps on this phone, because the archive did not answer. */
    private boolean nifcOffline;

    /**
     * When the saved copy each catalog section is showing was saved, or 0 when
     * it came from the depot just now. Kept for the status lines.
     */
    private long mapsSavedAt, forestsSavedAt;
    private final Deque<String[]> nifcStack = new ArrayDeque<>();
    private final List<String> gaccPaths = new ArrayList<>();
    private final List<String> gaccLabels = new ArrayList<>();

    /** Encoded path for building URLs; decoded path for naming downloads. */
    private String nifcPath = "";
    private String nifcDecodedPath = "";

    private String activePostingId;
    private final Set<String> nifcInstalled = new HashSet<>();

    /** Survives a plugin reload only on the host context -- see loadGacc(). */
    /**
     * Keyed per source, because the two archives do not cover the same ground:
     * NIFC has all eleven GACCs and UASWFC currently only pacific_nw, so one
     * remembered folder cannot serve both.
     */
    private String prefGaccKey() {
        return "mapdepot_gacc_" + (source == null ? "nifc" : source.id());
    }

    public MapDepot(IServiceController serviceController) {
        this.serviceController = serviceController;

        final PluginContextProvider ctxProvider = serviceController
                .getService(PluginContextProvider.class);
        if (ctxProvider != null) {
            pluginContext = ctxProvider.getPluginContext();
            pluginContext.setTheme(R.style.ATAKPluginTheme);
        }

        uiService = serviceController.getService(IHostUIService.class);

        toolbarItem = new ToolbarItem.Builder(
                pluginContext.getString(R.string.app_name),
                MarshalManager.marshal(
                        pluginContext.getResources()
                                .getDrawable(R.drawable.ic_toolbar),
                        android.graphics.drawable.Drawable.class,
                        gov.tak.api.commons.graphics.Bitmap.class))
                .setListener(new ToolbarItemAdapter() {
                    @Override
                    public void onClick(ToolbarItem item) {
                        showPane();
                    }
                })
                .setIdentifier(pluginContext.getPackageName())
                .build();
    }

    @Override
    public void onStart() {
        if (uiService == null)
            return;
        client = new DepotClient(savedDir());
        client.adopt(oldCacheDir());
        installer = new RegionInstaller();
        baseMaps = new BaseMapInstaller();
        packages = new PackageInstaller();
        uiService.addToolbarItem(toolbarItem);
        com.atakmap.android.ipc.AtakBroadcast.getInstance().registerSystemReceiver(
                showReceiver, new com.atakmap.android.ipc.AtakBroadcast.DocumentedIntentFilter(
                        ACTION_SHOW, "Open the Map Depot pane"));
        registerPreferences();
        final com.atakmap.android.maps.MapView mv =
                com.atakmap.android.maps.MapView.getMapView();
        if (mv != null)
            mv.addOnMapMovedListener(mapMoved);

        // Left until ATAK has finished coming up: this runs while plugins are
        // still loading, and a dialog thrown at a window that is not ready yet
        // is lost, or worse. The check itself is cheap either way.
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                new Runnable() {
                    @Override
                    public void run() {
                        if (packages != null)
                            offerToParkVectorPackages();
                        drawOutlinesFromSavedCatalog();
                    }
                }, PARK_OFFER_DELAY_MS);
    }

    private final android.os.Handler mainHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    /**
     * The packages list follows the map: "in view" and the distances are
     * measured from the map center, so a pan has to re-measure them.
     *
     * <strong>onMapMoved runs on the GL render thread</strong>; nothing here
     * touches a view. It posts, and coalesces, because a pinch fires it every
     * frame. The rebuild only runs while the packages page is showing a list
     * that carries locations.
     */
    private final com.atakmap.map.AtakMapView.OnMapMovedListener mapMoved =
            new com.atakmap.map.AtakMapView.OnMapMovedListener() {
                @Override
                public void onMapMoved(com.atakmap.map.AtakMapView v, boolean animate) {
                    mainHandler.removeCallbacks(mapSettled);
                    mainHandler.postDelayed(mapSettled, 500L);
                }
            };

    private final Runnable mapSettled = new Runnable() {
        @Override
        public void run() {
            if (forestView == null || forestView.getVisibility() != View.VISIBLE)
                return;
            if (activeForestId != null || !locatedList())
                return;
            applyForestFilter();
        }
    };

    /**
     * The flight footprints come back after a restart with the flights, from
     * the catalog saved on the phone; the pane does not have to be opened.
     */
    private void drawOutlinesFromSavedCatalog() {
        if (client == null)
            return;
        client.cachedStorms(new DepotClient.StormsCallback() {
            @Override
            public void onStorms(Depot.Storms storms) {
                if (client == null || !allStorms.isEmpty())
                    return;
                allStorms.addAll(storms.events);
                for (Depot.Storm s : allStorms)
                    allFlights.addAll(s.flights);
                stormsBuiltAt = storms.builtAt;
                refreshInstalledPackages();
                syncOutlines();
            }
        });
    }

    /** One box per installed flight, following the Outlines button. */
    private void syncOutlines() {
        FlightOutlines.sync(allStorms, installedPackages,
                Boolean.TRUE.equals(PackageInstaller.outlinesVisible()));
    }

    private static final long PARK_OFFER_DELAY_MS = 5000L;

    /**
     * Why a vector tile package should not be downloaded onto this ATAK, in
     * the form that fits where it is shown: the section's status line, or the
     * tail of a row. Null when there is nothing in the way.
     *
     * Two different reasons, and they must not be confused: 5.6 cannot display
     * one, which wastes the download; official 5.8.0.4 and earlier display it
     * and then never start again, which loses the phone.
     */
    private String vectorPackageBlock(boolean brief) {
        if (!PackageInstaller.supportsVectorPackages())
            return brief ? pluginContext.getString(R.string.forests_need_57_brief)
                    : pluginContext.getString(R.string.forests_need_57);
        if (AtakBuild.blocksVectorPackages(hostContext())) {
            final String v = AtakBuild.versionNumber(hostContext());
            return brief ? pluginContext.getString(R.string.forests_blocked_brief, v)
                    : pluginContext.getString(R.string.forests_blocked, v,
                            AtakBuild.fixedVersion());
        }
        return null;
    }

    /**
     * A phone already carrying packages on official 5.8.0.4 is one restart from an
     * ATAK that does not open, and nothing on the phone says so. This does,
     * once, and offers the one recovery that is known to work: moving the
     * files to a folder beside atak/imagery that ATAK does not scan. Moving
     * someone's files is not something this plugin does anywhere else, and
     * the dialog says why it is right here.
     */
    private void offerToParkVectorPackages() {
        if (offeredToPark)
            return;
        offeredToPark = true;
        final Context host = hostContext();
        if (!AtakBuild.blocksVectorPackages(host))
            return;
        final List<File> found = PackageInstaller.vectorPackagesOnDevice();
        Log.i(TAG, "vector tile packages on an ATAK that will not start with"
                + " them: " + found.size());
        if (found.isEmpty())
            return;

        final int n = found.size();
        final String version = AtakBuild.versionNumber(host);
        final String message = n + " vector tile package" + (n == 1 ? " is" : "s are")
                + " in atak/imagery (Offline Public Land Vector Tiles). ATAK " + version
                + " will not start with " + (n == 1 ? "it" : "them")
                + " there after the next restart.\n\nMove "
                + (n == 1 ? "it" : "them") + " to atak/" + PackageInstaller.PARKED_DIR_NAME
                + " now? ATAK does not look in that folder. Map Depot moves no other"
                + " files, ever; this is the one case where the alternative is an"
                + " ATAK that does not open. Move " + (n == 1 ? "it" : "them")
                + " back by hand after updating ATAK to " + AtakBuild.fixedVersion()
                + " or newer.";
        try {
            new AlertDialog.Builder(host)
                    .setTitle("ATAK will not start next time")
                    .setMessage(message)
                    .setPositiveButton("Move", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            parkVectorPackages(n);
                        }
                    })
                    .setNegativeButton("Not now", null)
                    .show();
        } catch (RuntimeException noWindow) {
            // Nothing to draw on -- ATAK is not up the way the delay assumed.
            // The section itself still says what is wrong; the offer comes
            // back at the next plugin start.
            Log.w(TAG, "could not offer to park vector tile packages: " + noWindow);
            offeredToPark = false;
        }
    }

    private void parkVectorPackages(final int expected) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final int moved = PackageInstaller.parkVectorPackages();
                new android.os.Handler(android.os.Looper.getMainLooper()).post(
                        new Runnable() {
                            @Override
                            public void run() {
                                reportParked(moved, expected);
                            }
                        });
            }
        }, "mapdepot-park").start();
    }

    private void reportParked(int moved, int expected) {
        final String where = "atak/" + PackageInstaller.PARKED_DIR_NAME;
        if (moved >= expected) {
            toast("Moved " + moved + " to " + where + ". ATAK will start.");
            return;
        }
        // Say it in a dialog, not a toast: this phone is still going to fail
        // and three seconds on screen is not enough to learn that.
        new AlertDialog.Builder(hostContext())
                .setTitle("Not all moved")
                .setMessage("Moved " + moved + " of " + expected + " to " + where
                        + ". The rest are still in atak/imagery, and ATAK will not"
                        + " start with them after the next restart. Move them out"
                        + " with a file manager, or delete them.")
                .setPositiveButton(pluginContext.getString(R.string.ok), null)
                .show();
    }

    /**
     * Puts Map Depot in ATAK's Tool Preferences, which is the only route to the
     * user manual. The PDF is built into the plugin's assets, and an asset is
     * not reachable by anyone -- without this entry it shipped inside the APK
     * with no way to open it.
     *
     * Guarded rather than assumed: a build that does not expose
     * {@code ToolsPreferenceFragment} should lose the manual, not the plugin.
     */
    private void registerPreferences() {
        try {
            com.atakmap.app.preferences.ToolsPreferenceFragment.register(
                    new com.atakmap.app.preferences.ToolsPreferenceFragment
                            .ToolPreference(
                            pluginContext.getString(R.string.app_name),
                            pluginContext.getString(R.string.prefs_summary),
                            PREFS_KEY,
                            pluginContext.getResources().getDrawable(
                                    R.drawable.ic_toolbar),
                            new MapDepotPreferenceFragment(pluginContext)));
        } catch (LinkageError | RuntimeException notThisBuild) {
            Log.w(TAG, "could not register preferences: " + notThisBuild);
        }
    }

    private void unregisterPreferences() {
        try {
            com.atakmap.app.preferences.ToolsPreferenceFragment
                    .unregister(PREFS_KEY);
        } catch (LinkageError | RuntimeException notThisBuild) {
            Log.w(TAG, "could not unregister preferences: " + notThisBuild);
        }
    }

    @Override
    public void onStop() {
        unregisterPreferences();
        try {
            com.atakmap.android.ipc.AtakBroadcast.getInstance()
                    .unregisterSystemReceiver(showReceiver);
        } catch (RuntimeException e) {
            Log.w(TAG, "show receiver was not registered", e);
        }

        // A fetch in flight when the client is shut down never calls back,
        // and a flag left true would make the next FSTopo pick do nothing.
        fstopoLoading = false;

        // Close the pane and let go of it. Its buttons hold a reference to this
        // instance, and everything this instance owns is about to be null, so a
        // pane left on screen after an unload is a tap away from taking ATAK
        // down with a NullPointerException on the main thread.
        if (pane != null) {
            if (uiService != null && uiService.isPaneVisible(pane))
                uiService.closePane(pane);
            pane = null;
        }

        if (installer != null) {
            installer.shutdown();
            installer = null;
        }
        if (baseMaps != null) {
            baseMaps.shutdown();
            baseMaps = null;
        }
        if (packages != null) {
            packages.shutdown();
            packages = null;
        }
        if (client != null) {
            client.shutdown();
            client = null;
        }
        if (uiService != null)
            uiService.removeToolbarItem(toolbarItem);
        mainHandler.removeCallbacks(mapSettled);
        final com.atakmap.android.maps.MapView mv =
                com.atakmap.android.maps.MapView.getMapView();
        if (mv != null)
            mv.removeOnMapMovedListener(mapMoved);
        FlightOutlines.unregister(mv);
    }

    private void showPane() {
        if (pane == null) {
            final View root = PluginLayoutInflater.inflate(pluginContext,
                    R.layout.main_layout, null);
            bind(root);
            pane = new PaneBuilder(root)
                    .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Default)
                    .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 0.5D)
                    .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, 0.5D)
                    .build();
        }
        if (!uiService.isPaneVisible(pane))
            uiService.showPane(pane, null);
    }

    private void bind(View root) {
        homeView = root.findViewById(R.id.home_view);
        dtedView = root.findViewById(R.id.dted_view);
        baseMapView = root.findViewById(R.id.basemap_view);
        mapStatus = root.findViewById(R.id.basemap_status);
        categoryButton = root.findViewById(R.id.category_button);
        basemapFilter = root.findViewById(R.id.basemap_filter);
        basemapFilter.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mapShown = nextShown(mapShown);
                applyCategoryFilter();
            }
        });
        status = root.findViewById(R.id.depot_status);
        countryButton = root.findViewById(R.id.country_button);
        cancelBarDted = root.findViewById(R.id.cancel_bar_dted);
        cancelBarDted.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (installer != null)
                    installer.cancel();
                activeRegionId = null;
                progressById.clear();
                cancelBarDted.setVisibility(View.GONE);
                status.setText("Cancelled.");
                adapter.notifyDataSetChanged();
            }
        });

        regionFilter = root.findViewById(R.id.region_filter);
        regionFilter.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                regionShown = nextShown(regionShown);
                applyCountryFilter();
            }
        });

        adapter = new RegionAdapter(pluginContext);
        final ListView list = root.findViewById(R.id.region_list);
        list.setAdapter(adapter);

        root.findViewById(R.id.btn_dted).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showDted();
                    }
                });

        mapAdapter = new BaseMapAdapter(pluginContext);
        ((ListView) root.findViewById(R.id.basemap_list)).setAdapter(mapAdapter);

        root.findViewById(R.id.btn_maps).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showBaseMaps();
                    }
                });

        forestView = root.findViewById(R.id.forest_view);
        forestStatus = root.findViewById(R.id.forest_status);
        forestSearch = root.findViewById(R.id.forest_search);
        seriesRow = root.findViewById(R.id.series_row);
        seriesButton = root.findViewById(R.id.sheet_series);
        seriesButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (packageMode == PackageMode.STORMS)
                    chooseStorm();
                else if (packageMode == PackageMode.SATELLITE)
                    chooseSceneStyle();
                else
                    chooseSeries();
            }
        });

        root.findViewById(R.id.btn_storms).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showPackages(PackageMode.STORMS);
                    }
                });
        root.findViewById(R.id.btn_satellite).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showPackages(PackageMode.SATELLITE);
                    }
                });
        nearButton = root.findViewById(R.id.near_anchor);
        nearButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (packageMode == PackageMode.SATELLITE) {
                    satSort = satSort == SatSort.NEWEST ? SatSort.MAP
                            : satSort == SatSort.MAP ? SatSort.ME : SatSort.NEWEST;
                    if (satSort == SatSort.ME && Distance.selfPoint() == null) {
                        toast(pluginContext.getString(R.string.no_position));
                        satSort = SatSort.NEWEST;
                    }
                    refreshSeriesControls();
                    loadScenes(true);
                    return;
                }
                // Storms: Newest first -> Near: map center -> Near: me.
                // Elsewhere: map center <-> me.
                if (packageMode == PackageMode.STORMS && newestFirst) {
                    newestFirst = false;
                    nearMe = false;
                } else if (!nearMe) {
                    if (Distance.selfPoint() == null) {
                        toast(pluginContext.getString(R.string.no_position));
                        if (packageMode == PackageMode.STORMS)
                            newestFirst = true;
                    } else {
                        nearMe = true;
                    }
                } else {
                    nearMe = false;
                    if (packageMode == PackageMode.STORMS)
                        newestFirst = true;
                }
                refreshSeriesControls();
                applyForestFilter();
            }
        });
        cancelBar = root.findViewById(R.id.cancel_bar);
        cancelBar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (packages != null)
                    packages.cancel();
                activeForestId = null;
                showCancelBar(false);
                forestStatus.setText("Cancelled.");
                packageAdapter.notifyDataSetChanged();
            }
        });

        packageFilter = root.findViewById(R.id.package_filter);
        packageFilter.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                packageShown = nextShown(packageShown);
                applyForestFilter();
            }
        });
        packageAdapter = new PackageAdapter(pluginContext);
        final ListView packageList = root.findViewById(R.id.forest_list);
        packageList.setAdapter(packageAdapter);

        // The list owns the row click, not the row. A per-row listener loses the
        // touch to the Button inside it.
        packageList.setOnItemClickListener(
                new android.widget.AdapterView.OnItemClickListener() {
                    @Override
                    public void onItemClick(android.widget.AdapterView<?> parent,
                            View view, int position, long id) {
                        if (position < 0 || position >= shownPackages.size())
                            return;
                        Depot.Package pkg = shownPackages.get(position);
                        if (pkg instanceof Depot.Scene)
                            FlightOutlines.preview((Depot.Scene) pkg);
                        if (!installedPackages.contains(pkg.id())
                                && pkg instanceof Depot.Flight
                                && ((Depot.Flight) pkg).pack != null
                                && installedPackages.contains(
                                        ((Depot.Flight) pkg).pack.id()))
                            pkg = ((Depot.Flight) pkg).pack;
                        if (!installedPackages.contains(pkg.id())) {
                            // Not added yet, but the catalog knows where it
                            // is: show the place, so a crew can see which
                            // flight covers them before adding one.
                            if (pkg instanceof Depot.Bounded
                                    && PackageInstaller.showBounds(
                                            ((Depot.Bounded) pkg).bounds()))
                                forestStatus.setText(pkg instanceof Depot.Scene
                                        ? "The orange box is the area " + pkg.name()
                                                + " covers. Add it to see the imagery."
                                        : "Showing where " + pkg.name()
                                                + " is. Add it to see the imagery.");
                            return;
                        }
                        // A freshly downloaded package is not in ATAK's map
                        // list the instant the download ends -- the scan takes
                        // as long as it takes. Say so rather than letting the
                        // tap look broken.
                        PackageInstaller.goTo(pkg, new PackageInstaller.GoTo() {
                            @Override
                            public void onGoing(Depot.Package p) {
                                forestStatus.setText("Going to " + p.name());
                                // The map has moved, so "nearest" has too.
                                // Rebuilt after the fit has settled rather
                                // than mid-flight, and on the UI thread.
                                forestStatus.postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (forestView.getVisibility() == View.VISIBLE)
                                            applyForestFilter();
                                    }
                                }, 1500L);
                            }

                            @Override
                            public void onWaiting(Depot.Package p) {
                                // Two lines of room, and the name is already
                                // long. Anything more gets cut off mid-word,
                                // which reads worse than saying less.
                                forestStatus.setText("Adding to the map list…");
                            }

                            @Override
                            public void onUnavailable(Depot.Package p, String why) {
                                forestStatus.setText("Could not go there: " + why);
                            }
                        });
                    }
                });

        final View forestsButton = root.findViewById(R.id.btn_forests);
        forestsButton.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showPackages(PackageMode.FORESTS);
                    }
                });

        // A section that vanishes reads as a bug. On an ATAK that must not be
        // given these maps the button stays, dimmed, and the line under the
        // buttons says why; it still opens, because a package already on the
        // phone can still be removed from inside.
        final TextView homeNote = root.findViewById(R.id.home_note);
        if (AtakBuild.blocksVectorPackages(hostContext())) {
            forestsButton.setAlpha(0.5f);
            homeNote.setText(pluginContext.getString(R.string.home_forests_blocked,
                    AtakBuild.versionNumber(hostContext()), AtakBuild.fixedVersion()));
            homeNote.setVisibility(View.VISIBLE);
        } else {
            homeNote.setVisibility(View.GONE);
        }

        root.findViewById(R.id.btn_recmaps).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showPackages(PackageMode.RECMAPS);
                    }
                });

        nifcView = root.findViewById(R.id.nifc_view);
        nifcList = root.findViewById(R.id.nifc_list);
        nifcStatus = root.findViewById(R.id.nifc_status);
        nifcGaccButton = root.findViewById(R.id.nifc_gacc);
        cancelBarNifc = root.findViewById(R.id.cancel_bar_nifc);
        forestOutlines = root.findViewById(R.id.forest_outlines);
        forestOutlines.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleOutlines(forestStatus);
            }
        });

        nifcOutlines = root.findViewById(R.id.nifc_outlines);
        nifcOutlines.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleOutlines(nifcStatus);
            }
        });

        nifcFilter = root.findViewById(R.id.nifc_filter);
        nifcFilter.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                nifcShown = nextShown(nifcShown);
                applyNifcFilter();
            }
        });
        nifcAdapter = new NifcAdapter(pluginContext);
        nifcList.setAdapter(nifcAdapter);

        root.findViewById(R.id.btn_nifc).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (nifc == null)
                            nifc = new NifcClient();
                        showSource(nifc);
                    }
                });

        root.findViewById(R.id.btn_beacon).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (beacon == null)
                            beacon = new BeaconClient();
                        showSource(beacon);
                    }
                });

        root.findViewById(R.id.btn_uaswfc).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (uaswfc == null)
                            uaswfc = new UaswfcClient();
                        showSource(uaswfc);
                    }
                });

        root.findViewById(R.id.btn_back_nifc).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        nifcBack();
                    }
                });

        nifcGaccButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseGacc();
            }
        });

        cancelBarNifc.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (packages != null)
                    packages.cancel();
                cancelBarNifc.setVisibility(View.GONE);
            }
        });

        root.findViewById(R.id.btn_back_forests).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showHome();
                    }
                });

        forestSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence c, int a, int b, int d) {
            }

            @Override
            public void onTextChanged(CharSequence c, int a, int b, int d) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                applyForestFilter();
            }
        });

        root.findViewById(R.id.btn_back_maps).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showHome();
                    }
                });

        categoryButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseCategory();
            }
        });

        root.findViewById(R.id.btn_back).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showHome();
                    }
                });

        countryButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseCountry();
            }
        });

        showHome();
    }

    private void showHome() {
        FlightOutlines.clearPreview();
        homeView.setVisibility(View.VISIBLE);
        dtedView.setVisibility(View.GONE);
        baseMapView.setVisibility(View.GONE);
        forestView.setVisibility(View.GONE);
        nifcView.setVisibility(View.GONE);
    }

    private void showPackages(PackageMode mode) {
        packageMode = mode;
        homeView.setVisibility(View.GONE);
        dtedView.setVisibility(View.GONE);
        baseMapView.setVisibility(View.GONE);
        forestView.setVisibility(View.VISIBLE);
        nifcView.setVisibility(View.GONE);
        refreshSeriesControls();
        forestSearch.setText("");

        // Say it up front rather than after a gigabyte has been downloaded.
        // ATAK 5.6 has no vector tile package support, so a forest basemap
        // installs and then exists nowhere ATAK can see it; official 5.8.0.4
        // displays it and then does not start again.
        if (mode == PackageMode.FORESTS) {
            final String block = vectorPackageBlock(false);
            if (block != null)
                forestStatus.setText(block);
        }

        if (mode == PackageMode.SATELLITE) {
            // A fresh look every time the section opens: what passed over
            // this morning is the point.
            loadScenes(true);
            return;
        }

        if (!catalogFresh())
            loadCatalog();
        else
            applyForestFilter();
    }

    private void chooseSceneStyle() {
        final String[] items = { pluginContext.getString(R.string.scene_visual),
                pluginContext.getString(R.string.scene_swir) };
        new AlertDialog.Builder(hostContext())
                .setTitle(pluginContext.getString(R.string.choose_scene_style))
                .setSingleChoiceItems(items,
                        Depot.STYLE_SWIR.equals(sceneStyle) ? 1 : 0,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                d.dismiss();
                                switchSceneView(which == 1 ? Depot.STYLE_SWIR : Depot.STYLE_VISUAL);
                            }
                        })
                .setNegativeButton(pluginContext.getString(R.string.cancel), null)
                .show();
    }

    /**
     * Switches every pass you added to one view, and relabels the list to
     * match. Nothing is fetched: the same passes are on the list, held in the
     * other view, so the list stays put and only the names change.
     */
    private void switchSceneView(String style) {
        sceneStyle = style;
        PackageInstaller.showSceneView(style);
        final List<Depot.Scene> relabeled = new ArrayList<>();
        for (Depot.Scene s : allScenes)
            relabeled.add(s.style.equals(style) ? s : s.otherView());
        allScenes.clear();
        allScenes.addAll(relabeled);
        FlightOutlines.clearPreview();
        refreshSeriesControls();
        refreshInstalledPackages();
        applyForestFilter();
        forestStatus.postDelayed(new Runnable() {
            @Override
            public void run() {
                forestStatus.setText("Showing "
                        + Depot.Scene.viewLabel(sceneStyle)
                        + " on every pass you added.");
            }
        }, 300L);
    }

    /**
     * Asks the satellite service what passed over the map (here) or over the
     * place last searched. The list is emptied first so a slow answer is not
     * mistaken for the previous place's passes.
     */
    private void loadScenes(boolean here) {
        if (client == null) {
            forestStatus.setText("Map Depot is not running — reload the plugin.");
            return;
        }
        if (here || Double.isNaN(sceneLat)) {
            final GeoPoint self = satSort == SatSort.ME ? Distance.selfPoint() : null;
            final GeoPoint c = self != null ? self : Distance.mapCenter();
            if (c == null) {
                forestStatus.setText(pluginContext.getString(R.string.no_position));
                return;
            }
            sceneLat = c.getLatitude();
            sceneLon = c.getLongitude();
        }
        FlightOutlines.clearPreview();
        scenesLoading = true;
        scenesError = null;
        allScenes.clear();
        applyForestFilter();
        client.fetchScenes(sceneLat, sceneLon, 0.5, SCENE_DAYS, sceneStyle,
                new DepotClient.SceneCallback() {
                    @Override
                    public void onScenes(List<Depot.Scene> scenes) {
                        scenesLoading = false;
                        allScenes.clear();
                        allScenes.addAll(scenes);
                        refreshInstalledPackages();
                        if (packageMode == PackageMode.SATELLITE)
                            applyForestFilter();
                    }

                    @Override
                    public void onError(String message) {
                        scenesLoading = false;
                        scenesError = message;
                        if (packageMode == PackageMode.SATELLITE)
                            applyForestFilter();
                    }
                });
    }

    /** The list behind the current mode and series, in catalog order. */
    private List<? extends Depot.Package> sourceList() {
        if (packageMode == PackageMode.FORESTS)
            return allForests;
        if (packageMode == PackageMode.SATELLITE)
            return allScenes;
        if (packageMode == PackageMode.STORMS)
            return stormIndex < 0 ? allFlights
                    : stormIndex < allStorms.size()
                            ? allStorms.get(stormIndex).flights
                            : java.util.Collections.<Depot.Package>emptyList();
        switch (series) {
            case REGIONAL:
                return allRegional;
            case K100:
                return allK100;
            case FSTOPO:
                return allFstopo;
            default:
                return allRecMaps;
        }
    }

    /** The chooser button and the search hint follow the mode and series. */
    private void refreshSeriesControls() {
        final boolean sheets = packageMode == PackageMode.RECMAPS;
        final boolean storms = packageMode == PackageMode.STORMS;
        final boolean sat = packageMode == PackageMode.SATELLITE;
        final boolean located = locatedList();
        seriesRow.setVisibility(sheets || storms || sat || located ? View.VISIBLE : View.GONE);
        seriesButton.setVisibility(sheets || storms || sat ? View.VISIBLE : View.GONE);
        if (sheets)
            seriesButton.setText(seriesLabel(series, true) + "  \u25be");
        else if (storms)
            seriesButton.setText(stormLabel(stormIndex, true) + "  \u25be");
        else if (sat)
            seriesButton.setText(pluginContext.getString(
                    Depot.STYLE_SWIR.equals(sceneStyle) ? R.string.scene_swir
                            : R.string.scene_visual) + "  \u25be");
        nearButton.setVisibility(located || sat ? View.VISIBLE : View.GONE);
        nearButton.setText(pluginContext.getString(sat
                ? (satSort == SatSort.NEWEST ? R.string.newest_first
                        : satSort == SatSort.ME ? R.string.near_me : R.string.near_map)
                : storms && newestFirst ? R.string.newest_first
                        : nearMe ? R.string.near_me : R.string.near_map));
        forestSearch.setHint(storms || sat ? R.string.search_flights
                : !sheets ? R.string.search_forests
                : series == Series.DISTRICT ? R.string.search_recmaps
                        : series == Series.REGIONAL ? R.string.search_regional
                                : R.string.search_sheets);
    }

    /** With the count, as the UI standard asks: a filter says what it costs. */
    private String seriesLabel(Series s, boolean withCount) {
        final int name;
        final int count;
        switch (s) {
            case REGIONAL:
                name = R.string.series_regional;
                count = allRegional.size();
                break;
            case K100:
                name = R.string.series_100k;
                count = allK100.size();
                break;
            case FSTOPO:
                name = R.string.series_fstopo;
                count = allFstopo.isEmpty() ? fstopoCount : allFstopo.size();
                break;
            default:
                name = R.string.series_district;
                count = allRecMaps.size();
        }
        final String label = pluginContext.getString(name);
        return withCount ? label + " (" + String.format(Locale.US, "%,d", count) + ")"
                : label;
    }

    private void chooseSeries() {
        final Series[] all = Series.values();
        final String[] items = new String[all.length];
        for (int i = 0; i < all.length; i++)
            items[i] = seriesLabel(all[i], true);
        new AlertDialog.Builder(hostContext())
                .setTitle(pluginContext.getString(R.string.choose_series))
                .setSingleChoiceItems(items, series.ordinal(),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                d.dismiss();
                                selectSeries(all[which]);
                            }
                        })
                .setNegativeButton(pluginContext.getString(R.string.cancel), null)
                .show();
    }

    private void selectSeries(Series s) {
        series = s;
        refreshSeriesControls();
        // Clearing the search re-filters through the watcher; the FSTopo
        // list may still have to be fetched first.
        forestSearch.setText("");
        if (s == Series.FSTOPO && allFstopo.isEmpty())
            loadFstopo();
        else
            applyForestFilter();
    }

    /** "Hurricane Erin 2025 (3)": the event, and what choosing it costs. */
    private String stormLabel(int i, boolean withCount) {
        if (i < 0)
            return pluginContext.getString(R.string.all_events)
                    + (withCount ? " (" + allFlights.size() + ")" : "");
        if (i >= allStorms.size())
            return pluginContext.getString(R.string.choose_storm);
        final Depot.Storm s = allStorms.get(i);
        final String label = s.name
                + (s.year > 0 && !s.name.contains(String.valueOf(s.year))
                        ? " " + s.year : "")
                + ("NOAA".equalsIgnoreCase(s.agency) ? "" : " · " + s.agency);
        return withCount ? label + " (" + s.flightCount() + ")" : label;
    }

    private void chooseStorm() {
        if (allStorms.isEmpty()) {
            toast(pluginContext.getString(R.string.no_storms));
            return;
        }
        final String[] items = new String[allStorms.size() + 1];
        for (int i = 0; i < items.length; i++)
            items[i] = stormLabel(i - 1, true);
        new AlertDialog.Builder(hostContext())
                .setTitle(pluginContext.getString(R.string.choose_storm))
                .setSingleChoiceItems(items, stormIndex + 1,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                d.dismiss();
                                stormIndex = which - 1;
                                refreshSeriesControls();
                                forestSearch.setText("");
                                applyForestFilter();
                            }
                        })
                .setNegativeButton(pluginContext.getString(R.string.cancel), null)
                .show();
    }

    private void loadFstopo() {
        if (client == null || fstopoLoading)
            return;
        fstopoLoading = true;
        forestStatus.setText("Loading " + String.format(Locale.US, "%,d", fstopoCount)
                + " quads…");
        client.fetchFstopo(new DepotClient.SheetFileCallback() {
            @Override
            public void onSheets(List<Depot.RecMap> sheets) {
                fstopoLoading = false;
                Log.i(TAG, "fstopo quads=" + sheets.size());
                allFstopo.clear();
                allFstopo.addAll(sheets);
                refreshInstalledPackages();
                refreshSeriesControls();
                applyForestFilter();
            }

            @Override
            public void onError(String message) {
                fstopoLoading = false;
                forestStatus.setText("Could not read the FSTopo list: " + message);
            }
        });
    }

    private void showBaseMaps() {
        homeView.setVisibility(View.GONE);
        dtedView.setVisibility(View.GONE);
        forestView.setVisibility(View.GONE);
        nifcView.setVisibility(View.GONE);
        baseMapView.setVisibility(View.VISIBLE);
        if (!catalogFresh())
            loadCatalog();
        else {
            // A key changed in Tool Preferences flips which keyed rows count
            // as installed, so the check is redone on every visit.
            refreshInstalledMaps();
            applyCategoryFilter();
        }
    }

    /** The catalog is only fetched once the operator asks for elevation. */
    private void showDted() {
        homeView.setVisibility(View.GONE);
        baseMapView.setVisibility(View.GONE);
        forestView.setVisibility(View.GONE);
        nifcView.setVisibility(View.GONE);
        dtedView.setVisibility(View.VISIBLE);
        if (!catalogFresh())
            loadCatalog();
    }

    /**
     * Builds the country list from the catalog rather than a fixed list, so a
     * country added to the depot appears without a plugin release.
     */
    private void populateCountries() {
        final Map<String, String> names = new LinkedHashMap<>();
        for (Depot.Region r : allRegions)
            if (!r.country.isEmpty())
                names.put(r.country, r.countryName());

        countryCodes.clear();
        countryLabels.clear();
        // United States first: it is the complete one, and the fleet is there.
        if (names.containsKey("US")) {
            countryCodes.add("US");
            countryLabels.add(names.get("US"));
        }
        for (Map.Entry<String, String> e : names.entrySet()) {
            if ("US".equals(e.getKey()))
                continue;
            countryCodes.add(e.getKey());
            countryLabels.add(e.getValue());
        }
        countryIndex = 0;
    }

    /** The chooser is a dialog on ATAK's context, for the reason in the layout. */
    private void chooseCountry() {
        if (countryLabels.isEmpty())
            return;
        final String[] items = countryLabels.toArray(new String[0]);
        new AlertDialog.Builder(hostContext())
                .setTitle(pluginContext.getString(R.string.choose_country))
                .setSingleChoiceItems(items, countryIndex,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                countryIndex = which;
                                d.dismiss();
                                applyCountryFilter();
                            }
                        })
                .setNegativeButton(pluginContext.getString(R.string.cancel), null)
                .show();
    }

    private void applyCountryFilter() {
        final String code = (countryIndex >= 0
                && countryIndex < countryCodes.size())
                        ? countryCodes.get(countryIndex) : null;
        if (countryIndex >= 0 && countryIndex < countryLabels.size())
            countryButton.setText(countryLabels.get(countryIndex) + "  ▾");

        shown.clear();
        for (Depot.Region r : allRegions)
            if (code == null || code.equals(r.country)) {
                final boolean have = r.held() > 0;
                if (regionShown == Shown.INSTALLED && !have)
                    continue;
                if (regionShown == Shown.AVAILABLE && have)
                    continue;
                shown.add(r);
            }
        if (regionFilter != null)
            regionFilter.setText(pluginContext.getString(labelFor(regionShown))
                    + " (" + shown.size() + ")");
        adapter.notifyDataSetChanged();
    }

    /**
     * Asks the device what DTED it already holds and tells every region.
     *
     * One scan of the tree, shared by all 59 rows. Done off the main thread
     * because a full CONUS install is tens of thousands of files, then the rows
     * are refreshed on it.
     */
    private void measureRegions() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Map<String, Long> onDevice = RegionInstaller.scanInstalled();
                final MapView mv = MapView.getMapView();
                if (mv == null)
                    return;
                mv.post(new Runnable() {
                    @Override
                    public void run() {
                        onDeviceCells.clear();
                        onDeviceCells.putAll(onDevice);
                        for (Depot.Region r : allRegions)
                            r.measureAgainst(onDevice);
                        if (adapter != null)
                            adapter.notifyDataSetChanged();
                        Log.i(TAG, "device holds " + onDevice.size() + " DTED cells");
                    }
                });
            }
        }, "mapdepot-dted-scan").start();
    }

    /**
     * Every cell that some region other than this one is holding.
     *
     * Adjacent states share border cells -- that overlap is why the depot stores
     * cells once rather than per region -- so removing one region must not take
     * a cell its neighbour still uses. A region only votes if it actually holds
     * something; otherwise every region in the catalog would vote to keep
     * everything and nothing could ever be deleted.
     */
    private Set<String> cellsNeededElsewhere(Depot.Region removing) {
        final Set<String> keep = new HashSet<>();
        for (Depot.Region r : allRegions) {
            if (r == removing || r.held() <= 0)
                continue;
            keep.addAll(r.cells);
        }
        return keep;
    }

    private void confirmAndRemove(final Depot.Region region) {
        if (activeRegionId != null) {
            toast("Busy downloading — let it finish first.");
            return;
        }

        final Set<String> keep = cellsNeededElsewhere(region);

        // What this actually frees is the cells nobody else is holding, which
        // for a small state wedged between larger ones can be nothing at all.
        // Quoting the region's whole size promises something untrue.
        long freeable = 0L;
        int shared = 0;
        for (String key : region.cells) {
            final Long len = onDeviceCells.get(key);
            if (len == null)
                continue;
            if (keep.contains(key))
                shared++;
            else
                freeable += len;
        }

        if (freeable == 0L) {
            new AlertDialog.Builder(hostContext())
                    .setTitle(region.name + " shares all its elevation")
                    .setMessage("Elevation comes in one-degree squares, and every"
                            + " square covering " + region.name + " also covers "
                            + neighboursHolding(region) + ".\n\nRemoving "
                            + region.name + " alone frees nothing. To get the"
                            + " space back you would have to remove those too.")
                    .setPositiveButton(pluginContext.getString(R.string.ok), null)
                    .show();
            return;
        }

        final String note = shared > 0
                ? "\n\nCells shared with " + neighboursHolding(region) + " stay."
                : "";

        new AlertDialog.Builder(hostContext())
                .setTitle("Remove " + region.name + "?")
                .setMessage("Frees " + Depot.bytes(freeable) + "." + note)
                .setNegativeButton(pluginContext.getString(R.string.cancel), null)
                .setPositiveButton(pluginContext.getString(R.string.remove),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                removeRegion(region, keep);
                            }
                        })
                .show();
    }

    /** The regions, named, whose cells overlap this one and are on the device. */
    private String neighboursHolding(Depot.Region region) {
        final Set<String> mine = new HashSet<>(region.cells);
        final List<String> names = new ArrayList<>();
        for (Depot.Region r : allRegions) {
            if (r == region || r.held() <= 0)
                continue;
            for (String key : r.cells)
                if (mine.contains(key)) {
                    names.add(r.name);
                    break;
                }
        }
        if (names.isEmpty())
            return "another region";
        if (names.size() == 1)
            return names.get(0);

        // Named in full rather than "and 2 more". If the operator wants the
        // space back they have to remove every one of them, so an abbreviated
        // list is a list they cannot act on. Only past six does it stop being
        // a sentence and start being a wall.
        final int shown = Math.min(names.size(), 6);
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < shown; i++) {
            if (i > 0)
                sb.append(i == shown - 1 && shown == names.size()
                        ? " and " : ", ");
            sb.append(names.get(i));
        }
        if (shown < names.size())
            sb.append(" and ").append(names.size() - shown).append(" more");
        return sb.toString();
    }

    private void removeRegion(final Depot.Region region, final Set<String> keep) {
        status.setText("Removing " + region.name + "…");
        activeRegionId = region.id;
        progressById.put(region.id, 0);
        adapter.notifyDataSetChanged();

        installer.uninstall(region, keep, new RegionInstaller.Callback() {
            @Override
            public void onProgress(int cellsDone, int cellsTotal, long bytesDone,
                    long bytesTotal, String currentCell) {
                if (cellsTotal > 0)
                    progressById.put(region.id, cellsDone * 100 / cellsTotal);
                adapter.notifyDataSetChanged();
            }

            @Override
            public void onSkipped(String key) {
            }

            @Override
            public void onRetry(String key, int attempt, long haveBytes) {
            }

            @Override
            public void onComplete(int installed, int skipped) {
                if (cancelBarDted != null)
                    cancelBarDted.setVisibility(View.GONE);
            }

            @Override
            public void onRemoved(int removed, int kept, long freed) {
                activeRegionId = null;
                progressById.remove(region.id);
                completeById.remove(region.id);
                status.setText(region.name + " removed — " + Depot.bytes(freed)
                        + " freed" + (kept > 0 ? ", shared cells kept" : "")
                        + ". ATAK updates coverage within a few seconds.");
                // Re-read from disk rather than assuming; the scan is the
                // authority everywhere else on this screen too.
                measureRegions();
            }

            @Override
            public void onError(String message) {
                Log.w(TAG, region.id + " remove failed: " + message);
                activeRegionId = null;
                progressById.remove(region.id);
                adapter.notifyDataSetChanged();
                status.setText(region.name + " could not be removed: " + message);
            }
        });
    }

    private void loadCatalog() {
        if (client == null) {
            // Reachable if a view outlives the instance despite the above.
            // Saying so beats dying.
            Log.w(TAG, "loadCatalog with no client; plugin is stopped");
            if (status != null)
                status.setText("Map Depot is not running — reload the plugin.");
            return;
        }
        status.setText(pluginContext.getString(R.string.loading_catalog));
        client.fetchCatalog(new DepotClient.CatalogCallback() {
            @Override
            public void onCatalog(List<Depot.Region> fetched, long savedAt) {
                Log.i(TAG, "onCatalog regions=" + fetched.size() + " savedAt=" + savedAt);
                catalogLoaded = savedAt == 0;
                if (catalogLoaded)
                    catalogFetchedAt = System.currentTimeMillis();
                allRegions.clear();
                allRegions.addAll(fetched);
                measureRegions();
                populateCountries();
                applyCountryFilter();
                loadBaseMaps();
                loadForests();

                status.setText(savedAt > 0 ? savedLine(savedAt) : "");
            }

            @Override
            public void onError(String message) {
                Log.w(TAG, "catalog error: " + message);
                status.setText(nothingSavedLine());
            }
        });
    }

    /**
     * Fetching the manifest first means the confirmation can quote what this
     * device actually still needs, not the region's full size. Someone who
     * already holds most of a state should be told the real number.
     */
    private void confirmAndInstall(final Depot.Region region) {
        if (activeRegionId != null) {
            toast("Already downloading " + activeRegionId
                    + " — let it finish first.");
            return;
        }

        status.setText("Reading " + region.name + "…");
        client.fetchManifest(region, new DepotClient.ManifestCallback() {
            @Override
            public void onManifest(Depot.Manifest manifest) {
                final int held = RegionInstaller.installedCount(manifest);
                final int todo = manifest.cells.size() - held;

                if (todo == 0) {
                    completeById.add(region.id);
                    adapter.notifyDataSetChanged();
                    status.setText(region.name + " is already installed.");
                    return;
                }

                long bytes = 0;
                for (Depot.Cell c : manifest.cells)
                    bytes += c.gzBytes;
                final long approx = held > 0
                        ? (long) (bytes * (todo / (double) manifest.cells.size()))
                        : bytes;

                final StringBuilder msg = new StringBuilder();
                msg.append(todo).append(" cells, about ")
                        .append(Depot.bytes(approx)).append(" to download.");
                if (held > 0)
                    msg.append("\n").append(held)
                            .append(" already on this device will be skipped.");
                if (!region.complete)
                    msg.append("\n\nThis region is partial: the depot holds ")
                            .append(region.cellCount).append(" of ")
                            .append(region.needCount).append(" cells.");

                // "Reading X..." was put up before the fetch and has to come
                // back down however this dialog ends -- including a tap outside
                // it. Left standing it reads as a download stuck on the manifest,
                // which is what it was mistaken for.
                final boolean[] started = { false };

                // Strings still come from the plugin's own resources; only the
                // window comes from the host.
                new AlertDialog.Builder(hostContext())
                        .setTitle(region.name)
                        .setMessage(msg.toString())
                        .setPositiveButton(pluginContext.getString(R.string.download),
                                new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d, int w) {
                                        started[0] = true;
                                        start(region, manifest);
                                    }
                                })
                        .setNegativeButton(pluginContext.getString(R.string.cancel), null)
                        .setOnDismissListener(new DialogInterface.OnDismissListener() {
                            @Override
                            public void onDismiss(DialogInterface d) {
                                if (!started[0])
                                    status.setText("");
                            }
                        })
                        .show();
            }

            @Override
            public void onError(String message) {
                status.setText("Could not read " + region.name + ": " + message);
            }
        });
    }

    private void start(final Depot.Region region, Depot.Manifest manifest) {
        activeRegionId = region.id;
        progressById.put(region.id, 0);
        adapter.notifyDataSetChanged();

        if (cancelBarDted != null)
            cancelBarDted.setVisibility(View.VISIBLE);
        installer.install(manifest, new RegionInstaller.Callback() {
            @Override
            public void onProgress(int done, int total, long bytesDone,
                    long bytesTotal, String cell) {
                final int pct = total > 0 ? (int) (done * 100L / total) : 0;
                progressById.put(region.id, pct);
                status.setText(String.format("%s — %d of %d cells (%s of %s)",
                        region.name, done, total, Depot.bytes(bytesDone),
                        Depot.bytes(bytesTotal)));
                adapter.notifyDataSetChanged();
            }

            @Override
            public void onSkipped(String key) {
                // Already held; nothing to say about it beyond the running count.
            }

            @Override
            public void onRetry(String key, int attempt, long haveBytes) {
                status.setText(String.format(
                        "%s — connection dropped, resuming %s (attempt %d)",
                        region.name, key, attempt));
            }

            @Override
            public void onComplete(int installed, int skipped) {
                activeRegionId = null;
                progressById.remove(region.id);
                completeById.add(region.id);
                // The scan is the authority on this screen, and it was only
                // being re-run after a removal -- so a state that had just
                // finished downloading still read "N MB to download" beside its
                // Remove button, and did not appear under the Installed filter
                // until the plugin was reopened.
                measureRegions();
                adapter.notifyDataSetChanged();
                status.setText(String.format(
                        "%s installed — %d cells added, %d already held.",
                        region.name, installed, skipped));
            }

            @Override
            public void onError(String message) {
                activeRegionId = null;
                progressById.remove(region.id);
                adapter.notifyDataSetChanged();
                if (cancelBarDted != null)
                    cancelBarDted.setVisibility(View.GONE);
                if (RegionInstaller.CANCELLED.equals(message)) {
                    status.setText(region.name + " cancelled — what had already "
                            + "downloaded is kept.");
                    return;
                }
                status.setText(region.name + " failed: " + message);
                Log.w(TAG, region.id + " install failed: " + message);
            }

            @Override
            public void onRemoved(int removed, int kept, long freed) {
                // Removal has its own callback; this path only installs.
            }
        });
    }

    /**
     * A dialog needs a window, and a window needs an Activity token. The plugin
     * context is not an Activity -- it exists to resolve this plugin's own
     * resources -- so anything with a window has to be built against ATAK's
     * context instead. Getting this wrong does not degrade: it throws
     * BadTokenException on the main thread and takes ATAK down with it.
     */
    /**
     * Where the catalog, the FSTopo list and the region manifests are saved:
     * atak/tools, beside every other plugin's data, which nothing clears behind
     * the operator's back. Not anywhere under the plugin context either: its
     * dirs belong to the plugin package's uid, which ATAK's process cannot
     * write -- every document written there vanished and Base Maps came up
     * empty.
     */
    private static File savedDir() {
        return FileSystemUtils.getItem("tools/mapdepot");
    }

    /**
     * Where builds up to 1.9 saved them: ATAK's app cache dir, which Android
     * empties under storage pressure. Read once at start to bring them across.
     */
    private File oldCacheDir() {
        return new File(hostContext().getCacheDir(), "mapdepot");
    }

    /**
     * True when the phone has no network at all, as against a server that did
     * not answer. Decides which of the two the status line says.
     */
    @SuppressWarnings("deprecation")
    private boolean noNetwork() {
        try {
            final ConnectivityManager cm = (ConnectivityManager) hostContext()
                    .getSystemService(Context.CONNECTIVITY_SERVICE);
            final NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
            return ni == null || !ni.isConnected();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * The line a catalog section shows when it is working from the copy on
     * this phone: why, and how old that copy is. Never the host error --
     * "Unable to resolve host" tells a crew on a ridge nothing.
     */
    private String savedLine(long savedAt) {
        return (noNetwork() ? "No network." : "Depot unreachable.")
                + " Showing what this phone saved " + ago(savedAt) + ".";
    }

    /** The same, for a section with nothing saved to fall back on. */
    private String nothingSavedLine() {
        return (noNetwork() ? "No network" : "Depot unreachable")
                + ", and nothing saved on this phone yet.";
    }

    /** A status line, with the saved-copy line under it when that is what shows. */
    private String withSaved(String line, long savedAt) {
        return savedAt > 0 ? line + "\n" + savedLine(savedAt) : line;
    }

    private static String ago(long when) {
        final long min = Math.max(0L, System.currentTimeMillis() - when) / 60000L;
        if (min < 1)
            return "just now";
        if (min < 60)
            return min + " min ago";
        final long hours = min / 60;
        if (hours < 48)
            return hours + " h ago";
        return (hours / 24) + " days ago";
    }

    private Context hostContext() {
        final MapView mv = MapView.getMapView();
        return mv != null ? mv.getContext() : pluginContext;
    }

    /**
     * Base maps come from the same catalog document, so they cost no extra
     * request -- the client re-reads its cached copy rather than fetching again.
     */
    private void loadBaseMaps() {
        if (client == null)
            return;
        client.fetchBaseMaps(new DepotClient.BaseMapCallback() {
            @Override
            public void onBaseMaps(List<Depot.BaseMap> maps, long savedAt) {
                Log.i(TAG, "onBaseMaps count=" + maps.size() + " savedAt=" + savedAt);
                mapsSavedAt = savedAt;
                allMaps.clear();
                allMaps.addAll(maps);

                categories.clear();
                categories.add(pluginContext.getString(R.string.all_categories));
                for (Depot.BaseMap m : maps)
                    if (!categories.contains(m.category))
                        categories.add(m.category);
                categoryIndex = 0;
                refreshInstalledMaps();
                applyCategoryFilter();
            }

            @Override
            public void onError(String message) {
                Log.w(TAG, "base map error: " + message);
                mapStatus.setText(nothingSavedLine());
            }
        });
    }

    /** Ask the filesystem once per listing rather than once per row. */
    private void refreshInstalledMaps() {
        installedMaps.clear();
        for (Depot.BaseMap m : allMaps)
            if (BaseMapInstaller.isInstalled(m))
                installedMaps.add(m.id);
    }

    private void chooseCategory() {
        if (categories.isEmpty())
            return;
        final String[] items = categories.toArray(new String[0]);
        new AlertDialog.Builder(hostContext())
                .setTitle(pluginContext.getString(R.string.choose_category))
                .setSingleChoiceItems(items, categoryIndex,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                categoryIndex = which;
                                d.dismiss();
                                applyCategoryFilter();
                            }
                        })
                .setNegativeButton(pluginContext.getString(R.string.cancel), null)
                .show();
    }

    private void applyCategoryFilter() {
        final String want = (categoryIndex > 0
                && categoryIndex < categories.size())
                        ? categories.get(categoryIndex) : null;
        if (!categories.isEmpty())
            categoryButton.setText(categories.get(categoryIndex) + "  \u25be");

        basemapFilter.setText(pluginContext.getString(labelFor(mapShown))
                + " (" + shownMaps.size() + ")");

        shownMaps.clear();
        for (Depot.BaseMap m : allMaps) {
            if (want != null && !want.equals(m.category))
                continue;
            final boolean have = installedMaps.contains(m.id);
            if (mapShown == Shown.INSTALLED && !have)
                continue;
            if (mapShown == Shown.AVAILABLE && have)
                continue;
            shownMaps.add(m);
        }
        if ("Overlay".equalsIgnoreCase(want))
            sectionOverlays();
        mapAdapter.notifyDataSetChanged();

        if (shownMaps.isEmpty())
            mapStatus.setText(withSaved(mapShown == Shown.INSTALLED
                    ? "None installed here yet."
                    : mapShown == Shown.AVAILABLE
                            ? "All of these are installed."
                            : "Nothing in this category.", mapsSavedAt));
        else
            mapStatus.setText(withSaved(String.format("%d of %d installed",
                    installedMaps.size(), allMaps.size()), mapsSavedAt));
    }

    /** The overlay groups in the order the operator asked for; anything else last. */
    private static final String[] OVERLAY_GROUPS = {
            "Roads, streets and POI", "Trails", "Land and boundaries",
            "Hazards", "Weather", "Terrain" };

    private static int groupRank(String g) {
        for (int i = 0; i < OVERLAY_GROUPS.length; i++)
            if (OVERLAY_GROUPS[i].equalsIgnoreCase(g))
                return i;
        return OVERLAY_GROUPS.length;
    }

    /**
     * The overlay list, sectioned: a cyan heading per group, roads and
     * points of interest first, because that is what goes over fresh
     * imagery. The operator (2026-10-11): "section the types of overlays
     * when you click on overlays ... roads and streets and poi at top".
     */
    private void sectionOverlays() {
        final List<Depot.BaseMap> maps = new ArrayList<>(shownMaps);
        Collections.sort(maps, new Comparator<Depot.BaseMap>() {
            @Override
            public int compare(Depot.BaseMap a, Depot.BaseMap b) {
                final int c = Integer.compare(groupRank(a.group), groupRank(b.group));
                return c != 0 ? c : a.name.compareToIgnoreCase(b.name);
            }
        });
        shownMaps.clear();
        String last = null;
        for (Depot.BaseMap m : maps) {
            final String g = groupRank(m.group) < OVERLAY_GROUPS.length
                    ? OVERLAY_GROUPS[groupRank(m.group)] : "Other";
            if (!g.equals(last)) {
                shownMaps.add(Depot.BaseMap.heading(g));
                last = g;
            }
            shownMaps.add(m);
        }
    }

    private void install(final Depot.BaseMap map) {
        if (map.needsKey() && BaseMapInstaller.keyFor(map.keyProvider).isEmpty()) {
            askForKey(map);
            return;
        }
        mapStatus.setText("Getting " + map.name + "…");
        baseMaps.install(map, mapCallback());
    }

    /**
     * Takes the operator's API key on the spot rather than sending them to
     * Tool Preferences: the row they tapped is the one that needs it, and the
     * install goes ahead as soon as a key is saved. The same key is editable
     * later from the settings screen.
     */
    private void askForKey(final Depot.BaseMap map) {
        final String who = map.provider.isEmpty() ? map.keyProvider : map.provider;
        final EditText box = new EditText(hostContext());
        box.setSingleLine(true);
        box.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        final AlertDialog.Builder b = new AlertDialog.Builder(hostContext())
                .setTitle(pluginContext.getString(R.string.key_needed_title, who))
                .setMessage(pluginContext.getString(R.string.key_needed_message, who))
                .setView(box)
                .setPositiveButton(pluginContext.getString(R.string.add),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                final String k = box.getText().toString().trim();
                                if (k.isEmpty()
                                        || !BaseMapInstaller.setKey(map.keyProvider, k)) {
                                    mapStatus.setText(pluginContext.getString(
                                            R.string.key_bad_shape));
                                    return;
                                }
                                install(map);
                            }
                        })
                .setNegativeButton(pluginContext.getString(R.string.cancel), null);
        if (!map.keyUrl.isEmpty())
            b.setNeutralButton(pluginContext.getString(R.string.get_key),
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            openUrl(map.keyUrl);
                        }
                    });
        b.show();
    }

    /** Hands an https address to the device browser; a toast if there is none. */
    private void openUrl(String url) {
        try {
            hostContext().startActivity(new android.content.Intent(
                    android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException noBrowser) {
            toast(url);
        }
    }

    private void uninstall(final Depot.BaseMap map) {
        mapStatus.setText("Removing " + map.name + "…");
        baseMaps.uninstall(map, mapCallback());
    }

    private BaseMapInstaller.Callback mapCallback() {
        return new BaseMapInstaller.Callback() {
            @Override
            public void onInstalled(Depot.BaseMap m, java.io.File dest) {
                installedMaps.add(m.id);
                mapAdapter.notifyDataSetChanged();
                mapStatus.setText(m.name
                        + (m.overlay()
                                ? " installed — switched on over the map. Overlay Manager › Image Overlay turns it off."
                                : " installed — it is in ATAK's map source list."));
            }

            @Override
            public void onRemoved(Depot.BaseMap m) {
                installedMaps.remove(m.id);
                mapAdapter.notifyDataSetChanged();
                mapStatus.setText(m.name + " removed.");
            }

            @Override
            public void onError(Depot.BaseMap m, String message) {
                Log.w(TAG, "base map " + m.id + ": " + message);
                if (BaseMapInstaller.STALE_CATALOG.equals(message)) {
                    // Refetch and say so, rather than reporting a checksum
                    // failure the operator can neither understand nor act on.
                    mapStatus.setText("Depot has a newer " + m.name
                            + " — refreshing, then tap Get again.");
                    loadBaseMaps();
                    return;
                }
                if (BaseMapInstaller.NO_KEY.equals(message)) {
                    askForKey(m);
                    return;
                }
                mapStatus.setText(m.name + " failed: " + message);
            }
        };
    }

    // ------------------------------------------------------------ public lands

    private void loadForests() {
        if (client == null)
            return;
        client.fetchForests(new DepotClient.ForestCallback() {
            @Override
            public void onForests(List<Depot.Forest> fetched,
                    List<Depot.RecMap> recMaps, Depot.Sheets sheets,
                    Depot.Storms storms, long savedAt) {
                forestsSavedAt = savedAt;
                allStorms.clear();
                allStorms.addAll(storms.events);
                allFlights.clear();
                for (Depot.Storm s : allStorms)
                    allFlights.addAll(s.flights);
                stormsBuiltAt = storms.builtAt;
                if (stormIndex >= allStorms.size())
                    stormIndex = -1;
                Log.i(TAG, "onForests forests=" + fetched.size()
                        + " recmaps=" + recMaps.size()
                        + " regional=" + sheets.regional.size()
                        + " 100k=" + sheets.k100.size()
                        + " fstopo=" + sheets.fstopoCount);
                allForests.clear();
                allForests.addAll(fetched);
                allRecMaps.clear();
                allRecMaps.addAll(recMaps);
                allRegional.clear();
                allRegional.addAll(sheets.regional);
                allK100.clear();
                allK100.addAll(sheets.k100);
                fstopoCount = sheets.fstopoCount;
                refreshInstalledPackages();
                refreshSeriesControls();
                applyForestFilter();
                syncOutlines();
            }

            @Override
            public void onError(String message) {
                Log.w(TAG, "package catalog: " + message);
                forestStatus.setText(nothingSavedLine());
            }
        });
    }

    /** Ask the filesystem once per listing rather than once per row. */
    private void refreshInstalledPackages() {
        installedPackages.clear();
        installedPackages.addAll(PackageInstaller.installedIds(allForests));
        installedPackages.addAll(PackageInstaller.installedIds(allRecMaps));
        installedPackages.addAll(PackageInstaller.installedIds(allRegional));
        installedPackages.addAll(PackageInstaller.installedIds(allK100));
        installedPackages.addAll(PackageInstaller.installedIds(allFstopo));
        for (Depot.Storm s : allStorms) {
            installedPackages.addAll(PackageInstaller.installedIds(s.flights));
            installedPackages.addAll(PackageInstaller.installedIds(s.packs()));
        }
        installedPackages.addAll(PackageInstaller.installedIds(allScenes));
    }

    private void applyForestFilter() {
        refreshOutlinesButton();
        final String q = forestSearch.getText().toString().trim().toLowerCase();

        shownPackages.clear();
        for (Depot.Package p : sourceList()) {
            if (!q.isEmpty() && !matches(p, q))
                continue;
            final boolean have = installedPackages.contains(p.id())
                    || (p instanceof Depot.Flight && ((Depot.Flight) p).pack != null
                            && installedPackages.contains(((Depot.Flight) p).pack.id()));
            if (packageShown == Shown.INSTALLED && !have)
                continue;
            if (packageShown == Shown.AVAILABLE && have)
                continue;
            shownPackages.add(p);
        }
        // After the rebuild, not before it: the label used to carry the
        // previous list's count ("Installed (50)" with four installed).
        packageFilter.setText(pluginContext.getString(labelFor(packageShown))
                + " (" + shownPackages.size() + ")");
        final int hidden = sortNearest();
        packageAdapter.notifyDataSetChanged();

        if (activeForestId == null)
            forestStatus.setText(withSaved(statusLine(q, hidden), forestsSavedAt));
    }

    /**
     * For the series that carry a location, order the list by distance from
     * the map and keep the nearest few hundred. Nobody scrolls eighteen
     * thousand quads, and a crew does not know its quad's name; it knows
     * where it is standing. Returns how many rows were cut, so the status
     * line can say so rather than let a trimmed list read as the whole set.
     *
     * Computed when the list is rebuilt -- opening the section, choosing a
     * series, typing -- not on every map move: that callback is the GL
     * thread and this touches views.
     */
    /**
     * Whether the current list can be sorted by distance: true as soon as any
     * entry carries a location. Decided by the catalog, not by which list it
     * is, so a series that gains locations sorts without a plugin release.
     */
    private boolean locatedList() {
        // Passes are listed newest first, as the service sends them.
        if (packageMode == PackageMode.SATELLITE)
            return false;
        for (Depot.Package p : sourceList()) {
            if (p instanceof Depot.Located && ((Depot.Located) p).located())
                return true;
        }
        return false;
    }

    /** The point "nearest" is measured from; falls back to the map center. */
    private GeoPoint anchor() {
        if (packageMode == PackageMode.SATELLITE && !Double.isNaN(sceneLat))
            return new GeoPoint(sceneLat, sceneLon);
        final GeoPoint self = nearMe ? Distance.selfPoint() : null;
        return self != null ? self : Distance.mapCenter();
    }

    private int sortNearest() {
        distances.clear();
        if (packageMode == PackageMode.SATELLITE) {
            sortScenes();
            return 0;
        }
        if (!locatedList())
            return 0;
        final GeoPoint center = anchor();
        if (center == null)
            return 0;
        for (Depot.Package p : shownPackages) {
            if (p instanceof Depot.Located && ((Depot.Located) p).located()) {
                final Depot.Located m = (Depot.Located) p;
                distances.put(p.id(), new double[] {
                        Distance.meters(center, m.lat(), m.lon()),
                        Distance.bearing(center, m.lat(), m.lon()) });
            }
        }
        if (packageMode == PackageMode.STORMS && newestFirst) {
            Collections.sort(shownPackages, new Comparator<Depot.Package>() {
                @Override
                public int compare(Depot.Package a, Depot.Package b) {
                    final Depot.Flight fa = flightOf(a), fb = flightOf(b);
                    final String da = fa != null ? fa.date : "";
                    final String db = fb != null ? fb.date : "";
                    int c = db.compareTo(da);
                    if (c == 0)
                        c = (fb != null ? fb.name : "").compareTo(fa != null ? fa.name : "");
                    if (c == 0)
                        c = Boolean.compare(a instanceof Depot.Pack, b instanceof Depot.Pack);
                    return c;
                }
            });
        } else {
            Collections.sort(shownPackages, new Comparator<Depot.Package>() {
                @Override
                public int compare(Depot.Package a, Depot.Package b) {
                    final double[] da = distances.get(a.id());
                    final double[] db = distances.get(b.id());
                    final int c = Double.compare(da == null ? Double.MAX_VALUE : da[0],
                            db == null ? Double.MAX_VALUE : db[0]);
                    // A pack shares its flight's center; it goes under the flight.
                    return c != 0 ? c
                            : Boolean.compare(a instanceof Depot.Pack, b instanceof Depot.Pack);
                }
            });
        }
        if (shownPackages.size() <= NEAREST_CAP)
            return 0;
        // The row that is downloading stays on screen whatever the map does.
        if (activeForestId != null) {
            for (int i = NEAREST_CAP; i < shownPackages.size(); i++) {
                if (activeForestId.equals(shownPackages.get(i).id())) {
                    shownPackages.add(0, shownPackages.remove(i));
                    break;
                }
            }
        }
        final int hidden = shownPackages.size() - NEAREST_CAP;
        shownPackages.subList(NEAREST_CAP, shownPackages.size()).clear();
        return hidden;
    }

    /**
     * Newest first as the service sends them, or nearest the point searched:
     * the passes that cover the point come first, newest of those on top,
     * then the rest by distance. A tile's center can be 50 km from a point it
     * covers, so distance alone would rank a pass over the crew below one
     * beside it.
     */
    private void sortScenes() {
        if (satSort == SatSort.NEWEST || Double.isNaN(sceneLat))
            return;
        final GeoPoint at = new GeoPoint(sceneLat, sceneLon);
        for (Depot.Package p : shownPackages) {
            final Depot.Scene sc = (Depot.Scene) p;
            if (sc.located())
                distances.put(p.id(), new double[] {
                        Distance.meters(at, sc.lat(), sc.lon()),
                        Distance.bearing(at, sc.lat(), sc.lon()) });
        }
        Collections.sort(shownPackages, new Comparator<Depot.Package>() {
            @Override
            public int compare(Depot.Package a, Depot.Package b) {
                final Depot.Scene sa = (Depot.Scene) a, sb = (Depot.Scene) b;
                final boolean ca = covers(sa, at), cb = covers(sb, at);
                if (ca != cb)
                    return ca ? -1 : 1;
                if (ca)
                    return sb.date.compareTo(sa.date);
                final double[] da = distances.get(a.id());
                final double[] db = distances.get(b.id());
                return Double.compare(da == null ? Double.MAX_VALUE : da[0],
                        db == null ? Double.MAX_VALUE : db[0]);
            }
        });
    }

    private static boolean covers(Depot.Scene sc, GeoPoint at) {
        final double[] b = sc.bounds();
        return b != null && at.getLatitude() >= b[0] && at.getLatitude() <= b[2]
                && at.getLongitude() >= b[1] && at.getLongitude() <= b[3];
    }

    /** The flight a storms row belongs to: itself, or the flight its pack copies. */
    private static Depot.Flight flightOf(Depot.Package p) {
        if (p instanceof Depot.Flight)
            return (Depot.Flight) p;
        if (p instanceof Depot.Pack)
            return ((Depot.Pack) p).flight;
        return null;
    }

    /** The row's second line, with how far away the sheet is when that is known. */
    private String describeRow(Depot.Package pkg) {
        final double[] d = distances.get(pkg.id());
        if (d == null)
            return pkg.describe();
        // "6 ft S" after a go-to is true and silly: the map is sitting on the
        // flight. When the anchor is inside the package's own box, say that.
        if (pkg instanceof Depot.Bounded) {
            final double[] b = ((Depot.Bounded) pkg).bounds();
            final GeoPoint c = anchor();
            if (b != null && c != null && c.getLatitude() >= b[0]
                    && c.getLatitude() <= b[2] && c.getLongitude() >= b[1]
                    && c.getLongitude() <= b[3])
                return pkg.describe() + " · in view";
        }
        return pkg.describe() + " · " + Distance.format(d[0], d[1]);
    }

    /**
     * An empty list has three quite different causes and they must not look
     * alike: nothing published, nothing matched, or nothing installed. Reporting
     * "0 of 0 installed" for all three reads as a broken search.
     */
    private String statusLine(String query, int hidden) {
        final int total = sourceList().size();
        if (packageMode == PackageMode.STORMS)
            return stormsStatus(query, hidden, total);
        if (packageMode == PackageMode.SATELLITE)
            return satelliteStatus(query, total);
        if (total == 0) {
            if (packageMode != PackageMode.RECMAPS)
                return "No packages in the catalog yet.";
            if (series == Series.FSTOPO && fstopoLoading)
                return "Loading quads…";
            return "No " + seriesLabel(series, false) + " maps in the catalog yet.";
        }

        if (hidden > 0)
            return String.format(Locale.US,
                    "Nearest %d of %,d%s to %s · %s",
                    shownPackages.size(), shownPackages.size() + hidden,
                    query.isEmpty() ? "" : " matching",
                    nearMe ? "you" : "the map center",
                    nearMe ? "search for the rest" : "move the map or search");

        if (shownPackages.isEmpty()) {
            if (packageShown == Shown.INSTALLED)
                return "None installed yet.";
            if (packageShown == Shown.AVAILABLE)
                return "All " + total + " are installed.";
            return "Nothing matches \"" + query + "\" in " + total + ".";
        }

        int held = 0;
        for (Depot.Package p : sourceList())
            if (installedPackages.contains(p.id()))
                held++;

        if (!query.isEmpty())
            return String.format("%d of %d shown · %d installed",
                    shownPackages.size(), total, held);
        return String.format("%d of %d installed", held, total);
    }

    private String satelliteStatus(String query, int total) {
        if (scenesLoading)
            return "Looking for satellite passes…";
        if (scenesError != null)
            return "Could not look for satellite passes: " + scenesError;
        if (total == 0)
            return "No passes in the last " + SCENE_DAYS
                    + " days here. Move the map and tap Search Here.";
        int held = 0;
        for (Depot.Package p : allScenes)
            if (installedPackages.contains(p.id()))
                held++;
        if (shownPackages.isEmpty())
            return query.isEmpty() ? "Nothing to show in this view."
                    : "Nothing matches \"" + query + "\" in " + total + ".";
        return total + " passes in " + SCENE_DAYS + " days "
                + (satSort == SatSort.ME ? "around you"
                        : "around the map center")
                + " · " + held + " installed";
    }

    /**
     * The flights list says which event it is showing and when the depot
     * last looked at NOAA, because during a storm new flights land daily and
     * "newest" is only as fresh as that.
     */
    private String stormsStatus(String query, int hidden, int total) {
        final String built = stormsBuiltAt.length() >= 10
                ? " · catalog " + Depot.prettyDate(stormsBuiltAt.substring(0, 10)) : "";
        if (allStorms.isEmpty())
            return "No disaster aerials in the catalog yet." + built;
        if (stormIndex < 0) {
            int held = 0;
            for (Depot.Package p : allFlights)
                if (installedPackages.contains(p.id())
                        || (p instanceof Depot.Flight && ((Depot.Flight) p).pack != null
                                && installedPackages.contains(((Depot.Flight) p).pack.id())))
                    held++;
            return allStorms.size() + " events · " + held + " of " + allFlights.size()
                    + " flights on the phone" + built;
        }
        final Depot.Storm s = allStorms.get(stormIndex);
        int held = 0;
        total = 0;
        for (Depot.Package p : sourceList()) {
            if (!(p instanceof Depot.Flight))
                continue;
            total++;
            if (installedPackages.contains(p.id()))
                held++;
        }
        if (shownPackages.isEmpty() && total > 0) {
            if (packageShown == Shown.INSTALLED)
                return "None of " + s.name + " installed yet." + built;
            if (packageShown == Shown.AVAILABLE)
                return "All of " + s.name + " is installed." + built;
            return "Nothing matches \"" + query + "\" in " + s.name + "." + built;
        }
        final String what = (s.detail.isEmpty() ? s.name : s.detail) + " · " + s.agency;
        return what + " · " + s.span() + " · " + held + " of " + total + " installed"
                + (hidden > 0 ? " · nearest " + shownPackages.size() + " shown" : "")
                + built;
    }

    /**
     * Search the unit as well as the name: someone looking for a district often
     * knows the forest it sits in rather than the district's own name.
     */
    private static boolean matches(Depot.Package p, String q) {
        if (p.name().toLowerCase().contains(q))
            return true;
        final Depot.Flight f = flightOf(p);
        if (f != null)
            return f.name.toLowerCase().contains(q) || f.date.contains(q)
                    || f.source.toLowerCase().contains(q);
        if (p instanceof Depot.RecMap) {
            final Depot.RecMap m = (Depot.RecMap) p;
            return m.unit.toLowerCase().contains(q)
                    || m.state.toLowerCase().contains(q);
        }
        return false;
    }

    private void showCancelBar(boolean visible) {
        if (cancelBar != null)
            cancelBar.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void installPackage(final Depot.Package pkg) {
        if (activeForestId != null) {
            toast("Already downloading — let it finish first.");
            return;
        }
        activeForestId = pkg.id();
        forestDone = 0;
        forestTotal = pkg.bytes();
        forestStatus.setText("Getting " + pkg.name() + " — "
                + Depot.bytes(pkg.bytes()));
        showCancelBar(true);
        packageAdapter.notifyDataSetChanged();
        packages.install(pkg, packageCallback());
    }

    private void uninstallPackage(final Depot.Package pkg) {
        forestStatus.setText("Removing " + pkg.name() + "…");
        packages.uninstall(pkg, packageCallback());
        if (pkg instanceof Depot.Scene) {
            final Depot.Scene twin = ((Depot.Scene) pkg).otherView();
            if (PackageInstaller.isInstalled(twin))
                packages.uninstall(twin, packageCallback());
        }
    }


    private PackageInstaller.Callback packageCallback() {
        return new PackageInstaller.Callback() {
            @Override
            public void onProgress(Depot.Package p, long done, long total) {
                forestDone = done;
                forestTotal = total;
                forestStatus.setText(p.name() + " — " + Depot.bytes(done)
                        + " of " + Depot.bytes(total));
                packageAdapter.notifyDataSetChanged();
            }

            @Override
            public void onInstalled(final Depot.Package p, java.io.File dest) {
                activeForestId = null;
                showCancelBar(false);
                installedPackages.add(p.id());
                packageAdapter.notifyDataSetChanged();
                if (p instanceof Depot.Scene) {
                    // A pass is held in both views, a few hundred bytes each,
                    // and the View button switches which one draws.
                    final Depot.Scene twin = ((Depot.Scene) p).otherView();
                    if (!PackageInstaller.isInstalled(twin)) {
                        activeForestId = twin.id();
                        packages.install(twin, packageCallback());
                        return;
                    }
                    forestStatus.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            PackageInstaller.showSceneView(sceneStyle);
                            packageAdapter.notifyDataSetChanged();
                        }
                    }, 1500L);
                    forestStatus.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            PackageInstaller.showSceneView(sceneStyle);
                            packageAdapter.notifyDataSetChanged();
                        }
                    }, 4000L);
                    forestStatus.setText(((Depot.Scene) p).name()
                            + " added in both views. Showing "
                            + Depot.Scene.viewLabel(sceneStyle)
                            + "; the View button switches.");
                    return;
                }
                if (p instanceof Depot.Flight || p instanceof Depot.Pack) {
                    syncOutlines();
                    // ATAK keeps an on/off state per overlay name and a
                    // fresh download of a name that was hidden came in
                    // hidden (2026-10-11). What was just asked for is on.
                    forestStatus.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (Boolean.FALSE.equals(PackageInstaller.isVisible(p)))
                                PackageInstaller.setVisible(p, true);
                            packageAdapter.notifyDataSetChanged();
                        }
                    }, 1500L);
                }
                forestStatus.setText(p.name()
                        + (p instanceof Depot.Pack
                                ? " saved — this flight now works with no signal."
                                : p instanceof Depot.Flight || p instanceof Depot.Scene
                                ? " installed — drawing over the map; tap the row to go there."
                                : " installed — it is in ATAK's map layer list."));
            }

            @Override
            public void onRemoved(Depot.Package p) {
                installedPackages.remove(p.id());
                packageAdapter.notifyDataSetChanged();
                applyForestFilter();
                if (p instanceof Depot.Flight || p instanceof Depot.Pack)
                    syncOutlines();
            }

            @Override
            public void onError(Depot.Package p, String message) {
                activeForestId = null;
                showCancelBar(false);
                packageAdapter.notifyDataSetChanged();
                if (PackageInstaller.CANCELLED.equals(message)) {
                    forestStatus.setText(p.name() + " cancelled — nothing kept.");
                    return;
                }
                Log.w(TAG, "package " + p.id() + ": " + message);
                forestStatus.setText(p.name() + " failed: " + message);
            }
        };
    }

    // ------------------------------------------------------------------ NIFC

    /**
     * Opens the incident map browser at the operator's own GACC.
     *
     * The GACC is remembered rather than asked for every time -- a crew works one
     * geographic area for a whole assignment -- but it is a button, not a
     * one-time setting, because the next fire may be somewhere else.
     */
    private void showSource(MapSource wanted) {
        homeView.setVisibility(View.GONE);
        dtedView.setVisibility(View.GONE);
        baseMapView.setVisibility(View.GONE);
        forestView.setVisibility(View.GONE);
        nifcView.setVisibility(View.VISIBLE);

        // Switching archives starts over: the two do not share a path, and a
        // half-walked NIFC folder means nothing to UASWFC.
        final boolean switched = source == null
                || !source.id().equals(wanted.id());
        source = wanted;
        if (switched) {
            nifcStack.clear();
            gaccPaths.clear();
            gaccLabels.clear();
            nifcOffline = false;
            nifcRows.clear();
            nifcAdapter.notifyDataSetChanged();
        }

        if (!nifcStack.isEmpty())
            return;

        final String remembered = loadGacc();
        if (remembered == null || remembered.isEmpty()) {
            nifcGaccButton.setText(pluginContext.getString(R.string.nifc_pick_gacc));
            loadGaccList(true);
        } else {
            nifcGaccButton.setText(labelForPath(remembered));
            if (gaccPaths.isEmpty())
                loadGaccList(false);
            browse(remembered, decodeSegment(remembered), Nav.ROOT);
        }
    }

    /**
     * The GACC list comes from the server rather than a compiled-in list, so a
     * region added or renamed upstream appears without a plugin release.
     *
     * @param thenPrompt open the chooser as soon as it arrives, for an operator
     *        who has not picked one yet
     */
    private void loadGaccList(final boolean thenPrompt) {
        nifcStatus.setText(pluginContext.getString(R.string.nifc_loading));
        source.list("", new MapSource.ListingCallback() {
            @Override
            public void onListing(String path, List<MapSource.Entry> entries,
                    int hidden) {
                gaccPaths.clear();
                gaccLabels.clear();
                for (final MapSource.Entry e : entries) {
                    if (!e.directory)
                        continue;
                    gaccPaths.add(e.href);
                    gaccLabels.add(prettyGacc(e.name));
                }
                nifcStatus.setText("");
                if (thenPrompt)
                    chooseGacc();
            }

            @Override
            public void onError(String message) {
                Log.w(TAG, "area list: " + message);
                // Opened with an area already chosen, the folder listing beside
                // this one says what is wrong, and this must not talk over it.
                // Asked for to choose one, this is the only answer coming.
                if (thenPrompt)
                    showNifcOffline();
            }
        });
    }

    /**
     * A button opening a chooser, never a Spinner: a Spinner's dropdown is a
     * Dialog built from the context that inflated it, and on the plugin context
     * that is a BadTokenException that takes ATAK down with it.
     */
    private void chooseGacc() {
        if (gaccPaths.isEmpty()) {
            loadGaccList(true);
            return;
        }
        final String[] items = gaccLabels.toArray(new String[0]);
        final String current = loadGacc();
        int checked = -1;
        for (int i = 0; i < gaccPaths.size(); i++) {
            if (gaccPaths.get(i).equals(current))
                checked = i;
        }
        new AlertDialog.Builder(hostContext())
                .setTitle(pluginContext.getString(R.string.nifc_pick_gacc))
                .setSingleChoiceItems(items, checked,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                d.dismiss();
                                final String path = gaccPaths.get(which);
                                saveGacc(path);
                                nifcGaccButton.setText(gaccLabels.get(which));
                                nifcStack.clear();
                                browse(path, decodeSegment(path), Nav.ROOT);
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * How the browser arrived at a folder, which decides what happens to the
     * history. One boolean used to do this and got Back wrong: going back
     * cleared the whole stack, so the first Back worked and the second left the
     * panel entirely.
     */
    private enum Nav {
        /** A step deeper. Remember where we were. */
        DESCEND,
        /** A new starting point. Forget everything above it. */
        ROOT,
        /** A step back out. The entry was already popped. */
        BACK
    }

    private void browse(final String encodedPath, final String decodedPath,
            final Nav nav) {
        if (nav == Nav.ROOT)
            nifcStack.clear();
        else if (nav == Nav.DESCEND)
            nifcStack.push(new String[] {
                    nifcPath, nifcDecodedPath
            });

        nifcPath = encodedPath;
        nifcDecodedPath = decodedPath;
        nifcRows.clear();
        nifcAdapter.notifyDataSetChanged();
        nifcStatus.setText(pluginContext.getString(R.string.nifc_loading));

        source.list(encodedPath, new MapSource.ListingCallback() {
            @Override
            public void onListing(String path, List<MapSource.Entry> entries,
                    int hidden) {
                // A listing that arrives after the operator has moved on is not
                // an error, but it must not overwrite where they are now.
                if (!path.equals(nifcPath))
                    return;

                nifcOffline = false;
                nifcAllRows.clear();

                // Pinned fires ride at the top of the first screen only. Deeper
                // in, the operator has already arrived somewhere and a shortcut
                // back to the top of a different fire is just noise.
                if (nifcStack.isEmpty()) {
                    for (final Pinned.Entry pin : Pinned.all(source.id())) {
                        if (!pin.path.equals(path))
                            nifcAllRows.add(pin);
                    }
                }

                final List<MapSource.Entry> folders = new ArrayList<>();
                for (final MapSource.Entry e : entries) {
                    if (e.directory)
                        folders.add(e);
                }
                nifcAllRows.addAll(newestFirst(folders));

                final List<MapSource.Posting> postings =
                        source.postingsFor(entries, path, decodedPath);

                // Asked of the disk, not remembered, so it is still right after
                // ATAK has been closed and reopened -- and after a map has been
                // deleted outside the plugin.
                //
                // Checked by URL first: a map downloaded under an older naming
                // rule sits on disk under a name today's rules would not
                // produce, and a name-only check would offer to download it all
                // over again. Where the index knows it, the row takes the name
                // the file actually has, so Remove and "go there" reach it.
                final List<Object> resolved = new ArrayList<>();
                for (final MapSource.Posting posting : postings) {
                    final InstalledIndex.Record known =
                            InstalledIndex.byUrl(posting.url());
                    MapSource.Posting row = posting;
                    if (known != null && known.file().isFile()
                            && !known.installName.equals(posting.name()))
                        row = new MapSource.Posting(posting.url(),
                                posting.originalName(), known.installName,
                                posting.bytes(), posting.describe());

                    if (known != null && known.file().isFile()
                            || PackageInstaller.isInstalled(row))
                        nifcInstalled.add(row.id());
                    else
                        nifcInstalled.remove(row.id());
                    resolved.add(row);
                }
                nifcAllRows.addAll(resolved);

                nifcHidden = hidden;
                applyNifcFilter();
                nifcList.setSelectionAfterHeaderView();
            }

            @Override
            public void onError(String message) {
                if (!encodedPath.equals(nifcPath))
                    return;
                Log.w(TAG, "listing " + decodedPath + ": " + message);
                showNifcOffline();
            }
        });
    }

    /**
     * The incident list when the archive does not answer: the maps on this
     * phone and the pinned fires, rebuilt from what InstalledIndex and Pinned
     * saved, whichever filter is set. Without it the list came up empty and the
     * pins were gone, with every map still on the phone under a filter nobody
     * would think to try.
     *
     * At the top of the archive it is every map from it, because there is no
     * walking down to them without a network; inside a pinned fire it is that
     * fire's.
     */
    private void showNifcOffline() {
        nifcOffline = true;
        nifcAllRows.clear();
        if (nifcStack.isEmpty()) {
            for (final Pinned.Entry pin : Pinned.all(source.id())) {
                if (!pin.path.equals(nifcPath))
                    nifcAllRows.add(pin);
            }
        }
        for (final InstalledIndex.Record r : offlineRecords()) {
            final MapSource.Posting row = InstalledIndex.toPosting(r);
            nifcInstalled.add(row.id());
            nifcAllRows.add(row);
        }
        nifcHidden = 0;
        applyNifcFilter();
    }

    private List<InstalledIndex.Record> offlineRecords() {
        return InstalledIndex.under(source == null ? null : source.id(),
                nifcStack.isEmpty() ? "" : nifcDecodedPath);
    }

    /** Says the list is what is on the phone, and why, in words. */
    private void describeOffline() {
        int maps = 0, pins = 0;
        for (final Object o : nifcRows) {
            if (o instanceof Pinned.Entry)
                pins++;
            else if (o instanceof MapSource.Posting)
                maps++;
        }
        final StringBuilder sb = new StringBuilder();
        if (!nifcStack.isEmpty()) {
            final String where = breadcrumb(nifcDecodedPath);
            if (!where.isEmpty())
                sb.append(where).append('\n');
        }
        sb.append(noNetwork() ? "No network. "
                : "Could not reach " + source.label() + ". ");
        if (maps == 0 && pins == 0) {
            sb.append(nifcStack.isEmpty()
                    ? "No maps from " + source.label() + " on this phone."
                    : "No maps from this folder on this phone.");
        } else {
            sb.append("Showing ");
            if (maps > 0)
                sb.append(maps).append(maps == 1 ? " map" : " maps")
                        .append(" on this phone");
            if (maps > 0 && pins > 0)
                sb.append(" and ");
            if (pins > 0)
                sb.append(pins).append(pins == 1 ? " pinned fire"
                        : " pinned fires");
            sb.append('.');
        }
        nifcStatus.setText(sb.toString());
    }

    /** One layer, so one toggle, wherever it is pressed from. */
    private void toggleOutlines(TextView status) {
        final Boolean on = PackageInstaller.outlinesVisible();
        if (on == null || !PackageInstaller.setOutlinesVisible(
                !on.booleanValue())) {
            if (status != null)
                status.setText("ATAK would not change the outlines");
            return;
        }
        refreshOutlinesButton();
        syncOutlines();
    }

    /**
     * The outlines control reflects ATAK, not a state of our own: someone may
     * have toggled them from the overlay manager, or from the other panel, since
     * this one was last looked at. Hidden entirely when this build has no such
     * layer.
     */
    private void refreshOutlinesButton() {
        final Boolean on = PackageInstaller.outlinesVisible();
        final String label = on == null ? null
                : pluginContext.getString(on.booleanValue()
                        ? R.string.outlines_on : R.string.outlines_off);
        for (final Button b : new Button[] {
                nifcOutlines, forestOutlines }) {
            if (b == null)
                continue;
            // Outlines are for flights and incident maps; a satellite pass is a
            // whole scene with no outline of its own.
            if (label == null || (b == forestOutlines
                    && packageMode == PackageMode.SATELLITE)) {
                b.setVisibility(View.GONE);
                continue;
            }
            b.setVisibility(View.VISIBLE);
            b.setText(label);
        }
    }

    /**
     * Applies the installed/available filter.
     *
     * Folders are never filtered out -- they are how the operator gets anywhere,
     * and a folder is neither installed nor available.
     */
    private void applyNifcFilter() {
        nifcRows.clear();

        // Everything installed at or below where the operator is standing, so
        // the top of an archive shows the lot and it narrows as they walk down.
        // A fire's folder is mostly subfolders, so without this "Installed" had
        // nothing to count and showed an empty list beside a full device.
        final List<InstalledIndex.Record> below = nifcOffline ? offlineRecords()
                : InstalledIndex.under(source == null ? null : source.id(),
                        nifcDecodedPath);

        final java.util.Set<String> indexed = new HashSet<>();
        for (final InstalledIndex.Record r : below)
            indexed.add(InstalledIndex.lower(r.installName));

        int installed = below.size(), available = 0;
        final java.util.Set<String> listedHere = new HashSet<>();

        for (final Object o : nifcAllRows) {
            // Folders and pins are how the operator gets anywhere. Neither is
            // installed or available, so neither is ever filtered out.
            if (o instanceof MapSource.Entry || o instanceof Pinned.Entry) {
                nifcRows.add(o);
                continue;
            }
            final MapSource.Posting posting = (MapSource.Posting) o;
            final boolean have = nifcInstalled.contains(posting.id());
            // Offline every row is a map on the phone; one removed since
            // goes, rather than turning into a Download that cannot work.
            if (nifcOffline && !have)
                continue;
            if (have) {
                listedHere.add(InstalledIndex.lower(posting.name()));
                // Downloaded before the index existed, so it is on the device
                // but not remembered. Counted here rather than going missing.
                if (!indexed.contains(InstalledIndex.lower(posting.name())))
                    installed++;
            } else {
                available++;
            }
            if (nifcOffline || nifcShown == Shown.ALL
                    || (nifcShown == Shown.INSTALLED && have)
                    || (nifcShown == Shown.AVAILABLE && !have))
                nifcRows.add(posting);
        }

        // The installed maps from deeper folders, which this listing has no rows
        // for. Shown with the folder they came from rather than a size, since
        // that is what tells two days of the same fire apart.
        if (nifcShown == Shown.INSTALLED) {
            for (final InstalledIndex.Record r : below) {
                if (listedHere.contains(InstalledIndex.lower(r.installName)))
                    continue;
                final MapSource.Posting rebuilt = InstalledIndex.toPosting(r);
                nifcInstalled.add(rebuilt.id());
                nifcRows.add(rebuilt);
            }
        }

        refreshOutlinesButton();

        // Offline the list is the installed maps whatever the filter says, and
        // the button says so rather than "Available" over a list of installed.
        final Shown counted = nifcOffline ? Shown.INSTALLED : nifcShown;
        final int count = counted == Shown.INSTALLED ? installed
                : counted == Shown.AVAILABLE ? available
                        : installed + available;
        nifcFilter.setText(pluginContext.getString(labelFor(counted))
                + " (" + count + ")");
        nifcAdapter.notifyDataSetChanged();
        if (nifcOffline)
            describeOffline();
        else
            describeListing(nifcHidden);
    }

    /**
     * Says what is on screen and, as importantly, what is not: a folder holding
     * geodatabase zips alongside two PDFs would otherwise look like a folder
     * holding two files.
     */
    private void describeListing(int hidden) {
        int folders = 0, maps = 0, pins = 0;
        for (final Object o : nifcRows) {
            if (o instanceof Pinned.Entry)
                pins++;
            else if (o instanceof MapSource.Entry)
                folders++;
            else
                maps++;
        }
        final StringBuilder sb = new StringBuilder();
        final String where = breadcrumb(nifcDecodedPath);
        if (!where.isEmpty())
            sb.append(where).append('\n');
        if (folders == 0 && maps == 0) {
            // "Nothing posted here" is a lie when the folder is full and the
            // filter is hiding all of it. Say which it is, and how to undo it.
            int filteredOut = 0;
            for (final Object o : nifcAllRows) {
                if (o instanceof MapSource.Posting)
                    filteredOut++;
            }
            if (filteredOut > 0 && nifcShown != Shown.ALL) {
                sb.append(filteredOut)
                        .append(filteredOut == 1 ? " map here, hidden by the "
                                : " maps here, hidden by the ")
                        .append(pluginContext.getString(labelFor(nifcShown)))
                        .append(" filter");
            } else {
                sb.append(pluginContext.getString(R.string.nifc_empty));
            }
        } else {
            if (folders > 0)
                sb.append(folders).append(folders == 1 ? " folder" : " folders");
            if (folders > 0 && maps > 0)
                sb.append(", ");
            if (maps > 0)
                sb.append(maps).append(maps == 1 ? " map" : " maps");
        }
        if (hidden > 0)
            sb.append("  ·  ").append(hidden)
                    .append(hidden == 1 ? " other file hidden"
                            : " other files hidden");
        nifcStatus.setText(sb.toString());
    }

    /**
     * Date-named folders newest first; everything else left as the server had it.
     *
     * A fire's IR and GIS folders are one directory per day, and the server
     * lists them ascending, so the map an operator almost always wants -- today's
     * -- is at the bottom of a scroll that grows for as long as the fire burns.
     * Folders that are not dates (a fire's name, DAILY MAP PRODUCT, IAP) keep
     * their order, where alphabetical is what is wanted.
     */
    private static List<MapSource.Entry> newestFirst(
            List<MapSource.Entry> folders) {

        final List<MapSource.Entry> dated = new ArrayList<>();
        final List<MapSource.Entry> rest = new ArrayList<>();
        for (final MapSource.Entry e : folders) {
            if (DATE_FOLDER.matcher(e.name).matches())
                dated.add(e);
            else
                rest.add(e);
        }
        java.util.Collections.sort(dated, new java.util.Comparator<MapSource.Entry>() {
            @Override
            public int compare(MapSource.Entry a, MapSource.Entry b) {
                return digitsOf(b.name).compareTo(digitsOf(a.name));
            }
        });
        final List<MapSource.Entry> out = new ArrayList<>(dated);
        out.addAll(rest);
        return out;
    }

    private static String digitsOf(String s) {
        return s.replaceAll("[^0-9]", "");
    }

    /** {@code 20260729}, and the separated spellings of the same thing. */
    private static final java.util.regex.Pattern DATE_FOLDER =
            java.util.regex.Pattern.compile("^\\d{4}[-_.]?\\d{2}[-_.]?\\d{2}$");

    /**
     * What to call a pin: the fire, and how far into it the pin sits.
     *
     * A pin on the fire itself is just "Timber", but one on Timber's Products
     * folder is "Timber > Products" -- otherwise two pins on the same fire are
     * the same word twice and the operator cannot tell which is which.
     *
     * Recomputed from the path every time it is shown rather than read from
     * what was stored, so pins made before this existed correct themselves.
     */
    private static String pinLabel(String decodedPath) {
        if (decodedPath == null || decodedPath.isEmpty())
            return "";
        final String[] parts = decodedPath.replaceAll("/+$", "").split("/");
        final String incident = com.atakmap.android.mapdepot.NifcClient
                .incidentFolderOf(decodedPath);

        int from = 0;
        if (incident != null) {
            for (int i = 0; i < parts.length; i++) {
                if (incident.equals(parts[i])) {
                    from = i;
                    break;
                }
            }
        } else {
            from = Math.max(0, parts.length - 1);
        }

        final StringBuilder sb = new StringBuilder();
        for (int i = from; i < parts.length; i++) {
            if (parts[i].isEmpty())
                continue;
            // The year prefix is how the server files a fire, not what anyone
            // calls it.
            final String seg = i == from
                    ? parts[i].replaceFirst("^\\d{4}[_\\-\\s]+", "")
                    : parts[i];
            if (sb.length() > 0)
                sb.append(" \u203a ");
            sb.append(pretty(seg));
        }
        return sb.length() == 0 ? pretty(decodedPath) : sb.toString();
    }

    /**
     * Where the operator is, written for reading rather than as a path.
     *
     * UASWFC's paths are rooted at the site, so they start with the server's own
     * "ftp" directory -- plumbing the operator did not ask about and which read
     * as lowercase noise at the top of the panel.
     */
    private static String breadcrumb(String decodedPath) {
        if (decodedPath == null || decodedPath.isEmpty())
            return "";
        final StringBuilder sb = new StringBuilder();
        for (final String seg : decodedPath.split("/")) {
            if (seg.isEmpty() || "ftp".equalsIgnoreCase(seg))
                continue;
            if (sb.length() > 0)
                sb.append(" \u203a ");
            sb.append(pretty(seg));
        }
        return sb.toString();
    }

    /** Back walks up the stack, and leaves the browser at the GACC. */
    private void nifcBack() {
        if (nifcStack.isEmpty()) {
            showHome();
            return;
        }
        final String[] previous = nifcStack.pop();
        // Re-listing rather than caching: a fire's folder gains files during the
        // day, and a stale list is worse here than a second of loading.
        browse(previous[0], previous[1], Nav.BACK);
    }

    /**
     * The real size first. The listing's "4.6M" is Apache rounding, and the
     * installer treats a length mismatch as a corrupt download -- so handing it
     * the rounded figure would fail every install.
     */
    private void confirmAndInstallPosting(final MapSource.Posting posting) {
        if (activePostingId != null) {
            toast("Already downloading — let it finish first.");
            return;
        }
        nifcStatus.setText("Checking size…");
        source.exactSize(posting, new MapSource.SizeCallback() {
            @Override
            public void onSize(long bytes) {
                posting.setBytes(bytes);
                promptForPosting(posting, bytes);
            }

            @Override
            public void onError(String message) {
                // Not fatal: zero simply means the installer skips the space
                // estimate and the length check rather than refusing to try.
                Log.w(TAG, "no content length for " + posting.name() + ": "
                        + message);
                posting.setBytes(0L);
                promptForPosting(posting, 0L);
            }
        });
    }

    private void promptForPosting(final MapSource.Posting posting, long bytes) {
        final String size = bytes > 0 ? Depot.bytes(bytes) : "an unknown size";
        new AlertDialog.Builder(hostContext())
                .setTitle(posting.name())
                .setMessage("Download " + size + " from "
                        + (source == null ? "the archive" : source.label())
                        + "?\n\nPosted as " + posting.originalName())
                .setPositiveButton(pluginContext.getString(R.string.download),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                installPosting(posting);
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * A freshly downloaded map is not in ATAK's list the instant the download
     * ends -- the scan takes as long as it takes. Say so, rather than letting
     * the tap look broken.
     */
    /**
     * Waits for ATAK to register a freshly installed map, then redraws the row.
     *
     * The Show/Hide control is only offered once ATAK will answer about
     * visibility, and that was only ever checked when a folder was listed -- so a
     * map you had just downloaded had no button until you left the folder and
     * came back. Registration takes a few seconds; this asks until it does,
     * then stops.
     */
    private void awaitVisibility(final MapSource.Posting posting,
            final int attemptsLeft) {
        if (attemptsLeft <= 0 || posting == null)
            return;
        if (PackageInstaller.isVisible(posting) != null) {
            nifcAdapter.notifyDataSetChanged();
            return;
        }
        nifcList.postDelayed(new Runnable() {
            @Override
            public void run() {
                awaitVisibility(posting, attemptsLeft - 1);
            }
        }, 1000L);
    }

    private void goToPosting(final MapSource.Posting posting) {
        PackageInstaller.goTo(posting, new PackageInstaller.GoTo() {
            @Override
            public void onGoing(Depot.Package p) {
                nifcStatus.setText("Going to " + p.name());
            }

            @Override
            public void onWaiting(Depot.Package p) {
                nifcStatus.setText("Adding to the map list…");
            }

            @Override
            public void onUnavailable(Depot.Package p, String why) {
                nifcStatus.setText("Could not go there: " + why);
            }
        });
    }

    /**
     * Removing from here rather than from ATAK's overlay manager is the safe
     * path, and not only the convenient one: {@code PackageInstaller.retire}
     * hands ATAK a delete and lets it unload the layer *before* the file goes,
     * which is the opposite of the overlay manager's own order. Deleting a GRG
     * there leaves the tile reader holding a path that no longer exists, and
     * disposing it dereferences a freed GDAL dataset.
     */
    private void confirmAndRemovePosting(final MapSource.Posting posting) {
        new AlertDialog.Builder(hostContext())
                .setTitle(posting.name())
                .setMessage("Remove this map from the device?")
                .setPositiveButton(pluginContext.getString(R.string.remove),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                removePosting(posting);
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void removePosting(final MapSource.Posting posting) {
        nifcStatus.setText("Removing " + posting.name() + "…");
        packages.uninstall(posting, new PackageInstaller.Callback() {
            @Override
            public void onProgress(Depot.Package p, long done, long total) {
            }

            @Override
            public void onInstalled(Depot.Package p, File dest) {
            }

            @Override
            public void onRemoved(Depot.Package p) {
                nifcInstalled.remove(p.id());
                InstalledIndex.remove(posting.name(), posting.destination());
                applyNifcFilter();
                nifcStatus.setText(p.name() + " removed.");
            }

            @Override
            public void onError(Depot.Package p, String message) {
                Log.w(TAG, "removing " + p.id() + ": " + message);
                nifcStatus.setText("Could not remove " + p.name()
                        + ": " + message);
            }
        });
    }

    private void installPosting(final MapSource.Posting posting) {
        activePostingId = posting.id();
        nifcDone = 0;
        nifcTotal = posting.bytes();
        nifcStatus.setText("Getting " + posting.name() + "…");
        cancelBarNifc.setVisibility(View.VISIBLE);
        nifcAdapter.notifyDataSetChanged();
        packages.install(posting, new PackageInstaller.Callback() {
            @Override
            public void onProgress(Depot.Package p, long done, long total) {
                nifcDone = done;
                nifcTotal = total;
                nifcStatus.setText(p.name() + " — " + Depot.bytes(done)
                        + " of " + Depot.bytes(total));
                nifcAdapter.notifyDataSetChanged();
            }

            @Override
            public void onInstalled(Depot.Package p, File dest) {
                activePostingId = null;
                nifcDone = nifcTotal = 0;
                cancelBarNifc.setVisibility(View.GONE);
                nifcInstalled.add(p.id());
                // Written down here because the file itself cannot say where it
                // came from: grg/ is flat and holds only a name.
                InstalledIndex.add(new InstalledIndex.Record(
                        source.id(), nifcDecodedPath, posting.url(),
                        posting.originalName(), posting.name(),
                        posting.destination(), posting.bytes()));
                applyNifcFilter();
                nifcStatus.setText(p.name()
                        + " installed — tap it to go there.");
                // ATAK has the file but has not registered it yet; the row's
                // Show/Hide appears as soon as it has.
                awaitVisibility(posting, 30);
            }

            @Override
            public void onRemoved(Depot.Package p) {
                nifcInstalled.remove(p.id());
                nifcAdapter.notifyDataSetChanged();
            }

            @Override
            public void onError(Depot.Package p, String message) {
                activePostingId = null;
                nifcDone = nifcTotal = 0;
                cancelBarNifc.setVisibility(View.GONE);
                nifcAdapter.notifyDataSetChanged();
                if (PackageInstaller.CANCELLED.equals(message)) {
                    nifcStatus.setText(p.name() + " cancelled — nothing kept.");
                    return;
                }
                Log.w(TAG, "nifc " + p.id() + ": " + message);
                nifcStatus.setText(p.name() + " failed: " + message);
            }
        });
    }

    // ---- small helpers

    private String loadGacc() {
        final MapView mv = MapView.getMapView();
        if (mv == null)
            return null;
        return android.preference.PreferenceManager
                .getDefaultSharedPreferences(mv.getContext())
                .getString(prefGaccKey(), null);
    }

    /**
     * Written to ATAK's own preferences on the host context, not the plugin's:
     * the plugin context's preferences do not survive a plugin reload, and a
     * remembered GACC that forgets itself on every update is worse than none.
     */
    private void saveGacc(String path) {
        final MapView mv = MapView.getMapView();
        if (mv == null)
            return;
        android.preference.PreferenceManager
                .getDefaultSharedPreferences(mv.getContext())
                .edit().putString(prefGaccKey(), path).apply();
    }

    private String labelForPath(String path) {
        for (int i = 0; i < gaccPaths.size(); i++) {
            if (gaccPaths.get(i).equals(path))
                return gaccLabels.get(i);
        }
        return prettyGacc(decodeSegment(path));
    }

    /**
     * Words that are initials rather than words, and look wrong title-cased.
     * "Pacific Nw" was the one that prompted this; the rest are the same
     * mistake waiting in a folder name.
     */
    private static final java.util.Set<String> ACRONYM =
            new HashSet<>(Arrays.asList(
                    "N", "S", "E", "W", "NW", "NE", "SW", "SE",
                    "IR", "GIS", "IAP", "UAS", "FTP", "QR", "PDF", "KMZ",
                    "USFS", "BLM", "NPS", "DPA", "GACC", "NIFC", "UASWFC",
                    "AM", "PM", "DIV", "MP", "US", "USA"));

    private static final java.util.Set<String> SMALL =
            new HashSet<>(Arrays.asList("of", "and", "the", "at", "in", "on",
                    "de", "del", "la", "y"));

    /**
     * A folder name as a person would write it: {@code pacific_nw} to
     * "Pacific NW", {@code calif_n} to "Calif N", {@code DAILY MAP PRODUCT}
     * left alone.
     */
    static String pretty(String raw) {
        if (raw == null)
            return "";
        final String cleaned = raw.replace('/', ' ').replace('_', ' ').trim();
        final StringBuilder sb = new StringBuilder();
        for (final String w : cleaned.split("\\s+")) {
            if (w.isEmpty())
                continue;
            if (sb.length() > 0)
                sb.append(' ');
            final String upper = w.toUpperCase(java.util.Locale.US);
            if (ACRONYM.contains(upper)) {
                sb.append(upper);
                continue;
            }
            // "City of Malibu", not "City Of Malibu": the little words stay
            // down unless they start the name.
            if (sb.length() > 0 && SMALL.contains(w.toLowerCase(java.util.Locale.US))) {
                sb.append(w.toLowerCase(java.util.Locale.US));
                continue;
            }
            // Already shouting, or already mixed on purpose -- leave it.
            if (w.equals(upper) && w.length() > 1) {
                sb.append(w);
                continue;
            }
            sb.append(Character.toUpperCase(w.charAt(0)));
            if (w.length() > 1)
                sb.append(w.substring(1));
        }
        return sb.length() == 0 ? raw : sb.toString();
    }

    /**
     * A region's name for the button. Drops the same {@code ftp} segment the
     * breadcrumb drops -- UASWFC's paths are rooted at the site, so without this
     * the control an operator presses most reads "FTP Pacific NW".
     */
    private static String prettyGacc(String raw) {
        if (raw == null)
            return "";
        final StringBuilder sb = new StringBuilder();
        for (final String seg : raw.split("/")) {
            if (seg.isEmpty() || "ftp".equalsIgnoreCase(seg))
                continue;
            if (sb.length() > 0)
                sb.append(' ');
            sb.append(pretty(seg));
        }
        return sb.length() == 0 ? pretty(raw) : sb.toString();
    }

    private static String decodeSegment(String encoded) {
        try {
            return java.net.URLDecoder.decode(encoded, "UTF-8");
        } catch (Exception notEncoded) {
            return encoded;
        }
    }

    /**
     * Folders and maps in one list.
     *
     * The row's own click listener does the work rather than the ListView's:
     * a row containing a Button stops firing OnItemClickListener entirely, which
     * reads exactly like a dead list.
     */
    private final class NifcAdapter extends ArrayAdapter<Object> {

        NifcAdapter(Context ctx) {
            super(ctx, 0, nifcRows);
        }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            View row = convert;
            if (row == null)
                row = LayoutInflater.from(pluginContext)
                        .inflate(R.layout.region_row, parent, false);

            final Object item = getItem(position);
            if (row == null || item == null)
                return row;

            final TextView name = row.findViewById(R.id.region_name);
            final TextView detail = row.findViewById(R.id.region_detail);
            final Button action = row.findViewById(R.id.region_action);
            final Button eye = row.findViewById(R.id.region_visible);
            final ProgressBar bar = row.findViewById(R.id.region_progress);
            bar.setVisibility(View.GONE);
            eye.setVisibility(View.GONE);

            // Rows are recycled, so a colour set on one has to be taken off the
            // next -- otherwise a folder inherits the cyan of a pin that used to
            // occupy the same view.
            if (rowTextColor == null)
                rowTextColor = name.getTextColors();
            name.setTextColor(rowTextColor);
            detail.setTextColor(rowTextColor);

            if (item instanceof Pinned.Entry) {
                // A pinned fire, shown at the top of the source. It jumps
                // straight to the folder rather than walking down to it.
                final Pinned.Entry pin = (Pinned.Entry) item;
                final int cyan = pluginContext.getResources()
                        .getColor(R.color.pin_cyan);
                name.setText(pinLabel(pin.decodedPath));
                name.setTextColor(cyan);
                detail.setText("Pinned");
                detail.setTextColor(cyan);
                action.setVisibility(View.VISIBLE);
                action.setEnabled(true);
                action.setText(pluginContext.getString(R.string.remove));
                action.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Pinned.unpin(pin.sourceId, pin.path);
                        browse(nifcPath, nifcDecodedPath, Nav.BACK);
                    }
                });
                row.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        browse(pin.path, pin.decodedPath, Nav.DESCEND);
                    }
                });
                return row;
            }

            if (item instanceof MapSource.Entry) {
                final MapSource.Entry e = (MapSource.Entry) item;
                final String childPath = source.childPath(nifcPath, e);
                final String childDecoded = join(nifcDecodedPath, e.name);
                final boolean isPinned = Pinned.isPinned(source.id(), childPath);

                name.setText(breakable(pretty(e.name)));
                detail.setText("Folder");

                // Pin lives on the folder row because that is where the operator
                // is looking when they decide this is the fire they are working.
                action.setVisibility(View.VISIBLE);
                action.setEnabled(true);
                action.setText(pluginContext.getString(
                        isPinned ? R.string.pinned : R.string.pin));
                action.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (isPinned)
                            Pinned.unpin(source.id(), childPath);
                        else
                            Pinned.pin(new Pinned.Entry(source.id(), childPath,
                                    childDecoded,
                                    Pinned.labelFor(childDecoded)));
                        nifcAdapter.notifyDataSetChanged();
                    }
                });

                row.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        browse(childPath, childDecoded, Nav.DESCEND);
                    }
                });
                return row;
            }

            final MapSource.Posting posting = (MapSource.Posting) item;
            final boolean done = nifcInstalled.contains(posting.id());
            final boolean active = posting.id().equals(activePostingId);

            name.setText(breakable(posting.name()));

            if (done) {
                // Only the hint is coloured; the size stays the same weight as
                // every other row, so the list does not turn into a christmas
                // tree once a few maps are installed.
                //
                // The original filename comes off once it is installed: it is
                // there to help choose what to download, and afterwards it only
                // pushes the line to a third wrap and crowds the buttons.
                // Its own line, deliberately. Appended to the size and the
                // folder it came from, it wrapped anyway -- and a wrap lands
                // wherever the width runs out rather than where it reads well.
                final String hint = "\ntap to go there";
                final String detailText = posting.describe()
                        .replaceAll("\\s+·\\s+[^·]+\\.(pdf|kmz)$", "");
                final SpannableString line =
                        new SpannableString(detailText + hint);
                line.setSpan(
                        new ForegroundColorSpan(pluginContext.getResources()
                                .getColor(R.color.action_green)),
                        line.length() - hint.length(), line.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                detail.setText(line);
            } else {
                detail.setText(posting.describe());
            }

            if (active && nifcTotal > 0) {
                bar.setVisibility(View.VISIBLE);
                bar.setProgress((int) (nifcDone * 100L / nifcTotal));
            } else {
                bar.setVisibility(View.GONE);
            }

            action.setVisibility(View.VISIBLE);
            // While one map is downloading the others are not offered: these run
            // to tens of megabytes and two at once on a hotspot serves nobody.
            // Removing one already downloaded stays available.
            action.setEnabled(activePostingId == null || done);
            action.setText(pluginContext.getString(
                    done ? R.string.remove : R.string.download));
            action.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (done)
                        confirmAndRemovePosting(posting);
                    else
                        confirmAndInstallPosting(posting);
                }
            });

            // Turning the overlay on and off without leaving the panel.
            // Offered only when ATAK will actually answer: before it has
            // registered a freshly downloaded map there is no handler to ask,
            // and a control that silently does nothing is worse than no control.
            if (done) {
                final Boolean visible = PackageInstaller.isVisible(posting);
                if (visible != null) {
                    eye.setVisibility(View.VISIBLE);
                    // Labelled with what pressing it does, not with the state
                    // it is in: Hide while the map is up, Show while it is not.
                    eye.setText(pluginContext.getString(
                            visible.booleanValue() ? R.string.hide_map
                                    : R.string.show_map));
                    eye.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            final boolean want = !visible.booleanValue();
                            if (PackageInstaller.setVisible(posting, want)) {
                                nifcAdapter.notifyDataSetChanged();
                            } else {
                                nifcStatus.setText("ATAK would not change "
                                        + posting.name());
                            }
                        }
                    });
                }
            }

            // An installed map is a place to go, the same as a forest map is.
            row.setOnClickListener(done ? new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    goToPosting(posting);
                }
            } : null);
            return row;
        }
    }

    /**
     * A map's name, with somewhere for the line to break.
     *
     * These names are one long token with no spaces, and Android's line breaker
     * did not treat their hyphens as break opportunities -- it ran out of width
     * and split mid-word, GRASSHOPPER-OVERLAY-A / USTIN-IR. Neither
     * breakStrategy nor hyphenationFrequency changed that. A zero-width space
     * after each hyphen is an explicit break opportunity, so it breaks where the
     * name already has a seam and nowhere else. The character is invisible and
     * is not part of the filename -- this is display only.
     */
    private static CharSequence breakable(String name) {
        return name == null ? "" : name.replace("-", "-\u200B");
    }

    private static String join(String path, String segment) {
        if (path == null || path.isEmpty())
            return segment + "/";
        return path.endsWith("/") ? path + segment + "/"
                : path + "/" + segment + "/";
    }

    private final class PackageAdapter extends ArrayAdapter<Depot.Package> {

        PackageAdapter(Context ctx) {
            super(ctx, 0, shownPackages);
        }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            View row = convert;
            if (row == null)
                row = LayoutInflater.from(pluginContext)
                        .inflate(R.layout.region_row, parent, false);

            final Depot.Package pkg = getItem(position);
            if (row == null || pkg == null)
                return row;

            final TextView name = row.findViewById(R.id.region_name);
            final TextView detail = row.findViewById(R.id.region_detail);
            final Button action = row.findViewById(R.id.region_action);
            final Button action2 = row.findViewById(R.id.region_action2);
            final Button eye = row.findViewById(R.id.region_visible);
            final ProgressBar bar = row.findViewById(R.id.region_progress);

            final boolean active = pkg.id().equals(activeForestId);
            final boolean done = installedPackages.contains(pkg.id());

            // A flight with an offline copy on the depot gets a second button:
            // the stream above, the download with its size under it. The
            // operator's rule (2026-10-11): one row per flight, both choices
            // on it, never a second row.
            final Depot.Pack pack = pkg instanceof Depot.Flight
                    ? ((Depot.Flight) pkg).pack : null;
            final boolean packActive = pack != null && pack.id().equals(activeForestId);
            final boolean packDone = pack != null && installedPackages.contains(pack.id());
            action2.setVisibility(pack != null ? View.VISIBLE : View.GONE);
            if (pack != null) {
                if (packActive) {
                    final int pct = forestTotal > 0
                            ? (int) (forestDone * 100L / forestTotal) : 0;
                    action2.setText(pct + "%");
                } else {
                    // Same width as Add online, so the rows line up; the
                    // size is on the row's text instead (2026-10-11).
                    // Plain "Remove" on both: the top button is the online
                    // layer, the bottom the offline copy, and a longer label
                    // was cut off in the box (2026-10-11).
                    action2.setText(packDone
                            ? pluginContext.getString(R.string.remove)
                            : pluginContext.getString(R.string.download));
                }
                action2.setEnabled(activeForestId == null || packActive);
                action2.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (packActive)
                            return;
                        if (packDone)
                            uninstallPackage(pack);
                        else
                            installPackage(pack);
                    }
                });
            }

            // Same Show/Hide the incident maps have. A district map is a PDF
            // overlay like any other and there is no reason toggling it should
            // mean a trip out to the overlay manager. Offered only where ATAK
            // will answer, so a vector tile package -- which has no such handler
            // -- simply does not show one.
            eye.setVisibility(View.GONE);
            // Hide / Show drives whatever of this flight is on the phone: the
            // online layer when it is added, else the offline copy.
            final Depot.Package shown = done ? pkg : packDone ? pack : null;
            if (pkg instanceof Depot.Scene && done && !active) {
                // A pass is held in both views. The switch sits under Remove,
                // in the slot a flight uses for its offline copy, so the name
                // keeps the width and fits on one line.
                final boolean toSwir = !Depot.STYLE_SWIR.equals(sceneStyle);
                action2.setVisibility(View.VISIBLE);
                action2.setEnabled(true);
                action2.setText(toSwir ? "Show SWIR" : "Show Color");
                action2.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        switchSceneView(toSwir ? Depot.STYLE_SWIR : Depot.STYLE_VISUAL);
                    }
                });
            } else if (shown != null && !active && !packActive) {
                final Boolean visible = PackageInstaller.isVisible(shown);
                if (visible != null) {
                    eye.setVisibility(View.VISIBLE);
                    eye.setText(pluginContext.getString(
                            visible.booleanValue() ? R.string.hide_map
                                    : R.string.show_map));
                    eye.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            if (PackageInstaller.setVisible(shown,
                                    !visible.booleanValue()))
                                packageAdapter.notifyDataSetChanged();
                            else
                                forestStatus.setText("ATAK would not change "
                                        + pkg.name());
                        }
                    });
                }
            }

            // The whole screen is Sentinel-2 and a crew has no use for the
            // tile code, so the row title is "Oct 29 SWIR Fire View" and fits
            // on one line. ATAK's layer name keeps both.
            name.setText(pkg instanceof Depot.Scene
                    ? pkg.name().replaceFirst("^Sentinel2 ", "")
                            .replaceFirst(" [0-9]{2}[A-Z]{3}$", "")
                    : pkg.name());
            if (done || packDone) {
                // Only the hint is coloured; the size stays the same weight as
                // every other row so the list does not turn into a christmas
                // tree once a few things are installed.
                // Its own line, as on the incident rows: appended to a forest,
                // a state and a size it wrapped wherever the width ran out.
                final String hint = "\ntap to go there";
                final SpannableString line =
                        new SpannableString(describeRow(pkg) + hint);
                line.setSpan(
                        new ForegroundColorSpan(pluginContext.getResources()
                                .getColor(R.color.action_green)),
                        line.length() - hint.length(), line.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                detail.setText(line);
            } else if (pkg instanceof Depot.Bounded
                    && ((Depot.Bounded) pkg).bounds() != null) {
                // A pass is a whole scene: tapping it moves the map there, and
                // the row says so before and after it is added.
                final String hint = pkg instanceof Depot.Scene
                        ? "\ntap to go there" : "\ntap to see where";
                final SpannableString line =
                        new SpannableString(describeRow(pkg) + hint);
                line.setSpan(
                        new ForegroundColorSpan(pluginContext.getResources()
                                .getColor(R.color.action_green)),
                        line.length() - hint.length(), line.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                detail.setText(line);
            } else {
                detail.setText(describeRow(pkg));
            }

            if ((active || packActive) && forestTotal > 0) {
                bar.setVisibility(View.VISIBLE);
                bar.setProgress((int) (forestDone * 100L / forestTotal));
            } else {
                bar.setVisibility(View.GONE);
            }

            // The downloading row shows its percentage and nothing else. Cancel
            // is the bar above the list: a row button moves when the list
            // reflows and is a poor target, and offering two ways to cancel --
            // one of which is hard to hit -- is worse than offering one.
            //
            // While one package is downloading the others are not offered:
            // these run to a gigabyte and two at once on a hotspot serves nobody.
            // A forest basemap this ATAK cannot display is not worth a
            // gigabyte. Removing one already downloaded stays available.
            final String block = packageMode == PackageMode.FORESTS && !done
                    ? vectorPackageBlock(true) : null;
            final boolean unusable = block != null;

            action.setEnabled(!active && activeForestId == null && !unusable);
            if (active) {
                final int pct = forestTotal > 0
                        ? (int) (forestDone * 100L / forestTotal) : 0;
                action.setText(pct + "%");
            } else {
                // A flight is a pointer file; the tiles stream as you pan.
                // "Download" promised a file that never arrives (2026-10-11).
                // The operator's words (2026-10-11): "Add online" then
                // "Download offline", so the two buttons explain each other.
                action.setText(pkg instanceof Depot.Flight || pkg instanceof Depot.Scene
                        ? (done ? R.string.remove : R.string.add_online)
                        : done ? R.string.remove : R.string.download);
            }
            if (unusable)
                detail.setText(describeRow(pkg) + " · " + block);
            action.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (active)
                        return;
                    if (done)
                        uninstallPackage(pkg);
                    else
                        installPackage(pkg);
                }
            });
            return row;
        }
    }

    private void toast(String s) {
        Toast.makeText(hostContext(), s, Toast.LENGTH_SHORT).show();
    }

    // -------------------------------------------------------------- base maps

    private final class BaseMapAdapter extends ArrayAdapter<Depot.BaseMap> {

        BaseMapAdapter(Context ctx) {
            super(ctx, 0, shownMaps);
        }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            View row = convert;
            if (row == null)
                row = LayoutInflater.from(pluginContext)
                        .inflate(R.layout.region_row, parent, false);

            final Depot.BaseMap map = getItem(position);
            if (map == null)
                return row;

            final TextView name = row.findViewById(R.id.region_name);
            final TextView detail = row.findViewById(R.id.region_detail);
            final Button action = row.findViewById(R.id.region_action);
            row.findViewById(R.id.region_progress).setVisibility(View.GONE);

            if (map.header) {
                // A heading, in the cyan the pinned rows use, small caps.
                name.setText(map.name.toUpperCase(Locale.US));
                name.setTextColor(pluginContext.getResources().getColor(R.color.pin_cyan));
                name.setTextSize(12f);
                detail.setVisibility(View.GONE);
                action.setVisibility(View.GONE);
                row.findViewById(R.id.region_visible).setVisibility(View.GONE);
                return row;
            }
            name.setTextColor(rowTextColorOf(name));
            name.setTextSize(16f);
            detail.setVisibility(View.VISIBLE);
            action.setVisibility(View.VISIBLE);

            name.setText(map.name);
            String about = map.describe();
            if (map.needsKey())
                about += (about.isEmpty() ? "" : " · ") + pluginContext.getString(
                        BaseMapInstaller.keyFor(map.keyProvider).isEmpty()
                                ? R.string.key_row_missing : R.string.key_set);
            detail.setText(about);

            // One button that flips rather than a swipe: a hidden gesture is a
            // poor fit for a list read with gloves on, and removing a map source
            // is cheap to undo -- the row goes straight back to Get.
            final boolean done = installedMaps.contains(map.id);
            action.setEnabled(true);
            action.setText(done ? R.string.remove : R.string.add);

            // Hide / Show on an added overlay, the same control the flight
            // rows have, so it never means a trip to Overlay Manager.
            final Button eye = row.findViewById(R.id.region_visible);
            eye.setVisibility(View.GONE);
            if (done && map.overlay()) {
                final Boolean visible = BaseMapInstaller.isVisible(map);
                if (visible != null) {
                    eye.setVisibility(View.VISIBLE);
                    eye.setText(pluginContext.getString(
                            visible.booleanValue() ? R.string.hide_map
                                    : R.string.show_map));
                    eye.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            if (BaseMapInstaller.setVisible(map, !visible.booleanValue()))
                                mapAdapter.notifyDataSetChanged();
                            else
                                mapStatus.setText("ATAK would not change " + map.name);
                        }
                    });
                }
            }
            action.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (done)
                        uninstall(map);
                    else
                        install(map);
                }
            });
            return row;
        }
    }

    // ------------------------------------------------------------------ list

    private final class RegionAdapter extends ArrayAdapter<Depot.Region> {

        RegionAdapter(Context ctx) {
            super(ctx, 0, shown);
        }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            View row = convert;
            if (row == null)
                row = LayoutInflater.from(pluginContext)
                        .inflate(R.layout.region_row, parent, false);

            final Depot.Region region = getItem(position);
            if (region == null)
                return row;

            final TextView name = row.findViewById(R.id.region_name);
            final TextView detail = row.findViewById(R.id.region_detail);
            final ProgressBar bar = row.findViewById(R.id.region_progress);
            final Button action = row.findViewById(R.id.region_action);

            // The country is the dropdown, so the row does not repeat it.
            name.setText(region.name);
            detail.setText(region.describe());

            final Integer pct = progressById.get(region.id);
            if (pct != null) {
                bar.setVisibility(View.VISIBLE);
                bar.setProgress(pct);
                action.setEnabled(false);
                action.setText(pct + "%");
            } else {
                bar.setVisibility(View.GONE);
                // The device scan is the authority. completeById only knows what
                // finished downloading in this session, so on its own it called
                // a region installed years ago "Get".
                final boolean done = region.fullyHeld()
                        || completeById.contains(region.id);
                // A region holding some of its cells reads "Finish", not "Get":
                // the same word for "you have none of this" and "you have all
                // but two cells of this" tells the operator nothing.
                final boolean started = !done && region.held() > 0;
                action.setEnabled(activeRegionId == null);
                action.setText(done ? R.string.remove
                        : started ? R.string.finish : R.string.download);
            }

            final boolean held = region.fullyHeld()
                    || completeById.contains(region.id);
            action.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (held)
                        confirmAndRemove(region);
                    else
                        confirmAndInstall(region);
                }
            });
            return row;
        }
    }
}
