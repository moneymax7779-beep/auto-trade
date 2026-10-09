import { useQuery } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import { Link } from "react-router";
import { get, type PerformanceRow, type SessionRow } from "../api";
import { STRATEGY_INFO, strategyShort } from "../components/strategies";
import { ErrorNote, Loading, Panel, Pnl, Stat, Table } from "../components/ui";

const SYMBOLS = ["ALL", "NIFTY", "SENSEX"] as const;
type Symbol = (typeof SYMBOLS)[number];

/**
 * How the live PAPER trading has done: wins and losses, average rupees and premium points per win and per loss,
 * and a calendar of green and red days (NIFTY and SENSEX combined, or one of them), with the days listed below it.
 * Only closed positions count; a day with a live session and no trade shows as grey.
 */
export function PerformancePage() {
  const [symbol, setSymbol] = useState<Symbol>("ALL");
  const [strategy, setStrategy] = useState<string>("ALL");
  const rows = useQuery({ queryKey: ["performance"], queryFn: () => get<PerformanceRow[]>("/api/performance") });
  const sessions = useQuery({ queryKey: ["sessions"], queryFn: () => get<SessionRow[]>("/api/sessions") });

  const all = rows.data ?? [];
  const strategies = useMemo(() => [...new Set(all.map((r) => r.strategy_id ?? "?"))].sort(), [all]);
  const trades = useMemo(() => all.filter((r) => (symbol === "ALL" || r.underlying === symbol)
    && (strategy === "ALL" || (r.strategy_id ?? "?") === strategy)), [all, symbol, strategy]);
  // every day that had a live session (the calendar's grey days), and the session to link each day to
  const liveDays = useMemo(() => {
    const m = new Map<string, number>();
    for (const s of sessions.data ?? []) if (s.mode === "PAPER_LIVE") m.set(s.session_date, Math.max(m.get(s.session_date) ?? 0, s.id));
    return m;
  }, [sessions.data]);
  const days = useMemo(() => dayTotals(trades, liveDays), [trades, liveDays]);
  const stats = useMemo(() => summarise(trades, days), [trades, days]);

  if (rows.isLoading || sessions.isLoading) return <Loading what="performance" />;
  if (rows.error) return <ErrorNote error={rows.error} />;
  if (sessions.error) return <ErrorNote error={sessions.error} />;

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-2 text-sm">
        <div className="flex gap-1" role="group" aria-label="Symbol">
          {SYMBOLS.map((s) => (
            <button key={s} type="button" onClick={() => setSymbol(s)}
              className={`rounded px-3 py-1 ${s === symbol ? "bg-accent text-black" : "border border-line text-muted hover:text-text"}`}>
              {s === "ALL" ? "NIFTY + SENSEX" : s}
            </button>
          ))}
        </div>
        <select aria-label="Strategy" value={strategy} onChange={(e) => setStrategy(e.target.value)}
          className="rounded border border-line bg-panel px-2 py-1">
          <option value="ALL">All strategies</option>
          {strategies.map((id) => <option key={id} value={id}>{STRATEGY_INFO[id]?.name ?? id}</option>)}
        </select>
        <span className="ml-auto text-xs text-muted">live PAPER sessions · closed positions only · {liveDays.size} session days</span>
      </div>

      <Panel title="Wins and losses">
        {trades.length === 0 ? <p className="text-sm text-muted">No closed trades for this selection.</p> : (
          <div className="grid grid-cols-2 gap-4 sm:grid-cols-4 lg:grid-cols-6">
            <Stat label="Net" value={<Pnl value={stats.net} />} />
            <Stat label="Trades" value={stats.trades} />
            <Stat label="Wins / losses" value={<><span className="text-up">{stats.wins}</span> / <span className="text-down">{stats.losses}</span>{stats.flat > 0 && <span className="text-muted"> / {stats.flat} flat</span>}</>} />
            <Stat label="Win rate" value={pct(stats.wins, stats.wins + stats.losses)} />
            <Stat label="Profit factor" value={stats.grossLoss > 0 ? (stats.grossWin / stats.grossLoss).toFixed(2) : stats.grossWin > 0 ? "∞" : "–"} />
            <Stat label="Green / red days" value={<><span className="text-up">{stats.greenDays}</span> / <span className="text-down">{stats.redDays}</span></>} />
            <Stat label="Average win" value={<Pnl value={stats.avgWin} />} tone="up" />
            <Stat label="Average loss" value={<Pnl value={stats.avgLoss} />} tone="down" />
            <Stat label="Avg points per win" value={points(stats.avgPointsWin)} tone="up" />
            <Stat label="Avg points per loss" value={points(stats.avgPointsLoss)} tone="down" />
            <Stat label="Best day" value={<Pnl value={stats.bestDay?.net} />} />
            <Stat label="Worst day" value={<Pnl value={stats.worstDay?.net} />} />
          </div>
        )}
        <p className="mt-3 text-xs text-muted">
          Points are option premium per unit: exit price minus entry price of the contract, averaged over the trades.
          A straddle's two legs count as two trades. Rupee figures are net of brokerage and charges.
        </p>
      </Panel>

      <Panel title={`Calendar · ${symbol === "ALL" ? "NIFTY + SENSEX" : symbol}`}>
        <Calendar days={days} />
      </Panel>

      <Panel title="Days">
        <Table rows={[...days.values()].filter((d) => d.trades > 0).sort((a, b) => (a.date < b.date ? 1 : -1))} columns={[
          { key: "date", label: "Date", render: (d) => <Link to={`/sessions/${d.sessionId}`} className="num text-accent hover:underline">{d.date}</Link> },
          { key: "trades", label: "Trades", align: "right", render: (d) => d.trades },
          { key: "wins", label: "Wins", align: "right", render: (d) => <span className="text-up">{d.wins}</span> },
          { key: "losses", label: "Losses", align: "right", render: (d) => <span className="text-down">{d.losses}</span> },
          { key: "points", label: "Points", align: "right", render: (d) => points(d.points) },
          { key: "net", label: "Net", align: "right", render: (d) => <Pnl value={d.net} /> },
          { key: "strats", label: "Strategies", render: (d) => [...d.strategies].map(strategyShort).join(" · ") },
        ]} empty="No trades for this selection." />
      </Panel>
    </div>
  );
}

