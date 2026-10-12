# The `.wtf` project file

One JSON object in one UTF-8 file, extension **`.wtf`**, registered with the desktop shell by
the installers. It describes a print and holds no imagery, so it is a few kilobytes. It is read
into and written from the records of `WtfFile` in `wtf-compose`; the editor converts its model in
`ProjectIo`.

## The whole file

A made-up project: Czechia in full color at zoom 12, the neighbors faded and fading out
across the 25 km around its box, the outline stroked in orange, three strips of a 1067 mm roll, a
PDF and a TIFF of each strip. The fields are the ones WTF writes, in its order, the area to the last
digit; only the line breaks are for reading, since the editor puts each shape on one line.
`ProjectFileExampleTest` reads the example out of this page, round-trips it and checks that it shows
every field but the ones named below; `ProjectIoTest` opens and saves it in the editor unchanged. So
the page cannot drift from the code.

```json
{
  "format": "wtf",
  "title": "Czechia on the hall wall",
  "notes": "Three strips of the 1067 mm satin roll, 3.16 m across. Hang the middle one first.",
  "saved": "2026-09-25T08:41:12.518204Z",
  "source": {
    "provider": "mapy-com",
    "theme": "outdoor",
    "lang": "cs"
  },
  "area": {
    "kind": "rectangle",
    "source": "subject",
    "polygons": [
      [
        [ [11.742028331987024, 48.32542220625224], [11.742028331987024, 51.28457779374776],
          [19.207971668012977, 51.28457779374776], [19.207971668012977, 48.32542220625224],
          [11.742028331987024, 48.32542220625224] ]
      ]
    ]
  },
  "subject": {
    "source": "library:ne0:CZE:Czechia",
    "polygons": [
      [
        [ [12.09, 50.25], [13.02, 50.45], [14.32, 51.06], [15.04, 51.01], [16.3, 50.66],
          [16.85, 50.2], [17.72, 50.32], [18.55, 49.92], [18.86, 49.52], [17.65, 48.85],
          [16.95, 48.62], [14.97, 48.77], [14.33, 48.55], [13.83, 48.77], [12.9, 49.35],
          [12.4, 50.1], [12.09, 50.25] ]
      ]
    ]
  },
  "refine": {
    "simplify": "BOUNDING_BOX",
    "bufferValue": 25000.0,
    "fade": true
  },
  "regions": {
    "zoom": 12,
    "inside": {
      "drawn": true,
      "effect": { "saturation": 1.0, "lightness": 0.0, "contrast": 0.0 }
    },
    "outside": {
      "drawn": true,
      "effect": { "saturation": 0.0, "lightness": 0.35, "contrast": -0.45 }
    },
    "boundaryStroke": {
      "drawn": true,
      "widthMm": 1.2,
      "color": "#D9480F"
    }
  },
  "print": {
    "sizeMode": "FIT_WIDTH",
    "widthMm": 3151.0,
    "backgroundColor": "#FFFFFF",
    "setAside": {
      "sizeMode": "FIT_WIDTH",
      "widthMm": 3000.0
    },
    "exactArea": true
  },
  "medium": {
    "kind": "ROLL",
    "rollWidthMm": 1067.0,
    "stripAxis": "VERTICAL",
    "overlapMm": 20.0,
    "edgeMm": 5.0,
    "sheetsAcross": 3
  },
  "export": {
    "formats": ["PDF", "TIFF"],
    "grouping": "PER_SHEET",
    "outputDir": "D:\\Prints\\Czechia",
    "baseName": "czechia-wall",
    "missingTiles": "REFUSE",
    "credit": true,
    "scale": "BOTH"
  }
}
```

A field with nothing to say is left out, never written as `null`. So the example has no
`refine.custom` (the area is worked out from the subject, not grown from a Custom shape) and no
`subject.kind`, and names no sheet: `sheetWidthMm`, `sheetHeightMm` and `orientation` belong to a
medium of `"kind": "SHEET"`, in place of `rollWidthMm` and `stripAxis`, and `sheetsDown` is the
other count to `sheetsAcross`.

