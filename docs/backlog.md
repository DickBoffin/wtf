# Backlog

What is open. A bullet becomes a numbered item when it is scheduled, numbered on from **114**,
with the label of the agent that does it (see [../CLAUDE.md](../CLAUDE.md)). A finished item or
bullet is **deleted**; the commit that does it says "Backlog item N", and git keeps the text.

## Before other people test it

- **One real run of the release workflow**, manually, once CI is green: three platforms, three
  installers and the notes on the draft; the `.wtf` association; a `0.0.<run>` build installing
  over the previous one; the Linux package named `wtf`; `check-version.sh` refusing a tag the pom
  or the change log contradicts; and whether an installer with `WtfApp` as its main class starts,
  in which case `Launcher` goes.
- **A run through the studio by hand** (`mvn -pl wtf-studio javafx:run`): pan, wheel, keys and a
  double-click after a drag; drawing a rectangle and a polygon; midpoints, right-click,
  Ctrl+right-click and Escape, then undo and redo; switching provider, theme and map language;
  New, Open, Save and Save as with the asterisk in the title, in both themes; Save, Don't save and
  Cancel before changes are lost, and before an export; Settings moving the tile cache; the 1:1
  preview's wheel, closing and reopening; the view restored after a restart; a `.wtf` double-clicked
  while a dialog is open and while a file chooser is, opened once it closes – the headless platform
  shows no chooser.

## Scheduled

Decided on 12 October 2026, walking the list above for the alpha.

127. *To be done by an Opus 5.5 agent at medium effort.*
    **The installers carry the third-party license texts.** AtlantaFX (MIT), JTS (EDL/EPL),
    Logback (EPL/LGPL), JavaFX (GPLv2 with the Classpath Exception, jlinked into the runtime),
    Jackson, PDFBox, ikonli and Font Awesome (CC BY/OFL), and the rest of what ships, want their
    notices with a binary; About names them only. `license-maven-plugin` writes
    `THIRD-PARTY.txt` from the poms at build time, the full text of each license used goes
    beside it in `licenses/`, both are copied into the app image, and About links to them. The
    build fails on a dependency whose license is unknown. *Test: the build's `THIRD-PARTY.txt`
    names every jar in `wtf-studio/target/libs`, each with a license that has its text in
    `licenses/`.*

## Open

Each is *Small* unless it says otherwise.

**Losing work or a print**

- **WTF keeps tiles longer than four providers allow.** Mapy.com forbids storing or caching tiles
  at all, Mapbox caps a device cache at 30 days, Stadia at the HTTP headers' lifetime (7 days
  without), MapTiler at a "temporary personal cache"; WTF's cache keeps every tile it browses for
  good, and can print what it browsed. A per-provider cache lifetime, or the provider dropped, if
  WTF gets users. *Medium.*
- **A tile that vanishes during an export prints as background under `REFUSE`**
  (`PdfExporter:718-731`, `RasterExporter:590-599`): `tiles.get` returning null for a tile not
  known to be empty leaves a silent hole. Ask the policy again.
- **A plan's size has no bound.** A zoom the theme allows can still list tens of millions of tiles
  in `TilePlan.of` over a continent. Refuse a plan past a count before listing it. *Medium.*
- **The raster exporters check for a cancel per band**, not per tile as compose-module §9 says;
  `writeWhole` (`:336-338`) composites up to 64 Mpx before it asks.
- **A failed rename stops a whole download** (`TileCache:255-261`, `TileDownloader:723-731`): a
  `.part` briefly locked by a virus scanner on Windows ends an hours-long job. Retry once.
- **An example's provider can become the one New starts on.** Pasting a key in the Providers
  dialog re-sets the current provider outside `Prefs.keepChoices` (`MapSource:469`, `:359`), so
  after the swisstopo example `LAST_PROVIDER` is swisstopo, against D43.
- **A shape cut at ±180° prints the world's width.** 21 library shapes (Russia, Fiji, the United
  States…) come in parts on both sides of the antimeridian and pass as one range up to 360° wide.
  Treat a shape whose widest empty stretch of longitude is not the one across ±180° as crossing it:
  refuse it as D22 says, or drop the smaller side with a word. *Medium.*
- **An override that fails the check drops the shipped provider too.** `ProviderCatalog.load`
  merges before it checks. The shipped definition should stay, with a log line.
- **`"hidden": [null]` in `providers.json` stops the catalog loading** (`Set.copyOf` throws).
  Drop nulls where the file is read.
