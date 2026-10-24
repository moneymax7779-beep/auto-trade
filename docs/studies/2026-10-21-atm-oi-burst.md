# A-157 near-ATM OI bursts (21 Oct 2026) — PASS, thin

**Question (user).** Do sudden near-ATM OI changes over 1 / 3 / 5 minutes, strong relative to the day, alone or with a
level break and / or futures volume, predict the index's next 30 minutes? 15-minute and since-open OI are not used.
October own-capture tick days only: the stored bars carry option OI only every 3 minutes, so 1-minute windows need ticks.

**Definitions (ledger, before any result).** ATM ± 2 strikes, nearest expiry. share_w = (Δ put OI − Δ call OI over w
minutes) / the band's OI; burst when |share_w| ≥ k × the day's mean |share_w| so far (k = 3, 5, 8); positive = up.
Break = index crosses ORH / PDH up (ORL / PDL down) within the window; volume = futures volume in the window ≥ 1.5 × the
day's earlier windows' median. Entry at the next minute's close; outcome = index move in the burst's direction over 30
minutes. One event per 15 minutes per direction.

**Result.** Chosen on 1–9 Oct (where every variant was near zero): bursts over 3 minutes at 3× the day's typical change,
with futures volume ≥ 1.5×.

| | events | mean 30 min | hit | ATM option, 30 min |
|---|---|---|---|---|
| Selection 1–9 Oct | 33 | +3.7 bp | 52 % | +11.5 % |
| **Validation 12–19 Oct** | 25 | **+14.6 bp** (CI +7.9 … +24.6) | 84 % | +28.6 % |
| Same rule without OI (volume only) | 184 | +1.6 bp | 51 % | +2.5 % |
| 21 Oct (not counted) | 6 | +24.2 bp | 100 % | +24.2 % |

**Caveats.** Thin: 12 days. Selection was flat, so validation's strength reflects the 12–19 Oct trend days. NIFTY and
SENSEX often fire together (~15 independent signals). Fixed 30-minute exit, no stop, no costs. Level breaks added nothing
on their own (baselines negative) and are rare with bursts.

**Next.** A separately registered strategy with exactly these rules, replayed on the October tick days, then judged on
new days (shadow). No change to any live strategy.
