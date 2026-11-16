import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { get, postJson } from "../api";
import { ConfirmButton, ErrorNote } from "./ui";
import type { ChartDrawing, DrawnBox, DrawnLine } from "./SurgePanel";

/**
 * Chart orders (user, 14 Nov 2026: "I will draw a line in the chart for entry and exit and will also draw stop loss";
 * plan docs/plans/2026-11-14-chart-line-trading-plan.md, P0). Pick Draw Buy CE / Buy PE, click the chart at the entry:
 * the entry, a stop and a target (2 R) appear; drag them, check the ticket, Arm. The live session's chart-order engine
 * then buys the best ATM when the index crosses the entry line and sells at the stop or target line. The lines of a
 * waiting order, and the stop and target of an open one, stay draggable on the chart. PAPER accounts only for now.
 */

interface PaperAccount { id: number; name: string; broker: string; mode: string; canBuy: boolean; canExit: boolean; blocked?: string | null }

interface ChartEvent { at: string; kind: string; actor: string; index: number | null; detail: string }

export interface ChartOrder {
  id: number;
  underlying: string;
  side: "CE" | "PE";
  kind: "BREAKOUT" | "PULLBACK" | "FAR_BREAKOUT";
  entry: number;
  stop: number;
  target: number;
  buffer: number;
  noChase: number;
  lots: number;
  validUntil: string;
  status: string;
  reason: string | null;
  version: number;
  symbol: string | null;
  events: ChartEvent[];
  position?: { open: number; entry: number; status: string; exit_reason: string | null; gross: number | null; charges: number | null };
}

/** engine (live only, review 16 Nov 2026): whether the chart-order engine watches today's orders; arm and move are refused if not */
interface ChartOrdersView { account: string; mode: string; canArm: boolean; canExit: boolean; unavailable?: string | null; orders: ChartOrder[];
  engine?: { running: boolean; lastSeen: string | null } }

interface Quote { symbol: string; lotSize: number; ask: number; bid: number; spot: number }

interface Draft { side: "CE" | "PE"; entry: number; stop: number; target: number; lots: number; validUntil: string }

const WORKING = ["ARMED", "TRIGGERED", "OPEN", "EXITING"];
const ENTRY = "#2563eb", STOP = "#dc2626", TARGET = "#16a34a";
/** The first stop's distance from a placed entry, in index points (dragged from there); the target is 2 R. */
const firstStop = (u: string) => (u === "SENSEX" ? 100 : 30);
const buffer = (u: string) => (u === "SENSEX" ? 6 : 2);
const noChase = (u: string) => (u === "SENSEX" ? 25 : 8);
const KIND: Record<ChartOrder["kind"], string> = { BREAKOUT: "breakout", PULLBACK: "pullback", FAR_BREAKOUT: "breakout through the stop" };
/** A refusal's reason without the HTTP status the API puts in front ("400: the stop is …"). */
const reason = (e: unknown) => (e instanceof Error ? e.message : String(e)).replace(/^\d{3}:?\s*/, "");

/**
 * What the ticket's lines would be armed as with the index at {@code spot}, or why they would be refused: the server's
 * rules (ChartLines.validate) mirrored, so a problem shows while the lines are drawn, not only after Arm. The server
 * still decides.
 */
function preview(draft: Draft, underlying: string, spot: number | undefined): { kind?: ChartOrder["kind"]; problem?: string } {
  const d = draft.side === "CE" ? 1 : -1, buf = buffer(underlying);
  const e = d * draft.entry, s = d * draft.stop, t = d * draft.target;
  if (![draft.entry, draft.stop, draft.target].every((v) => Number.isFinite(v) && v > 0)) return { problem: "entry, stop and target must be index prices" };
  if (!(s < e && e < t)) return { problem: draft.side === "CE" ? "a CE's stop must be below its entry and its target above it" : "a PE's stop must be above its entry and its target below it" };
  if (e - s < 2 * buf) return { problem: `the stop is ${nf(e - s)} pts from the entry: at least ${2 * buf}` };
  if (t - e <= buf) return { problem: `the target is ${nf(t - e)} pts from the entry: more than ${buf}` };
  if (spot == null || !(spot > 0)) return {};
  const x = d * spot;
  if (Math.abs(x - e) < 2 * buf) return { problem: `the entry is ${nf(Math.abs(x - e))} pts from the index (${nf(spot)}): it would fire at once` };
  return { kind: x > e ? "PULLBACK" : x <= s + buf ? "FAR_BREAKOUT" : "BREAKOUT" };
}
const half = (v: number) => Math.round(v * 2) / 2;
const nf = (v: number | null | undefined, d = 1) =>
  v == null || !Number.isFinite(v) ? "–" : v.toLocaleString("en-IN", { minimumFractionDigits: d, maximumFractionDigits: d });
