import { type ReactNode, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { get, type ExecutionAccount, type LiveBook, type PositionRow } from "../api";
import { ErrorNote } from "./ui";

/**
 * The Overview page's positions, one design for real money and paper (6 Nov 2026, user: "I just want the application
 * traded positions shown in a better UX", then "can we move to the top at the best place"): a totals row, one card per open
 * position (entry → bid, P&L in ₹ and %, a rail with stop · entry · now · best · target) and one line per closed trade
 * with its exit, why it closed, time held and net after charges.
 * - {@link LiveBooks}: every LIVE account the user can see, auto-trade's own positions only (never the broker's other
 *   holdings); nothing renders while no account is LIVE.
 * - {@link PaperPositions}: the running PAPER session's positions (PAPER-1).
 */

// ------------------------------------------------------------------------------------------------- shared view model

export interface OpenView {
  key: string | number;
  strategy: string;
  symbol: string;
  quantity: number;
  lotSize: number | null;
  entry: number;
  /** IST wall time, ISO local date-time */
  opened: string;
  bid: number | null;
  quoteAt?: string | null;
  best: number | null;
  stop: number | null;
  target?: number | null;
  /** the line under the rail: where the stop is and whether it works */
  stopNote: ReactNode;
  pnl: number | null;
  pnlPct: number | null;
  leg?: string;
}

export interface ClosedView {
  key: string | number;
  strategy: string;
  symbol: string;
  quantity: number;
  lotSize: number | null;
  entry: number;
  exit: number | null;
  exitReason: string | null;
  opened: string;
  closed: string;
  charges: number;
  net: number;
  pct: number | null;
}

interface TotalView {
  label: string;
  value: string;
  cls?: string;
  sub?: string;
}

type Tab = "open" | "today" | "week";

const money = (v: number | null | undefined, sign = true) =>
  v == null || !Number.isFinite(v) ? "–"
    : `${sign && v > 0 ? "+" : v < 0 ? "−" : ""}₹${Math.abs(v).toLocaleString("en-IN", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
const tone = (v: number | null | undefined) => (v == null ? "" : v > 0 ? "text-up" : v < 0 ? "text-down" : "");
const px = (v: number | null | undefined) => (v == null || !Number.isFinite(v) ? "–" : v.toFixed(2));
const hhmm = (iso: string | null | undefined) => (iso ? iso.slice(11, 16) : "–");
const dayLabel = (iso: string) => new Date(`${iso.slice(0, 10)}T12:00:00`).toLocaleDateString("en-IN",
  { weekday: "short", day: "numeric", month: "short" });
const istToday = () => new Date(Date.now() + 330 * 60_000).toISOString().slice(0, 10);

/** "NIFTY 24650 CE 09 NOV 26" -> contract "NIFTY 24650 CE" and expiry "9 Nov". */
function contract(symbol: string) {
  const m = /^(\S+ \S+ (?:CE|PE)) (\d{1,2}) ([A-Za-z]{3})/.exec(symbol);
  return m ? { name: m[1], expiry: `${Number(m[2])} ${m[3].charAt(0)}${m[3].slice(1).toLowerCase()}` } : { name: symbol, expiry: "" };
}

/** The ISO strings are IST wall times without a zone; "now" is compared on the same clock. */
function held(from: string, to?: string | null) {
  const a = new Date(from).getTime();
  const b = to ? new Date(to).getTime() : Date.now() + (330 + new Date().getTimezoneOffset()) * 60_000;
  const min = Math.max(0, Math.round((b - a) / 60_000));
  return min < 60 ? `${min} m` : `${Math.floor(min / 60)} h ${min % 60} m`;
}

const lotsText = (qty: number, lot: number | null) =>
  lot ? `${Math.round(qty / lot)} lot${Math.round(qty / lot) === 1 ? "" : "s"} · ${qty.toLocaleString("en-IN")}` : `${qty} qty`;

// ------------------------------------------------------------------------------------------------- LIVE accounts

export function LiveBooks() {
  const accounts = useQuery({ queryKey: ["exec-accounts"], queryFn: () => get<ExecutionAccount[]>("/api/execution/accounts"),
    refetchInterval: 30_000 });
  const live = (accounts.data ?? []).filter((a) => a.mode === "LIVE");
  if (live.length === 0) return null;
  return <>{live.map((a) => <LiveBookPanel key={a.id} accountId={a.id} />)}</>;
}

function LiveBookPanel({ accountId }: { accountId: number }) {
  const book = useQuery({ queryKey: ["live-book", accountId],
    queryFn: () => get<LiveBook>(`/api/execution/accounts/${accountId}/live-book?days=7`), refetchInterval: 5_000 });
  if (book.error) return <ErrorNote error={book.error} />;
  if (!book.data) return null;
  const b = book.data;
  const open: OpenView[] = b.open.map((p) => ({
    key: p.id, strategy: p.strategy, symbol: p.symbol, quantity: p.quantity, lotSize: p.lotSize, entry: p.entry,
    opened: p.opened, bid: p.bid, quoteAt: p.quoteAt ?? null, best: p.best, stop: p.stop, pnl: p.pnl ?? null,
    pnlPct: p.pnlPct ?? null,
    stopNote: p.stop == null ? "no stop on record"
      : p.stopWorking ? <>stop resting at the broker{p.bid != null ? <> · {px(p.bid - p.stop)} pts below the bid · {money(p.atRisk ?? null, false)} at risk</> : null}</>
      : <span className="text-down">stop {p.stopStatus?.toLowerCase() ?? "not working"} at the broker</span>,
  }));
  const closed: ClosedView[] = b.closed.map((c) => ({ key: c.id, strategy: c.strategy, symbol: c.symbol, quantity: c.quantity,
    lotSize: c.lotSize, entry: c.entry, exit: c.exit, exitReason: c.exitReason, opened: c.opened, closed: c.closed,
    charges: c.charges, net: c.net, pct: c.pct }));
  const totals: TotalView[] = [
    { label: "Open P&L", value: money(b.summary.openPnl), cls: tone(b.summary.openPnl) },
    { label: "Realised today", value: money(b.summary.realisedToday), cls: tone(b.summary.realisedToday) },
    { label: "At risk to stops", value: money(b.summary.atRisk, false) },
    { label: `Since LIVE${b.summary.sinceLiveFrom ? ` (${dayLabel(b.summary.sinceLiveFrom)})` : ""}, net`,
      value: money(b.summary.sinceLiveNet), cls: tone(b.summary.sinceLiveNet), sub: `${b.summary.sinceLiveTrades} closed trades` },
  ];
  return (
    <PositionsPanel title={<>Auto-trade positions · {b.account}</>} badge="LIVE" note="only trades auto-trade placed"
      banner={b.killSwitches.length > 0 ? <><b>Entries stopped:</b> {b.killSwitches.join(" · ")} <span className="opacity-80">(exits are never blocked)</span></> : null}
      totals={totals} open={open} closed={closed} weekly footnote="prices from the own capture · refreshed every 5 s · P&L at the bid" />
  );
}

// ------------------------------------------------------------------------------------------------- PAPER session

/** The running PAPER session's positions (from /api/status), in the same design. */
export function PaperPositions({ account, date, openRows, closedRows }: {
  account: string; date: string; openRows: PositionRow[]; closedRows: PositionRow[];
}) {
  const iso = (t: string | null) => (t ? (t.length > 8 ? t : `${date}T${t}`) : `${date}T00:00:00`);
  let atRisk = 0;
  const open: OpenView[] = openRows.map((r, i) => {
    const stop = r.stopPct != null ? r.averageCost * (1 - r.stopPct / 100) : null;
    const target = r.targetPct != null ? r.averageCost * (1 + r.targetPct / 100) : null;
    const bid = r.bid ?? null;
    const pnl = r.unrealised ?? (bid != null ? (bid - r.averageCost) * r.quantity : null);
    const risk = stop != null && bid != null ? Math.max(0, (bid - stop) * r.quantity) : null;
    if (risk != null) atRisk += risk;
    return {
      key: `${r.strategy}|${r.symbol}|${r.opened}|${i}`, strategy: r.strategy ?? "", symbol: r.symbol, quantity: r.quantity,
      lotSize: r.lotSize ?? null, entry: r.averageCost, opened: iso(r.opened), bid, best: null, stop, target, pnl,
      pnlPct: pnl != null && r.averageCost > 0 ? (100 * pnl) / (r.averageCost * r.quantity) : null, leg: r.leg || undefined,
      stopNote: stop == null ? "no stop"
        : <>engine stop −{r.stopPct} %{bid != null ? <> · {px(bid - stop)} pts below the bid · {money(risk, false)} at risk</> : null}{target != null ? <> · target +{r.targetPct} %</> : null}</>,
    };
  });
  const closed: ClosedView[] = closedRows.map((r, i) => {
    const qty = r.boughtQuantity ?? r.quantity;
    return {
      key: `${r.strategy}|${r.symbol}|${r.opened}|${i}`, strategy: r.strategy ?? "", symbol: r.symbol, quantity: qty,
      lotSize: r.lotSize ?? null, entry: r.averageCost, exit: r.exitPrice ?? null, exitReason: r.exitReason, opened: iso(r.opened),
      closed: iso(r.closed), charges: r.costs ?? 0, net: r.net,
      pct: r.premiumPaid ? (100 * (r.realised ?? r.net)) / r.premiumPaid : null,
    };
  });
  const openPnl = open.reduce((a, o) => a + (o.pnl ?? 0), 0);
  const realised = closed.reduce((a, c) => a + c.net, 0);
  const won = closed.filter((c) => c.net > 0).length;
  const totals: TotalView[] = [
    { label: "Open P&L", value: money(openPnl), cls: tone(openPnl) },
    { label: "Realised today", value: money(realised), cls: tone(realised), sub: `${closed.length} closed · ${won} won` },
    { label: "At risk to stops", value: money(atRisk, false) },
  ];
  return (
    <PositionsPanel title={<>Positions · {account}</>} badge="PAPER" note="paper money, the live strategy set"
      totals={totals} open={open} closed={closed} footnote="P&L at the bid · costs included in net" />
  );
}

// ------------------------------------------------------------------------------------------------- the panel

function PositionsPanel({ title, badge, note, banner, totals, open, closed, weekly, footnote }: {
  title: ReactNode; badge: "LIVE" | "PAPER"; note: string; banner?: ReactNode; totals: TotalView[]; open: OpenView[];
  closed: ClosedView[]; weekly?: boolean; footnote: string;
}) {
  const [tab, setTab] = useState<Tab>(weekly ? "open" : "today");
  const today = istToday();
  const closedToday = weekly ? closed.filter((c) => c.closed.startsWith(today)) : closed;
  const shown = tab === "week" ? closed : tab === "today" ? closedToday : [];
  const tabs: [Tab, string][] = weekly
    ? [["open", `Open ${open.length}`], ["today", `Today ${open.length + closedToday.length}`], ["week", `This week ${open.length + closed.length}`]]
    : [["open", `Open ${open.length}`], ["today", `Today ${open.length + closed.length}`]];
  const badgeCls = badge === "LIVE" ? "border-rose-300 bg-rose-50 text-rose-700" : "border-amber-300 bg-amber-50 text-amber-700";
  return (
    <section className="min-w-0 rounded-lg border border-line bg-panel shadow-sm">
      <header className="flex flex-wrap items-center justify-between gap-3 border-b border-line px-4 py-3">
        <h2 className="flex flex-wrap items-center gap-2 text-sm font-semibold tracking-wide">
          {title}
          <span className={`inline-flex items-center gap-1.5 rounded-full border px-2 py-0.5 text-[11px] font-bold ${badgeCls}`}>
            <span className="h-1.5 w-1.5 rounded-full bg-current" />{badge}</span>
          <span className="font-normal text-muted">{note}</span>
        </h2>
        <div className="inline-flex overflow-hidden rounded-md border border-line text-sm">
          {tabs.map(([k, label]) => (
            <button key={k} onClick={() => setTab(k)}
              className={`px-3 py-1.5 ${tab === k ? "bg-panel-2 font-medium" : "text-muted hover:text-text"}`}>{label}</button>
          ))}
        </div>
      </header>

      {banner && <div className="border-b border-line bg-rose-50 px-4 py-2 text-sm text-rose-800">{banner}</div>}

      <div className="flex flex-wrap items-end gap-x-10 gap-y-3 border-b border-line px-4 py-3">
        {totals.map((t) => (
          <div key={t.label}>
            <div className="text-xs text-muted">{t.label}</div>
            <div className={`num text-2xl font-bold ${t.cls ?? ""}`}>{t.value}</div>
            {t.sub && <div className="text-xs text-muted">{t.sub}</div>}
          </div>
        ))}
        <div className="ml-auto max-w-56 text-right text-xs text-muted">{footnote}</div>
      </div>

      <div className="px-4 pt-3">
        <div className="mb-2 text-xs font-semibold uppercase tracking-wider text-muted">Open</div>
        {open.length === 0 ? <p className="pb-3 text-sm text-muted">Flat: no open position.</p>
          : open.map((p) => <OpenCard key={p.key} p={p} />)}
      </div>

      {tab !== "open" && (
        <div className="pt-2">
          <div className="mb-1 px-4 text-xs font-semibold uppercase tracking-wider text-muted">{tab === "today" ? "Closed today" : "Closed this week"}</div>
          {shown.length === 0 ? <p className="px-4 pb-3 text-sm text-muted">None.</p> : <ClosedTable rows={shown} showDay={tab === "week"} />}
        </div>
      )}
    </section>
  );
}

function OpenCard({ p }: { p: OpenView }) {
  const c = contract(p.symbol);
  const edge = p.pnl == null ? "border-l-line" : p.pnl >= 0 ? "border-l-up" : "border-l-down";
  return (
    <div className={`mb-3 grid grid-cols-1 items-center gap-3 rounded-lg border border-line border-l-4 p-4 md:grid-cols-[1.3fr_0.9fr_2fr_0.8fr] ${edge}`}>
      <div className="min-w-0">
        <div className="text-lg font-bold">{c.name}{p.leg ? <span className="ml-2 text-xs font-normal text-muted">straddle leg</span> : null}</div>
        <div className="mt-1 flex flex-wrap items-center gap-1.5 text-xs text-muted">
          {c.expiry && <span>expires {c.expiry}</span>}
          <Tag strategy>{p.strategy}</Tag>
          <Tag>{lotsText(p.quantity, p.lotSize)}</Tag>
        </div>
        <div className="mt-1 text-xs text-muted">bought {hhmm(p.opened)} · held {held(p.opened)}</div>
      </div>
      <div>
        <div className="text-xs text-muted">Entry → bid</div>
        <div className="num text-lg font-bold">{px(p.entry)} → {px(p.bid)}</div>
        <div className="text-xs text-muted">{money(p.entry * p.quantity, false)} paid{p.quoteAt ? ` · ${hhmm(p.quoteAt)}` : p.bid == null ? " · no recent quote" : ""}</div>
      </div>
      <Rail p={p} />
      <div className="text-right">
        <div className={`num text-xl font-bold ${tone(p.pnl)}`}>{p.pnl != null ? money(p.pnl) : <span className="text-base font-normal text-muted">no price</span>}</div>
        <div className={`text-xs ${tone(p.pnl)}`}>{p.pnlPct != null ? `${p.pnlPct > 0 ? "+" : ""}${p.pnlPct.toFixed(1)} %` : ""}</div>
      </div>
    </div>
  );
}

/** Stop · entry · now · best · target on one line, to scale: coloured ticks on the rail, their values in a legend. */
function Rail({ p }: { p: OpenView }) {
  const values = [p.stop, p.entry, p.bid, p.best, p.target].filter((v): v is number => v != null && Number.isFinite(v));
  const lo = Math.min(...values), hi = Math.max(...values);
  const pad = (hi - lo) * 0.06 || 1;
  const at = (v: number) => `${(100 * (v - (lo - pad))) / (hi - lo + 2 * pad)}%`;
  const gain = p.bid != null && p.bid >= p.entry;
  return (
    <div className="min-w-0">
      <div className="text-xs text-muted">Premium: stop · entry · now{p.best != null ? " · best so far" : ""}{p.target != null ? " · target" : ""}</div>
      <div className="relative mt-2 h-5">
        <div className="absolute inset-x-0 top-2 h-1.5 rounded bg-panel-2" />
        {p.bid != null && (
          <div className={`absolute top-2 h-1.5 rounded ${gain ? "bg-up/60" : "bg-down/50"}`}
            style={{ left: at(Math.min(p.entry, p.bid)), width: `calc(${at(Math.max(p.entry, p.bid))} - ${at(Math.min(p.entry, p.bid))})` }} />
        )}
        {p.stop != null && <Tick at={at(p.stop)} cls="bg-down" />}
        {p.target != null && <Tick at={at(p.target)} cls="bg-accent" />}
        <Tick at={at(p.entry)} cls="bg-muted" />
        {p.best != null && <Tick at={at(p.best)} cls="bg-muted/50" thin />}
        {p.bid != null && <Tick at={at(p.bid)} cls={gain ? "bg-up" : "bg-down"} />}
      </div>
      <div className="mt-1 flex flex-wrap gap-x-3 text-xs">
        {p.stop != null && <span className="text-down">stop {px(p.stop)}</span>}
        <span className="text-muted">entry {px(p.entry)}</span>
        {p.bid != null && <span className={gain ? "text-up" : "text-down"}>now {px(p.bid)}</span>}
        {p.best != null && <span className="text-muted">best {px(p.best)}</span>}
        {p.target != null && <span className="text-accent">target {px(p.target)}</span>}
      </div>
      <div className="mt-1 text-xs text-muted">{p.stopNote}</div>
    </div>
  );
}

function Tick({ at, cls, thin }: { at: string; cls: string; thin?: boolean }) {
  return <span className={`absolute top-0 h-5 -translate-x-1/2 rounded ${thin ? "w-0.5" : "w-1"} ${cls}`} style={{ left: at }} />;
}

function Tag({ children, strategy }: { children: ReactNode; strategy?: boolean }) {
  return <span className={`rounded border px-1.5 py-0.5 text-[11px] ${strategy ? "border-blue-300 bg-blue-50 text-blue-700" : "border-line text-muted"}`}>{children}</span>;
}

const REASONS: Record<string, string> = { STOP: "stop", MANUAL: "sold by hand", TARGET: "target", FLAT_BY: "end of day",
  TRAIL: "trail", EOD_SQUARE_OFF: "square-off", FLATTEN: "flattened", TIME_60: "60-min time stop" };

function ClosedTable({ rows, showDay }: { rows: ClosedView[]; showDay: boolean }) {
  const heads = ["Day", "Contract", "Strategy", "Lots · qty", "Entry → exit", "Exit", "In → out", "P&L %", "Charges", "Net"];
  const left = new Set(["Day", "Contract", "Strategy", "Exit", "In → out"]);
  return (
    <div className="overflow-x-auto">
      <table className="w-full whitespace-nowrap text-sm">
        <thead><tr className="border-b border-line text-xs text-muted">
          {heads.filter((h) => showDay || h !== "Day").map((h) =>
            <th key={h} className={`px-3 py-2 font-medium ${left.has(h) ? "text-left" : "text-right"}`}>{h}</th>)}
        </tr></thead>
        <tbody>
          {rows.map((r) => {
            const c = contract(r.symbol);
            return (
              <tr key={r.key} className="border-b border-line">
                {showDay && <td className="num px-3 py-2.5">{dayLabel(r.closed)}</td>}
                <td className="px-3 py-2.5"><b>{c.name}</b> <span className="text-muted">· {c.expiry}</span></td>
                <td className="px-3 py-2.5"><Tag strategy>{r.strategy}</Tag></td>
                <td className="num px-3 py-2.5 text-right">{lotsText(r.quantity, r.lotSize)}</td>
                <td className="num px-3 py-2.5 text-right">{px(r.entry)} → {px(r.exit)}</td>
                <td className="px-3 py-2.5"><Tag>{REASONS[r.exitReason ?? ""] ?? r.exitReason?.toLowerCase().replace(/_/g, " ") ?? "–"}</Tag></td>
                <td className="num px-3 py-2.5 text-muted">{hhmm(r.opened)} → {hhmm(r.closed)} · {held(r.opened, r.closed)}</td>
                <td className={`num px-3 py-2.5 text-right ${tone(r.pct)}`}>{r.pct != null ? `${r.pct > 0 ? "+" : ""}${r.pct.toFixed(1)} %` : "–"}</td>
                <td className="num px-3 py-2.5 text-right text-muted">{r.charges.toLocaleString("en-IN", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}</td>
                <td className={`num px-3 py-2.5 text-right font-semibold ${tone(r.net)}`}>{money(r.net)}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
