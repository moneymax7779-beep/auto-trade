import { rupees, type PositionRow } from "../api";
import { Pnl, Table } from "./ui";

/** One live trade: a single option position, or a straddle's call and put (grouped by strategy, index, entry). */
interface LiveTrade {
  strategy: string;
  underlying: string;
  title: string;
  expiry: string;
  stages: string;
  opened: string | null;
  closed: string | null;
  state: string;
  legs: PositionRow[];
  paid: number;
  value: number | null;
  net: number;
  targetPct: number | null;
  stopPct: number | null;
}

function parse(symbol: string) {
  const m = /^(\S+) (\S+) (CE|PE) (.+)$/.exec(symbol);
  return m ? { index: m[1], strike: m[2], type: m[3], expiry: m[4] } : null;
}

const px = (v: number | null | undefined) => (v == null || !Number.isFinite(v) ? "–" : v.toFixed(2));
const hhmm = (t: string | null) => (t ? t.slice(0, 5) : null);
const heldOrBought = (l: PositionRow) => (l.quantity > 0 ? l.quantity : l.boughtQuantity ?? l.quantity);

function group(rows: PositionRow[]): LiveTrade[] {
  const groups = new Map<string, PositionRow[]>();
  for (const row of rows) {
    const key = row.leg ? `${row.strategy}|${row.underlying}|${row.opened}` : `${row.strategy}|${row.underlying}|${row.opened}|${row.symbol}`;
    groups.set(key, [...(groups.get(key) ?? []), row]);
  }
  return [...groups.values()].map((legs) => {
    legs.sort((a, b) => a.side.localeCompare(b.side));
    const straddle = legs.length === 2;
    const first = parse(legs[0].symbol);
    const strikes = legs.map((l) => parse(l.symbol)?.strike);
    const title = !first ? legs[0].symbol : straddle
      ? `${first.index} ${strikes[0] === strikes[1] ? `${strikes[0]} straddle` : `${strikes[0]}/${strikes[1]} strangle`}`
      : `${first.index} ${first.strike} ${first.type}`;
    const paid = legs.reduce((s, l) => s + (l.premiumPaid ?? l.averageCost * heldOrBought(l)), 0);
    const open = legs.some((l) => l.state !== "CLOSED");
    const value = open
      ? (legs.every((l) => l.value != null) ? legs.reduce((s, l) => s + (l.value ?? 0), 0) : null)
      : legs.reduce((s, l) => s + (l.exitPrice ?? 0) * heldOrBought(l), 0);
    return {
      strategy: legs[0].strategy ?? "–", underlying: legs[0].underlying, title, expiry: first?.expiry ?? "",
      stages: [...new Set(legs.flatMap((l) => l.stages))].join(" → "),
      opened: legs[0].opened, closed: open ? null : legs.map((l) => l.closed ?? "").sort().at(-1) ?? null,
      state: open ? [...new Set(legs.map((l) => l.state))].join("/") : [...new Set(legs.map((l) => l.exitReason ?? "–"))].join(" / "),
      legs, paid, value, net: legs.reduce((s, l) => s + l.net, 0),
      targetPct: legs[0].targetPct ?? null, stopPct: legs[0].stopPct ?? null,
    };
  });
}

function PerLeg({ trade, value }: { trade: LiveTrade; value: (leg: PositionRow) => string }) {
  if (trade.legs.length === 1) return <span className="num whitespace-nowrap">{value(trade.legs[0])}</span>;
  return (
    <div className="num whitespace-nowrap">
      {trade.legs.map((leg) => (
        <div key={leg.side}><span className="text-xs text-muted">{leg.side} </span>{value(leg)}</div>
      ))}
    </div>
  );
}

/** Where a straddle's combined value sits between its stop and its target. */
function BracketBar({ pct, stop, target }: { pct: number; stop: number; target: number }) {
  const span = stop + target;
  const at = Math.max(0, Math.min(100, ((pct + stop) / span) * 100));
  const zero = (stop / span) * 100;
  return (
    <div className="mt-1 w-40" title={`stop −${stop}% · target +${target}%`}>
      <div className="relative h-1.5 rounded bg-panel-2">
        <div className="absolute top-[-2px] h-2.5 w-px bg-muted" style={{ left: `${zero}%` }} />
        <div className={`absolute top-[-3px] h-3 w-1.5 rounded ${pct >= 0 ? "bg-up" : "bg-down"}`} style={{ left: `calc(${at}% - 3px)` }} />
      </div>
      <div className="mt-0.5 flex justify-between text-[10px] text-muted"><span>−{stop}%</span><span>+{target}%</span></div>
    </div>
  );
}

/**
 * The session's positions as trades: open ones valued at the current bid (with a straddle's
 * stop-to-target bar), closed ones at their exit. A straddle's two legs are one row.
 */
export function LivePositions({ rows, closed, empty }: { rows: PositionRow[]; closed: boolean; empty: string }) {
  const trades = group(rows);
  return (
    <Table
      rows={trades}
      empty={empty}
      columns={[
        { key: "s", label: "Strategy", render: (t: LiveTrade) => <span className="text-xs">{t.strategy}</span> },
        {
          key: "c", label: "Trade", render: (t: LiveTrade) => (
            <div>
              <div className="whitespace-nowrap font-medium">{t.title}</div>
              <div className="text-xs text-muted">{t.expiry} · {t.stages}</div>
            </div>
          ),
        },
        { key: "l", label: "Lots", align: "right", render: (t: LiveTrade) => <PerLeg trade={t} value={(l) => l.lotSize ? String(Math.round(heldOrBought(l) / l.lotSize)) : "–"} /> },
        { key: "q", label: "Qty", align: "right", render: (t: LiveTrade) => <PerLeg trade={t} value={(l) => heldOrBought(l).toLocaleString("en-IN")} /> },
        { key: "e", label: "Entry ₹", align: "right", render: (t: LiveTrade) => <PerLeg trade={t} value={(l) => px(l.averageCost)} /> },
        {
          key: "n", label: closed ? "Exit ₹" : "Now ₹ (bid)", align: "right",
          render: (t: LiveTrade) => <PerLeg trade={t} value={(l) => px(closed ? l.exitPrice : l.bid)} />,
        },
        { key: "p", label: "Premium paid", align: "right", render: (t: LiveTrade) => <span className="whitespace-nowrap">{rupees(t.paid)}</span> },
        {
          key: "v", label: closed ? "Sold for" : "Value now", align: "right",
          render: (t: LiveTrade) => <span className="whitespace-nowrap">{t.value == null ? "–" : rupees(t.value)}</span>,
        },
        {
          key: "pl", label: "P&L", align: "right", render: (t: LiveTrade) => {
            const pct = t.value != null && t.paid > 0 ? (t.value / t.paid - 1) * 100 : null;
            return (
              <div className="whitespace-nowrap">
                <Pnl value={t.net} />
                {pct != null && <div className={`num text-xs ${pct >= 0 ? "text-up" : "text-down"}`}>{pct >= 0 ? "+" : ""}{pct.toFixed(1)}%</div>}
                {!closed && pct != null && t.targetPct != null && t.stopPct != null
                  && <BracketBar pct={pct} stop={t.stopPct} target={t.targetPct} />}
              </div>
            );
          },
        },
        { key: "st", label: closed ? "Exit reason" : "State", render: (t: LiveTrade) => <span className="text-xs">{t.state}</span> },
        { key: "t", label: "IST", render: (t: LiveTrade) => <span className="num whitespace-nowrap">{hhmm(t.opened)}–{hhmm(t.closed) ?? "now"}</span> },
      ]}
    />
  );
}
