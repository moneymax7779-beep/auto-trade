import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useMemo, useRef, useState } from "react";
import { get, plainError, postJson, type ExecMe } from "../api";
import { Big, Card, ModeBadge, serviceDown, Sheet, signed, tone } from "../mobile/common";

/**
 * The owner's desk (plan 2026-11-23 feature B, PAPER only; user 23 Nov 2026: "start with desk trades PAPER only"): one
 * trade to the paper books of all or chosen accounts at once. A real account's trade goes to its paper twin. Each account
 * is sized by its own equity (or fixed lots), under the desk cap (open desk premium at most 20 % of equity), and runs
 * through its own risk checks; the stop and target are watched on the captured bid and exit every holder.
 */

interface Candidate { token: number; symbol: string; strike: number; expiry: string; lotSize: number; bid: number; ask: number;
  askQuantity: number; spreadPct: number; quoteAt: string; refusal: string | null }
interface Quote { underlying: string; side: string; spot: number; spotAt: string; best: string; candidates: Candidate[] }
interface DeskAccount { id: number; account: string; broker: string; mode: string; tenant_id: number; tenant: string;
  paper_id: number | null; paper_account: string | null; allocated_capital: number }
interface Target { requestedId: number; requested: string; tenant: string | null; accountId: number | null; account: string | null;
  equity: number; deskOpen: number; lots: number; quantity: number; premium: number; refusal: string | null }
interface Preview { contract: Candidate; spot: number; targets: Target[]; totalQuantity: number; askDepth: number;
  depthWarning: string | null }
interface Placed { orderId: number; repeat: boolean; targets: Target[] }
interface OrderTarget { requested: string; tenant: string; account: string | null; lots: number; quantity: number;
  refusal: string | null; intent_state: string | null; reject_code: string | null; reject_reason: string | null;
  position_status: string | null; quantity_open: number | null; average_entry: number | null; exit_reason: string | null;
  unrealised?: number; net?: number }
interface DeskOrder { id: number; created_by: string; created_at: string; underlying: string; option_side: string; symbol: string;
  reference_ask: number; sizing: string; sizing_value: number; stop_pct: number; target_pct: number | null; reason: string;
  exit_reason: string | null; bid: number | null; stopAt: number; targetAt: number | null; targets: OrderTarget[];
  net: number; open: number; state: "OPEN" | "EXITING" | "WAITING" | "CLOSED" | "NONE" }

const newKey = () => (typeof crypto !== "undefined" && "randomUUID" in crypto ? crypto.randomUUID()
  : `${Date.now()}-${Math.random()}`);
const rupees = (v: number) => `₹${Math.round(v).toLocaleString("en-IN")}`;
const field = "min-h-11 rounded-lg border border-line bg-bg px-3 text-base";

/** A request that failed, said as a sentence (no status code or API path). */
function Problem({ error }: { error: unknown }) {
  if (!error) return null;
  return <p className="rounded-lg border border-down/40 bg-down/5 px-3 py-2 text-sm text-down">{serviceDown(error)
    ? "The trading service is not answering. The desk shows again when it is back." : plainError(error)}</p>;
}
const chip = (on: boolean) => `min-h-11 rounded-lg border px-4 text-sm font-semibold ${on ? "border-sky-700 bg-sky-700 text-white"
  : "border-line"}`;

export function DeskPage() {
  const me = useQuery({ queryKey: ["exec-me"], queryFn: () => get<ExecMe>("/api/execution/me"), staleTime: 30_000, retry: false });
  const admin = me.data?.admin === true;
  if (me.data && !admin) return <p className="text-sm text-muted">The desk is the platform admin&apos;s.</p>;
  return (
    <div className="mx-auto flex max-w-6xl flex-col gap-3">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h1 className="flex items-center gap-2 text-xl font-semibold">Desk <ModeBadge mode="PAPER" /></h1>
        <p className="text-xs text-muted">PAPER only: a real account&apos;s trade goes to its paper twin. Desk trades are their own
          strategy (&quot;desk&quot;) in every report.</p>
      </div>
      <Problem error={me.error} />
      {admin && <NewOrder />}
      {admin && <Orders />}
    </div>
  );
}

