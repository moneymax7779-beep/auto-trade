"""Historical index weights for the bar replays (ledger A-231), Sep 2024 - Jun 2026.

NIFTY 50: NSE Indices' official month-end publication, indices_data<Mon><YYYY>.zip from
https://www.niftyindices.com/Indices_-_Market_Capitalisation_and_Weightage/ (NIFTY_50_<Mon><YYYY>.pdf).
The published 2-decimal weights are written unchanged.

SENSEX: BSE's scrip-weight API refuses scripted access, so SENSEX weights are the NSE free-float index market
caps (NIFTY 50 and NIFTY Next 50 PDFs of the same month) of the SENSEX members, renormalised to 100. Checked
against BSE's official files for 31 Jul and 31 Aug 2026: max 0.09 percentage points apart (mean 0.01-0.03).
Membership (BSE / Asia Index announcements): 23 Dec 2024 Zomato in, JSW Steel out; 23 Jun 2025 Trent and BEL in,
Nestle India and IndusInd Bank out; 22 Dec 2025 InterGlobe in, Tata Motors PV out; Jun 2026 no change. Each
change date also gets a file (previous month-end caps, new members).

Symbols are written as Upstox lists them today (the bars are keyed by today's listing): ZOMATO -> ETERNAL,
TATAMOTORS -> TMPV (same ISINs). Demerger placeholder rows (DUMMY*) have no market data and are left out.

usage: python3 -I 2026-10-25-index-weights-backfill.py <dir with the zips> <config/reference/weights> <bse codes file>
needs pypdf. The bse codes file maps BSE scrip codes to symbols (code|symbol per line).
"""
import datetime
import io
import json
import os
import re
import sys
import zipfile

import pypdf

ZIPS, OUT, CODES = sys.argv[1], sys.argv[2], sys.argv[3]
RENAME = {"ZOMATO": "ETERNAL", "TATAMOTORS": "TMPV"}
LINE = re.compile(r"^(\S+) (.+?) (\d+\.\d+) (\d+\.\d{2})(\d+)$")   # symbol name close weight(2dp)mcap (columns run together)
DATE = re.compile(r"^([A-Z][a-z]+ \d{1,2}, \d{4})$")
FIRST = "2024-09-30"
LAST = "2026-06-30"        # Jul and Aug 2026 are already in the repo (official files)


def parse(pdf_bytes):
    rows, as_of = [], None
    for page in pypdf.PdfReader(io.BytesIO(pdf_bytes)).pages:
        for line in page.extract_text().splitlines():
            line = line.strip()
            m = DATE.match(line)
            if m:
                as_of = datetime.datetime.strptime(m.group(1), "%B %d, %Y").date().isoformat()
            m = LINE.match(line)
            if m:
                rows.append({"symbol": m.group(1), "close": float(m.group(3)), "weight": float(m.group(4)),
                             "mcap": int(m.group(5))})
    return as_of, rows


months = {}   # as_of -> {"n50": rows, "next50": rows}
for name in sorted(os.listdir(ZIPS)):
    if not name.endswith(".zip"):
        continue
    with zipfile.ZipFile(os.path.join(ZIPS, name)) as z:
        for member in z.namelist():
            kind = "n50" if member.startswith("NIFTY_50_") else "next50" if member.startswith("NIFTY_Next_50_") else None
            if kind:
                as_of, rows = parse(z.read(member))
                assert len(rows) >= 50, (member, len(rows))
                assert abs(sum(r["weight"] for r in rows) - 100) < 0.1, member
                months.setdefault(as_of, {})[kind] = rows

codes = dict(line.strip().split("|")[::-1] for line in open(CODES) if line.strip())   # symbol -> BSE code
codes.update({"JSWSTEEL": "500228", "NESTLEIND": "500790", "INDUSINDBK": "532187", "TMPV": "500570"})

SENSEX_NOW = ["ADANIPORTS", "ASIANPAINT", "AXISBANK", "BAJAJFINSV", "BAJFINANCE", "BEL", "BHARTIARTL", "ETERNAL",
              "HCLTECH", "HDFCBANK", "HINDUNILVR", "ICICIBANK", "INDIGO", "INFY", "ITC", "KOTAKBANK", "LT", "M&M",
              "MARUTI", "NTPC", "POWERGRID", "RELIANCE", "SBIN", "SUNPHARMA", "TATASTEEL", "TCS", "TECHM", "TITAN",
              "TRENT", "ULTRACEMCO"]
CHANGES = [("2024-12-23", ["ETERNAL"], ["JSWSTEEL"]),
           ("2025-06-23", ["TRENT", "BEL"], ["NESTLEIND", "INDUSINDBK"]),
           ("2025-12-22", ["INDIGO"], ["TMPV"])]


def sensex_members(day):
    members = set(SENSEX_NOW)
    for date, added, removed in reversed(CHANGES):   # undo the changes that come after `day`
        if day < date:
            members -= set(added)
            members |= set(removed)
    assert len(members) == 30, (day, len(members))
    return members


def caps(as_of):
    out = {}
    for kind in ("next50", "n50"):
        for r in months[as_of][kind]:
            if not r["symbol"].startswith("DUMMY"):
                out[RENAME.get(r["symbol"], r["symbol"])] = r
    return out


def write(path, rows):
    with open(path, "w") as f:
        f.write("symbol,weight,as_of\n")
        for symbol, weight, as_of in rows:
            f.write(f"{symbol},{weight},{as_of}\n")


def write_sensex(file_date, cap_month, members, overrides=None):
    c = caps(cap_month)
    mcap = {s: c[s]["mcap"] for s in members}
    mcap.update(overrides or {})
    total = sum(mcap.values())
    rows = sorted(((codes[s], 100.0 * v / total, file_date) for s, v in mcap.items()), key=lambda r: -r[1])
    write(os.path.join(OUT, f"sensex-{file_date}.csv"), [(a, f"{w:.10f}", d) for a, w, d in rows])


summary = []
for as_of in sorted(months):
    if not FIRST <= as_of <= LAST:
        continue
    n50 = [r for r in months[as_of]["n50"] if not r["symbol"].startswith("DUMMY")]
    write(os.path.join(OUT, f"nifty-50-{as_of}.csv"),
          [(RENAME.get(r["symbol"], r["symbol"]), f"{r['weight']:.2f}", as_of) for r in n50])
    next_day = (datetime.date.fromisoformat(as_of) + datetime.timedelta(days=1)).isoformat()
    write_sensex(as_of, as_of, sensex_members(next_day))
    summary.append((as_of, len(n50)))

for date, added, removed in CHANGES:
    cap_month = max(m for m in months if m < date)
    overrides = {}
    if date == "2024-12-23":
        # Zomato's NSE free-float factor changed at the end of Dec 2024 (index cap / close 193 -> 692 Cr shares);
        # the post-change factor at the Nov close stands for its SENSEX weight from 23 Dec
        dec = caps("2024-12-31")["ETERNAL"]
        overrides["ETERNAL"] = dec["mcap"] / dec["close"] * caps(cap_month)["ETERNAL"]["close"]
    write_sensex(date, cap_month, sensex_members(date), overrides)

print(json.dumps(summary))
