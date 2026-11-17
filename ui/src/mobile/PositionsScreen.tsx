import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useRef, useState } from "react";
import { get, postJson, type ExecutionAccount, type ExitPreview, type LiveBook, type LiveOpenPosition } from "../api";
import { activeAccounts, Big, Card, ErrorLine, Freshness, istTime, ModeBadge, Sheet, signed, tone, useAccess,
  useAccounts } from "./common";

const newKey = () => (typeof crypto !== "undefined" && "randomUUID" in crypto ? crypto.randomUUID()
  : `${Date.now()}-${Math.random()}`);

/**
 * Mobile phase 1 Positions (17 Nov 2026): every open position of the accounts the caller sees, one card each, with its
 * mode, lots, entry → bid, P&L and stop; Exit (all, half or chosen lots) through a sheet that previews the sale. The
 * exit endpoint, its preview and its one-key-per-sheet repeat guard are the desktop's (positions phase 2, 17 Nov).
 */
export function PositionsScreen() {
  const accounts = useAccounts();
  const shown = activeAccounts(accounts.data);
  return (
    <div className="mx-auto flex max-w-xl flex-col gap-3">
      <div className="flex items-baseline justify-between">
        <h1 className="text-xl font-semibold">Positions</h1>
        <Freshness at={accounts.dataUpdatedAt} />
      </div>
      <ErrorLine error={accounts.error} />
      {accounts.data && shown.length === 0 && <p className="text-sm text-muted">No active accounts.</p>}
      {/* only accounts that traded or hold something today ask for their book (every 5 s); the rest are one line */}
      {shown.filter(busy).map((a) => <AccountBook key={a.id} account={a} />)}
      {shown.some((a) => !busy(a)) && (
        <Card><p className="text-sm text-muted">No trades today: {shown.filter((a) => !busy(a)).map((a) => a.name).join(", ")}</p></Card>
      )}
    </div>
  );
}

const busy = (a: ExecutionAccount) => a.today == null || a.today.open > 0 || a.today.trades > 0;

function AccountBook({ account }: { account: ExecutionAccount }) {
  const access = useAccess();
  const book = useQuery({ queryKey: ["live-book", account.id],
    queryFn: () => get<LiveBook>(`/api/execution/accounts/${account.id}/live-book?days=1`), refetchInterval: 5_000 });
  const [flatten, setFlatten] = useState(false);
  const [showClosed, setShowClosed] = useState(false);
  const b = book.data;
  const canAct = (b?.canAct ?? false) || (b?.canStop ?? false);
  const mode = b?.bookMode ?? account.mode;
  return (
    <Card>
      <div className="flex items-center justify-between gap-2">
        <h2 className="flex min-w-0 items-center gap-2 text-base font-semibold"><span className="truncate">{account.name}</span>
          <ModeBadge mode={mode} /></h2>
        {b && <span className={`num text-sm font-semibold ${tone(b.summary.openPnl)}`}>{b.open.length > 0 ? `${signed(b.summary.openPnl)} open` : ""}</span>}
      </div>
      {b?.killSwitches.map((k) => <p key={k.id} className="mt-1 text-xs font-semibold text-warn">Stopped: {k.text}</p>)}
      <ErrorLine error={book.error} />
      {b && (
        <>
          <div className="mt-1 text-xs text-muted">booked today {signed(b.summary.realisedToday)}
            {b.squareOff ? ` · square-off ${b.squareOff}` : ""}</div>
          <ul className="mt-2 flex flex-col gap-2">
            {b.open.map((p) => <OpenCard key={p.id} accountId={account.id} p={p} mode={mode} canAct={b.canAct || !!b.canStop} />)}
          </ul>
          {b.open.length === 0 && <p className="mt-2 text-sm text-muted">Nothing open.</p>}
          {b.closed.length > 0 && (
            <button type="button" className="mt-2 min-h-11 text-sm font-semibold text-sky-700" onClick={() => setShowClosed(!showClosed)}>
              {showClosed ? "Hide" : "Show"} {b.closed.length} closed today</button>
          )}
          {showClosed && (
            <ul className="flex flex-col divide-y divide-line text-sm">
              {b.closed.map((c) => (
                <li key={c.id} className="flex items-center justify-between gap-2 py-2">
                  <span className="min-w-0"><span className="block truncate">{c.symbol}</span>
                    <span className="text-xs text-muted">{c.strategy} · {istTime(c.opened)}–{istTime(c.closed)} · {c.exitReason ?? ""}</span></span>
                  <span className={`num shrink-0 ${tone(c.net)}`}>{signed(c.net)}</span>
                </li>
              ))}
            </ul>
          )}
          {canAct && access.canStop(account.tenantId) && b.open.length > 0 && (
            <button type="button" className="mt-3 min-h-11 w-full rounded-lg border border-down text-sm font-semibold text-down"
              onClick={() => setFlatten(true)}>Flatten {account.name}…</button>
          )}
        </>
      )}
      {flatten && <FlattenSheet account={account} mode={mode} open={b?.open.length ?? 0} onClose={() => setFlatten(false)} />}
    </Card>
  );
}

