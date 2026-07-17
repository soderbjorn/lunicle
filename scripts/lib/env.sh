#!/usr/bin/env bash
#
# Source (don't run) this to load .env into the environment.
#
#   source "$SCRIPT_DIR/lib/env.sh"
#
# Every run script needs the OAuth credentials and none of them should own the
# parsing, so it lives here. Absent .env is fine and silent: the server treats a
# missing provider as "not configured here" and simply offers no sign-in, which
# is exactly Stage 1's behaviour. See docs/oauth-instructions.html.
#
# Why a file rather than your shell profile: exporting secrets from .zshrc puts
# them in the environment of every process you ever run, and — specifically here
# — Gradle's JavaExec inherits the long-lived *daemon's* environment rather than
# the invoking shell's, so a rotated secret would keep resolving to the value the
# daemon started with until you killed it. lib/dev-server.sh converts these into
# per-invocation -P properties for that reason. See resolveValue() in
# OAuthConfig.kt.

# The repo root, derived from this file's own path rather than a caller's
# variable: scripts name their root differently, and under `set -u` depending on
# any one of their names would be a hard failure in the others. BASH_SOURCE is
# this file regardless of who sourced it.
LUNICLE_REPO_ROOT="${LUNICLE_REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"

# The providers the server knows how to configure. Named once, here, because two
# callers need the same list for two different mechanisms: lib/dev-server.sh
# turns each into a -P property, container-up.sh into a -e environment variable.
# Adding a provider in OAuthConfig.kt and forgetting one of those two is the
# drift this exists to prevent. Must match resolveOAuthConfig() in OAuthConfig.kt.
LUNICLE_OAUTH_VARS="GOOGLE_CLIENT_ID GOOGLE_CLIENT_SECRET GITHUB_CLIENT_ID GITHUB_CLIENT_SECRET"

# `set -a` exports every subsequent assignment, which is what makes a plain
# KEY=value file work as configuration without `export` on every line.
_load_env_file() {
  local env_file="$1"
  [[ -f "$env_file" ]] || return 0

  # Refuse a world/group-readable secrets file rather than quietly using it.
  # Cheap to check, and the failure it prevents is the kind you don't notice.
  local perms
  perms="$(stat -f '%OLp' "$env_file" 2>/dev/null || stat -c '%a' "$env_file" 2>/dev/null || echo '')"
  case "$perms" in
    ''|*00) ;;
    *) echo "warning: $env_file is mode $perms; consider: chmod 600 $env_file" >&2 ;;
  esac

  set -a
  # shellcheck disable=SC1090
  source "$env_file"
  set +a
}

_load_env_file "$LUNICLE_REPO_ROOT/.env"
