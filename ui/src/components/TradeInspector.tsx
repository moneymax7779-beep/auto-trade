import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { get, post, type Status } from "../api";
import { useMoment } from "./chartView";
import { strategyShort } from "./strategies";
import { Panel } from "./ui";

/**
 * Looking inside a session for tuning (user, 5 Nov 2026: "stop at some point for analyzing what data it holds at that
 * point ... add entry point and exit point in the chart to see what data it holds"): a paced replay's controls, each
 * trade's data at its entry and exit (and every trade as CSV), and the session as of any minute (the chart's pin, or a
 * paused replay's clock). Reads only; the controls act on a replay, never on a live session.
 */

type Features = Record<string, unknown>;

/** /api/sessions/{id}/trade-context: closed trades, and open ones (no exit yet). */
export interface TradeContext {
  strategy_id: string | null; underlying: string; option_side: string; symbol: string | null;
  opened_at: string; closed_at: string | null; exit_reason: string | null; quantity: number | null;
  average_cost: number | null; average_exit: number | null; net: number | null;
  entry_reason: string | null; entry_context: Features | null; entry_features: Features | null; exit_features: Features | null;
}

interface ReplayState {
  paused: boolean; pausedBecause: string | null; speed: number; marketTime: string | null;
  pauseAt: string | null; pauseOn: string; pauseOnSymbol: string | null;
}

interface AtData {
  date: string; time: string; underlying: string;
  decisions: { strategy_id: string; t: string; spot: number; ce_stage: string; pe_stage: string; state: Record<string, unknown> }[];
  snapshot: { t: string; phase: string; spot: number; features: Features } | null;
  open: { strategy_id: string; option_side: string; symbol: string; opened_at: string; quantity: number; average_cost: number; mark: number | null; direction?: string | null }[];
  closedNet: number;
}

const nf = (n: number, d = 0) => n.toLocaleString("en-IN", { minimumFractionDigits: d, maximumFractionDigits: d });
const hhmm = (m: number) => `${String(Math.floor(m / 60)).padStart(2, "0")}:${String(Math.floor(m % 60)).padStart(2, "0")}`;
const ist = (instant: string) => new Date(instant).toLocaleTimeString("en-GB", { timeZone: "Asia/Kolkata", hour12: false });
/** A feature's value: integers grouped, small decimals to 3 significant figures (a gamma of 0.0009 is not "0.000"). */
const show = (v: unknown) => v == null ? "–" : typeof v === "number" ? (Number.isInteger(v) ? nf(v)
  : Math.abs(v) < 0.01 ? v.toPrecision(3) : nf(v, Math.abs(v) < 10 ? 3 : 2))
  : typeof v === "object" ? JSON.stringify(v) : String(v);

/** Nested objects (the entry conditions) as "conditions.oi_burst" keys. */
function flatten(f: Features | null | undefined, prefix = ""): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(f ?? {})) {
    if (v && typeof v === "object" && !Array.isArray(v)) Object.assign(out, flatten(v as Features, `${prefix}${k}.`));
    else out[prefix + k] = v;
  }
  return out;
}

/**
 * The session's trades with their entry context and snapshots: read only once a trade is picked on the chart (a heavy
 * query), and while live every 30 s so an open trade's exit arrives.
 */
export function useTradeContext(sessionId: number | undefined, live: boolean, wanted: boolean) {
  return useQuery({
    queryKey: ["trade-context", sessionId],
    queryFn: () => get<TradeContext[]>(`/api/sessions/${sessionId}/trade-context`),
    enabled: sessionId != null && wanted,
    refetchInterval: live && wanted ? 30_000 : false,
    retry: false,
  });
}

/** The replay's controls and clock (shared by the controls and the minute inspector: one poll); only while a replay runs. */
export function useReplayState(replaying: boolean) {
  return useQuery({ queryKey: ["replay-control"], queryFn: () => get<ReplayState>("/api/replay-control"),
    enabled: replaying, retry: false,
    // an unpaced replay answers 409 (nothing to control): stop asking; after any other error (a deploy, a blip) ask again
    // more slowly
    refetchInterval: (q) => (!replaying ? false : !q.state.error ? 1_000 : String(q.state.error).includes("409") ? false : 5_000) });
}