const rupees = (v: number) => `₹${Math.round(v).toLocaleString("en-IN")}`;

/** A paced replay's one paper account (user, 15 Nov 2026: "in the replay i should be able to draw and run the replay"). */
const REPLAY_ACCOUNT: PaperAccount = { id: 0, name: "Replay paper account", broker: "PAPER", mode: "PAPER", canBuy: true, canExit: true };

/**
 * The chart-order desk of one index on its live day: the account, the draw tool, the unarmed draft, the account's
 * orders, and the {@link ChartDrawing} the chart draws (draft and working orders' lines) and reports clicks and drags to.
 * {@code replay}: a paced replay's chart, whose orders trade the replay's own paper account at the replayed prices
 * (trading-core's /api/replay, the same shapes as the live execution API).
 */
export function useChartDesk(underlying: string, enabled: boolean, replay = false) {
  const queryClient = useQueryClient();
  const accounts = useQuery({ queryKey: ["manual-accounts"], enabled: enabled && !replay,
    queryFn: () => get<PaperAccount[]>("/api/execution/manual/accounts"), refetchInterval: 15_000 });
  // code review 15 Nov 2026: an account the caller may only Exit on (SUPPORT) is listed too, so its open order shows
  const paper = replay ? [REPLAY_ACCOUNT] : (accounts.data ?? []).filter((a) => a.mode === "PAPER" && (a.canBuy || a.canExit));
  const [picked, setPicked] = useState<number | null>(null);
  const accountId = paper.some((a) => a.id === picked) ? picked! : paper[0]?.id ?? null;
  // every call of this desk goes under this path: an execution account's, or the running replay's
  const base = replay ? "/api/replay" : `/api/execution/accounts/${accountId}`;
  const [tool, setTool] = useState<"CE" | "PE" | null>(null);
  const [draft, setDraft] = useState<Draft | null>(null);
  // a dragged line's new price until the server's answer is read back (the line does not jump back meanwhile)
  const [moving, setMoving] = useState<{ key: string; price: number } | null>(null);
  const orders = useQuery({ queryKey: ["chart-orders", base], enabled: enabled && accountId != null,
    queryFn: () => get<ChartOrdersView>(`${base}/chart-orders`), refetchInterval: replay ? 1000 : 2000 });
  const refresh = () => queryClient.invalidateQueries({ queryKey: ["chart-orders", base] });
  const move = useMutation({
    mutationFn: (m: { order: ChartOrder; field: "entry" | "stop" | "target"; price: number }) =>
      postJson<ChartOrder>(`${base}/chart-orders/${m.order.id}/move`,
        { version: m.order.version, [m.field]: m.price }),
    onSettled: async () => { await refresh(); setMoving(null); },
  });
  const canArm = orders.data?.canArm ?? false, canExit = orders.data?.canExit ?? false;
  // review 16 Nov 2026: no engine, no move (each drag would be refused and snap back)
  const engineUp = !(orders.data?.engine != null && !orders.data.engine.running);
  const mine = (orders.data?.orders ?? []).filter((o) => o.underlying === underlying);
  const working = mine.filter((o) => WORKING.includes(o.status));

  const lines: DrawnLine[] = [];
  if (draft) {
    lines.push({ key: "draft:entry", price: draft.entry, color: ENTRY, label: `new ${draft.side} entry`, dashed: true, draggable: true });
    lines.push({ key: "draft:stop", price: draft.stop, color: STOP, label: "stop", dashed: true, draggable: true });
    lines.push({ key: "draft:target", price: draft.target, color: TARGET, label: "target", dashed: true, draggable: true });
  }
  for (const o of working) {
    const armed = o.status === "ARMED", open = o.status === "OPEN";
    const tag = `#${o.id} ${o.side}`;
    const at = (key: string, price: number) => (moving?.key === key ? moving.price : price);
    lines.push({ key: `o:${o.id}:entry`, price: at(`o:${o.id}:entry`, o.entry), color: ENTRY, label: `${tag} ${armed ? "entry" : "entered"}`, dashed: armed, draggable: canArm && engineUp && armed });
    lines.push({ key: `o:${o.id}:stop`, price: at(`o:${o.id}:stop`, o.stop), color: STOP, label: `${tag} stop`, dashed: !open, draggable: canArm && ((engineUp && armed) || open) });
    lines.push({ key: `o:${o.id}:target`, price: at(`o:${o.id}:target`, o.target), color: TARGET, label: `${tag} target`, dashed: !open, draggable: canArm && ((engineUp && armed) || open) });
  }
  // finished orders stay on the chart as trade boxes (15 Nov 2026: their lines vanished once closed), from the arming
  // to the last event; an order that never entered shows its lines up to its void, expiry or cancel
  const boxes: DrawnBox[] = mine.filter((o) => !WORKING.includes(o.status) && o.events.length > 0).map((o) => {
    const pnl = o.position?.gross != null ? o.position.gross - (o.position.charges ?? 0) : null;
    return { key: String(o.id), from: o.events[0].at, to: o.events[o.events.length - 1].at, entry: o.entry, stop: o.stop,
      target: o.target, colors: { entry: ENTRY, stop: STOP, target: TARGET },
      // a closed order's trade marker already reads "#n +₹…"; one that never entered is labelled with why
      label: o.status === "CLOSED" || pnl != null ? "" : `#${o.id} ${o.side} ${o.status.toLowerCase()}` };
  });
  const drawing: ChartDrawing = {
    lines,
    boxes,
    placing: tool,
    onPlace: (price) => {
      if (!tool) return;
      const d = tool === "CE" ? 1 : -1, risk = firstStop(underlying);
      setDraft({ side: tool, entry: price, stop: half(price - d * risk), target: half(price + d * 2 * risk), lots: 1, validUntil: "14:45" });
      setTool(null);
    },
    onDrag: (key, price) => {
      const [kind, id, field] = key.split(":");
      if (kind === "draft") {
        setDraft((x) => (x ? { ...x, [id]: price } : x));
        return;
      }
      const order = working.find((o) => String(o.id) === id);
      if (order && (field === "entry" || field === "stop" || field === "target")) {
        setMoving({ key, price });
        move.mutate({ order, field, price });
      }
    },
  };
  return { underlying, accounts, paper, accountId, setAccountId: setPicked, tool, setTool, draft, setDraft, orders, mine,
    move, refresh, drawing, canArm, canExit, base, replay, unavailable: orders.data?.unavailable ?? null,
    engineDown: orders.data?.engine != null && !orders.data.engine.running ? orders.data.engine : null };
}

