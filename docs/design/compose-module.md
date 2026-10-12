# `wtf-compose` – from a shape to print files

The engine: no JavaFX, no window. It takes a shape, a medium and a zoom, and writes files. The
studio's steps 2 to 5 are a skin over it; the CLI is the proof.

---

## 1. The pipeline

```
Area shape (WGS84)                  Subject shape (polygonal, WGS84, optional)
   │  §2  simplify → buffer            │  §2  used exactly as given
   ▼                                   ▼
fillShape + envelope                cut line
   │  §3  pin the print size           │
   ▼                                   │
PrintRect (Web Mercator unit square, size in mm)
   │  §4  lay out the medium           │
   ▼                                   │
SheetLayout (Sheets, each a PrintRect slice and a mm rectangle)
   │  §5  regions, tiles per region per sheet
   ▼◄──────────────────────────────────┘
TilePlan.Plan (per sheet, per region: side, TileRange, keys, effect)
   │  §6–§8  export
   ▼
files
```

Every stage is a record, computable without the next. The `.wtf` file holds the inputs and every
stage is worked out again from them, which is why step 3 draws sheets before step 4 picks a zoom.

---

## 2. The two shapes

| | Role | Geometry | Required |
| --- | --- | --- | --- |
| **Area** | What the print covers; its bounding box is the print rectangle. | Any polygonal geometry: worked out from the subject, or grown from a Custom shape. | To print |
| **Subject** | What the print is *about*; splits it into inside and outside (§5). | Any polygonal geometry, parts and holes included. | No |

Two roles, because one shape cannot say *five meters of wall from France to Slovakia, with Czechia
in color*. Never more than two; without a subject the model is one shape.

### The area shape

**The area is the answer, not the question.** Every reader – engine, CLI, editor – prints it as
stored; the fit and growth beside it record how the editor got there and are never reapplied (D21,
[project-file.md](project-file.md)).

A JTS polygonal geometry in WGS84 degrees; a point becomes a ±0.0001° rectangle. `Shapes.validate`
checks every shape on entry, not on export: empty, non-area, off-world and antimeridian-crossing
shapes are refused, an invalid one is repaired with `buffer(0)` and refused only if that fails.

**The antimeridian, edge by edge** (D22, `Shapes.checkExtent`). Neighbors more than 180° apart are
joined the short way, so a ring from 179° to −179.5° is an island and refused – except an edge along
±180° and a box, whose corners are a west-to-east range. What WTF draws itself has long edges split
where the map draws them (`Shapes.splitLongEdges`), so only shapes from outside are refused. A shape
cut at ±180°, as Natural Earth cuts Russia, passes part by part as one world-wide range.

The editor derives an area from a subject, or from a Custom shape with the fit `NONE`, in two
steps, in this order (`Shapes.refine`; the whole derivation is `Shapes.areaFrom`, also used to check
that a file reproduces its own area):

1. **Simplify** – `NONE`, `BOUNDING_BOX` (a wall is a rectangle), `CONVEX_HULL` (the United States
   with the Gulf of Mexico).
2. **Buffer** – in meters at the shape's center latitude, without a projection
   library:

   ```
   dLat = m / 111_320
   dLon = m / (111_320 * cos(centerLat))
   ```

   Scale x by `cos(centerLat)`, `buffer(dLat)`, scale back (`Shapes.buffer`) – exact enough for a
   print rounded to tiles. A grown rectangle is squared again; a negative buffer that empties the
   shape is refused.

The result is the **fill shape**.

**The fade across the growth** (D42). The map can be whole inside the fit (`Shapes.fitOf`, the shape
before step 2: the subject's outline, box or hull, or a Custom shape's parts made one) and fade to
nothing at the printed edge. The job carries the fit as `ComposeJob.fadeFrom`; the file says only
`refine.fade`. The share shown is `dOut / (dIn +
dOut)`, distances to the fit and to the outside of what is printed – `1 − d/X` across a straight
band, and independent of the growth's width and unit. `FadeMask` computes it once per export on one
grid over the whole print (half-millimeter cells, wider on a print that would need more than
four million of them, its own even-odd fill, exact distance
transforms), so seams agree and nothing depends on the machine.

**No tile is decoded for it.** The PDF draws the map – background and both regions – into a
transparency group and paints the group once under a luminosity soft mask (DeviceGray, the page's
part of the grid). The group is required: a soft mask applies to each object under it, and a tile at
half over a background at half shows three quarters of the map. A raster multiplies its alpha. The
subject stroke and marks go on top.

### The subject shape

