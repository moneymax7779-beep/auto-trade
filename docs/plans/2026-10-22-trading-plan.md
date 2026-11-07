# auto-trade — trading research and development plan (22 Oct 2026)

Built from three studies run on 22 Oct on our own data and the published evidence (full reports in
`docs/research/2026-10-22/`), the GCP live PAPER record, and the experiment ledger (A-001 … A-165).

- `day-anatomy.md`: 396 index-days (1-minute bars 1 Jan–6 Oct + own ticks 7–21 Oct), day types, 738+ impulse moves
  and their precursors, time-of-day profile, level breaks, 30-second microstructure.
- `loss-forensics.md`: all 81 trades of the current live set replayed on 25 tick days, with minute-level context at
  entry, MFE / MAE after entry, and loser paths.
- `market-research.md`: SEBI / NSE structure and studies, intraday evidence, option-buyer economics, compounding and
  sizing maths (≈ 60 sources, each claim tagged by evidence quality).

---

## 1. Where we actually stand

| Measure | Value | What it means |
|---|---|---|
| Live PAPER on GCP, 25 Sep–21 Oct (17 sessions) | **+₹56,493** on ₹5L; 6 winning days; best +₹5.35L, worst −₹5.17L | The only true forward record. Small net, huge day-to-day swings |
| Current live set replayed on 25 tick days (2 Sep–21 Oct) | +₹38.67L, PF 3.43, 81 trades | In-sample: these days shaped the rules |
| … of which etr's 6 winners | +₹27.17L (70 %) | The system is a trend-day catcher; everything else roughly pays for itself |
| Biggest single live losses | odm −₹3.17L, odb −₹1.83L, −₹1.25L | One trade can cost 35–60 % of starting capital |
| October regime | 41 % trend / gap-and-go days vs 28 % Jan–Sep | Our good replay numbers come from an unusually trendy month |

**The gap between replay (+₹38.7L) and live (+₹0.56L) is the most important number in this plan.** It comes from
(a) strategies kept because they did well on the days they are replayed on, (b) the live set changing during the
period, and (c) sizing that lets one trade decide a day. Until a change survives days it has never seen, it should be
treated as unproven.

## 2. What the market data says — our working model of NIFTY / SENSEX intraday

Each point is from our own data unless marked [lit].

1. **Direction is not predictable from state.** Over Jan–Sep no state variable (15-min momentum, trend since open,
   VWAP side, opening-range position, put−call OI flow, futures OI × price, breadth, VIX change) predicts the next
   30 minutes' sign by more than ±0.01 % of index; hit rates 48–53 %. This matches 160+ ledger tests (OI walls,
   writer flow, bursts on bars, PCR, pin, regime model, intraday momentum, gamma, order flow). [lit] SEBI: ~90 % of
   individual F&O traders lose, mostly option buyers; profits go to prop / algo desks and option sellers.
2. **Volatility is predictable; direction is not.** Impulses (≥ 0.35 % in ≤ 30 min) average 2.6 per index-day but
   0.7/day with VIX < 12 versus 5.1/day with VIX ≥ 17.5. Activity clusters: a wide last-15-minutes range makes an
   impulse 1.6–1.9× more likely; a **compressed** range makes it 0.45× as likely (compression does not lead to
   breakouts at 15–30 minutes). Consistent in Jan–May, Jun–Sep and October.
3. **The money is at the onset, not after confirmation.** Inside 738 impulses the right ATM option makes +36 % in the
   15 minutes after onset (+81 % on expiry days) but **+0.3 %** if bought when the move is "confirmed" at 0.35 %.
   Most of our entries are confirmation entries (breaks, crosses, bursts), which explains thin, regime-dependent edges.
4. **The day's structure is set early.** On 71 % of days one extreme is printed before 10:00; the largest swing starts
   in the first 15 minutes on 42 % of days (55 % of trend days). Opening strategies (odb, odm) sit where the money is;
   their problem is stop placement, not location.
5. **Level breaks mostly fail.** First closes beyond ORH/ORL fail 84–88 % of the time; previous-day levels 76–78 %.
   Breaks after 10:15 from a wide range on high volume do better (26 % extend 0.25 % vs 11 % random) — still small.
6. **Microstructure.** Futures order flow moves with price but leads only ~0.3 bp a minute (A-165). Before the largest
   impulses, flow points against the coming move and flips at onset. **The SENSEX index print lags NIFTY by up to
   30 seconds, but SENSEX option prices do not** — SENSEX entries triggered from the SENSEX index are late.
