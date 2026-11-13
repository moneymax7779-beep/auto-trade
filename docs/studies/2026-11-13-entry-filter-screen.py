import csv, statistics
R = list(csv.DictReader(open(".local/trade-filters/trades.csv")))
f = lambda x: float(x) if x not in ("", None) else None
T = []
for r in R:
    d = 1 if r["side"] == "CE" else -1
    h, m, s = map(int, r["open"].split(":")); tmin = h * 60 + m - 555
    atr3 = f(r["structure.atr3m"]); em = f(r["regime.expectedMoveDaily"]); spot = f(r["spot"])
    room = f(r["levels.roomAbovePoints"] if d == 1 else r["levels.roomBelowPoints"])
    dh, dl, do = f(r["structure.dayHigh"]), f(r["structure.dayLow"]), f(r["structure.dayOpen"])
    T.append(dict(day=r["day"], u=r["u"], st=r["strategy"], side=r["side"], open=r["open"], net=float(r["net"]), exit=r["exit"],
                  tmin=tmin,
                  vwap=d * f(r["structure.distVwapAtr"]) if f(r["structure.distVwapAtr"]) is not None else None,
                  ema=d * f(r["structure.distEma20Atr"]) if f(r["structure.distEma20Atr"]) is not None else None,
                  room_atr=room / atr3 if room is not None and atr3 else None,
                  room_em=room / em if room is not None and em else None,
                  used=f(r["regime.dayRangeVsExpected"]),
                  chase=d * (spot - do) / em if None not in (spot, do, em) else None,
                  to_ext=((dh - spot) if d == 1 else (spot - dl)) / atr3 if None not in (dh, dl, spot, atr3) and atr3 else None,
                  iv=f(r["options.atmIv"]), dte=f(r["regime.dteTradingDays"]), rvol=f(r["futures.rvolTod"])))
HALF = "2026-10-19"
tot = sum(t["net"] for t in T)
print(f"trades {len(T)}  net {tot/1e5:.2f} L  wins {sum(t['net']>0 for t in T)}")
def show(name, pred):
    rm = [t for t in T if pred(t)]
    if not rm: print(f"{name:42s} removes 0"); return
    w = [t for t in rm if t["net"] > 0]; l = [t for t in rm if t["net"] <= 0]
    h1 = sum(t["net"] for t in rm if t["day"] <= HALF); h2 = sum(t["net"] for t in rm if t["day"] > HALF)
    sts = {}
    for t in rm: sts[t["st"][:4]] = sts.get(t["st"][:4], 0) + t["net"]
    print(f"{name:42s} removes {len(rm):3d} (W{len(w)}/L{len(l)})  net removed {sum(t['net'] for t in rm)/1e5:+7.2f} L "
          f"[winners {sum(t['net'] for t in w)/1e5:+6.2f}, losers {sum(t['net'] for t in l)/1e5:+6.2f}]  halves {h1/1e5:+6.2f} / {h2/1e5:+6.2f}  "
          + " ".join(f"{k}:{v/1e5:+.2f}" for k, v in sorted(sts.items())))
nn = lambda k, op: (lambda t: t[k] is not None and op(t[k]))
for cut in (15, 20, 30):
    show(f"entry before 09:15+{cut} min", lambda t, c=cut: t["tmin"] < c)
show("entry after 14:30", lambda t: t["tmin"] >= 315)
for k in (1.5, 2.5, 3.5):
    show(f"VWAP stretch in trade dir > {k} ATR", nn("vwap", lambda v, k=k: v > k))
for k in (1.0, 2.0, 3.0):
    show(f"EMA20 stretch in trade dir > {k} ATR", nn("ema", lambda v, k=k: v > k))
for k in (0.5, 1.0, 2.0):
    show(f"room to next wall < {k} ATR3m", nn("room_atr", lambda v, k=k: v < k))
for k in (0.1, 0.2):
    show(f"room to next wall < {k} x expected move", nn("room_em", lambda v, k=k: v < k))
for k in (0.8, 1.0):
    show(f"day range used > {k} x expected", nn("used", lambda v, k=k: v > k))
for k in (0.4, 0.6):
    show(f"already moved > {k} x exp. move from open (chase)", nn("chase", lambda v, k=k: v > k))
for k in (0.5, 1.0):
    show(f"within {k} ATR3m of the day's extreme (dir)", nn("to_ext", lambda v, k=k: v < k))
show("against the open (moved < -0.3 em from open)", nn("chase", lambda v: v < -0.3))
print()
for st in sorted({t["st"] for t in T}):
    s = [t for t in T if t["st"] == st]
    print(f"{st:20s} n {len(s):3d} net {sum(t['net'] for t in s)/1e5:+7.2f} L hit {sum(t['net']>0 for t in s)/len(s):.0%}  "
          f"median vwap {statistics.median([t['vwap'] for t in s if t['vwap'] is not None]):+.2f}  "
          f"median tmin {statistics.median([t['tmin'] for t in s])}")
# winners vs losers medians
for k in ("tmin", "vwap", "ema", "room_atr", "used", "chase", "to_ext", "iv", "rvol"):
    w = [t[k] for t in T if t["net"] > 0 and t[k] is not None]; l = [t[k] for t in T if t["net"] <= 0 and t[k] is not None]
    print(f"{k:9s} winners median {statistics.median(w):+.2f} (n {len(w)})  losers median {statistics.median(l):+.2f} (n {len(l)})")
