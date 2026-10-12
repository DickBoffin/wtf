# The tile providers WTF ships

What is in `providers.json` and why. The mechanics – templates, auth kinds, the cache, formats – are
in [tiles-module.md](tiles-module.md); this is the catalog. A card marked **verified** comes from the
provider's own documentation or a fetched tile, **unverified** from neither; of the keyed providers,
only Mapy.com has been fetched. Terms are summarized, never interpreted.

---

## 1. What ships, and why

WTF prints map wallpaper, so a provider is chosen for **how its map looks enlarged to five meters**
and **whether it has imagery**, the other half of what goes on a wall. The best map of a place is
often its national agency's (§5), so the national services that answer without a key ship too.

"Bulk" is `bulkDownload`: **allowed**, or **viewing only** – at most 256 missing tiles a job
([tiles-module.md](tiles-module.md) §1).

| Provider (id) | Why it ships | Imagery | Key | Bulk | Terms, as far as read |
| --- | --- | --- | --- | --- | --- |
| **Mapy.com** (`mapy-com`) | The primary: its tourist map and aerial coverage of Czechia to z20. | `aerial` | Yes | Allowed | Read: no restriction on use; attribution with the logo required; requests past the free allowance billed. |
| **MapTiler** (`maptiler`) | Good-looking raster styles, worldwide satellite, a generous free tier. | `satellite` | Yes | **Viewing only** | Read 2026-10-12: bulk tile download and export outside the service forbidden; only a temporary personal cache; prints internal, A4 at 300 DPI, on paid plans. Free plan non-commercial, 100,000 requests a month. |
| **Thunderforest** (`thunderforest`) | *Outdoors* and *Landscape*, among the best rendered maps at large sizes. | No | Yes | **Viewing only** | Read 2026-10-12: bulk downloading and pre-caching forbidden below the Small Business plan; on-device caching allowed; tiles CC BY-SA 4.0, printed with a text credit. Free: 150,000 tiles a month. |
| **Mapbox** (`mapbox`) | Worldwide satellite at high zoom. | `mapbox.satellite` | Yes | **Viewing only** | Read 2026-10-12: no systematic download; a device cache of 30 days at most; print or PDF only under bought print rights, Streets only – never satellite. Free: 750,000 raster tiles a month. |
| **Stadia Maps** (`stadia`) | Perpetual free tier, an EU endpoint, the Stamen styles. | No | Yes | **Viewing only** | Read 2026-10-12: bulk downloading forbidden; a client cache only as long as the HTTP headers say, 7 days without; print allowed if strictly non-commercial. Free: 200,000 credits a month, non-commercial. |
| **Geoapify** (`geoapify`) | A free tier counted in tiles, not map loads. | No | Yes | Allowed | Read 2026-10-12: an open tile policy – tiles may be cached, stored and redistributed with attribution. Free: 3,000 credits a day, a tile is 0.25 credits, commercial use allowed. |
| **Swisstopo** (`swisstopo`) | The Swiss national map and SWISSIMAGE. | `swissimage` | No | Allowed | Partly read: free to use; the documentation separates local use from deployment and mentions a WMTS account for production. |
| **IGN Géoplateforme** (`ign-fr`) | Plan IGN and the French orthophotos. | `ortho` | No | Allowed | Licence Ouverte (Etalab) unless a dataset says otherwise; not read in detail. |
| **PDOK** (`pdok-nl`) | The Dutch BRT background maps. | No | No | Allowed | CC BY 4.0, crediting the Kadaster; not read in detail. |
| **Kartverket** (`kartverket`) | Norway's topographic map. | No | No | Allowed | CC BY 4.0, crediting Kartverket; not read in detail. |
| **basemap.de** (`basemap-de`) | The German federal base map. | No | No | Allowed | CC BY 4.0 or the Data License Germany, crediting GeoBasis-DE / BKG; not read in detail. |
| **USGS The National Map** (`usgs`) | US Topo, imagery and hydrography. | `USGSImageryOnly` | No | Allowed | US federal work, public domain. |
| **GSI** (`gsi-jp`) | Japan's standard and pale maps and aerial photos. | `seamlessphoto` | No | Allowed | Public Data License 1.0, source cited; the Japanese text counts; not read in detail. |
| **Esri World Imagery** (`esri`) | Worldwide imagery with no key. | `World_Imagery` | No | **Viewing only** | Read, and unclear (§3). |
| **OpenStreetMap standard** (`osm`) | A map with no account at all. | No | No | **Viewing only** | The tile usage policy rules out bulk downloading. |
| **OpenTopoMap** (`opentopomap`) | Contour-and-relief topography, unusually good printed large. | No | No | **Viewing only** | CC BY-SA; community-run, welcomes use that does not strain the server. |