Used **exactly as given** – it is the cut line. **Polygonal**, because real outlines have parts and
holes (Greece, South Africa), which JTS and a PDF clip take for free. The `buffer(0)` repair of an
invalid one is the only change it ever sees.

---

## 3. Pinning the print size

The fill shape's envelope in the Web Mercator unit square is the **print rectangle**; the subject
has no say. The size comes from `PrintSize.Mode`: `FIT_WIDTH`, `FIT_HEIGHT`, `FIT_INSIDE`,
`FILL_AND_CROP` – the last grows the slack axis to the paper's ratio. None distorts. The studio
adds three ways (`JobBuilder.printSize`): a number of sheets is `FIT_WIDTH` or `FIT_HEIGHT` of what
those sheets cover, a DPI is `FIT_WIDTH` of what the print's zoom comes to at it
(`PrintSize.atDpi`), and a scale is `FIT_WIDTH` of what the ground comes to at it
(`PrintSize.atScale`, `mapScale` the other way round), the zoom then deciding only the DPI.

```java
record PrintRect(double x0, double y0, double x1, double y1,   // unit square, y down
                 double widthMm, double heightMm) {}
```

At the print rectangle's center latitude:

```
pixels        = (x1 - x0) * 256 * scale * 2^zoom
dpi           = pixels / (widthMm / 25.4)
groundMeters  = (x1 - x0) * 2 * PI * 6378137 * cos(centerLat)
mapScale      = groundMeters / (widthMm / 1000)
```

**Any two of ground width, print width and pixel width fix the third.** One tile pixel covers
`156543.03392 · cos(lat) / 2^z` meters: at 50° north, 3.07 m at z15 and half that per zoom up. The
steps are ordered for the common case – the wall's size first, the zoom read off a table – but the
table reads the other way too. A 180 km region on a 5 m wall at z15 is 298 DPI; Czechia, 500 km, at
z14 is 414.

**File size depends only on the physical size and the DPI**; the zoom decides how much ground that
buys. A 5 × 3 m wall at 300 DPI is 2.09 Gpx, just under what one Java array can index, and at 600
DPI four times that. So no exporter may hold the whole print, or a whole strip, in memory: every
exporter streams, and the PDF exporters never rasterize.

**The DPI need not be round.** A PDF places each tile by a matrix, so 298 DPI costs nothing, and
the DPI is reported unless the size is given as one. A raster is the tile pixels exactly, with the
DPI in its metadata.

**`@2x` tiles are the exception** (`Regions.scale` 2, tiles-module §1): the zoom's names in twice
the pixels, so the DPI of the zoom above with the labels of this one, and this one's tile count.
`ComposeJob` refuses scale 2 for a map whose template has no `{scale}`; the raster is twice the
pixels each way, and the PDF places a 512 px image where a 256 px one would go.

**More DPI makes a map less readable.** One tile pixel is one printed dot, so a 12 px place name is
1.02 mm tall at 300 DPI and 2.03 mm at 150, at any zoom, against a reading floor near 1.5 mm. A
wall map wants 100–150 DPI. Hence the label height beside each zoom and the 1:1 preview
(studio-module §9).

---

## 4. Laying out the medium

### Media

`Medium` is a `Roll(widthMm, stripAxis)`, cut in one axis into strips as long as the print, or a
`CutSheet(widthMm, heightMm, orientation)`, tiled in both. The engine and the file count in
millimeters; inches, miles and feet are the studio's (D32).

Sheet presets (`Medium.PAPER_SIZES`, portrait mm): 4A0 1682 × 2378, 2A0 1189 × 1682, A0 841 × 1189,
A1 594 × 841, A2 420 × 594, A3 297 × 420, A4 210 × 297, A5 148 × 210, A6 105 × 148; and, converted
exactly from inches so a driver matches its tray, Letter 8.5 × 11, Legal 8.5 × 14, Tabloid 11 × 17,
ANSI C 17 × 22, D 22 × 34, E 34 × 44, Arch D 24 × 36, Arch E 36 × 48. Roll presets
(`Medium.ROLL_WIDTHS`): 610, 762, 914 (36 in), 915 (metric), 1067, 1118, 1270, 1524 mm. Any other
size is a custom one.

### The strip count

For a print of length `L` on sheets of length `w` with overlap `o`:

```
n = ceil((L - o) / (w - o))                covered = n*w - (n-1)*o  >=  L
```