export type ChartDesk = ReturnType<typeof useChartDesk>;

/**
 * The desk; {@code embedded}: inside the chart's panel, right above the chart (15 Nov 2026: the draw buttons, the ticket's
 * Arm and Exit now sat a screen above the chart, so drawing meant scrolling away from the lines), finished orders folded.
 */
export function ChartOrdersPanel({ desk, embedded = false }: { desk: ChartDesk; embedded?: boolean }) {
  const { underlying, paper, accountId, tool, draft, orders, move } = desk;
  if (desk.accounts.error) return <ErrorNote error={desk.accounts.error} />;
  // nothing to trade on (a member's chart-only view, or no PAPER account): no panel
  if (!desk.replay && (desk.accounts.isLoading || paper.length === 0)) return null;
  return (
    <section className={embedded ? "mb-2 min-w-0 rounded border border-accent/30 bg-bg/40" : "min-w-0 rounded-lg border border-line bg-panel"}>
      <header className={`flex flex-wrap items-center justify-between gap-2 border-b border-line ${embedded ? "px-3 py-1.5" : "px-4 py-2.5"}`}>
        <h2 className="text-sm font-semibold tracking-wide">Chart orders · {underlying}
          <span className="ml-2 text-xs font-normal text-muted">{desk.replay
            ? "draw entry, stop and target on the replay's chart · trades the replay's paper account at the replayed prices"
            : "draw entry, stop and target on the chart below · PAPER only"}</span></h2>
        <div className="flex flex-wrap items-center gap-2 text-sm">
          {!desk.replay && <label className="flex items-center gap-1.5 text-xs text-muted">Account
            <select value={accountId ?? ""} onChange={(e) => desk.setAccountId(Number(e.target.value))}
              className="rounded border border-line bg-bg px-2 py-1 text-sm text-text">
              {paper.map((a) => <option key={a.id} value={a.id}>{a.name} · {a.mode}</option>)}
            </select></label>}
          {desk.canArm && (["CE", "PE"] as const).map((s) => (
            <button key={s} type="button" onClick={() => desk.setTool(tool === s ? null : s)} aria-pressed={tool === s}
              className={`rounded border px-3 py-1 text-xs font-semibold ${tool === s ? "border-accent bg-accent text-white"
                : s === "CE" ? "border-up/60 text-up" : "border-down/60 text-down"}`}>
              Draw Buy {s}
            </button>
          ))}
        </div>
      </header>
      <div className={`space-y-2 text-sm ${embedded ? "px-3 py-2" : "p-4"}`}>
        {desk.unavailable && <p className="text-xs text-muted">Lines cannot be drawn now: {desk.unavailable}.</p>}
        {desk.engineDown && (
          <p className="text-xs font-semibold text-down">The chart-order engine is not running
            {desk.engineDown.lastSeen ? ` (last seen ${new Date(desk.engineDown.lastSeen).toLocaleTimeString("en-IN", { timeZone: "Asia/Kolkata", hour12: false })} IST)` : " today"}:
            nothing can be armed, armed lines are not watched, and an open position's chart stop and target are not
            watched either (only its broker safety stop protects it: use Exit now). It runs inside the trading session.</p>
        )}
        {desk.replay && desk.canArm && !tool && !draft && (
          <p className="text-xs text-muted">Pause the replay to draw at leisure; a line counts from the next replayed tick. The
            strike is the ATM of the replay's latest minute when the entry fires.</p>
        )}
        {tool && <p className="text-accent">Click the chart at your {tool} entry level (click Draw Buy {tool} again to stop).</p>}
        {draft && accountId != null && <Ticket key={desk.base} desk={desk} draft={draft} />}
        {move.error && <p className="text-sm text-down">Line not moved: {reason(move.error)}</p>}
        {orders.error && <ErrorNote error={orders.error} />}
        <OrderList key={desk.base} desk={desk} fold={embedded} />
      </div>
    </section>
  );
}

