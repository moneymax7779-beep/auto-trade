import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { get, postJson } from "../api";
import { ErrorNote } from "./ui";

/**
 * Manual trading (user, 9 Nov 2026: "buy PE or buy CE buttons so that application should immediately buy the best ATM
 * PE CE ... and when I click EXIT it should exit the order in LIVE/PAPER"). One follower account and one index at a time:
 * BUY CE / BUY PE buy 1 lot of the best ATM contract (tightest spread of the three strikes around spot, nearest expiry)
 * through the account's normal order path (risk checks, kill switches, LiveGuard, the 25 % broker stop); EXIT cancels a
 * buy not sent yet and sells the index's manual position. Every action is confirmed first, showing the account, its
 * mode, the contract and the quantity; a LIVE account's confirmation is red and says real money.
 */

interface ManualAccount {
  id: number;
  name: string;
  broker: string;
  mode: "PAPER" | "DRY_RUN" | "LIVE" | string;
  liveSwitch: boolean;
  canBuy: boolean;
  canExit: boolean;
  liveBlocked: string | null;
  /** a daily-switch workspace account that does not take entries now (why) */
  blocked?: string | null;
  lots: number;
  open: { id: number; underlying: string; side: string; symbol: string; quantity: number; entry: number }[];
}

interface Candidate { symbol: string; strike: number; bid: number; ask: number; spreadPct: number | null; refusal: string | null }

interface ManualQuote {
  underlying: string;
  side: string;
  spot: number;
  symbol: string;
  strike: number;
  expiry: string;
  lotSize: number;
  bid: number;
  ask: number;
  spreadPct: number | null;
  candidates: Candidate[];
}

interface ManualOrderRow {
  intentId: number;
  underlying: string;
  side: string;
  symbol: string;
  quantity: number;
  mode: string;
  state: string;
  rejectCode: string | null;
  rejectReason: string | null;
  requested: string;
  orders: { role: string; side: string; status: string; requested: number; filled: number; limit: number | null;
    trigger: number | null; average: number | null; error: string | null; at: string }[];
  position?: { id: number; open: number; entry: number; status: string; exitReason: string | null; gross: number | null;
    charges: number | null };
}

interface ManualDetail extends ManualAccount {
  killSwitch: string | null;
  orders: ManualOrderRow[];
}

const INDICES = ["NIFTY", "SENSEX"] as const;

const px = (v: number | null | undefined) => (v == null || !Number.isFinite(v) ? "–" : v.toFixed(2));
const hhmmss = (iso: string | null | undefined) => (iso ? iso.slice(11, 19) : "–");
const btn = "rounded-md border px-4 py-2 text-sm font-semibold disabled:cursor-not-allowed disabled:opacity-40";
/** A buy not run to its end yet: not taken by its turn, or its broker orders waiting for a session. */
const working = (o: ManualOrderRow) => o.state === "CREATED";

function modeBadge(mode: string) {
  const cls = mode === "LIVE" ? "bg-rose-600 text-white" : mode === "DRY_RUN" ? "bg-warn/15 text-warn"
    : "bg-panel-2 text-muted";
  return <span className={`rounded px-1.5 py-0.5 text-xs font-bold ${cls}`}>{mode === "LIVE" ? "LIVE · real money" : mode}</span>;
}

function stateTone(state: string) {
  return state === "EXECUTED" ? "text-up" : ["REJECTED", "FAILED", "RECONCILIATION_REQUIRED"].includes(state) ? "text-down"
    : state === "CANCELLED" || state === "SKIPPED" ? "text-muted" : "text-warn";
}

export function ManualTradePanel() {
  const accounts = useQuery({ queryKey: ["manual-accounts"],
    queryFn: () => get<ManualAccount[]>("/api/execution/manual/accounts"), refetchInterval: 15_000 });
  const usable = (accounts.data ?? []).filter((a) => a.canBuy || a.canExit);
  const [picked, setPicked] = useState<number | null>(null);
  const [index, setIndex] = useState<(typeof INDICES)[number]>("NIFTY");
  if (accounts.error) return <ErrorNote error={accounts.error} />;
  if (usable.length === 0) return null;
  const accountId = usable.some((a) => a.id === picked) ? picked! : usable[0].id;
  return (
    <section className="min-w-0 rounded-lg border border-line bg-panel">
      <header className="flex flex-wrap items-center justify-between gap-2 border-b border-line px-4 py-2.5">
        <h2 className="text-sm font-semibold tracking-wide">Manual trade</h2>
        <div className="flex flex-wrap items-center gap-2 text-sm">
          <label className="flex items-center gap-1.5 text-xs text-muted">Account
            <select value={accountId} onChange={(e) => setPicked(Number(e.target.value))}
              className="rounded border border-line bg-bg px-2 py-1 text-sm text-text">
              {usable.map((a) => <option key={a.id} value={a.id}>{a.name} · {a.broker} · {a.mode}</option>)}
            </select></label>
          <div className="flex overflow-hidden rounded border border-line">
            {INDICES.map((u) => (
              <button key={u} onClick={() => setIndex(u)}
                className={`px-3 py-1 text-xs font-semibold ${index === u ? "bg-accent text-white" : "bg-bg text-muted"}`}>{u}</button>
            ))}
          </div>
        </div>
      </header>
      {/* keyed by account and index: a confirmation never outlives a switch of either */}
      <div className="p-4"><ManualAccountView key={`${accountId}:${index}`} accountId={accountId} index={index} /></div>
    </section>
  );
}

