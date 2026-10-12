# Headless operation and debugging

How WTF is worked on by someone – or something – not sitting in front of it. Two of its three
modules have no window at all.

---

## 1. Four levels, cheapest first

| Level | What runs | Display | Use it for |
| --- | --- | --- | --- |
| **1. Unit tests** | JUnit in every module | no | The math, the layout, the PNG-to-PDF mapping. Most bugs live here. |
| **2. The CLIs** | `TilesCli`, `ComposeCli` (§3) | no | End-to-end export from a `.wtf`, anywhere, in CI. |
| **3. Capture runs** | `WtfApp --screenshot…` (§4) | briefly | What only goes wrong once the window is laid out. |
| **4. Live debugger** | JDWP (§7) | yes | State that exists only mid-frame, mid-drag or mid-export. |

A bug reproducible at level 1 or 2 is reproduced there; "you have to click it" is a smell in the
module split.

## 2. Tests

`wtf-tiles` and `wtf-compose` are pure – records in, files out – with an ordinary JUnit 5 suite.
The tests that matter most:

- **`PdfRoundTripTest`**: an indexed PNG, a truecolor PNG and a JPEG placed as the exporter does,
  read back with PDFBox and compared pixel for pixel – the one place where a subtle mistake yields a
  file that opens, looks plausible and is wrong. **`CentralClaimTest`** shows why: each image
  XObject's raw stream is the PNG's IDAT or the JPEG file byte for byte, with the `/DecodeParms`
  that read it.
- **Golden files**, `GoldenPdfTest`, over `wtf-compose/src/test/resources/golden`: a fixture project
  over nine cached tiles (one recorded as sea), exported and compared byte for byte with
  `golden.pdf`, every tile copied; and `decoded.wtf` over an aerial JPEG theme, compared with
  `decoded.pdf` – JPEG copied with a `/Decode` array inside, desaturated outside so each outer tile
  with color is re-encoded through `LosslessFactory`, and among the inner tiles a gray JPEG, a progressive one, a
  truecolor PNG with `/Decode` and two PNGs with alpha decoded with an `/SMask`. A third test checks
  which path each of those takes, so a change sending them all one way cannot pass by replacing the
  file.