function Ticket({ desk, draft }: { desk: ChartDesk; draft: Draft }) {
  const { underlying, base } = desk;
  const quote = useQuery({ queryKey: ["chart-quote", base, underlying, draft.side], retry: false,
    queryFn: () => get<Quote>(`${base}/manual/quote?underlying=${underlying}&side=${draft.side}`),
    refetchInterval: desk.replay ? 3000 : 10_000 });
  // review 16 Nov 2026 (D7): the kind the ticket shows goes with the order; the server refuses it when the index has
  // moved through the entry since this quote (a breakout would arm as a pullback), and the quote is read again
  const arm = useMutation({
    mutationFn: (expectKind: string | undefined) => postJson<ChartOrder>(`${base}/chart-orders`, { underlying, ...draft, expectKind }),
    onSuccess: () => { desk.setDraft(null); desk.refresh(); },
    onError: () => { quote.refetch(); },
  });
  const set = (k: keyof Draft) => (e: { target: { value: string } }) =>
    desk.setDraft({ ...draft, [k]: k === "validUntil" ? e.target.value : Number(e.target.value) });
  const d = draft.side === "CE" ? 1 : -1;
  const toStop = d * (draft.entry - draft.stop), toTarget = d * (draft.target - draft.entry);
  const buf = buffer(underlying);
  const q = quote.data;
  // an estimate: an ATM option moves about half the index (delta 0.5); the stop fires at the buffer past the line and
  // the entry at the buffer past its own
  const riskRs = q ? 0.5 * (toStop + 2 * buf) * q.lotSize * draft.lots : null;
  const rewardRs = q ? 0.5 * (toTarget - buf) * q.lotSize * draft.lots : null;
  const field = "w-28 rounded border border-line bg-bg px-2 py-1 text-right num";
  const check = preview(draft, underlying, q?.spot);
  const side = draft.side === "CE" ? "rises" : "falls";
  return (
    <div className="rounded border border-accent/40 bg-accent/5 p-3">
      <div className="mb-2 flex flex-wrap items-center gap-3">
        <span className={`rounded px-2 py-0.5 text-xs font-bold text-white ${draft.side === "CE" ? "bg-up" : "bg-down"}`}>Buy {draft.side}</span>
        <label className="flex items-center gap-1 text-xs" style={{ color: ENTRY }}>entry<input className={field} type="number" step="0.5" value={draft.entry} onChange={set("entry")} /></label>
        <label className="flex items-center gap-1 text-xs" style={{ color: STOP }}>stop<input className={field} type="number" step="0.5" value={draft.stop} onChange={set("stop")} /></label>
        <label className="flex items-center gap-1 text-xs" style={{ color: TARGET }}>target<input className={field} type="number" step="0.5" value={draft.target} onChange={set("target")} /></label>
        <label className="flex items-center gap-1 text-xs text-muted">lots<input className="w-16 rounded border border-line bg-bg px-2 py-1 text-right num" type="number" min={1} max={10} value={draft.lots} onChange={set("lots")} /></label>
        <label className="flex items-center gap-1 text-xs text-muted">valid until<input className="rounded border border-line bg-bg px-2 py-1 num" type="time" value={draft.validUntil} onChange={set("validUntil")} /></label>
      </div>
      <p className="num text-xs text-muted">
        {nf(toStop)} pts to the stop · {nf(toTarget)} pts to the target · R:R {toStop > 0 ? nf(toTarget / toStop, 2) : "–"}
        {q && <> · ATM now {q.symbol} ask {nf(q.ask, 2)}, lot {q.lotSize} · ≈ risk <span className="text-down">{rupees(riskRs!)}</span>,
          reward <span className="text-up">{rupees(rewardRs!)}</span> (delta 0.5 estimate, before costs)</>}
        {quote.error && <> · {(quote.error as Error).message}</>}
      </p>
      {check.problem
        ? <p className="mt-1 text-xs font-semibold text-down">Cannot arm: {check.problem}.</p>
        : check.kind && (
          <p className="mt-1 text-xs">
            <span className="font-semibold">{KIND[check.kind]}</span>{" · "}
            {check.kind === "PULLBACK"
              ? <>buys when the index comes back to {nf(draft.entry)}; voided if the stop line comes first.</>
              : check.kind === "BREAKOUT"
                ? <>buys when the index {side} {buf} pts past {nf(draft.entry)}; voided if it reaches the stop line first.</>
                : <>the index is beyond your stop now: it {side} through the stop line, then buys {buf} pts past {nf(draft.entry)};
                  the stop counts only once bought.</>}
          </p>
        )}
      <p className="mt-1 text-xs text-muted">
        Not entered if the first tick is more than {noChase(underlying)} pts past the entry (a gap is not chased). The stop sells {buf} pts
        past its line; the target at its line. Strike: the best ATM when it fires.
      </p>
      <div className="mt-2 flex gap-2">
        <button type="button" disabled={arm.isPending || !!check.problem || !check.kind || !!desk.engineDown}
          title={!check.kind && !check.problem ? "no quote yet: the entry's kind is not known" : undefined} onClick={() => arm.mutate(check.kind)}
          className="rounded bg-accent px-4 py-1.5 text-sm font-semibold text-white disabled:opacity-50">Arm</button>
        <button type="button" onClick={() => desk.setDraft(null)} className="rounded border border-line px-3 py-1.5 text-sm">Discard</button>
      </div>
      {arm.error && <p className="mt-2 text-sm text-down">Not armed: {reason(arm.error)}</p>}
    </div>
  );
}

