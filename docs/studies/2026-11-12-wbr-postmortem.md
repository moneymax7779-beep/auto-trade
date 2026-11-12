# Post-mortem: wall-break-rider on the 12 Nov 2026 SENSEX expiry

Sources (all read-only): own capture in the Mac DB (md.index_tick, md.future_tick, md.option_tick, 1-minute
aggregates in `.local/pm-wbr-1112/`), the 12 Nov live-set replay (session 35538, which matches live PAPER-1 trade for trade
on wbr), the wbr-v13 file and `WallBreakRider` / `OptionChainState` code on main, GCP's exec tables read on 12 Nov, and
zt-tiger-v2's independent capture (zt_archive.option_tick_snapshots) for the OI cross-check. Chart:
`2026-11-12-wbr-postmortem-1317.svg`.

## The rule (wbr-v13)

SENSEX 0DTE only, 10:30-14:30. Buy the ATM put when the "put floor" (the near-ATM put strike below spot with the best
score: OI share, 5-minute fresh OI and volume, weighted by closeness to spot) has lost >= 5 % of its OI over 10 minutes
while spot fell over the last 3 minutes, on 2 consecutive snapshots; futures OI not FRESH_LONG; no move > 4 ATR(3m) in
30 min; the floor >= 0.5 ATR below spot; ATM IV < 35 %. VWAP condition off (v13). Exits: 40 % premium stop, trail from
+100 %, a 3-minute close back through the wall after crossing it, flat 15:15.

## Trade 1: 13:17 SENSEX 81900 PE (PAPER-1 -1,60,387; SIGNAL-1 -99,204; ZERODHA-1 did not trade it, latched)

Day: open 81,808.8, low 81,585.9 (09:15), high 82,320.1 (10:01). By 13:17 the day's range was 2.02x the expected range.

| Data at entry (13:17) | Value | Reading |
|---|---|---|
| Spot | 81,855.4, 133 min to expiry | |
| Wall | 81800 PE (verified: the only put strike below spot with OI down >= 5 % at 13:16 and 13:17) | |
| Wall OI, 10-min change | 46.92 -> 42.92 lakh (-8.5 %) | the trigger |
| When the OI fell | one exchange print: 45.98 -> 41.14 lakh between 13:08 and 13:11 (zt shows the same at 13:12:06) | real, not a feed glitch |
| What spot did then | rallied 81,874 -> 81,963 (13:07-13:10); the 81800 put fell 135.8 -> 85 | puts were closed while the index moved AWAY from the floor |
| Wall OI by entry | already rebuilding: 41.14 (13:11) -> 41.75 (13:13) -> 42.92 (13:16) | the rule compares 10-minute endpoints, not the direction now |
| Spot move toward the wall | 81,963 (13:10) -> 81,855 (13:17), 3-min change -7.5 pts | weak |
| Ceiling | 82000 CE 67.1 lakh, the largest call OI near spot, rising | writers above |
| Futures (SENSEX 26 Nov) | RVOL 0.60, 0-40 contracts a minute, OI flat ~44,600, state NEUTRAL | no participation; the "not FRESH_LONG" check passed only because nothing traded |
| IV / premium | ATM IV 32 % (cap 35 %), ATM straddle 334, put 196.25 | needed a fast fall of ~200 points |
| Breadth | momentum -56, movers -79 | weak stocks, the one thing for the trade |
| Size (PAPER-1) | 101 lots = 2,020 x 196.25 = Rs 3.96 L premium, 49 % of Rs 8.1 L equity; 40 % stop = Rs 1.6 L = 20 % of equity | |

What happened (minute data):

| Time | Spot | 81800 PE OI | 82000 CE OI | 81900 PE bid | |
|---|---|---|---|---|---|
| 13:17 | 81,852 | 42.9 | 67.1 | 203.3 | entry 196.25 |
| 13:19 | 81,826 | 44.0 | 65.9 | 225.0 | best bid (+15 %); trail arms only at +100 % |
| 13:24 | 81,803 | 45.6 | 70.5 | 222.4 | lowest spot: 3 points above the wall, never crossed |
| 13:27 | 81,816 | 45.7 | 72.2 | 179.9 | spot flat, IV 34 % -> 27 %: premium bleeds |
| 13:38 | 81,901 | 50.8 | 74.3 | 154.3 | the floor rebuilt to above its pre-drop level |
| 13:39 | 81,933 | 50.8 | 74.3 | 116.5 | 40 % stop, filled 117.21 (-79 a unit) |

After the exit: spot 81,836-82,014 to the close, 81,911 at 15:30; the 81900 PE expired at 0.05 (best bid after the exit
153.2 at 14:31). Holding to expiry would have lost the whole premium; the stop limited it to 40 %.

## Trade 2: 11:14 SENSEX 82000 PE (ZERODHA-1 real money -10,118 net; SIGNAL-1 -4,182; PAPER-1 refused for capital)

Same pattern. Wall 82000 PE: OI 57.82 (11:01) -> 52.56 lakh (11:12), -9.1 %; spot 82,153 (11:08) -> 82,035 (11:13). The
wall was 35 points below spot (0.6 ATR). Within 11 minutes the put OI was back to 56.73 (11:25); spot dipped through 82000
only at 11:28 (81,999.8) and 11:31, and the strategy's own exit (wall re-crossed) sold at 225.0 at 11:30 (SIGNAL-1 -4,182).
ZERODHA-1 never reached that exit: bought 200 at 232.79 (the bid was 206.9 in the same minute: the 0DTE premium moved 25
points a minute) and its Rs 10 K open-loss limit sold both its positions at 11:18 (182.91).

## Why it lost (only what the data shows)

1. **The signal was one OI print that reversed.** In both trades the wall's OI drop came in a single exchange update and
   was rebuilt within 5-15 minutes (13:17: back to 50.8 lakh, above where it started; 11:14: back to 56.7). The rule
   checks a 10-minute endpoint change and does not ask whether the OI is still falling at entry.
2. **The 13:17 OI drop happened while SENSEX rallied away from the floor**, not while it pressed on it. Closing puts into
   a rally is not the "writers giving up the floor" the strategy assumes. The rule cannot tell the two apart: the 3-minute
   spot condition was met only later, by the dip that followed.
3. **No break, ever.** Spot came within 3 points of 81800 and never crossed it. The strategy buys the anticipation of a
   break (it requires the wall at least 0.5 ATR away), so it pays for a move that did not come.
4. **Expiry pin between two growing walls.** 81800 put OI rose 42.9 -> 50.8 lakh and 82000 call OI 67.1 -> 74.3 lakh
   during the trade; the index stayed between them until the close. With 2 hours to expiry, the put lost value on time
   and on IV (34 % -> 27 %) while spot was flat.
5. **No volume behind the move.** SENSEX futures RVOL 0.60, a few contracts a minute. The futures filter is close to
   meaningless on SENSEX because its futures barely trade.
6. **Size.** 20 % of PAPER-1's equity at risk on one 0DTE trade (A-376: full premium sizing risks ~17 % a trade).

Not established here: whether these failures are typical for wbr. On the expiry tick days through 9 Nov wbr earned the
most per lakh-hour of the live set (A-371); two losing trades on one day do not overturn that. A rule such as "the wall's
OI must still be falling at entry" or "enter only after a close through the wall" is a hypothesis to register and test on
the expiry tick days, not a conclusion from this day.
