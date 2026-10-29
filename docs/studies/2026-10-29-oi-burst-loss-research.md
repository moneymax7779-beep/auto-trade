# OI-burst losses: overnight research, 29 Oct 2026 (A-266)

The user asked (01:20 IST) to find why oba / obc lose, test every combination of time windows, OI support, levels and
S&R, entry and exit timing, and use outside research. Tick days only. Protocol registered before the search (ledger
A-266): choose on one half, confirm on the other, on both strategies, robust to the best day; engine replays for the
survivors; the HELD_OUT days 22–25 Sep (+ 28 Oct) read once at the end. Scripts and outputs: `.local/a266/`.

## Why the trades lose (oba-v1, 47 losers, −₹24.75 L)

| First matching cause | Trades | Net |
|---|---|---|
| Re-entry after a loss the same day | 14 | −₹7.86 L |
| Futures OI fresh build-up in the trade's direction | 8 | −₹5.72 L |
| Chased a ≥ 0.4 % opening gap in its own direction | 11 | −₹4.92 L |
| Stopped within 15 minutes (never worked) | 7 | −₹4.27 L |
| Counter-burst exit | 5 | −₹1.05 L |
| Index moved against later | 2 | −₹0.93 L |

95 % of the loss amount is the index moving against the trade (not theta, not fills: A-259, A-263).

## What was tried (about 42,100 rules)

293 single conditions, 41,771 pairs, 10 threshold families chosen both ways, 14 entry-timing / level-exit variants,
10 rules from a web review (5 ideas), 3 stop-after-loss rules. A shuffle test calibrates chance: single rules qualify
by chance 0–1 times (real 3), pairs 36–112 times (real 630, mostly the futures-OI and gap themes).

| Idea (user's or the review's) | Result |
|---|---|
| Skip time windows (first N minutes, after 10:00 / 11:00 / 13:00) | Fails: removes winners in one half |
| Need supporting OI (call/put OI z, OI change, writer- vs buyer-led) | Fails |
| Skip if a level / S&R / round number is near ahead | Fails (−₹6 to −15 L per half) |
| Already beyond PDH/PDL, chase (3-min move), quiet-day range | Fail |
| Price on the trade's side of the previous close | Fails (−₹7.6 to −10.7 L): trades toward the gap fill are the winners |
| Enter later / on a pullback | Fails (−₹0.5 to −32 L): winners do not pull back |
| Exit at the next level / when the level behind breaks / trail on half-hours | Fails |
| Stop for the day after one or two losses | Fails (H1 +, H2 −) |
| Futures-OI fresh build-up veto (A-265) | In sample PASS, **held-out FAIL** (vetoed the 22 Sep winner: obc −₹2.79 L) |
| **No entry in the direction of an opening gap ≥ 0.4 %** | **PASS**: in sample both halves, both strategies; held-out not worse |

## The surviving rule, engine replays

| | 29 tick days | Losing days | Held-out (22–25 Sep + 28 Oct) |
|---|---|---|---|
| oba-v1 → oba-v4 | +₹39.72 L → +₹41.71 L (H1 +1.75, H2 +0.24) | 16 → 16 (−18.22 → −16.13 L) | +₹31,548 → +₹46,841 |
| obc-v11 → obc-v13 | +₹33.16 L → +₹36.66 L (H1 +2.01, H2 +1.49), no day worse | 11 → 7 (−7.32 → −3.83 L) | unchanged (+₹1,53,762) |

The gap is measured from the official open (pre-open auction price) against the previous close; entries against the
gap (toward the fill) are untouched. Out of sample it changed one trade (24 Sep), so the evidence there is thin.

## Reading

- The OI burst is right about 40 % of the time and the losers cannot be told apart from the winners at entry with any
  data we have; every filter that removes many losers removes the few large winners that make the profit.
- The two robust levers are both structural: hold winners to 15:15 (A-260) and do not chase a large opening gap in its
  own direction (this study). A strategy that "works any day" is not supported by the data: the profit comes from trend
  days, and on gap-reversal and chop days the signal loses; the account kill switch caps those days.
- Proposed next step (user's decision): oba-v4 and obc-v13 as PAPER shadows next to oba-v1 / obc-v11, judged forward.
  Nothing was deployed overnight; both GCP VMs are stopped (user, 01:10).

## Outside research used (web review, 29 Oct)

Pan & Poteshman (RFS 2006), Easley, O'Hara & Srinivas (JF 1998), Muravyev, Pearson & Broussard (JFE 2013), Chordia et al.
(Mgmt Sci 2021), Gao et al. (JFE 2018), Baltussen et al. (JFE 2021), Zarattini et al. (SFI 2024), Osler (JF 2003),
Muravyev & Ni (JFE 2020); SEBI F&O reforms (Nov 2024) and NSE's closing auction session (Aug 2026). None of their
suggested filters held on this data except holding to the close. Other AI chat tools (ChatGPT, DeepSeek) were not
used: they need the user's sign-in and would receive the strategy's data.
