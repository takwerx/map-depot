# Map Depot for ATAK — User Guide

**Version 1.16 · takwerx**

**Download Map Depot 1.16** (pick the one matching your ATAK-CIV version, sideload, then load it in ATAK's Plugins manager):

- **ATAK-CIV 5.6:** https://github.com/takwerx/map-depot/releases/download/v1.16/ATAK-Plugin-MapDepot-1.16--5.6.0-civ-release.apk
- **ATAK-CIV 5.7:** https://github.com/takwerx/map-depot/releases/download/v1.16/ATAK-Plugin-MapDepot-1.16--5.7.0-civ-release.apk
- **ATAK-CIV 5.8:** https://github.com/takwerx/map-depot/releases/download/v1.16/ATAK-Plugin-MapDepot-1.16--5.8.0-civ-release.apk

All releases: https://github.com/takwerx/map-depot/releases

Map Depot downloads map data from inside ATAK. Without it, getting a new base map
or a terrain file onto a device means a web browser, the Downloads folder and the
Import Manager — awkward on a phone, worse in the field. Map Depot puts the
catalog in the plugin, and installs what you pick into the folder ATAK already
scans.

---

## Before you start

Published builds exist for **ATAK-CIV 5.6, 5.7 and 5.8**. Plugins are matched to
the ATAK release they were built against, so download the one for the ATAK
version on your device — a mismatch will not load, and the failure does not look
like a version problem.

Map Depot needs outbound HTTPS (port 443) and nothing else. No account, no key,
no configuration, and no TAK server involvement.

**On ATAK 5.6, Offline Public Land Vector Tiles does not work.** Vector tile package support
arrived in ATAK 5.7, so on 5.6 a forest basemap would download and then be
invisible to ATAK. Map Depot marks those maps as needing 5.7 or newer rather
than letting you spend a gigabyte on one. Elevation, streaming base maps and
Forest Service map sheets all work normally on 5.6.

**On official ATAK 5.8.0.4, Offline Public Land Vector Tiles is not available either:
update ATAK to 5.8.0.5.** ATAK 5.8.0.4 does not start with a vector tile package
on the phone once it has cataloged it — it dies before any plugin loads, with no
message. TAK Product Center fixed this in 5.8.0.5, and there Map Depot offers
these maps as on 5.7. On 5.8.0.4 Map Depot dims the section and says so, and
does not offer the downloads. If you already have packages, Map Depot asks once,
shortly after ATAK starts, whether to move them to `atak/imagery.off`, where
ATAK does not look. Take it: otherwise ATAK will not open after its next
restart, and the recovery is to move the files out with a file manager. Once
ATAK is on 5.8.0.5 or newer, move them back to `atak/imagery` by hand.
Elevation, streaming base maps, Forest Service map sheets and the incident maps
all work normally on every 5.8.

---

## 1. Opening it

Load the plugin from ATAK's Plugins manager, then open it from the toolbar.

![Map Depot in the ATAK toolbar](screenshots/1_plugin_in_toolbar.png)

The landing page offers nine kinds of data, each on its own page.

![The Map Depot landing page](screenshots/2_landing_page.png)

- **Elevation (DTED2)** — terrain, by state or province. What viewsheds and
  elevation readouts need.
- **Streaming Base Maps** — imagery and street maps served over the network,
  including transparent overlays.
- **Offline Public Land Vector Tiles** — Forest Service vector basemaps, one per national
  forest or grassland, held on the device.
- **Offline Public Land PDFs** — the printed map sheets, as GeoPDFs: ranger
  district, forest, regional, 100K and FSTopo.
- **Incident Maps (NIFC)** — the operational maps posted for a going fire: ops,
  division, air operations, transport, briefing and IR.
- **Beacon Box Maps** — FlameMapper's neighborhood pre-plans, four sheets per
  neighborhood, as GeoPDFs.
- **Drone IR Maps (UASWFC)** — infrared flown by uncrewed aircraft, as
  georeferenced PDFs and KMZs.
- **Disaster Aerials** — the imagery flown or collected after a hurricane,
  flood, fire or tornado by NOAA, the Civil Air Patrol and Maxar, one overlay
  per flight, drawn over whatever map you have.
- **Satellite (Sentinel-2)** — recent satellite passes over the map center or
  your position, in true color or as a fire view, streamed over the map.

---

## 2. How every page works

A list of what is available, a **Download** button on each row, and a line at the
top saying what is happening. Rows report one of three states, so the list
doubles as an inventory of what the device already holds.

![Rows showing Remove, Download and Finish](screenshots/3_elevation_row_states.png)

- **Download** — none of it is on the device.
- **Finish** — some of it is. Only the missing part downloads.
- **Remove** — all of it is here.

Downloads resume rather than restart after a dropped connection, and can be
cancelled at any point. Whatever had already arrived is kept, and **Finish**
picks it up from there.

---

## 3. Elevation

Elevation is DTED level 2, by state and province, and shared between neighbors:
it comes in one-degree squares, and a square covering one state usually covers
part of the next. The depot stores each square once, so a second state near the
first is much smaller than its own size suggests.

The country button switches between the United States and Canada.

![Choosing a country](screenshots/4_country_chooser.png)

Confirming quotes what **this** device still needs, not the region's full size,
and says how much it will skip.

![Confirming a region download](screenshots/5_confirm_download.png)

While a region downloads, its row carries a progress bar and a percentage, and
the line above counts cells and megabytes. **Cancel download** sits above the
list, full width.

![A region downloading](screenshots/6_download_in_progress.png)

Cancelling keeps every cell that had already finished: the row becomes
**Finish**, and picking it up later downloads only what is left. Other rows wait
while one is downloading — one region at a time.

---

## 4. Streaming base maps

Map sources served over the network rather than stored on the device: satellite
imagery, street maps, nautical charts, topographic sheets. Installing one adds it
to ATAK's map source list; ATAK fetches tiles as you pan and caches what it has
drawn.

The category button narrows a long list.

![Choosing a category](screenshots/9_category_chooser.png)

Each row says who serves the map and how far it can be zoomed. **Zoom 21** is
close enough to see parking spaces; **zoom 10** is regional only.

![Base map rows](screenshots/10_base_map_rows.png)

### Maps that need your own key

A few servers only serve clean tiles to requests that carry an API key of
your own; CARTO's **Voyager**, **Positron** and **Dark Matter** are the ones
in the list. Their rows say *needs your API key*. Tap **Add** and the plugin
asks for the key, with a **Get a key** button that opens the provider's page
(CARTO's is free for ordinary use). The key is kept on the device and written
into the map source file ATAK reads; the row then says *key set*. To change or
clear it later, go to **Settings › Tool Preferences › Map Depot**. After a
change, the affected rows go back to **Add** so you can re-add them with the
new key.

### Overlays

Some sources are *overlays* rather than base maps: roads, labels, contour lines,
flood zones or radar on a transparent background, drawn over whatever is
underneath. The **Overlay** category lists them.

![A reference overlay over imagery](screenshots/18_reference_overlay.png)

An overlay is something you switch **on**, not something you switch **to**.
Adding one from Map Depot switches it on over the map you already have, and
any number can be on at once. The list is sectioned: roads, streets and
points of interest first, then trails, land and boundaries, hazards, weather
and terrain.

![The overlay list, sectioned](screenshots/52_overlay_sections.png)

An added overlay draws over the map, and over any disaster aerial too, which
is what a roads overlay is for. The row keeps a **Hide / Show** button beside
Remove, so switching it off is never a trip to ATAK's Overlay Manager, though
it is listed there as well, under **Image Overlay**. The one exception is
OpenFreeMap, a vector layer, which ATAK keeps in its own Maps list.

This is what makes fresh imagery usable. Aerial imagery flown after an event has
no street names on it. **Google Roads and Labels (overlay)** puts them back
without hiding the imagery.

---

## 5. Offline public land vector tiles

A Forest Service vector basemap for each national forest and grassland — roads,
trails, boundaries, contours and labels — held on the device and drawn with no
network at all. These are large: tens of megabytes for a grassland, over a
gigabyte for the biggest forests.

**Needs ATAK 5.7 or newer, and not official 5.8.0.4.** On 5.6 these rows are
disabled and say so. On official 5.8.0.4 they are disabled too, because ATAK
does not start with one of these installed; 5.8.0.5 fixed that. See "Before
you start".

Type part of a forest's name to narrow the list.

![Searching for a forest](screenshots/12_search_forests.png)

Downloads report progress and can be cancelled.

![A forest basemap downloading](screenshots/13_package_downloading.png)

Once installed it is a map source like any other — forest road numbers, trail
names and boundaries, all local. Being vector rather than imagery it stays sharp
at any zoom, and takes far less space than the equivalent tiles would.

![A forest basemap on the map](screenshots/17_forest_basemap_on_map.png)

---

## 6. Offline Public Land PDFs

The Forest Service's printed map sheets, as GeoPDFs. Where a forest basemap is
the whole forest at map scale, a sheet is what an office hands out, with its
margin, legend and printed detail. Four series, picked with the button beside
Back; each entry shows how many sheets it holds:

- **Ranger District & Forest** — the district maps and the whole-forest visitor
  maps. Search by forest name to see its districts. Each row names the forest
  and state it belongs to.
- **Regional** — one map per Forest Service region, in two editions: with ranger
  district boundaries, or with forest boundaries. Eighteen sheets.
- **100K** — the 1:100,000 series, nationwide, forest land or not. About 1,800
  sheets of 12 to 46 MB.
- **USFS Topo 24K** — the 1:24,000 quads. About 18,000 sheets of 3 to 4 MB. The
  list is fetched the first time you choose it, then kept on the device.

The 100K and FSTopo lists are sorted by distance from the center of the map,
nearest first, and every row says how far and in which direction. Pan the map
to where you are working, open the list, and the sheet you want is at the top.
Only the nearest 300 are listed, and the status line says so; move the map or
type part of a quad or state name to reach the rest. The distance is in the
units your ATAK is set to.

![The district list with the series row](screenshots/15_ranger_districts.png)

![Choosing a series](screenshots/31_series_chooser.png)

![USFS Topo quads nearest you](screenshots/30_usfs_topo_nearest.png)

Installed, a sheet lands as a georeferenced overlay, in the right place on the
ground, over whatever base map is showing.

![A district map drawn over imagery](screenshots/16_district_map_on_map.jpg)

---

## 7. Incident maps

Two archives the incident community publishes to. Where every other page is a
catalog that changes with a release, these change hourly: what is on the page is
what the fire's GIS shop uploaded this morning.

- **Incident Maps (NIFC)** — `ftp.wildfire.gov`. Ops, division, air operations,
  transport, briefing and IR products, for fires across the country.
- **Drone IR Maps (UASWFC)** — `uaswfc.org`. Infrared flown by uncrewed
  aircraft.

Both are public and need no account.

### Picking your area

Pick your geographic area once and it is remembered. It is a button, not a buried
setting, so you can change it when the next fire is somewhere else.

![Picking a geographic area](screenshots/20_incident_region_picker.png)

From there you walk down to your fire and into whatever folder that shop uses.
They do not all agree — one fire has `DAILY MAP PRODUCT`, `GIS` and `IR`, another
has `Current`, `Products` and `QR` — so the plugin shows what is actually there
rather than a fixed set of screens.

Folders named by date are listed **newest first**, because the map you want is
almost always today's.

![Date folders, newest first](screenshots/21_incident_dates_newest_first.png)

### Names you can read

The archives name files for a GIS shop's file browser. Map Depot renames them for
a phone:

```
ops_arch_e_port_20260828_0115_RoweCreekComplex_ORPRD000491_0828day.pdf
  →  OR-PRD-ROWE-CREEK-COMPLEX-MAP-OPS-082826.pdf
```

![A file list](screenshots/22_incident_file_list.png)

The date is the **operational period** — the shift the map is for, not the moment
it came off the plotter. Division letters, sortie numbers and IR areas are all
kept, because two maps you can both pick have to be able to sit on the device
together. The original name is shown underneath, so a map named over the radio
can still be found.

A PDF says **MAP** and installs as a georeferenced overlay. A KMZ says
**OVERLAY** and installs as ATAK's own kind of overlay. The name tells you which
you are about to get.

![A KMZ and a PDF in the same folder](screenshots/25_overlay_and_map.png)

Only maps are offered: PDFs and KMZs. Geodatabases, shapefile bundles and flight
logs are left out — a flight log is a PDF, but it is a sortie's paperwork rather
than a map, and one filed as a GRG would drape a flat page over your map. The
line under the buttons says how many were hidden, so a short list is never
mistaken for an empty folder.

### Pinning the fire you are working

A crew assigned to a fire opens the same folder twenty times a day, and reaching
it means a region, a year folder, the fire and then a product folder.

Every folder has a **Pin** button.

![Pinning a fire](screenshots/23_pin_a_fire.png)

It then sits at the top of the first screen, in cyan, one tap away. **Remove** on
a pinned row unpins it and deletes nothing.

![A pinned fire at the top](screenshots/24_pinned_at_top.png)

Anything can be pinned, not only a fire: if you live in one product folder, pin
that and you land straight in it. Pins survive a restart and a plugin update, and
the two archives keep their own.

### On the map

![A drone IR product on the ground](screenshots/27_drone_ir_on_map.jpg)

**Outlines** turns the footprints of every GRG on or off, which is how you see
what covers the ground in front of you before deciding what to turn on.

![Outlines on](screenshots/26_outlines_on.jpg)

---

### Beacon Box maps

FlameMapper's neighborhood pre-plans for the Santa Monica Mountains
communities and a few others: for each neighborhood a base map, an aerial, a
fire-science sheet and a structure-vulnerability sheet, all GeoPDFs. They are
browsed the same way as the incident maps: pick your area, open a
neighborhood, tick what you want, Download. Each row says which of the four
it is and how big. They install as overlays and draw over whatever base map
is showing, exactly like an incident map.

![Malibu's neighborhoods, east to west](screenshots/33_beacon_neighborhoods.png)

![A neighborhood's maps](screenshots/34_beacon_maps.png)

The maps' own site cannot be reached from a phone, so Map Depot reads them
from its depot, where they are mirrored with the author's permission. Maps by
FlameMapper.

## 8. Disaster Aerials

After a hurricane makes landfall, and after some floods, fires and tornadoes,
the damaged area gets flown, and the imagery is public within a day or two.
Map Depot lists three sources in one list, events from this year and last,
newest first, and every flight in each:

- **NOAA**, the National Geodetic Survey's aerial flights, 15 to 30 cm a pixel.
- **Civil Air Patrol**, flown for FEMA, down to 9 cm a pixel, served by FEMA.
- **Maxar**, satellite, 30 cm, released under the Maxar Open Data program
  for major US events, with a before and an after where both exist, and the
  cloud cover of the best pass on the row. Non-commercial license.

Map Depot lists what each agency publishes and does not grade it. A rushed
mosaic can sit off its true position, and a satellite pass can be mostly
cloud; the row's date and cloud figure are there to judge by, and Remove
is one tap. The event button starts on **All Events**, every flight from
every source in one list, and narrows to one event when you pick it.

![The flights of one event](screenshots/50_disaster_aerials.png)

Pick the event with the button under the search box. The count is how many
flights it has.

![Choosing an event](screenshots/51_event_chooser.png)

Each flight is an overlay: it covers the area that was flown, at 15 to 30 cm a
pixel, and draws over whatever map you have. Outside the flown area your map
shows through. Flights are listed newest first; the button on the right
switches that to nearest the map or nearest you, and every row says how far
away the flight is, or **in view** when the map is on it. Tap a row before
adding it and the map goes to where the flight is.

A flight row has two buttons, and they are different things:

- **Add Online** switches the flight on as a streaming overlay. Nothing of
  any size lands on the phone; the tiles come from the agency as you pan,
  like any streaming map, so it needs a connection. Once added the button
  reads Remove.
- **Download**, with the copy's size in the row text, puts a copy of the
  flight on the phone for when there is no signal. It is a real download, a
  few tens to a few hundred MB, built on the Map Depot server at the flight's
  own detail where that fits; a long flight is built a step coarser to stay
  under about 500 MB, and the row says so. It draws by itself, with or
  without the online layer, and comes back after a restart with no network.
  Once downloaded the button reads Remove.

Added or downloaded, a row says **tap to go there**, and tapping it takes the
map to the flight. **Hide** turns it off without removing it. **Outlines** at
the left of the status line draws an orange box with the flight's name around
every flight on the phone, so you can see where the imagery is before you zoom
in. The boxes are listed in Overlay Manager as Disaster Aerial Outlines.

![Outlines on: two flights boxed over the base map](screenshots/53_flight_outlines.jpg)

A flight has no street names on it. Add the Google roads overlay from
Streaming Base Maps and the names and house numbers draw over the imagery,
which is how a crew finds an address in it.

![A flight with the roads overlay on top](screenshots/54_roads_over_flight.jpg)

During a storm NOAA adds flights every day. The list says when the catalog was
last built; a flight flown after that appears the next day, and its offline
copy the morning after.

---

## 9. Satellite (Sentinel-2)

Recent satellite pictures of the ground, from the European Sentinel-2
satellites, for the last 14 days. Each row is one pass over one area, named for
the nearest town, with its date and how much of it is cloud.

![The pass list](screenshots/60_satellite_list.png)

The right-hand button sets where to look: **Newest First** around the map
center, **Near: Map Center** (passes covering the center come first), or
**Near: Me** around your own position. Tapping it searches again from there.
The left-hand button picks the view.

**Tap a row to go there.** An orange box shows exactly the area the pass
covers, before you add anything.

![The orange box shows what the pass covers](screenshots/61_satellite_outline.png)

**Add Online** streams the picture over whatever map you have. It needs a
signal; this version has no offline copy of a satellite pass. Added, the pass
draws over the map and the row offers **Remove**.

![A pass added and drawing](screenshots/62_satellite_added.png)

### Two views

- **True Color**, the picture as the eye sees it, about 10 meters to a pixel.
- **SWIR Fire View**, the same place in shortwave infrared. Healthy plants
  show bright green and bare or burned ground shows brown; active fire and a
  fresh burn glow orange and red. Thin smoke mostly clears.

![True Color](screenshots/64_satellite_true_color.png)

![SWIR Fire View, the same fields](screenshots/65_satellite_swir.png)

Every pass you add is held in both views, so switching downloads nothing. The
**View** button chooses which to show, and under **Remove** each added pass has
a button that flips every pass you added to the other view at once.

![The View chooser](screenshots/63_satellite_view_chooser.png)

Clouds hide the ground in both views, so check the cloud figure on the row. The
satellites pass each place every few days, and a pass usually appears within a
day. Contains modified Copernicus Sentinel data.

---

## 10. Finding what you already have

Every page has a filter button, next to **Back**, that cycles **All** →
**Installed** → **Available**. Set to **Installed**, the page becomes a list of
what is on this device.

![The list filtered to installed](screenshots/11_filter_installed.png)

An installed offline map says **tap to go there**, in green. Tapping the row
turns that map on and takes you to it.

![An installed row offering tap to go there](screenshots/14_tap_to_go_there.png)

Straight after a download it may say it is still being added to the map list —
ATAK is indexing the file, which takes a while for a large one. It goes there on
its own when that finishes. Nothing needs tapping again.

Set to **Available**, the page hides everything you already hold, which is the
view you want when you are adding to a device rather than checking it.

![The list filtered to available](screenshots/29_filter_available.png)

When a filter is hiding everything, the page says so rather than reading as an
empty folder — "3 maps here, hidden by the Installed filter" is a different
problem from a folder with nothing in it.

![A folder whose contents are hidden by the filter](screenshots/28_hidden_by_filter.png)

### Show and Hide

An installed overlay or GRG carries a **Show** or **Hide** button beside
**Remove**. It does what the Overlay Manager's checkbox does, without leaving the
plugin — useful when you hold a dozen maps of the same fire and want one of them
on at a time.

---

## 11. Removing

**Remove** appears on anything installed, and says what removing actually frees
before it does it.

![Removing a region](screenshots/7_remove_shared_cells.png)

For elevation that is rarely the region's full size. Squares shared with a
neighboring region you also hold are kept, because that region still needs them,
and the dialog names which neighbors those are.

A small state wedged between larger ones may share **all** its squares. Rather
than run a removal that frees nothing, Map Depot says so, and names what else
would have to go for the space to come back.

![A removal that would free nothing](screenshots/8_remove_frees_nothing.png)

**Remove** on a pinned folder is a different thing: it unpins the folder and
deletes nothing. A pin is a shortcut, not a download.

---

## 12. Where files go

Map Depot writes into ATAK's own folders, so what it installs is
indistinguishable from data put there by hand.

| Data | Folder |
|---|---|
| Elevation | `atak/DTED/`, one file per one-degree square |
| Streaming base maps | `atak/imagery/`, as map source files |
| Streaming overlays and disaster aerials | `atak/grg/`, as map source files, so ATAK lists them under Image Overlay |
| Satellite passes | `atak/grg/`, as map source files, two per pass (one per view) |
| Disaster aerials, offline copies | `atak/grg/`, as MBTiles, listed under Image Overlay too |
| Offline public land vector tiles | `atak/imagery/`, as vector tile packages |
| Forest Service map sheets | `atak/grg/`, as georeferenced PDFs |
| Incident and drone maps (PDF) | `atak/grg/`, as georeferenced PDFs |
| Incident and drone maps (KMZ) | `atak/overlays/`, as ATAK overlays |

Installed maps appear in ATAK's own layer list alongside anything else you have.

![An installed package in ATAK's layer list](screenshots/19_atak_layer_list.png)

Removing something through Map Depot tells ATAK first and deletes the file after,
so no layer is left pointing at something that is gone.

---

## 13. In the plugin

The same guide ships inside the plugin as a PDF, for when you are in the field
without this page. In ATAK go to **Settings → Tool Preferences → Map Depot →
Plugin Documentation**.

---

## Questions and problems

Issues and questions: https://github.com/takwerx/map-depot/issues
