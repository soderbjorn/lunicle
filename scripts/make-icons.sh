#!/usr/bin/env bash
#
# make-icons.sh
#
# Regenerate the icon files the web client ships, from the two sources in icons/.
#
#   ./scripts/make-icons.sh
#
# Run it after changing the mark, and commit what it writes. It is deterministic
# — same input, same bytes out — so a run that changes nothing leaves the working
# tree clean, which is also how you check that the committed copies really are
# what the sources say.
#
# ── The two sources ──────────────────────────────────────────────────────────
#
#   icons/icon.svg          the mark on a rounded plate with a hairline border.
#                           The browser-tab favicon.
#   icons/icon-square.svg   the same mark, full-bleed, no border. For the
#                           add-to-home-screen icon, which iOS masks itself and
#                           refuses an alpha channel.
#
# Both files explain why they exist. They carry the same mark group and this
# script refuses to run if it ever differs between them, so "the same mark" is a
# checked claim rather than a hope.
#
# ── Why the outputs are committed rather than built ──────────────────────────
#
# A browser asking for /favicon-32.png wants a file the server can serve, and the
# server serves web/src/jsMain/resources verbatim. Generating them during a build
# would teach Gradle a trick it has no reason to know and would put a native
# binary (`rsvg-convert`) on the critical path of every clean build and every CI
# run. This way the dependency is on one developer's machine, once, when the mark
# actually changes.
#
# ── Where the mark itself lives ──────────────────────────────────────────────
#
# Not here. The drawing is `LOGO_SVG` in Icons.kt — the mark the running app
# paints — and icons/icon.svg is a hand copy of it with a plate around it. This
# script cannot check that copy, because one side of it is Kotlin; icons/icon.svg
# says so at the top.
set -euo pipefail

if ! command -v rsvg-convert > /dev/null 2>&1; then
  echo "error: rsvg-convert is not installed (brew install librsvg)" >&2
  exit 1
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PLATED="$ROOT/icons/icon.svg"
SQUARE="$ROOT/icons/icon-square.svg"

# The two sources must be drawing the same object. Compare the whole mark group —
# the transform, the stroke settings and every path inside it, which is the whole
# of the drawing. Everything outside that <g> is deliberately different between
# the two files.
marks() { sed -n '/<g /,/<\/g>/p' "$1"; }
if ! diff <(marks "$PLATED") <(marks "$SQUARE") > /dev/null; then
  echo "error: icons/icon.svg and icons/icon-square.svg no longer draw the same mark." >&2
  echo "       Fix one to match the other; they are two framings of one drawing." >&2
  diff <(marks "$PLATED") <(marks "$SQUARE") >&2 || true
  exit 1
fi

render() { # render <source> <px> <path>
  mkdir -p "$(dirname "$3")"
  rsvg-convert -w "$2" -h "$2" "$1" -o "$3"
  echo "  $2px  ${3#"$ROOT/"}"
}

echo "==> Web"
# The SVG itself is what a modern browser uses; it is copied rather than linked
# out of icons/ because only this directory is served.
cp "$PLATED" "$ROOT/web/src/jsMain/resources/icon.svg"
echo "        web/src/jsMain/resources/icon.svg"
# The raster fallback, for browsers that ignore an SVG icon link.
render "$PLATED" 32 "$ROOT/web/src/jsMain/resources/favicon-32.png"
# Add-to-home-screen on iOS Safari, and — because this app declares no web
# manifest — the icon Chrome uses when somebody installs the site as an app. Both
# want an opaque square, so it comes from the square source.
render "$SQUARE" 180 "$ROOT/web/src/jsMain/resources/apple-touch-icon.png"

echo "==> Done."
