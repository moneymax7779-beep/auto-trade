# Indian index options (NIFTY / SENSEX): evidence brief for an intraday option-buying system

Prepared 22 Oct 2026. Web research (search plus selected page fetches) and a few calculations of my own (labelled **[calc]**; script in `calc.py` next to this file).

**Evidence tags**
- **strong**: regulator data on the full population, peer-reviewed work with large samples, or plain arithmetic.
- **moderate**: a single peer-reviewed or working-paper study, an official circular reported consistently by several secondary sources, or non-Indian evidence that is likely to transfer.
- **weak**: blog posts, single-day snapshots, small samples or unverified secondary claims.
- **marketing**: vendor, guru or broker-promotional claims.

**Caveat on sourcing.** Several SEBI and NSE primary PDFs could not be fetched (403 errors or timeouts). Many regulatory figures therefore come from consistent secondary reporting (Business Today, Moneylife, Outlook Money, Zerodha, broker notices). Check the exact effective dates against sebi.gov.in and NSE/BSE circulars before encoding them in code.

---

## 1. Market structure, 2025–2026

| Item | Current state | Tag |
|---|---|---|
| Weekly expiries | One benchmark per exchange since 20 Nov 2024 (SEBI circular of 1 Oct 2024). NSE kept NIFTY 50; BSE kept SENSEX. BANKNIFTY's last weekly expiry was 13 Nov 2024, MIDCPNIFTY 18 Nov, FINNIFTY 19 Nov. SENSEX50 and BANKEX weeklies were also dropped. | strong |
| Expiry days | From Sep 2025, NIFTY weekly expires Tuesday and SENSEX Thursday. SEBI required exchanges to pick Tuesday or Thursday. Monthly contracts expire on the last Tuesday (NSE) or last Thursday (BSE). | strong |
| Contract size | Minimum contract value about ₹15 lakh at introduction (Nov 2024), reviewed periodically. | strong |
| Lot sizes (2026) | NIFTY 65 (from the Jan 2026 expiries; 75 before). SENSEX 20. BANKNIFTY 30 (monthly only). Effective dates differ between sources. | moderate |
| Upfront premium | Brokers collect the full premium from option buyers (from 1 Feb 2025). Intraday leverage on long options is gone. | strong |
| Expiry-day margins | Extra 2 % extreme-loss margin on short index options on expiry day (from 20 Nov 2024). Calendar-spread margin benefit removed on expiry day (Feb 2025); extended to single-stock derivatives from 4 May 2026 (reported only by ICICI). | strong / moderate |
| Intraday position limits | SEBI circular of 1 Sep 2025: index options per entity, futures-equivalent (delta) basis, net ₹5,000 cr intraday (end-of-day ₹1,500 cr) and gross ₹10,000 cr. At least 4 random intraday snapshots, one of them 14:45–15:30. Monitoring from 1 Oct 2025; expiry-day surveillance deposit from about 6–8 Dec 2025. Not binding at the team's scale. | strong |
| Freeze quantity (maximum per order) | NIFTY 1,800 units (27 lots) through Sep 2026. One broker article reports 3,510 from 5 Oct 2026 (NSE circular 136/2026) — **unverified**. SENSEX 1,000 units (50 lots) from Nov 2025. Larger orders must be sliced. | moderate / weak |
| STT (from 1 Apr 2026, Budget 2026) | Option sale: 0.15 % of premium (was 0.10 %). Exercised options: 0.15 % of intrinsic value (was 0.125 %). Futures: 0.05 % (was 0.02 %). | strong |
| Exchange transaction charges | NSE options 0.03553 % of premium (total unchanged since 1 Oct 2024; the split changed 1 Mar 2026). BSE SENSEX options 0.0325 %. Plus SEBI fee ₹10/cr, stamp duty 0.003 % on the buy side, and 18 % GST on brokerage + exchange charges. | moderate |
| 0DTE share | SEBI FY26 study: about 59 % of index-options turnover is in same-day-expiry contracts, 75 % within one day of expiry, 97 % within one week. Earlier commentary: the 0DTE share fell from about 70 % to about 50 % right after the Nov 2024 measures, then concentrated again because only two weeklies remain. Expiry days used to run about 20 a month across indices; now there are about 8. | strong (SEBI) / weak (pre/post split) |
| Expiry-day timing | SEBI consultation paper (Jul 2024): up to 40 % of expiry-day notional turnover traded in the last 60 minutes. | strong |
| Activity trend | NSE index-options premium turnover was flat right after Nov 2024 while notional fell 42 % (OTM expiry lottery tickets disappeared). Equity-options premium ADT was ₹87,808 cr in Jun 2026, down 30.8 % from March to June (STT hike; India VIX fell from about 22 to 14.5). | moderate |
| Offshore 0DTE | NSE IX (GIFT City) has offered daily-expiry (0DTE) NIFTY options since 13 Oct 2025. These are offshore contracts, not available to resident retail. | moderate |