`L = length + 2e`, the printer's white edge `e` at both ends of every cut axis (D41). A 5000 mm
wall on a 915 mm roll, 15 mm overlap, 5 mm edge: `n = ceil(4995 / 900) = 6`, and **405 mm to
spare**. The spare is spent on more map: the print rectangle grows by it, half at each end, and the
paper is trimmed against the wall. An outline, a hull or a rectangle printed exactly
(`ComposeJob.exactArea`) stays the edge, and the spare is paper, half on each side, planned without
tiles (D34). Every sheet is a whole sheet, and every overlap the one asked for.

A negative overlap is a deliberate gap; only `o >= w` is refused. A length over what `n` sheets
cover by a nanometer or less counts as covered: a print sized as `n` sheets, `n*w - (n-1)*o - 2e`,
comes out a hair over in inches, and is `n` sheets, not `n + 1`.

### The overlap, the white edge, and where the marks go

**The overlap is the shared band** (D7): strip B repeats strip A's last 15 mm. There is no *usable
width*; sheets start at the paper's edge. The printer's **white edge** is `medium.edgeMm`
(`Marks.edgeMm` in the job), 5 mm by default, a new project taking the last one used.

- **At a seam**, a larger overlap covers it: 5 mm trimmed off each strip and 20 mm overlap leave
  15 mm shared.
- **Round the print** (D41), `SheetLayout.plan` grows the print by the edge on each cut side before
  counting, so no outer map lies where the printer puts no ink; `layout.print()` is the grown
  rectangle. `n` sheets cover `n × w − (n − 1) × o − 2 × edge`.
- **Full bleed**: the page is the whole sheet, the map runs to all four edges, nothing is inset.

**The seam rule.** The sheet to the right, and the sheet below, lies on top. A sheet's left and top
white edge is cut off along the ink boundary – the printer's edge is the cut guide, so there are no
cut marks – and its right and bottom edge stays on, under the neighbor. What the neighbor hides once
both white edges are dealt with is the **hidden strip** along a sheet's right and bottom edges, from
the neighbor's edge + `e` + 1 mm to this sheet's edge − `e` − 1 mm:

```
hidden strip = overlap − 2 × edge − 2 mm
```

**Marks go only there**: the sheet's number and place, arrows to its covering sheets, and a
registration hairline, each switched in `Marks`, placed by `SeamMarks` from where the
neighbor actually is and drawn by both exporters. The studio switches the first two together and
never the hairline. The medium step warns when the strip is too narrow
for them. **The label** reads `Sheet 2 of 6 · column 2, row 1 · Čelákovicko · 2026-10-10`: which
sheet and where it goes, what the print is called, and the day it was exported (`Marks.today()`).
The engine has no language, so its words come in `Marks.sheetLine`, a pattern whose `{0}`–`{3}` it
fills in as plain integers: the studio gives them in the interface language, and a job given none –
the command line's – has `Marks.SHEET_LINE`, in English. The name is `Marks.name`: in the studio the
one step 5 gives the print, which its files are written under, or the word they fall back to when
it is blank; in a project file's job the file's title, though the command line prints no label.
**The two visible marks** are the provider's credit in the bottom-right corner of the
bottom-right sheet and, when asked for, the map scale in the bottom-left corner of the bottom-left
sheet, both 3 mm in from the white edge (D14); on one sheet narrower than 120 mm the scale goes a
line above the credit.

**The credit's logo.** Where the provider's definition names one (`Provider.logo`, Mapy.com's:
[providers.md](providers.md) §3), the logo takes the corner and the words end half their size
before it. It is as tall as they are set, 7 pt from the top of what it paints to the bottom, and
centered on their capitals (`SeamMarks.Logo`). `CreditLogo` reads the bundled SVG – filled paths of
straight lines and cubic curves, each one of PDF's own operators, in flat colors; anything else is
refused by name – and takes a fill's opacity as the color it makes over white, drawn opaque, since
a RIP flattens a page with anything transparent on it and the copied tiles under it with it. The
PDF makes the logo one form XObject a document, its paths as the file has them, drawn by the page
that carries the credit and counted in the `/ID`; a raster draws the same shapes into its pixels.
Every other credit is its words alone.

**The scale** is `Marks.scale`, a `ScaleMark`: a ratio, a bar or both, the bar first. It is the job's
print's `mapScale`, true at the center latitude. The **ratio** is `1:50 000`, the thousands apart by
a narrow no-break space, and where Mercator's scale differs by more than 1% between the print's top
and bottom edge it names the latitude it holds at in degrees and minutes, `1:2 500 000 (49°48′ N)` –
a city never does, a country does. The **bar** is ticked twice. Above, the longest 1, 2 or 5 × 10ⁿ m
that fits in 40 mm, at its ends and middle, labeled `0` and `2 km` (cm, m or km, always whole).
Below, the 1, 2 or 5 × 10ⁿ in, ft (to 2 000) or mi nearest that by ratio – `1 mi` beside `2 km`,
`500 ft` beside `200 m` – ticked the same and labeled at its end; the line runs to whichever end is
further, and the labels under it end on the credit's baseline. It stays right when a print shop
scales the sheet, which the ratio does not. Neither has words, so neither needs a
language.

