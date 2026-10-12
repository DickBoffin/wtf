# `wtf-tiles` – providers, cache, download

The module that answers one question: **give me the bytes of tile (provider, theme, z, x, y).**
No JavaFX, no sheets or prints, no image decoding. It hands out `byte[]` and `Path`.

---

## 1. The model

```
Provider          id, displayName, urlTemplate, auth, minZoom, maxZoom, expectedFormat, themes[],
                  subdomains, attribution, signupUrl, signupHint, termsUrl, bulkDownload,
                  quotaNote, coverage, languages
Provider.Theme    id, displayName; minZoom, maxZoom, expectedFormat, urlTemplate that override the
                  provider's; transparent, note
ProviderCatalog   the shipped providers.json with the user's file merged over it
ProviderStore     the user's file: add, override, hide, restore
TileKey           providerId, themeId, lang, zoom, x, y, scale  – a record, the cache key
TileRange         minX, minY, maxX, maxY                 – immutable, iterated row-major
LonLatBounds      west, south, east, north               – degrees; refuses the antimeridian
Mercator          static math, no state
TileCache         find, store, empty markers, inventory, statistics
TileDownloader    bounded-parallel fetch, retry, the viewing-only limit
ProviderLimits    what each provider allows the whole process: connections, a 429's quiet and pace
TileSource        the facade: get(TileKey) -> byte[]; CachedTileSource, cache-only or downloading
```

`TileSource` is the only type the other modules see; they never learn whether bytes came off the
disk or the network. A definition that fails a check throws a `Refusal` with a `ProviderReason`,
printed in English by the command line and translated by the studio (`EngineText`).

### Provider definitions

A provider is data, not a class: the shipped `providers.json`, and the user's
`~/.wtf/providers.json` of the same shape – `{ "providers": [ … ], "hidden": [ … ] }` – which the
Providers dialog writes. A user entry replaces the shipped one of its id whole, never field by
field; removing it restores the shipped one. A hidden id leaves the pickers but still resolves, so a
project naming it opens.

Mapy.com's shipped entry, its prose fields shortened (`ProviderCatalogTest` loads this block, the
first `json` one under this heading):

```json
{
  "id": "mapy-com",
  "displayName": "Mapy.com",
  "urlTemplate": "https://api.mapy.com/v1/maptiles/{theme}/256{scale}/{z}/{x}/{y}?lang={lang}",
  "languages": ["cs", "en"],
  "auth": { "kind": "header", "name": "X-Mapy-Api-Key" },
  "minZoom": 0,
  "maxZoom": 19,
  "expectedFormat": "png",
  "themes": [
    { "id": "basic",         "displayName": "Basic" },
    { "id": "outdoor",       "displayName": "Outdoor (tourist)" },
    { "id": "aerial",        "displayName": "Aerial",        "maxZoom": 20, "expectedFormat": "jpeg",
      "urlTemplate": "https://api.mapy.com/v1/maptiles/{theme}/256/{z}/{x}/{y}?lang={lang}" },
    { "id": "winter",        "displayName": "Winter",
      "urlTemplate": "https://api.mapy.com/v1/maptiles/{theme}/256/{z}/{x}/{y}?lang={lang}" },
    { "id": "names-overlay", "displayName": "Names overlay", "transparent": true,
      "urlTemplate": "https://api.mapy.com/v1/maptiles/{theme}/256/{z}/{x}/{y}?lang={lang}" }
  ],
  "attribution": "© Seznam.cz a.s., © OpenStreetMap contributors",
  "signupUrl": "https://developer.mapy.com/",
  "signupHint": "Create a developer account at developer.mapy.com and generate an API key.",
  "termsUrl": "https://developer.mapy.com/terms/",
  "bulkDownload": true,
  "quotaNote": "Free tier: a monthly request allowance."
}
```

`signupUrl` and `signupHint` are shown beside the key field. `coverage`, a
`{ west, south, east, north }` box, is absent for a global provider ([providers.md](providers.md) §1).