function ManualAccountView({ accountId, index }: { accountId: number; index: string }) {
  const client = useQueryClient();
  const detail = useQuery({ queryKey: ["manual", accountId],
    queryFn: () => get<ManualDetail>(`/api/execution/accounts/${accountId}/manual`),
    refetchInterval: (q) => ((q.state.data?.orders ?? []).some(working) ? 1_000 : 5_000) });
  const [confirm, setConfirm] = useState<{ kind: "BUY"; side: "CE" | "PE"; quote: ManualQuote } | { kind: "EXIT" } | null>(null);
  const [note, setNote] = useState<string | null>(null);
  const refresh = () => {
    client.invalidateQueries({ queryKey: ["manual", accountId] });
    client.invalidateQueries({ queryKey: ["live-book", accountId] });
    client.invalidateQueries({ queryKey: ["manual-accounts"] });
  };
  const quote = useMutation({
    mutationFn: (side: "CE" | "PE") => get<ManualQuote>(
      `/api/execution/accounts/${accountId}/manual/quote?underlying=${index}&side=${side}`),
    onSuccess: (q, side) => setConfirm({ kind: "BUY", side, quote: q }),
  });
  const buy = useMutation({
    mutationFn: (v: { underlying: string; side: string; symbol: string; live: boolean }) => postJson<{ intentId: number;
      symbol: string; quantity: number }>(`/api/execution/accounts/${accountId}/manual/buy`,
      { underlying: v.underlying, side: v.side, symbol: v.symbol, confirm: v.live ? "BUY LIVE" : "BUY" }),
    onSuccess: (r) => { setNote(`Buy of ${r.quantity} ${r.symbol} sent to the account's order path`); setConfirm(null); },
    onSettled: refresh,
  });
  const exit = useMutation({
    mutationFn: () => postJson<{ result: string[] }>(`/api/execution/accounts/${accountId}/manual/exit`,
      { underlying: index, confirm: "EXIT" }),
    onSuccess: (r) => { setNote(r.result.join(" · ")); setConfirm(null); },
    onSettled: refresh,
  });

  if (detail.error) return <ErrorNote error={detail.error} />;
  if (!detail.data) return <p className="text-sm text-muted">Loading…</p>;
  const a = detail.data;
  const live = a.mode === "LIVE";
  const openHere = a.open.filter((p) => p.underlying === index);
  const pending = a.orders.some((o) => o.underlying === index && working(o));
  const buyBlocked = !a.canBuy ? "your role cannot buy on this account" : a.blocked ? a.blocked : live && a.liveBlocked ? `LIVE buy blocked: ${a.liveBlocked}`
    : a.killSwitch ? `kill switch engaged: ${a.killSwitch}` : openHere.length > 0 ? `holds ${openHere[0].symbol}: EXIT it first`
    : pending ? "a buy is still working" : null;
  const busy = quote.isPending || buy.isPending || exit.isPending;

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-2 text-sm">
        <b>{a.name}</b> <span className="text-muted">{a.broker}</span> {modeBadge(a.mode)}
        <span className="text-muted">· {a.lots} lot a buy · nearest expiry · 25 % broker stop · followers do not copy</span>
      </div>
      <div className="flex flex-wrap items-center gap-2">
        <button className={`${btn} border-emerald-600 bg-emerald-600 text-white hover:bg-emerald-700`} disabled={busy || !!buyBlocked}
          onClick={() => { setNote(null); buy.reset(); quote.mutate("CE"); }}>BUY {index} CE</button>
        <button className={`${btn} border-rose-600 bg-rose-600 text-white hover:bg-rose-700`} disabled={busy || !!buyBlocked}
          onClick={() => { setNote(null); buy.reset(); quote.mutate("PE"); }}>BUY {index} PE</button>
        <button className={`${btn} border-line bg-bg text-text hover:bg-panel-2`}
          disabled={busy || !a.canExit || (openHere.length === 0 && !pending)}
          onClick={() => { setNote(null); exit.reset(); setConfirm({ kind: "EXIT" }); }}>EXIT {index}</button>
        {quote.isPending && <span className="text-sm text-muted">Finding the best ATM…</span>}
        {buyBlocked && <span className="text-xs text-muted">{buyBlocked}</span>}
      </div>
      {quote.error && <p className="text-sm text-down">No contract to buy: {(quote.error as Error).message}</p>}

      {confirm?.kind === "BUY" && (
        <div className={`space-y-2 rounded-md border px-3 py-2 text-sm ${live ? "border-down/60 bg-down/10" : "border-line bg-panel-2"}`}>
          <div>
            {live && <b className="text-down">REAL ORDER at {a.broker}. </b>}
            Buy <b>{a.lots} lot · {a.lots * confirm.quote.lotSize} qty</b> of <b>{confirm.quote.symbol}</b> on <b>{a.name}</b> ({a.mode})
            {" "}at about ₹{px(confirm.quote.ask)} (bid {px(confirm.quote.bid)}, spread {confirm.quote.spreadPct == null ? "–" : confirm.quote.spreadPct.toFixed(1)} %,
            {" "}spot {confirm.quote.spot.toLocaleString("en-IN")}) — about ₹{Math.round(a.lots * confirm.quote.lotSize * confirm.quote.ask).toLocaleString("en-IN")} premium.
          </div>
          <div className="text-xs text-muted">
            Strikes looked at: {confirm.quote.candidates.map((c) => (
              <span key={c.symbol} className={c.symbol === confirm.quote.symbol ? "font-semibold text-text" : ""}>
                {c.strike} ({c.refusal ?? `${px(c.bid)}/${px(c.ask)}, ${c.spreadPct?.toFixed(1)} %`}){" "}
              </span>))}
          </div>
          <div className="flex flex-wrap gap-2">
            <button className={`${btn} ${live ? "border-rose-700 bg-rose-700" : "border-accent bg-accent"} text-white`} disabled={buy.isPending}
              onClick={() => buy.mutate({ underlying: confirm.quote.underlying, side: confirm.side, symbol: confirm.quote.symbol, live })}>
              {buy.isPending ? "Sending…" : live ? `Confirm BUY ${confirm.side} LIVE` : `Confirm BUY ${confirm.side}`}</button>
            <button className={`${btn} border-line bg-bg text-text`} disabled={buy.isPending} onClick={() => setConfirm(null)}>Cancel</button>
          </div>
          {buy.error && <p className="text-down">Not bought: {(buy.error as Error).message}</p>}
        </div>
      )}

      {confirm?.kind === "EXIT" && (
        <div className={`space-y-2 rounded-md border px-3 py-2 text-sm ${live ? "border-down/60 bg-down/10" : "border-line bg-panel-2"}`}>
          <div>
            {live && <b className="text-down">REAL ORDER at {a.broker}. </b>}
            {openHere.length > 0
              ? <>Sell {openHere.map((p) => <b key={p.id}>{p.quantity} {p.symbol} </b>)}on <b>{a.name}</b> ({a.mode}) now? Its stop comes off first.</>
              : <>Cancel the {index} buy on <b>{a.name}</b> if it has not been sent; anything it bought is sold.</>}
          </div>
          <div className="flex flex-wrap gap-2">
            <button className={`${btn} border-rose-600 bg-rose-600 text-white`} disabled={exit.isPending}
              onClick={() => exit.mutate()}>{exit.isPending ? "Exiting…" : "Confirm EXIT"}</button>
            <button className={`${btn} border-line bg-bg text-text`} disabled={exit.isPending} onClick={() => setConfirm(null)}>Cancel</button>
          </div>
          {exit.error && <p className="text-down">Not exited: {(exit.error as Error).message}</p>}
        </div>
      )}
      {note && <p className="text-sm text-muted">{note}</p>}

      {a.orders.length > 0 && (
        <div className="overflow-x-auto">
          <table className="w-full text-left text-sm">
            <thead className="text-xs text-muted">
              <tr><th className="py-1 pr-3">Time</th><th className="pr-3">Contract</th><th className="pr-3">Qty</th>
                <th className="pr-3">Mode</th><th className="pr-3">State</th><th className="pr-3">Orders</th><th>Position</th></tr>
            </thead>
            <tbody>
              {a.orders.map((o) => (
                <tr key={o.intentId} className="border-t border-line align-top">
                  <td className="num py-1 pr-3">{hhmmss(o.requested)}</td>
                  <td className="pr-3">{o.symbol}</td>
                  <td className="num pr-3">{o.quantity}</td>
                  <td className="pr-3">{o.mode}</td>
                  <td className={`pr-3 font-semibold ${stateTone(o.state)}`}>{o.state}
                    {o.rejectCode && <div className="text-xs font-normal">{o.rejectCode}: {o.rejectReason}</div>}</td>
                  <td className="pr-3 text-xs">{o.orders.length === 0 ? "–" : o.orders.map((x, i) => (
                    <div key={i}>{x.role} {x.side} {x.status} {x.filled}/{x.requested}
                      {x.average != null ? ` @ ${px(x.average)}` : x.trigger ? ` trig ${px(x.trigger)}` : x.limit ? ` lmt ${px(x.limit)}` : ""}
                      {x.error ? <span className="text-down"> {x.error}</span> : null}</div>))}</td>
                  <td className="text-xs">{o.position == null ? "–" : o.position.status === "CLOSED"
                    ? <>closed ({o.position.exitReason}) · gross <span className={(o.position.gross ?? 0) >= 0 ? "text-up" : "text-down"}>
                        ₹{px(o.position.gross)}</span></>
                    : <>{o.position.open} open @ {px(o.position.entry)}</>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
