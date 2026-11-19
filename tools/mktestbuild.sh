#!/bin/sh
# A test build: one branch's change for a tester to try on their car, before it's merged.
# Its own kind beside releases and pre-releases: tag test-<name>, built and signed by the
# release workflow (.github/workflows/release.yml), published as a GitHub pre-release
# titled "Test build" (GitHub has no third kind), never Latest. It keeps the version it's
# built on (1.1.0-rc.5: the app offers it as "Reinstall HMG-API", and the next real release
# updates over it); its files carry the name, and its Car Reports say which build it is
# (installed.txt's build stamp).
#
#   tools/mktestbuild.sh <name> [--pr N] [--issue N] [--title "..."] [--note "..."]
#       writes the notes into an annotated tag test-<name> on HEAD and pushes it: the release
#       workflow builds, tests and signs it (after a maintainer approves the run, as for a
#       release) and drafts the pre-release with those notes
#   tools/mktestbuild.sh <name> --publish
#       publishes the draft
#   tools/mktestbuild.sh <name> --delete
#       deletes the test build and its tag (once a real release carries the change)
#
# <name>: lowercase letters, digits and dashes, ending in its number: report-oom-1, selinux-2.
# HEAD must be committed and on GitHub. --pr defaults to the open PR for the branch.
set -e
cd "$(dirname "$0")/.."

die() { echo "mktestbuild: $*" >&2; exit 1; }

NAME=${1:-}
[ -n "$NAME" ] || die "usage: tools/mktestbuild.sh <name> [--pr N] [--issue N] [--title ...] [--note ...] | --publish | --delete"
shift
echo "$NAME" | grep -qE '^[a-z0-9]+(-[a-z0-9]+)*-[0-9]+$' || die "$NAME: lowercase letters, digits and dashes, ending in its number (report-oom-1)"
TAG=test-$NAME
REPO=${HMGAPI_REPO:-Open-HMG/HMG-API}

PR= ISSUE= TITLE= NOTE= ACTION=tag
while [ $# -gt 0 ]; do
    case "$1" in
        --pr) PR=$2; shift ;;
        --issue) ISSUE=$2; shift ;;
        --title) TITLE=$2; shift ;;
        --note) NOTE=$2; shift ;;
        --publish) ACTION=publish ;;
        --delete) ACTION=delete ;;
        *) die "unknown option $1" ;;
    esac
    shift
done

case "$ACTION" in
    publish)
        gh release edit "$TAG" -R "$REPO" --draft=false --prerelease --latest=false >/dev/null
        gh release view "$TAG" -R "$REPO" --json url -q .url
        exit 0 ;;
    delete)
        gh release delete "$TAG" -R "$REPO" --cleanup-tag --yes 2>/dev/null || git push -q origin ":refs/tags/$TAG" 2>/dev/null || true
        git tag -d "$TAG" >/dev/null 2>&1 || true
        echo "deleted the test build $TAG"
        exit 0 ;;
esac

git diff --quiet HEAD && git diff --cached --quiet || die "uncommitted changes: commit them (the build stamp names a commit)"
C=$(git rev-parse HEAD)
git fetch -q origin
[ -n "$(git branch -r --contains "$C")" ] || die "HEAD $(git rev-parse --short HEAD) isn't on GitHub: push the branch first"
git ls-remote --exit-code --tags origin "refs/tags/$TAG" >/dev/null 2>&1 && die "tag $TAG is on GitHub already: pick the next number"

V=$(sed -n 's/.*HMGAPI_VERSION = "\(.*\)";/\1/p' core/runtime/java/hmgapi/engine/Engine.java)
S=$(git rev-parse --short "$C")
BRANCH=$(git rev-parse --abbrev-ref HEAD)
if [ -z "$PR" ] && [ "$BRANCH" != HEAD ]; then
    PR=$(gh pr list -R "$REPO" --head "$BRANCH" --state open --json number -q '.[0].number' 2>/dev/null || true)
fi
[ -n "$TITLE" ] || TITLE=$( [ -n "$PR" ] && gh pr view "$PR" -R "$REPO" --json title -q .title 2>/dev/null || echo "$BRANCH")

# the tag's message: its subject is the release's title, the rest its notes (release.yml)
M=$(mktemp); trap 'rm -f "$M"' EXIT
{
    echo "Test build: $TITLE ($TAG)"
    echo
    echo "**This is a test build, not a release.** It's HMG-API $V plus one change under test (build \`$S\`$( [ -n "$PR" ] && echo ", PR #$PR")). Install it only if you're helping test."
    echo
    [ -n "$NOTE" ] && { echo "$NOTE"; echo; }
    echo "**Install:** unzip \`hmgapi-$V-$NAME-usb.zip\` onto a USB stick, plug it in, and open HMG-API → Updates: it offers **Reinstall HMG-API** (same version number, new build). The other downloads install the same build: \`-nemesis\` (NEMESIS 1.16+), \`-adb\`, and \`-update\` (only the signed update, for a stick's \`HMGAPI/updates\` folder)."
    echo
    echo "**Check it's this build:** a Car Report from it says \`HMG-API $V (build $S …)\`."
    echo
    echo "**Going back:** reinstall the $V release the same way, or let the next release update over this one."
    echo
    echo "Tested off-car only, unless it says otherwise above.$( [ -n "$ISSUE" ] && echo " Post results on #$ISSUE.") Checksums are in \`SHA256SUMS\`."
} > "$M"

git tag -a "$TAG" --cleanup=verbatim -F "$M" "$C"
git push -q origin "refs/tags/$TAG"
echo "pushed $TAG ($S, HMG-API $V). The release workflow builds it once a maintainer approves the run:"
echo "  https://github.com/$REPO/actions/workflows/release.yml"
echo "then check the draft and: tools/mktestbuild.sh $NAME --publish"