**Tokens**: `{theme}` `{z}` `{x}` `{y}` `{scale}` `{lang}` `{key}` `{s}`; an unknown one is refused
at load. `{key}` puts the key in percent-encoded, as one value: every UTF-8 byte but the letters,
digits and `- . _ ~` that RFC 3986 leaves unreserved, so a space is `%20` and an `&`, `#` or `+`
neither ends the address nor changes it, in a path or a query alike. `{s}` is picked from
`subdomains` by `(31x + y) mod n`, so a tile always goes to one host (`a` without the array).
`{lang}` makes the language part of the tile (§2); `languages` lists the codes the server takes and
the toolbar offers (none listed: any may be typed). The first is the provider's own, English where
none is listed (`Provider.defaultLanguage`, D40), used wherever nothing names one – a project
without `source.lang`, `wtf-tiles` without `--lang`, the dialogs' Tests.

| `auth.kind` | Effect |
| --- | --- |
| `none` | Nothing added. |
| `header` | Header `auth.name`, value `auth.value` with `{key}` replaced, or the bare key without a `value` (Mapy.com); Stadia's is `Authorization: Stadia-Auth {key}`. No `name`, or a `value` without `{key}`: refused at load. |
| `query` | `{key}` in the template, percent-encoded; `auth.name` is a label. A template without `{key}`: refused at load. |
| `basic` | HTTP Basic, the key entered as `user:password`. |

**The key goes only to its own origin** (scheme, host, port): the downloader follows redirects
itself and drops a `header` or `basic` credential at another origin, whatever the header is called
(the JDK client would drop only `Authorization`). A `query` key is part of the address; a `Location`
is followed as written.

**Loading.** Enums are read in either case, unknown fields ignored. Every entry passes
`ProviderCatalog.validate`, the dialog's own check, so a hand-edited file meets the form's rules –
zooms from 0 to 22, the lowest not above the highest, and no empty subdomain; one that fails or does
not parse is dropped with a logged error, and the rest load.