function NewOrder() {
  const client = useQueryClient();
  const [underlying, setUnderlying] = useState("NIFTY");
  const [side, setSide] = useState<"CE" | "PE">("PE");
  const [symbol, setSymbol] = useState<string | null>(null);
  const [sizing, setSizing] = useState<"PCT_EQUITY" | "FIXED_LOTS">("PCT_EQUITY");
  const [pct, setPct] = useState("5");
  const [lots, setLots] = useState("1");
  const [stop, setStop] = useState("25");
  const [target, setTarget] = useState("");
  const [reason, setReason] = useState("");
  const [chosen, setChosen] = useState<Set<number>>(new Set());
  // what was previewed is what is sent (a change to the form after Preview never reaches the send unseen)
  const [confirm, setConfirm] = useState<{ preview: Preview; body: object } | null>(null);
  const quote = useQuery({ queryKey: ["desk-quote", underlying, side], refetchInterval: 3_000, retry: false,
    queryFn: () => get<Quote>(`/api/execution/desk/quote?underlying=${underlying}&side=${side}`) });
  const accounts = useQuery({ queryKey: ["desk-accounts"], queryFn: () => get<DeskAccount[]>("/api/execution/desk/accounts") });
  const picked = symbol && quote.data?.candidates.some((c) => c.symbol === symbol) ? symbol : quote.data?.best ?? null;
  const byTenant = useMemo(() => {
    const m = new Map<string, DeskAccount[]>();
    (accounts.data ?? []).forEach((a) => m.set(a.tenant, [...(m.get(a.tenant) ?? []), a]));
    return [...m.entries()];
  }, [accounts.data]);
  const body = () => ({ underlying, side, symbol: picked, sizing, value: Number(sizing === "PCT_EQUITY" ? pct : lots),
    stopPct: Number(stop), targetPct: target.trim() === "" ? null : Number(target), reason: reason.trim(), accounts: [...chosen] });
  const preview = useMutation({
    mutationFn: async () => { const b = body(); return { preview: await postJson<Preview>("/api/execution/desk/preview", b), body: b }; },
    onSuccess: (r) => setConfirm(r) });
  const toggle = (id: number) => setChosen((s) => { const n = new Set(s); if (n.has(id)) n.delete(id); else n.add(id); return n; });
  const ready = picked != null && chosen.size > 0 && reason.trim().length >= 3 && Number(stop) > 0 && Number(stop) < 100
    && (sizing === "PCT_EQUITY" ? Number(pct) > 0 && Number(pct) <= 20 : Number.isInteger(Number(lots)) && Number(lots) >= 1);
  return (
    <Card>
      <h2 className="mb-2 text-base font-semibold">New desk order</h2>
      <div className="flex flex-col gap-3">
        <div className="flex flex-wrap gap-2">
          {["NIFTY", "SENSEX"].map((u) => <button key={u} type="button" className={chip(underlying === u)}
            onClick={() => { setUnderlying(u); setSymbol(null); }}>{u}</button>)}
          <span className="w-2" />
          {(["CE", "PE"] as const).map((s) => <button key={s} type="button" className={chip(side === s)}
            onClick={() => { setSide(s); setSymbol(null); }}>BUY {s}</button>)}
        </div>
        <div>
          <p className="mb-1 text-xs text-muted">Strike (around ATM{quote.data ? `, spot ${quote.data.spot.toFixed(2)}` : ""}; refreshed
            every 3 s)</p>
          {quote.error ? <p className="text-sm text-down">{serviceDown(quote.error) ? "The trading service is not answering."
            : plainError(quote.error)}</p> : !quote.data ? <p className="text-sm text-muted">Loading…</p> : (
            <div className="flex flex-col gap-1">
              {quote.data.candidates.map((c) => (
                <label key={c.symbol} className={`flex flex-wrap items-center gap-2 rounded-lg border px-3 py-2 text-sm ${picked === c.symbol
                  ? "border-sky-700" : "border-line"} ${c.refusal ? "opacity-60" : ""}`}>
                  <input type="radio" name="strike" className="h-5 w-5 shrink-0" checked={picked === c.symbol} disabled={c.refusal != null}
                    onChange={() => setSymbol(c.symbol)} />
                  <b>{c.symbol}</b>
                  <span className="num">{c.bid.toFixed(2)} / {c.ask.toFixed(2)} · spread {Number.isFinite(c.spreadPct) ? c.spreadPct.toFixed(1) : "–"} %
                    · {c.askQuantity.toLocaleString("en-IN")} offered</span>
                  {c.symbol === quote.data!.best && <span className="text-xs text-up">tightest</span>}
                  {c.refusal && <span className="text-xs text-down">{c.refusal}</span>}
                </label>
              ))}
            </div>
          )}
        </div>
        <div className="flex flex-wrap items-end gap-3">
          <fieldset className="flex flex-col gap-1 text-sm">
            <legend className="mb-1 text-xs text-muted">Size per account</legend>
            <label className="flex items-center gap-2"><input type="radio" className="h-5 w-5" checked={sizing === "PCT_EQUITY"}
              onChange={() => setSizing("PCT_EQUITY")} /> % of its equity
              <input className={`${field} w-20`} inputMode="decimal" value={pct} onChange={(e) => setPct(e.target.value)}
                aria-label="% of equity" disabled={sizing !== "PCT_EQUITY"} /></label>
            <label className="flex items-center gap-2"><input type="radio" className="h-5 w-5" checked={sizing === "FIXED_LOTS"}
              onChange={() => setSizing("FIXED_LOTS")} /> fixed lots
              <input className={`${field} w-20`} inputMode="numeric" value={lots} onChange={(e) => setLots(e.target.value)}
                aria-label="Lots" disabled={sizing !== "FIXED_LOTS"} /></label>
          </fieldset>
          <label className="flex flex-col text-xs text-muted">Stop % below the price
            <input className={`${field} w-24`} inputMode="decimal" value={stop} onChange={(e) => setStop(e.target.value)} /></label>
          <label className="flex flex-col text-xs text-muted">Target % above (optional)
            <input className={`${field} w-24`} inputMode="decimal" value={target} onChange={(e) => setTarget(e.target.value)} /></label>
        </div>
        <div>
          <div className="mb-1 flex flex-wrap items-baseline justify-between gap-2">
            <p className="text-xs text-muted">Accounts ({chosen.size} chosen)</p>
            <span className="flex gap-3 text-xs">
              <button type="button" className="text-sky-700 underline" onClick={() => setChosen(new Set((accounts.data ?? []).map((a) => a.id)))}>all</button>
              <button type="button" className="text-sky-700 underline" onClick={() => setChosen(new Set())}>none</button>
            </span>
          </div>
          {accounts.error ? <Problem error={accounts.error} /> : (
            <div className="grid gap-2 md:grid-cols-2">
              {byTenant.map(([tenant, list]) => (
                <div key={tenant} className="rounded-lg border border-line p-2">
                  <p className="mb-1 text-xs font-semibold">{tenant}</p>
                  {list.map((a) => (
                    <label key={a.id} className="flex items-center gap-2 py-1 text-sm">
                      <input type="checkbox" className="h-5 w-5 shrink-0" checked={chosen.has(a.id)} onChange={() => toggle(a.id)} />
                      <span>{a.account}{a.broker !== "PAPER" && <span className="text-muted">{a.paper_account ? ` → ${a.paper_account}`
                        : " · no paper twin"}</span>}</span>
                    </label>
                  ))}
                </div>
              ))}
            </div>
          )}
        </div>
        <input className={field} placeholder="Reason (audited, shown in each account's history)" value={reason} maxLength={300}
          onChange={(e) => setReason(e.target.value)} aria-label="Reason" />
        {preview.error && <p className="text-sm text-down">{plainError(preview.error)}</p>}
        <Big kind="primary" disabled={!ready || preview.isPending} onClick={() => preview.mutate()}>
          {preview.isPending ? "Working it out…" : "Preview"}</Big>
      </div>
      {confirm && <SendSheet preview={confirm.preview} body={confirm.body} onClose={() => setConfirm(null)}
        onSent={() => { client.invalidateQueries({ queryKey: ["desk-orders"] }); }} />}
    </Card>
  );
}

