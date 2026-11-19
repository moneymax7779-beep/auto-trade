import { type ReactNode, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { get, postJson, type ExecutionAccount, type ExitPreview, type LiveBook, type LiveOpenPosition, type PositionRow, type Status } from "../api";
import { ErrorNote } from "./ui";

/**
 * The Overview page's positions, one design for real money and paper (6 Nov 2026, user: "I just want the application
 * traded positions shown in a better UX", then "can we move to the top at the best place"): a totals row, one card per open
 * position (entry → bid, P&L in ₹ and %, a rail with stop · entry · now · best · target) and one line per closed trade
 * with its exit, why it closed, time held and net after charges.
 * - {@link LiveBooks}: every LIVE account the user can see, auto-trade's own positions only (never the broker's other
 *   holdings); nothing renders while no account is LIVE.
 * - {@link PaperPositions}: the running PAPER session's positions (PAPER-1).
 * - {@link LiveBookPanel} on the Today page (17 Nov 2026): a workspace's paper twins and LIVE accounts, with Exit on paper
 *   too, and a warning on a position its strategy no longer manages or that was bought late.
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
  /** the strategy's stage (CONFIRMED, RUNNER, ...) when the engine reports one */
  stage?: string;
  /** a straddle: its legs, shown instead of one entry -> bid and the rail */
  legs?: { side: string; entry: number; bid: number | null; pnl: number | null }[];
  /** under the card: what the trader can do with it (LIVE: Exit, book a hand exit) */
  actions?: ReactNode;
  /** warnings across the card (not managed by its strategy, a late join) */
  flags?: ReactNode;
  /** rupees per index point and its index */
  perPoint?: number | null;
  underlying?: string;
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
  best?: number;
  worst?: number;
  missed?: number;
  saved?: number;
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
  const m = /^(\S+ \S+ (?:CE\+PE|CE|PE)) (\d{1,2}) ([A-Za-z]{3})/.exec(symbol);
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

/** The query the book panels and the Today page's totals share (one request per account and poll). */
export const bookQuery = (accountId: number) => ({ queryKey: ["live-book", accountId],
  queryFn: async () => withMirrorOpen(await get<LiveBook>(`/api/execution/accounts/${accountId}/live-book?days=7`)),
  refetchInterval: 5_000 });

/**
 * V49 (review 19 Nov 2026): a mirror account's book from the server has only the mirrored account's closed trades (the
 * engine writes a position when it closes); its open positions are the running session's, which members read from
 * /api/status (the chart shows them from there too). They are added here, so the panel, the Today page's open P&L and
 * "has today" all see them. Read-only rows: no Exit on a mirror (canAct is false).
 */
export async function withMirrorOpen(b: LiveBook): Promise<LiveBook> {
  if (!b.mirrorOf) return b;
  let s: Status;
  try {
    s = await get<Status>("/api/status");
  } catch {
    return b;                                        // the closed trades still show; the open ones come back next poll
  }
  if (s.status !== "RUNNING" || s.date !== istToday() || (s.account != null && s.account !== b.mirrorOf)) return b;
  const open: LiveOpenPosition[] = (s.openPositions ?? []).filter((p) => p.quantity > 0).map((p, i) => {
    const pnl = p.unrealised ?? (p.bid != null ? (p.bid - p.averageCost) * p.quantity : undefined);
    return {
      id: -(i + 1), strategy: p.strategy ?? "", underlying: p.underlying, side: p.side, symbol: p.symbol,
      lotSize: p.lotSize ?? null, entry: p.averageCost, quantity: p.quantity,
      opened: p.opened == null ? `${s.date}T00:00:00` : p.opened.length > 8 ? p.opened : `${s.date}T${p.opened}`,
      status: "OPEN", bid: p.bid ?? null, best: null, stop: null, stopStatus: null, stopWorking: false,
      pnl, pnlPct: p.bid != null && p.averageCost > 0 ? Math.round(10_000 * (p.bid / p.averageCost - 1)) / 100 : null,
    };
  });
  const openPnl = open.reduce((sum, p) => sum + (p.pnl ?? 0), 0);
  return { ...b, open, summary: { ...b.summary, openPnl } };
}

/** Whether a book has anything for today: an open position or a trade closed today. */
export const bookHasToday = (b: LiveBook) => b.open.length > 0 || b.closed.some((c) => c.closed.startsWith(istToday()));

/** One account's positions; {@code hideEmpty}: nothing when it has no open position and no trade today. */
export function LiveBookPanel({ accountId, hideEmpty }: { accountId: number; hideEmpty?: boolean }) {
  const book = useQuery(bookQuery(accountId));
  if (book.error) return <ErrorNote error={book.error} />;
  if (!book.data) return null;
  const b = book.data;
  if (hideEmpty && !bookHasToday(b)) return null;
  const paper = b.bookMode === "PAPER";
  const open: OpenView[] = b.open.map((p) => ({
    key: p.id, strategy: p.strategy, symbol: p.symbol, quantity: p.quantity, lotSize: p.lotSize, entry: p.entry,
    opened: p.opened, bid: p.bid, quoteAt: p.quoteAt ?? null, best: p.best, stop: p.stop, pnl: p.pnl ?? null,
    pnlPct: p.pnlPct ?? null, perPoint: p.perPoint ?? null, underlying: p.underlying,
    stopNote: b.mirrorOf ? `${b.mirrorOf}'s position: it exits on its strategy's signal or the square-off`
      : paper ? "paper: exits on the strategy's signal, your Exit or the square-off"
      : p.stop == null ? "no stop on record"
      : p.stopWorking ? <>stop resting at the broker{p.bid != null ? <> · {px(p.bid - p.stop)} pts below the bid · {money(p.atRisk ?? null, false)} at risk</> : null}</>
      : <span className="text-down">stop {p.stopStatus?.toLowerCase() ?? "not working"} at the broker</span>,
    actions: b.canAct || b.canStop ? <PositionActions accountId={accountId} p={p} paper={paper} /> : undefined,
    flags: p.strategyExitedAt || p.lateJoin ? (
      <div className="space-y-0.5 rounded-md border border-amber-300 bg-amber-50 px-3 py-2 text-sm text-amber-900 md:col-span-4">
        {p.strategyExitedAt && <div><b>Not managed by its strategy:</b> {p.strategy} exited this contract at {p.strategyExitedAt.slice(11, 19)}.
          Nothing will close it except your Exit{b.squareOff ? ` or the ${b.squareOff} square-off` : " or the square-off"}.</div>}
        {p.lateJoin && <div><b>Late join:</b> signal at {p.lateJoin.signalAt.slice(11, 19)}, bought at {p.lateJoin.joinedAt.slice(11, 19)} at
          the signal's price.</div>}
      </div>) : undefined,
  }));
  const closed: ClosedView[] = b.closed.map((c) => ({ key: c.id, strategy: c.strategy, symbol: c.symbol, quantity: c.quantity,
    lotSize: c.lotSize, entry: c.entry, exit: c.exit, exitReason: c.exitReason, opened: c.opened, closed: c.closed,
    charges: c.charges, net: c.net, pct: c.pct, best: c.bestBid, worst: c.worstBid, missed: c.missed, saved: c.saved }));
  const since = `Since ${paper ? "start" : "LIVE"}${b.summary.sinceLiveFrom ? ` (${dayLabel(b.summary.sinceLiveFrom)})` : ""}, net`;
  const totals: TotalView[] = [
    { label: "Open P&L", value: money(b.summary.openPnl), cls: tone(b.summary.openPnl) },
    { label: "Realised today", value: money(b.summary.realisedToday), cls: tone(b.summary.realisedToday) },
    ...(paper ? [] : [{ label: "At risk to stops", value: money(b.summary.atRisk, false) }]),
    { label: since, value: money(b.summary.sinceLiveNet), cls: tone(b.summary.sinceLiveNet), sub: `${b.summary.sinceLiveTrades} closed trades` },
    ...(b.summary.perPoint && Object.keys(b.summary.perPoint).length > 0 ? [{
      label: "Exposure per index point",
      value: Object.entries(b.summary.perPoint).map(([u, v]) => `${money(v)} ${u}`).join(" · "),
      sub: "delta × quantity: what 1 point up does to the open P&L" }] : []),
  ];
  return (
    <PositionsPanel title={<>{paper ? "Paper positions" : "Auto-trade positions"} · {b.account}</>} badge={paper ? "PAPER" : "LIVE"}
      note={b.mirrorOf ? `paper money: shows ${b.mirrorOf}'s trades, the same as ${b.mirrorOf}` : paper ? "paper money: no broker order" : "only trades auto-trade placed"}
      banner={b.killSwitches.length > 0 ? <KillBanner accountId={accountId} kills={b.killSwitches} /> : null}
      totals={totals} open={open} closed={closed} weekly
      footnote={`prices from the own capture · refreshed every 5 s · P&L at the bid${b.squareOff ? ` · square-off ${b.squareOff}` : ""}`} />
  );
}

/** Why entries are stopped, one line per switch, each with Release for the switches the user may release. */
function KillBanner({ accountId, kills }: { accountId: number; kills: LiveBook["killSwitches"] }) {
  const client = useQueryClient();
  const release = useMutation({
    mutationFn: (id: number) => postJson(`/api/execution/kill-switches/${id}/release`, {}),
    onSettled: () => client.invalidateQueries({ queryKey: ["live-book", accountId] }),
  });
  return (
    <div className="space-y-1">
      <div><b>Entries stopped</b> <span className="opacity-80">(exits are never blocked)</span></div>
      {kills.map((k) => (
        <div key={k.id} className="flex flex-wrap items-center gap-2">
          <span className="min-w-0 break-words">{k.text}</span>
          {k.releasable && (
            <button onClick={() => { if (window.confirm(`Release this kill switch? ${k.text}\n\nThe account takes new entries again.${k.text.includes('RECONCILIATION') ? ' If the broker holds more than ours (your own trade), this exact difference will not stop it again today; a changed quantity, another contract, or the broker holding less than ours will.' : ''}`)) release.mutate(k.id); }}
              disabled={release.isPending}
              className="rounded border border-rose-300 bg-white px-2 py-0.5 text-xs font-medium text-rose-700 hover:bg-rose-100 disabled:opacity-50">
              Release</button>
          )}
        </div>
      ))}
      {release.error && <div className="text-xs">Not released: {(release.error as Error).message}</div>}
    </div>
  );
}

/** IST wall time now as "YYYY-MM-DDTHH:MM:SS" (what the hand-exit form edits and the API takes). */
const istNow = () => new Date(Date.now() + 330 * 60_000).toISOString().slice(0, 19);

/**
 * A LIVE position's own controls (7 Nov 2026, user: "give the flexibility for the trader to close his open position when
 * he wants ... allow the user to edit the price for reconcile and close the position"):
 * - Exit: sells this position now at the broker (its stop comes off first); the account keeps trading.
 * - Book hand exit: it was sold outside the app (in Kite): our record closes as MANUAL at the price and time the trader
 *   enters; nothing is sent to the broker. Opened by itself when the reconciler found the broker holds none of it.
 */
function PositionActions({ accountId, p, paper }: { accountId: number; p: LiveOpenPosition; paper?: boolean }) {
  const client = useQueryClient();
  const hint = p.notAtBroker;
  const [mode, setMode] = useState<"idle" | "exit" | "hand">(hint ? "hand" : "idle");
  const [price, setPrice] = useState<string>(() => (hint?.bidFirst ?? p.bid ?? p.entry).toFixed(2));
  const [at, setAt] = useState<string>(() => (hint?.from ?? istNow()).slice(0, 19));
  const [unchecked, setUnchecked] = useState(false);
  const [done, setDone] = useState<string | null>(null);
  const refresh = () => client.invalidateQueries({ queryKey: ["live-book", accountId] });
  const base = `/api/execution/accounts/${accountId}/positions/${p.id}`;
  // the exit sheet (17 Nov 2026, phase 2): how many lots, a preview of the sale, typed EXIT when it is big or risky,
  // and one key per opened sheet so a double click or a retry never sells twice
  const allLots = p.lotSize ? Math.max(1, Math.round(p.quantity / p.lotSize)) : 1;
  const [lots, setLots] = useState(allLots);
  const [typed, setTyped] = useState("");
  const [key, setKey] = useState("");
  // review 17 Nov 2026: All sends no quantity (everything that is open), never a computed lot count
  const all = !p.lotSize || lots >= allLots;
  const qty: number | undefined = all ? undefined : lots * p.lotSize!;
  const preview = useQuery({ queryKey: ["exit-preview", accountId, p.id, qty ?? "all"], enabled: mode === "exit",
    queryFn: () => get<ExitPreview>(`${base}/exit-preview${qty != null ? `?quantity=${qty}` : ""}`), refetchInterval: 3_000 });
  const newKey = () => setKey(typeof crypto !== "undefined" && "randomUUID" in crypto ? crypto.randomUUID()
    : `${Date.now()}-${Math.random()}`);
  const exit = useMutation({
    mutationFn: () => postJson<{ result: string[]; repeat?: boolean }>(`${base}/exit`, { confirm: "EXIT", quantity: qty ?? null, key }),
    onSuccess: (r) => { setDone((r.repeat ? "already sent: " : "") + r.result.join(" · ")); setMode("idle"); },
    // a refusal (4xx) sold nothing: the next click is a new attempt; anything else may have sold, so it keeps its key
    onError: (e) => { if (/^4\d\d:/.test((e as Error).message)) newKey(); },
    onSettled: refresh,
  });
  const openExit = () => {
    exit.reset(); setLots(allLots); setTyped(""); newKey();
    setMode("exit");
  };
  const pv = preview.data;
  // review 17 Nov 2026: a failed preview never blocks the exit; it asks for the typed EXIT instead
  const mustType = preview.error != null || (pv != null && (pv.typeToConfirm || (paper === true && pv.warnings.length > 0)));
  const hand = useMutation({
    mutationFn: () => postJson<{ result: string }>(`${base}/hand-exit`, { price: Number(price), at, unchecked }),
    onSuccess: (r) => { setDone(r.result); setMode("idle"); },
    onSettled: refresh,
  });
  const value = Number(price);
  const valid = Number.isFinite(value) && value > 0 && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?$/.test(at);
  const gross = valid ? (value - p.entry) * p.quantity : null;
  const needsUnchecked = /unchecked/.test((hand.error as Error | null)?.message ?? "");
  const btn = "rounded-md border px-3 py-1.5 text-sm font-medium disabled:opacity-50";
  return (
    <div className="md:col-span-4">
      {hint && (
        <div className="mb-2 rounded-md border border-amber-300 bg-amber-50 px-3 py-2 text-sm text-amber-900">
          <b>The broker holds none of this:</b> it was sold outside the app between {hint.from.slice(11, 19)} and {hint.to.slice(11, 19)}
          {hint.bidLow != null && hint.bidHigh != null ? <> (captured bid {px(hint.bidLow)}–{px(hint.bidHigh)})</> : null}.
          {" "}Book the sale at your fill price from Kite → Orders → Trades; then release the kill switch above.
        </div>
      )}
      {mode === "idle" && (
        <div className="flex flex-wrap items-center gap-2">
          {!hint && <button className={`${btn} border-rose-400 text-rose-700 hover:bg-rose-50`} onClick={openExit}>Exit</button>}
          {p.bookedSoFar != null && <span className="text-xs text-muted">booked so far by partial exits <span className={tone(p.bookedSoFar)}>{money(p.bookedSoFar)}</span></span>}
          {!paper && <button className={`${btn} border-line text-muted hover:text-text`} onClick={() => { hand.reset(); setMode("hand"); }}>
            Sold it in Kite? Book the exit</button>}
          {done && <span className="text-sm text-muted">{done}</span>}
        </div>
      )}
      {mode === "exit" && (
        <div className="space-y-2 rounded-md border border-rose-300 bg-rose-50 px-3 py-2 text-sm">
          <div className="flex flex-wrap items-center gap-2">
            <span>Exit <b>{contract(p.symbol).name}</b>{paper ? " (paper)" : ""}:</span>
            <button className={`${btn} ${lots >= allLots ? "border-rose-500 bg-white font-semibold" : "border-line bg-white"}`}
              onClick={() => setLots(allLots)}>All {allLots} lot{allLots === 1 ? "" : "s"}</button>
            {allLots >= 2 && <button className={`${btn} ${lots === Math.floor(allLots / 2) ? "border-rose-500 bg-white font-semibold" : "border-line bg-white"}`}
              onClick={() => setLots(Math.floor(allLots / 2))}>Half ({Math.floor(allLots / 2)})</button>}
            {allLots >= 2 && <label className="flex items-center gap-1 text-xs text-muted">lots
              <input type="number" min={1} max={allLots} step={1} value={lots}
                onChange={(e) => setLots(Math.max(1, Math.min(allLots, Math.floor(Number(e.target.value) || 1))))}
                className="num w-16 rounded border border-line bg-white px-2 py-1 text-sm text-text" /></label>}
          </div>
          {preview.error ? <div className="text-rose-800">No preview ({(preview.error as Error).message}): you can still exit; type EXIT to confirm.</div> : !pv ? <div className="text-muted">Pricing…</div> : (
            <table className="w-full max-w-xl text-sm">
              <tbody>
                <tr><td className="py-0.5 pr-3 text-muted">Sell</td><td className="num">{lotsText(pv.quantity, pv.lotSize)}{pv.remaining > 0 ? ` · ${pv.remaining.toLocaleString("en-IN")} stay open` : " · closes the position"}</td></tr>
                <tr><td className="py-0.5 pr-3 text-muted">Order</td><td>{pv.how}{pv.orders > 1 ? ` · ${pv.orders} orders (freeze ${pv.freezeQuantity?.toLocaleString("en-IN")})` : ""}</td></tr>
                <tr><td className="py-0.5 pr-3 text-muted">Est. price</td><td className="num">{px(pv.price)} {pv.priceFrom === "bid" ? <>bid{pv.bidQuantity != null ? <> × {pv.bidQuantity.toLocaleString("en-IN")}</> : null}</> : <span className="text-amber-800">({pv.priceFrom}'s price: no fresh bid)</span>}
                  {pv.spreadPct != null ? ` · spread ${pv.spreadPct.toFixed(1)} %` : ""}{pv.quoteAgeSec != null ? ` · ${pv.quoteAgeSec} s old` : ""}</td></tr>
                <tr><td className="py-0.5 pr-3 text-muted">Gross</td><td className={`num ${tone(pv.gross)}`}>{money(pv.gross)}</td></tr>
                {pv.charges != null && <tr><td className="py-0.5 pr-3 text-muted">Charges</td><td className="num">{money(pv.charges, false)}</td></tr>}
                {pv.net != null && <tr><td className="py-0.5 pr-3 text-muted">Net if filled</td><td className={`num font-semibold ${tone(pv.net)}`}>{money(pv.net)}</td></tr>}
                <tr><td className="py-0.5 pr-3 text-muted">After</td><td>{pv.rest ?? (pv.remaining > 0 ? `${pv.remaining} stay open` : "the position closes")}; the strategy keeps trading and won't re-buy this signal</td></tr>
              </tbody>
            </table>
          )}
          {pv && pv.warnings.length > 0 && (
            <ul className="list-disc pl-5 text-amber-900">{pv.warnings.map((w) => <li key={w}>{w}</li>)}</ul>
          )}
          <div className="flex flex-wrap items-center gap-2">
            {mustType && <input placeholder="type EXIT" value={typed} onChange={(e) => setTyped(e.target.value)}
              className="w-28 rounded border border-rose-300 bg-white px-2 py-1 text-sm" />}
            <button className={`${btn} border-rose-500 bg-rose-600 text-white hover:bg-rose-700`}
              disabled={exit.isPending || (!pv && !preview.error) || (mustType && typed !== "EXIT")}
              onClick={() => exit.mutate()}>{exit.isPending ? "Selling…" : `Exit ${Math.min(lots, allLots)} lot${Math.min(lots, allLots) === 1 ? "" : "s"} now`}</button>
            <button className={`${btn} border-line bg-white`} disabled={exit.isPending} onClick={() => setMode("idle")}>Cancel</button>
            {mustType && <span className="text-xs text-muted">{preview.error ? "No preview: type EXIT to confirm."
              : pv?.typeToConfirm ? "Big or risky sale: type EXIT to confirm." : "The price is not a fresh bid: type EXIT to confirm."}</span>}
          </div>
          {exit.error && <div className="text-rose-800">Not sold: {(exit.error as Error).message}</div>}
        </div>
      )}
      {mode === "hand" && (
        <form className="flex flex-wrap items-end gap-3 rounded-md border border-line bg-panel-2 px-3 py-2 text-sm"
          onSubmit={(e) => { e.preventDefault(); if (valid) hand.mutate(); }}>
          <label className="flex flex-col text-xs text-muted">Sold at (₹ per unit)
            <input type="number" step="0.05" min="0.05" value={price} onChange={(e) => setPrice(e.target.value)}
              className="num mt-0.5 w-28 rounded border border-line bg-white px-2 py-1 text-sm text-text" required /></label>
          <label className="flex flex-col text-xs text-muted">Time (IST)
            <input type="datetime-local" step="1" value={at} onChange={(e) => setAt(e.target.value)}
              className="num mt-0.5 rounded border border-line bg-white px-2 py-1 text-sm text-text" required /></label>
          <div className="text-xs text-muted">
            <div>{lotsText(p.quantity, p.lotSize)} · entry {px(p.entry)}</div>
            <div className={`num text-sm font-semibold ${tone(gross)}`}>{gross != null ? <>{money(gross)} gross</> : "–"}</div>
          </div>
          {needsUnchecked && (
            <label className="flex items-center gap-1.5 text-xs text-amber-900">
              <input type="checkbox" checked={unchecked} onChange={(e) => setUnchecked(e.target.checked)} />
              Kite can't be read now: book it without checking Kite</label>
          )}
          <button type="submit" className={`${btn} border-accent bg-accent text-white hover:opacity-90`} disabled={!valid || hand.isPending}>
            {hand.isPending ? "Booking…" : "Book sold by hand"}</button>
          {!hint && <button type="button" className={`${btn} border-line bg-white`} onClick={() => setMode("idle")}>Cancel</button>}
          <span className="w-full text-xs text-muted">Only for a sale made outside the app: nothing is sent to the broker, our record closes as "sold by hand".</span>
          {hand.error && <span className="w-full text-rose-800">Not booked: {(hand.error as Error).message}</span>}
        </form>
      )}
    </div>
  );
}

// ------------------------------------------------------------------------------------------------- PAPER session

/** The running PAPER session's positions (from /api/status), in the same design. */
export function PaperPositions({ account, date, openRows, closedRows }: {
  account: string; date: string; openRows: PositionRow[]; closedRows: PositionRow[];
}) {
  const iso = (t: string | null) => (t ? (t.length > 8 ? t : `${date}T${t}`) : `${date}T00:00:00`);
  let atRisk = 0;
  const single: OpenView[] = openRows.map((r, i) => {
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
      stage: r.stages?.length ? r.stages[r.stages.length - 1] : undefined,
      stopNote: stop == null ? "no stop"
        : <>engine stop −{r.stopPct} %{bid != null ? <> · {px(bid - stop)} pts below the bid · {money(risk, false)} at risk</> : null}{target != null ? <> · target +{r.targetPct} %</> : null}</>,
    };
  });
  // a straddle's legs (same strategy, index and entry time) are one trade: one card, both legs, the combined P&L
  const open: OpenView[] = [];
  single.forEach((v, i) => {
    const r = openRows[i];
    if (!v.leg) { open.push(v); return; }
    const group = `${r.strategy}|${r.underlying}|${r.opened}`;
    const leg = { side: v.leg, entry: v.entry, bid: v.bid, pnl: v.pnl };
    const trade = open.find((o) => o.key === group);
    if (!trade) {
      open.push({ ...v, key: group, symbol: v.symbol.replace(/ (CE|PE) /, " CE+PE "), legs: [leg], stop: null, target: null,
        best: null, bid: null, stopNote: "each leg has its own stop" });
      return;
    }
    trade.legs!.push(leg);
    trade.pnl = trade.pnl != null && v.pnl != null ? trade.pnl + v.pnl : null;
    trade.entry += v.entry;                                  // the straddle's premium per unit: both legs
    trade.pnlPct = trade.pnl != null && trade.entry > 0 ? (100 * trade.pnl) / (trade.entry * trade.quantity) : null;
  });
  const closed: ClosedView[] = closedRows.map((r, i) => {
    const qty = r.boughtQuantity ?? r.quantity;
    return {
      key: `${r.strategy}|${r.symbol}|${r.opened}|${i}`, strategy: r.leg ? `${r.strategy ?? ""} · ${r.leg} leg` : r.strategy ?? "", symbol: r.symbol, quantity: qty,
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
        <div className="text-lg font-bold">{c.name}{p.legs ? <span className="ml-2 text-xs font-normal text-muted">straddle</span> : p.leg ? <span className="ml-2 text-xs font-normal text-muted">straddle leg</span> : null}</div>
        <div className="mt-1 flex flex-wrap items-center gap-1.5 text-xs text-muted">
          {c.expiry && <span>expires {c.expiry}</span>}
          <Tag strategy>{p.strategy}</Tag>
          {p.stage && <Tag>{p.stage.toLowerCase()}</Tag>}
          <Tag>{lotsText(p.quantity, p.lotSize)}</Tag>
        </div>
        <div className="mt-1 text-xs text-muted">bought {hhmm(p.opened)} · held {held(p.opened)}</div>
      </div>
      {p.legs ? (
        <div className="md:col-span-2">
          <div className="text-xs text-muted">Straddle legs: entry → bid · P&amp;L</div>
          {p.legs.map((l) => (
            <div key={l.side} className="num text-sm"><b>{l.side}</b> {px(l.entry)} → {px(l.bid)} · <span className={tone(l.pnl)}>{money(l.pnl)}</span></div>
          ))}
          <div className="text-xs text-muted">{money(p.entry * p.quantity, false)} paid · {p.stopNote}</div>
        </div>
      ) : (
        <>
          <div>
            <div className="text-xs text-muted">Entry → bid</div>
            <div className="num text-lg font-bold">{px(p.entry)} → {px(p.bid)}</div>
            <div className="text-xs text-muted">{money(p.entry * p.quantity, false)} paid{p.quoteAt ? ` · ${hhmm(p.quoteAt)}` : p.bid == null ? " · no recent quote" : ""}</div>
            {p.perPoint != null && <div className="text-xs text-muted">≈ {money(p.perPoint)} per {p.underlying ?? "index"} point</div>}
          </div>
          <Rail p={p} />
        </>
      )}
      <div className="text-right">
        <div className={`num text-xl font-bold ${tone(p.pnl)}`}>{p.pnl != null ? money(p.pnl) : <span className="text-base font-normal text-muted">no price</span>}</div>
        <div className={`text-xs ${tone(p.pnl)}`}>{p.pnlPct != null ? `${p.pnlPct > 0 ? "+" : ""}${p.pnlPct.toFixed(1)} %` : ""}</div>
      </div>
      {p.flags}
      {p.actions}
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
  TRAIL: "trail", EOD_SQUARE_OFF: "square-off", FLATTEN: "flattened", TIME_60: "60-min time stop", TRADER_EXIT: "exited by you",
  EARLY_FAIL_15: "15-min early exit" };

function ClosedTable({ rows, showDay }: { rows: ClosedView[]; showDay: boolean }) {
  const ranged = rows.some((r) => r.best != null);
  const heads = ["Day", "Contract", "Strategy", "Lots · qty", "Entry → exit", "Exit", "In → out", "Best · worst held", "P&L %", "Charges", "Net"]
    .filter((h) => ranged || h !== "Best · worst held");
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
                {ranged && <td className="num px-3 py-2.5 text-right">{r.best != null ? <>
                  {px(r.best)} · {px(r.worst)}
                  {r.missed != null && r.missed > 0 ? <div className="text-xs text-muted">left {money(r.missed, false)} vs best</div> : null}
                </> : <span className="text-muted">–</span>}</td>}
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