interface DayTotal {
  date: string;
  sessionId?: number;
  trades: number;
  wins: number;
  losses: number;
  net: number;
  /** premium points summed over the day's trades (exit − entry per unit) */
  points: number;
  strategies: Set<string>;
}

/** Premium points of one trade: exit minus entry per unit; falls back to realised / quantity when a price is missing. */
function tradePoints(r: PerformanceRow): number | null {
  if (r.average_exit != null && r.average_cost != null) return r.average_exit - r.average_cost;
  if (r.quantity) return r.realised / r.quantity;
  return null;
}

function dayTotals(trades: PerformanceRow[], liveDays: Map<string, number>): Map<string, DayTotal> {
  const m = new Map<string, DayTotal>();
  for (const [date, sessionId] of liveDays) m.set(date, { date, sessionId, trades: 0, wins: 0, losses: 0, net: 0, points: 0, strategies: new Set() });
  for (const r of trades) {
    const d = m.get(r.session_date) ?? { date: r.session_date, sessionId: r.session_id, trades: 0, wins: 0, losses: 0, net: 0, points: 0, strategies: new Set<string>() };
    d.trades++;
    if (r.net > 0) d.wins++; else if (r.net < 0) d.losses++;
    d.net += r.net;
    d.points += tradePoints(r) ?? 0;
    d.strategies.add(r.strategy_id ?? "?");
    m.set(r.session_date, d);
  }
  return m;
}

function summarise(trades: PerformanceRow[], days: Map<string, DayTotal>) {
  const wins = trades.filter((r) => r.net > 0), losses = trades.filter((r) => r.net < 0);
  const sum = (xs: number[]) => xs.reduce((a, b) => a + b, 0);
  const mean = (xs: number[]) => (xs.length ? sum(xs) / xs.length : null);
  const pts = (rs: PerformanceRow[]) => rs.map(tradePoints).filter((p): p is number => p != null);
  const traded = [...days.values()].filter((d) => d.trades > 0);
  return {
    trades: trades.length, wins: wins.length, losses: losses.length, flat: trades.length - wins.length - losses.length,
    net: sum(trades.map((r) => r.net)),
    grossWin: sum(wins.map((r) => r.net)), grossLoss: -sum(losses.map((r) => r.net)),
    avgWin: mean(wins.map((r) => r.net)), avgLoss: mean(losses.map((r) => r.net)),
    avgPointsWin: mean(pts(wins)), avgPointsLoss: mean(pts(losses)),
    greenDays: traded.filter((d) => d.net > 0).length, redDays: traded.filter((d) => d.net < 0).length,
    bestDay: traded.reduce<DayTotal | undefined>((b, d) => (!b || d.net > b.net ? d : b), undefined),
    worstDay: traded.reduce<DayTotal | undefined>((b, d) => (!b || d.net < b.net ? d : b), undefined),
  };
}