function SendSheet({ preview, body, onClose, onSent }: { preview: Preview; body: object; onClose: () => void;
  onSent: () => void }) {
  const [typed, setTyped] = useState("");
  const [key] = useState(newKey);
  const sending = useRef(false);
  const n = preview.targets.length;
  const phrase = `SEND ${n} ORDERS`;
  const ok = preview.targets.filter((t) => t.refusal == null);
  const send = useMutation({
    mutationFn: () => postJson<Placed>("/api/execution/desk/orders", { ...body, key, confirm: phrase }),
    onSuccess: onSent, onSettled: () => { sending.current = false; },
  });
  const c = preview.contract;
  return (
    <Sheet title={`BUY ${c.symbol}`} onClose={onClose}>
      {send.data ? (
        <div className="flex flex-col gap-3">
          <p className="text-sm">{send.data.repeat ? "Already sent: this is that order. " : ""}Desk order #{send.data.orderId}:{" "}
            {send.data.targets.filter((t) => t.refusal == null).length} paper buy(s) written; each runs on its account&apos;s next
            turn, through its own checks.</p>
          <Big onClick={onClose}>Done</Big>
        </div>
      ) : (
        <div className="flex flex-col gap-3">
          <p className="text-sm">At ask {c.ask.toFixed(2)} (bid {c.bid.toFixed(2)}), spot {preview.spot.toFixed(2)}. Each account
            fills on its own paper book; a refused one gets nothing.</p>
          <div className="max-h-72 overflow-y-auto">
            <table className="w-full text-sm">
              <thead className="text-left text-xs text-muted"><tr><th className="py-1 pr-2">Account</th><th className="pr-2">Goes to</th>
                <th className="pr-2 text-right">Equity</th><th className="pr-2 text-right">Lots</th><th className="text-right">Premium</th></tr></thead>
              <tbody>
                {preview.targets.map((t) => (
                  <tr key={t.requestedId} className="border-t border-line align-top">
                    <td className="py-1 pr-2">{t.requested}<div className="text-xs text-muted">{t.tenant}</div></td>
                    {t.refusal ? <td colSpan={4} className="py-1 text-xs text-down">{t.refusal}</td> : <>
                      <td className="pr-2">{t.account}</td>
                      <td className="num pr-2 text-right">{rupees(t.equity)}</td>
                      <td className="num pr-2 text-right">{t.lots}</td>
                      <td className="num text-right">{rupees(t.premium)}</td></>}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <p className="text-sm">{ok.length} of {n} take it: {ok.reduce((a, t) => a + t.quantity, 0).toLocaleString("en-IN")} qty,{" "}
            {rupees(ok.reduce((a, t) => a + t.premium, 0))} premium in all.</p>
          {preview.depthWarning && <p className="text-sm font-semibold text-warn">{preview.depthWarning}</p>}
          <input className={`${field} uppercase`} placeholder={`Type ${phrase}`} autoCapitalize="characters" autoCorrect="off"
            value={typed} onChange={(e) => setTyped(e.target.value)} aria-label={`Type ${phrase}`} />
          {send.error && <p className="text-sm text-down">{plainError(send.error)}</p>}
          <Big kind="primary" disabled={ok.length === 0 || typed.trim().toUpperCase() !== phrase || send.isPending}
            onClick={() => { if (!sending.current) { sending.current = true; send.mutate(); } }}>
            {send.isPending ? "Sending…" : `Send to ${ok.length} paper book${ok.length === 1 ? "" : "s"}`}</Big>
          <Big onClick={onClose}>Cancel</Big>
        </div>
      )}
    </Sheet>
  );
}

function Orders() {
  const orders = useQuery({ queryKey: ["desk-orders"], refetchInterval: 3_000,
    queryFn: () => get<DeskOrder[]>("/api/execution/desk/orders") });
  const [exiting, setExiting] = useState<DeskOrder | null>(null);
  return (
    <Card>
      <h2 className="mb-2 text-base font-semibold">Today&apos;s desk orders</h2>
      <Problem error={orders.error} />
      {!orders.data ? <p className="text-sm text-muted">Loading…</p> : orders.data.length === 0
        ? <p className="text-sm text-muted">None today.</p> : (
        <div className="flex flex-col gap-3">
          {orders.data.map((o) => (
            <div key={o.id} className="rounded-lg border border-line">
              <div className="flex flex-wrap items-center justify-between gap-2 border-b border-line px-3 py-2 text-sm">
                <span><b>#{o.id} BUY {o.symbol}</b> <span className="text-muted">· at {o.reference_ask.toFixed(2)} · stop {o.stopAt.toFixed(2)}
                  {o.targetAt != null ? ` · target ${o.targetAt.toFixed(2)}` : ""} · bid {o.bid != null ? o.bid.toFixed(2) : "–"} · {o.reason}</span></span>
                <span className="flex items-center gap-2">
                  <span className={`rounded px-1.5 py-0.5 text-[11px] font-semibold ${o.state === "OPEN" ? "bg-up/15 text-up"
                    : o.state === "EXITING" ? "bg-warn/15 text-warn" : "bg-panel-2 text-muted"}`}>{o.state}</span>
                  <span className={`num font-semibold ${tone(o.net)}`}>{signed(o.net)}</span>
                  {(o.state === "OPEN" || o.state === "WAITING" || o.state === "EXITING") && (
                    <button type="button" className="min-h-9 rounded-lg border border-down px-3 text-xs font-semibold text-down"
                      onClick={() => setExiting(o)}>Exit all holders…</button>)}
                </span>
              </div>
              {o.exit_reason && <p className="px-3 pt-2 text-xs text-muted">Exit: {o.exit_reason}</p>}
              <ul className="divide-y divide-line">
                {o.targets.map((t, i) => (
                  <li key={i} className="flex flex-wrap justify-between gap-2 px-3 py-2 text-sm">
                    <span>{t.requested}{t.account && t.account !== t.requested ? <span className="text-muted"> → {t.account}</span> : null}
                      <span className="text-xs text-muted"> · {t.tenant}</span></span>
                    <span className="num text-xs">{t.refusal ? <span className="text-down">{t.refusal}</span>
                      : t.position_status ? `${t.quantity_open ?? 0} open · in at ${t.average_entry?.toFixed(2)}${t.exit_reason ? ` · ${t.exit_reason}` : ""}`
                      : `${t.lots} lots · ${(t.intent_state ?? "").toLowerCase()}${t.reject_reason ? `: ${t.reject_reason}` : ""}`}</span>
                    {t.net != null && <span className={`num font-semibold ${tone(t.net)}`}>{signed(t.net)}</span>}
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </div>
      )}
      {exiting && <ExitSheet order={exiting} onClose={() => setExiting(null)} onDone={() => orders.refetch()} />}
    </Card>
  );
}

function ExitSheet({ order, onClose, onDone }: { order: DeskOrder; onClose: () => void; onDone: () => void }) {
  const [reason, setReason] = useState("");
  const [typed, setTyped] = useState("");
  const sending = useRef(false);
  const exit = useMutation({
    mutationFn: () => postJson<{ lines: string[] }>(`/api/execution/desk/orders/${order.id}/exit`, { reason: reason.trim(),
      confirm: typed.trim() }),
    onSettled: () => { sending.current = false; onDone(); },
  });
  return (
    <Sheet title={`Exit desk order #${order.id} · ${order.symbol}`} onClose={onClose}>
      {exit.data ? (
        <div className="flex flex-col gap-3">
          <ul className="list-disc pl-5 text-sm">{exit.data.lines.map((l, i) => <li key={i}>{l}</li>)}</ul>
          <p className="text-xs text-muted">What could not be sold yet (no fresh bid) is tried again every few seconds.</p>
          <Big onClick={onClose}>Done</Big>
        </div>
      ) : (
        <div className="flex flex-col gap-3">
          <p className="text-sm">Sells every account still holding it ({order.open} open) at the latest bid, and cancels buys not yet
            run.</p>
          <input className={field} placeholder="Reason (audited)" value={reason} maxLength={300}
            onChange={(e) => setReason(e.target.value)} aria-label="Reason" />
          <input className={`${field} uppercase`} placeholder="Type EXIT" autoCapitalize="characters" autoCorrect="off" value={typed}
            onChange={(e) => setTyped(e.target.value)} aria-label="Type EXIT" />
          {exit.error && <p className="text-sm text-down">{plainError(exit.error)}</p>}
          <Big kind="danger" disabled={typed.trim().toUpperCase() !== "EXIT" || reason.trim().length < 3 || exit.isPending}
            onClick={() => { if (!sending.current) { sending.current = true; exit.mutate(); } }}>
            {exit.isPending ? "Exiting…" : "Exit all holders"}</Big>
          <Big onClick={onClose}>Cancel</Big>
        </div>
      )}
    </Sheet>
  );
}
