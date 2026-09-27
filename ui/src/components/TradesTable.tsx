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
  legs: TradePositionRow[];
}

/** "NIFTY 23300 CE 22 SEP 26" → parts; anything else is shown as it is. */
function parse(symbol: string) {
  const m = /^(\S+) (\S+) (CE|PE) (.+)$/.exec(symbol);
  return m ? { index: m[1], strike: m[2], type: m[3], expiry: m[4] } : null;
}

const time = (ts: string | null) => (ts ? ts.slice(11, 16) : null);

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
        detail: `CE ${rupees(call.net)} · PE ${rupees(put.net)}${c ? ` · ${c.expiry}` : ""}`,
        opened: call.opened_at, closed: closes.length === 2 ? closes[1] : null,
        stages: [...new Set(legs.flatMap((l) => l.stages))].join(" → "),
        exit: exits.join(" / "),
        costs: call.costs + put.costs, net: call.net + put.net, legs,
      });
    } else {
      for (const leg of legs) {
        const s = parse(leg.symbol);
        trades.push({
          strategy: leg.strategy_id ?? null, underlying: leg.underlying,
          title: s ? `${s.index} ${s.strike} ${s.type}` : leg.symbol, detail: s ? s.expiry : "",
          opened: leg.opened_at, closed: leg.closed_at, stages: leg.stages.join(" → "), exit: leg.exit_reason,
          costs: leg.costs, net: leg.net, legs: [leg],
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
    .map((t) => ({ t: time(t.closed)!, text: `exit ${t.exit ?? ""} ${rupees(t.net)}`, tone: "info" as const }));
}

/** Trades with a straddle as one row; narrow enough for a phone or a side pane. */
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
              {t.detail && <div className="whitespace-nowrap text-xs text-muted">{t.detail}</div>}
            </div>
          ),
        },
        {
          key: "t", label: "IST", render: (t: Trade) => (
            <span className="num whitespace-nowrap">{time(t.opened)}–{time(t.closed) ?? "open"}</span>
          ),
        },
        { key: "g", label: "Stages", render: (t: Trade) => <span className="text-xs">{t.stages}</span> },
        { key: "x", label: "Exit", render: (t: Trade) => <span className="text-xs">{t.exit ?? "–"}</span> },
        { key: "n", label: "Net", align: "right", render: (t: Trade) => <span className="whitespace-nowrap"><Pnl value={t.net} /></span> },
        { key: "k", label: "Costs", align: "right", render: (t: Trade) => <span className="whitespace-nowrap text-muted">{rupees(t.costs)}</span> },
      ]}
    />
  );
}
