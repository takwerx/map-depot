package com.atakmap.android.mapdepot.plugin;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.atak.plugins.impl.PluginContextProvider;
import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.mapdepot.Depot;
import com.atakmap.android.mapdepot.DepotClient;
import com.atakmap.android.mapdepot.RegionInstaller;
import com.atakmap.coremap.log.Log;

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

    /** Everything the catalog offers. */
    private final List<Depot.Region> allRegions = new ArrayList<>();

    /** Just the country currently selected — what the list actually shows. */
    private final List<Depot.Region> shown = new ArrayList<>();

    /** Country code per spinner position, in the order the spinner shows them. */
    private final List<String> countryCodes = new ArrayList<>();

    private RegionAdapter adapter;
    private TextView status;
    private View homeView, dtedView;
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
        client = new DepotClient(pluginContext.getCacheDir());
        installer = new RegionInstaller();
        uiService.addToolbarItem(toolbarItem);
    }

    @Override
    public void onStop() {
        if (installer != null) {
            installer.shutdown();
            installer = null;
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

        // Imagery is the other half of the depot and has nothing published yet.
        // Say so on the button rather than hiding it, so the shape of the plugin
        // is visible before the content exists.
        final Button maps = root.findViewById(R.id.btn_maps);
        maps.setEnabled(false);
        ((TextView) root.findViewById(R.id.home_note))
                .setText(R.string.maps_not_yet);

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
    }

    /** The catalog is only fetched once the operator asks for elevation. */
    private void showDted() {
        homeView.setVisibility(View.GONE);
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

    private void loadCatalog() {
        status.setText(R.string.loading_catalog);
        client.fetchCatalog(new DepotClient.CatalogCallback() {
            @Override
            public void onCatalog(List<Depot.Region> fetched, boolean cached) {
                catalogLoaded = true;
                allRegions.clear();
                allRegions.addAll(fetched);
                populateCountries();
                applyCountryFilter();

                if (cached) {
                    status.setText(R.string.catalog_offline);
                } else {
                    status.setText("");
                }
            }

            @Override
            public void onError(String message) {
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
    private Context hostContext() {
        final MapView mv = MapView.getMapView();
        return mv != null ? mv.getContext() : pluginContext;
    }

    private void toast(String s) {
        Toast.makeText(hostContext(), s, Toast.LENGTH_SHORT).show();
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
                final boolean done = completeById.contains(region.id);
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