## The fields

| Field | What it holds |
| --- | --- |
| `format` | Always `"wtf"`. |
| `title`, `notes` | The project's name, which Save proposes as the file's name, and free text. Both optional. |
| `saved` | When the file was written, an ISO-8601 instant in UTC. Nothing reads it back. |
| `source` | The provider and theme by id, as `providers.json` names them, and the map language – `cs`, `de` – for a provider that takes one. |
| `area` | What the print covers: `polygons`, and two notes nothing reads back – `kind`, `"rectangle"` or `"polygon"`, and `source`, `"subject"` or `"custom"`, what it was worked out from. Absent while a project has no area yet. |
| `subject` | The outline the two regions are cut along: `polygons`, and `source` – a library entry, `"library:ne0:CZE:Czechia"`, a file name, or `"drawn"`. |
| `refine` | What the area grows from – the fit `simplify` (`NONE`, `BOUNDING_BOX`, `CONVEX_HULL`) of the subject, or a `custom` shape, `polygons` with a `source` as the subject's – and, beside either, the growth (`bufferValue`, in meters) and `fade`, `true` when the map fades out across the growth from the shape it grows from to the area's edge, left out otherwise. Empty when the area stands as given. |
| `regions` | `zoom`, the print's, and `scale` 2 when it is made of that zoom's `@2x` tiles, left out otherwise. `inside` and `outside`: each `drawn` and an `effect`. `boundaryStroke`: the outline's `widthMm`, `color` and whether it is `drawn`. |
| `print` | `sizeMode` (`FIT_WIDTH`, `FIT_HEIGHT`, `FIT_INSIDE`, `FILL_AND_CROP`) with `widthMm` and `heightMm` as the mode needs them – only the width for `FIT_WIDTH`, only the height for `FIT_HEIGHT`; `backgroundColor`; `setAside`, whose lengths follow the same rule; `dpi`, a size given as a DPI; `mapScale`, a size given as a scale, 50000 for 1:50 000; and `exactArea`, `true` when a rectangular area is printed to its edge and the spare paper stays paper, left out otherwise. |
| `medium` | `kind` `ROLL` with `rollWidthMm` and `stripAxis` (`VERTICAL`, `HORIZONTAL`), or `SHEET` with `sheetWidthMm`, `sheetHeightMm` and `orientation` (`PORTRAIT`, `LANDSCAPE`); `overlapMm` between neighbors; `edgeMm`, the printer's white edge, which the outermost sheets keep free round the print (D41); `sheetsAcross` or `sheetsDown`. |
| `export` | `formats` (`PDF`, `PNG`, `TIFF`), `grouping` (`PER_SHEET`, `SINGLE_DOCUMENT`), `outputDir`, `baseName` (the print's name, for its files and, in the studio, each sheet's label), `missingTiles` (`REFUSE`, `TRANSPARENT`, `PLACEHOLDER`) `credit`, whether the provider's credit is printed in the bottom-right corner, and `scale`, how the map scale is printed in the bottom-left one (`RATIO`, `BAR`, `BOTH`), left out when it is not. |

## Rules

**The area is authoritative, and `refine` is a note** (D21). `area` is what the print covers, and
every reader prints it as it stands. `refine` records how the editor arrived at it – the subject
fitted by `simplify`, or the `custom` shape, then grown by `bufferValue` – so that the editor can
work it out again and check on reopening that it still would, and **no reader ever applies it to
the area**, which is already its result. The one field of it a reader acts on is `fade`: the fade starts
at the shape the area grows from, which the area does not say, so a reader works it out again – the
subject's fit, or the Custom shape's parts made one – and leaves the area as it stands (D42).

**One of the two, or neither.** A `refine` with both `simplify` and `custom` is refused as it is
read, by every reader; either of them needs `bufferValue`. With neither, nothing was worked out and
the area stands as given: the editor opens it as a Custom area of that shape, grown by nothing.

