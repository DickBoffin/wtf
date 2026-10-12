#!/usr/bin/env bash
#
# Builds a native installer for whichever platform it is run on.
#
#   tools/package/package.sh [version] [type]
#
# `type` defaults to the platform's installer (msi, dmg, deb). Pass `app-image` to get a plain
# unpacked application directory, which needs no WiX or dpkg and is the quickest way to check
# that the packaged runtime actually starts.
#
# Runs on Windows (Git Bash), macOS and Linux, and is what the release workflow calls, so a
# packaging problem can be reproduced locally instead of only in CI.
#
# Why the application lands on the class path rather than the module path: jpackage over a
# module path means jlink, and jlink refuses automatic modules. This application has four it
# does not control - jts-core, pdfbox, pdfbox-io and fontbox. So the application's own jars and
# its non-modular dependencies sit on the class path, entered through
# net.trdlo.wtf.studio.Launcher, which does not extend Application. See that class for why.
#
# JavaFX is the exception and goes into the runtime image as proper named modules: jlink places
# the native libraries itself, and --enable-native-access can name javafx.graphics. It names the
# unnamed module as well, since WTF's own calls into the Windows Credential Manager come from
# the class path.

set -euo pipefail

cd "$(dirname "$0")/../.."
ROOT="$(pwd)"

VERSION="${1:-}"
TYPE="${2:-}"
# Without a version, the pom's, read as check-version.sh reads it: the first version after the
# project's own artifactId
if [ -z "$VERSION" ]; then
    VERSION=$(tr -d '\r' < pom.xml \
        | sed -n '/<artifactId>wtf<\/artifactId>/,/<\/version>/ s:.*<version>\(.*\)</version>.*:\1:p' \
        | head -1)
fi
# jpackage will not accept a version with a qualifier, and every platform wants digits and dots
APP_VERSION="${VERSION%%-*}"
if ! [[ "$APP_VERSION" =~ ^[0-9]+(\.[0-9]+)*$ ]]; then
    echo "Not a version to stamp on an installer: '$VERSION'" >&2
    exit 1
fi

APP_NAME="WTF"
VENDOR="DickBoffin"
MAIN_CLASS="net.trdlo.wtf.studio.Launcher"
LIBS="$ROOT/wtf-studio/target/libs"
OUT="$ROOT/target/installers"
RUNTIME="$ROOT/target/runtime"
RES="$ROOT/wtf-studio/src/main/resources/net/trdlo/wtf/studio"

case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) OS=windows ;;
    Darwin)               OS=mac ;;
    *)                    OS=linux ;;
esac

# macOS refuses a bundle version whose first number is 0, so the first number is one higher there,
# always: 0.1.0 is stamped 1.1.0 and 1.0.0 is 2.0.0, which keeps every later version above every
# earlier one. It is installer metadata only – the version the application shows is its own, from
# app.properties.
FILE_VERSION="$APP_VERSION"
if [ "$OS" = mac ]; then
    APP_VERSION="$(( ${APP_VERSION%%.*} + 1 )).${APP_VERSION#*.}"
fi

echo "== WTF $APP_VERSION for $OS"

# ---------------------------------------------------------------------------------------------
# 1. Build. target/libs is assembled by the wtf-studio pom during package, and cleaned first:
# after a version change it would hold both versions of every wtf-* jar. The tests and SpotBugs
# are not run here but before it – by the release workflow's verify job, and locally by
# `mvn install`.
# ---------------------------------------------------------------------------------------------

mvn -B clean package -DskipTests

MAIN_JAR=$(basename "$(ls "$LIBS"/wtf-studio-*.jar | head -1)")
echo "   main jar: $MAIN_JAR"
echo "   $(ls "$LIBS"/*.jar | wc -l) jars on the class path"

# ---------------------------------------------------------------------------------------------
# 2. A runtime image of the JDK's modules and JavaFX's.
#
# The ones jdeps finds the jars using, committed in runtime-modules.txt and checked against the
# jars before anything is linked, so a dependency that needs another module stops this script
# instead of breaking the installed application alone. The one added by hand is jdk.localedata:
# the Czech number and date formats are found by name at run time, where jdeps cannot see them,
# and --include-locales needs it.
# ---------------------------------------------------------------------------------------------

tools/package/runtime-modules.sh --check
MODULES="$(tr -d '\r' < tools/package/runtime-modules.txt | grep -v '^#' | sed '/^$/d' \
    | paste -sd, -),jdk.localedata"

rm -rf "$RUNTIME"
jlink \
    --module-path "$LIBS" \
    --add-modules "$MODULES" \
    --strip-debug --no-header-files --no-man-pages --compress=zip-6 \
    --include-locales=en,cs \
    --output "$RUNTIME"