### The rule

**This is the policy, and the other sections point here.** A provider ships when its definition
loads, its template has been fetched or taken from the provider's documentation, it names its
source in `attribution`, it uses https and it says where a key comes from – the last three are what
`ProviderCatalogTest` checks of every shipped entry. Reading the terms in full is not a condition;
the table says where they have not been.

`bulkDownload` is the one decision taken from the terms. It is `false` – viewing only – when:

- the provider's policy rules out bulk downloading (OpenStreetMap);
- it runs on donated or community capacity (OpenTopoMap);
- its terms have been read and leave the question open (Esri).

Otherwise it is `true`: a commercial API under the user's own key and plan, or a national open-data
service published for use without registration. What the terms allow beyond downloading – printing,
showing the print – is for the user to check against the terms link the Providers dialog and the
About window show. The three viewing-only providers are there so that a first run shows a map, and
the shape tools work, before anybody signs up for anything.

### Coverage

```json
"coverage": { "west": 5.9, "south": 45.8, "east": 10.6, "north": 47.9 }
```

**Absent means global** – nothing is assumed of a provider nobody has written a box for. The seven
national providers declare one; the nine global ones do not. Two things ask:

- **The splash** fetches a first world from the first provider in catalog order that is global,
  needs no key and serves zoom 2. `providers.json` lists OpenStreetMap before Esri, so the first
  world is OpenStreetMap's (D37) without code naming a provider.
- **The key test** in the Providers dialog fetches a tile at the middle of the declared coverage,
  or over Paris for a global provider.

A box, not an outline: the question is roughly where a provider is useful, and exact borders would
bring a geometry library into the tile module for one lookup. The shape of a print uses the outline
library.

## 2. How the dialog uses this

`signupUrl`, `signupHint` and `termsUrl` exist for the Providers dialog
([studio-module.md](studio-module.md) §5), which shows beside the key field the hint, a **Get a
key...** link, a **Terms** link and a **Test** button that fetches one tile and says what came back –
format and size, or why nothing did. So a 403 leads to a page the user can act on. The hints are in
`providers.json`: two or three sentences on where to sign up, where the key appears and what the
free tier gives, since that is the number a user decides on.

## 3. The cards

What is particular to each provider; everything else is in its `providers.json` entry.

| Provider | |
| --- | --- |
| **Mapy.com** – verified | Key in the header `X-Mapy-Api-Key`. **`cs` and `en` are offered** of the fourteen languages the server takes: by its OpenAPI description the language changes only tiles at zoom 6 and below, and there the fourteen come back as two tiles, `cs` (shared by `sk`) and `en` (the rest), so more would only cache the same tiles under other names; `cs`, the server's default, is first (D40). Rendered themes PNG to z19; **`aerial` JPEG**, z20 over Czechia, z18–19 over AT/PL/SI/SK/FR/CH, **z13 elsewhere** – one ceiling per theme, so outside Czechia the deeper zooms are offered and empty. **`256@2x` for `basic` and `outdoor`**, probed: 512 px of the same ground; the other three answer it with 404 and have templates without `{scale}`. |
| **MapTiler** – partly verified | Rendered styles under `/maps/{id}/256/` as PNG – without `256` the server sends 512 px tiles drawn a zoom deeper, whose names are half the size the Detail step says; `@2x` from its documentation, satellite without. Satellite under `/tiles/satellite/` as JPEG: a theme overrides the provider's `urlTemplate`. Style ids unverified, as its `quotaNote` says. Free: 100 000 requests a month. |
| **Thunderforest** – template verified | Query `apikey`. `@2x` for every style, from its documentation. Six styles ship; the list and `maxZoom` 22 are unverified. |
| **Mapbox** – verified | The v4 raster endpoint, `mapbox.satellite` only (rendered styles are another API). `@2x` from its documentation, which for imagery is about the photo of a zoom deeper. **Always JPEG**, whatever the extension asks. Documented to z30; the imagery stops lower, and the definition says 22. |
| **Stadia Maps** – verified | Header `Authorization: Stadia-Auth <key>`, keeping the key out of logs. `@2x` for every style, from its documentation. The EU endpoint `tiles-eu.stadiamaps.com`. **Watercolor stops at z16**, a real limit on a large wall. |
| **Geoapify** – verified | Query `apiKey`; five of 21 styles; `@2x` for each, from its documentation. 3 000 credits a day at 0.25 a tile: about 12 000 tiles daily. |
| **OpenStreetMap** – verified | Viewing only. The policy asks for a real `User-Agent` with a contact, which every request sends ([tiles-module.md](tiles-module.md) §6). |
| **OpenTopoMap** – unverified | Viewing only; subdomains `a`, `b`, `c`; `maxZoom` 17 unverified. |
| **Esri World Imagery** – researched | `{z}/{y}/{x}`. Viewing only: Esri is moving open access to a token-based `ibasemaps-api.arcgis.com`, and what the legacy endpoint permits is not to be guessed. |
| **Swisstopo** | RESTful WMTS, JPEG for both themes. |
| **IGN** | WMTS by query parameters; Plan IGN is RGB PNG, the orthophotos JPEG. |
| **PDOK**, **basemap.de** | RGBA PNG whose alpha is 255 everywhere; basemap.de `{z}/{y}/{x}`. |
| **Kartverket** | `{z}/{y}/{x}`; RGBA PNG with real transparency, most likely sea and beyond its coverage, which is why a print has a background color. |
| **USGS** | ArcGIS REST, `{z}/{y}/{x}`, JPEG even for the topographic map; `maxZoom` 16. |
| **GSI** | Plain XYZ; `std` PNG, `seamlessphoto` JPEG. |