**A Custom area is any shape.** Its `custom` shape is built of parts as the subject is – a library
country, an imported outline, rectangles and polygons drawn – kept as parts in the file, and the
area is their union grown, so it may be any polygonal shape, as a derived one may.

**A file that does not reproduce its own area is refused** by the editor, within one part in ten
thousand of the shape's diagonal (about half a millimeter on a five-meter print). The message names
the line to delete to open it as it stands: without `simplify` or `custom` the area is a Custom area
of the stored shape, grown by nothing and printed exactly. The command line warns instead and prints
what the file states: its job is to print what the file says, and only the editor rewrites one.

**The print's size is authoritative too, and a count of sheets, a DPI or a scale is a note.** `print` – `sizeMode` and
the two lengths – is the size every reader prints. When the size was given as a number of sheets,
`print` holds what the sheets come to, `medium.sheetsAcross` or `medium.sheetsDown` records the
count, and `print.setAside` keeps the size in millimeters the size step held, so going back to
millimeters finds it. A roll counts in strips – `sheetsAcross` when they are vertical,
`sheetsDown` when horizontal. The example's three vertical 1067 mm strips overlapping by 20 mm, with
the 5 mm edge kept free at either end, come to 3 × 1067 − 2 × 20 − 2 × 5 = 3151 mm, and the 3000 mm
it was sized to before is set aside. A size given as a DPI is the same: `print` is `FIT_WIDTH` of
what the area comes to at `regions.zoom`, one tile pixel a dot, and `print.dpi` records it; a size
given as a scale is `FIT_WIDTH` of what the area comes to at it, true at the area's middle latitude,
and `print.mapScale` records it. Only the editor looks at these notes; a size given in millimeters
has none. The editor checks on open that the count, the DPI or the scale still comes to the stated
size and refuses a file where it does not, naming the line to delete; the command line prints the
stated size.

**Two shapes, two roles.** `area` is what the print covers; `subject` is the cut line, stored
exactly as given, because nothing may move it. Both, and a `refine.custom`, are `polygons` **four
levels deep** – polygons,
each an array of rings, the first the shell and the rest holes, each ring an array of `[lon, lat]`
pairs in degrees – which is what a country with islands and enclaves needs, and easy to get wrong by
one level ([compose-module.md](compose-module.md) §2). A ring may be left open; the reader closes
it. `subject` is optional; without it there is one region, and the editor writes `regions.outside`
as a copy of `regions.inside`. `source` and `kind` are notes nothing reads back.

**A project is saved at any stage.** One with no area yet leaves `area` out, and `subject` too when
there is none. The editor opens it as it was left, with a `refine.simplify` waiting for the subject
picked next, or – with neither that nor `refine.custom` – as a Custom area waiting for its shape;
the command line refuses it, having nothing to print.

**One zoom for the whole print.** `regions.zoom` is the zoom of every tile on both sides of the
outline; what tells the two regions apart is their treatment. It is one the map has – the theme's
`minZoom` to `maxZoom`, else the provider's, and its `@2x` tiles are of the same zooms – or the
project is refused by name when it becomes a job, before a tile is listed: the command line exits
with it, and the editor says it where the layout and the export would be.

**The two regions have identical schemas**, so fading the surround and fading the subject are the
same file with the values swapped. `drawn: false` blanks a region. An `effect` is three numbers:
`saturation` (1 leaves color alone, 0 is gray), `lightness` (0 leaves it alone, +1 is white, −1
black) and `contrast` (0 leaves it alone, −1 is flat mid-gray). Without a `subject`,
`regions.outside` is ignored.

**`regions.boundaryStroke` is the subject's outline**: its color, and the line the print draws
along it, `widthMm` wide, when `drawn` is true. The editor writes it whenever there is a subject,
drawn or not, because the color is the subject's on the map as well.