7. **Expiry (0DTE).** ATM straddles are fairly priced on average (no unconditional edge); theta takes ~18 % of a 0DTE
   ATM premium in 30 minutes at 14:00 [calc]; heavy near-ATM OI build-ups precede premium crush (−1 %/15 min).
   No pinning (A-154, [lit]). Expiry days are where etr made its money — trend afternoons, not pins.
8. **Who earns what [lit].** The variance risk premium in NIFTY options is earned overnight; intraday short-option
   returns are ~zero. An intraday option buyer can only win through direction/timing, net of spread, theta and costs.

## 3. Why we lose (loss forensics, in-sample)

| Cause | ₹ effect (25 days) | Evidence |
|---|---|---|
| **Sizing / concentration** | 10 worst trades = 61 % of gross losses; live −₹3.17L single trade | One idea at full-equity size; NIFTY + SENSEX same signal = double size (8 of 10 pairs same outcome; losing pairs −₹3.04L) |
| **etr adds into reversals** | Worst trade −₹1.98L would be −₹45K without adds; adds +₹15.8L on winners, −₹2.6L on losers | Every losing runner added within 1–4 minutes of entry |
| **odb stops at the exact level** | 8 of 8 losers stopped at the level, median 1 minute, 7 never green; the index then went +0.28 ADR their way | Zero-buffer stops in the opening noise |
| **mac losers** | −₹3.89L | Ordinary stop-outs; no entry filter beats chance (151-rule scan) — accept as the cost of the strategy |
| **Losers look like winners at entry** | — | Every entry feature separates winners from losers at 0.39–0.63 AUC (chance 0.5); the difference shows only after entry (first-30-min MFE) |

**Implication:** the fastest ₹ improvements are in **risk construction** (size, correlation, adds, stop buffers) and
**early-failure exits**, not in more entry filters.

## 4. The ₹10 crore target

₹5L → ₹10 cr by 31 Dec needs ~11.4 % per session for the ~49 sessions left. For scale [lit]: Renaissance Medallion
≈ 0.2 %/session gross; the best audited futures-contest year ≈ 1.9 %/session. A strong edge (45 % wins, 2 : 1 payoff)
compounds ~3 %/trade at full Kelly with ~50 % chance of a halving drawdown; betting above Kelly lowers growth.

| Compounded per session for 49 sessions | ₹5L becomes |
|---|---|
| 0.5 % | ₹6.4L |
| 1 % | ₹8.1L |
| 2 % | ₹13.2L |
| 3 % | ₹21.3L |
| 5 % | ₹54.6L |
| 11.4 % (the target) | ₹9.9 cr |

**Recommendation (your decision):** replace the end-date target with a risk-based objective — e.g. "positive monthly
P&L with ≤ 15–20 % drawdown, sized at ¼–½ Kelly of the out-of-sample edge", then scale capital when the forward
record supports it. Chasing 11 %/session forces bet sizes that make ruin likely; that is how the −₹5.17L day happened.

## 5. The plan — six workstreams

Rules for all of them: register every change in the ledger before computing; test on **bar days (large sample) and
tick days**, never on October alone; anything that passes goes to shadow on new days before live; live only by your
decision. Threshold changes are new versioned files.

### WS1 — Risk construction (highest ₹ impact, start now)
| # | Change | Test |
|---|---|---|
| 1.1 | **Risk per trade cap**: size so the loss at the stop / premium stop ≤ 1.5–2 % of equity (today up to ~35 %) | Replay the live set on 25 tick days + bar days with the cap; compare net, max drawdown, worst day |
| 1.2 | **Correlation cap**: same-direction NIFTY + SENSEX entries within 15 minutes count as one risk unit (half size each) | Same replays |
| 1.3 | **etr add rule**: no add before 10 minutes in and +X % open profit; adds never raise the loss at the stop above the first unit (pyramid risk cap exists, extend) | etr replays; bar days |
| 1.4 | **odb stop buffer**: stop beyond the level by k × 1-min ATR instead of at it | odb on 169 bar days (A-155 set) + tick days |
| 1.5 | **Early-failure exit** (data-driven, post-entry): exit if premium < −5 % at +10 min (etr) — the forensic finding that held in both halves | etr / mac replays; bar days |
| 1.6 | **Daily drawdown brake** on equity-scaled sizing (risk v17 lets the limit grow with equity) | Replays |

Output: risk v18 candidate, A/B replay report, your go / no-go.

