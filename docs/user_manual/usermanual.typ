#import "@preview/polylux:0.4.0": *
#import "formatting.typ": *

#show: userguide.with(
   plugin-name: "Map Depot",
   plugin-version: "1.2",
   platform: "ATAK",
   platform-version: "5.8.0",
)

#tak-slide[
= Overview

Map Depot downloads map data from inside ATAK. Without it, getting a new base
map or a terrain file onto a device means a browser, a download folder and the
Import Manager -- on a phone, in the field, often on a hotspot. Map Depot
removes that trip: the catalog is in the plugin, and what you pick installs
itself where ATAK expects to find it.

#v(6pt)
#toolbox.side-by-side(columns: (3fr, 9fr))[
  #image("01.png", width: 100%)
][
  Open it from the toolbar. The icon sits with the other tools.
]
]

#tak-slide[
#toolbox.side-by-side(columns: (4fr, 8fr))[
  #image("02.png", width: 78%)
][
  Six kinds of data, each on its own page:

  - *Elevation (DTED2)* -- terrain, by state or province. What viewsheds and
    elevation readouts need.
  - *Streaming Base Maps* -- imagery and street maps served over the network,
    including transparent overlays.
  - *Offline Public Lands* -- Forest Service vector basemaps, one per national
    forest or grassland, held on the device.
  - *Offline Ranger District Maps* -- the printed district maps, as GeoPDFs.
  - *Incident Maps (NIFC)* -- the operational maps posted for a going fire:
    ops, division, air operations, IR.
  - *Drone IR Maps (UASWFC)* -- infrared products flown over a fire, as
    georeferenced PDFs and KMZs.
]
]

#tak-slide[
= Getting map data

Every page works the same way: a list of what is available, a *Download* button
on each row, and a line at the top saying what is happening.

#v(6pt)
#toolbox.side-by-side(columns: (5fr, 7fr))[
  #image("03.png", width: 88%)
][
  Rows report one of three states, so the list doubles as an inventory of what
  the device already holds:

  - *Download* -- none of it is here.
  - *Finish* -- some of it is. Only the missing part downloads.
  - *Remove* -- all of it is here.

  Downloads resume rather than restart after a dropped connection, and can be
  cancelled at any point. Whatever had already arrived is kept, and *Finish*
  picks it up from there.
]
]

#tak-slide[
= Elevation

DTED level 2, by state and province, and shared between neighbors: elevation
comes in one-degree squares, and a square covering one state usually covers
part of the next. The depot stores each square once, so a second state near
the first is much smaller than its own size suggests.

#v(6pt)
#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("04.png", width: 92%)

  The country button switches between the United States and Canada.
][
  #image("05.png", width: 92%)

  Confirming quotes what *this* device still needs, and says how much it will
  skip.
]
]

#tak-slide[
#toolbox.side-by-side(columns: (5fr, 7fr))[
  #image("06.png", width: 88%)
][
  While a region downloads, its row carries a progress bar and a percentage,
  and the line above counts cells and megabytes.

  *Cancel download* sits above the list, full width, where it can be hit
  without hunting for it. Cancelling keeps every cell that had already
  finished: the row becomes *Finish*, and picking it up later downloads only
  what is left.

  Other rows wait while one is downloading. One region at a time.
]
]

#tak-slide[
= Streaming base maps

Map sources served over the network rather than stored on the device:
satellite imagery, street maps, nautical charts, topographic sheets. Installing
one adds it to ATAK's map source list; ATAK fetches tiles as you pan and
caches what it has drawn.

#v(6pt)
#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("09.png", width: 92%)

  The category button narrows a long list.
][
  #image("10.png", width: 60%)

  Each row says who serves the map and how far it can be zoomed.
]
]

#tak-slide[
#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("18.png", width: 100%)
][
  Some sources are *overlays* rather than base maps: roads, labels and points
  of interest on a transparent background, drawn over whatever is underneath.

  This is what makes fresh imagery usable. Aerial imagery flown after an event
  has no street names on it. An overlay puts them back without hiding the
  imagery underneath.
]
]

#tak-slide[
= Offline public lands

A Forest Service vector basemap for each national forest and grassland --
roads, trails, boundaries, contours and labels -- held on the device and drawn
with no network at all. These are large: tens of megabytes for a grassland,
over a gigabyte for the biggest forests.

#v(6pt)
#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("12.png", width: 60%)

  Type part of a forest's name to narrow the list.
][
  #image("13.png", width: 60%)

  Downloads report progress and can be cancelled.
]
]

#tak-slide[
#toolbox.side-by-side(columns: (8fr, 4fr))[
  #image("17.png", width: 100%)
][
  Installed, the forest basemap is a map source like any other: forest road
  numbers, trail names and boundaries, all held locally.

  Being vector rather than imagery it stays sharp at any zoom, and takes far
  less space than the equivalent tiles would.
]
]