**Everything derivable is absent.** No DPI or map scale worked out, no sheet rectangles, no tile list: all of
it comes out of `Composer.layout()`, and storing it would store something that can disagree with the
rest. A size given in sheets, as a DPI or as a scale is a note checked against the size.
This is why reopening a project reproduces its export byte for byte.

**No credentials.** API keys are stored as
[packaging-and-updates.md](packaging-and-updates.md) §6 says, never in a project. A `.wtf` is a
thing you can mail to somebody.

**No absolute paths that matter.** `export.outputDir` is a convenience and may be missing or stale:
an export creates the folder when it has gone, and a file without one exports where the editor last
exported to, else to `Documents\WTF`, or, from the command line, to `out` beside the project or
wherever `--out` says. `subject.source` may name an imported file, as a note. Nothing else is a
path.

**Lengths on paper are millimeters, places are degrees.** Every field ending in `Mm` is millimeters
of the finished print, every coordinate a longitude and latitude in degrees. The one length on the
ground, `refine.bufferValue`, is meters. No inches, however the interface is set: the
display unit is a user preference, not a document property.

**No format version.** The file is whatever this build writes, and a file it did not write is not
something it tries to understand. `"format": "wtf"` is checked, so a stray JSON file – or World of
Warcraft's `config.wtf` – is refused as not a project. A field this build does not know is skipped.
Versioning comes with the first file anybody else has to keep.

**What every project has is required.** A zoom, a region's treatment, the overlap, whether the credit
is printed: a file that leaves one out is refused by name, never read as zero or off – a zoom of
zero is the whole world in one tile. A ring of fewer than three points is refused by name too,
before JTS sees it, and so is a shape that leaves the world or crosses the antimeridian, by the
editor and the command line alike ([compose-module.md](compose-module.md) §2). Every refusal of the
file is a `Refusal` with a `ComposeReason`, which the editor says from its bundle; the editor's own
checks throw its `ProjectRefusal`. What has a meaningful default may be left out: the map language
(the provider's first listed, or English, D40), the background (white), the way a strip runs (down)
or a sheet is turned (portrait), and a missing tile (refuse).

**A provider that is not installed is not a refusal.** The editor opens the project on the first
provider it offers – one the person has hidden only when all are – or the provider's first theme,
and says which it used instead of what; the command line, with nothing to fall back to, refuses and
names the providers it knows.

## Reading and writing

`jackson-databind`, binding the records in `WtfFile` by their parameter names, in `wtf-compose`
because the command line reads these files too. Fields come in the order the records declare them,
two spaces to a level, so `git diff` of a `.wtf` is readable; Jackson's printer puts every array on
one line, though, so a moved vertex shows as the whole shape changed.

Writing goes into `<name>.part`, forced to the disk, and replaces the file only then, so a crash, a
full disk or a power cut leaves the old project rather than part of the new one.

The studio's recovery copy of unsaved work is a `.wtf` like any other, written the same way into
`~/.wtf/recovery`, never beside the project ([studio-module.md](studio-module.md) §5).

## What is deliberately not in the file

| Not stored | Where it lives instead | Why |
| --- | --- | --- |
| API keys | See [packaging-and-updates.md](packaging-and-updates.md) §6 | Sharing a project must not share a key. |
| The tile cache location | Settings | Machine-specific. |
| Window size and position, where the map was looking | `Preferences`, through the studio's `Prefs` | Per machine, per monitor. A project opens framed on its shapes. |
| Provider and theme *definitions* | `providers.json` | The project names a provider by id; an unknown one is replaced on open, as above. |
| The marks for putting the sheets together | `Preferences`, set in the Export step | They describe the person printing and how they join sheets. The command line prints none. |
| Rows of tiles a PNG or TIFF holds in memory | The engine, from the sheet's width ([compose-module.md](compose-module.md) §7); the command line's `--band-tiles` | The exporting machine's memory, not the print: the file comes out the same either way. |
| Computed layout, DPI, tile lists | Nowhere | Derived. |
