# Missing data: GIFT Nifty, FII/participant positioning, BANKNIFTY (branch feature/missing-data)

User, 14 Oct 2026 ~23:15: "for Missing create a new branch and do implement: GIFT Nifty and global cues before 09:15;
FII/participant derivatives positioning; BANKNIFTY", then review, fix and deploy locally. The gaps come from the 14 Oct
post-mortem (`docs/studies/2026-10-14-postmortem.md` §5).

## Sources (checked 15 Oct 00:20–00:40 IST)

| Data | Source | Access | Result |
|---|---|---|---|
| GIFT Nifty futures | NSE IX `https://www.nseix.com/api/market-rate?type=derivative`, the endpoint NSE IX's own site calls | plain GET, no login | 200 JSON: NIFTY FUTIDX per expiry, last price, day change and %, contracts, exchange time |
| Participant-wise OI | NSE archives `fao_participant_oi_DDMMYYYY.csv` | plain GET | 200 CSV on trading days, 404 otherwise (e.g. Sunday 11 Oct) |
| Global indices, US futures, crude, DXY | Yahoo Finance chart API | HTTP 429 | not used |
| (same) | Stooq | JavaScript bot verification | not used: getting around bot checks is not allowed |
| USD/INR | Upstox currency F&O (NCD_FO / BCD_FO) | Upstox feed | available, not added in this branch (it needs a feed subscription on GCP) |
| BANKNIFTY | Upstox (already in the universe, mapper, candles, lot size) | Upstox feed | recording re-enabled by config |

GIFT Nifty trades about 21 hours a day (two sessions), so its move after the Indian close already carries the US and
Asian sessions. It is the standard pre-09:15 proxy for global cues.

## What was built

1. **V20** adds two tables.
   - `ref.gift_nifty_quote`: one row per contract and exchange quote time, with `fetched_at` for point-in-time use.
   - `ref.participant_oi`: one row per session and participant type (Client, DII, FII, Pro, TOTAL), every published
     column.
2. **`ReferenceData`** gains two methods: `participantOi(session, n)`, which returns sessions strictly before `session`,
   and `giftNiftyQuotes(session)`, which reads from 09:00 four calendar days before. Both are implemented by
   `ReferenceStore` and delegated by `LiveReference` (live) and `BarSessionHistory` (bar replays).
3. **Features v13** (`config/features/features.v13.yaml` = v12 + `cues.enabled`) add a new `cues.*` section; v12 and
   older files give EMPTY.

   | Field | Meaning |
   |---|---|
   | `giftNiftyLast` | near-month GIFT Nifty futures price |
   | `giftNiftyChangePct` | change against the previous settlement, as NSE IX reports it |
   | `giftNiftyQuoteAgeMin` | minutes since the quote's exchange time; NaN past 12 hours |
   | `giftNiftyMovePct` | move from the last quote in Indian hours (09:15–15:30) on an earlier day: the overnight move |
   | `fiiIndexFutLongPct` | FII index futures long as % of long + short |
   | `fiiIndexFutLongPctChange` | its change from the session before |
   | `fiiIndexCallNet`, `fiiIndexPutNet` | FII index options, long minus short contracts |
   | `clientIndexFutLongPct`, `proIndexFutLongPct` | the same long % for clients and props |
   | `positioningAgeDays` | calendar days from the positioning session to this one |

   - A quote counts only from its `fetched_at`.
   - Positioning is always the previous published session; today's file is never used today.
   - No strategy reads these fields, so trades cannot change.
4. **Collection.**
   - One-shot commands: `--mode=fetch-cues` (GIFT Nifty once) and
     `--mode=fetch-participant-oi --from=D --to=D` (weekdays, 1 s apart; 404 means no file).
   - `CueScheduler`, only with `autotrade.cues.enabled=true` and only in auto or serve mode:
     - GIFT Nifty every 5 minutes, except 03:00–06:00 IST;
     - participant OI each weekday from 19:00 IST, retried every 30 minutes until 23:30 while the day's file is
       missing.
   - It is **off by default**, so merging this branch does not make GCP fetch anything.
5. **BANKNIFTY.** `underlyings: [NIFTY, SENSEX, BANKNIFTY]` records it; `trade-underlyings` stays NIFTY and SENSEX.
   - Every candidate's context now stores each other index's move from its open (`peerMovePct.<INDEX>`) and the cue
     values.
6. **API and UI.** `GET /api/cues` returns the v13 cues as of now (NaN as null), FII/Client/Pro/DII positioning for
   the last 10 sessions, and the collector state. The Live page has a "Pre-open cues" panel.

## Tests

| Test | What it checks |
|---|---|
| `CueStateTest` | Positioning is the previous published sessions, never today's. A GIFT quote is invisible before its fetch. A Monday measures from Friday's Indian hours, not from Saturday's night session. Stale quotes are ignored. |
| `ReferenceStoreCuesIT` (Testcontainers, real schema) | Upsert replaces; reads are strictly before the session; a duplicate quote is kept once; a Monday reads Friday's quotes. |
| `CueFetcherTest` | Parses the real 14 Oct NSE CSV (padded header names) and the real NSE IX JSON; the collector's timing rules. |

## Review findings fixed before the local deploy

- The overnight-move reference first took Saturday's 02:40 night quote on a Monday. It is now restricted to Indian
  hours (found by `CueStateTest`).
- The quote read window started at 15:30 three days back and so missed Friday's 15:29 for a Monday. It now starts at
  09:00 four days back (found by `ReferenceStoreCuesIT`).
- `LiveReference` and `BarSessionHistory` did not delegate the new methods, so live sessions and bar replays would have
  seen no cues. They now delegate.
- NaN values in the API response are written as null, so the JSON stays valid.
- The GIFT poll is based on elapsed time instead of the minute of the hour, so timer drift cannot skip a poll.

## Limits and next steps

- **History.** GIFT Nifty history is not available from NSE IX, so the cues exist only from the day collection starts.
  Participant OI history can be back-filled from NSE's archive (done locally from 1 Jul 2026).
- **Turning it on on GCP** needs three changes:
  - `AUTOTRADE_CUES_ENABLED=true`;
  - `features-file: features.v13.yaml` (no trade can change, but it is a new features version);
  - the BANKNIFTY recording. This adds about 50 option contracts and a constituent set to the one Upstox connection,
    which handled it from 28 Sep to 29 Sep. It is the user's decision.
- **No strategy uses the cues yet.** Any rule that does (for example, no opening-drive call when GIFT is down overnight
  and FII long % is below 20) is a new ledger row, tested on the days the data covers.