**Round-trip cost per NIFTY lot [calc].** Buy and sell at ₹100 with ₹20 per order brokerage: about ₹63, or about 0.96 index points per unit. At 100 lots it falls to about 0.24 points per unit, because flat brokerage gets diluted; STT then dominates. On a ₹100 premium, statutory costs are about 0.2–0.25 % per round trip. **Spread and slippage are a separate, usually larger cost** (section 5).

**Implications.**
1. The system trades exactly two weekly products. NIFTY has 0DTE on Tuesdays; SENSEX has 0DTE on Thursdays. Every other weekday the nearest weekly has 1–6 days to expiry.
2. After Feb 2025 an option buyer cannot be leveraged intraday.
3. The STT increase taxes high-turnover strategies the most.

## 2. Who makes money and who loses

**SEBI studies of individual F&O traders.** All strong: regulator data covering about 90 % of individual clients.

| Study | Period | Loss-making share | Aggregate net loss of individuals | Average loss | Notes |
|---|---|---|---|---|---|
| Jan 2023 | FY22 | 89 % | ₹45,000 cr+ | ₹1.1 lakh | 45.2 lakh traders (up from 7.1 lakh in FY19). The 11 % who profited averaged +₹1.5 lakh. |
| Sep 2024 | FY22–FY24 | 93 % (3 years combined) | ₹1.8 lakh cr+ over 3 years | ~₹2 lakh incl. costs | FY24: individuals −₹61,000 cr before costs. Proprietary traders +₹33,000 cr and FPIs +₹28,000 cr gross. **97 % of FPI and 96 % of proprietary profits came from algorithmic trading.** Top 3.5 % of loss-makers averaged −₹28 lakh. |
| Jul 2025 | FY25 | 91 % (revised to 90.9 %) | ₹1,05,603 cr (later ₹1.12 lakh cr) | ~₹1.1 lakh | About 96–98 lakh traders. |
| Aug 2026 (DEPA, PR 50/2026) | FY26 | 87.7 % | ₹91,685 cr | ₹1.17 lakh | Details below. |

**FY26 study details.**
- Active individuals fell 20 % to 78.6 lakh; new entrants fell 40 %.
- **Options = 92 % of individual losses.**
- **97 % of individuals mainly buy options and 2 % mainly sell. Option sellers were the only group with a positive median return on capital.**
- About 85 % of trader-quarters lost money. Among traders with both winning and losing quarters, 79 % had average gains smaller than average losses.
- About 90 % of traders who lost two years in a row lost again in the third. Loss share rises with experience (91 % in year 1 to more than 95 % after 4 or more years).
- Transaction costs were about ₹25,000 cr — about 35 % of the ₹72,000 cr gross trading loss.
- Gross profits by category: proprietary traders ₹44,483 cr (FY25 ₹45,955 cr), FPIs ₹13,896 cr (−55 % y/y), corporates about ₹8,000 cr, mutual funds about ₹3,000 cr. **About 99 % of FPI and proprietary profits came from algo entities. About 98 % of proprietary profits came from options.**