**Determinism** needs no setting: a PDF has no creation date, and its `/ID` is a SHA-256 of the
file's name, pages and images. The one printed date, a sheet label's `Marks.today()` (used by
`WtfFile.toJob` and the studio's `JobBuilder`), is fixed by `-Dwtf.export.date=2026-01-01`. A change
meant to alter the PDF fails the test and leaves the new file in `target/golden/` under the
fixture's name. The stored bytes depend on zlib, which the JDK bundles only on Windows, so the byte
compare is skipped where the JDK's zlib compresses differently; the test that a reopened project
exports the same bytes runs everywhere.

`mvn -pl wtf-compose -am test` needs no JavaFX and no display; the enforcer rule and CI's
engine-only job (§3) keep it so.

### The `Headless` test platform

The studio's toolkit tests run on JavaFX's headless platform (`-Dglass.platform=Headless`,
`-Dprism.order=sw`) through one helper, `Headless`: it starts the platform once per JVM, applies the
dark theme, unpacks JavaFX's natives before any test moves `user.home`, and **fails** – never skips –
every test that needs it where the platform will not start. On it: `StudioDialogTest` (every
dialog), `WindowsTest`, `CaptureInputTest`, `MediumPanelTest`, `ExportPanelTest`,
`ForgetEmptyTest` (the whole main window, answering its Providers dialog inside `showAndWait`'s
nested loop) and `StartPageTest` (the whole window opening an example, whose providers it stands in
for). None touches the user's settings, home or cache; their prints and maps use a provider at
`127.0.0.1:1`, where nothing listens. Windows must stay inside the 1000 × 1000 headless screen.
On Linux the platform needs only libc, libm, FreeType, fontconfig, Pango and GLib.

## 3. The CLIs

The only home for their exit codes and conventions; the commands are in
[tiles-module.md](tiles-module.md) §6 and [compose-module.md](compose-module.md) §11.

**They are developer tools, run from a build.** No module has a `Main-Class` and the installers
contain only the studio. After `mvn install`, from the repository root, the class path of
`wtf-compose` – which carries `wtf-tiles` and the logging with it – runs both, as CI does (on
Windows with `;` between the entries):

```bash
mvn -q -pl wtf-compose dependency:build-classpath -DincludeScope=runtime -Dmdep.outputFile=target/classpath.txt
CP="wtf-compose/target/classes:$(cat wtf-compose/target/classpath.txt)"
java -cp "$CP" net.trdlo.wtf.tiles.TilesCli stats
java -cp "$CP" net.trdlo.wtf.compose.ComposeCli layout project.wtf
```

Elsewhere in the documents `wtf-tiles` stands for the first `java` line up to its arguments and
`wtf-compose` for the second.

```
wtf-tiles   providers | stats | count | download | forget-empty      the cache and the downloader
wtf-compose layout | inventory | export | sample                     a .wtf into print files
```

**Exit codes**, the same numbers in both, so a caller can branch on them:

| Code | `wtf-tiles` | `wtf-compose` |
| --- | --- | --- |
| `0` | Success, `--help`. | Success, `--help`. |
| `1` | A job error: a `download` in which a tile failed, in which every tile answered "does not exist" and none is cached, or which a keyless provider refused (`Refusal` with a `DownloadReason`). | A job error, including a print with nothing real in it (`NothingThereException`), from `export`, `inventory` and `--dry-run` alike, under every `--missing`. |
| `2` | Bad arguments, none at all, or a `Refusal` of any other reason. | Bad arguments, none at all, or a refused job (`Refusal`) – a tile no decoder can read and a print of no tiles at all among them. |
| `3` | Missing credentials. | – |
| `4` | Tiles missing, from `count` (`--json` too). | Tiles missing: `inventory`, `export --dry-run`, and an export that refuses gaps. |
| `5` | A bulk download the provider does not allow; nothing overrides it. | – |

Argument parsing happens inside the same handling, so a bad option is `2`, never a stack trace.

- **Prefixed status lines** – `[tiles]`, `[compose]`, and `[batch]` for capture runs – so tool
  output is separable from wrapper output.
- **`--json`** on `wtf-tiles providers` and `count`, `wtf-compose layout` and `inventory`: one object
  on stdout. `inventory --json` and `export --dry-run` are how an agent checks a change in seconds.
- **Keys from the environment**: `--key`, or `WTF_KEY_<PROVIDER>` (`WTF_KEY_MAPY_COM` for
  `mapy-com`), so no key is typed into a logged command.
- **Numbers in `Locale.ROOT`**, and logging to stderr (§6), so nothing interrupts a `--json` answer.

CI's second job builds `wtf-tiles` and `wtf-compose` without `wtf-studio` and runs `ComposeCli
sample` and `layout` on a JDK without JavaFX.

## 4. Capture runs

Each capture flag builds a state, photographs it to a PNG and exits. `BatchMode` parses the flags
and captures; `WtfApp` seeds the state through `CaptureSeeds`.

```bash
mvn -pl wtf-studio javafx:run -Djavafx.args="--screenshot-steps=temp/steps"
mvn -pl wtf-studio javafx:run -Djavafx.args="--screenshot-steps=temp/o@outline --lang=cs"
```

| Flag | The picture |
| --- | --- |
| `--screenshot=<file>` | The main window, on the Start page as a start that opens no project shows it. |
| `--screenshot-steps=<dir>` | `step-0.png`, the Start page, to `step-5.png`, with a subject and an area seeded from the view so no panel is empty. |
| `--screenshot-outlines=<file>#<words>` | The outline picker searching for the words – `#`, because `outline` is a modifier. |
| `--screenshot-splash=<file>@<ms>` | The splash that many milliseconds in (1 400 by default). |
| `--screenshot-preview=<file>` | The 1:1 preview window. |
| `@edit`, `@outline`, `@drawn`, `@faded`, `@onesheet`, `@sheets`, `@calibrate` | Modifiers after the file: a many-part subject; the fit *the outline itself*; a Custom area of two parts, grown; a visible treatment; a print 150 mm wide, on one sheet, or 1 000 mm, on several; the preview's calibration. Several go after one `@`, comma-separated, each a whole word. |
| `@hike`, `@poster`, `@wallpaper`, `@city` | That example, opened as its card on the Start page opens it, before the other modifiers; nothing is seeded from the view over it. |
| `--seed-subject=<file.geojson>`, `--seed-view=lon,lat,zoom` | Start from that subject or view. |
| `--lang=cs\|en`, `--units=mm\|inch` | That language or those lengths, over Settings. Check layouts in Czech: an ellipsis there means a fixed width. |

A flag that starts like these and is none of them exits 1 with a console line. No capture shows
handles, since they follow the pointer and a capture hears none.

What makes a run safe unattended:

1. **No splash**, except in the splash capture.
2. **No dialog waits.** Any capture flag calls `Dialogs.setBatchMode(true)` first: messages become
   `[batch]` lines, and a question answers on the side of doing nothing irreversible – yes/no
   declines, `Dialogs.Unsaved` answers Don't save (the only project is the seeded one or an
   example; a capture opens no file and takes no hand-over), `Dialogs.BeforeExport` answers Export
   without saving.
