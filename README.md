# WTF – the Web Tile Framework

WTF turns web map tiles into print-ready files for walls that never asked for any of this: a
country across five meters of wallpaper, a valley on a stack of A3 sheets, a city on a single
poster. You say what the print is about and how big it should be; WTF works out the zoom, fetches
the tiles, and writes PDF, PNG or TIFF files cut into the sheets or strips your printer can feed.

It is a desktop application for Windows, macOS and Linux, in English and Czech. It is in early
development – there are no releases yet – so expect it to change under you.

## What it does

- **A subject and an area.** The subject is what the print is about – a country, a region, a
  lake – picked from a built-in library of continents, countries and regions, drawn by hand, or
  imported from GeoJSON, KML or CSV. The area is the world around it: the subject's box, hull or
  outline, or a shape of your own, with room around the edge that can fade out into the paper.
- **Two treatments.** The ground inside the subject and the ground outside it can have their own
  zoom and their own look: Czechia in full color, the rest of Europe faded around it, and a sharp
  vector boundary between the two.
- **Honest scale.** The detail step shows how tall a place name will be on the wall, not just a
  DPI number, and a 1:1 preview – calibrated against a bank card – shows a patch of the print at
  life size on your screen before a single sheet is printed.
- **Paper that exists.** Standard sheet sizes or a roll, portrait or landscape, with overlaps for
  gluing. Marks for putting the sheets together – sheet numbers and arrows – go only where the
  neighboring sheet covers them, so nothing shows on the wall.
- **Fast, exact PDFs.** Cached tiles are copied into the PDF byte for byte rather than decoded
  and compressed again, so a print of tens of thousands of tiles is written in seconds and every
  pixel is the tile's own.
- **No holes.** An export whose tiles are not all downloaded is refused unless you choose
  otherwise, and a file that is only a proof says so.

## Map data

Sixteen tile providers come built in – among them OpenStreetMap, OpenTopoMap, Mapy.com, Esri
World Imagery, and the national mapping agencies of Switzerland, France, the Netherlands, Norway,
Germany, the United States and Japan – and you can add your own in the Providers dialog. Some need
a free API key, which you enter in the application; it is kept on your machine and never written
into a project file.

The map data belongs to its providers, not to WTF. Each provider's terms apply to what you print,
several of them require their credit on anything printed from their maps, and some do not allow
downloading tiles in bulk – WTF follows that and says so. The credit is written beside every
export, and can go on the print itself.

## Building and running

You need JDK 25. Maven 3.9 or newer does the rest, JavaFX included.

```bash
git clone https://github.com/DickBoffin/wtf.git
cd wtf
mvn install                    # builds everything and runs the tests
mvn -pl wtf-studio javafx:run  # starts the application
```

`tools/package/package.sh` builds a native installer for the platform it runs on, using `jlink`
and `jpackage`.

`wtf-tiles` and `wtf-compose` also have command lines (`TilesCli`, `ComposeCli`), for scripts and
for CI. They are developer tools, run from a build and not installed: no module has a `Main-Class`,
and the installers contain only the application. After `mvn install`, from the repository root (on
Windows, with `;` between the class path entries instead of `:`):

```bash
mvn -q -pl wtf-compose dependency:build-classpath -DincludeScope=runtime -Dmdep.outputFile=target/classpath.txt
CP="wtf-compose/target/classes:$(cat wtf-compose/target/classpath.txt)"
java -cp "$CP" net.trdlo.wtf.compose.ComposeCli layout project.wtf
java -cp "$CP" net.trdlo.wtf.tiles.TilesCli stats
```

The project is three Maven modules:

| Module | What it is |
| --- | --- |
| `wtf-tiles` | Tile providers, the tile cache, downloading. No user interface. |
| `wtf-compose` | Shapes, sheet layout and the exporters, plus a developer command line (`layout`, `inventory`, `export`, and `sample` to write a starting project) for working on a `.wtf` project without a window. |
| `wtf-studio` | The JavaFX application. |

The design documents are in [docs](docs/README.md), and what changed between versions is in
[CHANGELOG.md](CHANGELOG.md).

## Authors

DickBoffin & Claude Code.

## License

Copyright © 2026 DickBoffin.

WTF is free software: you can redistribute it and/or modify it under the terms of the GNU General
Public License as published by the Free Software Foundation, either version 3 of the License, or
(at your option) any later version. It is distributed in the hope that it will be useful, but
WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
PARTICULAR PURPOSE. See [LICENSE](LICENSE) for the full text.

In short: use it, change it and pass it on – with its source, with the names of the people who
made it, and with a note of what you changed.

The libraries WTF uses keep their own licenses, listed in the application under About →
Licenses; an installation carries their notices in `THIRD-PARTY.txt` and the text of each license
in `licenses/`, linked from there. The country and region outlines come from [Natural Earth](https://www.naturalearthdata.com/),
which is in the public domain.
