#!/usr/bin/env bash
#
# count-loc.sh
#
# Counts lines of code across the Lunicle codebase, grouped by the role each
# module plays, and prints a per-group breakdown followed by a grand TOTAL.
#
# Two counts are reported per group:
#   Total  - every line in the matched source files (raw `wc -l` equivalent).
#   Code   - lines that still contain source after stripping comments; this
#            drops full-line and block comments and any line left blank once its
#            comment is removed (i.e. blank lines and comment-only lines).
#
# Groups (see settings.gradle.kts for the module layout):
#   Server                        - server/                (JVM backend, incl. the
#                                                           SQLDelight schema and
#                                                           migrations)
#   Shared between all clients    - client/                (KMP module shared by
#                                                           every client target)
#   Shared between server+clients - clientServer/          (protocol/model code
#                                                           used on both ends)
#   Web                           - web/                   (Kotlin/JS renderer —
#                                                           Lunicle's only client)
#   Lunula Toolkit                - ../../lunula           (sibling toolkit modules
#                                                           consumed via the Gradle
#                                                           composite build; only
#                                                           counted when the
#                                                           checkout is present on
#                                                           disk — a build resolving
#                                                           the published artifacts
#                                                           instead reports 0)
#
# Generated/dependency trees (node_modules, build, dist) are pruned. Counted
# code file types: .kt (all modules), .sq and .sqm
# (SQLDelight schema and migrations).
#
# Comment stripping recognises C-style `//` line comments and `/* ... */` block
# comments (the syntax used by Kotlin), plus SQL `--` line comments in .sq/.sqm
# files only — `--` is a decrement operator in Kotlin, so it is never treated as
# a comment there. It is a lexical approximation: it does not parse string
# literals, so a `//`, `/*` or `--` inside a string is treated as a comment. In
# practice this only affects the rare line whose *only* content sits after such
# a token, so the Code figure is a close estimate rather than an exact SLOC.
#
# Usage:
#   scripts/count-loc.sh
#
# This is a bash script, not a POSIX sh one — run it directly or with `bash`.
# `sh scripts/count-loc.sh` fails, because /bin/sh on macOS disables the process
# substitution used below.
#
# Override the Lunula Toolkit location with:
#   LUNULA_PATH=/path/to/lunula/checkout scripts/count-loc.sh

set -euo pipefail

# Resolve the repo root from this script's location so it works from any CWD.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# awk program that, for the files handed to it, prints two space-separated
# integers: "<total-lines> <code-lines>". Block-comment state (`inblock`) and
# the SQL-dialect flag (`sqlmode`) are (re)set at the first record of every file
# (FNR==1) so an odd file cannot bleed state into the next one.
read -r -d '' AWK_SLOC <<'AWK' || true
# Returns 1 if the line holds any code once comments are stripped, else 0.
function has_code(line,   out, i, n, c, c2) {
  out = ""
  n = length(line)
  i = 1
  while (i <= n) {
    c  = substr(line, i, 1)
    c2 = substr(line, i, 2)
    if (inblock) {
      if (c2 == "*/") { inblock = 0; i += 2 } else { i += 1 }
      continue
    }
    if (c2 == "/*") { inblock = 1; i += 2; continue }
    if (c2 == "//") { break }          # line comment: ignore the remainder
    if (sqlmode && c2 == "--") { break }  # SQL line comment (.sq/.sqm only)
    out = out c
    i += 1
  }
  gsub(/[ \t\r]/, "", out)
  return (length(out) > 0)
}
FNR == 1 { inblock = 0; sqlmode = (FILENAME ~ /\.sqm?$/) }
{ total += 1; if (has_code($0)) code += 1 }
END { print total + 0, code + 0 }
AWK

# tally: reads a NUL-delimited file list on stdin and prints "<total> <code>".
# xargs may split a huge list across several awk invocations, so the trailing
# awk sums their partial totals back together.
tally() {
  xargs -0 awk "$AWK_SLOC" 2>/dev/null \
    | awk '{ t += $1; c += $2 } END { print t + 0, c + 0 }'
}

