import { type ReactNode, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { get, type ExecutionAccount, type LiveBook, type LiveClosedPosition, type LiveOpenPosition } from "../api";
import { ErrorNote } from "./ui";

/**
 * "Auto-trade positions" for every LIVE account the user can see (6 Nov 2026, user: "I just want the application traded
 * positions shown in a better UX"): only the positions auto-trade placed, never the broker's other holdings. A total row,
 * one card per open position (entry → price, P&L in ₹ and %, a rail with stop · entry · now · best) and one line per closed
 * trade with its exit, time held and net after charges. Nothing renders while no account is LIVE.
 */
export function LiveBooks() {
  const accounts = useQuery({ queryKey: ["exec-accounts"], queryFn: () => get<ExecutionAccount[]>("/api/execution/accounts"),
    refetchInterval: 30_000 });
  const live = (accounts.data ?? []).filter((a) => a.mode === "LIVE");
  if (live.length === 0) return null;
  return <>{live.map((a) => <LiveBookPanel key={a.id} accountId={a.id} />)}</>;
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

/** "NIFTY 24650 CE 09 NOV 26" -> contract "NIFTY 24650 CE" and expiry "9 Nov". */
function contract(symbol: string) {
  const m = /^(\S+ \S+ (?:CE|PE)) (\d{1,2}) ([A-Za-z]{3})/.exec(symbol);
  return m ? { name: m[1], expiry: `${Number(m[2])} ${m[3].charAt(0)}${m[3].slice(1).toLowerCase()}` } : { name: symbol, expiry: "" };
}

function held(from: string, to?: string | null) {
  const a = new Date(from).getTime(), b = to ? new Date(to).getTime() : Date.now() + istOffsetDrift();
  const min = Math.max(0, Math.round((b - a) / 60_000));
  return min < 60 ? `${min} m` : `${Math.floor(min / 60)} h ${min % 60} m`;
}

/** The ISO strings are IST wall times without a zone; "now" must be compared on the same clock. */
function istOffsetDrift() {
  return (330 + new Date().getTimezoneOffset()) * 60_000;
}

function LiveBookPanel({ accountId }: { accountId: number }) {
  const [tab, setTab] = useState<Tab>("open");
  const book = useQuery({ queryKey: ["live-book", accountId], queryFn: () => get<LiveBook>(`/api/execution/accounts/${accountId}/live-book?days=7`),
    refetchInterval: 5_000 });
  if (book.error) return <ErrorNote error={book.error} />;
  if (!book.data) return null;
  const b = book.data;
  const today = new Date(Date.now() + 330 * 60_000).toISOString().slice(0, 10);       // the IST date
  const closedToday = b.closed.filter((c) => c.closed.startsWith(today));
  const closed = tab === "week" ? b.closed : tab === "today" ? closedToday : [];
  const tabs: [Tab, string][] = [["open", `Open ${b.open.length}`], ["today", `Today ${b.open.length + closedToday.length}`],
    ["week", `This week ${b.open.length + b.closed.length}`]];

  return (
    <section className="min-w-0 rounded-lg border border-line bg-panel shadow-sm">
      <header className="flex flex-wrap items-center justify-between gap-3 border-b border-line px-4 py-3">
        <h2 className="flex flex-wrap items-center gap-2 text-sm font-semibold tracking-wide">
          Auto-trade positions · {b.account}
          <span className="inline-flex items-center gap-1.5 rounded-full border border-rose-300 bg-rose-50 px-2 py-0.5 text-[11px] font-bold text-rose-700">
            <span className="h-1.5 w-1.5 rounded-full bg-rose-600" />LIVE</span>
          <span className="font-normal text-muted">only trades auto-trade placed</span>
        </h2>
        <div className="inline-flex overflow-hidden rounded-md border border-line text-sm">
          {tabs.map(([k, label]) => (
            <button key={k} onClick={() => setTab(k)}
              className={`px-3 py-1.5 ${tab === k ? "bg-panel-2 font-medium" : "text-muted hover:text-text"}`}>{label}</button>
          ))}
        </div>
      </header>

      {b.killSwitches.length > 0 && (
        <div className="border-b border-line bg-rose-50 px-4 py-2 text-sm text-rose-800">
          <b>Entries stopped:</b> {b.killSwitches.join(" · ")} <span className="text-rose-700/80">(exits are never blocked)</span>
        </div>
      )}

      <div className="flex flex-wrap items-end gap-x-10 gap-y-3 border-b border-line px-4 py-3">
        <Total label="Open P&L" value={money(b.summary.openPnl)} cls={tone(b.summary.openPnl)} />
        <Total label="Realised today" value={money(b.summary.realisedToday)} cls={tone(b.summary.realisedToday)} />
        <Total label="At risk to stops" value={money(b.summary.atRisk, false)} />
        <Total label={`Since LIVE${b.summary.sinceLiveFrom ? ` (${dayLabel(b.summary.sinceLiveFrom)})` : ""}, net`}
          value={money(b.summary.sinceLiveNet)} cls={tone(b.summary.sinceLiveNet)} sub={`${b.summary.sinceLiveTrades} closed trades`} />
        <div className="ml-auto text-right text-xs text-muted">prices from the own capture · refreshed every 5 s<br />P&amp;L at the bid</div>
      </div>

      {(
        <div className="px-4 pt-3">
          <div className="mb-2 text-xs font-semibold uppercase tracking-wider text-muted">Open</div>
          {b.open.length === 0 ? <p className="pb-3 text-sm text-muted">Flat: no open position.</p>
            : b.open.map((p) => <OpenCard key={p.id} p={p} />)}
        </div>
      )}

      {tab !== "open" && (
        <div className="pt-2">
          <div className="mb-1 px-4 text-xs font-semibold uppercase tracking-wider text-muted">{tab === "today" ? "Closed today" : "Closed this week"}</div>
          {closed.length === 0 ? <p className="px-4 pb-3 text-sm text-muted">None.</p> : <ClosedTable rows={closed} />}
        </div>
      )}
    </section>
  );
}

function Total({ label, value, cls = "", sub }: { label: string; value: string; cls?: string; sub?: string }) {
  return (
    <div>
      <div className="text-xs text-muted">{label}</div>
      <div className={`num text-2xl font-bold ${cls}`}>{value}</div>
      {sub && <div className="text-xs text-muted">{sub}</div>}
    </div>
  );
}

function OpenCard({ p }: { p: LiveOpenPosition }) {
  const c = contract(p.symbol);
  const lots = p.lotSize ? Math.round(p.quantity / p.lotSize) : null;
  const edge = p.pnl == null ? "border-l-line" : p.pnl >= 0 ? "border-l-up" : "border-l-down";
  return (
    <div className={`mb-3 grid grid-cols-1 items-center gap-3 rounded-lg border border-line border-l-4 p-4 md:grid-cols-[1.3fr_0.9fr_2fr_0.8fr] ${edge}`}>
      <div className="min-w-0">
        <div className="text-lg font-bold">{c.name}</div>
        <div className="mt-1 flex flex-wrap items-center gap-1.5 text-xs text-muted">
          {c.expiry && <span>expires {c.expiry}</span>}
          <Tag strategy>{p.strategy}</Tag>
          <Tag>{lots != null ? `${lots} lot${lots === 1 ? "" : "s"} · ${p.quantity}` : `${p.quantity} qty`}</Tag>
        </div>
        <div className="mt-1 text-xs text-muted">bought {hhmm(p.opened)} · held {held(p.opened)}</div>
      </div>
      <div>
        <div className="text-xs text-muted">Entry → bid</div>
        <div className="num text-lg font-bold">{px(p.entry)} → {px(p.bid)}</div>
        <div className="text-xs text-muted">{money(p.entry * p.quantity, false)} paid{p.quoteAt ? ` · ${hhmm(p.quoteAt)}` : " · no recent quote"}</div>
      </div>
      <Rail p={p} />
      <div className="text-right">
        <div className={`num text-xl font-bold ${tone(p.pnl)}`}>{p.pnl != null ? money(p.pnl) : <span className="text-base font-normal text-muted">no price</span>}</div>
        <div className={`text-xs ${tone(p.pnl)}`}>{p.pnlPct != null ? `${p.pnlPct > 0 ? "+" : ""}${p.pnlPct.toFixed(1)} %` : ""}</div>
      </div>
    </div>
  );
}

/** Stop · entry · now · best on one line, to scale: coloured ticks on the rail, their values in a legend (never overlapping). */
function Rail({ p }: { p: LiveOpenPosition }) {
  const values = [p.stop, p.entry, p.bid, p.best].filter((v): v is number => v != null && Number.isFinite(v));
  const lo = Math.min(...values), hi = Math.max(...values);
  const pad = (hi - lo) * 0.06 || 1;
  const at = (v: number) => `${(100 * (v - (lo - pad))) / (hi - lo + 2 * pad)}%`;
  const gain = p.bid != null && p.bid >= p.entry;
  const nowCls = gain ? "text-up" : "text-down";
  return (
    <div className="min-w-0">
      <div className="text-xs text-muted">Premium: stop · entry · now · best so far</div>
      <div className="relative mt-2 h-5">
        <div className="absolute inset-x-0 top-2 h-1.5 rounded bg-panel-2" />
        {p.bid != null && (
          <div className={`absolute top-2 h-1.5 rounded ${gain ? "bg-up/60" : "bg-down/50"}`}
            style={{ left: at(Math.min(p.entry, p.bid)), width: `calc(${at(Math.max(p.entry, p.bid))} - ${at(Math.min(p.entry, p.bid))})` }} />
        )}
        {p.stop != null && <Tick at={at(p.stop)} cls="bg-down" />}
        <Tick at={at(p.entry)} cls="bg-muted" />
        {p.best != null && <Tick at={at(p.best)} cls="bg-muted/50" thin />}
        {p.bid != null && <Tick at={at(p.bid)} cls={gain ? "bg-up" : "bg-down"} />}
      </div>
      <div className="mt-1 flex flex-wrap gap-x-3 text-xs">
        {p.stop != null && <span className="text-down">stop {px(p.stop)}</span>}
        <span className="text-muted">entry {px(p.entry)}</span>
        {p.bid != null && <span className={nowCls}>now {px(p.bid)}</span>}
        {p.best != null && <span className="text-muted">best {px(p.best)}</span>}
      </div>
      <div className="mt-1 text-xs text-muted">
        {p.stop == null ? "no stop on record"
          : p.stopWorking ? <>stop resting at the broker{p.bid != null ? <> · {px(p.bid - p.stop)} pts below the bid · {money(p.atRisk ?? null, false)} at risk</> : null}</>
          : <span className="text-down">stop {p.stopStatus?.toLowerCase() ?? "not working"} at the broker</span>}
      </div>
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
  TRAIL: "trail", EOD_SQUARE_OFF: "square-off", FLATTEN: "flattened" };

function ClosedTable({ rows }: { rows: LiveClosedPosition[] }) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full whitespace-nowrap text-sm">
        <thead><tr className="border-b border-line text-xs text-muted">
          {["Day", "Contract", "Strategy", "Qty", "Entry → exit", "Exit", "In → out", "P&L %", "Charges", "Net"].map((h, i) =>
            <th key={h} className={`px-3 py-2 font-medium ${i < 3 || i === 5 || i === 6 ? "text-left" : "text-right"}`}>{h}</th>)}
        </tr></thead>
        <tbody>
          {rows.map((r) => {
            const c = contract(r.symbol);
            return (
              <tr key={r.id} className="border-b border-line">
                <td className="num px-3 py-2.5">{dayLabel(r.closed)}</td>
                <td className="px-3 py-2.5"><b>{c.name}</b> <span className="text-muted">· {c.expiry}</span></td>
                <td className="px-3 py-2.5"><Tag strategy>{r.strategy}</Tag></td>
                <td className="num px-3 py-2.5 text-right">{r.quantity}</td>
                <td className="num px-3 py-2.5 text-right">{px(r.entry)} → {px(r.exit)}</td>
                <td className="px-3 py-2.5"><Tag>{REASONS[r.exitReason ?? ""] ?? r.exitReason?.toLowerCase() ?? "–"}</Tag></td>
                <td className="num px-3 py-2.5 text-muted">{hhmm(r.opened)} → {hhmm(r.closed)} · {held(r.opened, r.closed)}</td>
                <td className={`num px-3 py-2.5 text-right ${tone(r.pct)}`}>{r.pct != null ? `${r.pct > 0 ? "+" : ""}${r.pct.toFixed(1)} %` : "–"}</td>
                <td className="num px-3 py-2.5 text-right text-muted">{r.charges.toFixed(2)}</td>
                <td className={`num px-3 py-2.5 text-right font-semibold ${tone(r.net)}`}>{money(r.net)}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
