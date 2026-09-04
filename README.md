ATAK Plugin — Map Depot

**Download Map Depot 1.5** (pick the one matching your ATAK-CIV version, sideload, then load it in ATAK's Plugins manager):

- **ATAK-CIV 5.6:** https://github.com/takwerx/map-depot/releases/download/v1.5/ATAK-Plugin-MapDepot-1.5--5.6.0-civ-release.apk
- **ATAK-CIV 5.7:** https://github.com/takwerx/map-depot/releases/download/v1.5/ATAK-Plugin-MapDepot-1.5--5.7.0-civ-release.apk
- **ATAK-CIV 5.8:** https://github.com/takwerx/map-depot/releases/download/v1.5/ATAK-Plugin-MapDepot-1.5--5.8.0-civ-release.apk

All releases: https://github.com/takwerx/map-depot/releases

**User guide with screenshots: [docs/USER_GUIDE.md](docs/USER_GUIDE.md)**
(https://github.com/takwerx/map-depot/blob/main/docs/USER_GUIDE.md)


_________________________________________________________________
PURPOSE AND CAPABILITIES

Map Depot downloads map data from inside ATAK. Without it, getting elevation or a
map source onto a device means a web browser, the Downloads folder, and the Import
Manager -- a sequence that is awkward on a phone and impossible with gloves on.

Six kinds of data, each installed into the directory ATAK already scans, so
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

  * Incident maps. The operational products a going fire's GIS shop posts to the
    NIFC archive -- ops, division, air operations, transport, briefing and IR --
    browsed by geographic area and fire. Filenames written for a desktop file
    browser are renamed for a phone screen, keyed to the operational period
    rather than the plot time, with the original shown underneath.

  * Drone IR maps. The same, for infrared flown by uncrewed aircraft and posted
    to the UAS Wildland Fire Consortium. Flight logs and geodatabases are left
    out; only georeferenced maps and KMZ overlays are offered.

Any folder in either archive can be pinned to the top of the list, so a crew
assigned to a fire reaches it in one tap rather than four.

The catalog is fetched at runtime and cached, so a device that has been to the
depot once still shows what it holds when there is no signal. The two incident
archives are read live, since what they hold changes through the day.

_________________________________________________________________
STATUS

Version 1.5. Verified on ATAK-CIV 5.8.0.3 (SDK build).

1.5 responds to a fault in ATAK, not in the plugin: official ATAK-CIV 5.8.0.4
does not start once a vector tile package -- an Offline Public Lands map -- is
in its layer catalog. The first import survives; every start after it dies in
ATAK's own imagery scan, before any plugin loads, with an AbstractMethodError on
TileMatrix.getName(). Measured 2026-09-03 on a Galaxy S22 Ultra, Android 14,
with a 20 MB and a 242 MB package; the SDK's own 5.8.0.3 build starts fine with
fifteen of them, so this is the official build's obfuscation. Reported to TAK
Product Center support with the stack and the reproduction.

So on an official 5.8 build, Map Depot no longer offers those downloads: the
section stays, dimmed, and says why. A phone that already has packages is one
restart from an ATAK that does not open, and on start Map Depot offers once to
move them to atak/imagery.off, which ATAK does not scan; the dialog says that it
is the only place this plugin moves a file. The gate is by release, not build
number, and lifts when tak.gov ships a fixed ATAK. Everything else -- elevation,
streaming base maps, ranger district maps, incident and drone IR maps -- is
unchanged, on every ATAK version.

_________________________________________________________________
POINT OF CONTACTS

takwerx
https://github.com/takwerx/map-depot/issues

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
  * ftp.wildfire.gov, for NIFC incident maps. Public, no account.
  * uaswfc.org, for UAS Wildland Fire Consortium drone products. Public, no
    account. Both are preferences rather than fixed addresses, and both are
    rejected if not HTTPS.

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
in grg, KMZ in overlays. After a file lands, ATAK is told: imagery through a layer scan, GRGs
through the import pipeline, because GRG discovery otherwise runs only at startup.

Removal notifies ATAK before deleting the file. The reverse order leaves a tile
reader holding a path that no longer exists.

Downloads stage outside any scanned directory and are moved into place once
complete, so ATAK never sees a partly written file and registers a broken layer.

Region, source and package identifiers arrive over the network and become both
URL components and filenames, so each is validated against a closed pattern
before use, and every written path is checked to be inside its intended
directory.
