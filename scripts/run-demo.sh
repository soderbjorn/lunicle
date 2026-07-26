#!/usr/bin/env bash
#
# Run the tracker in DEMO mode from your WORKING TREE — no server, no database.
#
#   ./scripts/run-demo.sh              → http://localhost:8081/?demo=1
#   ./scripts/run-demo.sh --prod       # the minified bundle the website embeds
#   ./scripts/run-demo.sh --no-open    # don't launch a browser
#   LUNICLE_BRAND_DIR=/path/to/brand ./scripts/run-demo.sh    # branded (LNL-110)
#
# ── What demo mode is ────────────────────────────────────────────────────────
#
# `?demo=1` (LNL-146) swaps the HTTP backend for DemoLunicleApi: an in-memory
# world seeded by seedDemoWorld(), living entirely in the tab. Nothing is
# fetched and nothing is stored. You arrive signed in as Captain Janeway —
# instance admin and project owner, so every affordance is unlocked — your edits
# last until you reload, and a reload starts the same story over.
#
# That is why this script starts no JVM and touches no database: the only thing
# demo mode needs is *something serving the JS bundle*. Hence python3 over the
# built distribution directory, and hence a port of its own, so this can run
# beside a real ./scripts/run-dev.sh rather than fighting it for 8080.
#
# It is also the closest local equivalent of what the marketing site embeds:
# www.lunicle.dev serves the very same bundle as static files and frames it at
# demo-app/index.html?demo=1. With --prod it is byte-for-byte that bundle.
#
# ── Which bundle ─────────────────────────────────────────────────────────────
#
# Default is the DEVELOPMENT webpack build: unminified, much faster to produce,
# readable in devtools — what you want when the thing you are demoing is a
# change you just made. --prod builds the production bundle instead, which is
# what to reach for when the question is "is what we ship right?" — a minifier
# is the kind of thing that turns a working app into a blank page, and only the
# production build can answer that.
#
# ── Branding, without the server that normally does it ───────────────────────
#
# LUNICLE_BRAND_DIR is the same variable the deployed container sets (LNL-110),
# and pointing this script at one previews a branded instance's demo — what
# lunicle-deployment's own run-demo.sh does with its brand/ directory.
#
# Serving it needs a little work the server would otherwise do for us, because
# here there is no server. The client fetches branding over plain URLs —
# /brand/brand.json, /brand/themes/*.json, /brand/logo.svg, /brand/fonts/* (see
# loadBrand() in BrandConfig.kt) — so a static copy answers those perfectly well,
# except for the two things Ktor computes rather than reads off disk:
#
#   * /brand/brand.json is served ENRICHED with a `themes` array of the theme
#     filenames on disk (brandRoutes in BrandRoutes.kt). It is the only way the
#     client discovers which themes to fetch: without it a branded instance
#     serves its logo and fonts but none of its themes.
#   * index.html is spliced with the favicon link, the fonts.css link and the
#     <title> override (brandedIndexHtml in BrandRoutes.kt).
#
# So a brand dir means staging: bundle + brand copied into one directory, with
# those two files rewritten. The python below is a transcription of those two
# functions and has to stay one — if their behaviour changes, this drifts.
# Unbranded (the default) stages nothing and serves the build directly.
#
# Env:
#   LUNICLE_DEMO_PORT   the static server's port (default: 8081)
#   LUNICLE_BRAND_DIR   a brand directory to apply (default: unbranded)
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
# port_holder / wait_for_http. No lib/env.sh: there is no sign-in here to
# configure, and no lib/dev-server.sh: there is no server.
# shellcheck source=lib/probe.sh
source "$SCRIPT_DIR/lib/probe.sh"
# shellcheck source=lib/browser.sh
source "$SCRIPT_DIR/lib/browser.sh"

LUNICLE_DEMO_PORT="${LUNICLE_DEMO_PORT:-8081}"

open_browser=1
flavour=development
for arg in "$@"; do
  case "$arg" in
    --no-open) open_browser=0 ;;
    --prod|--production) flavour=production ;;
    -h|--help) sed -n '2,8p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "usage: $0 [--prod] [--no-open]" >&2; exit 2 ;;
  esac
done

if [[ "$flavour" == production ]]; then
  gradle_task=":web:jsBrowserDistribution"
  dist="$REPO_ROOT/web/build/dist/js/productionExecutable"
else
  gradle_task=":web:jsBrowserDevelopmentExecutableDistribution"
  dist="$REPO_ROOT/web/build/dist/js/developmentExecutable"
fi

command -v python3 > /dev/null 2>&1 || {
  echo "error: python3 is needed to serve the bundle, and isn't on PATH." >&2
  echo "       Any static file server over $dist would do just as well." >&2
  exit 1
}

# Same rule as require_dev_port_free in lib/dev-server.sh, and for the same
# reason: silently adopting whatever already answers here would demo someone
# else's bundle while looking perfectly healthy. Reported by name, not killed —
# this script did not start it.
if port_is_held "$LUNICLE_DEMO_PORT"; then
  echo "error: something is already listening on http://localhost:$LUNICLE_DEMO_PORT/" >&2
  echo "       It's: $(port_holder "$LUNICLE_DEMO_PORT")" >&2
  echo "       Stop it, or re-run with LUNICLE_DEMO_PORT=<other port>." >&2
  exit 1
fi

echo "==> Building the $flavour web bundle ($gradle_task)"
"$REPO_ROOT/gradlew" -p "$REPO_ROOT" "$gradle_task"