#tak-slide[
= Offline ranger district maps

The printed ranger district maps, as GeoPDFs. Where a forest basemap is the
whole forest at map scale, a district map is the sheet a district office hands
out, with its margin, legend and printed detail.

#v(6pt)
#toolbox.side-by-side(columns: (5fr, 7fr))[
  #image("15.png", width: 72%)
][
  Search by forest name to see its districts. Each row names the forest and
  state it belongs to.
]
]

#tak-slide[
#toolbox.side-by-side(columns: (8fr, 4fr))[
  #image("16.jpg", width: 100%)
][
  Installed, a district map lands as a georeferenced overlay, in the right
  place on the ground, over whatever base map is showing.

  #v(6pt)
  *Show* and *Hide* work here too -- a district map is a GeoPDF like any
  incident map.
]
]

#tak-slide[
= Incident maps

The maps posted for a going fire, from the two archives the incident community
publishes to. Where every other page is a catalog that changes with a release,
these change hourly: what is on the page is what the fire's GIS shop uploaded
this morning.

#v(6pt)
- *Incident Maps (NIFC)* -- `ftp.wildfire.gov`. Ops, division, air operations,
  transport, briefing and IR products, for every fire in the country.
- *Drone IR Maps (UASWFC)* -- `uaswfc.org`. Infrared flown by uncrewed
  aircraft, as georeferenced PDFs and KMZs.

#v(6pt)
Both are public and need no account.
]

#tak-slide[
#toolbox.side-by-side(columns: (5fr, 7fr))[
  #image("20.png", width: 100%)
][
  Pick your geographic area once and it is remembered. You can change it
  whenever you like -- the next fire may be somewhere else.
]
]

#tak-slide[
#toolbox.side-by-side(columns: (5fr, 7fr))[
  #image("25.png", width: 100%)
][
  From there you walk down to your fire and into whatever folder that shop
  uses. They do not all agree, so the plugin shows what is actually there
  rather than a fixed set of screens.

  #v(6pt)
  Folders named by date are listed newest first, because the map you want is
  almost always today's.
]
]

#tak-slide[
#toolbox.side-by-side(columns: (5fr, 7fr))[
  #image("26.png", width: 100%)
][
  Only maps are offered: PDFs and KMZs. Geodatabases, shapefile bundles and
  flight logs are not maps and are left out.

  #v(6pt)
  The line under the buttons says how many were hidden, so a short list is
  never mistaken for an empty folder.
]
]

#tak-slide[
== Names you can read

The archives name files for a GIS shop's file browser. Map Depot renames them
for a phone:

#v(6pt)
```
ops_arch_e_port_20260828_0115_RoweCreekComplex_ORPRD000491_0828day.pdf
  -> OR-PRD-ROWE-CREEK-COMPLEX-MAP-OPS-082826.pdf
```

#v(6pt)
The date is the operational period -- the shift the map is for, not the moment
it came off the plotter. Division letters, sortie numbers and IR areas are all
kept, because two maps you can both pick have to be able to sit on the device
together. The original name is shown under the new one, so a map named over the
radio can still be found.

#v(6pt)
A PDF says *MAP* and installs as a georeferenced overlay you can see through.
A KMZ says *OVERLAY* and installs as ATAK's own kind of overlay. The name tells
you which you are about to get.
]

#tak-slide[
#toolbox.side-by-side(columns: (5fr, 7fr))[
  #image("38.png", width: 100%)
][
  The same fire's drone products: two PDFs and a KMZ, named so you can tell at
  a glance which is which without reading the extension.
]
]

#tak-slide[
#toolbox.side-by-side(columns: (5fr, 7fr))[
  #image("28.png", width: 100%)
][
  Tapping *Download* says what it will cost, and what the file is called on the
  server -- so a map named over the radio can still be matched to the one you
  are about to fetch.
]
]

#tak-slide[
#toolbox.side-by-side(columns: (5fr, 7fr))[
  #image("29.png", width: 100%)
][
  While it runs, the row carries the progress and the others stop offering
  themselves: these are tens of megabytes and two at once on a hotspot serves
  nobody. *Cancel download* stops it and keeps nothing half-finished.
]
]

#tak-slide[
== Pinning the fire you are working

A crew assigned to a fire opens the same folder twenty times a day, and reaching
it means a region, a year folder, the fire and then a product folder -- four taps
to arrive somewhere they never leave.

#v(6pt)
Every folder has a *Pin* button. Press it on your fire and it sits at the top of
the first screen from then on, in cyan, one tap away. *Remove* on a pinned row
unpins it and deletes nothing.

]

#tak-slide[
#toolbox.side-by-side(columns: (5fr, 7fr))[
  #image("22.png", width: 100%)
][
  Press *Pin* on the fire you are working.
]
]