## 4. National and regional providers – probed

The national providers, Esri, OpenStreetMap and OpenTopoMap were probed with one tile each at a real
place, sixteen themes in all, and every one answered 200 without a key; the formats they sent are
counted in [compose-module.md](compose-module.md) §6. Four URL shapes – plain XYZ, RESTful WMTS, WMTS
by query parameters, ArcGIS REST – need no code, only a template, because tokens are named; so
`{z}/{y}/{x}` (USGS, Esri, Kartverket, basemap.de) costs nothing, and it is what §8 tests first.
National providers cover their own country and stop, so a print over a border has holes, which is
correct.

## 5. Adding your own provider

**The shipped list is a starting point, not a boundary.** `~/.wtf/providers.json` is merged over
it ([tiles-module.md](tiles-module.md) §1), so adding a provider is editing a file, no release. The
Providers dialog writes that file: **Add**, **Duplicate**, **Edit** and hide; a shipped provider is
read-only until **Make it mine...** copies it into the file under the same id, and **Restore**
removes the copy.

The best tiles for a place are often local – for Czechia, Mapy.com beats anything global – and most
countries have a national agency or regional service. Of those, Swisstopo, IGN, basemap.de (BKG),
PDOK, Kartverket, USGS and GSI ship; Lantmäteriet (Sweden), NLS Finland and Ordnance Survey (United
Kingdom) do not. Each has its own terms, key mechanism and idea of a URL, which is why a provider is
data; §8 is written for them as much as for the shipped set.

## 6. Also considered

| Provider | Why not shipped |
| --- | --- |
| Jawg, CARTO, Tracestrack, MapTiles API, Maptoolkit, SmartMaps, Nextzen, LatLng | OSM-based raster hosts with free tiers, offering nothing MapTiler, Thunderforest, Stadia and Geoapify do not. |
| Google Maps | Its terms rule this use out, and it has no raster XYZ endpoint for it. |
| HERE, TomTom, Azure Maps | Enterprise licensing and sign-up; no advantage. |
| Geofabrik, Champs-Libres, Makina Corpus, mapz.com | Paid only. |

## 7. TileJSON

Mapy.com, MapTiler, Mapbox and Stadia publish a **TileJSON** document with the authoritative
template, attribution, zoom range and bounds, e.g.
`https://api.maptiler.com/tiles/satellite/tiles.json?key={key}`. It is where to copy a template and
an attribution from (§8, step 6). WTF does not read it: a cached map must open without a network.

## 8. The checklist, per provider, before shipping (or adding) its definition

Each line is a way a definition can be wrong while looking right.

1. **Fetch one tile of a known place** – z14 over Prague – and confirm it shows that place. Catches
   `{y}/{x}` inversion and TMS-versus-XYZ y-flipping.
2. **Record the actual format** from the magic bytes, per theme, at a low and a high zoom – not
   what the extension says.
3. **Find the real `maxZoom` per theme** by asking one past it: 404, a blank tile or an upscaled
   one. All three happen; only the first is honest.
4. **Confirm the auth mechanism** by asking once without a key and recording the status, which the
   401/403 hand-off keys on and providers do not agree on.
5. **Find the `@2x` variant**, if any, and put `{scale}` where it goes: the Test must say 512 × 512
   for it, and a theme it 404s for gets a template without the token
   ([tiles-module.md](tiles-module.md) §1).
6. **Copy the attribution** from the provider's documentation or TileJSON, not from memory.
7. **Read the terms and set `bulkDownload` by the rule in §1**; say in the §1 table whether they were
   read, and what they say about printing if anything.
8. **Write the `signupHint` after signing up**, so it describes the page that exists.
