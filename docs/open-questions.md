# Decisions

Each decision with its reason. The numbers are cited from code and never reused; a number
missing from the table was withdrawn. A new question goes at the end with a recommendation.

| # | Subject | Decision |
| --- | --- | --- |
| D1 | Code split | Maven modules `wtf-tiles`, `wtf-compose`, `wtf-studio`. The engine modules build and run without JavaFX. |
| D2 | Export formats | PDF (default), TIFF per sheet, PNG per sheet. |
| D3 | Cache migration | None. One layout, `<provider>/<theme>/<z>/<x>_<y>`; moving a cache is done by hand. |
| D4 | Step UI | A toolbar and a vertical step rail; no menu bar, no modal wizard; every step always reachable. |
| D5 | May tiles be printed? | Yes, for Mapy.com: its terms restrict the request budget, not the use. Attribution is required. |
| D6 | `@2x` tiles | The same ground at 512 px, names drawn twice as sharp: the DPI of the zoom above with the labels of this one. `{scale}` in a template is where `@2x` goes and says the theme has it; the downloader proves every tile by its pixels. Chosen as a ×2 row of the Detail table, not a setting; the map browses at the usual scale, the 1:1 preview and the print use the project's. |
| D7 | What "15 mm overlap" means | The shared band: strip B repeats strip A's last 15 mm. No *usable width*; a larger overlap covers the printer's white edge at a seam (D41 for the outer edge). |
| D8 | Full bleed | The page is the whole sheet; nothing is inset. |
| D9 | "A00" | 2A0, the 2 m² sheet. The presets keep 4A0 and 2A0. |
| D12 | Mapy.com aerial | JPEG, measured. Responses carry an `ETag` and a `Last-Modified` that is the imagery's age. |
| D14 | Marks | Only where the neighboring sheet covers them (compose-module §4), plus two visible ones, each per project: the credit in the print's bottom-right corner, on by default, and the map scale in its bottom-left, off by default. No cut marks: the printer's edge is the cut guide. |
| D15 | Localization | Every user-visible string through the bundle; see below. |
| D16 | Two regions | A subject splits the print into inside and outside, each with its own treatment at the print's one zoom, divided by an exact vector edge. The seam is the subject of the print, not an artifact; a PDF clip is exact and free, and an effect on an indexed PNG is a palette change. The fade, not a coarser zoom, makes the surround read as background. |
| D17 | Where the cache lives | The platform's per-user cache directory, one configurable override, no fallback chain. |
| D18 | Carrying a cache | Copy the folder; tiles are immutable files, so merging is safe. The tile count Settings → Tile cache shows on both machines checks it; `wtf-tiles stats` is the developer's check. |
| D19 | Exporting an incomplete cache | Allowed, never the default, never silent: `REFUSE`, `TRANSPARENT`, `PLACEHOLDER` (red, branded a proof). A tile that does not exist is not a hole, and a print of nothing but such tiles is refused. |
| D20 | The name | WTF – Web Tile Framework; projects are `*.wtf`. The clash with World of Warcraft's `config.wtf` is a file refused as not a project. |
| D21 | A project's area | The stored `area` is the printed area and every reader prints it as it is; `refine` records how the editor got there – the subject's fit or a Custom shape of any form, and the growth – and is never applied. On open the editor works the area out again and refuses the file if it differs by more than 1/10 000 of the shape's size, naming the line to delete; the CLI warns. A file that names neither stands as given, a Custom area grown by nothing. A shape is editable while its step is open, never outside it. |
| D22 | A shape across the antimeridian | Refused when read: it would be two ranges in every later stage, and no wall print needs one. A shape crosses where two neighboring points are more than 180° apart, unless both lie on ±180° or the part is a box. What WTF draws itself is split where the map draws it, so only a shape from outside is refused. |
| D23 | A tile that does not exist | A zero-byte `<x>_<y>.empty` beside the tiles: one probe answers, one tile can be forgotten. |
| D24 | Undo | In the model: `batch`, `snapshot`/`restore`, one write per gesture, a stack over them; the project is dirty when the stack's position differs from the saved one. |
| D25 | Map language in the cache | Part of the path, `<provider>/<theme>@<lang>/…`, for templates with `{lang}`. |
| D26 | A canceled export | Finished sheets stay, the partial one is deleted, no layout or attribution file, "canceled after N of M". Never a file under its final name that is not whole. Before the first sheet, every print file of the run's base name and formats already in the folder, and its layout and attribution, is renamed `.previous`, and deleted only when a run is whole. A `.previous` file is never replaced: when one of a format is there, that format's files in place are a canceled run's and are deleted – the layout and attribution likewise – so what is aside stays the last whole export. A name another project could have written is left alone when that project's layout or attribution is there. |
| D28 | `TRANSPARENT` | No fill under a missing tile; RGBA output. |
| D29 | The credit's font | Liberation Sans, bundled and subset-embedded, so that Plzeň prints as Plzeň. |
| D30 | Repository | `DickBoffin/wtf`, public, where CI runs, releases are drafted and the update check looks. The project was squashed into it for the alpha. |
| D31 | CI | Tests on Linux on every push; installers on a tag or a manual run, since macOS minutes cost ten times more. |
| D32 | Units | Inches on paper mean miles and feet on the ground, with no metric unit beside them; *Room around it* is in miles. The model, file and engine stay metric. |
| D33 | The former name in history | The squash into `DickBoffin/wtf` left it behind, author addresses included; the rule in `CLAUDE.md` covers the working tree. |
| D34 | The spare paper round a non-rectangular area | What is printed wins: the spare is split between opposite sides; for a rectangle it is more map, for an outline or hull it is paper. `TilePlan` plans against the printed shape. *Print only this area* (`print.exactArea`) makes a rectangle its own edge. |
| D35 | Outline data in git | Kept in git, with no generated date and plain decimals, so re-running the importer on the same data changes nothing. |
| D36 | Sheet numbering | Row-major: three across, two down read 1, 2, 3 along the top. |
| D37 | The default provider | OpenStreetMap, for looking. No built-in provider is key-free, global and open to bulk download; the export step refuses OSM in bulk and names an alternative. The `User-Agent` carries the version and a contact. WTF uses the tiles as the tile usage policy allows and asks nobody; a word with OSMF waits until WTF has users enough to matter. |
| D38 | Which providers ship | Every provider that loads, names its source and says where a key comes from. `bulkDownload` is what its terms decide (providers.md §1). |
| D39 | Recovery copy | One per window in `~/.wtf/recovery` while the project is unsaved: `<id>.wtf` and a held `<id>.lock` naming its file. Written 2 s after the last change (30 s at most), off the FX thread, never mid-derivation. Gone on save, undo to the save, New, Open, an example, hand-over or an answered close. A start offers each copy whose lock it can take, newest first. Saves and copies are forced to disk before they replace a file. |
| D40 | Map language nothing names | The provider's first listed language, or English (`Provider.defaultLanguage`), for the CLI and the editor alike, so a file means one print everywhere. The editor always writes the language. |
| D41 | The printer's white edge round the print | Kept free once on every cut outer side: `n` sheets cover `n × w − (n − 1) × o − 2 × edge`. It is `medium.edgeMm` in the project, since it moves the sheets; a new project takes the last one used. |
| D42 | A fade across the growth | A soft mask over the map, never a treatment of the tiles: whole at the shape the area grows from – the subject's fit, or the Custom shape made one – gone at the printed edge, `1 − d/X` between. The PDF draws the map in a transparency group under a mask, so the tiles stay copied; a raster multiplies its alpha. `refine.fade`, off by default. |
| D43 | The examples | Four `.wtf` files in the studio's resources – a hike, a poster, a wallpaper, a city – opened from the Start page as a new project: no file, nothing unsaved, nothing to undo, titled with the card's name. On swisstopo and IGN, which need no key and allow a whole print to be downloaded; no tiles bundled, no color treatment, so every tile is copied. A visit: the provider, map, language, paper and edge an example brings are not remembered as the ones last used, and New starts on those last chosen. Made by `ExampleProjectsTest`'s recipes, and made again when the format changes. |