- **A start cannot ask questions while the splash is up.** `Splash` runs `onClosed` inside its
  `AnimationTimer`, where `showAndWait` throws, and the throw skips the rest of `WtfApp.open`,
  `SingleInstance.serve` included. Post `onClosed` with `runLater`.
- **Two projects can share a file name.** `wall-s01.pdf` is the first sheet of `wall` and the whole
  document of `wall-s01`, `wall-proof.pdf` a proof of `wall` and the document of `wall-proof`:
  either export writes over the other's file, and setting aside tells them apart only by the other
  project's layout or attribution in the folder (`OutputFiles.claimedByAnother`). Refuse a base
  name ending in `-sNN` or `-proof`, as a `Refusal` the studio says from the bundle. *Medium.*
- **A project save that runs out of memory leaves its `.part`**: `WtfFile.write` (`:386`) catches
  `IOException | RuntimeException`. `OutputFiles.discardPart` on any throwable, as the exporters do.
- **A `.previous` opened while a whole export runs fails it at its end**, every file written
  (`OutputFiles.discardAside`; the check before the work found the file free), and the next export
  takes that export's files for a canceled run's: it deletes them, and keeps aside what the failed
  discard left, which is no longer whole.

**Wrong or missing words**

- **project-file.md says `regions.outside` is ignored without a subject**; `WtfFile.toRegions`
  (`:561`) passes it on and `ComposeJob` (`:100-103`) throws a plain `IllegalArgumentException`,
  which no bundle says. Ignore it, as the document says.
- **A malformed color is replaced silently** (`WtfFile.parseColor`, `:578-587`): `"red"` reads as
  white, `"#12345"` as `#012345`. Refuse it by name, as other bad values are.
- **One job's rate-limit verdict reaches another** (`TileDownloader:507-521`): a preview tile that
  arrived with 200 reports "rate limited" when an export job on the same provider was outlasted
  meanwhile, and the verdict overwrites the job's own last failure.
- **More concatenated strings**: `MediumPanel:433-435`, `ScalePreview:314` (`" × "`, `" in"`), and
  `ProviderEditor:201`'s English prompt "My Provider".
- **A studio export's sheet labels and seam marks follow a machine preference** (`marks.seams`,
  `PrintingPrefs:20`, `JobBuilder:182`), not the project, so one `.wtf` exports differently on two
  machines. Into the project, or said beside D14.
- **An entry that parses but fails `ProviderCatalog.validate`** is dropped from the catalog, kept
  by the store and not named in the Providers dialog. Name it, with the refusal through `EngineText`.
- **The Tests of the Providers dialog and the provider editor say how many tiles failed, not why**:
  `TileProbe.run` shows `ProgressText.report`, and `Report.failure` – the server's status, the
  connection's message, why no request could be made – goes unsaid. A rejected key is said in
  English, `MissingCredentialsException`'s message, since `EngineText.of` passes it through.
- **The Area step says "No subject yet"**, or "No Custom shape yet" under Custom, when the growth
  ate the area (`AreaPanel.updateResult`). Say what happened, where the growth is set.
- **A Custom shape refused while its area is worked out is called the subject shape**:
  `Shapes.partsOf` checks every shape an area grows from as `Role.SUBJECT`. Rarely reached, since a
  Custom shape is checked as the area shape on its way in.
- **"Show Details" in English** in a Czech error dialog. A `DialogPane` whose
  `createDetailsButton` is WTF's.
- **`--dry-run` passes a raster too large.** `ComposeCli` (`:157-170`) asks `refuseNothingThere`
  after its count and not the raster limits `Composer.export` refuses first, so a dry run of a TIFF
  past 4 GiB reports a plan the export then refuses. Ask `refuseTooLarge` there too.
- **The command line prints a raster export's summary twice** (`ConsoleProgress.finished`, then
  `ComposeCli.export`).
- **Hyphens for dashes in strings**: `LonLatBounds:22`, `TileDownloader:197`, `:664`, the usage and
  lines of both CLIs, the four batch-mode answers in `Dialogs`, six theme `note`s in
  `providers.json`; and in `studio.css`, the poms and `tools/`.
- **Concatenated interface strings**: `ProvidersDialog` "Maps:" and `OutlineLibrary.Entry.label()`.
  One key with placeholders each.
- **The engine's English hedges plurals** ("file(s)", "sheet(s)", "column(s)") in
  `Composer.Report`, the dry run and the command line's layout file (`EnglishFileWords`, which
  `SheetLayout.describe` shares). Labels for counts instead: "Files: 3".
