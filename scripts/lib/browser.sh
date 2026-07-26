#!/usr/bin/env bash
#
# Source (don't run) this to open the browser at the end of a run script.
#
#   source "$SCRIPT_DIR/lib/browser.sh"
#
# All four run scripts take --no-open and all four ended with the same handful
# of lines. This is that handful, once.

# maybe_open_url URL OPEN_FLAG — open unless OPEN_FLAG is 0.
#
# `open` is macOS-only and its absence is not an error: these scripts run fine
# on a machine without it, you just visit the URL yourself. The URL has already
# been printed by the caller's banner at this point, so there is nothing to lose
# but the convenience.
maybe_open_url() {
  local url="$1" open_flag="$2"
  if [[ "$open_flag" -ne 1 ]]; then return 0; fi
  if command -v open > /dev/null 2>&1; then
    echo "==> Opening your browser… (--no-open to skip)"
    open "$url"
  else
    echo "==> No 'open' command; visit the URL above yourself."
  fi
}