# The bundle is what this whole script exists to serve, so check it is there
# rather than starting a server that would answer 404 and leave you reading the
# browser console to find out why.
[[ -f "$dist/index.html" && -f "$dist/web.js" ]] || {
  echo "error: no bundle at $dist (expected index.html and web.js)" >&2
  exit 1
}

serve_dir="$dist"
brand_dir="${LUNICLE_BRAND_DIR:-}"

if [[ -n "$brand_dir" ]]; then
  [[ -d "$brand_dir" ]] || {
    echo "error: LUNICLE_BRAND_DIR is not a directory: $brand_dir" >&2
    exit 1
  }
  # Under web/build/, so it is already ignored by git and `./gradlew clean`
  # takes it with everything else. Rebuilt from the bundle on every run: a stage
  # directory that outlived its bundle would serve yesterday's app under today's
  # brand, which looks like a code bug and isn't one.
  serve_dir="$REPO_ROOT/web/build/demo-branded"
  echo "==> Applying brand: $brand_dir"
  mkdir -p "$serve_dir"
  # Two passes rather than one: --delete on the bundle would otherwise sweep
  # away brand/ (it isn't in the source), and --delete on brand/ scoped to its
  # own subdirectory is what drops assets you removed from the brand dir.
  rsync -a --delete --exclude='/brand' "$dist/" "$serve_dir/"
  rsync -a --delete "$brand_dir/" "$serve_dir/brand/"

  # Transcribed from brandRoutes() and brandedIndexHtml() in BrandRoutes.kt —
  # see the header. Defensive in the same places they are: a malformed
  # brand.json costs you the themes list, not the run.
  python3 - "$serve_dir" <<'PY'
import html, json, pathlib, re, sys

stage = pathlib.Path(sys.argv[1])
brand = stage / "brand"

manifest = {}
manifest_file = brand / "brand.json"
if manifest_file.is_file():
    try:
        manifest = json.loads(manifest_file.read_text())
    except ValueError:
        print("    warning: brand.json is not valid JSON; serving it unenriched")
        manifest = None

# The `themes` array the server computes from the directory listing.
if isinstance(manifest, dict):
    themes_dir = brand / "themes"
    themes = sorted(p.name for p in themes_dir.glob("*.json")) if themes_dir.is_dir() else []
    manifest["themes"] = themes
    manifest_file.write_text(json.dumps(manifest, indent=2))
    print(f"    themes: {', '.join(themes) if themes else '(none)'}")

# The <head> splices, gated on the files actually being present so a partial
# brand degrades cleanly — exactly as brandedIndexHtml does.
info = manifest if isinstance(manifest, dict) else {}
index = stage / "index.html"
doc = index.read_text()
title = (info.get("title") or "").strip()
if title:
    doc, n = re.subn(r"<title>.*?</title>", f"<title>{html.escape(title)}</title>", doc, count=1, flags=re.S)

additions = ""
if (brand / "favicon.png").is_file():
    additions += '    <link rel="icon" href="/brand/favicon.png">\n'
# After the app's own styles.css link, by virtue of landing at the end of
# <head> — that ordering is what lets the brand win the cascade.
if (brand / "fonts" / "fonts.css").is_file():
    additions += '    <link rel="stylesheet" href="/brand/fonts/fonts.css">\n'
if title and "<title>" not in doc:
    additions += f"    <title>{html.escape(title)}</title>\n"
if additions and "</head>" in doc:
    doc = doc.replace("</head>", additions + "</head>", 1)
index.write_text(doc)
PY
fi

target_url="http://localhost:$LUNICLE_DEMO_PORT/?demo=1"

echo "==> Serving $serve_dir on :$LUNICLE_DEMO_PORT"
# --bind 127.0.0.1, not the default all-interfaces: this is a local preview and
# nothing about it wants to be reachable from the coffee-shop wifi.
python3 -m http.server "$LUNICLE_DEMO_PORT" --bind 127.0.0.1 --directory "$serve_dir" > /dev/null 2>&1 &
SERVER_PID=$!

cleaned=0
cleanup() {
  # The trap fires on INT/TERM *and* again on EXIT; without this guard the whole
  # thing runs twice and reports "Stopping…" twice. Same as run-dev.sh.
  if [[ "$cleaned" -eq 1 ]]; then return; fi
  cleaned=1
  echo
  echo "==> Stopping…"
  kill "$SERVER_PID" 2>/dev/null || true
  wait 2>/dev/null || true
}
trap cleanup EXIT INT TERM

# 20 tries at a quarter-second: python's http.server binds almost immediately —
# unlike the dev server, whose first run compiles Kotlin/JS — so a slow answer
# here means it failed to start, not that it is thinking.
if ! wait_for_http "http://localhost:$LUNICLE_DEMO_PORT/index.html" 20 0.25 "$SERVER_PID"; then
  echo "error: the static server never answered on :$LUNICLE_DEMO_PORT" >&2
  exit 1
fi

cat <<EOF

    ─────────────────────────────────────────────────────────────
      Demo:  $target_url

      In-memory world, no server, no database, no sign-in.
      You are Captain Janeway; edits live until you reload.
      Bundle: $flavour$([[ "$flavour" == development ]] && echo "  (--prod for the one the website embeds)")
      Brand:  ${brand_dir:-(none — stock Lunicle)}

      Drop the ?demo=1 and it's the real app talking to a server
      that isn't there — so keep the query string.
    ─────────────────────────────────────────────────────────────

EOF

maybe_open_url "$target_url" "$open_browser"

echo "==> Ctrl-C to stop."
wait
