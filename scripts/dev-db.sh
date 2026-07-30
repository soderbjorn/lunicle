#!/usr/bin/env bash
#
# The local volume: inspect it, seed it, wipe it.
#
#   ./scripts/dev-db.sh path              # where it is
#   ./scripts/dev-db.sh inspect           # tables and row counts
#   ./scripts/dev-db.sh dump [table]      # everything, or one table
#   ./scripts/dev-db.sh sql "SELECT …"    # ad-hoc query
#   ./scripts/dev-db.sh seed              # a project with issues to look at
#   ./scripts/dev-db.sh admin             # seat yourself at the top of the ladder
#   ./scripts/dev-db.sh wipe              # delete it; next run recreates it
#
# ── Why the database lives at ~/.lunicle ─────────────────────────────────────
#
# It has run away from two homes, for two different reasons, and both are worth
# knowing before you move it again.
#
# It was at server/build/lunicle.db, so that `./gradlew clean` discarded it.
# That was right when the payload was a counter and wiping cost nothing. It is
# wrong now: `clean` is something you run to fix a build problem, and it would
# take every issue you had typed in with it — silently, as a side effect of an
# unrelated command.
#
# Then it was at .localdata/ in the repo root, gitignored. Better, but still
# your real data sitting inside a checkout: it goes with any `rm -rf` of the
# clone, a second clone starts empty, and one gitignore line is all that keeps
# it out of a commit.
#
# So it lives in your home directory, where your data already lives, and wiping
# is a thing you ask for by name, with this script. The `create` path is still
# worth exercising — it is the one code path production runs exactly once, on a
# fresh volume, where getting it wrong is most expensive — which is what `wipe`
# is for.
#
# ── What this is standing in for ─────────────────────────────────────────────
#
# Railway mounts a volume at $RAILWAY_VOLUME_MOUNT_PATH and the server puts
# lunicle.db and attachments/ inside it (see Database.kt). Locally there is no
# Railway, so a local run takes the `lunicle.databasePath` branch instead —
# defaulted to ~/.lunicle by server/build.gradle.kts, or pointed elsewhere with
# LUNICLE_LOCAL_DATA, which this script and the run-dev-*.sh scripts both read. Same directory layout, same create and
# migrate paths, same orphan sweep — a real directory on your disk rather than a
# mount. That is the whole trick, and it is why you can test all of this without
# building a container.
#
set -euo pipefail

# Kept in step with the databasePath default in server/build.gradle.kts — this
# script and the server must resolve the same directory or `inspect` reports on
# a file the server has never opened. Override to keep several databases around
# — say, one mid-migration and one fresh. Note the default is per-USER, not
# per-checkout: two clones share it unless you say otherwise here.
LOCAL_DATA_DIR="${LUNICLE_LOCAL_DATA:-$HOME/.lunicle}"
DB_PATH="$LOCAL_DATA_DIR/lunicle.db"
ATTACHMENTS_DIR="$LOCAL_DATA_DIR/attachments"

have_sqlite() { command -v sqlite3 > /dev/null 2>&1; }

require_sqlite() {
  if ! have_sqlite; then
    echo "error: sqlite3 is not installed." >&2
    echo "       macOS ships it; otherwise: brew install sqlite" >&2
    exit 1
  fi
}

require_db() {
  if [[ ! -f "$DB_PATH" ]]; then
    echo "error: no database at $DB_PATH" >&2
    echo "       Start the server once (./scripts/run-dev.sh) — it creates the schema on boot." >&2
    exit 1
  fi
}

# Refuse to touch a database the server has open.
#
# SQLite would *let* us: it takes the lock, our write succeeds, and the running
# server carries on with its own view of things. The damage is quiet — a seeded
# project that the server does not show until it restarts, or a wipe that leaves
# the server holding a deleted file's handle and writing into nowhere. Both
# present as "the script did nothing", which is the worst way to spend an hour.
refuse_if_running() {
  if curl -sf -o /dev/null "http://localhost:${LUNICLE_PORT:-8080}/api/session" 2>/dev/null; then
    echo "error: the server is running on :${LUNICLE_PORT:-8080} and has this database open." >&2
    echo "       Stop it first (Ctrl-C in the run script's window), then re-run." >&2
    exit 1
  fi
}

