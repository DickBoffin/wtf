#!/usr/bin/env bash
#
# The modules the installed runtime is linked from, as jdeps finds the application's jars using
# them – the jars in wtf-studio/target/libs, which `mvn package` assembles.
#
#   tools/package/runtime-modules.sh           prints them, one per line
#   tools/package/runtime-modules.sh --check   fails, showing the difference, when they are not
#                                              what runtime-modules.txt says
#
# The list is committed so that a dependency which needs one module more, or one fewer, changes a
# file in review instead of the installed application alone. CI checks it on every push, and
# package.sh before every runtime image.
#
# It is the reduced list jlink wants: a module that one of these requires comes along by itself,
# which is how java.desktop, java.logging, java.xml and javafx.graphics get in. What jdeps cannot
# see is a module found by name at run time only; package.sh adds the one WTF needs.

set -euo pipefail

cd "$(dirname "$0")/../.."
LIST=tools/package/runtime-modules.txt
LIBS=wtf-studio/target/libs

if [ ! -d "$LIBS" ]; then
    echo "$LIBS is not there: run mvn package first" >&2
    exit 1
fi

case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) SEP=';' ;;
    *)                    SEP=':' ;;
esac

derive() {
    (
        cd "$LIBS"
        # Every jar goes on the module path, where the application's own modules find the
        # automatic ones they require; the application is what is read. A classifier-less JavaFX
        # jar, an empty marker the wtf-studio pom excludes, would be a second javafx.base there,
        # and jdeps would say nothing useful about it.
        local all app
        all=$(ls -- *.jar)
        if printf '%s\n' "$all" | grep -qE '^javafx-[a-z]+-[0-9][0-9.]*\.jar$'; then
            echo "A classifier-less JavaFX jar is in $LIBS: see the exclusions in wtf-studio/pom.xml" >&2
            return 1
        fi
        app=$(printf '%s\n' "$all" | grep -v '^javafx-')
        # shellcheck disable=SC2086 # one jar name per word is the point
        jdeps --print-module-deps --ignore-missing-deps --multi-release 25 \
                --module-path "$(printf '%s\n' "$all" | paste -sd "$SEP" -)" $app 2>/dev/null \
            | grep -E '^[a-z][a-z0-9.]*(,[a-z][a-z0-9.]*)*$' | tail -1 | tr ',' '\n'
    )
}

committed() {
    tr -d '\r' < "$LIST" | grep -v '^#' | sed '/^$/d'
}

case "${1:-}" in
    --check|--write)
        DERIVED=$(derive)
        if [ -z "$DERIVED" ]; then
            echo "jdeps worked out no modules at all" >&2
            exit 1
        fi
        ;;
esac

case "${1:-}" in
    --check)
        if ! diff <(committed) <(printf '%s\n' "$DERIVED"); then
            echo "The jars need other modules than $LIST lists (< the file, > the jars)." >&2
            echo "If the change is meant: tools/package/runtime-modules.sh --write" >&2
            exit 1
        fi
        echo "Runtime modules as listed: $(committed | paste -sd, -)"
        ;;
    --write)
        # The file's own comments stay; the modules under them are the jars'
        HEADER=$(tr -d '\r' < "$LIST" | grep '^#' || true)
        printf '%s\n%s\n' "$HEADER" "$DERIVED" > "$LIST"
        echo "Wrote $LIST"
        ;;
    *)
        derive
        ;;
esac
