import type { ChartMarker } from "./TimelineChart";
import { rupees, type TradePositionRow } from "../api";
import { Pnl, Table } from "./ui";

/** One trade: a single option position, or a straddle's call and put bought together. */
export interface Trade {
  strategy: string | null;
  underlying: string;
  title: string;
  detail: string;
  opened: string;
  closed: string | null;
  stages: string;
  exit: string | null;
  costs: number;
  net: number;
  /** Quantity per leg and lots (null for rows recorded before quantities were stored). */
  quantity: number | null;
  lots: number | null;
  /** "39.35 → 41.12", or per leg for a straddle. */
  prices: string;
  legs: TradePositionRow[];
}

/** "NIFTY 23300 CE 22 SEP 26" → parts; anything else is shown as it is. */
function parse(symbol: string) {
  const m = /^(\S+) (\S+) (CE|PE) (.+)$/.exec(symbol);
  return m ? { index: m[1], strike: m[2], type: m[3], expiry: m[4] } : null;
}

const time = (ts: string | null) => (ts ? ts.slice(11, 16) : null);

const px = (v: number | null | undefined) => (v == null ? "–" : v.toFixed(2));
const legPrices = (l: TradePositionRow) => `${px(l.average_cost)} → ${px(l.average_exit)}`;
const lotsOf = (l: TradePositionRow) => (l.quantity != null && l.lot_size ? Math.round(l.quantity / l.lot_size) : null);

/** +₹3,06,033 / −₹1,55,158 */
const signed = (value: number) => `${value > 0 ? "+" : value < 0 ? "−" : ""}${rupees(Math.abs(value))}`;

/**
 * Groups a straddle's two legs (same strategy, index and entry second, one call and one put) into
 * one trade with the combined net; every other position is a trade of its own.
 */
export function groupTrades(rows: TradePositionRow[]): Trade[] {
  const groups = new Map<string, TradePositionRow[]>();
  for (const row of rows) {
    const key = `${row.strategy_id ?? ""}|${row.underlying}|${row.opened_at}`;
    groups.set(key, [...(groups.get(key) ?? []), row]);
  }
  const trades: Trade[] = [];
  for (const legs of groups.values()) {
    const call = legs.find((l) => l.option_side === "CE");
    const put = legs.find((l) => l.option_side === "PE");
    if (legs.length === 2 && call && put) {
      const c = parse(call.symbol);
      const p = parse(put.symbol);
      const strikes = c && p ? (c.strike === p.strike ? `${c.strike} straddle` : `${c.strike}/${p.strike} strangle`) : "straddle";
      const closes = legs.map((l) => l.closed_at).filter((x): x is string => x != null).sort();
      const exits = [...new Set(legs.map((l) => l.exit_reason ?? "open"))];
      trades.push({
        strategy: call.strategy_id ?? null, underlying: call.underlying,
        title: `${call.underlying} ${strikes}`,
        detail: `CE ${signed(call.net)} · PE ${signed(put.net)}${c ? ` · ${c.expiry}` : ""}`,
        opened: call.opened_at, closed: closes.length === 2 ? closes[1] : null,
        stages: [...new Set(legs.flatMap((l) => l.stages))].join(" → "),
        exit: exits.join(" / "),
        costs: call.costs + put.costs, net: call.net + put.net,
        quantity: call.quantity ?? null, lots: lotsOf(call),
        prices: `CE ${legPrices(call)} · PE ${legPrices(put)}`, legs,
      });
    } else {
      for (const leg of legs) {
        const s = parse(leg.symbol);
        trades.push({
          strategy: leg.strategy_id ?? null, underlying: leg.underlying,
          title: s ? `${s.index} ${s.strike} ${s.type}` : leg.symbol, detail: s ? s.expiry : "",
          opened: leg.opened_at, closed: leg.closed_at, stages: leg.stages.join(" → "), exit: leg.exit_reason,
          costs: leg.costs, net: leg.net, quantity: leg.quantity ?? null, lots: lotsOf(leg), prices: legPrices(leg),
          legs: [leg],
        });
      }
    }
  }
  return trades.sort((a, b) => a.opened.localeCompare(b.opened));
}

