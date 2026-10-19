# Change log

## 0.1.1 – unreleased

- **The macOS disk image is named after the version WTF shows**, not the bundle number macOS
  requires inside it.
- **A new provider's maps table shows its rows whole**: its header covered half of the first.
- **A header value without the key token says where the key goes instead** – under Providers and
  keys, never in the provider itself.
- **A choice of looks.** Settings → Display chooses Primer, GitHub's, which is now the default;
  Cupertino, after macOS; or Nord, the look until now – each light or dark. Dracula is dark only,
  and JavaFX's own Modena and Caspian light only.
- **JavaFX 27**, with AtlantaFX 3 and the libraries' newest releases.

## 0.1.0 – 2026-10-12

The first release: WTF turns web map tiles into print-ready files, from one poster to a country
across five meters of wall.

- **Start from an example.** A Start page above the five steps says what WTF makes and opens a map
  for a hike, a country on a poster, a wallpaper for the living room or a city on nine sheets as a
  new project, every step filled in and ready to export. It is what a new project shows, and Alt+0
  comes back to it.
- **Say what the print is about.** Pick a continent, a country or a region from the built-in
  outlines, draw a shape by hand, or import one from GeoJSON, KML or CSV. The map around it is
  worked out from it, or is a shape of your own, with room around the edge that can fade out.
- **Inside and outside, each its own way.** The ground inside the subject and the ground around
  it can have their own look – lighter, grayer, flatter, or not there at all – with a sharp line
  between the two, in the subject's color if you want one.
- **See the scale before you pay for paper.** The detail step says how tall a place name will be
  on the wall, and a life-size preview, calibrated against a bank card, shows a patch of the print
  at its real size on your screen. Where a map has sharper `@2x` tiles, a print can have twice the
  DPI with names just as large.
- **Paper that exists.** Standard sheets or a roll, in millimeters or inches, with overlaps for
  gluing; the print's size given as a length, a number of sheets or a DPI. Sheet numbers and
  arrows go only where the next sheet covers them, so nothing shows on the wall.
- **Sixteen map providers built in**, among them OpenStreetMap, Mapy.com and the national mapping
  agencies of seven countries, and your own in the Providers dialog. A provider's key stays on
  your computer – on Windows in the Credential Manager, not in plain text – and never in a
  project file.
- **PDF, PNG or TIFF.** A PDF copies each downloaded tile into itself as it is, so tens of
  thousands of tiles are written in seconds and every pixel is the provider's own. The same
  project always gives the same file.
- **No holes by surprise.** An export whose tiles are not all downloaded is refused unless you
  choose otherwise, and a file that is only a proof says so in its name. A canceled export leaves
  only finished sheets behind.
- **Projects are `.wtf` files.** Double-click one in the file manager and it opens – in the WTF
  that is already running, if one is. Save and Save as have their own buttons, and unsaved work is
  never lost without a question: Save, Don't save or Cancel, and before an export Save, Save as,
  Export without saving or Cancel – and if WTF goes down before it is saved, in a crash or a power
  cut, the next start offers it back.
- **Undo and redo** for everything in the project.
- **English and Czech**, on Windows, macOS and Linux.
- The license of everything WTF is built on comes with it in full, linked from About → Licenses.
