---
name: strategy-improvement-loop
description: >-
  Runs the auto-trade strategy improvement loop end to end for one strategy (e.g. oi-burst-cross, ma-cross,
  wall-break-rider): replays its live and shadow versions alone on every own-capture tick day, takes full-page entry and
  exit screenshots of every trade, has a Fable 5.1 subagent research the screenshots and trades and propose changes,
  builds each proposal as a new version, replays them on the tick days, picks the best against the live version with the
  ledger's both-halves rules, and runs the winner on the 2026 Upstox bar days. Use it whenever the user asks to improve,
  tune, research, review, "find a better version of", or run deep research on a strategy's trades, or to replay a
  strategy's live and shadow versions with screenshots and suggestions, even if they do not name the loop.
---

# Strategy improvement loop

One strategy, five steps, each with a written record. The point is evidence the user can trust: every number comes
from an actual replay, every rule is registered in the ledger before its replay, and the result says plainly when a
change fails or rests on a single day. The user has repeatedly asked for verified, honest results; an improvement that
only fits the days it was found on is worse than no change, because it goes live.

## Models

- Steps 1, 2, 4, 5 and all judging run on **Opus 5.5 at high effort**. Check with
  `mcp__ccd_session_mgmt__get_session {"session_id":"self"}`; if this session is on another model or effort, say so
  and do the work in an `Agent` with `model: "opus"`, `effort: "high"` (the user asked for these explicitly).
- Step 3 (research) runs in an `Agent` with **`model: "fable"`** (Fable 5.1). It only reads and reports; you (Opus)
  turn its proposals into versions. Back on Opus for everything after.

## Before starting

- Read `CLAUDE.md` and the ledger header (`docs/EXPERIMENT-LEDGER.md`): register before computing, never rewrite a
  row, threshold files are versioned (a change is a new `vN` file), held-out sessions read once.
- Market hours (09:00–15:50 IST weekdays): no heavy replays; run in the evening or weekend.
- The main checkout may be shared with other sessions on other branches: add files to commits **by exact path**, never
  `commit -a`; put code changes in their own worktree (`git worktree add ../auto-trade-<tag> -b research/<tag> main`).
- Bar-day replays are allowed here only because this loop's step 5 asks for them; outside this loop ask first.
- Work folder: `.local/loop-<strategy>-<YYYYMMDD>/` (run dirs `tick-base/`, `tick-new/`, `bars/`; screenshots under
  `.local/screenshots/loop-<strategy>-<YYYYMMDD>/`). Next free ledger id: `grep -oE "^\| A-[0-9]+ " docs/EXPERIMENT-LEDGER.md | sort -t- -k2 -n | tail -1`.

Scripts live in this skill's `scripts/` (call them by absolute path):