function OpenCard({ accountId, p, mode, canAct }: { accountId: number; p: LiveOpenPosition; mode: string; canAct: boolean }) {
  const [exiting, setExiting] = useState(false);
  const lots = p.lotSize ? Math.round(p.quantity / p.lotSize) : null;
  const pnl = p.pnl ?? (p.bid != null ? (p.bid - p.entry) * p.quantity : null);
  return (
    <li className="rounded-lg border border-line p-3">
      <div className="flex items-start justify-between gap-2">
        <span className="min-w-0 font-semibold">{p.symbol}</span>
        <span className={`num shrink-0 font-semibold ${tone(pnl)}`}>{signed(pnl)}</span>
      </div>
      <div className="mt-0.5 text-xs text-muted">{p.strategy} · {lots != null ? `${lots} lot${lots === 1 ? "" : "s"}` : `${p.quantity} qty`}
        {" "}· since {istTime(p.opened)}</div>
      <div className="num mt-2 flex justify-between rounded bg-panel-2 px-2 py-1 text-xs">
        <span>entry {p.entry.toFixed(2)}</span>
        <span className="font-semibold">{p.bid != null ? `bid ${p.bid.toFixed(2)}` : "no quote"}</span>
        <span className={p.stop != null ? "text-down" : "text-muted"}>{p.stop != null ? `stop ${p.stop.toFixed(2)}` : "no stop"}</span>
      </div>
      {p.strategyExitedAt && <p className="mt-1 text-xs font-semibold text-warn">The strategy exited at {istTime(p.strategyExitedAt)}: only you or the square-off close this.</p>}
      {p.notAtBroker && <p className="mt-1 text-xs font-semibold text-warn">The broker no longer holds this: book the hand exit on the desktop Accounts page.</p>}
      {canAct && !p.notAtBroker && (
        <div className="mt-2 flex justify-end"><Big kind="danger" onClick={() => setExiting(true)}>Exit…</Big></div>
      )}
      {exiting && <ExitSheet accountId={accountId} p={p} mode={mode} onClose={() => setExiting(false)} />}
    </li>
  );
}

/**
 * Sell all, half or a chosen number of whole lots. The preview (refreshed every 3 s) shows the price, estimate and the
 * server's warnings; a big or risky sale, or one without a preview, needs EXIT typed. One key per opened sheet: a double
 * tap or a retry after a timeout returns the first result and sells nothing more.
 */