#tak-slide[
#toolbox.side-by-side(columns: (5fr, 7fr))[
  #image("23.png", width: 100%)
][
  It sits at the top of the first screen from then on, in cyan so it is obvious
  why it is above everything else.

  #v(6pt)
  Anything can be pinned, not only a fire: if you live in one product folder,
  pin that and you land straight in it. Pins survive a restart and a plugin
  update, and the two archives keep their own.
]
]

#tak-slide[
#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("31.png", width: 100%)
][
  == Working with what you have downloaded

  - *Show* / *Hide* turns one map on or off without leaving the page.
  - *Outlines* turns the footprints of every GRG on or off, which is how you
    see what covers the ground in front of you.
  - Tapping an installed row goes to that map.
  - *Remove* deletes it, telling ATAK first so no layer is left pointing at a
    file that is gone.
]
]

#tak-slide[
#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("42.jpg", width: 100%)
][
  A drone IR product, on the ground: the perimeter and every heat source the
  aircraft found, drawn over whatever base map you are using.
]
]

#tak-slide[
#image("34.jpg", width: 100%)

#v(4pt)
An IR map shown, with *Outlines* on.
]

#tak-slide[
#image("35.jpg", width: 100%)

#v(4pt)
The same map hidden. The footprint stays either way, so you can see what a map
covers before deciding to turn it on.
]

#tak-slide[
#toolbox.side-by-side(columns: (5fr, 7fr))[
  #image("44.png", width: 100%)
][
  The filter widens with where you are standing: at the top of an archive
  *Installed* is everything you hold, and it narrows to the folder as you walk
  down into it. *Available* is the other way round.

  #v(6pt)
  A filter that hides everything says so, and names itself, rather than leaving
  a folder looking empty when it is not.
]
]

#tak-slide[
#toolbox.side-by-side(columns: (5fr, 7fr))[
  #image("43.png", width: 100%)
][
  *Available* is what you have not got yet.
]
]

#tak-slide[
#toolbox.side-by-side(columns: (5fr, 7fr))[
  #image("33.png", width: 100%)
][
  *Installed* stays useful high up the tree: standing at the fire, a map
  downloaded from a folder two levels below is still listed, with the folder it
  came from rather than a size.
]
]

#tak-slide[
#toolbox.side-by-side(columns: (5fr, 7fr))[
  #image("45.png", width: 100%)
][
  *Remove* asks first, and deletes through ATAK rather than behind its back --
  the layer is unloaded before the file goes, so nothing is left pointing at
  something that is no longer there.
]
]

#tak-slide[
= Finding what you have installed

#toolbox.side-by-side(columns: (5fr, 7fr))[
  #image("11.png", width: 60%)
][
  Every page has a filter button, next to *Back*, that cycles *All* →
  *Installed* → *Available*. Set to *Installed*, the page becomes a list of
  what is on this device.
]
]

#tak-slide[
#toolbox.side-by-side(columns: (5fr, 7fr))[
  #image("14.png", width: 60%)
][
  An installed offline map says *tap to go there*, in green. Tapping the row
  turns that map on and takes you to it.

  Straight after a download it may say it is still being added to the map
  list -- ATAK is indexing the file, which takes a while for a large one. It
  goes there on its own when that finishes. Nothing needs tapping again.
]
]

#tak-slide[
= Removing

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("07.png", width: 92%)

  *Remove* appears on anything installed, and says what removing actually
  frees before it does it.
][
  #image("08.png", width: 92%)

  Elevation squares shared with a neighboring region you also hold are kept.
  A small state may share all of them, and the plugin says so rather than
  running a removal that frees nothing.
]
]

#tak-slide[
= Where files go

Map Depot writes into ATAK's own folders, so what it installs is
indistinguishable from data put there by hand.

#v(6pt)
#toolbox.side-by-side(columns: (7fr, 5fr))[
  - Elevation → `atak/DTED/`, one file per one-degree square.
  - Streaming base maps → `atak/imagery/`, as map source files.
  - Offline public lands → `atak/imagery/`, as vector tile packages.
  - Ranger district maps → `atak/grg/`, as georeferenced PDFs.
  - Incident and drone maps → `atak/grg/` for a PDF, `atak/overlays/` for a
    KMZ.

  #v(6pt)
  Removing something through Map Depot tells ATAK first and deletes the file
  after, so no layer is left pointing at something that is gone.
][
  #image("19.png", width: 100%)
]
]

#tak-slide[
= What it needs

- *Network* -- outbound HTTPS on port 443 only: to the map depot, to the public
  map server a chosen source belongs to, and -- for incident maps -- to
  `ftp.wildfire.gov` and `uaswfc.org`. Both are public and need no account.
  No inbound ports, and no TAK server involvement.

- *Storage* -- enough free space for what you download. Map Depot checks
  before it starts and refuses rather than filling the device.

- *Nothing else* -- no account, no key, no configuration. It does not read or
  transmit position, callsign, or any other ATAK data.
]