/** Pause, resume, speed, run to the next entry / exit / a position's exit / a time: shown only while a replay runs. */
export function ReplayControls({ s }: { s: Status }) {
  const client = useQueryClient();
  const state = useReplayState(s.mode === "PAPER_REPLAY");
  const act = useMutation({
    mutationFn: (q: string) => post<ReplayState>(`/api/replay-control?${q}`),
    onSuccess: (data) => client.setQueryData(["replay-control"], data),
  });
  const [until, setUntil] = useState("");
  if (s.mode !== "PAPER_REPLAY" || !state.data) return null;
  const r = state.data;
  const btn = "rounded border border-line px-2 py-1 hover:bg-panel-2 disabled:opacity-40";
  const open = (s.openPositions ?? []).filter((p) => p.quantity > 0);
  return (
    <div className="flex flex-wrap items-center gap-2 rounded border border-accent/40 bg-accent/5 px-3 py-2 text-xs">
      <span className="font-semibold">Replay {s.date}</span>
      <span className="num">{r.marketTime ? ist(r.marketTime) : "–"}</span>
      {r.paused
        ? <span className="rounded bg-warn/15 px-1.5 py-0.5 font-semibold text-warn">paused{r.pausedBecause ? ` · ${r.pausedBecause}` : ""}</span>
        : <span className="text-muted">running {r.speed}×{r.pauseOn !== "NONE" ? ` · until next ${r.pauseOn.toLowerCase()}${r.pauseOnSymbol ? ` of ${r.pauseOnSymbol}` : ""}` : ""}
            {r.pauseAt ? ` · until ${ist(r.pauseAt)}` : ""}</span>}
      <button type="button" className={btn} onClick={() => act.mutate(r.paused ? "action=resume" : "action=pause")}>
        {r.paused ? "▶ Resume" : "❚❚ Pause"}</button>
      <select aria-label="Speed" className="num rounded border border-line bg-panel px-1 py-1" value={r.speed}
        onChange={(e) => act.mutate(`action=speed&value=${e.target.value}`)}>
        {[...new Set([1, 5, 10, 30, 60, 120, 300, r.speed])].sort((a, b) => a - b).map((v) => <option key={v} value={v}>{v}×</option>)}
      </select>
      <button type="button" className={btn} onClick={() => act.mutate("action=next-entry")}>→ next entry</button>
      <button type="button" className={btn} onClick={() => act.mutate("action=next-exit")}>→ next exit</button>
      {[...new Set(open.map((p) => p.symbol))].map((symbol) => (
        <button key={symbol} type="button" className={btn} title="run on until this position exits, then pause"
          onClick={() => act.mutate(`action=next-exit&symbol=${encodeURIComponent(symbol)}`)}>→ exit of {symbol}</button>
      ))}
      <form className="flex items-center gap-1" onSubmit={(e) => { e.preventDefault(); if (until) act.mutate(`action=until&time=${until}`); }}>
        <input aria-label="Run until" className="num w-20 rounded border border-line bg-panel px-1 py-0.5" placeholder="HH:MM"
          value={until} onChange={(e) => setUntil(e.target.value)} />
        <button type="submit" className={btn} disabled={!/^\d{2}:\d{2}(:\d{2})?$/.test(until)}>run until</button>
      </form>
      {act.error && <span className="text-down">{String(act.error)}</span>}
    </div>
  );
}