# count_loc <root-dir...> -- <ext...>  ->  "<total> <code>"
#
# Counts lines across all files under the given root directories whose extension
# matches one of the listed extensions. Directories named node_modules, build
# or dist are pruned so generated/dependency code is never counted. Missing
# directories contribute 0.
#
# Args:
#   Everything before the literal `--` token is a root directory; everything
#   after it is a bare file extension (no leading dot).
count_loc() {
  local dirs=() exts=() seen_sep=0 arg
  for arg in "$@"; do
    if [ "$arg" = "--" ]; then seen_sep=1; continue; fi
    if [ "$seen_sep" -eq 0 ]; then dirs+=("$arg"); else exts+=("$arg"); fi
  done

  local name_pred=() first=1 e
  for e in "${exts[@]}"; do
    if [ "$first" -eq 1 ]; then
      name_pred+=(-name "*.$e"); first=0
    else
      name_pred+=(-o -name "*.$e")
    fi
  done

  find "${dirs[@]}" \
      -type d \( -name node_modules -o -name build -o -name dist \) -prune -o \
      -type f \( "${name_pred[@]}" \) -print0 2>/dev/null \
    | tally
}

# format_int <n>  ->  n with thousands separators (e.g. 37161 -> 37,161)
format_int() {
  printf "%s" "$1" | awk '{
    n = $0; s = ""
    while (length(n) > 3) {
      s = "," substr(n, length(n) - 2) s
      n = substr(n, 1, length(n) - 3)
    }
    print n s
  }'
}

# --- Per-group counts (each captures "<total> <code>") ----------------------

read -r server_t server_c            < <(count_loc server/src -- kt sq sqm)
read -r sclients_t sclients_c        < <(count_loc client/src -- kt)
read -r ssc_t ssc_c                  < <(count_loc clientServer/src -- kt)
read -r web_t web_c                  < <(count_loc web/src -- kt)

# Lunula Toolkit lives in a sibling checkout and is pulled in via a Gradle
# composite build (see settings.gradle.kts). Auto-detect it the same way Gradle
# does — honouring an explicit override, then walking UP from the repo root
# rather than counting a fixed number of `..`, since a git worktree sits three
# levels further down at <repo>/.claude/worktrees/<name>. Bounded at eight
# levels, which is far past any real layout and stops the search from walking to
# the filesystem root on a machine with no lunula at all. When no checkout is
# found, Gradle resolves the published artifacts and these counts stay 0.
toolkit_t=0; toolkit_c=0; toolkit_path=""
toolkit_candidates=("${LUNULA_PATH:-}")
for depth in 0 1 2 3 4 5 6 7 8; do
  up=""
  for _ in $(seq 0 "$depth"); do up="../$up"; done
  toolkit_candidates+=("${up}lunula/develop" "${up}lunula/main")
done
for cand in "${toolkit_candidates[@]}"; do
  [ -n "$cand" ] || continue
  if [ -f "$cand/settings.gradle.kts" ]; then toolkit_path="$cand"; break; fi
done
if [ -n "$toolkit_path" ]; then
  read -r toolkit_t toolkit_c < <(count_loc \
    "$toolkit_path/lunula-core/src" \
    "$toolkit_path/lunula-store/src" \
    "$toolkit_path/lunula-web/src" \
    "$toolkit_path/lunula-compose/src" \
    -- kt sq sqm)
fi

total_t=$(( server_t + sclients_t + ssc_t + web_t + toolkit_t ))
total_c=$(( server_c + sclients_c + ssc_c + web_c + toolkit_c ))

# --- Output -----------------------------------------------------------------

label_w=32
row() { printf "  %-${label_w}s %12s %12s\n" "$1" "$2" "$3"; }
rule() { row "------------------------------" "------------" "------------"; }

printf "\n"
row "Lunicle — Lines of Code" "Total" "Code"
rule
row "Server"                    "$(format_int "$server_t")"   "$(format_int "$server_c")"
row "Shared (all clients)"      "$(format_int "$sclients_t")" "$(format_int "$sclients_c")"
row "Shared (server + clients)" "$(format_int "$ssc_t")"      "$(format_int "$ssc_c")"
row "Web"                       "$(format_int "$web_t")"      "$(format_int "$web_c")"
if [ "$toolkit_t" -gt 0 ]; then
  row "Lunula Toolkit"        "$(format_int "$toolkit_t")"  "$(format_int "$toolkit_c")"
else
  row "Lunula Toolkit (no checkout)" "0" "0"
fi
rule
row "TOTAL LOC"                 "$(format_int "$total_t")"    "$(format_int "$total_c")"
printf "\n"
printf "  Total = all lines; Code = excludes comments and blank lines.\n\n"
