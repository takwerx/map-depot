Map Depot


_________________________________________________________________
PURPOSE AND CAPABILITIES

Map Depot downloads map data from inside ATAK. Without it, getting elevation or a
map source onto a device means a web browser, the Downloads folder, and the Import
Manager -- a sequence that is awkward on a phone and impossible with gloves on.

Four kinds of data, each installed into the directory ATAK already scans, so
nothing has to be imported by hand afterwards:

  * Elevation. DTED2 by state or province. Every cell is verified against a
    SHA-256 digest before it is installed, because a truncated .dt2 reads as
    valid to ATAK and silently corrupts line of sight and viewshed. Downloads
    resume, and a region already partly present fetches only what is missing.

  * Streaming base maps. MOBAC map source XML and streaming vector tile configs
    -- satellite, street, topographic, nautical, and transparent overlays such as
    BLM roads and trails, FEMA flood hazard zones and current fire perimeters.

  * Offline public lands. US Forest Service vector basemap packages, one per
    national forest or grassland, fetched directly from ArcGIS Online.

  * Offline ranger district maps. Forest Service ranger district and forest
    visitor maps as georeferenced PDFs, which install as image overlays and draw
    over whatever base map is already in use.

The catalog is fetched at runtime and cached, so a device that has been to the
depot once still shows what it holds when there is no signal.

_________________________________________________________________
STATUS

Version 0.1. Verified on ATAK-CIV 5.8.0.3.

_________________________________________________________________
POINT OF CONTACTS

takwerx

Issues and questions: https://github.com/takwerx/map-depot/issues

_________________________________________________________________
PORTS REQUIRED

Outbound TCP 443 (HTTPS) only. The plugin opens no listening ports and accepts no
inbound connections.

Hosts contacted:

  * The depot itself, which is a preference and not a fixed address. It serves
    the catalog, the map source files and the elevation cells. It ships pointing
    at a Cloudflare R2 bucket.
  * www.arcgis.com and tiles.arcgis.com, for Forest Service basemap packages,
    which are fetched from their publisher rather than mirrored.
  * data.fs.usda.gov, for Forest Service ranger district and forest maps.

Installing a map source does not itself contact that source's server; ATAK does,
when the operator turns the layer on. Which servers those are is visible in the
source list before anything is installed.

Non-HTTPS depot addresses are rejected.

_________________________________________________________________
EQUIPMENT REQUIRED

An Android device running ATAK-CIV 5.8.0.x, and enough free storage for whatever
is downloaded. The plugin checks free space before starting and refuses rather
than filling the device.

_________________________________________________________________
EQUIPMENT SUPPORTED

No peripherals. No GPS, radio, camera or Bluetooth use.

_________________________________________________________________
COMPILATION

Standard ATAK plugin build. Set sdk.path in local.properties to an extracted
ATAK-CIV SDK, then:

    ./gradlew assembleCivRelease

The release build applies minification and ProGuard.

_________________________________________________________________
DEVELOPER NOTES

Files are placed where ATAK's own classification puts them, since the directory
decides what ATAK treats the file as: map source XML in imagery/mobile/mapsources,
streaming configs and vector tile packages at the imagery root, georeferenced PDFs
in grg. After a file lands, ATAK is told: imagery through a layer scan, GRGs
through the import pipeline, because GRG discovery otherwise runs only at startup.

Removal notifies ATAK before deleting the file. The reverse order leaves a tile
reader holding a path that no longer exists.

Downloads stage outside any scanned directory and are moved into place once
complete, so ATAK never sees a partly written file and registers a broken layer.

Region, source and package identifiers arrive over the network and become both
URL components and filenames, so each is validated against a closed pattern
before use, and every written path is checked to be inside its intended
directory.