### The sheet

`SheetLayout.Sheet` has its index, column and row, its millimeter rectangle in the print, and its
`slice` of the unit square – all an exporter sees, which keeps the memory rule structural.

Numbering is row-major and one-based (D36). Files: `<project>-s01.pdf`, `-s01-proof.pdf` (§5.7),
and beside them `<project>-layout.txt` – medium, regions, scale, the sheet
table, overlap and how seams hang – and `<project>-attribution.txt`, the credit and terms.

**Both are in the job's words**, `ComposeJob.words`, a `FileWords`, since the engine has no
language. `Composer` decides what goes in and lays it out – the lines and the blank lines, the `!!`
before a warning, the sheets drawn as a grid of their numbers, the hanging instructions wrapped at
78 columns – and asks the words for each line, numbers and lengths included; the provider's own
credit is copied as it is. The studio gives `FileText`, which says them in the interface language
([studio-module.md](studio-module.md) §9). A job given none – the command line's – has
`FileWords.ENGLISH`, the files as they always were: the medium, the regions and each sheet as
`describe()` and `toString()` say them in the log, numbers in `Locale.ROOT`.

---

## 5. Regions, and the tiles they need

### 5.1 The two regions

```
inside   =  fillShape  ∩  subject
outside  =  fillShape  −  subject
```

Each region has **its own treatment**, at the one zoom of the whole print, and they meet on the
subject's outline as an exact vector path. The seam is **meant to be seen**: Czechia in color, the
surround faded, reads as deliberate.

```java
record Regions(int zoom, RegionSpec inside, RegionSpec outside, @Nullable BoundaryStroke boundary) {
    record RegionSpec(TileEffect effect, boolean drawn) {}
    record BoundaryStroke(double widthMm, int rgb) {}                  // §5.5
}
```

- **One zoom**: what tells the regions apart is the treatment. A coarser surround would be a second
  table to pick from, for a saving in tiles the fade makes unnecessary.
- **Symmetric**: the same controls, defaults and code on both sides; a faded Switzerland in a
  surround in full color is the same records swapped.
- **`drawn = false`** blanks a region – a map only outside an outline is a stencil.
- **Opt-in**: no subject, or identical specs (`Regions.isUniform()`), is one plan and no clip.

The outline's resolution is print quality: a 34-point outline is a 34-sided polygon on the wall
([subject-outlines.md](subject-outlines.md)).

### 5.2 Effects

```java
record TileEffect(double saturation,   // 1 = untouched, 0 = gray
                  double lightness,    // 0 = untouched, +1 = white, -1 = black
                  double contrast) {}  // 0 = untouched, -1 = flat mid-gray
// NONE = (1, 0, 0); FADED, the preset = (0, 0.35, -0.45)
```

**The effect is one pure function `int rgb → int rgb`**, which keeps passthrough alive. Nothing
mixes neighboring pixels – no blur – since that would decode every tile, the one thing the PDF
export exists to avoid:

