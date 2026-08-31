#import "@preview/polylux:0.4.0": *
#import "formatting.typ": *

#show: userguide.with(
   plugin-name: "Map Depot",
   plugin-version: "0.6",
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
  #image("16.png", width: 100%)
][
  Installed, a district map lands as a georeferenced overlay, in the right
  place on the ground, over whatever base map is showing.
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