**Ids.** A provider id is its cache folder's name, so one Windows will make:
`[a-z0-9][a-z0-9._-]{0,63}`, no trailing dot, no device name (`con`, `nul`, `aux`, `com1` … in any
case, with any extension – `Provider.DEVICE_NAME`, which wtf-compose's `FileNames` uses too). A theme
id may have capitals (`USGSTopo`), since it goes into the address, but is compared ignoring case and
cached lower-case.

**`ProviderStore` never writes over what it could not read.** An unparsable entry is named when the
dialog opens and written back as it was by every save, until mended by hand or replaced by a
provider saved under its id. A file with nothing readable – not JSON, not an object, a bad `hidden` –
is renamed `providers.unreadable.json` (`-2`, `-3` …) and the store starts empty; one that cannot be
read from disk or moved stays, and every write is refused.

### Retina tiles

A `@2x` tile covers the ground of the 256 px tile at the same z/x/y in 512 px: the same map, its
lines and names drawn twice as sharp. One zoom deeper doubles the pixels too, but halves every name
on paper; `@2x` keeps them. **`{scale}` is where a template puts `@2x`** – empty for the usual tile,
`@2x` for a `TileKey` of scale 2 – and a theme offers `@2x` exactly when its template has the token
(`Provider.offersRetina`); a theme of the same provider without them has a template of its own
without it. A key of scale 2 for a theme without the token is refused. The `@2x` tiles of a zoom
live beside its others, in `<zz>@2x/`.

**Only the pixels prove it.** Esri answers a `@2x` address with 200 and a 256 px tile. The
downloader reads the width from the header of every `@2x` tile it gets (`TileFormat.widthOf`, no
decode); one that is not 512 px is not cached, fails, and leaves `<theme>.no-retina` in the
provider's folder, after which no `@2x` tile of the map is asked for and the Detail step offers no
×2 rows for it. Forgetting the map's empty tiles forgets the marker too. The Providers dialog's Test
asks for the probe tile at both scales and says each one's pixels.

### The shipped providers

[providers.md](providers.md) is the catalog and holds the `bulkDownload` rule (its §1). Two rules
belong to the module:

**`bulkDownload: false` is enforced by the downloader.** A job needing more than 256 tiles
(`TileDownloader.BROWSE_LIMIT`), counting only those neither cached nor known empty, is refused
before its first request with `BulkDownloadRefusedException`, and the provider is asked at most two
tiles at once (`POLITE_CONCURRENCY`) by the whole process (§3). A screenful is browsing; forty
thousand tiles is not. **Nothing overrides it** – no setting, dialog or switch – because the limit is
the provider's, not the user's to waive.

**Attribution travels with the tiles**: the About window lists each provider's attribution and
terms link, an export writes `<name>-attribution.txt` (attribution, theme, terms link), and the
print marks can put the credit on the sheets ([compose-module.md](compose-module.md)). WTF writes
the words and places no logo.

---

## 2. The cache

### Layout

```
<cacheRoot>/<providerId>/<themeId>/<zz>/<x>_<y>.<ext>
<cacheRoot>/<providerId>/<themeId>/<zz>/<x>_<y>.empty      the provider says there is no tile here
<cacheRoot>/<providerId>/<themeId>/<zz>@2x/<x>_<y>.<ext>   the same tile at 512 px
<cacheRoot>/<providerId>/<themeId>@<lang>/<zz>/…             a theme whose address takes {lang}
```

`<zz>` is the zoom in two digits, `<x>` and `<y>` zero-padded to the digits of `2^zoom − 1`. A theme
whose address takes `{lang}` has a folder per language, `outdoor@cs` beside `outdoor@en` (D25);
`Provider.key` decides which themes do.

**A tile that does not exist is not a missing tile.** A 204 or 404 – sea, past a national
provider's edge – leaves a zero-byte `.empty` marker named as the tile would be (D23), so one probe
finds a tile or its absence. Tiles are never deleted; a theme's markers can be forgotten (the
Providers dialog's **Forget empty tiles**, `wtf-tiles forget-empty`), which an address that answered
404 everywhere needs once fixed.

`<cacheRoot>` is the folder set in Settings → Tile cache, else the platform's per-user cache – two
possibilities, not a chain. Tests construct a `TileCache` on a temporary directory.

| Platform | Default |
| --- | --- |
| Windows | `%LOCALAPPDATA%\WTF\tiles` |
| macOS | `~/Library/Caches/WTF/tiles` |
| Linux | `$XDG_CACHE_HOME/wtf/tiles`, else `~/.cache/wtf/tiles` |

**Carrying it to another machine** is copying the folder: plain files with lower-case ASCII names,
immutable, so merging either way is safe. Settings → Tile cache → Open folder shows it in the file
manager; there is no export format, and a zip of compressed tiles saves nothing. Settings →
Tile cache shows the count and size of the folder it points at, on both machines, and `wtf-tiles
stats` is the developer's check.

### Tile format: detected, never assumed

Rendered maps are PNG, imagery JPEG, some providers offer WebP, and a theme may change format
between zooms: format is per tile ([compose-module.md](compose-module.md) §6 counts what the probed
providers send).

- **The bytes decide the extension** (`TileFormat.sniff`): PNG `89 50 4E 47 0D 0A 1A 0A`, JPEG
  `FF D8 FF`, WebP `52 49 46 46 … 57 45 42 50`. A disagreeing `Content-Type` is a debug line.
- **`expectedFormat`** (per provider, per theme) is a hint `wtf-tiles providers` shows; nothing else
  reads it.
- **A non-image is never cached**: an HTML page with HTTP 200 fails the tile, and `TileCache.store`
  refuses it.
- **Lookup probes** `png`, `jpg`, `jpeg`, `webp` in order; a cached tile is never fetched again, so
  none is stored twice.

WebP is cached, but the JDK cannot decode it and PDF cannot embed it, so the exporters refuse it
with a message. No probed theme sends it, so no decoder is added.

### Imagery date

**`Last-Modified` is the age of the imagery**, not of the response – which year goes on the wall.
It becomes the cached file's modification time after the rename, read back by
`TileCache.imageryDate`: no sidecar, no index. So a tile's time never means "when downloaded", and
the `.part` sweep reads only folder and `.part` times. The `ETag` is not kept.

### Reading and writing

`TileCache.find(TileKey)` returns a `Path` or nothing. **A `TileCache` holds no state but its root**
and asks the disk every time, so two of them, or two processes, agree; sparing repeated probes is
the studio image cache's job ([studio-module.md](studio-module.md) §2). `inventory` sorts a job into
cached, missing and empty, listing a zoom folder once where it is asked 64 tiles or more; a download
does so before its first request, or takes its caller's fresh inventory, and looks once more at each
missing tile before asking for it, since the 1:1 preview or the view's download may have fetched it
meanwhile – counted then as cached or empty, as the inventory would have.

**Every write goes to its own `<name>.<n>.part`** in the tile's folder and is renamed atomically, so
a file that exists is complete and two writers cannot cut each other short. At startup and before a
command-line download, `*.part` files older than an hour are deleted from the zoom folders touched
since shortly before the last sweep, whose time `.part-sweep` in the root keeps.

### Statistics

`TileCache.statistics()` counts tiles, bytes and tile range per provider, theme folder and zoom,
without markers or `.part` files. It walks the tree on every call and keeps nothing, so its callers –
the Providers dialog, Settings → Tile cache, `wtf-tiles stats` – run it off the UI thread.

---

## 3. Downloading

### Concurrency and order

- **One `HttpClient` per `TileDownloader`**: HTTP/2 where offered, 15 s to connect, 30 s a request.
  The downloader follows redirects itself (§1): at most five, none from https to http.
- **Workers** on virtual threads each take the next tile of the job, sorted **zooms ascending,
  row-major within a zoom**, so an interrupted job leaves a usable coarse map. Default **6**
  (`DEFAULT_CONCURRENCY`, Settings, `--threads`); a viewing-only provider gets 2.
- **What a provider allows belongs to the process** (`ProviderLimits`, keyed by id and address),
  shared by every downloader – view, export, 1:1 preview, splash, the dialogs' Tests: a viewing-only
  provider's two connections, and after a 429 its quiet, one request at a time and five refusals.
  The quiet passes by itself; the rest starts afresh only with a job that finds none asking and the
  quiet over, so a restart within the quiet gains nothing.
- **A job owns** its tiles, workers, counts, its 256 viewing-only tiles, and what stops only it: a
  cancel, a rejected key, a keyless provider's refusal, a failing cache. It stops between tiles and
  during a backoff or the cache lookup, lets requests in flight finish, and returns its report or
  throws with it attached; its progress is told `finished` either way.

### Failure handling

| Status | Action |
| --- | --- |
| 200 | Check the magic bytes, write `.part`, rename. Not an image: the tile fails, no retry. A failed write **stops the job** with the cache's error. |
| 204, 404 | No tile here: write the `.empty` marker, not an error; a marker that cannot be written stops the job. A job whose every tile answered this, none cached, raises `NothingThereException` – out of coverage or a wrong address – so callers pass the whole job, not its missing part. The composer raises the same for a print with nothing real ([compose-module.md](compose-module.md) §5.7). |
| 401, 403 | **Stop the job**, raise `MissingCredentialsException` with the provider id and the report; never retry. A keyed provider given no key is refused so before any request (status 0); the GUI opens the credentials. A provider needing no key had nothing to reject: `Refusal` with `DownloadReason.REJECTED_WITHOUT_KEY`, naming host and status. |
| 429 | The provider's limit: nobody asks until `Retry-After` (seconds or a date), else backoff from 1 s doubling to 60 s; then **one request in flight at a time** across jobs. At most **5 refusals**, shared across jobs and apart from the 5xx retries; a 429 to a request sent before the last one came back spends nothing. A `Retry-After` past 60 s or the fifth refusal ends asking: the remaining tiles fail, the quiet holds later jobs too, and the report says rate limited, and until when. |
| 5xx, I/O | Backoff from 1 s, doubling, at most 3 attempts; then the tile fails and the job goes on. |
| Other | The tile fails, no retry. |

A job ends with a `TileDownloader.Report`: downloaded, from cache, empty, failed, bytes, elapsed,
only the failed keys (to retry), `rateLimited` with the server's time, and `failure` – why a tile
failed: the status, 0 with the connection's message when nothing answered, and whether asking again
may help (5xx, no answer, 429) or not (any other answer). The last failure that may help is kept
over any that may not. The closing line then ends
`; rate limited until 2026-09-28T13:05:00Z, nothing more asked`.

A tile's request is made once, before anything is sent, and one that cannot be made fails the tile
**here** – `local`, status 0, not worth asking again: a template that, filled in, is no http or https
address with a host (`UNMADE_ADDRESS`, with the parser's reason), or a key the header it goes in
cannot carry (`UNMADE_KEY`); any other exception on the way is WTF's own, named by its class. The
failure and the log say it without the address, which holds a key sent in it, and without the words
of whatever refused it, which quote the address or the header.

### Quota

WTF cannot know an allowance and counts nothing; it shows the number before it is spent. The export says how many it downloads
first, and above 2 000 (fixed) it asks once more. `quotaNote` is shown where a viewing-only refusal
is explained.

---

## 4. The math

`Mercator` is static and pure: longitude and latitude to the unit square and to fractional or
integer tile coordinates and back, `tileBounds`, `range`, `groundResolution`, `tileGroundSize`,
`tileCount`; `TILE_SIZE` 256, `MAX_LATITUDE` 85.0511287798066, beyond which latitudes are clamped,
never wrapped, and no index is negative. A range's maximum edge is `ceil(t) − 1`, held at or above
the minimum – `floor` adds a row and column whenever an edge lies on a tile boundary, the ordinary
case for a shape rounded outwards.

**No JTS here**: `LonLatBounds` is four doubles, and selection against a shape lives in
`wtf-compose`. A `LonLatBounds` across the antimeridian is refused (D22).

`MercatorTest` checks the world's edges and the clamp at several zooms, round trips to 1e-9, known
tiles (the OSM wiki's example, the Brandenburg Gate at z8, places at z1–17 against an independent
implementation), tile bounds that meet without a gap, `groundResolution(50, 15)` = 3.071 m, and range
edges on tile boundaries.

---

## 5. Public API and progress

`TileSource`: `get(key)` gives the bytes, or null when the mode cannot; `findCached`, `isCached`,
`isKnownEmpty` and `inventory` never download; `download(keys, progress)` returns the report;
`canDownload()`. `CachedTileSource` implements it, `cacheOnly(cache, provider)` – a missing tile is a
placeholder, not a request – or `downloading(cache, downloader, provider, key)`.

**`ProgressSink`** is the one progress interface, here so `wtf-compose` and the GUI share it:
`phase(name, total, unit)` (`total < 0` indeterminate; `TILES`, `BYTES`, `SHEETS`, `ROWS` of a
raster), `advance(delta)` from any thread, `note(detail)`, `isCanceled()`, `finished(summary)`.
**Phases exist because long operations have several** – an export places each sheet's tiles, then
writes the file – and one number for all is a bar stuck at 95 %. `ConsoleProgress` prints a line per
phase, at most one report every five seconds, and the summary.

**`ProgressModel`** turns the stream into elapsed, rate and ETA once, so the status bar, step 5 and
the console agree; it is tested with synthetic streams. Its rules:

- **The rate is a median over the last 30 s**, so neither a slow start nor one 120 kB tile moves it.
- **No ETA before 5 s and 20 items**; "estimating…" until then.
- **Rounded to what it deserves** – "about 2 hours", "less than a minute", never `01:47:23`.
- **Hysteresis**: the shown ETA moves only by more than 5 % or half a minute, whichever is more.
- **A stall shows as a stall**: a window with nothing arriving is a rate of zero and no estimate.

---

## 6. The CLI

`wtf-tiles` has a `main` (`net.trdlo.wtf.tiles.TilesCli`), so the module runs and is tested without a
window. It is a developer tool, run from a build and not installed; the invocation, `java -cp "$CP"
net.trdlo.wtf.tiles.TilesCli`, and the class path are in
[headless-and-debugging.md](headless-and-debugging.md) §3, together with the exit codes and the
conventions for automated callers. In this section `wtf-tiles` stands for that invocation.

```
wtf-tiles providers                                          list providers and themes
wtf-tiles stats   [--provider p]                             what is in the cache
wtf-tiles count   --provider p --theme t --zoom z --bbox W,S,E,N
                                                             how many tiles, how many missing
wtf-tiles download --provider p --theme t --zoom z --bbox W,S,E,N [--key K]
wtf-tiles forget-empty --provider p --theme t                forget the tiles it said do not exist

common options: --cache DIR   --providers FILE   --threads N (default 6)
                --lang LL     (default: the first the provider lists, or en – D40)
                --json        (providers and count)
```

The catalog is the shipped one with `~/.wtf/providers.json` over it, or the `--providers` file. A
range above a million tiles is refused before its keys are made. A key comes from `--key` or
`WTF_KEY_<PROVIDER>` (`WTF_KEY_MAPY_COM`). No switch lifts the viewing-only limit.

Every request's `User-Agent` names the application, version and a contact,
`WTF/0.1.0 (+https://github.com/DickBoffin/wtf/issues)` (`TileDownloader.userAgent`; `WTF-tiles`
from the command line). Status lines start `[tiles]`. Numbers are `Locale.ROOT` in both command
lines and `ConsoleProgress`. Where the console's charset lacks the engine's en dash, ellipsis or
multiplication sign (code pages 852, 437), `ConsoleOutput`, wrapped around both streams by
`TilesCli.main` and `ComposeCli.main`, prints `-`, `...` and `x`.
