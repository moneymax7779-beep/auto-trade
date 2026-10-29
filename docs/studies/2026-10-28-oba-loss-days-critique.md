# oba-v1 loss days: an outside critique checked against the data (28 Oct 2026)

The user pasted an AI write-up on oba-v1 (`oi-burst-allday`, A-258) that names theta decay, paper-fill optimism and
naked option buying as its flaws and proposes other strategies. Each claim was checked against oba-v1's 80 trades on the
29 tick days (`.local/a259/trades.json`, sessions 20151–20179) and obc-v11's 32 (sessions 20180–20209, A-260).

| Claim | What the data says | Verdict |
|---|---|---|
| Losers lose to theta when the burst stalls | 40 of 47 losers (−Rs 23.56 L of −24.75 L) lost because the index moved more than 0.25 ATR against them; 6 flat trades −Rs 0.81 L; 1 trade lost with the index in its favour (−Rs 0.38 L). Losers are held a median 29 minutes (quartiles 8 / 47) | Rejected: losses are direction, not decay |
| Expiry-day theta is brutal | Expiry day (0 DTE) is oba-v1's best bucket: 25 trades, +Rs 22.60 L net (1 DTE +5.81 L, 2+ DTE +11.31 L) | Rejected for this signal |
| Paper fills at the limit price, perfect queue | `PaperBroker`: buys fill at the displayed ask, sells at the displayed bid, through the visible depth (partial fills when thin), after 250 ms; shared depth between orders on a tick. Six entries never filled (ENTRY_NOT_FILLED) | Rejected (the model already pays the spread) |
| Stress fills 1.5 % worse on entry and exit | oba-v1 +Rs 39.72 L → +32.56 L (H1 +4.48 L, H2 +28.08 L); obc-v11 +33.16 L → +30.05 L (H1 +10.59 L, H2 +19.45 L). At 3 %: oba +25.40 L (H1 +1.22 L), obc +26.93 L | Survives; oba's H1 is thin |
| Use SL-M instead of STOP_LIMIT | NSE discontinued SL-M for index options; Zerodha blocks it since 20 Sep 2021, BSE blocks it too. The engine's exits are decided on the index and sent as marketable limits at the bid; the resting STOP_LIMIT is only a catastrophe stop | Not possible; current design is the usual answer |
| Trade vertical spreads instead of naked options | 16 of 33 winners (Rs 55.04 L of 64.46 L, 85 %) came with an index move beyond about two strikes (100 NIFTY / 300 SENSEX points), exactly the part a two-strike spread gives up | Tested, A-263 FAIL: every spread arm (100/200 NIFTY, 300/600 SENSEX points; same lots or same capital) is below naked in both halves for oba-v1 and obc-v11 (same-capital 100-point: oba +25.4 L vs +39.7 L) |
| Max-pain / expiry pinning | A-154: on 78 expiry days, OI floors and ceilings held as often as chance | Already rejected |
| Gamma squeeze at the call wall (risk reversal) | A-057/A-058 wall-break rider FAIL, A-119 OI-wall break FAIL; the short put adds naked-short risk and margin | Not supported here |
| Fade 1.5 ATR stretch from VWAP | Swing-zones study (17 Oct, 392 index-days): stretch does not revert on the indices; single stocks untested | Rejected for indices |
| Cash–futures basis arbitrage | A real but small carry trade (it pays about the financing rate, capital-heavy, needs a spot basket); "> 1.5 % annualised" is below the normal carry, not an anomaly | Out of scope |
| Daily volatility-compression breakouts in stocks (CNC) | A different product (equity delivery, weeks); not built or tested here | Separate track if wanted |

What remains true in the critique: live fills can be worse than paper (queue position, latency, wider books in a burst).
The way to know is to measure live against paper once real orders run (the 26 Oct odm entry filled in replay but not
live). The losses themselves are the cost of the signal (A-259): no entry feature separates losers, and every early exit
or filter tested removed winners with them.

Sources: [Zerodha: SL-M blocked for index options](https://zerodha.com/marketintel/bulletin/305785/stop-loss-market-sl-m-orders-blocked-for-index-options),
[Zerodha: SL-M blocked on BSE](https://support.zerodha.com/category/trading-and-markets/kite-web-and-mobile/kite-error-messages/articles/slm-discontinued-by-bse).

## Follow-up, 29 Oct: A-263 spreads and A-264 blueprint rules (tick days only)

A-263 sold the further-OTM leg at its real bid at each trade's entry and bought it back at its real ask at the exit
(quote lookup reproduces the engine's own long fills within 0.1 %). Every spread variant made less in both halves, for
both strategies; the losing days shrink, but the winners shrink more (oba-v1 best trade ₹10.03 L → ₹3.89 L).

A-264 ran the second pasted blueprint's rules through the A-259 simulator on oba-v1: flat 14:45 or 15:00, a 13:00 expiry
kill, OTM exit at 14:30 and 50 % off at +1R all cost money in at least one half. The best entry window is 09:20–09:30
(+₹33.3 L of +₹39.7 L). One post-hoc pattern worth a registered test later: entries when the futures OI shows fresh
build-up in the trade's own direction (calls in FRESH_LONG, puts in FRESH_SHORT) went 2 of 15, −₹7.19 L, negative in
both halves; the blueprint's rule (avoid calls on short covering) points the other way.
