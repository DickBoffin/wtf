# WTF – notes for working on it

What anyone changing this code – a person or an agent – should know first. What WTF is and how to
build it is in [README.md](README.md); why things are the way they are is in [docs](docs/README.md);
what is missing is in [docs/backlog.md](docs/backlog.md).

## Building and running

JDK 25 and Maven 3.9.

```bash
mvn install                    # builds everything, runs the tests and SpotBugs
mvn -pl wtf-studio javafx:run  # starts the application
```

`install` rather than `package`, because `-pl wtf-studio` builds that module alone and resolves
`wtf-tiles` and `wtf-compose` from the local repository, where only `install` puts them. Adding
`-am` instead is not the way out: it widens the reactor to the aggregator, and the run goal then
asks the aggregator for a main class it does not have. In IntelliJ, the committed run configuration
**WtfApp** works; it names the `wtf-studio.main` module, which is the part that matters – IntelliJ
splits `wtf-studio` into `.main` and `.test`, since the tests compile with arguments the main code
does not, and the bare `wtf-studio` has no class path.

To look at the window without sitting in front of it, use the capture flags – `--screenshot-steps`,
`--lang=cs` and the others – described in
[docs/design/headless-and-debugging.md](docs/design/headless-and-debugging.md) §4. Check a layout
in Czech: it is the longer language, and a label that ends in an ellipsis there has a fixed width.

## What does not travel with the repository

| | |
| --- | --- |
| API keys | Providers and keys → the provider → paste → Test. On Windows in the Credential Manager, as `trdlo.net/wtf/key.<provider>`; elsewhere in the preference store. Never in a file. |
| The tile cache | `%LOCALAPPDATA%\WTF\tiles` on Windows, the platform equivalent elsewhere. Copy the folder to move it; Settings → Tile cache points WTF at another one. |
| The remembered view | `map.view` in the preference store. Absent on a first run, which is what makes the window open on the world the splash fetched. |
| Your own providers | `~/.wtf/providers.json`, written by the Providers dialog. |
| Unsaved work | `~/.wtf/recovery`: each window's copy of its unsaved project, beside a lock naming its file. Gone with a save or a close answered; a start offers any whose window has gone. |

Without a cache the application still starts: the splash fetches a world from OpenStreetMap, and
every view says what is not cached until something is downloaded.

## Things to know before changing anything

- `wtf-tiles` and `wtf-compose` **must not** see JavaFX. An enforcer rule in both fails any build
  that gives them JavaFX in any scope, and CI builds them without `wtf-studio` and runs the
  command line. They also have no locale: the studio translates what they report (`ProgressText`),
  and a check of theirs that a person can fail throws a `Refusal` with a reason the studio says
  from the bundle (`EngineText`).
- SpotBugs runs in `verify`, so `mvn install` fails on a high-priority finding, as CI does.
- Tile bytes are **copied**, not converted, into a PDF. Anything that decodes a tile that did not
  need decoding undoes the central result of the project.
- **An export is the same file every time.** `GoldenPdfTest` compares two fixture exports with
  `golden.pdf` and `decoded.pdf` in `wtf-compose/src/test/resources/golden/` byte for byte – the
  first copies every tile, the second decodes – so nothing in a PDF may come from the clock, the
  machine or the order of a hash map. The one exception is the day a sheet label prints
  (`Marks.today()`, fixed by `-Dwtf.export.date`). A change meant to alter what an export writes replaces the
  file it changes, after a look at it, with the one the failing test leaves in `target/golden/`,
  in the same commit.
- A tile that **does not exist** (204 or 404) is not a missing tile.
- Numbers are parsed in the display locale and written with `Locale.ROOT`.
- Every interface string is in `messages.properties` and `messages_cs.properties`, through
  `I18n`: no concatenation, numbered placeholders, and plurals avoided ("Sheets: 6"). Write a key
  out as a literal where it is used: `MessagesTest` reads the code for keys that are missing or
  unused, and checks the bundles for parity, placeholders, spacing, escapes, apostrophes, American
  spelling and en dashes.
- **Writing style**, in strings, comments and docs: American spelling, an en dash (–) for a dash
  inside a sentence, commas between clauses. JTS's `Envelope.centre()` is the one exception.
- Comments and docs say what is true now. What changed and why is the commit's business.
- The opening view is **asked of the cache**, not stored as somebody's favorite place.
- **Pre-alpha: no compatibility code.** WTF reads its current formats and nothing else – no
  migration of old files, settings or caches – until there are users.
- The letters of the former company name appear nowhere in the repository. The home is
  **wtf.trdlo.net**: packages `net.trdlo.wtf.*`, preferences node `trdlo.net/wtf`.
- The license is **GPL-3.0-or-later**. Copyright and license lines name DickBoffin; the About
  window names the authors, DickBoffin & Claude Code.

## Commits

A subject line that says what changed for the user, and a body that says why. Tests pass before a
commit; `mvn install` is the check.

## Working the backlog

[docs/backlog.md](docs/backlog.md) numbers an item when it is scheduled. Its label says which
agent does it: *To be done by an Opus 5.5 agent at maximum effort*, in plan mode first, for an item
that crosses modules, changes a contract or touches undo; *at medium effort* for the rest. The two
are `.claude/agents/backlog-opus-max.md` and `backlog-opus-medium.md`. Items are worked **in order,
one per session**; "the next item" is the lowest number left.

1. The item's text is the specification; the decisions it cites are in
   [docs/open-questions.md](docs/open-questions.md). An item that names an earlier one as a
   dependency waits until that one is gone.
2. Do exactly that item, with its test. A problem found on the way becomes a new bullet under
   "Open", not a change.
3. `mvn install` passes. One commit whose body names the item: "Backlog item 114". The same commit
   deletes the item from the backlog; a part deliberately left out becomes a new bullet.
4. Say in one line which item is next and which agent its label names.