- **The command line's layout file says "one region" and no more** when both sides of the subject
  are drawn alike, faded or not: `Regions.describe` names a treatment only where the two differ.
- **basemap.de's credit** should read "© GeoBasis-DE / BKG (<year checked>) CC BY 4.0".
- **Mapy.com's credit is not the notice its attribution page asks for.** The page wants "Seznam.cz
  a.s. and others" linked to https://api.mapy.com/copyright, and the logo "clickable and link to
  https://mapy.com/"; WTF prints "© Seznam.cz a.s., © OpenStreetMap contributors", and its PDF
  links neither, though a PDF is read on screens too. The page says the current text is in
  Mapy.com's TileJSON, which needs a key to read. The words, and a `/Link` annotation over each –
  the same bytes every time.
- **The scale and the credit can overlap on one sheet just wider than 120 mm.** `SeamMarks` puts
  the scale a line above the credit only on a narrower sheet, whatever the two need: a 40 mm bar
  and a ratio naming its latitude take 70 mm from the left inset, Mapy.com's credit with its logo
  67 mm from the right one, and an upright A5 sheet has 132 mm between them – Stadia's credit is
  90 mm alone. Ask their widths, not the sheet's.
- **The export's folder field shows the start of the path** and is cut where the field ends, so
  the folder's own name, at the end, is the part not seen. Show the end, the whole path as a tooltip.
- **The Paper step says the spare paper shows more map where it stays white.** `MediumPanel.spare`
  asks only whether the print is cut to the area, so a rectangle whose outside is not drawn reads
  *with more map around it* while that map is not printed.
- **providers.md says a national map stops at the border**, and swisstopo's coverage box says so
  too, but its national map goes on in full past it: probed at z15 at Aosta, Domodossola and
  Chamonix, 25 km and more out. Widen the box and say what each national map really covers.

**The studio**

- **The 1:1 preview re-centers on every edit and every step of the calibration slider**
  (`ScalePreview.apply`). Center when it opens and when the print's rectangle changes only; a new
  screen DPI keeps the point at the middle of the view where it was.
- **Seams between tiles in the 1:1 preview calibrated to 400 DPI.** Find out whether the print
  has them too, and fix whichever does.
- **The 1:1 preview re-requests a tile whose read threw**, in a loop (`TileImageCache` leaves it
  unrecorded). Record the failure until the next invalidate.
- **A capture run inherits the previous run's window and view.** A capture should neither read nor
  write the remembered ones.
- **The sheet-grid thumbnail in the Paper step** is too small to tell anything.
- **The step captures keep zoom 13 whatever the seeded area**: Greenland comes out at 28 180 DPI
  with names 0.01 mm tall, a Detail step nobody would see. Seed a zoom that suits the area.
- **No capture shows a print sized at a DPI.** `@city` is sized by sheets and `@hike` at a scale;
  a `@dpi` modifier in `CaptureSeeds` would choose the last of the sizings.
- **The "?" of *Room around it* drops to a line of its own** in English, under a label that fills
  the 96-pixel label column, as *Printer's white edge (mm)* wraps in both languages. A label column
  that grows to its words in the area step, or a shorter label.
- **The area editor could move a side by its midpoint**, as the subject's does.
- **A shape refused on its way into an empty slot** is followed by a second error dialog after an
  import, and by an uncaught exception after a library pick: `ShapeSlot` frames `target.get()`
  after `add` without asking whether anything was added.
- **An export's end does not count the cache again.** `ExportPanel.countCache` skips while a job
  runs and only a download's landing counts again, so after a canceled export the step still shows
  the count from before its download, with tiles since fetched among the missing – or none, when
  the plan changed meanwhile – until a change, a press or a return to the step counts again.

**Platforms and code**

- **CI's warnings**: `actions/upload-artifact` is pinned to a Node 20 release GitHub is retiring;
  `ubuntu-latest` moves to Ubuntu 26 on 19 October 2026; and `javac` warns of a `requires
  transitive` on an automatic module (`wtf-studio/src/main/java/module-info.java:13`).
- **A huge `Retry-After` defeats the rate limit** (`ProviderLimits:229`): `toNanos()` overflows
  past 292 years, the throw is an ordinary failure, and the next tile is asked at once. Anything
  beyond `maxWait` is outlasted before it is converted.
- **A `@2x` tile whose width cannot be read is cached as proven** (`TileDownloader:711-712` takes
  a width of −1 as a pass, and `jpegWidth` gives up on fill bytes). Refuse what is not proven.
- **Theme ids are not made canonical in a `TileKey`** (`Provider.key`, `:251`): `USGSTopo` and
  `usgstopo` are two keys for one file in every in-memory cache.