| Tile | Where it is applied |
| --- | --- |
| **Indexed PNG** (Mapy.com's maps, OpenStreetMap, OpenTopoMap) | The 256-entry palette: `PLTE` becomes a transformed `/Indexed` table and `IDAT` passes **byte for byte**. Exact, and 768 bytes of arithmetic a distinct tile. |
| Gray or truecolor PNG, JPEG | Lightness and contrast in `/Decode` (§5.3); otherwise the pixels. |

### 5.3 Can PDF apply the effect itself?

Partly: an image's color description lives in its dictionary, apart from the samples.

| Effect | Where it can live | Touches the stream? |
| --- | --- | --- |
| Anything, on an **indexed** image | The palette, `/ColorSpace [/Indexed /DeviceRGB N <lut>]` | **No** |
| **Lightness and contrast**, gray or RGB | The **`/Decode` array** | **No** |
| **Saturation**, gray or RGB | Nowhere | **Yes** |

`/Decode` maps each sample linearly, `Dmin + sample × (Dmax − Dmin) / (2^bpc − 1)`, and lightness
and contrast compose into one linear map:

```
contrast c  (-1 … +1):  out = 0.5 + (in - 0.5)(1 + c)
lightness l (0 … +1):   out = out(1 - l) + l          toward white
lightness l (-1 … 0):   out = out(1 + l)              toward black
compose:                out = a·in + b,  a = (1+c)(1-|l|),  b = -0.5c(1-|l|) + max(l, 0)
⇒  /Decode [b  a+b   b  a+b   b  a+b]        (DeviceRGB, repeated per channel)
```

Six numbers, no image byte touched; `/Decode` is core PDF and routine on `DCTDecode`. A RIP's
handling is unverified. `TileEffect.fitsDecode()` says whether both ends stay in 0..1 – most added
contrast does not, and the tile is decoded (§6.2). Saturation could be had with a `/Color` blend
over the region, and is not: live transparency makes large PDFs crawl in a RIP.

### 5.4 Which effects force a tile to be re-encoded

| Tile | Effect | Lives in | Bytes copied? |
| --- | --- | --- | --- |
| Indexed PNG | any color effect | `/Indexed` table | **Yes** |
| Gray or truecolor PNG | none, or lightness/contrast in 0..1 | `/Decode` | **Yes** |
| JPEG, baseline or progressive | none, or lightness/contrast in 0..1 | `/Decode` | **Yes** |
| Gray or truecolor PNG, JPEG | lightness/contrast past 0..1 | pixels | No |
| Truecolor PNG, 3-component JPEG | **saturation** | pixels | No |
| Gray PNG, 1-component JPEG | **saturation** | nothing to mix (`TileEffect.onOneChannel`) | **Yes** |

**Cropping never re-encodes**: the clip is a path over a whole XObject, and a straddling tile is
sent whole and partly painted. In the rasters every pixel is composited anyway; the palette
transform still wins, 256 entries against 65 536 pixels.

### 5.5 Making the seam explicit

`Regions.boundary` (width in mm, color, default off) strokes the subject outline over both regions
with round joins. In the PDF it is a vector, sharp at any RIP resolution.

### 5.6 Which tiles are fetched

Per region, per sheet, at the print's zoom: the slice's tile range intersected with the region
(`TilePlan.of`, JTS `PreparedGeometry` once per job).

- **Touch**: a tile the outline crosses belongs to both regions, fetched once and clipped twice,
  so no band of background runs along the outline. A tile at the print's own edge always counts.
- **A sheet places only what it shows**: `TilePlan.tilesOf` places a seam tile on a sheet only if
  its part inside the slice meets what the region draws. Side of the outline is asked of the whole
  tile.
- `drawn = false` fetches nothing.

### 5.7 Tiles that are missing, and tiles that do not exist

**A tile that does not exist is not a missing tile.**

| | What it is | What happens |
| --- | --- | --- |
| **Does not exist** | 204 or 404: sea, or past coverage (Kartverket stops at Norway). | A zero-byte `<x>_<y>.empty`, never asked again; background, silently. It is the map. |
| **Missing** | A gap: an unfinished or never-run download. | `MissingTilePolicy`. |

| `MissingTilePolicy` | | Printable? |
| --- | --- | --- |
| `REFUSE` | **Default.** Stop, counting what is missing per region. | – |
| `TRANSPARENT` | Nothing drawn, not even background on the tile's side of the subject; PNG and TIFF get alpha (D28). | **Yes** |
| `PLACEHOLDER` | A red cross and outline per missing tile. | **No** |

**A proof is a file with a hole in it**: `PLACEHOLDER` and something actually missing. Its files are
`-proof`, `ExportReport.proof()` is set, and the layout file says `THIS IS A PROOF. Do not send it
to a printer.` – in the interface language when the studio writes it – and the CLI says so too.
`Composer.inventory` reports needed, cached, empty and missing up front (`TilePlan.Inventory`), the
regions **counted separately** – 3 000 tiles at z13 and at z16 are different downloads – and under
`REFUSE` the export throws `MissingTilesException` carrying it.

**A plan with nothing real in it is refused under every policy**: every tile known not to exist
would print plain background, indistinguishable from a wrong provider or place.
`Inventory.nothingThere()` tells it from a coastal print, and `Composer.refuseNothingThere` refuses
before anything is written, as the CLI's `inventory` and `--dry-run` do after their count. It is
`net.trdlo.wtf.tiles.NothingThereException`, the downloader's own ([tiles-module.md](tiles-module.md)
§3). A plan of **no tiles at all** – neither side drawn, or only the inside of a subject that misses
the area – would print blank sheets with a credit, and is refused there too, as a `Refusal` of its
own (`ComposeReason.NO_MAP_PRINTED`): no provider is to blame for it.

