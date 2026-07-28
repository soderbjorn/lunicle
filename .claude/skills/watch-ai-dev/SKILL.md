---
name: watch-ai-dev
description: Arm a background loop that runs /ai-dev on an interval. Default cadence 15m, configurable. One-shot starter — exits as soon as the loop is armed.
---

Arguments: $ARGUMENTS

Start a `/loop` that runs `/ai-dev` on a recurring cadence, then exit. This is a
**one-shot starter**, not a long-running command — the work happens inside the loop
this skill arms, not here. Stopping it is the user's business (interrupt the loop).

Each tick snapshots this repo's Lunicle "ready for AI development" column, claims
everything in it, and drives each ticket to a pull request in its own sibling
worktree. See `/ai-dev` for what a tick actually does.

## 1. Parse arguments

Split `$ARGUMENTS` into a **cadence token** and a **passthrough tail**:

- The first whitespace-separated token is the cadence if it looks like an interval
  (`15m`, `1h`, `45s`, `2h30m`) or is one of `auto` / `self-paced` / `dynamic` (all
  three mean "let the model pace itself").
- Everything after it is the tail, forwarded verbatim to `/ai-dev`.
- If the first token is not a cadence, the whole of `$ARGUMENTS` is the tail and the
  cadence is the default.

Default cadence: **15m**. Default tail: empty.

| `$ARGUMENTS` | Cadence | Tail forwarded |
|---|---|---|
| *(empty)* | `15m` | *(empty)* |
| `30m` | `30m` | *(empty)* |
| `1h --max 1` | `1h` | `--max 1` |
| `auto` | self-paced | *(empty)* |
| `--max 2` | `15m` | `--max 2` |

## 2. Arm the loop

Invoke the `loop` skill via the Skill tool:

- Fixed cadence → args `<cadence> /ai-dev <tail>`
- Self-paced → args `/ai-dev <tail>` (no interval)

Trim the trailing space when the tail is empty. Do not wrap the call in another
layer — calling `/loop` directly is this skill's entire job.

## 3. Report and exit

Print exactly one line:

```
Armed: /loop 15m /ai-dev — first tick scheduled; interrupt the loop to stop it.
```

or, self-paced:

```
Armed: /loop /ai-dev (self-paced) — the model picks each interval; interrupt the loop to stop it.
```

Then stop. Do **not** run the first tick yourself — the loop's own wake-up does that.

## 4. Guard rails

- If a `/loop` is already running `/ai-dev` in this session, do not arm a second
  one. Two loops would both snapshot the same column and dispatch the same tickets
  twice. Report that it is already armed and exit.
- Warn inline if the cadence is under 5 minutes: one `/ai-dev` tick routinely takes
  longer than that, since it builds and verifies real changes.
- Touch nothing else. No `git`, no worktrees, no `gh`, no Lunicle MCP writes — all
  of that belongs to the ticks.
