# Packaging, splash, change log, updates

How WTF is built into installers, released, started, and kept up to date.

---

## 1. The installed application

`tools/package/package.sh` builds the installer for the platform it runs on – an MSI on Windows, a
`.dmg` on macOS, a `.deb` on Linux: `mvn -B clean package -DskipTests`, a runtime image from
`jlink`, then `jpackage`. It is a plain script rather than Maven antrun executions, so it runs the
same on a developer's machine (Git Bash on Windows) as in the release workflow, and a packaging
problem reproduces without CI.

**The application is on the class path, not the module path.** Four dependencies are automatic
modules – `jts-core`, `pdfbox`, `pdfbox-io` and `fontbox` – which `jlink` refuses to link, and
`jpackage` over a module path runs `jlink`, so that route is closed until those four ship
`module-info`. What ships instead:

* a runtime image of **JDK and JavaFX modules only**: the ones `jdeps` finds the jars using,
  committed as `tools/package/runtime-modules.txt` and checked against the jars
  (`tools/package/runtime-modules.sh --check`) on every push and before every image, plus
  `jdk.localedata`, which the Czech formats need and `jdeps` cannot see. JavaFX goes in as named
  modules, so `jlink` places its native libraries and `--enable-native-access` can name
  `javafx.graphics`. The locales are cut to English and Czech;
* the application's jars and the rest of its dependencies on the **class path**, via
  `--input wtf-studio/target/libs`;
* `net.trdlo.wtf.studio.Launcher` as the entry point, a class that does not extend `Application`
  (its Javadoc says why);
* `-Xss4m` (buffering a detailed outline recurses deeply in JTS), `-XX:MaxRAMPercentage=70` and
  `--enable-native-access=javafx.graphics,ALL-UNNAMED` for JavaFX and for WTF's own
  Credential Manager calls (§6). Maven and the IDE, which run the module, name
  `net.trdlo.wtf.studio` for the second; the tests `ALL-UNNAMED`.

The module declarations still matter: the compiler checks them, and Maven and the IDE start `WtfApp`
on the module path. What keeps JavaFX out of the engine is the enforcer rule in `wtf-tiles` and
`wtf-compose`, which fails any build in which JavaFX reaches them in any scope.

| | Windows | macOS | Linux |
| --- | --- | --- | --- |
| Installer | MSI, per user, with a folder chooser, a Start menu entry and a desktop shortcut | `.dmg`, bundle `net.trdlo.wtf` | `.deb`, package `wtf`, maintainer `wtf@trdlo.net`, menu group Graphics |
| Icon | `icon.ico` | `icon.icns` | `icon-256.png` |
| Upgrades | A fixed upgrade UUID, so a new MSI replaces the old one | – | – |
| Signed | No: SmartScreen warns | No: the quarantine flag has to be cleared | – |