echo "   runtime image: $(du -sh "$RUNTIME" | cut -f1)"

# And out of the class path input: they are in the image now, and a second copy beside it
# would be shadowed on a good day and picked on a bad one.
find "$LIBS" -name 'javafx-*.jar' -delete
echo "   $(ls "$LIBS"/*.jar | wc -l) jars on the class path, JavaFX in the runtime"

# ---------------------------------------------------------------------------------------------
# 3. The installer.
# ---------------------------------------------------------------------------------------------

rm -rf "$OUT"
mkdir -p "$OUT"

COMMON=(
    --name "$APP_NAME"
    --app-version "$APP_VERSION"
    --vendor "$VENDOR"
    --description "Assembles cached map tiles into print-ready files for very large prints."
    --copyright "DickBoffin – GNU GPL v3 or later"
    # The jars, and beside them THIRD-PARTY.txt and licenses/, the notices the build writes
    # there: jpackage takes only the jars onto the class path, and About links the two
    --input "$LIBS"
    --main-jar "$MAIN_JAR"
    --main-class "$MAIN_CLASS"
    --runtime-image "$RUNTIME"
    --dest "$OUT"
    # -Xss for JTS: buffering a detailed country outline recurses deeply enough to matter
    --java-options "-Xss4m"
    --java-options "-XX:MaxRAMPercentage=70"
    # JavaFX loads its native libraries, and on Windows WTF calls the Credential Manager's
    # functions from the class path, where it is the unnamed module (WindowsCredentials). Without
    # this the JDK warns of both on every start, and says a later release will refuse them.
    --java-options "--enable-native-access=javafx.graphics,ALL-UNNAMED"
)

# A project opens in WTF from the file manager. The icon is the application's, in the format
# the platform wants; Windows and Linux hand the file over as the argument WtfApp looks for.
associate() {
    local file="$ROOT/target/wtf-project.properties"
    local icon="$1"
    # Git Bash rewrites /c/... for a Windows program's arguments, but not inside a file it reads
    if [ "$OS" = windows ]; then
        icon=$(cygpath -m "$icon")
    fi
    printf '%s\n' \
        "extension=wtf" \
        "mime-type=application/x-wtf-project" \
        "description=WTF project" \
        "icon=$icon" > "$file"
    printf '%s' "$file"
}

# A plain unpacked application, which needs neither WiX nor dpkg nor hdiutil. This is how the
# packaged runtime gets smoke-tested: the installer formats only wrap what this produces, so if
# the app image starts, the difference left to go wrong is the installer itself.
if [ "$TYPE" = "app-image" ]; then
    jpackage "${COMMON[@]}" --type app-image
    echo
    echo "== Written to $OUT"
    ls -la "$OUT"
    exit 0
fi

case "$OS" in
    windows)
        jpackage "${COMMON[@]}" \
            --type "${TYPE:-msi}" \
            --license-file "$ROOT/LICENSE" \
            --icon "$RES/icon.ico" \
            --win-dir-chooser \
            --win-menu \
            --win-shortcut \
            --win-per-user-install \
            --win-upgrade-uuid "7d1f2c48-6a3b-4c7e-9f21-3ab5d8e01c64" \
            --file-associations "$(associate "$RES/icon.ico")"
        ;;
    mac)
        # Unsigned: a downloaded build needs the quarantine flag cleared, which the release
        # notes say. Signing needs a paid developer identity and is a decision, not a default.
        jpackage "${COMMON[@]}" \
            --type "${TYPE:-dmg}" \
            --license-file "$ROOT/LICENSE" \
            --icon "$RES/icon.icns" \
            --mac-package-name "$APP_NAME" \
            --mac-package-identifier "net.trdlo.wtf" \
            --file-associations "$(associate "$RES/icon.icns")"
        ;;
    linux)
        jpackage "${COMMON[@]}" \
            --type "${TYPE:-deb}" \
            --license-file "$ROOT/LICENSE" \
            --icon "$RES/icon-256.png" \
            --linux-package-name "wtf" \
            --linux-deb-maintainer "wtf@trdlo.net" \
            --linux-shortcut \
            --linux-menu-group "Graphics" \
            --file-associations "$(associate "$RES/icon-256.png")"
        ;;
esac

# jpackage names the disk image after the bundle version; a tester reads the file name, so it
# carries the version About shows
if [ "$OS" = mac ] && [ "$APP_VERSION" != "$FILE_VERSION" ]; then
    mv "$OUT/$APP_NAME-$APP_VERSION.dmg" "$OUT/$APP_NAME-$FILE_VERSION.dmg"
fi

echo
echo "== Written to $OUT"
ls -la "$OUT"
