# `wtf-studio` – the application

JavaFX 26, AtlantaFX (Nord, light or dark), Ikonli FontAwesome 5, Java 25, one JPMS module. No
FXML – every window is built in code by the class that owns it – and no ControlsFX.

---

## 1. The window

```
┌──────────────────────────────────────────────────────────────────────────────────────────────────────┐
│ [New][Open][Save][Save as] │ [Undo][Redo] │ Provider [Mapy.com ▾] Map [Outdoor ▾] Language [Czech ▾] │
│ │ [Whole print]                                                      [Providers] [Settings] [About]  │
├──────────────┬────────────────┬──────────────────────────────────────────────────────────────────────┤
│ 0 Start      │                │                                                                      │
│ 1 Subject    │                │                                                                      │
│   Czechia, … │  the selected  │                                                                      │
│ 2 Area       │  step's panel  │                                                                      │
│   Bounding … │                │                            map canvas                                │
│ 3 Paper      │                │                                                                      │
│ 4 Detail     │                │                                                                      │
│ 5 Export     │                │                                                                      │
├──────────────┴────────────────┴──────────────────────────────────────────────────────────────────────┤
│ 14.4213 50.0875 · z15 · 3.07 m/px │ 1 200 × 800 mm, sheets: 6, 1:36 000     64% of this view cached  │
└──────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

**No menu bar.** The toolbar (`MainWindow.buildToolbar`) is one row of icon buttons:

- **New, Open, Save, Save as.** New (`ProjectModel.startNew`) starts on the machine's choices –
  the provider, map and map language last chosen, the paper and the printer's edge last used, the
  printing settings – and not the project's it replaces, so its export cannot overwrite the old
  project's prints and an example's provider and paper are not carried over (D43); it shows the
  Start page. A project saves without a subject or an area.
- **Undo, Redo** (§3).
- **Provider, Map, Language** (`MapSource`; Language only for a map lettered in one, §9).
  `MapSource` owns the window's one `TileCache`, replaced only when Settings moves the folder, and
  the catalog, asked at the time (`StepContext.provider`) so the export sees a provider edited since.
- **Show the whole print** (§2).
- **Providers and keys** (§5), **Settings** and **About** (§8).

Keys: Ctrl+N, Ctrl+O, Ctrl+S, Ctrl+Shift+S, Ctrl+Z, Ctrl+Y, Alt+0 for the Start page and Alt+1 to
Alt+5 for the steps.

**The step rail** (`StepRail`): the Start page and the five steps, each step with a summary
(*1 200 × 800 mm, sheets: 6*); the selected one's panel sits beside it. Every step is clickable,
and one whose inputs are missing says what it waits for. Nothing on the rail is stored: summaries
are asked of the panels on each model revision, and whether the export can run of
`ProjectModel.build`, cached per revision.

### The Start page

Step 0 (`StartPanel`): what WTF makes, a line on what each step decides, and four examples to
start from – a hike map, a country on a poster, a living-room wallpaper and a city on sheets. A
window holding a new project shows it: at a start that opens no project, the first or any other,
and after New. A project opened while it shows – at the start, from a recovery copy, a hand-over,
Open or an example – goes to step 1; opened over another step, the step stays.

The examples (`Example`, D43) are `.wtf` files in the studio's resources, as `ExampleProjectsTest`'s
recipes make them, and open as a new project: read and checked as a file is
(`ProjectIo.openExample`), then on no file, with nothing unsaved and nothing to undo, titled with
the card's name in the interface language. So closing an untouched example asks nothing, a
recovery copy follows the first change, and Save asks for a name, never writing over the example.
They are on swisstopo and IGN, which need no key and allow a whole print to be downloaded, and copy
every tile into the PDF, since no example changes the colors; nothing of their maps is bundled,
and the map and the export fetch it as for any project. An example is a visit: the provider, map,
language, paper and edge it brings are not remembered as last used (`Prefs.keepChoices`).

### The five steps

1. **Subject** – the one shape stated exactly, and the cut line between two regions. Never
   simplified or grown, which would move a border.
2. **Area** – **from the subject** (a rectangle around it, its outline with the bays filled in –
   the convex hull – or its outline itself) or a
   **Custom** shape of any form, grown by a distance either way and faded across the growth if
   asked. The file stores the area produced and how the editor works it out again.
3. **Paper** – the paper, then a size, a sheet count on one axis, a scale – picked from the
   usual ones or typed as `x:y` in the display locale and kept as the whole denominator nearest
   `y/x` (`ScaleText`) – or a DPI, which the zoom of step 4 turns into a size: the common ways
   first, the technical last. Whole sheets
   rarely add up to the size, and the paper they leave spare shows more map; the result line
   says so, or that it stays white where the map stops at the area's edge.
4. **Detail** – one zoom for the whole print, from the table, and each region's treatment. A map
   with `@2x` tiles has a ×2 row after each zoom: the DPI of the next zoom up with this one's
   labels and tiles; a row picks zoom and scale in one step. On a map without them the print is
   made at the usual scale and the choice kept (`ProjectModel.printScale`). A
   region's heading is the checkbox that draws it, and its treatment is enabled only while it is
   drawn. The three sliders show for *Custom* only. Until *Colors of its own* is on, the
   outside has the inside's treatment and shows no controls of its own, which open with the box;
   while only the outside is drawn it is on and cannot be turned off. A region not drawn is white on the map,
   as on the paper.
5. **Export** – one press fetches what is missing, then writes. Here too what goes on the
   finished print besides the map: the credit, and – however the print is sized – the map scale as
   a ratio, a bar or both, off in a new project. The file name is the print's name: the files are
   written under it, and each sheet's label calls the print by it – or, left blank, by the word the
   files then fall back to. The project's title, from a file or an example's card, shows nowhere;
   Save proposes it.

### The status bar

The cursor in WGS84, the tile zoom and the ground resolution (per pixel, in miles or feet with
inches, D32); the print's size, sheet count and scale from `ProjectModel.layout()`
([compose-module.md](compose-module.md) §3), or *No print yet*; the view download's progress, then
how much of the view is cached. Recomputed on every change, touching no tile.

---

## 2. The canvas

`TileCanvas` is a `Region` over three hand-painted `Canvas`es – the tiles, a copy clipped to the
subject for a separate inside treatment, and the overlays, so an effect never fades a line. Painting
is `TileLayer`'s, input `CanvasInput`'s. `Camera` is the unit-square point at the top-left and pixels per unit;
`tileZoom = round(log2(pixelsPerUnit / 256))`, so tiles are drawn scaled within [0.71, 1.41].

### The opening view

No place is built in (`MapSource.restore`, `OpeningView`): the view saved as `map.view` on close;
else the world at zoom 2 when its sixteen tiles are cached for the provider, map and language – the
splash's download, from a keyless provider that is then the one chosen; else that world anyway,
fetched as any view is. The world fills the canvas but is never stretched
by half a zoom or more, past which the canvas would round to zoom 3 and show placeholders over
cached tiles. Opening a project frames its shape.

Two traps (`TileCanvas.openOn`, `Camera.setViewSize`):

- The view is applied **after the canvas has a size** – fitting divides by it, and a window being
  built reports one pixel – and **again on every resize** until a pan, zoom or open moves the
  camera, since a window is laid out several times before it settles.
- Width and height arrive as two changes. A center asked for while a side is unsized, or the one
  held when a side was lost, is kept and applied as the sides arrive, unless a restore, pan or zoom
  intervenes – otherwise the 1:1 preview opens with the print's middle on its top edge.

### Drawing a frame

1. The visible tiles plus one of margin – drawn, not counted.
2. A decoded image in the cache is drawn.
3. A tile that does not exist (204 or 404) is background, neither counted nor marked.
4. Otherwise a decode is queued and a substitute drawn: a coarser tile up to four zooms up, or the
   four finer ones.
5. Otherwise a **placeholder**: flat, muted, a thin border, a small ring from 96 pixels. **No text
   per tile** – the status bar says how much is cached.
6. The overlays (§4).

### The image cache

`TileImageCache` is an LRU of decoded `Image`s bounded **by bytes** – 256 kB per tile whatever the
file weighs. Budget: a quarter of the maximum heap, at least 64 MB, grown to hold twice the tiles on
screen when a view needs it, up to that quarter; the 1:1 preview's starts at 48 MB and grows alike.
Constants, not settings. Evicted on the FX thread, decoded with `new Image(InputStream)` on four
decoder threads. A decode that found a tile not cached or not existing is remembered, so frames do
not ask again.

A change of provider, map, language or cache folder starts a new cache. A download or *Forget empty
tiles* drops only the remembered absences; a generation counter stops an in-flight decode from
recording its tile absent again, and decoded images stay, so nothing reverts to placeholders or
decodes twice.

### Fetch as I pan

`ViewDownload` downloads the view's missing tiles 600 ms after the view stops changing – at most
a screenful; the print's tiles are the export's (§11.3). There is no control for it: the status bar
says how much of the view is cached. `FetchAsIPan` (tested without the toolkit) keeps it from
asking for a provider lacking a key, with a key already rejected – remembered against the key, so
changing it lifts the block – and for tiles the last download could not get, until a download lands
or *Try again* is pressed. Failed tiles on screen are counted on `check-failed`, one check at a
time, stale ones dropped by generation (`FailedOnScreen`).

What stands in its way is said in a dialog, **once** (`FetchAsIPan.tell`), until a provider is
chosen – the same one included – or its key changes; *Try again* clears that too:

| Notice | Dialog | Buttons |
| --- | --- | --- |
| No key | *{provider} needs an API key* | Open settings, Close |
| Key rejected | the export's credentials dialog | Open settings, Cancel |
| Past the browsing limit | the bulk refusal | Open settings, Close |
| Tiles failed | how many, and the server's status, the connection's message, or why no request could be made (`Report.failure`) | Try again when it may help, Open settings, Close |
| The job threw | `ErrorText`; a keyless provider's refusal offers only Close | Try again, Open settings, Close |

The attempts behind a failed tile are the downloader's ([tiles-module.md](tiles-module.md) §3):
three for a server in trouble, none for an answer that will not change. Open sea says nothing. The
dialog waits for `Platform.runLater`, since JavaFX allows no `showAndWait` in the pause's handler,
and only one shows at a time. A provider that forbids bulk downloading is
served up to `TileDownloader.BROWSE_LIMIT` (256) tiles at a time.

### Interaction

| Input | Effect |
| --- | --- |
| Left drag | Pan; under the rectangle tool, draw; under the polygon tool, nothing. |
| Middle drag | Pan, always. |
| Wheel, Ctrl+wheel | Zoom a quarter level about the cursor, or the view center. |
| Double click; `+`, `-`; arrows | Zoom in about the cursor; zoom a level; pan a tenth of the view. |
| Drag a handle | Move the vertex; a rectangle's corner moves its neighbors. Written on release, with a midpoint in an edge longer than half the world, so it is not read across the antimeridian. |
| Drag an edge's midpoint (subject, Custom area) | Insert a vertex; on a rectangle, move the side. |
| Right-click a vertex (subject, Custom area) | Remove it; a ring below a triangle goes; with Ctrl, its whole part. |
| `Esc`; `Enter`; `Backspace`, `Delete` | Abandon a polygon or a drag; close the polygon; take back its last vertex. |

Handles show on the part under the pointer – inside its shell or within 24 pixels of a ring – and
only on vertices whose handles would not overlap. The map takes the focus when pressed, and the
toolbar, rail and slot buttons are `focusTraversable(false)`, so a click does not take the keys
from the map.

---

## 3. The shape tools

The subject step has one slot (`ShapeSlot`); the area step has *From the subject* (the fit) and
*Custom* (a slot), and below both *Room around it* and the fade, which apply to whichever is
chosen. *Room around it* is typed in kilometers, or miles where lengths on paper are inches; the
model and the file keep meters. A slot shows where its shape came from, its size on the ground and, above one, its parts,
with the bounds in the line's tooltip, and offers **Pick a country or region…** first, in words on
a line of its own, since most subjects come from the library; then the drawing tools as icons,
**Import…** in a word, and **Remove this shape**. Both slots **accumulate** – each arrival
is another part, unioned on the way into the engine, and for a Custom shape when the area is grown
from it.

**The area is always worked out**: the subject's fit or the Custom shape (`ProjectModel.areaFit`),
then grown (`area`), on the worker under the outline itself and under Custom. Choosing *Custom*
with no Custom shape takes the fit over as one, so nothing on the paper changes; choosing *From the
subject* drops the Custom shape in the same step, and undo brings it back.

**Editing is not a mode.** The step's own shape has handles while the step is shown and none
elsewhere; drawing is a mode entered by a button, after which the editor (`EditTool`) returns.
Under *From the subject* the editor is on the fit, keeps a box a box and shows no handles under the
hull or the outline, which follow the subject; dragging the box's corner makes the box as it is let
go the Custom shape, keeps the growth, and switches to *Custom*. Under *Custom* it is the full
editor the subject has: every vertex, midpoints and removals. The handles are on the shape the area
grows from, and the grown edge is the dashed line around it.

**Fade the room around it out** (D42), under *Room around it*: whole at the shape the area grows from, gone
at the area's edge; off by default, disabled while nothing is grown, not shown on the map. **Print
only this area** (D34), while the area is a rectangle: the rectangle is its own edge, and the spare
is paper.

| Tool | Gesture |
| --- | --- |
| **Rectangle** | Press, drag, release; axis-aligned in WGS84. Under four pixels draws nothing. |
| **Polygon** | Click per vertex; double-click, `Enter` or the first vertex closes. Self-crossings are repaired (`Shapes.validate`, `buffer(0)`) or refused. Long edges get a midpoint ([compose-module.md](compose-module.md) §2). |
| **Point** | One click; a ±0.0001° rectangle drawn as a pin. |

Point is for the subject only. `ShapeImport` reads CSV (`lon,lat`), GeoJSON and KML, every part and
hole; no Shapefile. The outline library is a searchable list of bundled boundaries
([subject-outlines.md](subject-outlines.md)).

### Undo

Over the whole project, not only the shapes. `History` is a bounded stack of a hundred snapshots,
each the project after a step, sharing every unchanged value – possible because shapes and points
are immutable.

- **Sharing goes down to the point.** A dragged vertex rebuilds only its part from unmoved points,
  and an area that is the outline itself, or a Custom shape grown, reuses that shape's: a drag on a
  125 607-point border costs about half a megabyte.
- **A step is what happens between two presses** of a mouse button or key, with everything it set
  off – the area worked out again, a box taken over as the Custom shape, a Custom shape dropped with
  its radio, the outside following the inside. Keys in one field merge until the focus moves or a
  button is released: "50" typed is one step, two spinner clicks two.
- **Unsaved is a position**: undoing back to the save makes the project clean. A change that changes
  nothing is no step, nor is anything the model derives.
- **The area and the edge it grows from come back as they were**, not worked out again; a step whose
  area was still pending has it worked out when put back.
- A focused text field keeps Ctrl+Z and Ctrl+Y for its text. Open, New and an example clear the
  stack.

---

## 4. What each step draws over the map

`ProjectOverlay` draws in screen space from world anchors, at fixed line widths and font sizes,
each line over a darker, wider casing so it reads on any map.

| Step | Overlay |
| --- | --- |
| Every step | The area dashed green; the regions' treatments on the tiles (`MapTreatments`), an unprinted region white. |
| Start, 1, 2, 3 | The subject in the boundary color, filled at 10 % (a pin when a point). |
| 1, 2 | The step's shape's handles – in step 2 on the shape the area grows from: the subject's fit only when a box, the Custom shape always – and a drawing tool's rubber band. |
| 3 Paper | The print rectangle, numbered sheets (from 44 pixels wide) at full size, overlaps as yellow bands. |
| 4, 5 | Step 3's, plus the tile grid when a tile is at least 12 pixels and the print at most 40 000 tiles; the subject's line only when the print draws it, and no fill. |

Step 3 also sets the overlap and the printer's white edge (a new project takes the last one
used), saying whether the edge leaves white at the seams, covers them or leaves room for sheet
numbers ([compose-module.md](compose-module.md) §4). A print on one sheet has no seam, so the
overlap and that line are hidden while it is one.

---

## 5. Dialogs

Settings, Providers, the provider editor and the outline picker are `StudioDialog`s (owner, title
key, resizability, buttons, `studio.css`); About and alerts get the sheet from `StudioDialog.style`.
The main window, splash and 1:1 preview go through `Windows.decorate` – icons, sheet and, but for
the splash, the last bounds unless off every screen now.

**Providers and keys** (`ProvidersDialog`): a list, and the selected provider's name, maps, key,
cache contents and *Forget empty tiles*. Keys live in the Windows Credential Manager, elsewhere in
`Preferences`, read once ([packaging-and-updates.md](packaging-and-updates.md) §6); a key is kept as
typed or pasted, without the spaces around it. Buttons add, duplicate, edit (the provider editor),
*Make it mine*, remove or restore a provider and show the providers file; a checkbox hides one from
the pickers. An unreadable entry of that file is named and kept ([tiles-module.md](tiles-module.md)
§1). Beside the key: the provider's `signupHint`, **Get a key...**, **Terms** and **Test** – the
texts are the provider's entry in `wtf-tiles`' `providers.json`; a keyless provider disables the
field and says so. A download rejected with 401 or 403 raises `Dialogs.credentialsRejected`, naming
the provider, with *Open settings...* opening this dialog on it.

**Unsaved changes.** Before New, Open, a close or a hand-over from the file manager,
`Dialogs.Unsaved` offers **Save**, **Don't save**, **Cancel**; a canceled picker or a failed write
counts as Cancel. A hand-over's question names the waiting file. Only the hand-over's claim
(`SingleInstance.Answer.begin`) is timed, so a hung WTF cannot hold the second one; once claimed,
the question and the open take as long as the person does (`HandOver`), and wait until the window
asks nothing else (`Modal`): a dialog, an alert or a file chooser open, or the FX thread still in
the event loop of one just closed. Posted work runs inside those loops, where the code that asked
would go on with its answer over the project handed over. JavaFX neither lists a chooser among its
windows nor counts its loop, so every chooser is shown through `Modal`, which counts it. Only an
offer left unclaimed, or claimed by a window that closes before it gets to it, opens the file in a
new window – the second WTF hears a timeout, or the connection close with the process; after a
Cancel or a failed open it just exits.

**The last project.** A close remembers the project's file (`project.last`), or that it had none
– after New or an example, a new project is what the next start has, on the Start page. A start
that is handed no file opens the remembered one, while it is still there, as last saved; unsaved
work is the recovery copy's.

**Before an export of unsaved changes**, `Dialogs.BeforeExport` offers **Save**, **Save as** (only
when the project has a file), **Export without saving**, **Cancel**. In batch mode
([headless-and-debugging.md](headless-and-debugging.md) §4) the two questions answer Don't save and
Export without saving.

### The recovery copy

(`Recovery`, `RecoveryWatch`; the rule is D39.) While the project is unsaved the window keeps
`~/.wtf/recovery/<id>.wtf` – what a save would write – and `<id>.lock`, held locked, naming the
project's file. Written 2 s after the last change, at most 30 s while changes continue, and never
while an area is being derived: `ProjectIo.toFile` would derive it on the FX thread, and a copy with
the old area would be refused on reading; the area's landing asks again. The FX thread only turns
the model into a file, a few milliseconds; JSON and disk are the `recovery` thread's. Both files go
on save, undo to the save, New, Open, an example, a hand-over or an answered close; a crash leaves
them.

A start takes every lock it can – the system releases a lock with its process – and once the window
is up asks about each copy, newest first (`RecoveryOffer`): **Open the copy** opens it unsaved, on
its own file, with nothing to undo, and adopts it; **Throw it away** deletes it; **Cancel** keeps it
and the rest. The question is posted, since during the splash the window opens inside an animation,
where a dialog cannot wait. A second WTF leaves the first's locked copies alone. Capture runs write
and offer none.

### Closing during a download or an export

`Closing.decide` (off the toolkit, `ClosingTest`) fixes the order: the running job first (*Stop and
close?*), then unsaved changes; only when both are answered is the job canceled and awaited
(`ExportPanel.stopAndWait`, a join on step 5's daemon worker) – an export up to one item, since a
PDF save cannot stop halfway, and the download before it at most `ViewJob.STOP_WAIT`, as the view's
below. A Cancel to either leaves the job running. The view download is stopped on every close
without a question: `ViewJob.stopAndWait` waits for the tile in hand, at most `ViewJob.STOP_WAIT`
(2 s), because the wait is on the FX thread and a request in flight ignores a cancel; a stalled
tile leaves a `.part` the next start clears.

---

## 6. Threading

1. **The FX thread paints and nothing else** – no decodes or HTTP, and no file reads but the
   project's own: opening, saving and importing a shape read and write on it. Decodes run on four
   threads; downloads and exports on their own; cache counts on `count-tiles` (step 5),
   `check-cache` and `check-failed` (the view's download); a detailed subject's area on
   `derive-area`; a recovery copy is serialized on the FX thread, as a save is, and written on
   `recovery`. Count and export threads work on copies of the area and subject (`JobBuilder`),
   because JTS fills a shape's envelope lazily, without a lock, while the map draws the model's.
2. **The layout may run on the FX thread** (`ProjectModel.layout()` → `ComposeJob.layout()`): it
   counts and does not read. If it becomes slow, that is a bug in `wtf-compose`.
3. **Long work is a `javafx.concurrent.Task`** reporting through `FxProgress`, a `ProgressSink`
   updating a bar and a line at most four times a second (§11). The window stays usable; only the
   export button is disabled, for as long as a job runs, whatever changes meanwhile. One job at a
   time – the download, then the export it starts – and a job's landing clears only itself, so
   Cancel and the close reach whichever runs. Step 5 exposes `isRunning`, `isDownloading` and
   `stopAndWait` for the close (§5).

---

## 7. Developer affordances

Capture runs (`BatchMode`, `CaptureSeeds`) are [headless-and-debugging.md](headless-and-debugging.md)
§4, the log §6. The error dialog (`ErrorText`) names the kind of failure, with details and the log
folder a click away. A refusal's headline comes from exactly two types – the engine's `Refusal`
(said by `EngineText`) and the editor's `ProjectRefusal` (from `ProjectIo`); any other exception is
unexpected, however well worded.

---

## 8. Settings and About

**Settings** (`SettingsDialog`): **Tile cache** – where (Choose..., Use the default, Open folder),
tiles and bytes held; **Display** – dark theme, interface language, lengths on paper, the last two at
the next start; **Downloading** – concurrent requests (six); **Startup** – splash, update check, what
is new after an update. The marks for putting the sheets together (`PrintingPrefs`) are set in step 5.

**About** (`AboutDialog`): version and authors, *Check for updates now*; **What is new**, the
bundled `CHANGELOG.md`, opened after an update; **Map data**, each provider's attribution and terms
from the catalog the export's `<name>-attribution.txt` also reads; **Licenses**, a table of what WTF
is built on, and links to `THIRD-PARTY.txt` and `licenses/` where an installation has them beside
its jars ([packaging-and-updates.md](packaging-and-updates.md) §1).

---

## 9. Strings, numbers and locale

Beyond CLAUDE.md and D15:

- **Parsing** (`I18n.parseNumber`): where the locale groups digits with a space, an ordinary, a
  no-break and a narrow no-break space all count. A field that does not parse is marked, writes
  nothing, and shows the model's value again on losing focus.
- **A length field writes only what is typed into it** (`LengthText`); text the code put there is
  never read back, so 210 mm shown as 8.27 in stays 210 mm.
- **The paper speaks the interface language too.** The label in each sheet's hidden strip is
  printed by the engine, which has no language: `JobBuilder` puts its words in the job
  (`export.marks.sheet`, in `Marks.sheetLine`) with the numbers left as placeholders, and the engine
  fills them in on each sheet as plain integers.
- **So do the files beside it.** The layout and the attribution are laid out by the engine, which
  asks the job's `FileWords` for every line; `JobBuilder` gives it `FileText`, which says each from
  the bundle (`export.layout.*`, `export.attribution.*`) in the window's own words – the zoom as
  the detail table names it, the sides of the subject as their controls do, the count of sheets as
  step 3 says it. Nothing but a person reads these files, so a number in them is written as the
  window writes it, not in `Locale.ROOT` – a Czech reader takes the comma of `1:25,000` for a
  decimal one – and a length in the window's unit (D32). The provider's credit stays as the provider
  gives it, and the map is named by its id. In English the files are the window's English, which
  differs from the command line's where that hedges a plural, `sheet(s)`, or writes `x` for `×`.
- **The map's language is not the interface language.** **Language** in the toolbar, shown for a map
  whose address takes `{lang}`, is saved with the project and offers the provider's `languages`; on
  choosing a provider an unlisted language gives way to the system's, English or the first listed. A
  provider listing none takes any code. A project keeps an unlisted language; one naming none opens
  in the provider's default (D40).
- **Map and language are remembered per provider** (`map.theme.<provider>`, `map.lang.<provider>`)
  and restored by `ProviderChoice` – the map by id, as far as the provider still has it. **They
  never override a project:** opening and undo write the provider before its map and language, so
  the project's land last. While a switch refills the combo boxes, `MapSource` ignores the values
  JavaFX writes on replacing items (null, then whatever is at the old index).
- **A map is chosen by id and drawn by definition.** Editing the provider in use keeps the choice,
  but `MapSource.rebuild` compares provider and map by content, so an edited address is drawn and
  fetched at once.

---

## 10. The actual-size preview

Nothing on screen says that place names will be unreadable on the wall. *See it at actual size…* in step
4 (`ScalePreview`) shows a piece of the print at physical size.

### 10.1 Why higher DPI makes a map less readable

In native mode DPI and zoom are one choice ([compose-module.md](compose-module.md) §3): a tile
pixel is a print dot, so **raising the DPI shrinks every label**. A place name is about 10–14 pixels
tall:

| Print DPI | 12 px label on paper | Readable? |
| ---: | ---: | --- |
| 600 | 0.51 mm | no |
| 300 | 1.02 mm | barely, with your nose on it |
| 200 | 1.52 mm | marginal |
| **150** | **2.03 mm** | **yes** |
| 100 | 3.05 mm | comfortable |
| 72 | 4.23 mm | large |

A map meant to be read wants **100–150 DPI**, not a print shop's 300. Label heights vary by provider
and theme, so the table is a hint – step 4's zoom table shows the height as a column – and the
preview is the answer.

### 10.2 Showing actual size

The preview opens on the print's middle at its zoom, **a millimeter of print per millimeter of
screen**: `screenPixelsPerTile = tilePrintMm * screenDpi / 25.4`. At 300 DPI a tile is 21.7 mm, 82
pixels at 96 DPI – downscaled from 256, showing exactly the paper's legibility; do not sharpen it.
It shows both regions' treatments as the map does, an unprinted region white, and the line along
the subject when the print draws it, at its width. Past what is printed it is white paper, and a
print that fades goes into that white across the growth: `PrintedPaper` draws both from the
export's own shapes, `TilePlan.printed` and `TilePlan.fade`, the fade's grid worked out off the FX
thread.

It fetches what the window's cache lacks as it shows it, under the provider's limits shared with
the other downloads ([tiles-module.md](tiles-module.md) §3), through a `FollowingDownloader` that
follows a moved tile folder. Around it: **rulers** (centimeters and millimeters, or inches and eighths), **a caption**
(zoom, DPI, label height, how much of the print is shown) and **drag to pan**, no zoom.

### 10.3 Knowing the monitor's real DPI

`Screen.getDpi()` is often wrong – Windows commonly reports 96 whatever the panel. **Calibrate**
draws a bank-card outline and a slider, the wheel adjusting a tenth of a DPI per notch; every
ISO/IEC 7810 ID-1 card is **85.60 × 53.98 mm**. The answer is `display.screenDpi`; until set, the
preview uses the primary screen's DPI, or 96 when implausible, and says which.

---

## 11. Progress

Phases, rate and ETA are `ProgressModel`'s ([tiles-module.md](tiles-module.md) §5), shared with the
command line; the window renders them through `FxProgress` (§6).

### 11.1 Where it shows

No window, no modal dialog. **The status bar** carries the view download's bar and line, with no
cancel. **The step 5 panel** carries the export and the download it starts: bar, line, cancel, and
a log of notes and reports. When nothing runs, there is no bar.

### 11.2 The line

The line under a bar is the current phase's count, rate and ETA, as `ProgressModel` publishes them.

### 11.3 Downloading

Step 5 counts the plan's inventory in the background whenever the plan changes
([compose-module.md](compose-module.md) §5.7) – *Tiles needed: 12 480. Already cached: 8 231.
Empty: 0. Missing: 4 249.* – and the button reads *Download 4 249 tiles and export*; above 2 000
tiles the press asks first ([tiles-module.md](tiles-module.md) §3). A cancel is checked between
tiles and loses only those in flight. The end is reported once (`TileDownloader.Report`), and its
failed keys are what fetch as I pan does not ask for again (§2).

### 11.4 Assembling

| Phase | Reported as |
| --- | --- |
| **Placing tiles**, per sheet | Per tile, every tile of the plan, sea and holes included. |
| **Writing the document** | Indeterminate – `PDDocument.save()` has no callback; its stream checks for a cancel per buffer, stopping within 8 kB. |
| **Rasterizing**, per sheet | Per band of rows. |

Each phase names its sheet or file. At the end the line says what was written – files, sheets,
tiles, megabytes, time – and, when any PDF tile was decoded rather than copied
([compose-module.md](compose-module.md) §6), a second line from the bundle says how many and why:
*Images decoded rather than copied: 15 of 26 (color: 11, alpha channel: 4).* The log keeps both.