/** A result line of an exit that sold nothing or not all (the server's wording, FollowerSimulator / ManualOrders). */
const SOLD_NOTHING = /\bnot (exited|flattened|sold)|^not |no manual|queued|not known|\b\d+ of \d+ sold/i;

function statusTone(s: string) {
  return s === "OPEN" || s === "TRIGGERED" || s === "EXITING" ? "bg-warn/15 text-warn" : s === "ARMED" ? "bg-accent/15 text-accent"
    : s === "CLOSED" ? "bg-panel-2 text-text" : "bg-panel-2 text-muted";
}

function OrderList({ desk, fold = false }: { desk: ChartDesk; fold?: boolean }) {
  const { mine, base } = desk;
  // review 16 Nov 2026: the exit's own result is shown ("not exited: …", "queued behind …"); it was dropped, so an exit
  // that sold nothing looked done
  const [outcome, setOutcome] = useState<{ id: number; lines: string[] } | null>(null);
  const act = useMutation({
    mutationFn: (a: { id: number; what: "cancel" | "exit" }) =>
      postJson<{ result?: string[] }>(`${base}/chart-orders/${a.id}/${a.what}`, a.what === "exit" ? { confirm: "EXIT" } : {}),
    onMutate: () => setOutcome(null),
    onSuccess: (r, a) => setOutcome(r?.result?.length ? { id: a.id, lines: r.result } : null),
    onSettled: desk.refresh,
  });
  if (mine.length === 0) return <p className="text-xs text-muted">No chart orders on {desk.underlying} {desk.replay ? "in this replay" : "today"}.</p>;
  const working = mine.filter((o) => WORKING.includes(o.status)), finished = mine.filter((o) => !WORKING.includes(o.status));
  const net = finished.reduce((sum, o) => sum + (o.position?.gross != null ? o.position.gross - (o.position.charges ?? 0) : 0), 0);
  const rows = (list: ChartOrder[]) => list.map((o) => {
        const pnl = o.position?.gross != null ? o.position.gross - (o.position.charges ?? 0) : null;
        return (
          <div key={o.id} className="rounded border border-line px-3 py-2">
            <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
              <span className="num font-semibold">#{o.id}</span>
              <span className={o.side === "CE" ? "text-up" : "text-down"}>Buy {o.side}</span>
              <span className="text-xs text-muted">{KIND[o.kind] ?? o.kind.toLowerCase()}</span>
              <span className={`rounded px-1.5 py-0.5 text-xs font-bold ${statusTone(o.status)}`}>{o.status}</span>
              <span className="num text-xs">
                <span style={{ color: ENTRY }}>E {nf(o.entry)}</span> · <span style={{ color: STOP }}>S {nf(o.stop)}</span> ·{" "}
                <span style={{ color: TARGET }}>T {nf(o.target)}</span> · {o.lots} lot{o.lots > 1 ? "s" : ""} · until {o.validUntil.slice(0, 5)}
              </span>
              {o.symbol && <span className="num text-xs">{o.symbol}{o.position ? ` @ ${nf(o.position.entry, 2)}` : ""}</span>}
              {pnl != null && o.status === "CLOSED" && <span className={`num text-xs font-semibold ${pnl >= 0 ? "text-up" : "text-down"}`}>{pnl >= 0 ? "+" : "−"}{rupees(Math.abs(pnl))} net</span>}
              <span className="ml-auto flex gap-2">
                {o.status === "ARMED" && desk.canArm && (
                  <button type="button" className="rounded border border-line px-2 py-0.5 text-xs" onClick={() => act.mutate({ id: o.id, what: "cancel" })}>Cancel</button>
                )}
                {(o.status === "TRIGGERED" || o.status === "OPEN" || o.status === "EXITING") && desk.canExit && (
                  act.isPending ? <span className="text-xs text-muted">Exiting…</span>
                    : <ConfirmButton label="Exit now" confirmLabel="Sell it now?" onConfirm={() => act.mutate({ id: o.id, what: "exit" })} />
                )}
              </span>
            </div>
            {o.reason && <p className="mt-1 text-xs text-muted">{o.reason}</p>}
            <details className="mt-1 text-xs text-muted">
              <summary className="cursor-pointer">{o.events.length} events</summary>
              <ul className="num mt-1 space-y-0.5">
                {o.events.map((e, i) => (
                  <li key={i}>{e.at} {e.kind}{e.index != null ? ` · index ${nf(e.index)}` : ""} · {e.actor}
                    {e.detail && e.detail !== "{}" ? ` · ${e.detail}` : ""}</li>
                ))}
              </ul>
            </details>
          </div>
        );
      });
  return (
    <div className="space-y-2">
      {act.error && <p className="text-sm text-down">Not done: {reason(act.error)}</p>}
      {outcome && mine.some((o) => o.id === outcome.id && WORKING.includes(o.status)) && (
        <p className={`text-sm ${outcome.lines.some((l) => SOLD_NOTHING.test(l)) ? "text-down" : "text-muted"}`}>
          #{outcome.id}: {outcome.lines.join(" · ")}</p>
      )}
      {fold ? <>
        {rows(working)}
        {finished.length > 0 && (
          <details className="text-xs">
            <summary className="cursor-pointer text-muted">{finished.length} finished order{finished.length > 1 ? "s" : ""} ·{" "}
              <span className={`num font-semibold ${net >= 0 ? "text-up" : "text-down"}`}>{net >= 0 ? "+" : "−"}{rupees(Math.abs(net))} net</span></summary>
            <div className="mt-2 space-y-2 text-sm">{rows(finished)}</div>
          </details>
        )}
      </> : rows(mine)}
    </div>
  );
}