Every installer carries `LICENSE`, the third-party notices (below) and registers `.wtf` files (`application/x-wtf-project`, with the
application's icon). `jpackage` takes digits and dots only, so a `-SNAPSHOT` is cut off, and macOS
refuses a bundle version starting with 0, so its first number is one higher there – `0.1.0` is
`1.1.0` and `1.0.0` is `2.0.0` – inside the bundle only; the `.dmg` keeps the version About
shows in its name.

Windows and Linux pass an opened `.wtf` as an argument; macOS sends an Apple event, which WTF does
not yet listen for. A project opened while WTF runs goes to that window (`SingleInstance`: a lock,
and a loopback port with a token in the log folder, owner-only where the platform allows). Each
connection has its own thread, and only claiming the offer is timed, never the person's answer, so
a slow window is still believed – one with a dialog open opens the file once the dialog is closed.

### The Maven side

- The parent pom's `<url>`, `https://github.com/DickBoffin/wtf`, names the repository for the
  studio; `AppInfo` repeats it as a fallback, and `TileDownloader.CONTACT` names its issues for the
  `User-Agent`. `app.properties` – `app.version`, `app.name`, `app.commit`, `app.author`,
  `app.repositoryUrl`, `app.releasesUrl` – is filtered in `wtf-studio` and read once by `AppInfo`.
- **The jars are reproducible.** `app.commit` is `git rev-parse HEAD`, there is no build date, and
  `project.build.outputTimestamp` fixes every entry's time. `.gitattributes` checks out what goes
  into a jar with LF, and the jars carry no `META-INF/maven`, so two builds of one commit are the
  same bytes on every platform. The installers are not: `jpackage` stamps its own times.
- The `windows`, `mac`, `mac-aarch64` and `linux` profiles, activated by the host OS, set
  `javafx.classifier` and nothing else. **Every JavaFX artifact names
  `<classifier>${javafx.classifier}</classifier>` explicitly**: without it, `copy-dependencies` on
  macOS silently matches nothing and `jlink` fails with "Module javafx.base not found". Do not
  simplify it away.
- `wtf-studio` copies its runtime dependencies, the sibling modules among them, into
  `wtf-studio/target/libs` at `package`, and puts its own jar beside them, so that folder is the
  whole application. The pom excludes every `org.openjfx` artifact without a classifier, and
  `package.sh` deletes the JavaFX jars from the folder once they are linked into the runtime.
- The upgrade UUID and the macOS package identifier are in `package.sh` and never change;
  `package.sh` also writes the file association `jpackage` reads. Output goes to `target/runtime`
  and `target/installers`.
- `CHANGELOG.md` is copied from the repository root onto the class path root.
- **The third-party notices.** `license-maven-plugin` writes `THIRD-PARTY.txt` into
  `wtf-studio/target/libs` at `prepare-package`: a line per library from its pom, its licenses
  under short names (`Apache-2.0`, `EPL-2.0`…, `licenseMerges` mapping what the poms call them).
  The template, `wtf-studio/src/license/third-party.ftl`, adds by hand what is not a Maven
  dependency – OpenJDK, Font Awesome inside the ikonli pack, Liberation Sans (D29), the Natural
  Earth outlines – and says the Mapy.com logo is Mapy.com's mark. The full text of each license is
  in `wtf-studio/src/license/licenses`, copied into `target/libs/licenses` at `package` with the
  root `LICENSE` as `GPL-3.0-or-later.txt`. A dependency without a license, or with one not in
  `includedLicenses`, fails the build: a new license is its text in `licenses/`, its name there,
  and a merge if its pom calls it something else. `ThirdPartyNoticesIT`, run by failsafe in
  `verify`, checks that every jar in `target/libs` has its line and every license named its text.
  The file is not a resource, so the jars stay the same bytes; `--input` takes it and `licenses/`
  into the application's folder, beside the jars – JavaFX's lines stay after its jars go into the
  runtime – and About → Licenses links both.

## 2. GitHub Actions

Two workflows, actions pinned by commit with the release named in a comment, Dependabot proposing
the next ones.

- **`maven.yml`** (*Build*), on every push and on a pull request from a fork, on Linux:
  `mvn -B verify` (tests, the enforcer, SpotBugs, as `mvn install` locally);
  `runtime-modules.sh --check`, so a dependency needing another JDK module fails on the push that
  brought it; the jars, uploaded. A second job builds `wtf-compose` and `wtf-tiles` alone
  (`-pl wtf-compose -am`) on a JDK with no JavaFX and runs the command line – `sample`, `layout`,
  and an export from an empty cache that leaves the gaps as paper and must log its warning to
  stderr.
- **`release.yml`** (*Installers*), on a `v*` tag or by hand: `check-version.sh` first (the tag or
  given version, the pom and the change log's newest heading name one version, and for a tag the
  heading has a date), with `mvn -B verify` beside it, since `package.sh` skips both. Then one job
  per platform, since `jpackage` targets only its own: `versions:set` in the runner (nothing is
  committed back), `package.sh`, the installer uploaded; on Windows and Linux an app image started
  with `--screenshot`, which fails when it wrote no picture (the macOS runner has no window
  server). A job checks the three platforms' jars are the same bytes. On a tag, a last job takes
  the notes from the tag's section of `CHANGELOG.md`, adds how to get past each unsigned installer
  (`xattr -dr com.apple.quarantine` on macOS), and creates a **draft** release.

## 3. The splash screen

`Splash`: four scan heads sweep a dark frame, and the map comes up behind them, washed out, then in
full color as the last lands. At least 3.3 seconds, and longer while the startup work runs behind
it off the FX thread – the provider catalog, stale `.part` files cleared from the cache. It opens on
the screen the main window's saved bounds overlap most. With Settings → Startup → *Show the splash
screen* off, nothing of it is built; an unattended run shows none, but for a capture of the splash.
No license gate, no FXML. A failed startup hides the splash, logs the exception, shows it and exits.

### The first world

The backdrop is the cached imagery of the provider and theme the map will open on: the deepest of
zooms 3, 2 and 1 the cache holds completely, since a world with gaps looks like a fault. On a first
run the splash fetches the sixteen tiles of zoom 2 from the first global provider that needs no key
– with the update check, the only request made before anybody asks for anything.

- **The splash waits for it**: until the backdrop has settled and been shown a second, up to 25
  seconds, since the fetch is abandoned when the splash goes.
- **The tiles are asked for scattered**, in bit-reversed Morton order, so the first four are the
  four quadrants and a half-finished fetch still looks like a world, not a strip of Arctic ocean.
- **A complete world is recorded**, and the main window opens on that provider when the user has
  not chosen one, rather than on one with an empty cache asking for a key. An incomplete world is
  not recorded; the next run fetches only the missing tiles.

## 4. The change log

`CHANGELOG.md` is the one list of what changed. It ships as a resource, is rendered by `Markdown`
under **About → What's new**, and gives the release workflow its notes. The first run of a new
version opens About on that tab, unless Settings → Startup → *Show what is new after an update* is
off; the version last run is remembered, so a downgrade announces nothing.

- A release is a `## <version> – <yyyy-MM-dd>` heading, newest first, the version as the second word
  (a hyphen for the en dash is accepted). Between releases the newest section names the pom's next
  version with `unreleased` for the date.
- A user-visible change goes in when it is committed: a new feature as a bold name, one or two
  sentences and how to reach it; an improvement or fix as a line, from the user's side. Refactoring,
  CI, docs and tooling stay out.
- The renderer knows `#` to `###` headings, paragraphs, `-` and `*` bullets nested by indentation,
  `|` tables with a separator row, `---` rules, and inline bold, italic, code and links. Anything
  else shows as text.

## 5. Update check

`UpdateCheck` asks `https://api.github.com/repos/<owner>/<repo>/releases`, derived from
`app.repositoryUrl`, and takes the newest release that is neither draft nor prerelease, comparing
versions number by number (`0.10.0` is newer than `0.9.0`, a release newer than its `-SNAPSHOT`). An
empty list is up to date. Nothing is sent but the request.

- **Silent**: six seconds after the window is up, on its own thread, at most once a day (only an
  answered check counts), when Settings → Startup → *Check for updates* is on, as it is by default.
  It speaks only of a newer release, offering the download page, not now, or skip this version,
  which is remembered. An unreachable repository is a debug line in the log.
- **Interactive**, *Check for updates now* in About: always asks and always reports – newer, up to
  date, or could not check. Not knowing is never reported as up to date.

## 6. Settings storage

`Prefs` wraps `java.util.prefs.Preferences` with typed accessors, and saves window bounds as
`window.<name>`, restored only onto a screen that exists. The node is `trdlo.net/wtf`
(`HKCU\Software\JavaSoft\Prefs\trdlo.net\wtf` on Windows). Every key is a constant in `Prefs`, and
one read in more than one place carries its default, so two readers cannot disagree about a first
run. An unattended run makes it read-only, so a capture leaves no view, paper or key behind. The
settings dialog's four tabs – Cache, Display, Download, Startup – write each key as it changes.

**API keys.** On Windows a key is not in the node, which is the registry and plain text to anything
running as the user, but a generic credential in the Credential Manager, `trdlo.net/wtf/key.<provider>`,
kept on this computer, in UTF-16 as `cmdkey` keeps a password. `WindowsCredentials` calls
`CredReadW`, `CredWriteW`, `CredDeleteW` and `CredFree` through the JDK's foreign-function API
(in `java.base`), with no library between. On macOS and Linux a key is `key.<provider>` in the node,
until the Keychain and the Secret Service are done where they can be tried (a backlog bullet each).
`ApiKeys` reads the store once per provider that needs a key, at the start behind the splash, and
keeps the answers, since the toolbar asks for the current key each time it says what it can do;
`Prefs.app()` is one instance, shared by the whole window. It writes only a key that changed; one the
store will not keep stays in memory until WTF closes, with a warning in the log naming the provider,
never the key. Nothing moves a key between stores: a key kept in the node on Windows is entered
again, and its `key.<provider>` value deleted by hand.

## 7. Icons

`tools/icon/IconGen.java` renders `wtf.svg` through Inkscape at ten sizes from 16 to 512 pixels and
writes the PNGs (`icon.png` is the 256), a Windows `icon.ico` with PNG members and a macOS
`icon.icns` into `wtf-studio`'s resources. It needs `inkscape` on the path, so it is not part of the
build, and its output is committed.

```bash
java tools/icon/IconGen.java     # from the repository root, into wtf-studio's resources
```

## Building an installer by hand

```bash
tools/package/package.sh 0.1.0            # msi, dmg or deb for the host OS
tools/package/package.sh 0.1.0 app-image  # unpacked, no WiX or dpkg needed
```

Without a version it takes the pom's, and stops if there is none. It builds from `clean` and skips
the tests: run `mvn install` first. The MSI needs the WiX Toolset 4 (`dotnet tool install --global
wix --version 4.0.6`, with the `WixToolset.Util.wixext` and `WixToolset.UI.wixext` extensions). The
installer lands in `target/installers`.

## Releasing

The tag is the version. Between releases the pom names the next one (`0.1.1-SNAPSHOT`); the workflow
stamps the tag's version into the build, so the installers and About both say `0.1.0`.

1. In `CHANGELOG.md`, replace the newest section's `unreleased` by the date – `## 0.1.0 –
   2026-10-01` – and commit.
2. `git tag v0.1.0 && git push origin v0.1.0`. The Installers workflow checks the version
   (`tools/package/check-version.sh v0.1.0` runs locally too), builds all three platforms and
   creates a **draft** release.
3. Look at the draft and press **Publish release**, or `gh release edit v0.1.0 --draft=false`. Only a
   published release is seen by the update check.
4. `mvn versions:set -DnewVersion=0.2.0-SNAPSHOT -DgenerateBackupPoms=false
   -DprocessAllModules=true`; add `## 0.2.0 – unreleased` above the released section; check with
   `tools/package/check-version.sh` and commit both. Without the heading, every manual run of the
   workflow fails its first job.

Only the builds of the newest two tags are kept: the account has 500 MB of Actions storage. When a
tag's draft is made, the workflow's last job deletes every release before the two newest – their
tags stay – and every artifact of an earlier run. An artifact is kept three days at most in any
case, and the build on a push keeps none.

**A tag is never moved.** A draft that needs redoing is deleted with its tag – `gh release delete
v0.1.0 --cleanup-tag` – and tagged again. A published release is not redone; the next version is.
The repository setting *immutable releases* enforces that.

A manual run (`gh workflow run release.yml`) builds and smoke-tests the installers without
publishing: the way to check a packaging change. Without a version it stamps them
`0.0.<run number>`, because the MSI takes its product code from the version – two test builds of one
version would be one product to Windows – and a test build ranks below every release. About still
says the pom's version. A version given to a manual run must be the one the pom and the change log
name (`tools/package/check-version.sh 0.2.0`), though its heading needs no date yet.