const pct = (n: number, of: number) => (of > 0 ? `${Math.round((100 * n) / of)} %` : "–");
const points = (v: number | null | undefined) => (v == null ? "–" : `${v > 0 ? "+" : ""}${v.toFixed(1)}`);

/** Month grids, Monday to Friday: green or red by the day's net, grey for a session day without a trade. */
function Calendar({ days }: { days: Map<string, DayTotal> }) {
  const dates = [...days.keys()].sort();
  if (dates.length === 0) return <p className="text-sm text-muted">No live session days yet.</p>;
  const months: string[] = [];
  for (let m = dates[0].slice(0, 7); m <= dates[dates.length - 1].slice(0, 7); m = nextMonth(m)) months.push(m);
  return (
    <div className="flex flex-wrap gap-6">
      {months.map((m) => <MonthGrid key={m} month={m} days={days} />)}
    </div>
  );
}

function MonthGrid({ month, days }: { month: string; days: Map<string, DayTotal> }) {
  const [y, mo] = month.split("-").map(Number);
  const first = new Date(Date.UTC(y, mo - 1, 1));
  const count = new Date(Date.UTC(y, mo, 0)).getUTCDate();
  // weeks of Mon..Fri; a cell is null outside the month or on a weekend
  const weeks: (string | null)[][] = [];
  let week: (string | null)[] = new Array((first.getUTCDay() + 6) % 7).fill(null).slice(0, 5);
  for (let d = 1; d <= count; d++) {
    const dow = (new Date(Date.UTC(y, mo - 1, d)).getUTCDay() + 6) % 7;   // 0 = Monday
    if (dow < 5) week[dow] = `${month}-${String(d).padStart(2, "0")}`;
    if (dow === 6 || d === count) { if (week.some((c) => c != null)) weeks.push([...week, null, null, null, null, null].slice(0, 5)); week = []; }
  }
  const label = first.toLocaleDateString("en-IN", { month: "long", year: "numeric", timeZone: "UTC" });
  return (
    <div>
      <div className="mb-1 text-sm font-semibold">{label}</div>
      <div className="grid grid-cols-5 gap-1 text-xs">
        {["Mon", "Tue", "Wed", "Thu", "Fri"].map((d) => <div key={d} className="text-center text-muted">{d}</div>)}
        {weeks.flat().map((date, i) => <DayCell key={i} date={date} day={date ? days.get(date) : undefined} />)}
      </div>
    </div>
  );
}

function DayCell({ date, day }: { date: string | null; day?: DayTotal }) {
  if (!date) return <div className="h-14 w-16" />;
  const n = date.slice(8);
  if (!day) return <div className="h-14 w-16 rounded border border-line/40 p-1 text-muted/50">{n}</div>;
  const tone = day.trades === 0 ? "border-line bg-panel-2 text-muted"
    : day.net > 0 ? "border-up/60 bg-up/20 text-up" : day.net < 0 ? "border-down/60 bg-down/20 text-down" : "border-line bg-panel-2";
  const body = (
    <div className={`flex h-14 w-16 flex-col justify-between rounded border p-1 ${tone}`}
      title={day.trades === 0 ? `${date}: session, no trade` : `${date}: ${day.trades} trades, ${day.wins} won, ${day.losses} lost`}>
      <div className="flex justify-between"><span>{n}</span>{day.trades > 0 && <span className="num opacity-70">{day.wins}/{day.trades}</span>}</div>
      {day.trades > 0 ? <div className="num text-right font-semibold">{compact(day.net)}</div> : <div className="text-right opacity-60">no trade</div>}
    </div>
  );
  return day.sessionId ? <Link to={`/sessions/${day.sessionId}`}>{body}</Link> : body;
}

/** ₹ in thousands or lakhs for a calendar cell: +4.5L, −13.6K. */
function compact(v: number): string {
  const a = Math.abs(v), sign = v > 0 ? "+" : v < 0 ? "−" : "";
  return a >= 100000 ? `${sign}${(a / 100000).toFixed(1)}L` : a >= 1000 ? `${sign}${(a / 1000).toFixed(1)}K` : `${sign}${Math.round(a)}`;
}

function nextMonth(m: string): string {
  const [y, mo] = m.split("-").map(Number);
  return mo === 12 ? `${y + 1}-01` : `${y}-${String(mo + 1).padStart(2, "0")}`;
}