3. **The window settles.** An `AnimationTimer` lets a few frames pass, then waits until no decode of
   the photographed canvas is outstanding for 1.5 s – since the decode count last changed, or since
   the step was selected. The preview's source fetches missing tiles inside its decodes, so they
   are waited for too. One 60 s deadline covers the whole run; past it, every remaining picture is
   taken as it is.
4. **Exit 0 only if every picture was written**, else 1 – a failed start included, and a run with
   nothing to photograph ends at once. Nothing ends a run that never reaches a window, so a caller
   sets its own timeout. The release workflow's smoke test runs the packaged app with `--screenshot`
   and checks the file.
5. **No input.** Every scene's root is mouse-transparent and every window swallows keys
   (`BatchMode.refuseInput`), so the pointer cannot light a button or show handles; the system's
   close button still works.
6. **No trace.** Preference writes are dropped, and no recovery copy is written or offered
   ([studio-module.md](studio-module.md) §5).

Dialogs have no flag: a throwaway test on the `Headless` platform (§2) can build one and write
`scene.snapshot(...)` to a file.

## 5. Run configurations and the IDE

**WtfApp** (see CLAUDE.md) and **WtfApp (screenshot)**, the same with
`--screenshot=temp/wtf-studio.png`, are committed with the inspection profile, encodings, `misc.xml`
and `vcs.xml`; neither carries a key. The JavaFX plugin's `<mainClass>` is `${app.mainClass}`, so
`-Dapp.mainClass=…` runs another class and `-Djavafx.mainClass` is ignored.

## 6. Logging

`wtf-studio/src/main/resources/logback.xml`: the console and `wtf.log` in the folder `LogDir` works
out – `%LOCALAPPDATA%\WTF\logs`, `~/Library/Logs/WTF`, `~/.cache/wtf/logs`, or `-Dwtf.log.dir`.

- INFO by default, a file a day, a week kept (10 MB a file, 100 MB in all); `-Dwtf.log.level=DEBUG`
  raises it without a new build.
- An uncaught exception is always logged and shown, at most once in ten seconds, since a painting
  fault repeats every frame. The error dialog links the log folder, and "Copy the details" copies the
  version and the stack trace.
- Tests log WARN and above to the console only (`logback-test.xml`), never to the user's folder; the
  engine modules have no file log.
- The command lines log to stderr, WARN by default (`wtf-tiles/src/main/resources/wtf-cli-logback.xml`,
  named by `ConsoleOutput.configureLogging` unless `-Dlogback.configurationFile` names another).
- **A long operation logs one INFO summary when it ends** – "Export finished", "Download finished" –
  with what it did.
- Never log an API key: log `Provider.redactedTemplate()`, never the resolved URL, nor the message
  of whatever refused to make a request of it, which quotes the address or the header it refused.

## 7. The debugger

[`mcp-jdwp-java`](https://github.com/FgForrest/mcp-jdwp-java) is an MCP server that attaches over
JDWP to a running JVM: stacks, locals, conditional breakpoints, logpoints, exception breakpoints,
watchpoints and expression evaluation. The target JVM needs only

```
-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:5005
```

in a copy of the run configuration or on the `java` command line. **Bind to `127.0.0.1`**, never
`*` – an open JDWP port is remote code execution. **One JDWP connection at a time**: never this and
the IDE's debugger on one JVM. Use it for state that is expensive to reach again – deep in a long
export, mid-frame, mid-gesture; for anything reproducible in a second, a test or `--dry-run` is
faster. Evaluated expressions must handle checked exceptions, watchpoints on hot fields stall the
target, and a JVM launched under a shell that exits dies with it.
