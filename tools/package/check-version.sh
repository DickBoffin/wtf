#!/usr/bin/env bash
#
# Whether a release says its version the same way everywhere, before any installer is built.
#
#   tools/package/check-version.sh v0.1.0   for a tag: the tag, the pom and the change log's
#                                           newest heading name one version, and that heading
#                                           has a date
#   tools/package/check-version.sh 0.1.0    for a manual run given a version: the version, the
#                                           pom and the newest heading agree; no date needed
#   tools/package/check-version.sh          otherwise: the pom and the newest heading agree
#
# The pom may say 0.1.0-SNAPSHOT at the tag v0.1.0; the release workflow stamps the tag's version
# into the build. The newest heading is what About opens What's new on after an update, and the
# section the release notes are taken from. Between releases it names the next version, with
# "unreleased" for its date.

set -euo pipefail

cd "$(dirname "$0")/../.."

ARG="${1:-}"

# The project's own version, the first one after its artifactId: the parent pom has no parent
POM=$(tr -d '\r' < pom.xml \
    | sed -n '/<artifactId>wtf<\/artifactId>/,/<\/version>/ s:.*<version>\(.*\)</version>.*:\1:p' \
    | head -1)
RELEASE="${POM%-SNAPSHOT}"

# "## 0.1.0 – 2026-10-01", the shape About reads; a hyphen for the dash is taken too
HEADING=$(tr -d '\r' < CHANGELOG.md | grep -m1 '^## ' || true)
read -r _ LOGGED DASH DATE _ <<< "$HEADING" || true

fail() {
    echo "::error::$1"
    exit 1
}

[ -n "$POM" ] || fail "no version found in pom.xml"
[ -n "$HEADING" ] || fail "CHANGELOG.md has no ## heading"
[ "${DASH:-}" = "–" ] || [ "${DASH:-}" = "-" ] \
    || fail "the newest heading of CHANGELOG.md is not '## <version> – <date>': $HEADING"
[ "$LOGGED" = "$RELEASE" ] \
    || fail "the pom says $POM, but the newest heading of CHANGELOG.md is $LOGGED"

case "$ARG" in
    v*)
        [ "${ARG#v}" = "$RELEASE" ] || fail "the tag is $ARG, but the pom says $POM"
        [[ "${DATE:-}" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] \
            || fail "CHANGELOG.md's heading for $RELEASE has no date: $HEADING"
        echo "The tag, the pom and the change log agree on $RELEASE, of $DATE."
        ;;
    "")
        echo "The pom and the change log agree on $RELEASE."
        ;;
    *)
        # A qualifier is the build's own: 0.2.0-rc1 is stamped on a build of 0.2.0
        [ "${ARG%%-*}" = "$RELEASE" ] || fail "the version given is $ARG, but the pom says $POM"
        echo "The version given, the pom and the change log agree on $RELEASE."
        ;;
esac