---

## 6. The PDF: `PdfExporter.write` (the default)

`PdfExporter.write` writes the PDFs, `PdfTileImages` turns tiles into image
XObjects, `RasterExporter` writes TIFF and PNG (§7, §8), and `Composer.export` runs them and writes
the layout and attribution.

### 6.1 The idea, and the passthrough table

Two PDF filters are what tile servers ship: **`FlateDecode` with `/Predictor 15`** is PNG's per-row
predictor, so an `IDAT` *is* an image stream; **`DCTDecode`** is baseline JPEG, and progressive
since PDF 1.3. So **the exporter never decodes a pixel and loses nothing**. It dispatches on the
format sniffed from magic bytes (`TileFormat.sniff`). Sixteen themes probed
([providers.md](providers.md) §4):

| Format | Themes | Verbatim? |
| --- | ---: | --- |
| JPEG | 7 | **yes**, `DCTDecode` |
| PNG indexed | 2 | **yes**, Flate + predictor; palette effects free |
| PNG RGB | 2 | **yes**; effects via `/Decode` |
| **PNG RGBA** | **5** | **no** – §6.3 |

**PNG, non-interlaced** (`89 50 4E 47`):

| PNG | PDF |
| --- | --- |
| `IDAT` bytes, concatenated | the stream, **verbatim** |
| `IHDR` width, height, bit depth | `/Width`, `/Height`, `/BitsPerComponent` |
| color type 3 + `PLTE` | `/ColorSpace [/Indexed /DeviceRGB N <palette>]`, `/Colors 1` |
| color type 0 | `/DeviceGray`, `/Colors 1` |
| color type 2 | `/DeviceRGB`, `/Colors 3` |
| zlib + row filters | `/FlateDecode`, `/DecodeParms << /Predictor 15 /Colors c /BitsPerComponent bd /Columns w >>` |

**JPEG, baseline or progressive, 8-bit** (`FF D8 FF`):

| JPEG | PDF |
| --- | --- |
| the whole stream | the stream, **verbatim**, `/DCTDecode` |
| `SOF0`/`SOF2` width, height | `/Width`, `/Height`, `/BitsPerComponent 8` |
| 1 component | `/DeviceGray` |
| 3 components (YCbCr) | `/DeviceRGB`; the filter converts |
| 4 components | decoded – inversion is a guess |

JPEG goes through PDFBox's `JPEGFactory.createFromByteArray`, which does not re-encode; PNG is
hand-rolled (`PdfTileImages.flatePassthrough`, `PngTile`).

### 6.2 The fallback table

What cannot go verbatim is decoded; what no decoder reads is refused (`ComposeReason.TILE_UNREADABLE`,
naming tile and format). PDF and rasters both decode through `TileSamples.decode`, from memory,
so they refuse alike and a full temp folder cannot pose as a damaged tile.

| Condition | Action |
| --- | --- |
| PNG type 3/0/2, non-interlaced, no `tRNS`; 16-bit too | Verbatim. |
| JPEG, baseline or progressive, 8-bit, 1 or 3 components | Verbatim. |
| PNG with `tRNS` | Decoded – verbatim would need an `/SMask` from the palette. |
| **PNG type 4 or 6 (alpha)** | **Decoded, always** (§6.3). |
| PNG interlaced (Adam7) | Decoded – PDF has no Adam7. |
| PNG whose chunks `PngTile` cannot read | Decoded, or refused. |
| **Any other JPEG** (extended, lossless, hierarchical, arithmetic, 12-bit, 4 components), or one `JPEGFactory` cannot read | Decoded – verbatim it renders in viewers and fails in a RIP. The `SOF` marker decides (`PdfTileImages.Jpeg`). |
| Lightness/contrast past `/Decode`'s 0..1 | Decoded on gray and truecolor, clamped. |
| WebP | Refused: no decoder in the JDK or the build. |
| AVIF, anything else | Decoded if ImageIO can, else refused – never a blank tile. |
| Not an image | Refused, format `UNKNOWN`; the cache stores none, so it is a damaged file. |

Decoding reads the samples **as they are** (`TileSamples`), applies the effect, drops an alpha that
is opaque everywhere, and re-encodes – gray by hand as `DeviceGray` with an `/SMask`, else through
`LosslessFactory`. **Never `BufferedImage.getRGB` on one channel**: it maps linear gray to sRGB, 128
to 188 (`GrayTilesTest`).

