# Exits and holding on trend days (A-385, descriptive)

User, 13 Nov 2026: "research the exits and holding on trend days", after A-383 / A-384 closed the order-flow direction
family and the oracle-gap study (A-357) showed most of the money is earned after entry.

Descriptive and in-sample: no rule is judged here. Scripts and full tables: `.local/trend-exit/` (`extract.py`,
`analyze.py`, `descriptive.md`).

## Data

- The five live strategies' alone replays on the code that is live (main-f82d279): etr-v1, mac-v34, obc-v13,
  wbr-v13, brtr-v1. 189 trades on 28 own-capture tick days, 28 Sep – 6 Nov; every trade has an option path.
- For each option, the bid every minute (own capture `md.option_tick`) from entry to 15:15.
- "Hold to 15:15" = the last bid at or before 15:15, with the trade's own costs and no stop. Rupees are at the
  replay's alone size (each strategy's ₹5 L budget).
- **Day type is post hoc**, known only at the close. A trend index-day has |close − open| ≥ 0.5 × the day's range
  and a move of at least 0.4 %. A trade is "with" the trend if its side (CE up, PE down) matches the day's direction.
  31 of the 55 index-days with trades are trend days (56 %). On the 2024–26 bar days the share is about 44 % (A-110),
  so this sample favours holding.

## Findings

**1. The money left on the table is real, but only on days known as trend days at the close.**

| Day type | Trades | Realized | Hold to 15:15 | Holding minus exits |
|---|---|---|---|---|
| With the trend | 67 | +80.1 L | +138.2 L | **+58.1 L** |
| Against the trend | 22 | −1.9 L | −23.7 L | −21.9 L |
| Range day | 100 | +27.7 L | −28.6 L | **−56.3 L** |
| All | 189 | +105.9 L | +85.9 L | −20.1 L |

The exits act as insurance. They give up about ₹58 L on trend days and save about ₹78 L on range and against-trend
days. Holding everything to 15:15 would have cost ₹20 L even on this trend-heavy sample.

Per strategy, the with-trend money left behind is brtr +19.4 L, wbr +15.7 L, obc +15.5 L, etr +3.8 L and mac +3.7 L.
brtr shows it most: holding beat its exit in 81 % of its with-trend trades (median +74 % held against −1 % at exit),
but it would have lost 18.2 L on its 54 range-day trades.

**2. A trend that is visible at the exit is mostly over.** I fixed the TREND-ON test before computing these tables,
using A-064's "proven trend" definition. At the strategy's own exit minute, all three of these had to hold:
- the future was beyond its session VWAP the trade's way for at least 45 of the last 60 minutes;
- the index was at least 0.3 % from the open the trade's way;
- it had made a new session extreme that way within the last 30 minutes.

- It is a fair detector: 23 of the 31 TREND-ON exits were on with-trend days (74 %), against 28 % of the other exits.
- But holding those trades to 15:15 **loses ₹13.6 L** (realized +72.1 L, held +58.4 L).
- Without the 10 trades that were already held to 15:15 by the FLAT_BY exit, holding was better in only 6 of 21
  trades and lost ₹14.3 L.
- The biggest losses come from 15 Oct SENSEX, where etr and wbr exited on a swing loss at 14:06 (+56 % / +53 %) and
  holding gave −2.8 L / −2.5 L, and from 1 Oct SENSEX, where wbr's +248 % trail exit would have shrunk to +384 K
  held.
- Once a trend is proven on price, the exit signals (swing reclaimed, trail) mostly mark its end.

**3. Shorter extensions are noise.** Holding 60 minutes past every exit: +110.5 L against +105.9 L realized (+4 %).
- By half: +4.8 L in H1 (28 Sep – 15 Oct) and −0.2 K in H2. By strategy, etr −7.6 L and obc −1.5 L, the rest
  positive.
- The +30 / +120 minute versions are no better: +120 minutes is −11.9 L.
- Exits before 12:00 look better held, +8.4 L at +60 minutes (from −2.4 L to +6.0 L), but these are mostly stops on trend days. The same
  stops on range days are where holding loses.

**4. Where the "left" money sits.**
- Nine index-days hold most of it: 8 Oct SENSEX +18.4 L, 13 Oct NIFTY +7.8 L, 29 Oct SENSEX +6.7 L, 19 Oct NIFTY
  +6.1 L, …
- Seven trend days had negative "left", among them 15 Oct SENSEX −11.0 L, 6 Nov NIFTY −2.9 L and 3 Nov NIFTY −2.7 L.
  On those the trend came before or after the trade, or reversed into the close.

## Conclusion

- **Holding pays only on trend days, and they are known only at the close.** A-110 (odb) found this before, and
  A-064, A-173 and A-094 found the trend state can't be told in advance. This study shows it for all five live
  strategies at once.
- **Waiting for the trend to be proven gives the wrong answer.** At the exit, a visible trend predicts its own end
  better than its continuation.
- **The current exits already earn their place.** They turn the range days, which are 53 % of trades, from −28.6 L
  held into +27.7 L.
- **I don't recommend a hold-longer or extend-the-exit rule for registration.** Every variant here is either negative
  or carried by H1's trend days. A registered test would very likely fail on the bar days, where trend days are
  rarer.

## What is left (not tested here)

- **Re-entry, not holding:** a second entry in the trend's direction after an exit, when the trend is still on.
  A-064 (trend-day rider) and A-369 (obc re-entry) failed on this; nothing new to add without a new idea.
- **Capital crowding:** in the shared account a held position also blocks other entries (A-360). These alone-replay
  numbers ignore that, so holding would look worse in the real account, not better.
- **Forward days:** 9–12 Nov and later days were not used. The finding is about the shape of the trade-off, which a
  few more days will not change.
