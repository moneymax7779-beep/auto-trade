# Official index weights

Monthly constituent weights (percent) of NIFTY 50, Nifty Bank and SENSEX as published by the index
providers' factsheets, copied on 2026-09-26 from zt-tiger-v2's packaged reference files
(`zerotrade-options/src/main/resources/index-playbook/weights/`). Files are named
`<index>-<as-of>.csv` with columns `symbol,weight,as_of`; the newest file dated on or before a
session is used. Add each month's publication as a new file; never edit an existing one.

## Backfill Sep 2024 – Jun 2026 (25 Oct 2026, ledger A-231)

Written by `docs/studies/2026-10-25-index-weights-backfill.py` so that bar replays before 31 Jul 2026 have
mover breadth. A file dated D (a month end) serves the sessions from D until the next file.

- **NIFTY 50** (`nifty-50-2024-09-30.csv` … `nifty-50-2026-06-30.csv`): NSE Indices' official month-end
  publication `https://www.niftyindices.com/Indices_-_Market_Capitalisation_and_Weightage/indices_data<Mon><YYYY>.zip`,
  file `NIFTY_50_<Mon><YYYY>.pdf`. The published 2-decimal weights are written unchanged. The same parser reproduces
  the two official files above (31 Jul and 31 Aug 2026) exactly, and the Jul/Aug 2026 archive hashes match the ones
  zt-tiger-v2 recorded. Symbols are written as Upstox lists them today: ZOMATO → ETERNAL, TATAMOTORS → TMPV (same
  ISINs). The demerger placeholder rows (DUMMYTATAM Oct 2025, DUMMYHDLVR Dec 2025–Jan 2026, ≤ 0.46 %) have no market
  data and are left out.
- **SENSEX** (`sensex-2024-09-30.csv` … `sensex-2026-06-30.csv`, plus `sensex-2024-12-23.csv`,
  `sensex-2025-06-23.csv`, `sensex-2025-12-22.csv`): **derived, not BSE's published weights.** BSE's scrip-weight
  API refuses scripted access. Each SENSEX member's NSE free-float index market cap (NIFTY 50 and NIFTY Next 50 PDFs
  of the same month) is renormalised over the 30 members. Checked against BSE's official files for 31 Jul and
  31 Aug 2026: at most 0.05 / 0.09 percentage points apart (mean 0.013 / 0.027). Membership changes from BSE's
  announcements: 23 Dec 2024 Zomato in, JSW Steel out; 23 Jun 2025 Trent and BEL in, Nestle India and IndusInd Bank
  out; 22 Dec 2025 InterGlobe Aviation in, Tata Motors PV out; Jun 2026 no change. Each change date has its own
  file (previous month-end market caps, new members). Zomato's NSE free-float factor was raised at the end of Dec
  2024 (from 193 to 692 crore shares counted); its 23 Dec 2024 weight uses the raised factor. The temporary Tata
  Motors CV listing after the Oct 2025 demerger is not modelled.

Source archives (SHA-256, first 16 hex digits):

| Archive | SHA-256 |
|---|---|
| `indices_dataApr2025.zip` | `331fdd535f330d9f…` |
| `indices_dataApr2026.zip` | `5274563ff7ae3738…` |
| `indices_dataAug2025.zip` | `3166a80173b90f9c…` |
| `indices_dataAug2026.zip` | `3ba997d427b824f1…` |
| `indices_dataDec2024.zip` | `c25bd0e1dffa353e…` |
| `indices_dataDec2025.zip` | `5ba998fbbaca9348…` |
| `indices_dataFeb2025.zip` | `9fd7e78567e2bf0b…` |
| `indices_dataFeb2026.zip` | `d5d418e330e4e7f2…` |
| `indices_dataJan2025.zip` | `2209682560f48d1e…` |
| `indices_dataJan2026.zip` | `aa6a9b9c7143df40…` |
| `indices_dataJul2025.zip` | `4431bafaa39eb84b…` |
| `indices_dataJul2026.zip` | `1184b58ea5e2b8a7…` |
| `indices_dataJun2025.zip` | `d15a87268f223754…` |
| `indices_dataJun2026.zip` | `c2f0aa29e9a10e52…` |
| `indices_dataMar2025.zip` | `0b574467951ba383…` |
| `indices_dataMar2026.zip` | `fd650078e31b4315…` |
| `indices_dataMay2025.zip` | `e8d1a0cbf0390a51…` |
| `indices_dataMay2026.zip` | `2491659c8e4e584d…` |
| `indices_dataNov2024.zip` | `dd81b3be2aa76205…` |
| `indices_dataNov2025.zip` | `31060ecce6119f2c…` |
| `indices_dataOct2024.zip` | `4cfe006e942f637c…` |
| `indices_dataOct2025.zip` | `bd3a938875d1b507…` |
| `indices_dataSep2024.zip` | `5ad8a872d9d67519…` |
| `indices_dataSep2025.zip` | `7d6e13b12fcd4902…` |