cmd_path() {
  echo "database:    $DB_PATH"
  echo "attachments: $ATTACHMENTS_DIR"
  if [[ -f "$DB_PATH" ]]; then
    echo "size:        $(du -h "$DB_PATH" | cut -f1)"
    local count
    count=$(find "$ATTACHMENTS_DIR" -type f 2>/dev/null | wc -l | tr -d ' ')
    echo "files:       $count attachment(s)"
  else
    echo "state:       does not exist yet — start the server once"
  fi
}

cmd_inspect() {
  require_sqlite; require_db
  echo "== $DB_PATH"
  echo
  # user_version is what decides create-vs-migrate on the next boot, so it is
  # the first thing worth seeing. See createOrMigrateSchema() in Database.kt.
  echo "schema version (user_version): $(sqlite3 "$DB_PATH" "PRAGMA user_version;")"
  echo
  printf '%-20s %s\n' "TABLE" "ROWS"
  printf '%-20s %s\n' "─────" "────"
  for table in $(sqlite3 "$DB_PATH" \
      "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name;"); do
    printf '%-20s %s\n' "$table" "$(sqlite3 "$DB_PATH" "SELECT count(*) FROM \"$table\";")"
  done
  echo
  # No `2>/dev/null` on any of the queries below, and that is the point of this
  # note. They used to be silenced, and it cost real time: `is_public` was dropped
  # by 33.sqm and `is_admin` by 11.sqm before it, so two of these sections printed
  # their heading and then nothing at all — which reads as "this database is empty"
  # rather than "this script is asking for a column that no longer exists". A
  # schema drift here should be loud, because it means this file has fallen behind
  # the migrations and every other section is suspect too.
  echo "== Projects"
  sqlite3 -header -column "$DB_PATH" \
    "SELECT id, name, name_prefix AS prefix, position AS pos FROM projects ORDER BY position, id;"
  echo
  echo "== Who each project admits"
  # The audience rows: at most three per project (guest | member | staff), each
  # naming the rung that audience arrives on. This replaced the two booleans
  # `is_public` and `visible_to_all_signed_in` in 33.sqm. A project with no rows
  # here and no own-row grants below is invisible to everybody but an instance
  # administrator — which is what every project is immediately after that
  # migration, deliberately.
  sqlite3 -header -column "$DB_PATH" "
    SELECT p.name_prefix AS project, a.audience, a.role AS rung
    FROM project_audience_roles a
    JOIN projects p ON p.id = a.project_id
    ORDER BY p.id, a.audience;"
  echo
  echo "== Own-row grants"
  # The exceptions to the audience rows. One rung per person per project; the
  # effective rung is max(audience rows they match, this row), so a row here can
  # only ever raise somebody. See AccessControl.effectiveRole.
  sqlite3 -header -column "$DB_PATH" "
    SELECT p.name_prefix AS project, u.provider_name AS who, r.role AS rung
    FROM project_roles r
    JOIN projects p ON p.id = r.project_id
    JOIN users u ON u.id = r.user_id
    ORDER BY p.id, u.id;"
  echo
  echo "== Users"
  # The email column is deliberately not selected: it never crosses the wire,
  # and it does not need to be on your terminal either.
  #
  # `kind` is the staff/member split, derived from the address against the
  # deployment's own domain and re-derived on every boot — never written by hand.
  # `instance_role` is only ever 'admin' or NULL. Neither column says who OWNS the
  # instance: that is a single row in instance_settings, printed below, because
  # "exactly one owner" is a setting rather than a third enum value.
  #
  # `signed_in` is what tells a real account apart from a placeholder somebody
  # typed into an Access list for an address that has never arrived.
  sqlite3 -header -column "$DB_PATH" "
    SELECT id, provider, provider_name, display_name, kind,
           coalesce(instance_role, '') AS instance_role,
           CASE WHEN signed_in_at IS NULL THEN 'no' ELSE 'yes' END AS signed_in
    FROM users ORDER BY id;"
  echo
  echo "== Instance"
  sqlite3 -header -column "$DB_PATH" "
    SELECT s.key,
           CASE WHEN s.key = 'owner_user_id'
                THEN s.value || ' (' || coalesce((SELECT provider_name FROM users WHERE id = CAST(s.value AS INTEGER)), 'no such account') || ')'
                ELSE s.value END AS value
    FROM instance_settings s ORDER BY s.key;"
  echo
  echo "== Issues"
  sqlite3 -header -column "$DB_PATH" "
    SELECT p.name_prefix || '-' || i.number AS ticket,
           substr(i.title, 1, 40) AS title,
           s.name AS status,
           i.is_draft AS draft
    FROM issues i
    JOIN projects p ON p.id = i.project_id
    JOIN statuses s ON s.id = i.status_id
    ORDER BY i.project_id, i.number;" 2>/dev/null
}