function ExitSheet({ accountId, p, mode, onClose }: { accountId: number; p: LiveOpenPosition; mode: string;
  onClose: () => void }) {
  const client = useQueryClient();
  const allLots = p.lotSize ? Math.max(1, Math.round(p.quantity / p.lotSize)) : 1;
  const [lots, setLots] = useState(allLots);
  const [typed, setTyped] = useState("");
  const [key, setKey] = useState(newKey);
  const [done, setDone] = useState<string | null>(null);
  // review 17 Nov: after a failure whose outcome is unknown (5xx, timeout: the broker may have sold) the sheet keeps its
  // key and its lots, so the only next step is the same request again, which the server answers with the first result
  const [unknown, setUnknown] = useState(false);
  // a second tap before React re-renders the button as disabled still fires: block it here (the server's key would
  // answer it with the first result, but nothing should be sent twice; review 17 Nov)
  const sending = useRef(false);
  const all = !p.lotSize || lots >= allLots;
  const qty: number | undefined = all ? undefined : lots * p.lotSize!;   // All sends no quantity (everything open)
  const base = `/api/execution/accounts/${accountId}/positions/${p.id}`;
  const preview = useQuery({ queryKey: ["exit-preview", accountId, p.id, qty ?? "all"], enabled: done == null,
    queryFn: () => get<ExitPreview>(`${base}/exit-preview${qty != null ? `?quantity=${qty}` : ""}`), refetchInterval: 3_000 });
  const exit = useMutation({
    mutationFn: () => postJson<{ result: string[]; repeat?: boolean }>(`${base}/exit`, { confirm: "EXIT", quantity: qty ?? null, key }),
    onSuccess: (r) => setDone((r.repeat ? "Already sent: " : "") + r.result.join(" · ")),
    // a refusal (4xx) sold nothing: the next tap is a new attempt; anything else may have sold, so it keeps its key
    onError: (e) => { if (/^4\d\d:/.test((e as Error).message)) setKey(newKey()); else setUnknown(true); },
    onSettled: () => { sending.current = false; client.invalidateQueries({ queryKey: ["live-book", accountId] }); },
  });
  const send = () => { if (sending.current) return; sending.current = true; exit.mutate(); };
  const pv = preview.data;
  const mustType = preview.error != null || (pv != null && (pv.typeToConfirm || pv.warnings.length > 0));
  // review 17 Nov: not before the preview has answered (or failed), or a risky sale would skip its typed EXIT
  const previewed = pv != null || preview.error != null;
  const ready = !exit.isPending && (previewed || unknown) && (!mustType || typed.trim().toUpperCase() === "EXIT");
  const choose = (n: number) => {
    if (unknown) return;                                           // the first sale may have gone through
    setLots(Math.max(1, Math.min(allLots, n))); setTyped(""); exit.reset(); setKey(newKey());
  };
  return (
    <Sheet title={`Exit ${p.symbol}`} onClose={onClose}>
      {done ? (
        <div className="flex flex-col gap-3"><p className="text-sm">{done}</p><Big onClick={onClose}>Done</Big></div>
      ) : (
        <div className="flex flex-col gap-3">
          <p className="text-sm"><ModeBadge mode={pv?.mode ?? mode} />{" "}
            {(pv?.mode ?? mode) === "LIVE" ? "Real money: sells at the broker now." : "Paper: sold in the simulation."}</p>
          {p.lotSize && allLots > 1 && (
            <div className="flex flex-col gap-2">
              <div className="grid grid-cols-3 gap-2">
                <Big kind={all ? "primary" : "plain"} onClick={() => choose(allLots)}>All {allLots}</Big>
                <Big kind={!all && lots === Math.max(1, Math.floor(allLots / 2)) ? "primary" : "plain"}
                  onClick={() => choose(Math.floor(allLots / 2))}>Half</Big>
                <div className="flex items-center justify-between rounded-lg border border-line">
                  <button type="button" aria-label="One lot fewer" className="min-h-11 w-10 text-lg" onClick={() => choose(lots - 1)}>−</button>
                  <span className="num text-sm font-semibold">{lots}</span>
                  <button type="button" aria-label="One lot more" className="min-h-11 w-10 text-lg" onClick={() => choose(lots + 1)}>+</button>
                </div>
              </div>
              <p className="text-xs text-muted">{lots} of {allLots} lots; {allLots - lots} stay open{allLots - lots > 0 ? " with their stop" : ""}.</p>
            </div>
          )}
          {pv && (
            <dl className="num grid grid-cols-2 gap-x-3 gap-y-1 rounded-lg bg-panel-2 p-3 text-sm">
              <dt className="text-muted">Selling</dt><dd className="text-right">{pv.quantity} qty ({pv.lots} lot{pv.lots === 1 ? "" : "s"})</dd>
              <dt className="text-muted">At about</dt><dd className="text-right">{pv.price.toFixed(2)} ({pv.priceFrom})</dd>
              <dt className="text-muted">Estimate</dt><dd className={`text-right font-semibold ${tone(pv.net ?? pv.gross)}`}>{signed(pv.net ?? pv.gross)}</dd>
              {pv.orders > 1 && <><dt className="text-muted">Orders</dt><dd className="text-right">{pv.orders} (freeze limit)</dd></>}
            </dl>
          )}
          {pv?.warnings.map((w) => <p key={w} className="text-xs font-semibold text-warn">{w}</p>)}
          {preview.error && <p className="text-xs text-down">No preview ({(preview.error as Error).message}). You can still exit: type EXIT.</p>}
          {mustType && (
            <input className="min-h-11 rounded-lg border border-line bg-bg px-3 text-base uppercase" placeholder="Type EXIT to confirm"
              autoCapitalize="characters" autoCorrect="off" value={typed} onChange={(e) => setTyped(e.target.value)} aria-label="Type EXIT to confirm" />
          )}
          {exit.error && <p className="text-sm text-down">{(exit.error as Error).message}</p>}
          {unknown && <p className="text-sm font-semibold text-warn">The broker may have sold already. Check Positions before
            anything else: Retry sends the same request, and the server answers it with the first one's result.</p>}
          {!previewed && !unknown && <p className="text-xs text-muted">Getting the price…</p>}
          <Big kind="danger" disabled={!ready} onClick={send}>
            {exit.isPending ? "Sending…" : unknown ? "Retry the same exit" : `Exit ${all ? "all" : lots} ${all && pv ? `(${pv.lots} lot${pv.lots === 1 ? "" : "s"})` : `lot${lots === 1 ? "" : "s"}`}`}</Big>
          <Big onClick={onClose}>Cancel</Big>
        </div>
      )}
    </Sheet>
  );
}

/** Flatten: the account's kill switch, its broker stops off, then every open position sold now (typed FLATTEN). */
function FlattenSheet({ account, mode, open, onClose }: { account: ExecutionAccount; mode: string; open: number;
  onClose: () => void }) {
  const client = useQueryClient();
  const [typed, setTyped] = useState("");
  const [done, setDone] = useState<string | null>(null);
  const sending = useRef(false);
  const flatten = useMutation({
    mutationFn: () => postJson<{ result: string[] }>(`/api/execution/accounts/${account.id}/flatten`,
      { confirm: "FLATTEN", reason: "flattened from the phone" }),
    onSuccess: (r) => setDone(Array.isArray(r.result) ? r.result.join(" · ") : "Flattened."),
    onSettled: () => { sending.current = false; client.invalidateQueries({ queryKey: ["live-book", account.id] });
      client.invalidateQueries({ queryKey: ["exec-switches"] }); },
  });
  return (
    <Sheet title={`Flatten ${account.name}`} onClose={onClose}>
      {done ? (
        <div className="flex flex-col gap-3"><p className="text-sm">{done}</p><Big onClick={onClose}>Done</Big></div>
      ) : (
        <div className="flex flex-col gap-3">
          <p className="text-sm"><ModeBadge mode={mode} /> Stops new entries on {account.name}, takes the broker stops off and
            sells all {open} open position{open === 1 ? "" : "s"} now. The stop stays until you release it.</p>
          <input className="min-h-11 rounded-lg border border-line bg-bg px-3 text-base uppercase" placeholder="Type FLATTEN"
            autoCapitalize="characters" autoCorrect="off" value={typed} onChange={(e) => setTyped(e.target.value)} aria-label="Type FLATTEN to confirm" />
          {flatten.error && <p className="text-sm text-down">{(flatten.error as Error).message}</p>}
          <Big kind="danger" disabled={typed.trim().toUpperCase() !== "FLATTEN" || flatten.isPending || flatten.isSuccess}
            onClick={() => { if (!sending.current) { sending.current = true; flatten.mutate(); } }}>{flatten.isPending ? "Flattening…" : "Flatten now"}</Big>
          <Big onClick={onClose}>Cancel</Big>
        </div>
      )}
    </Sheet>
  );
}
