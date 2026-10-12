# Documentation

The design of **WTF**, which turns web map tiles into print-ready files for very large maps, cut
into the strips or sheets a printer can feed. How to work on the code is in
[../CLAUDE.md](../CLAUDE.md).

| Document | What it answers |
| --- | --- |
| [backlog.md](backlog.md) | What is open. |
| [open-questions.md](open-questions.md) | The decisions, D1 onward, each with its reason. |
| [design/tiles-module.md](design/tiles-module.md) | `wtf-tiles`: providers, the cache, formats, downloading, keys. |
| [design/providers.md](design/providers.md) | The shipped providers and what is particular to each. |
| [design/compose-module.md](design/compose-module.md) | `wtf-compose`: shapes, scale, sheets, regions and effects, the exporters. |
| [design/studio-module.md](design/studio-module.md) | `wtf-studio`: the window, canvas, steps, dialogs, threading. |
| [design/subject-outlines.md](design/subject-outlines.md) | The outline library: sources, precision, what is bundled. |
| [design/project-file.md](design/project-file.md) | The `.wtf` file, field by field, and the rules on opening. |
| [design/packaging-and-updates.md](design/packaging-and-updates.md) | Installers, CI, splash, change log, update check, settings, releasing. |
| [design/headless-and-debugging.md](design/headless-and-debugging.md) | Tests without a display, the CLIs, capture runs, logging, the debugger. |

## Acceptance criteria

On a clean machine with the cache present and no API key:

1. The app opens on the world from the cache and pans and zooms smoothly wherever there are tiles.
2. A rectangle over Bohemia, 5000 mm wide, on a 915 mm roll in vertical strips with 15 mm overlap,
   at zoom 15, reports six strips at about 298 DPI and a scale near 1:36 000.
3. Exporting it writes six PDFs in under five minutes at a 2 GB heap, each of which opens and
   prints.
4. Reopening the saved `.wtf` reproduces the identical export, byte for byte.
5. A Czechia outline over an area from France to Slovakia prints the border as a crisp vector edge,
   the inside in full color and the outside faded, with the inventory counting both regions'
   tiles before anything is downloaded.
6. Past the cached area the map says so, and a download with no key configured opens the key
   settings instead of throwing.
7. A cache copied to another machine is recognized there as it is: Settings → Tile cache shows
   the same tile count on both (and the developer command `wtf-tiles stats` counts the same).
8. A coarse outline warns with its segment length in millimeters at the print size.
9. The Detail step shows, per zoom, the DPI, the scale and a place name's height on paper, and the
   1:1 preview shows the print at physical size.
10. A download of thousands of tiles reports a rate and an ETA that settle within a minute,
    survives cancel and resume with no tile fetched twice, and leaves the window usable.
11. `mvn -pl wtf-compose -am package` succeeds, and the CLI runs with no JavaFX at all.