cmd_dump() {
  require_sqlite; require_db
  if [[ $# -gt 0 ]]; then
    sqlite3 -header -column "$DB_PATH" "SELECT * FROM \"$1\";"
  else
    sqlite3 "$DB_PATH" .dump
  fi
}

cmd_sql() {
  require_sqlite; require_db
  [[ $# -gt 0 ]] || { echo "usage: $0 sql \"SELECT …\"" >&2; exit 2; }
  sqlite3 -header -column "$DB_PATH" "$1"
}

# Put every account at the top of the instance ladder, and seat the first one as
# the owner.
#
# The first person to sign in becomes the instance administrator automatically
# (see the upsert in Users.sq) and the next boot seats them as owner (see
# seatInstanceOwner), so this is only needed when you have already signed in as
# someone else first — or when you want a second account able to reach the
# instance tabs without going through a grant.
#
# ── Two writes, because the ladder is stored in two places ───────────────────
#
# This wrote `users.is_admin = 1` until the permission rework, and had in fact
# been broken since 11.sqm renamed that column — long enough that nobody noticed
# the command was dead. The replacement cannot be one write, and the reason is
# structural rather than incidental:
#
#   * `users.instance_role` is the ADMIN rung. Only ever 'admin' or NULL.
#   * `instance_settings.owner_user_id` is the OWNER rung, and it is a setting
#     rather than a third value of that column so that "exactly one owner" needs
#     no partial unique index — which Firestore has no equivalent of. See
#     Users.sq's comment on the column.
#
# The distinction now matters to what you can test. An instance administrator
# reaches the instance tabs and the account directory; the OWNER alone may delete
# projects across the deployment, attribute writes over MCP, send agent mail,
# delete an attachment and impersonate. So this seats one owner as well as raising
# everybody — otherwise half the interesting screens stay out of reach.
cmd_admin() {
  require_sqlite; require_db; refuse_if_running
  local count
  count=$(sqlite3 "$DB_PATH" "SELECT count(*) FROM users;")
  if [[ "$count" -eq 0 ]]; then
    echo "There are no users yet. Sign in once — the first user to do so becomes the instance administrator."
    exit 0
  fi
  sqlite3 "$DB_PATH" <<SQL
BEGIN;
UPDATE users SET instance_role = 'admin';
INSERT INTO instance_settings (key, value)
VALUES ('owner_user_id', (SELECT CAST(id AS TEXT) FROM users ORDER BY id LIMIT 1))
ON CONFLICT (key) DO UPDATE SET value = excluded.value;
COMMIT;
SQL
  echo "Every account is now an instance administrator ($count), and the first one owns the instance:"
  sqlite3 -header -column "$DB_PATH" "
    SELECT u.id, u.provider, u.provider_name, u.kind, u.instance_role,
           CASE WHEN CAST(s.value AS INTEGER) = u.id THEN 'yes' ELSE '' END AS owner
    FROM users u
    LEFT JOIN instance_settings s ON s.key = 'owner_user_id'
    ORDER BY u.id;"
  echo
  echo "Sign out and back in if the UI is already open — the rung rides on the session lookup."
}

# A project with issues in it, so there is something to look at.
#
# Deliberately NOT part of the server's startup. Seeding real content on boot
# would mean production came up with a fake project in it, and the only thing
# stopping that would be an environment check nobody remembers to write.
cmd_seed() {
  require_sqlite; require_db; refuse_if_running

  if [[ "$(sqlite3 "$DB_PATH" "SELECT count(*) FROM projects WHERE name = 'Lunamux';")" != "0" ]]; then
    echo "There is already a project called \"Lunamux\". Nothing to do."
    echo "(./scripts/dev-db.sh wipe, then start the server, to begin again.)"
    exit 0
  fi

  local now
  now=$(( $(date +%s) * 1000 ))

  # The vocabularies are seeded exactly as ProjectRepository.create does it —
  # same names, same order, same positions. If you change the defaults there,
  # change them here: this script is a stand-in for that transaction, not a
  # second definition of it.
  sqlite3 "$DB_PATH" <<SQL
BEGIN;
INSERT INTO projects (name, name_prefix, created_at)
VALUES ('Lunamux', 'LMX', $now);

-- Who the board admits, which used to be \`is_public, 1\` on the row above.
--
-- 33.sqm replaced the two visibility booleans with audience rows, and the reason
-- is worth knowing before changing this line: a row says at what RUNG an audience
-- arrives, so "the world may read this" and "the world may comment on this" are
-- the same mechanism rather than a boolean plus a feature request.
--
-- \`member → contributor\` rather than the \`guest → viewer\` that \`is_public = 1\`
-- literally became. Two reasons, and the second is the important one:
--
--   * Contributor is what makes a seeded board worth opening — you can file,
--     move and comment on the issues below rather than only look at them.
--   * A \`guest\` row is vetoed by the instance's \`allow_public_projects\` switch,
--     which is OFF by default. Writing one here in SQL would slip past a veto the
--     UI enforces, leaving a board that is public while the screen that governs
--     it says public boards are not allowed. That is exactly the "two answers to
--     who can see this, one of them enforced" state the audience table exists to
--     retire, and a seed script has no business creating it.
--
-- To make it genuinely public, turn the instance switch on first and then set the
-- guest row from the project's Access section — in that order, through the UI
-- that checks it.
INSERT INTO project_audience_roles (project_id, audience, role)
SELECT id, 'member', 'contributor' FROM projects WHERE name = 'Lunamux';

-- An owner for the board, so it is administrable by somebody.
--
-- Every project gets one on a migrated volume (33.sqm's second exception, "no
-- board is unadministrable"), so a seeded board without one would be the only
-- project on the instance in a state the migration goes out of its way to
-- prevent. Whoever signed in first, or nobody if the table is still empty — in
-- which case run \`$0 admin\` after your first sign-in and an instance
-- administrator reaches it that way instead.
INSERT INTO project_roles (user_id, project_id, role)
SELECT u.id, p.id, 'owner'
FROM projects p, (SELECT id FROM users ORDER BY id LIMIT 1) AS u
WHERE p.name = 'Lunamux';

INSERT INTO labels (project_id, name)
SELECT id, value FROM projects, (
  SELECT 'Bug' AS value UNION ALL SELECT 'Feature' UNION ALL
  SELECT 'Improvement' UNION ALL SELECT 'Codebase'
) WHERE projects.name = 'Lunamux';

INSERT INTO components (project_id, name)
SELECT id, value FROM projects, (
  SELECT 'Desktop' AS value UNION ALL SELECT 'Server' UNION ALL
  SELECT 'Android' UNION ALL SELECT 'iOS'
) WHERE projects.name = 'Lunamux';

-- Keep this list in step with DEFAULT_STATUSES in ProjectRepository.kt. It is a
-- copy, and copies drift: a status renamed there and not here seeds a local
-- board that no longer matches a real one, which is the opposite of what a seed
-- script is for.
-- requires_resolution marks the "magic" closing column — see Statuses.sq. It is
-- data, not a name match: the running server never keys this rule on 'Closed'.
INSERT INTO statuses (project_id, name, position, requires_resolution)
SELECT id, value, pos, req FROM projects, (
  SELECT 'New' AS value, 0 AS pos, 0 AS req UNION ALL
  SELECT 'Backlog', 1, 0 UNION ALL
  SELECT 'Ready for development', 2, 0 UNION ALL
  SELECT 'In progress', 3, 0 UNION ALL
  SELECT 'Ready for test', 4, 0 UNION ALL
  SELECT 'Closed', 5, 1
) WHERE projects.name = 'Lunamux';

-- Keep in step with DEFAULT_PRIORITIES in ProjectRepository.kt, as above.
-- Highest first: position 0 is "Very high". See Priorities.sq.
INSERT INTO priorities (project_id, name, position)
SELECT id, value, pos FROM projects, (
  SELECT 'Very high' AS value, 0 AS pos UNION ALL
  SELECT 'High', 1 UNION ALL
  SELECT 'Normal', 2 UNION ALL
  SELECT 'Low', 3 UNION ALL
  SELECT 'Very low', 4
) WHERE projects.name = 'Lunamux';

-- Keep in step with DEFAULT_RESOLUTIONS in ProjectRepository.kt.
INSERT INTO resolutions (project_id, name, position)
SELECT id, value, pos FROM projects, (
  SELECT 'Done' AS value, 0 AS pos UNION ALL
  SELECT 'Will not fix', 1 UNION ALL
  SELECT 'Duplicate', 2
) WHERE projects.name = 'Lunamux';
COMMIT;
SQL

  # Issues, spread across columns so the board has something to show. Authored
  # by whoever signed in first, or by nobody — a null author is a real state
  # (the account was deleted), and it is worth seeing it render.
  sqlite3 "$DB_PATH" <<SQL
BEGIN;
INSERT INTO issues (project_id, number, title, description, status_id, priority_id, is_draft, created_at, updated_at, created_by)
SELECT p.id, 1, 'Unable to remove user',
       'Removing a user from a project **fails silently**. Steps:

- Open project settings
- Remove a user
- Nothing happens

See the <u>console</u> for the 403.',
       (SELECT id FROM statuses WHERE project_id = p.id AND name = 'New'),
       (SELECT id FROM priorities WHERE project_id = p.id AND name = 'Normal'),
       0, $now, $now, (SELECT id FROM users ORDER BY id LIMIT 1)
FROM projects p WHERE p.name = 'Lunamux';

INSERT INTO issues (project_id, number, title, description, status_id, priority_id, is_draft, created_at, updated_at, created_by)
SELECT p.id, 2, 'Board columns should be reorderable',
       'Dragging a *column* should reorder it, not just the cards in it.',
       (SELECT id FROM statuses WHERE project_id = p.id AND name = 'Backlog'),
       (SELECT id FROM priorities WHERE project_id = p.id AND name = 'Normal'),
       0, $now, $now, (SELECT id FROM users ORDER BY id LIMIT 1)
FROM projects p WHERE p.name = 'Lunamux';

INSERT INTO issues (project_id, number, title, description, status_id, priority_id, is_draft, created_at, updated_at, created_by)
SELECT p.id, 3, 'Attachment sweep runs on every boot',
       'Confirm the startup sweep only removes files with no matching storage_key.',
       (SELECT id FROM statuses WHERE project_id = p.id AND name = 'In progress'),
       (SELECT id FROM priorities WHERE project_id = p.id AND name = 'Normal'),
       0, $now, $now, (SELECT id FROM users ORDER BY id LIMIT 1)
FROM projects p WHERE p.name = 'Lunamux';

INSERT INTO issues (project_id, number, title, description, status_id, priority_id, resolution_id, is_draft, created_at, updated_at, created_by)
SELECT p.id, 4, 'Ship the volume', 'Done — see docs/volume-instructions.html.',
       (SELECT id FROM statuses WHERE project_id = p.id AND name = 'Closed'),
       (SELECT id FROM priorities WHERE project_id = p.id AND name = 'Normal'),
       -- Closed, so it must have one: the column's requires_resolution is set.
       (SELECT id FROM resolutions WHERE project_id = p.id AND name = 'Done'),
       0, $now, $now, NULL
FROM projects p WHERE p.name = 'Lunamux';

-- A draft, to prove it stays off the board. It is invisible in the UI; you can
-- only see it here or in \`inspect\`.
INSERT INTO issues (project_id, number, title, description, status_id, priority_id, is_draft, created_at, updated_at, created_by)
SELECT p.id, 5, 'A draft nobody finished', 'If you can see this on the board, is_draft is being ignored.',
       (SELECT id FROM statuses WHERE project_id = p.id AND name = 'New'),
       (SELECT id FROM priorities WHERE project_id = p.id AND name = 'Normal'),
       1, $now, $now, NULL
FROM projects p WHERE p.name = 'Lunamux';

-- Imported history: an author with no account, which is the other real state
-- worth seeing render. The card should say "octocat" and be uneditable below
-- Maintainer on the board — there is no account for it to belong to, so the "your
-- own issue as a Contributor" half of canEditIssue can never reach it. Note
-- created_by is NULL and must stay that way; the CHECK forbids the pair.
INSERT INTO issues (project_id, number, title, description, status_id, priority_id, is_draft, created_at, updated_at, created_by, created_by_external)
SELECT p.id, 6, 'Imported from GitHub', 'Filed by somebody who has never signed in here.',
       (SELECT id FROM statuses WHERE project_id = p.id AND name = 'Backlog'),
       (SELECT id FROM priorities WHERE project_id = p.id AND name = 'Normal'),
       0, $now, $now, NULL, 'octocat'
FROM projects p WHERE p.name = 'Lunamux';
COMMIT;
SQL

  # Labels and components on the first two issues. The join carries project_id
  # redundantly on purpose — the composite foreign keys need it, and it is what
  # makes another project's label unstorable. See IssueLabels.sq.
  sqlite3 "$DB_PATH" <<SQL
BEGIN;
INSERT INTO issue_labels (issue_id, label_id, project_id)
SELECT i.id, l.id, i.project_id
FROM issues i JOIN labels l ON l.project_id = i.project_id
WHERE i.number = 1 AND l.name = 'Bug' AND i.project_id = (SELECT id FROM projects WHERE name = 'Lunamux');

INSERT INTO issue_components (issue_id, component_id, project_id)
SELECT i.id, c.id, i.project_id
FROM issues i JOIN components c ON c.project_id = i.project_id
WHERE i.number = 1 AND c.name = 'Server' AND i.project_id = (SELECT id FROM projects WHERE name = 'Lunamux');

INSERT INTO issue_labels (issue_id, label_id, project_id)
SELECT i.id, l.id, i.project_id
FROM issues i JOIN labels l ON l.project_id = i.project_id
WHERE i.number = 2 AND l.name = 'Feature' AND i.project_id = (SELECT id FROM projects WHERE name = 'Lunamux');
COMMIT;
SQL

  echo "Seeded \"Lunamux\" (LMX) with 5 issues and 1 invisible draft."
  # Two sentences, because the second is only true when there was an account to
  # seat. Saying "the first account owns the board" on an empty table would be the
  # script asserting a row it had just declined to write.
  if [[ "$(sqlite3 "$DB_PATH" "SELECT count(*) FROM project_roles WHERE role = 'owner';")" == "0" ]]; then
    echo "Members arrive as Contributors. Nobody owns the board yet."
  else
    echo "Members arrive as Contributors; the first account owns the board."
  fi
  echo "LMX-6 is imported history: its author is a name, not an account."
  echo
  # Foreign keys are OFF by default in the sqlite3 CLI — unlike the server,
  # which sets the pragma on every connection. So this checks that the rows just
  # written would have been accepted by the running server, rather than assuming.
  local violations
  violations=$(sqlite3 "$DB_PATH" "PRAGMA foreign_key_check;" | wc -l | tr -d ' ')
  if [[ "$violations" != "0" ]]; then
    echo "warning: the seed left $violations foreign key violation(s):" >&2
    sqlite3 "$DB_PATH" "PRAGMA foreign_key_check;" >&2
    exit 1
  fi
  echo "Foreign keys check out. Start the server and pick Lunamux in the picker."
  echo
  # This note used to say "nobody has a role in this project yet, so a signed-in
  # non-admin can read it and nothing more". Both halves stopped being true: with
  # no audience row and no own row, effectiveRole returns null and such an account
  # cannot see the board AT ALL — reading is the bottom rung rather than the
  # absence of one. The seed writes the member row above so that it can say
  # something true here instead.
  if [[ "$(sqlite3 "$DB_PATH" "SELECT count(*) FROM project_roles WHERE role = 'owner';")" == "0" ]]; then
    echo "Note: nobody has signed in yet, so the board has no owner row. Sign in once,"
    echo "then:  $0 admin   — which seats you at the top of the instance ladder."
  fi
}

cmd_wipe() {
  refuse_if_running
  if [[ ! -e "$LOCAL_DATA_DIR" ]]; then
    echo "Nothing to wipe — $LOCAL_DATA_DIR does not exist."
    exit 0
  fi
  echo "This deletes $LOCAL_DATA_DIR — the database and every attachment in it."
  printf 'Type "wipe" to confirm: '
  read -r answer
  if [[ "$answer" != "wipe" ]]; then
    echo "Left alone."
    exit 0
  fi
  rm -rf "$LOCAL_DATA_DIR"
  echo "Gone. The next server start creates the schema from nothing —"
  echo "which is the code path a fresh Railway volume takes, so it is worth watching."
}

# The usage block only: line 2 down to the first `── section ──` heading, which
# is where the rationale starts. It used to be the fixed range 2,28 — which
# spilled paragraphs of history onto the terminal of anyone who typed --help,
# and truncated mid-sentence the moment the header above was edited. A line
# number cannot know where the usage ends; the heading does.
usage() {
  sed -n '2,/^# ──/p' "${BASH_SOURCE[0]}" | sed '$d' | sed 's/^# \{0,1\}//'
}

case "${1:-}" in
  path)    cmd_path ;;
  inspect) cmd_inspect ;;
  dump)    shift; cmd_dump "$@" ;;
  sql)     shift; cmd_sql "$@" ;;
  seed)    cmd_seed ;;
  admin)   cmd_admin ;;
  wipe)    cmd_wipe ;;
  ""|-h|--help|help) usage ;;
  *) echo "unknown command: $1" >&2; echo >&2; usage >&2; exit 2 ;;
esac