| script | does |
|---|---|
| `find_versions.py <strategy>` | live and shadow files from `application.yml` on `main`, and the tick-day count |
| `replay_batch.sh OUT IMAGE CONFIG_DIR own\|bars JOBS [P]` | resumable parallel replays, each strategy alone, ₹5 L, risk v17 |
| `summarize.py OUT [--base LABEL] [--split DAY]` | totals, PF, max DD, halves, without-best-day, vs-base verdict, per-day nets, `trades.tsv` |
| `shot_jobs.py RUN_DIR SHOTS_DIR LABEL…` | screenshot job list (session pages + every trade's entry and exit) |
| `fullshots.mjs jobs.json [profile]` | full-page PNGs through headless Chrome; skips files that exist |

## Step 0 — image and identity

1. `find_versions.py <strategy>`; the live file(s) and shadow files are the base set.
2. Build one image from `main` (or reuse a tag already built from `main`'s code for this strategy). Building:
   `mvn -q -DskipTests -pl app-trading-core -am clean install` (**clean is required**: without it the image can carry a
   stale jar, which once made a whole batch trade nothing), then
   `AUTOTRADE_CODE_VERSION=<tag> docker compose -p autotrade -f docker-compose.yml build trading-core` from the
   worktree (the Dockerfile copies `ui/dist`; copy it from a running image with `docker cp` if the worktree has none).
   Verify the class you rely on is in the jar (`docker cp` the jar out, `unzip`, `javap`/`grep -a`).
3. Identity: replay the live version on one or two days for which an earlier run of the same code exists in
   `trade.session` / an earlier `progress.txt`, and require the same net. If no earlier run exists, say so in the ledger.

## Step 1 — replay live and shadows on all tick days

Jobs file: one line per version × tick day (`md.load_manifest` kind CAPTURE). Run in the background with
`run_in_background`, P = 3. When done, `summarize.py tick-base --base <live label>`. Register this step in the ledger
as descriptive (no decision). Check for `NO SUMMARY` lines and rerun until none are left (a day the replay calendar calls "not a trading day",
e.g. 1 Feb 2026, stays NO SUMMARY: read its log, then leave it out and say so). Always pass `--split <last day of the
first registered half>` to `summarize.py`; its default split may not match the halves you registered.

## Step 2 — screenshots

8095 must serve a UI build with the trade-moment links (`/sessions/<sid>?position=<id>&moment=entry|exit`); check one
link answers before starting. `shot_jobs.py tick-base <shots dir> <every label>`, then
`node fullshots.mjs <shots dir>/jobs.json <scratchpad>/chrome-profile` in the background. Spot-check two images
(Read them): the Trades table, stage changes and orders must be visible, not cut off.

## Step 3 — research (Fable 5.1)

Fill `references/research-brief.md` and launch one `Agent` with `model: "fable"`. Give it the strategy's earlier
ledger rows (grep the ledger for the strategy id and its short name; one line each with the result) so it does not
re-propose what failed. Ask for at most 6 proposals, one change each. While it works, do nothing that depends on it.
Save its report verbatim to `<work>/research.md`. If the agent's final message is only a one-line wrap-up, the report
is in its hand-back tool call in the session transcript JSONL; extract it from there rather than asking it again.

## Step 4 — build the proposals and replay them

1. Triage the report yourself (Opus): drop proposals that repeat a failed ledger row, need data the replay does not
   have, or are two changes in one. Keep at most 6. Write down why each dropped one was dropped.
2. Register **before any replay**: one ledger row for the step with a sub-row per version (values written out), and the
   decision rule (below). Commit the ledger by exact path.
3. Each kept proposal becomes a new `<strategy>.vN.yaml` (next free numbers) on top of the live version, one change
   each. Config-only when the key exists; otherwise code in the research worktree (default off when the key is absent,
   a unit test per new key), `mvn -q -pl app-trading-core -am clean install` (tests on), new image, verify the class.
4. Replay every new version alone on the same tick days into `tick-new/`, then
   `summarize.py tick-new --base <live label> --progress tick-base/progress.txt` so the live version's rows come from step 1.

**Decision rule (register it as written):** a version is a CANDIDATE only if, against the live version on the tick
days, it gains in **both halves** (first half = the earlier half of the days) **and** still gains in each half
without its single best day for the gain. Otherwise FAIL. Among candidates the best is the highest total net; ties
→ the simpler change. Everything here is in-sample (the researcher saw these days), so a candidate is a hypothesis for
step 5 and the forward record, never a live change by itself.

## Step 5 — 2026 bar days

1. Pick the best candidate. If none passed, take the version with the highest tick-day net and say clearly that it
   **failed** step 4 and the bar run is descriptive only.
2. Register the bar test before running: best version vs the live version, alone, `replay-source bars`, the 2026 bar
   days in `hist.candle` (for strategies that trade only on expiry days, only the NIFTY/SENSEX expiry dates). Rule: the
   version passes the bar screen if it beats the live version in both halves of the bar days and without its best
   day. Bars are coarse (A-045: totals within 9–31 %, 40–71 % of trades match), so a bar pass is a screen, not proof.
3. Run in the background (`bars/`, P = 3; ~1 min a replay), then `summarize.py bars --base <live label>`.

## Report

Record every result in the ledger (append a result row; never edit earlier rows) and commit it by exact path. Then
tell the user, in this order:
- what ran (days, versions, image, identity check);
- step 1 table (live and shadows) and the loss anatomy in two or three lines;
- the researcher's proposals: kept, dropped (why);
- step 4 table vs live with the verdict per version (both halves, without best day);
- step 5 bar result, labelled descriptive if no candidate passed;
- the recommendation (usually: keep live unchanged, or a forward arm / shadow for a candidate) and what it would take
  to go live. Nothing in this loop deploys anything; deploying is a separate decision for the user.
- where the screenshots, summaries and research report are.