### WS2 — Entry timing from the day-anatomy evidence
| # | Hypothesis (from day-anatomy §7) | Why | Test |
|---|---|---|---|
| 2.1 | **Compression veto**: no new long-option entry when the 15-min range ≤ 0.7× its trailing-20-day norm at that minute | Impulse odds 0.45×; straddle bleeds −0.44 %/15 min; consistent in all periods | Veto applied to every live / shadow strategy in bar + tick replays |
| 2.2 | **Volatility-regime gate**: thresholds and number of positions scaled by VIX at 09:15 / trailing realised range | 0.7 vs 5.1 impulses/day across VIX buckets | Bars: strategy P&L by VIX bucket, then σ-scaled thresholds |
| 2.3 | **SENSEX entries triggered from NIFTY** (execution) | SENSEX index print lags NIFTY ≤ 30 s, SENSEX options don't | Tick days: option ask at SENSEX trigger vs NIFTY-derived trigger |
| 2.4 | **No confirmation chasing**: no entry ≥ 0.25–0.35 % past the 10–30-min extreme | Confirmation entries earn +0.3 % | Bucket existing entries by distance from the extreme; bars |
| 2.5 | **Expiry-day OI-build veto** (15 min after near-ATM OI +6 % in 15 min) | Premium crush −1 %/15 min on expiry | Expiry trades, ≥ 8 new expiry days per index |

### WS3 — Holding trend days (where the money is)
- Keep the swing trail for etr (holding to 15:15 was worse in October, better in Sep — no change without evidence).
- Measure give-back on all winners on bar days; test a **two-stage trail** (wider until +X %, tighter after) per
  strategy, registered, on bars.
- aob-v2 / v4 and orb stay in shadow; judged after 15 new sessions (A-158 rule), no further October fitting.

### WS4 — New edge sources consistent with the evidence (decision needed)
1. **Onset entries**: the value is in the first minutes of an impulse. Candidate: activity precursors (wide range,
   futures volume, VIX moving) + order-flow flip at onset → enter on the first aligned 30-s bucket, tight time stop.
   Needs tick days; research first, registered.
2. **Defined-risk option selling on range regimes** (positive gamma, low VIX, compressed tape): the only consistently
   profitable participant group is option sellers [lit], and our data shows compression and low VIX are when buyers
   bleed. A hedged credit-spread / iron-fly strategy in PAPER would be a strategic change → your decision first.
3. **Strategy rotation by regime**: run trend strategies at full size only in high-activity regimes (WS2.2), reduce
   in compression.

### WS5 — Data and infrastructure
- **Slippage model from captured depth** (5-level book on every option tick): realistic fills by size and time — needed
  before any capital scaling (₹5 cr+ positions would need 18–37 sliced orders per side).
- **More history**: 1-minute bars for 2024–2025 (Upstox historical API; BarFetcher exists) to test every rule across
  regimes — today 188 days of one year (needs your approval; it is a large download).
- Capture continuity (futures ticks missing 28–29 Sep), daily data-quality check.
- Automated daily post-mortem (trade paths, MFE/MAE, missed impulses, regime) after each session.

### WS6 — Process discipline
- Every rule: bar days + tick days + forward shadow; a stated ₹ effect after costs; halves consistency.
- Stop fitting October: it is now used 5× (aob entry, exit, v3, v4, filters).
- Weekly review: live vs shadow vs replay; retire shadows that fail their rule.

## 6. Sequence (next two weeks)

| When | Work | Output |
|---|---|---|
| 22–23 Oct (after close) | WS1.1–1.2 (risk cap, correlation cap) registered and replayed on 25 tick days + bar days | risk v18 A/B report |
| 24–27 Oct | WS1.3–1.5 (etr adds, odb buffer, early-failure exit); WS2.1 compression veto on bars | ledger results |
| 28–30 Oct | WS2.2 VIX gate, WS2.3 SENSEX-from-NIFTY fill test, WS2.4 chasing | ledger results |
| 31 Oct | Review with you: what passes goes to shadow / risk v18 deploy decision | decisions |
| Ongoing | Shadows accumulate new days; daily post-mortem | 15-session reviews |
| On approval | WS4.2 option-selling research; WS5 history download | |

## 7. Decisions needed from you

1. Replace the ₹10 cr end-date target with a risk-based objective (section 4)?
2. Start WS1 (risk construction) as the first workstream?
3. Explore defined-risk option selling (WS4.2) in PAPER research?
4. Approve downloading 2024–2025 1-minute history (WS5)?