/** Exit markers for a chart: one per trade of {@code strategy} in {@code underlying}, at its close. */
export function exitMarkers(rows: TradePositionRow[], underlying: string, strategy: string | null): ChartMarker[] {
  return groupTrades(rows)
    .filter((t) => t.underlying === underlying && (strategy == null || t.strategy == null || t.strategy === strategy)
      && t.closed != null)
    .map((t) => ({ t: time(t.closed)!, text: `exit ${t.exit ?? ""} ${signed(t.net)}`, tone: "info" as const }));
}

/** One value per leg, stacked (a straddle shows "CE …" over "PE …"). */
function PerLeg({ trade, value }: { trade: Trade; value: (leg: TradePositionRow) => string }) {
  if (trade.legs.length === 1) return <span className="num whitespace-nowrap">{value(trade.legs[0])}</span>;
  return (
    <div className="num whitespace-nowrap">
      {trade.legs.slice().sort((a, b) => a.option_side.localeCompare(b.option_side)).map((leg) => (
        <div key={leg.option_side}><span className="text-xs text-muted">{leg.option_side} </span>{value(leg)}</div>
      ))}
    </div>
  );
}

const paid = (t: Trade) => t.legs.every((l) => l.quantity != null && l.average_cost != null)
  ? t.legs.reduce((sum, l) => sum + (l.quantity ?? 0) * (l.average_cost ?? 0), 0) : null;

/**
 * Trades with a straddle as one row: lots, quantity, entry and exit premium (average fill per unit),
 * premium paid, time, exit reason, net and costs. Wide tables scroll inside their panel.
 */
export function TradesTable({ rows, showStrategy, empty = "No trades." }: {
  rows: TradePositionRow[];
  showStrategy: boolean;
  empty?: string;
}) {
  const trades = groupTrades(rows);
  return (
    <Table
      rows={trades}
      empty={empty}
      columns={[
        ...(showStrategy ? [{ key: "st", label: "Strategy", render: (t: Trade) => <span className="text-xs">{t.strategy ?? "–"}</span> }] : []),
        {
          key: "c", label: "Trade", render: (t: Trade) => (
            <div className="min-w-0">
              <div className="whitespace-nowrap font-medium">{t.title}</div>
              <div className="text-xs text-muted">{t.legs[0] && parse(t.legs[0].symbol)?.expiry} · {t.stages}</div>
            </div>
          ),
        },
        { key: "l", label: "Lots", align: "right", render: (t: Trade) => <PerLeg trade={t} value={(l) => { const n = lotsOf(l); return n == null ? "–" : String(n); }} /> },
        { key: "q", label: "Qty", align: "right", render: (t: Trade) => <PerLeg trade={t} value={(l) => l.quantity == null ? "–" : l.quantity.toLocaleString("en-IN")} /> },
        { key: "e", label: "Entry ₹", align: "right", render: (t: Trade) => <PerLeg trade={t} value={(l) => px(l.average_cost)} /> },
        { key: "x", label: "Exit ₹", align: "right", render: (t: Trade) => <PerLeg trade={t} value={(l) => px(l.average_exit)} /> },
        { key: "p", label: "Premium paid", align: "right", render: (t: Trade) => { const v = paid(t); return <span className="whitespace-nowrap">{v == null ? "–" : rupees(v)}</span>; } },
        {
          key: "t", label: "IST", render: (t: Trade) => (
            <span className="num whitespace-nowrap">{time(t.opened)}–{time(t.closed) ?? "open"}</span>
          ),
        },
        { key: "r", label: "Exit reason", render: (t: Trade) => <span className="text-xs">{t.exit ?? "–"}</span> },
        {
          key: "n", label: "Net", align: "right", render: (t: Trade) => (
            <div className="whitespace-nowrap">
              <Pnl value={t.net} />
              {t.legs.length === 2 && <div className="text-xs text-muted">{t.detail.split(" · ").slice(0, 2).join(" · ")}</div>}
            </div>
          ),
        },
        { key: "k", label: "Costs", align: "right", render: (t: Trade) => <span className="whitespace-nowrap text-muted">{rupees(t.costs)}</span> },
      ]}
    />
  );
}