## D15, localization

1. **Numbers** are parsed with the display locale's `NumberFormat` and written with `Locale.ROOT` –
   a `5,5` typed in Czech must parse, and a file must open on every machine. A file only a person
   reads – the layout and the attribution beside an export – is written as the window writes (7).
2. **No concatenation**: `MessageFormat` with numbered arguments.
3. **No plurals where a label does it**: "Sheets: 6", `Dlaždice: 12`. Czech has three forms that
   `MessageFormat` cannot express; ICU4J only if one becomes unavoidable.
4. **No fixed widths**: Czech runs longer than English.
5. **Units are not language**: millimeters or inches is a setting of its own.
6. The **map's language** is per provider and not tied to the interface's.
7. The engine has no locale: `ProgressText` and `EngineText` say its phrases and refusals from the
   bundle, and an unknown phrase passes through in English. The words it prints on the paper, a
   sheet label's (`Marks.sheetLine`), and those of the layout and the attribution it writes beside
   the print (`ComposeJob.words`) it is given in the job, and says in English when it is given
   none. The studio's `FileText` says the files' words from the bundle, with every number and
   length as the window writes it.
8. English is `Locale.ROOT`, since `ResourceBundle` falls back to the system's language first.
   Surefire runs every test in `en-US`; tests about Czech choose it.