**Every export says why it decoded.** A fallback on 30 000 tiles is twenty times slower and a file
three times the size. `PdfTileImages.report()` logs each document's counts at debug level;
`ExportReport.images` (`PdfTileImages.Tally`) sums them under four reasons a person can act on
(`PdfTileImages.Fallback`: saturation, added contrast, alpha channel, tile format), and the
summary ends *images decoded rather than copied: 15 of 26 (saturation: 11, alpha channel: 4)* – in the log,
the CLI's last line and step 5 ([studio-module.md](studio-module.md) §11.4). Not in the layout file:
it costs time and size, not the print.

### 6.3 RGBA tiles, and why the print needs a background color

PDF has no interleaved alpha, so **an RGBA PNG never goes verbatim** – five of sixteen themes. One
pass (`TileSamples.isOpaque`) splits two cases:

| Case | Seen on | Do this |
| --- | --- | --- |
| Alpha 255 everywhere | PDOK, basemap.de | Drop it, re-encode RGB. Lossless and commoner. |
| Alpha used | Kartverket `topo` | Image plus 8-bit `/SMask`. |

Real transparency is sea or the end of coverage, and over nothing prints as nothing, so
`ComposeJob.backgroundColor` (default white, `print.backgroundColor` in the file, not in the window)
is filled under the map on every sheet – inside the area's clip where it has one, so the paper
round an outline stays paper. In a raster it is the band's initial fill.

### 6.4 Mixed formats in one print

Each XObject carries its own filter and color space, so Flate and DCT tiles mix freely – the normal
case with two regions, one copied and one decoded. That is why dispatch is per tile.

### 6.5 Placing them

One file per sheet (`PER_SHEET`, default) or one document (`SINGLE_DOCUMENT`).
`pt = mm / 25.4 * 72`. **The page limit is 14 400 units each way** (5080 mm); a
larger page gets **`/UserUnit`**, the smallest whole number bringing it under, the content scaled
and a warning logged (PDF 1.6, not honored everywhere).

Per tile, `s` its size in points and `(tx, ty)` its corner, and per region one clipped pass:

```
q  s 0 0 s tx ty cm  /Tm<n> Do  Q

q  <subject path> W* n  <this region's tile placements>  Q
```

Both clips are even-odd: outside is the page with the subject as a hole, and under nonzero winding a
hole wound like its shell would print an enclave as the country around it. A non-rectangular or
exact area (`ComposeJob.clipsToArea`) clips the page first. All vector and resolution-free; the
fade (§2) is a soft mask, not a clip. The stroke follows both passes.

Outlines are projected once per job (`TilePlan.Projected`) and cut per page to the page plus a
millimeter and a stroke width – a surveyed border is 125 000 points.

**The dedup key is `(digest, effect)`**: the same bytes under two effects are two XObjects, or a
faded tile appears inside the subject.

