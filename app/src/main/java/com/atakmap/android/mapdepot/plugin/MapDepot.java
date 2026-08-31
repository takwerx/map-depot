package com.atakmap.android.mapdepot.plugin;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
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
import com.atakmap.android.mapdepot.BaseMapInstaller;
import com.atakmap.android.mapdepot.Depot;
import com.atakmap.android.mapdepot.DepotClient;
import com.atakmap.android.mapdepot.InstalledIndex;
import com.atakmap.android.mapdepot.MapSource;
import com.atakmap.android.mapdepot.NifcClient;
import com.atakmap.android.mapdepot.UaswfcClient;
import com.atakmap.android.mapdepot.PackageInstaller;
import com.atakmap.android.mapdepot.RegionInstaller;
import com.atakmap.coremap.log.Log;

import java.io.File;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
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
    private boolean catalogLoaded;

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
        FORESTS, RECMAPS
    }

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
    private final List<Depot.Package> shownPackages = new ArrayList<>();
    private final Set<String> installedPackages = new HashSet<>();
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

    /** Progress for the row currently downloading, so it shows on the row. */
    private long nifcDone, nifcTotal;

    /** How many entries the source withheld, kept for the status line. */
    private int nifcHidden;
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
                                .getDrawable(R.drawable.ic_launcher),
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
        client = new DepotClient(cacheDir());
        installer = new RegionInstaller();
        baseMaps = new BaseMapInstaller();
        packages = new PackageInstaller();
        uiService.addToolbarItem(toolbarItem);
        registerPreferences();
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
                                    R.drawable.ic_launcher),
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
                        final Depot.Package pkg = shownPackages.get(position);
                        if (!installedPackages.contains(pkg.id()))
                            return;
                        // A freshly downloaded package is not in ATAK's map
                        // list the instant the download ends -- the scan takes
                        // as long as it takes. Say so rather than letting the
                        // tap look broken.
                        PackageInstaller.goTo(pkg, new PackageInstaller.GoTo() {
                            @Override
                            public void onGoing(Depot.Package p) {
                                forestStatus.setText("Going to " + p.name());
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

        root.findViewById(R.id.btn_forests).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showPackages(PackageMode.FORESTS);
                    }
                });

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
        forestSearch.setHint(mode == PackageMode.FORESTS
                ? R.string.search_forests : R.string.search_recmaps);
        forestSearch.setText("");

        // Say it up front rather than after a gigabyte has been downloaded.
        // ATAK 5.6 has no vector tile package support, so a forest basemap
        // installs and then exists nowhere ATAK can see it.
        if (mode == PackageMode.FORESTS
                && !PackageInstaller.supportsVectorPackages())
            forestStatus.setText(pluginContext.getString(R.string.forests_need_57));

        if (!catalogLoaded)
            loadCatalog();
        else
            applyForestFilter();
    }

    /** The list behind the current mode, in catalog order. */
    private List<? extends Depot.Package> sourceList() {
        return packageMode == PackageMode.FORESTS ? allForests : allRecMaps;
    }

    private void showBaseMaps() {
        homeView.setVisibility(View.GONE);
        dtedView.setVisibility(View.GONE);
        forestView.setVisibility(View.GONE);
        nifcView.setVisibility(View.GONE);
        baseMapView.setVisibility(View.VISIBLE);
        if (!catalogLoaded)
            loadCatalog();
        else
            applyCategoryFilter();
    }

    /** The catalog is only fetched once the operator asks for elevation. */
    private void showDted() {
        homeView.setVisibility(View.GONE);
        baseMapView.setVisibility(View.GONE);
        forestView.setVisibility(View.GONE);
        nifcView.setVisibility(View.GONE);
        dtedView.setVisibility(View.VISIBLE);
        if (!catalogLoaded)
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
            regionFilter.setText(labelFor(regionShown));
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
            public void onCatalog(List<Depot.Region> fetched, boolean cached) {
                Log.i(TAG, "onCatalog regions=" + fetched.size() + " cached=" + cached);
                catalogLoaded = true;
                allRegions.clear();
                allRegions.addAll(fetched);
                measureRegions();
                populateCountries();
                applyCountryFilter();
                loadBaseMaps();
                loadForests();

                if (cached) {
                    status.setText(pluginContext.getString(R.string.catalog_offline));
                } else {
                    status.setText("");
                }
            }

            @Override
            public void onError(String message) {
                Log.w(TAG, "catalog error: " + message);
                status.setText("Depot unreachable: " + message);
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
     * A cache directory ATAK's own process can actually write. The plugin
     * context's getCacheDir() points inside the plugin package's data dir, which
     * ATAK runs under a different uid and cannot create -- it fails with ENOENT,
     * so every document written there vanished and Base Maps came up empty.
     */
    private File cacheDir() {
        final MapView mv = MapView.getMapView();
        final Context host = mv != null ? mv.getContext() : pluginContext;
        return new File(host.getCacheDir(), "mapdepot");
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
            public void onBaseMaps(List<Depot.BaseMap> maps) {
                Log.i(TAG, "onBaseMaps count=" + maps.size());
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
                mapStatus.setText("Could not read base maps: " + message);
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

        basemapFilter.setText(labelFor(mapShown));

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
        mapAdapter.notifyDataSetChanged();

        if (shownMaps.isEmpty())
            mapStatus.setText(mapShown == Shown.INSTALLED
                    ? "None installed here yet."
                    : mapShown == Shown.AVAILABLE
                            ? "All of these are installed."
                            : "Nothing in this category.");
        else
            mapStatus.setText(String.format("%d of %d installed",
                    installedMaps.size(), allMaps.size()));
    }

    private void install(final Depot.BaseMap map) {
        mapStatus.setText("Getting " + map.name + "…");
        baseMaps.install(map, mapCallback());
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
                        + " installed — it is in ATAK's map source list.");
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
                    List<Depot.RecMap> recMaps) {
                Log.i(TAG, "onForests forests=" + fetched.size()
                        + " recmaps=" + recMaps.size());
                allForests.clear();
                allForests.addAll(fetched);
                allRecMaps.clear();
                allRecMaps.addAll(recMaps);
                refreshInstalledPackages();
                applyForestFilter();
            }

            @Override
            public void onError(String message) {
                Log.w(TAG, "package catalog: " + message);
                forestStatus.setText("Could not read packages: " + message);
            }
        });
    }

    /** Ask the filesystem once per listing rather than once per row. */
    private void refreshInstalledPackages() {
        installedPackages.clear();
        for (Depot.Forest f : allForests)
            if (PackageInstaller.isInstalled(f))
                installedPackages.add(f.id());
        for (Depot.RecMap m : allRecMaps)
            if (PackageInstaller.isInstalled(m))
                installedPackages.add(m.id());
    }

    private void applyForestFilter() {
        refreshOutlinesButton();
        final String q = forestSearch.getText().toString().trim().toLowerCase();

        packageFilter.setText(labelFor(packageShown));

        shownPackages.clear();
        for (Depot.Package p : sourceList()) {
            if (!q.isEmpty() && !matches(p, q))
                continue;
            final boolean have = installedPackages.contains(p.id());
            if (packageShown == Shown.INSTALLED && !have)
                continue;
            if (packageShown == Shown.AVAILABLE && have)
                continue;
            shownPackages.add(p);
        }
        packageAdapter.notifyDataSetChanged();

        if (activeForestId == null)
            forestStatus.setText(statusLine(q));
    }

    /**
     * An empty list has three quite different causes and they must not look
     * alike: nothing published, nothing matched, or nothing installed. Reporting
     * "0 of 0 installed" for all three reads as a broken search.
     */
    private String statusLine(String query) {
        final int total = sourceList().size();
        if (total == 0)
            return packageMode == PackageMode.RECMAPS
                    ? "No ranger district maps in the catalog yet."
                    : "No packages in the catalog yet.";

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

    /**
     * Search the unit as well as the name: someone looking for a district often
     * knows the forest it sits in rather than the district's own name.
     */
    private static boolean matches(Depot.Package p, String q) {
        if (p.name().toLowerCase().contains(q))
            return true;
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
            public void onInstalled(Depot.Package p, java.io.File dest) {
                activeForestId = null;
                showCancelBar(false);
                installedPackages.add(p.id());
                packageAdapter.notifyDataSetChanged();
                forestStatus.setText(p.name()
                        + " installed — it is in ATAK's map layer list.");
            }

            @Override
            public void onRemoved(Depot.Package p) {
                installedPackages.remove(p.id());
                packageAdapter.notifyDataSetChanged();
                applyForestFilter();
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
                nifcStatus.setText("Could not reach NIFC: " + message);
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

                nifcAllRows.clear();
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
                nifcStatus.setText("Could not read that folder: " + message);
            }
        });
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
            if (label == null) {
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
        final List<InstalledIndex.Record> below = InstalledIndex.under(
                source == null ? null : source.id(), nifcDecodedPath);

        final java.util.Set<String> indexed = new HashSet<>();
        for (final InstalledIndex.Record r : below)
            indexed.add(InstalledIndex.lower(r.installName));

        int installed = below.size(), available = 0;
        final java.util.Set<String> listedHere = new HashSet<>();

        for (final Object o : nifcAllRows) {
            if (o instanceof MapSource.Entry) {
                nifcRows.add(o);
                continue;
            }
            final MapSource.Posting posting = (MapSource.Posting) o;
            final boolean have = nifcInstalled.contains(posting.id());
            if (have) {
                listedHere.add(InstalledIndex.lower(posting.name()));
                // Downloaded before the index existed, so it is on the device
                // but not remembered. Counted here rather than going missing.
                if (!indexed.contains(InstalledIndex.lower(posting.name())))
                    installed++;
            } else {
                available++;
            }
            if (nifcShown == Shown.ALL
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

        final int count = nifcShown == Shown.INSTALLED ? installed
                : nifcShown == Shown.AVAILABLE ? available
                        : installed + available;
        nifcFilter.setText(pluginContext.getString(labelFor(nifcShown))
                + " (" + count + ")");
        nifcAdapter.notifyDataSetChanged();
        describeListing(nifcHidden);
    }

    /**
     * Says what is on screen and, as importantly, what is not: a folder holding
     * geodatabase zips alongside two PDFs would otherwise look like a folder
     * holding two files.
     */
    private void describeListing(int hidden) {
        int folders = 0, maps = 0;
        for (final Object o : nifcRows) {
            if (o instanceof MapSource.Entry)
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
                if (!(o instanceof MapSource.Entry))
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

    private static String prettyGacc(String raw) {
        return pretty(raw);
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

            if (item instanceof MapSource.Entry) {
                final MapSource.Entry e = (MapSource.Entry) item;
                name.setText(e.name);
                detail.setText("Folder");
                action.setVisibility(View.GONE);
                row.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        browse(source.childPath(nifcPath, e),
                                join(nifcDecodedPath, e.name), Nav.DESCEND);
                    }
                });
                return row;
            }

            final MapSource.Posting posting = (MapSource.Posting) item;
            final boolean done = nifcInstalled.contains(posting.id());
            final boolean active = posting.id().equals(activePostingId);

            name.setText(posting.name());

            if (done) {
                // Only the hint is coloured; the size stays the same weight as
                // every other row, so the list does not turn into a christmas
                // tree once a few maps are installed.
                final String hint = " · tap to go there";
                final SpannableString line =
                        new SpannableString(posting.describe() + hint);
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
            final Button eye = row.findViewById(R.id.region_visible);
            final ProgressBar bar = row.findViewById(R.id.region_progress);

            final boolean active = pkg.id().equals(activeForestId);
            final boolean done = installedPackages.contains(pkg.id());

            // Same Show/Hide the incident maps have. A district map is a PDF
            // overlay like any other and there is no reason toggling it should
            // mean a trip out to the overlay manager. Offered only where ATAK
            // will answer, so a vector tile package -- which has no such handler
            // -- simply does not show one.
            eye.setVisibility(View.GONE);
            if (done && !active) {
                final Boolean visible = PackageInstaller.isVisible(pkg);
                if (visible != null) {
                    eye.setVisibility(View.VISIBLE);
                    eye.setText(pluginContext.getString(
                            visible.booleanValue() ? R.string.hide_map
                                    : R.string.show_map));
                    eye.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            if (PackageInstaller.setVisible(pkg,
                                    !visible.booleanValue()))
                                packageAdapter.notifyDataSetChanged();
                            else
                                forestStatus.setText("ATAK would not change "
                                        + pkg.name());
                        }
                    });
                }
            }

            name.setText(pkg.name());
            if (done) {
                // Only the hint is coloured; the size stays the same weight as
                // every other row so the list does not turn into a christmas
                // tree once a few things are installed.
                final String hint = " · tap to go there";
                final SpannableString line =
                        new SpannableString(pkg.describe() + hint);
                line.setSpan(
                        new ForegroundColorSpan(pluginContext.getResources()
                                .getColor(R.color.action_green)),
                        line.length() - hint.length(), line.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                detail.setText(line);
            } else {
                detail.setText(pkg.describe());
            }

            if (active && forestTotal > 0) {
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
            final boolean unusable = packageMode == PackageMode.FORESTS
                    && !done && !PackageInstaller.supportsVectorPackages();

            action.setEnabled(!active && activeForestId == null && !unusable);
            if (active) {
                final int pct = forestTotal > 0
                        ? (int) (forestDone * 100L / forestTotal) : 0;
                action.setText(pct + "%");
            } else {
                action.setText(done ? R.string.remove : R.string.download);
            }
            if (unusable)
                detail.setText(pkg.describe() + " · needs ATAK 5.7 or newer");
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

            name.setText(map.name);
            detail.setText(map.describe());

            // One button that flips rather than a swipe: a hidden gesture is a
            // poor fit for a list read with gloves on, and removing a map source
            // is cheap to undo -- the row goes straight back to Get.
            final boolean done = installedMaps.contains(map.id);
            action.setEnabled(true);
            action.setText(done ? R.string.remove : R.string.download);
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