**Where the profitable side's edge comes from.**
- **Volatility risk premium (option selling)** — moderate–strong. Bhat, Pandey & Rao (*J. Futures Markets* 2024; NIFTY options 2017–2020) find short-option returns are significantly positive overnight (close to open) and negative or insignificant intraday (open to close). The variance risk premium is mainly a reward for bearing overnight index risk. The asymmetry weakens on days with large jumps in the index. Implication: **an intraday buyer who is flat by the close does not pay most of the volatility premium; the overnight holder pays it.** Intraday, the buyer's main costs are theta, spread and the lack of a directional edge.
- **Market making and latency/arbitrage, run by algos** — strong (SEBI's 96–99 % algo share). Individuals cannot compete here.
- **Expiry-day size and manipulation** — strong as a regulatory finding, contested by the firm. SEBI's Jane Street interim order (3 Jul 2025) covers 18 BANKNIFTY expiry days, Jan 2023–Mar 2025. The allegation: index constituents were bought in cash and futures in the morning while the firm held large short options, then sold later. Alleged net gains were about ₹36,500 cr (about ₹43,000–44,000 cr from index options); ₹4,843 cr was impounded. Jane Street disputes the findings. Implication: **0DTE index paths can be moved by large players, which is adverse selection against small directional buyers on expiry days.**
- **Wealthier and hedged participants lose less** — strong. The loss rate is 93 % for traders with no equity holdings and 58 % for those with portfolios above ₹10 cr.

**What this implies for retail-style option buying.** An intraday option-buying system trades the losing side of the market in aggregate. Its edge has to come entirely from directional and timing skill large enough to cover theta, the spread and costs. The aggregate data says about 9 in 10 individuals fail. This is a base rate, not proof of impossibility. The algos that capture the profits are mostly selling volatility, making markets or arbitraging, not buying direction.

## 3. Intraday behaviour of Indian indices

| Claim | Evidence | Tag |
|---|---|---|
| Intraday volatility is U-shaped (high at the open and close, low midday). | Singh & Gangwar (MPRA wp 89689): 1-min NIFTY futures, 2011–2018, U-shape, and hourly volatility declined over the sample. Consistent with decades of global evidence. | moderate (India) / strong (global) |
| Market intraday momentum (the rest of the day predicts the last 30 minutes). | Gao, Han, Li & Zhou (*JFE* 2018): S&P 500 ETF, the first half-hour predicts the last half-hour. Baltussen, Da, Lammers & Martens (*JFE* 2021): 60+ futures markets, 1974–2020. The rest-of-day return predicts the last 30 minutes, and the effect is linked to **negative-gamma hedging by option sellers**. Li, Sakkas & Urquhart (*J. Financial Markets* 2022): 16 developed markets, out of sample. Asia-Pacific ETF evidence is weaker (mainly China and Japan). Liu & Tse (2017) did not find it in international index futures. India: one unverified ResearchGate paper reports last-half-hour momentum. | strong (US/global) / weak (India) |
| Dealer gamma amplifies 0DTE moves. | US: Dim, Eraker & Vilkov find **0DTE open-interest gamma does not propagate volatility**; a Princeton thesis finds no meaningful gamma-feedback predictive power. The "gamma blast at 15:10" idea in India rests only on TradingView scripts. | moderate (US) / marketing (India) |
| Opening-range breakout (ORB) works. | Holmberg, Lönnbark & Lundström (*Finance Research Letters* 2013): ORB profitable on crude-oil futures. Zarattini, Barbon & Aziz (SSRN, about 2024): 5-min ORB on US "stocks in play" — Sharpe 2.8, about 1,600 % over 2016–2023; a QuantConnect replication gets Sharpe 2.4. **No peer-reviewed ORB study on NIFTY or BANKNIFTY with costs was found.** Indian ORB material is forum or vendor backtests without costs and optimised in sample. | moderate (non-India) / weak–marketing (India) |
| Volatility compression precedes expansion (NR7, Bollinger squeeze). | Volatility clustering is one of the best-established facts in finance (ARCH/GARCH). Squeezes predict a larger range, **not the direction**: one informal test of 399 squeezes found 49.6 % directional accuracy. No peer-reviewed NR7 evidence. | strong (magnitude) / weak (direction) |
| VWAP as support or resistance. | No India-specific peer-reviewed evidence found. VWAP is mainly an execution benchmark; its use as a signal is practitioner lore. | weak |
| Order-flow imbalance predicts short-term returns. | Tripathi, Dixit & Vipul (*Finance Research Letters* 2021), 195 NSE stocks: predictability is **strong for the first 5 minutes and gone within 30 minutes**, and informed flow shows up in deeper book levels. Gupta & Kumar (NSE limit-order book 2014–18): deeper levels add short-horizon predictability, more so when liquidity is low and uncertainty high. | moderate |
| Index-option volume or OI put-call ratios predict the index. | Singh & Chaudhary (Indian index options 2022–23): the ratios work for stock options but **not for index options**. ATM options are where informed traders concentrate (Pathak, Bhattacharjee & Reddy). | moderate |
| Expiry-day pinning. | US: strong for single stocks (Ni, Pearson & Poteshman, *JFE* 2005: clustering at strikes, ≥16.5 bp return shift per expiry) and S&P futures (Golez & Jackwerth, *JFE* 2012). India: pinning seen in NSE **stock** options but no max-pain effect; Gupta, Metia & Trivedi (NSE 2004) found no pinning; no direct NIFTY index-option pinning test found. Consistent with the team's A-154 (78 days, floors and ceilings hold no more often than chance). | strong (US) / weak–negative (India) |
| Expiry raises spot volatility. | NSE daily data 2000–2015 (GARCH): expiry day and expiry week raise spot volatility. A 2022 study finds pre-2019 expiry price effects faded after weekly options were introduced. | moderate |
| IV crush around events. | Budget day: one source says India VIX fell on every Budget day since 2011 (average −9 %); blog examples show ATM IV dropping from about 22–28 % to 12–16 % within hours. No peer-reviewed India event-IV study found. The mechanism (event variance resolves) is textbook. | weak (magnitudes) / strong (mechanism) |

## 4. News and event reaction

| Topic | Evidence | Tag |
|---|---|---|
| RBI policy | RBI working paper (WPS 03/2024, "Equity Markets and Monetary Policy Surprises"): on announcement days, equity returns respond only to the **path** (forward-guidance) factor, not the rate (target) surprise; both move volatility. An intraday short-window event study confirms this. MPC decisions are released about 10:00 IST. Older daily studies: effects are often anticipated. No minute-level study of NIFTY's speed of adjustment found; global high-frequency evidence says the repricing is near-instant. | moderate |
| Budget day | Average intraday range about 2.65 %, net move about 0 (one practitioner source). Budget 2026 (1 Feb, a Sunday): NIFTY −2.33 % after the STT hike. The 0.19 % average move looks net, not absolute. | weak |
| US FOMC, CPI, overnight US returns | Lakdawala (NSE–NYU, 2018): US monetary surprises have moved NIFTY on FOMC days since the early 2000s, and uncertainty shocks since 2008. Older Granger work: US daytime returns lead NIFTY overnight (gap) returns. No CPI-specific India study found. The information arrives overnight and is in the opening gap, so it is not an intraday signal. | moderate |
| GIFT Nifty as an opening predictor | Mechanically it is the overnight futures price, so it largely **is** the implied gap. Arbitrage ties it to NSE futures during overlapping hours. No published accuracy study found; rule-of-thumb thresholds (gap > 0.5 %) come from brokers. Useful for gap size; no evidence it predicts the intraday direction after the open. | moderate (mechanics) / weak (beyond the open) |
| FII/DII cash flows | Many Indian studies (Guru & Parikh 2009; Chandra 2012; Inoue 2008): causality runs mostly **from returns to FII flows** (FIIs chase returns); flows-to-returns is weak or period-specific. | moderate |
| Participant-wise OI (FII/Client/Pro/DII), next-day signal | Free daily NSE file. **No rigorous out-of-sample test found.** Commentary: extreme FII index-future shorts sometimes precede short-covering, but the pattern is inconsistent and confounded by hedging and arbitrage legs. | weak |
| Crude oil / USD-INR | Spillovers exist at daily frequency (Grewal & Trivedi on USD-INR and capital flows). No intraday trading-signal evidence found. | weak |
| India VIX | Built from out-of-the-money NIFTY options across the two nearest expiries (30-day measure). Peer-reviewed: India VIX forecasts realised volatility better than historical measures, and the VIX–return link is asymmetric (falls in NIFTY raise VIX more than rises lower it). Implied usually exceeds realised volatility (a variance premium); the evidence is a 2021–25 study described on LinkedIn (unverified) and practitioner glossaries. Peak 85 % (Nov 2008). 2026 range about 14–22. | moderate |

**Speed of absorption.** No India-specific minute-level evidence was found. Two things are consistent with global literature: scheduled news is priced within minutes, and order-flow predictability decays within 30 minutes (Tripathi et al.). A system reacting on 1-minute bars should assume it is late to the first move of any scheduled event.

## 5. Option-buyer economics

**Theta and break-even [calc].** Black-Scholes on a trading-time clock, NIFTY 25,000, ATM strike, IV 12–13 %. Break-even is the favourable spot move needed to recover theta plus about 1.5 points of round-trip cost.

| Contract and time | ATM premium | Decay in 15 / 30 / 60 min | Break-even move in 15 / 30 / 60 min | 1-σ spot move in 15 / 30 / 60 min |
|---|---|---|---|---|
| 0DTE at 09:15 | ~82 | 2 % / 4 % / 8 % | 6 / 10 / 16 pts | 41 / 58 / 82 pts |
| 0DTE at 12:00 | ~61 | 4 % / 7 % / 16 % | 7 / 12 / 21 pts | same |
| 0DTE at 14:00 | ~40 | 9 % / 18 % / 42 % | 10 / 16 / 31 pts | same |
| 1 DTE, open | ~107 | 1 % / 2 % / 4 % | 5 / 7 / 12 pts | 38 / 53 / 76 |
| 3 DTE, open | ~151 | 0.5 % / 1 % / 2 % | 5 / 6 / 9 pts | — |

How to read this:
1. The theta break-even is small compared with typical moves. The real hurdle is that **the premium already prices the expected absolute move**: the ATM straddle is about 0.8 σ√T. A buyer without directional skill loses theta plus costs on average.
2. On 0DTE after 14:00, decay runs about 1–2 % of premium per minute, so a buyer needs a fast move or must not be there.
3. Real NIFTY 0DTE premiums at the open also carry overnight and event variance, so actual morning decay is often faster than this model.

**Spread and slippage.** No published NSE measurement of ATM weekly spreads was found. The tick is ₹0.05. Practitioners describe ATM NIFTY weeklies as 1–2 ticks wide in normal conditions, with spreads widening in fast markets and far out of the money. The team already captures 5-level depth (the `md` bid/ask arrays). **Measuring realised slippage from that data is better than any external number.** Tag: weak externally.

**0DTE buyers, global evidence.** Beckmeyer, Branger & Gayda (2023), SPX 0DTE: retail lost about $358k a day after daily expiries began, and transaction costs (mostly spreads) were about 60 % of the loss. Tag: moderate.

**Buying vs selling.** SEBI FY26: option sellers had the only positive median return on capital. Bhat et al. (2024): the volatility premium is earned overnight. Together, an intraday buyer avoids paying the overnight premium but has no structural premium to harvest; its whole P&L is directional or convexity timing. Tag: strong / moderate.

**What professional intraday buyers do.** No systematic evidence exists, only practitioner descriptions.
- Buy only into a volatility expansion (a breakout with volume, or the event release itself), not in anticipation of one.
- Prefer ATM or slightly in-the-money strikes for delta and liquidity.
- Use hard time stops of 15–45 minutes and exit if the move does not come.
- Scale out into strength.
- Avoid 0DTE after about 13:30 unless already in profit.
- Size by premium at risk, not by notional.

Tag: weak or marketing. These practices are consistent with the theta table but not tested.

## 6. Compounding reality: ₹5 lakh to ₹10 crore by 31 Dec 2026

**Arithmetic [calc].** The target is 200×.

| Sessions available | Required compound return per session |
|---|---|
| 57 (from 7 Oct) | 9.7 % |
| 49 (about 22 Oct to 31 Dec, after holidays) | 11.4 % |
| 45 | 12.5 % |

The requirement rises as sessions pass without the curve being met. At 9.6 % per session, a 250-session year compounds to about 10⁹ ×.

**Best known track records** (strong as numbers; they show how far out the target is):

| Record | Return | Equivalent per session |
|---|---|---|
| Renaissance Medallion, 1988–2018 (Zuckerman reconstruction) | ~66 % a year gross, ~39 % net | ~0.2 % |
| Larry Williams, 1987 World Cup Championship of Futures Trading (contest record, never beaten) | 11,376 % in 12 months on a $10k account | ~1.9 % |
| Andrea Unger, 2008 contest win | 672 % | ~0.8 % |

The goal needs about **5× the per-session rate of the best contest result ever recorded**, sustained over 2–3 months. Contest records are small accounts with huge drawdowns and survivorship among many entrants.

**Kelly and risk of ruin [calc + Thorp 2006 / MacLean–Thorp–Ziemba].**
- A strong intraday edge — 45 % win rate with a 2:1 payoff (+0.35 R per trade) — has a full-Kelly fraction of 17.5 % and grows about **3.0 % per bet** at full Kelly, 2.25 % at half Kelly.
- Reaching 11 % per session would need about 4 such *independent* full-Kelly bets every day with a correctly estimated edge.
- Under full Kelly, the probability of **ever** being cut to half of starting capital is about **50 %**. At half Kelly it is about 12.5% (Thorp's continuous approximation: P = x^(2/c − 1)).
- Betting above Kelly (needed to chase the target) *lowers* long-run growth and raises ruin probability sharply. At 2× Kelly, expected log-growth is zero.
- Practitioners use ¼–½ Kelly because edge estimates are noisy. The common rule of 0.5–2 % of equity at risk per trade is a rule of thumb, not a tested optimum.

**Liquidity limit at scale** (moderate; estimate only).
- At ₹10 cr equity, risking 1–2 % per trade with a 30 % premium stop means ₹33–67 lakh of premium. At ₹100 per unit that is about 33,000–67,000 units, or **500–1,000 NIFTY lots**.
- At the 1,800-unit freeze quantity that is **18–37 sliced orders**. That is achievable in ATM weeklies in calm markets, but the top-5 book at one strike usually holds far less, so expect several ticks of impact per entry and exit in fast markets.
- The SEBI intraday limit (₹5,000 cr delta) is not binding.
- The team should measure available depth from its own `md` captures rather than rely on this estimate.

**Verdict.** The target is not plausible on any evidence found. It needs a per-session return 5–50× the best documented records and leverage above Kelly, which implies a high probability of ruin. A target that is still aggressive but defensible would be expressed as a monthly return with a maximum-drawdown limit (for example a 15–25 % drawdown cap), with sizing at ¼–½ Kelly computed from the system's out-of-sample edge.

## 7. Data professionals use

| Data | Availability | Evidence of usefulness for intraday index options | Tag |
|---|---|---|---|
| NSE participant-wise OI and volume (FII/DII/Pro/Client, by instrument) | Free, daily after close | No out-of-sample predictive test found. Hedges and arbitrage legs muddy what the positions mean. At most a day-level regime input. | weak |
| FII derivatives statistics (NSE daily) | Free | Same as above. FII cash flows follow returns rather than lead them. | weak–moderate (negative) |
| Order-book depth beyond 5 levels | Zerodha 20-depth: Kite UI only, not API (exchange restriction per Kite forum). Upstox Depth-30: product feature; API access unclear and conflicting. NSE tick-by-tick feed: co-location or vendor, paid. | Deeper book levels add short-horizon (≤ 30 min) predictability on NSE (Gupta & Kumar; Tripathi et al.). | moderate |
| Tick-by-tick trades (signed aggressor) | NSE TBT via vendors | Order imbalance predicts returns for about 5 minutes (Tripathi et al.). Needs low latency to use. | moderate |
| IV surface, skew and term structure | Computable from the option chain the team already has | Global work: skew and IV-to-realised spreads carry information (US single stocks). For the NIFTY index, the main use is risk and pricing (avoid buying rich IV; event term-structure kinks). | moderate (US) / weak (India) |
| India VIX (live) | Free | Good realised-volatility forecaster; asymmetric. Useful for sizing and for deciding whether premium is cheap enough to buy. | moderate |
| GIFT Nifty (pre-open, overnight) | Free or vendor | Gives the opening gap almost by construction; no evidence beyond the open. | moderate (gap) / weak |
| Global cues (US close, US futures, Asia, DXY, crude, USD-INR) | Free | Explain the overnight gap (US daytime leads NIFTY's overnight return) and are already in the open; little evidence of intraday direction after 09:30. | moderate |
| Option OI change and PCR (intraday) | Already captured | Index-option PCR does not predict the index (Singh & Chaudhary). Matches the team's A-068, A-121/123 and A-152 failures. | moderate (negative) |
| Event calendar (RBI, Budget, FOMC, CPI, index rebalancing, expiry) | Free | Volatility and IV behave predictably around events (rise before, crush after); direction does not. Useful for avoiding or sizing trades. | moderate |

**The most valuable additions the team lacks:**
1. A **measured slippage and depth model** from its own captures.
2. **IV-relative-to-realised and term-structure filters**, so it buys only when premium is cheap relative to the expected move.
3. **Signed trade flow from tick data**, but only if latency allows acting within about 5 minutes.

Participant-wise OI and GIFT Nifty are cheap to add but have little demonstrated predictive value.

---

## Sources

**Regulation and structure**
- Zerodha: SEBI's new rules for index derivatives — https://zerodha.com/z-connect/business-updates/sebis-new-rules-for-index-derivatives-heres-whats-changing
- Moneylife: SEBI tightens equity-derivatives regulations — https://www.moneylife.in/article/sebi-tightens-regulations-on-equity-derivatives-trading/75288.html
- Motilal Oswal: NSE discontinues BANKNIFTY/FINNIFTY/MIDCPNIFTY weeklies — https://www.motilaloswal.com/learning-centre/2024/10/nse-discontinues-weekly-derivatives-on-bank-nifty-nifty-midcap-select-and-finnifty
- Business Standard: NIFTY Thursday-to-Tuesday swap — https://www.business-standard.com/markets/news/nse-bids-adieu-to-thursday-expiry-as-dates-swap-come-into-effect-explained-125082800635_1.html
- Lot sizes 2026 — https://www.sahi.com/blogs/nifty-lot-size-2026-bank-nifty-sensex ; https://fyers.in/notice-board/revision-in-index-derivatives-contracts-lot-size/
- Intraday position limits — https://taxguru.in/sebi/sebi-tightens-intraday-position-limits-derivatives.html ; https://moneylife.in/article/sebi-restores-higher-intraday-limits-for-index-options-with-tighter-oversight/78154.html ; https://nsearchives.nseindia.com/content/circulars/SURV70553.pdf
- Freeze limits — https://zerodha.com/marketintel/bulletin/451659/quantity-freeze-limits-for-indices-from-july-01-2026 ; https://choiceindia.com/news/nse-revises-quantity-freeze-limit-for-index-fno-contracts-from-october-5-2026 ; https://zerodha.com/marketintel/bulletin/432323/quantity-freeze-limits-for-indices-from-november-03-2025
- STT under Budget 2026 — https://www.icicidirect.com/ilearn/futures-and-options/articles/stt-changes-in-budget-2026-what-f-o-traders-should-know ; https://www.5paisa.com/news/stt-hike-on-fo-to-take-effect-from-april-1-amid-rising-options-activity
- Exchange charges — https://support.zerodha.com/category/account-opening/resident-individual/ri-charges/articles/exchange-transaction-charges ; https://onetradejournal.com/brokers/charges-changelog
- Calendar spread / ELM — https://www.icicidirect.com/ilearn/futures-and-options/articles/removal-of-calendar-spread-margin-benefit-for-single-stock-derivatives-on-expiry ; https://www.outlookmoney.com/invest/equity/sebi-tightens-norms-on-fo-trading-what-does-this-mean-for-retail-investors
- SEBI consultation paper (Jul 2024) coverage — https://moneylife.in/article/sebi-suggests-changes-in-index-derivative-framework-to-reduce-speculation-recommends-fewer-weekly-expiries-increase-in-contract-size-by-34-times/74788.html ; https://www.business-standard.com/amp/opinion/columns/sebi-proposals-seek-orderly-conduct-in-f-o-market-amid-volatility-125032401438_1.html
- Premium turnover — https://www.outlookbusiness.com/markets/sebis-regulatory-changes-have-minimal-impact-on-option-premium-turnover ; https://www.outlookmoney.com/invest/derivatives-trading-activity-decline-after-sebi-futures-and-options-reforms
- NSE IX 0DTE — https://www.fow.com/insights/indias-nse-to-launch-same-day-options-on-nifty-index

**Who wins and who loses**
- SEBI FY26 DEPA studies — https://taxguru.in/?p=1072039 ; https://www.moneylife.in/article/92-percentage-of-aggregate-losses-incurred-by-individuals-are-from-options-trading-sebi-study/81429.html ; https://www.businesstoday.in/amp/markets/story/proprietary-traders-derivatives-profits-fall-in-fy26-says-sebi-study-550558-2026-08-21 ; https://www.outlookmoney.com/invest/sebi-futures-options-study-transaction-costs-traders-losses-fy26
- SEBI FY25 study — https://moneylife.in/article/91-percentage-of-retail-traders-lost-money-in-derivatives-losses-in-fo-surged-41-percentage-to-rs105-lakh-crore-in-fy25-sebi-study/77613.html ; https://www.valueresearchonline.com/stories/225398/average-trader-lost-rs-1-1-lakh-fo-fy25/
- SEBI FY22–FY24 study — https://www.outlookmoney.com/invest/93-of-individual-traders-suffered-losses-in-fo-in-last-3-years-sebi-study ; https://www.taxmann.com/post/blog/93-of-1-crore-fo-traders-saw-rs-2l-avg-loss-while-top-3-5-faced-rs-28l-avg-loss-incl-costs-sebi/
- SEBI FY22 study — https://www.business-standard.com/amp/article/markets/sebi-study-suggests-89-retail-traders-in-equity-f-o-suffered-losses-123012501466_1.html
- Jane Street interim order — https://blogs.law.ox.ac.uk/oblb/blog-post/2025/07/jane-street-and-expiry-day-trap-unpacking-sebis-crackdown-algorithmic ; https://www.ecgi.global/publications/blog/expiry-day-and-the-governance-of-algorithmic-trading-the-jane-street-episode
- Bhat, Pandey & Rao (2024), *J. Futures Markets* 44:1320–1337, day/night NIFTY option returns — https://law-journals-books.vlex.com/vid/the-asymmetry-in-day-1115554990

**Intraday behaviour**
- Singh & Gangwar, NIFTY futures intraday volatility — https://mpra.ub.uni-muenchen.de/89689/
- Gao, Han, Li & Zhou (*JFE* 2018) — https://profiles.wustl.edu/en/publications/market-intraday-momentum/
- Baltussen, Da, Lammers & Martens (*JFE* 2021) — https://pure.eur.nl/en/publications/hedging-demand-and-market-intraday-momentum/
- Li, Sakkas & Urquhart (2022), *J. Financial Markets* — https://ideas.repec.org/a/eee/finmar/v57y2022ics138641812100001x.html
- Holmberg, Lönnbark & Lundström (2013), ORB — https://ideas.repec.org/a/eee/finlet/v10y2013i1p27-33.html
- Zarattini, Barbon & Aziz, ORB on US stocks in play — https://concretumgroup.com/a-profitable-day-trading-strategy-for-the-u-s-equity-market/ ; replication https://www.quantconnect.com/research/18444/opening-range-breakout-for-stocks-in-play/
- Tripathi, Dixit & Vipul (2021), order imbalance on NSE — https://ideas.repec.org/a/eee/finlet/v41y2021ics1544612320316779.html
- Ni, Pearson & Poteshman (2005) — https://scholars.hkbu.edu.hk/en/publications/stock-price-clustering-on-option-expiration-dates-3/
- Golez & Jackwerth (2012) — https://academicnewsletter.sufe.edu.cn/info/356897
- Indian expiry-day studies — https://journals.sagepub.com/doi/10.1177/0972622516629029 ; https://repository.iimb.ac.in/handle/2074/21032?mode=full
- 0DTE gamma, US — https://harbourfrontquant.substack.com/p/the-rise-of-0dte-options-cause-for-concern-or-business-as-usual ; retail 0DTE losses https://www.bloomberg.com/news/articles/2023-04-21/day-traders-lose-358-000-per-day-gambling-on-zero-day-options ; https://cxoadvisory.com/individual-investing/retail-0dte-option-trader-performance
- Squeeze direction (weak) — https://www.luxalgo.com/library/concept/squeeze-release-direction/

**Events and flows**
- RBI WPS 03/2024, "Equity Markets and Monetary Policy Surprises" — https://website.rbi.org.in/documents/87730/30842423/WPS+No.+03+2024+Equity+Markets+and+Monetary+Policy+Surprises.pdf
- Lakdawala, US monetary policy and India (NSE–NYU) — https://website.cafral.org.in/sites/default/files/2025-07/1282019122037pm_aeimitlakdawalaslides.pdf
- US to NIFTY overnight spillover — https://nsearchives.nseindia.com/content/research/Paper39.pdf
- FII flows causality — https://ideas.repec.org/a/sae/fortra/v44y2009i2p60-79.html ; https://ideas.repec.org/p/pra/mprapa/15793.html
- Budget-day VIX — https://www.samco.in/knowledge-center/articles/stock-market-analysis-on-union-budget-of-india/ ; https://cleartax.in/s/budget-day-market-movement-history-in-india
- India VIX behaviour — https://iimidr.ac.in/wp-content/uploads/2019/12/v2010-issue2-The-Behaviour-of-Indias-Volatility-Index.pdf ; https://springerlink.bibliotecabuap.elogim.com/10.1007/s40622-014-0070-0
- GIFT Nifty (weak, practitioner) — https://www.mnclgroup.com/how-gift-nifty-predicts-nifty-50-opening
- Participant OI explainer — https://algotest.in/blog/participant-wise-open-interest/

**Sizing and track records**
- MacLean, Thorp & Ziemba, "Good and bad properties of the Kelly criterion" — https://www.stat.berkeley.edu/~aldous/157/Papers/Good_Bad_Kelly.pdf
- Thorp (2006), "The Kelly criterion in blackjack, sports betting and the stock market" — https://www.Gwern.net/doc/statistics/decision/2006-thorp.pdf
- Medallion — https://www.wealthmanagement.com/alternative-investments/jim-simons-built-the-best-hedge-fund-ever
- World Cup Championship records — https://completetradersedge.com/larry-williams-11376-percent-trading-record/ ; https://ireallytrade.com/halloffame

**Data and depth**
- Zerodha 20-depth not available via API — https://kite.trade/forum/discussion/comment/49927 ; https://support.zerodha.com/category/trading-and-markets/kite-web-and-mobile/articles/view-20-depth
- Upstox Depth-30 — https://upstox.com/help-center/why-do-traders-need-access-to-a-deeper-view-of-market-depth-like-depth-30-263400/ ; https://community.upstox.com/t/market-depth-30-increase-limit-for-more-than-50-instruments/13398

## Gaps (no credible evidence found)

- A peer-reviewed NIFTY or BANKNIFTY ORB study with costs.
- India-specific intraday-momentum evidence (only one unverified paper).
- A direct NIFTY index-option pinning test.
- Minute-level speed of adjustment to RBI, Budget or CPI news.
- An out-of-sample test of participant-wise OI as a predictor.
- GIFT Nifty accuracy statistics.
- Published ATM weekly spread and depth figures.
- Median outcomes for option buyers vs sellers in SEBI FY26 (stated qualitatively only).
- The 3,510 NIFTY freeze-quantity figure (single source).
