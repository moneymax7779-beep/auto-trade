# Udemy — "4 Key Indicators for Option Buying" — triage notes

Scanned 24 Oct 2026 (course id 6588059, 49 lectures, ~13 h, rating 4.4 from 75). Lectures 9–29 captions searched for
settings and rules; openings read. Base URL `https://www.udemy.com/course/4-key-indicators-for-option-buying/learn/lecture/<id>`.

- **Option-chain strategies (L9–L12):** event long straddle (budget, election results, earnings); **PCR rule**: PCR > 1.5
  → buy puts, PCR < 0.7 → buy calls, 0.8–1.4 → no trade (he reads PCR as "what buyers are doing"; the usual OI reading is
  the opposite).
- **"Market formula" FM = Levels × Segment × Signal + Momentum (L15–L28):**
  - Levels: support / resistance, role reversal after a break.
  - Segment: **EMA 9 / 20 / 50** (+ EMA 200 or VWAP). Classic bullish = 9 above 20 above 50; classic bearish = 9 below 20
    below 50; any other order ("EMA 20 not in the middle") = **no trade**.
  - Signal: a candle **closes above all three EMAs** (bullish segment); if VWAP / EMA 200 sits just above them, wait for
    a close above that too. Bollinger **%B with length 69** (he tried 3/6/9 combinations first): from the 0–0.5 zone, a
    close **above 0.5 = "standard buy signal"** (mirror below 0.5 for sells).
  - Momentum: needed after the signal; small targets in low volatility.
- Overlap: EMA-stack trend filters = our MA-cross family (mac-v1 live; A-156 FAIL); PCR as a direction signal is not
  supported by our OI work (A-152, A-068 writer flow no signal). No stop/target rules stated in the scanned lectures →
  not registered.