Pre-compressed data goes in through PDFBox's internal `PDImageXObject(PDDocument, InputStream,
COSBase filter, width, height, bitsPerComponent, PDColorSpace)`, `/DecodeParms` set after; the
PDFBox version is pinned for it. `PdfRoundTripTest` checks pixels; `CentralClaimTest` checks the
page's raw stream against the tile's `IDAT` or JPEG byte for byte.

### 6.6 Deduplication

Sea, forest and one-color tiles repeat. An XObject is kept by the tile's **SHA-256** and reused – a
short hash would let two tiles share a key and one print as the other.

### 6.7 Behavior at scale

Every document keeps stream data in a scratch file:

```java
new PDDocument(IOUtils.createTempFileOnlyStreamCache())
```

What stays on the heap is about **5.8 kB per image** – about 210 MB for the 32 000 tiles of a 5 × 3 m
wall at 300 DPI, 830 MB for the 128 000 at 600. **So the per-sheet split is a memory bound** (§9,
rule 3). Whether a RIP takes such files is untested.

---

## 7. The rasters: `RasterExporter`, and the TIFF per sheet

A raster composites pixels, so a sheet is drawn in **bands** of 16 tile rows (`DEFAULT_BAND_TILES`,
4 096 pixels), fewer on a sheet wide enough that sixteen would pass 64 Mpx, at least one; the CLI's
`--band-tiles` overrides it. No project file names it: it is the machine's memory, and the file is
the same whatever it is.

- **Size**: the print's zoom, origin snapped to its pixel grid so tiles are copied.
  Over two billion pixels is refused up front.
- **Regions** under Java2D clips, as the PDF (§6.5), not antialiased (0.08 mm at 300 DPI); effects
  on the decoded pixels.
- **The rest** as the PDF: background, area clip, stroke, `SeamMarks` marks in the bundled
  Liberation Sans (D29). Outside a shaped outline, white – or transparent under `TRANSPARENT`.
- **Resolution** each way from the pixels written, so the physical size is the paper's.

**TIFF, through ImageIO, is band *or* compress**: its writer replaces pixels only uncompressed. A
sheet up to 64 Mpx is composited whole and written Deflate (or LZW); a larger one is written empty
and filled band by band (`prepareWriteEmpty` / `replacePixels`), uncompressed – the allocation is
what fails, the size is merely annoying. `XResolution`/`YResolution` carry the DPI.

**The 4 GiB limit.** Baseline TIFF offsets are 32-bit and ImageIO cannot write BigTIFF, so a sheet
whose uncompressed size exceeds 4 GiB is refused, naming it. A 915 × 3000 mm strip at 300 DPI is
1.07 GiB; a wrapped offset would open wrong. Both limits are asked of every sheet before an export
writes a file or sets one aside, the PDFs it writes first included, so a refusal leaves the folder
as it was.

---

## 8. The PNG per sheet: `PngStreamWriter`

ImageIO cannot write PNG incrementally, so `PngStreamWriter` writes the chunks itself a band at a
time, rows filtered with libpng's heuristic and deflated as they come. The DPI goes in `pHYs`
(`dpi / 0.0254` per meter); without it print software assumes 72.

Under `TRANSPARENT` (§5.7) and under a fade (§2) both rasters have alpha: a missing tile is
see-through (D28), a faded pixel keeps its share, and past the area is see-through, not white.

---

## 9. Memory and progress rules

Breaking one is a bug even if the tests pass:

1. **No allocation proportional to the whole print.**
2. **No allocation proportional to a whole sheet** in the PDF; the rasters hold one band (§7),
   except a TIFF sheet up to 64 Mpx (256 MB as RGB), composited whole to be compressed.
3. **A tile is read, used and released.** The only cross-tile state is the dedup map – digests and
   references, no pixels – **scoped to one document**, which keeps a 600 DPI export in a 1 GB heap
   (§6.7).
4. **Everything long reports progress** through `ProgressSink` and **checks `isCanceled()` between
   tiles**, and between the buffers of a PDF's save through the stream it writes to.
5. **Write to `<name>.part`, rename on success.** Nothing is ever left under its final name that is
   not whole; cancels and re-exports follow D26 (`OutputFiles`). The `.part` is written from empty,
   and goes on any failure, an `OutOfMemoryError` as much as an exception.

---

## 10. Entry points

`ComposeJob` is the whole input, the words of the files beside the print included (§4). `Composer`
runs it: `layout`, `plan` and `inventory` only count and are cheap enough for every control change;
`export` alone reads tiles, and throws `MissingTilesException`, `NothingThereException` or
`FileInUseException` (checked before any work), or returns a report with `canceled` set. A refusal
a person can act on is a `net.trdlo.wtf.tiles.Refusal` carrying a `ComposeReason`. `ComposeCli` is
§11.

---

## 11. The CLI

`ComposeCli` is a developer tool, run from a build and not installed; the invocation, `java -cp "$CP"
net.trdlo.wtf.compose.ComposeCli`, and the class path are in
[headless-and-debugging.md](headless-and-debugging.md) §3. In this section `wtf-compose` stands for
that invocation, and `wtf-tiles` for the one of `TilesCli`.

```
wtf-compose layout    project.wtf [--json]   medium, size, scale, DPI and label height, sheet table
wtf-compose inventory project.wtf [--json]   placements; needed, cached, empty, missing per region
wtf-compose export    project.wtf [--dry-run] [--missing refuse|transparent|placeholder]
wtf-compose sample    --out project.wtf --bbox W,S,E,N [--zoom Z] [--width MM] [--provider ID] [--theme ID]

Options:  --cache DIR  --providers FILE (default ~/.wtf/providers.json)  --out DIR  --band-tiles N (§7)
```

Formats, grouping and base name are the project's. `layout` lays out the sheets and lists no tile,
so it answers for a print of any size. The export reads the cache only; `wtf-tiles`
downloads. Its last line is the summary, with the decoded images when there are any (§6.2).
`--dry-run` plans and counts everything and writes nothing, in seconds – what a script runs to check
a change; it and `inventory` exit as the export would, for missing tiles or a plan with nothing
real (§5.7). A refused job prints the engine's English on stderr and nothing else; a bad option is
an exit code, never a stack trace. Lines are prefixed `[compose]`; exit codes are in
[headless-and-debugging.md](headless-and-debugging.md) §3. A project whose area no longer follows
from its subject or its Custom shape prints as stated, with a warning; the editor refuses it (D21).
