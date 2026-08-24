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
import com.atakmap.android.mapdepot.PackageInstaller;
import com.atakmap.android.mapdepot.RegionInstaller;
import com.atakmap.coremap.log.Log;

import java.io.File;

import java.util.ArrayList;
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

    /**
     * Forests and ranger district maps are the same interaction -- search a long
     * list of units, download one large file -- so they share one screen, and a
     * mode says which list it is showing.
     */
    private enum PackageMode {
        FORESTS, RECMAPS
    }

    private PackageMode packageMode = PackageMode.FORESTS;

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
    }

    @Override
    public void onStop() {
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
        status = root.findViewById(R.id.depot_status);
        countryButton = root.findViewById(R.id.country_button);

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
        packageAdapter = new PackageAdapter(pluginContext);
        ((ListView) root.findViewById(R.id.forest_list)).setAdapter(packageAdapter);

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
    }

    private void showPackages(PackageMode mode) {
        packageMode = mode;
        homeView.setVisibility(View.GONE);
        dtedView.setVisibility(View.GONE);
        baseMapView.setVisibility(View.GONE);
        forestView.setVisibility(View.VISIBLE);
        forestSearch.setHint(mode == PackageMode.FORESTS
                ? R.string.search_forests : R.string.search_recmaps);
        forestSearch.setText("");
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
            if (code == null || code.equals(r.country))
                shown.add(r);
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

    private void loadCatalog() {
        status.setText(R.string.loading_catalog);
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
                    status.setText(R.string.catalog_offline);
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

                // Strings still come from the plugin's own resources; only the
                // window comes from the host.
                new AlertDialog.Builder(hostContext())
                        .setTitle(region.name)
                        .setMessage(msg.toString())
                        .setPositiveButton(pluginContext.getString(R.string.get),
                                new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d, int w) {
                                        start(region, manifest);
                                    }
                                })
                        .setNegativeButton(pluginContext.getString(R.string.cancel), null)
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
                status.setText(region.name + " failed: " + message);
                Log.w(TAG, region.id + " install failed: " + message);
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

        shownMaps.clear();
        for (Depot.BaseMap m : allMaps)
            if (want == null || want.equals(m.category))
                shownMaps.add(m);
        mapAdapter.notifyDataSetChanged();
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
        final String q = forestSearch.getText().toString().trim().toLowerCase();
        shownPackages.clear();
        for (Depot.Package p : sourceList())
            if (q.isEmpty() || matches(p, q))
                shownPackages.add(p);
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

        if (shownPackages.isEmpty())
            return "Nothing matches \"" + query + "\" in " + total + ".";

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
                Log.w(TAG, "package " + p.id() + ": " + message);
                activeForestId = null;
                packageAdapter.notifyDataSetChanged();
                forestStatus.setText(p.name() + " failed: " + message);
            }
        };
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
            final ProgressBar bar = row.findViewById(R.id.region_progress);

            final boolean active = pkg.id().equals(activeForestId);
            final boolean done = installedPackages.contains(pkg.id());

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

            // Tapping the row itself sends the map there, the way tapping an
            // entry in ATAK's overlay list does. The button keeps its own click,
            // so Get and Remove are unaffected.
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (done)
                        PackageInstaller.goTo(pkg);
                }
            });

            if (active && forestTotal > 0) {
                bar.setVisibility(View.VISIBLE);
                bar.setProgress((int) (forestDone * 100L / forestTotal));
            } else {
                bar.setVisibility(View.GONE);
            }

            // While one package is downloading the others are not offered: these
            // run to a gigabyte and two at once on a phone hotspot serves nobody.
            action.setEnabled(active || activeForestId == null);
            action.setText(active ? R.string.cancel
                    : done ? R.string.remove : R.string.get);
            action.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (active) {
                        packages.cancel();
                        activeForestId = null;
                        forestStatus.setText("Cancelled.");
                        packageAdapter.notifyDataSetChanged();
                    } else if (done) {
                        uninstallPackage(pkg);
                    } else {
                        installPackage(pkg);
                    }
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
            action.setText(done ? R.string.remove : R.string.get);
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
                action.setEnabled(!done && activeRegionId == null);
                action.setText(done ? R.string.installed : R.string.get);
            }

            action.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    confirmAndInstall(region);
                }
            });
            return row;
        }
    }
}