- **The engine's trigonometry is `Math`, not `StrictMath`** (`Mercator:67-78`), so a clip path, a
  fade cell or the `/ID` could differ by an ulp between machines. Measure before changing it: the
  golden files would change.
- **The FX thread reads and writes projects** (`ProjectIo.load`, `save` with its force to disk,
  `ShapeImport.read`); a slow network drive freezes the window.
- **More unused code**: `PdfTileImages.document()`, `PrintRect.pixelHeight(int)`,
  `PngStreamWriter.rowsWritten()`, `Provider.cacheDirName()`, `TileCache.unchecked`,
  `TileFormat.contentType()`, `Provider.coverageOrWorld()`, `LonLatBounds.of()`,
  `LonLatBounds.expandedBy()`, `Mercator.tileGroundSize()`, `TileImageCache.hitRate()`, `size()`,
  `bytes()` and `isMissing()`, and `ProjectModel.startNew()` without arguments – tests only, and
  the behavior D43 replaced.
- **Untested**: `redirect()` refusing https to http, and stopping after five hops.
- **macOS: a `.wtf` opened from Finder is not opened.** Try `java.awt.Desktop.setOpenFileHandler`
  beside JavaFX. *On a Mac.*
- **API keys in the macOS Keychain and the Linux Secret Service**, as one more `ApiKeys.Store`
  each through the foreign-function API, as `WindowsCredentials` does. *Medium each, on the
  platform.*
- **A store save drops fields the model lacks** (`_comment`, a misspelled key). Keep them beside the
  entry and write them back.
- **`--band-tiles 0` is accepted** as one row. Refuse a count under one.
- **Unused**: `Forms.combo`, `TileImageCache.absentCount`.
- **A provider no request can be made of loads.** `ProviderCatalog.validate` checks how a template
  starts and its tokens, not that it makes an address – a space or a `|` in it, an underscore in its
  host – nor that a header's name is one a request can carry (`X Key`, `Host`). Such a provider
  loads, and every tile fails before it is asked, said as `UNMADE_ADDRESS` or, for the header's
  name, as `UNMADE_KEY`, which blames the key. Refused at load by name, as the other checks are.
- **The command line takes a key with the spaces around it** (`--key`, `WTF_KEY_<PROVIDER>`): cmd's
  `set WTF_KEY_MAPY_COM=abc ` keeps the space, which goes out as `%20` and is refused. Trimmed where
  it is read, as the Providers dialog trims its field.
- **The download's DEBUG line names the provider's template for a map with one of its own**
  (`Provider.redactedTemplate` reads `urlTemplate`): MapTiler's satellite is logged with the
  address of its styles.

## Later

Ideas, not commitments.

- A shop's RIP accepting a PDF of tens of thousands of image XObjects – untested.
- A word with OSMF about WTF browsing its tiles (D37), once WTF has users enough to matter.
- Signed installers: SignPath Foundation for Windows, free for open source; Apple's Developer ID
  at 99 USD a year. Until then the release notes say what to click past.
- Point-of-view outlines from Natural Earth's worldview variants; a finer source than Natural Earth.
- Still English in a Czech window: provider names and credits, region kinds and names outside
  Czechia, text fields' context menu (D15).
- A simpler icon below 48 px; a document icon for `.wtf`.
- The imagery date (`TileCache.imageryDate`) in the status bar and the attribution file.
- ETags for refreshing an area; counting requests against a provider's monthly allowance.
- Per-theme zoom ceilings by region (Mapy.com aerial beyond Czechia).
- `wtf-tiles download --shape`, `probe --tilejson <url>`, `--json` on every reporting command.
- A project that remembers its view; an unknown provider offering the Providers dialog.
- A target DPI for PNG and TIFF; a custom sheet size and the background color in the window.
- The export step saying when a page passes 200 inches (`/UserUnit`).
- The coarseness check following the print; a crossing outline shown while it is drawn.
- Writing the shapes out as GeoJSON, KML or CSV; a context menu on the map; rail glyphs.
- Overlays: the tile footprint in the Area step, a count per sheet.
- The stall named ("rate limited, waiting 45 s"); writing as a determinate bar.
- The 1:1 preview: a jump to the densest part, calibration against a drawn line, DPI per screen.
- About naming the runtime; a state dump beside a capture and on a key.
- Captures without a display (Monocle); more run configurations and a `qodana.yaml`.
- The metric buffer tested at the equator and at 70°.
- Safe mode: Shift during the splash starts without the last project or view.