/** Every trade of the session, one row each, the entry context and both snapshots flattened: for a spreadsheet or a study. */
export function downloadTradesCsv(rows: TradeContext[], name: string) {
  const flat = rows.map((r) => ({
    strategy: r.strategy_id, underlying: r.underlying, side: r.option_side, symbol: r.symbol, opened: r.opened_at,
    closed: r.closed_at, exit_reason: r.exit_reason, quantity: r.quantity, entry_price: r.average_cost, exit_price: r.average_exit,
    net: r.net, entry_reason: r.entry_reason,
    ...Object.fromEntries(Object.entries(flatten(r.entry_context)).map(([k, v]) => [`ctx.${k}`, v])),
    ...Object.fromEntries(Object.entries(flatten(r.entry_features)).map(([k, v]) => [`entry.${k}`, v])),
    ...Object.fromEntries(Object.entries(flatten(r.exit_features)).map(([k, v]) => [`exit.${k}`, v])),
  } as Record<string, unknown>));
  const columns = [...new Set(flat.flatMap((r) => Object.keys(r)))];
  const cell = (v: unknown) => { const t = v == null ? "" : typeof v === "object" ? JSON.stringify(v) : String(v); return /[",\n]/.test(t) ? `"${t.replace(/"/g, '""')}"` : t; };
  const text = [columns.join(","), ...flat.map((r) => columns.map((c) => cell(r[c])).join(","))].join("\n");
  const url = URL.createObjectURL(new Blob([text], { type: "text/csv" }));
  const a = document.createElement("a");
  a.href = url; a.download = name; a.click();
  URL.revokeObjectURL(url);
}

/** The trade picked on the chart: what the strategy saw at entry, and every feature at entry and exit with the change. */
export function TradeDetail({ trade, all, onClose, sessionId }: { trade: TradeContext; all: TradeContext[]; onClose: () => void; sessionId: number }) {
  const [filter, setFilter] = useState("");
  const ctx = flatten(trade.entry_context);
  const entry = flatten(trade.entry_features), exit = flatten(trade.exit_features);
  const keys = [...new Set([...Object.keys(entry), ...Object.keys(exit)])].sort();
  const match = (k: string) => !filter || k.toLowerCase().includes(filter.toLowerCase());
  const delta = (k: string) => typeof entry[k] === "number" && typeof exit[k] === "number" ? (exit[k] as number) - (entry[k] as number) : null;
  return (
    <Panel title={<>{trade.strategy_id ? strategyShort(trade.strategy_id) : ""} {trade.underlying} {trade.option_side} · {trade.symbol ?? "open"}
      <span className="ml-2 text-xs font-normal text-muted">{trade.opened_at.slice(11)} → {trade.closed_at?.slice(11) ?? "open"}
        {trade.exit_reason ? ` · ${trade.exit_reason}` : ""}{trade.net != null ? ` · net ₹${nf(trade.net)}` : ""}</span></>}
      right={<span className="flex gap-2 text-xs">
        <button type="button" className="text-accent underline" onClick={() => downloadTradesCsv(all, `session-${sessionId}-trades.csv`)}>
          all trades as CSV</button>
        <button type="button" className="text-muted hover:text-text" onClick={onClose}>close ✕</button></span>}>
      <div className="flex flex-col gap-4 lg:flex-row">
        <div className="shrink-0 lg:w-80">
          <div className="mb-1 text-xs font-semibold">What the strategy decided on{trade.entry_reason ? ` · ${trade.entry_reason}` : ""}</div>
          {Object.keys(ctx).length === 0 ? <p className="text-xs text-muted">No entry context stored for this trade.</p> : (
            <dl className="num grid grid-cols-[1fr_auto] gap-x-3 text-xs">
              {Object.entries(ctx).map(([k, v]) => (
                <div key={k} className="contents"><dt className={k.startsWith("conditions.") ? "text-muted italic" : "text-muted"}>{k}</dt>
                  <dd className={v === true ? "text-up" : v === false ? "text-down" : ""}>{show(v)}</dd></div>
              ))}
            </dl>
          )}
        </div>
        <div className="min-w-0 flex-1">
          <div className="mb-1 flex items-center gap-2 text-xs">
            <span className="font-semibold">Every feature at entry and exit</span>
            <input aria-label="Filter features" className="rounded border border-line bg-panel px-1 py-0.5" placeholder="filter, e.g. oi, iv, vwap"
              value={filter} onChange={(e) => setFilter(e.target.value)} />
            <span className="text-muted">{keys.filter(match).length} of {keys.length}</span>
          </div>
          {keys.length === 0 ? (
            <p className="text-xs text-muted">No feature snapshots for this session: live sessions keep them; start a replay with
              replay-snapshots to keep them for replays.</p>
          ) : (
            <div className="max-h-96 overflow-auto">
              <table className="num w-full text-xs">
                <thead className="sticky top-0 bg-panel text-left text-muted">
                  <tr><th className="py-1">feature</th><th className="text-right">entry</th><th className="text-right">exit</th><th className="text-right">change</th></tr>
                </thead>
                <tbody>
                  {keys.filter(match).map((k) => {
                    const d = delta(k);
                    return (
                      <tr key={k} className="border-t border-line">
                        <td className="py-0.5 text-muted">{k}</td>
                        <td className="text-right">{show(entry[k])}</td>
                        <td className="text-right">{show(exit[k])}</td>
                        <td className={`text-right ${d == null || d === 0 ? "" : d > 0 ? "text-up" : "text-down"}`}>{d == null ? "" : show(d)}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>
    </Panel>
  );
}

/**
 * The session as of the pinned minute (at the minute's close, as the chart's candle) or a paused replay's clock:
 * strategies, open positions marked then, features. A running replay is not followed (it would re-query every tick):
 * pause it, or pin a minute.
 */
export function MomentInspector({ sessionId, date, underlying, replayTime }: { sessionId: number; date: string; underlying: string; replayTime?: string }) {
  const [pin, setPin] = useMoment("pin", date, underlying);
  const [filter, setFilter] = useState("");
  const replay = useReplayState(replayTime != null);
  const paused = replayTime != null && replay.data?.paused === true;
  const time = pin != null ? `${hhmm(pin)}:59` : paused ? replayTime : undefined;
  const q = useQuery({
    queryKey: ["at", sessionId, underlying, time],
    queryFn: () => get<AtData>(`/api/sessions/${sessionId}/at?underlying=${underlying}&time=${time}`),
    enabled: time != null, retry: false,
    placeholderData: (prev) => prev,
  });
  if (time == null) {
    return <p className="text-xs text-muted">Click the chart to pin a minute and see everything the session knew then{replayTime != null
      ? ", or pause the replay to see its clock" : ""}; click a trade's arrow or square for its entry and exit data.</p>;
  }
  const d = q.data;
  const f = flatten(d?.snapshot?.features);
  const keys = Object.keys(f).sort().filter((k) => !filter || k.toLowerCase().includes(filter.toLowerCase()));
  // a SHORT leg (A-358) gains when the mark falls below its credit
  const legPnl = (p: { mark: number | null; average_cost: number; quantity: number; direction?: string | null }) =>
    p.mark == null ? 0 : (p.direction === "SHORT" ? p.average_cost - p.mark : p.mark - p.average_cost) * p.quantity;
  const unrealised = (d?.open ?? []).reduce((a, p) => a + legPnl(p), 0);
  return (
    <Panel title={<>{underlying} at {pin != null ? `${time.slice(0, 5)} (its close)` : time.slice(0, 8)} <span className="text-xs font-normal text-muted">{pin != null ? "pinned on the chart" : "the paused replay's clock"}</span></>}
      right={pin != null ? <button type="button" className="text-xs text-muted hover:text-text" onClick={() => setPin(null)}>unpin ✕</button> : undefined}>
      {q.error ? <p className="text-xs text-down">{String(q.error)}</p> : !d ? <p className="text-xs text-muted">Loading…</p> : (
        <div className="flex flex-col gap-4 lg:flex-row">
          <div className="shrink-0 space-y-3 text-xs lg:w-96">
            <div>
              <div className="mb-1 font-semibold">Strategies</div>
              {d.decisions.map((x) => (
                <div key={x.strategy_id} className="num flex justify-between gap-2">
                  <span className="text-muted">{strategyShort(x.strategy_id)}</span>
                  <span>CE {x.ce_stage.toLowerCase()} · PE {x.pe_stage.toLowerCase()} <span className="text-muted">({x.t})</span></span>
                </div>
              ))}
            </div>
            <div>
              <div className="mb-1 font-semibold">Positions open then <span className="font-normal text-muted">(quantity bought, marked at the option's last trade by then)</span></div>
              {d.open.length === 0 ? <p className="text-muted">None.</p> : d.open.map((p) => (
                <div key={p.symbol + p.opened_at} className="num">
                  {p.strategy_id ? strategyShort(p.strategy_id) : ""} {p.direction === "SHORT" ? "SHORT " : ""}{p.symbol} · {p.quantity} @ {nf(p.average_cost, 2)} → {p.mark == null ? "–" : nf(p.mark, 2)}
                  {p.mark != null && <span className={legPnl(p) >= 0 ? "text-up" : "text-down"}> ₹{nf(legPnl(p))}</span>}
                </div>
              ))}
              <p className="num mt-1 text-muted">closed by then ₹{nf(d.closedNet)} · open ₹{nf(unrealised)} (before costs)</p>
            </div>
          </div>
          <div className="min-w-0 flex-1">
            <div className="mb-1 flex items-center gap-2 text-xs">
              <span className="font-semibold">Features{d.snapshot ? ` at ${d.snapshot.t} · ${d.snapshot.phase}` : ""}</span>
              <input aria-label="Filter features" className="rounded border border-line bg-panel px-1 py-0.5" placeholder="filter"
                value={filter} onChange={(e) => setFilter(e.target.value)} />
            </div>
            {!d.snapshot ? <p className="text-xs text-muted">No feature snapshot kept for this session at that minute.</p> : (
              <div className="max-h-80 overflow-auto">
                <dl className="num grid grid-cols-[1fr_auto] gap-x-3 text-xs">
                  {keys.map((k) => <div key={k} className="contents"><dt className="text-muted">{k}</dt><dd className="text-right">{show(f[k])}</dd></div>)}
                </dl>
              </div>
            )}
          </div>
        </div>
      )}
    </Panel>
  );
}
