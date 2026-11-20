import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { type ReactNode, useEffect, useMemo, useRef, useState } from "react";
import { get, postJson, type BatchResult, type DeskOpenPositions, type DeskPosition, type EngineSessionPositions,
  type ExecMe } from "../api";
import { Big, Card, ErrorLine, Freshness, istTime, ModeBadge, Sheet, signed, tone } from "../mobile/common";
import { ExitSheet } from "../mobile/PositionsScreen";

/**
 * The platform admin's exit desk (user 20 Nov 2026: "As a admin user, I should be able to manually exit open positions
 * PAPER/LIVE from all trader/demo accounts"; plan 2026-11-20, phases A, B, D): every open position of every workspace and
 * account in one place, LIVE first, then PAPER, then the engine's own (read-only); a quote older than 10 s and a broker that
 * is not logged in in red; refreshed every 3 s. Per position Exit (the account page's exit sheet, with a reason), per
 * account "Exit all in this account" (no kill switch) and Flatten (kill switch), and Exit selected / Exit everything (one
 * typed confirmation with the count, LIVE first, paced per broker app, a result per position). Works in the phone shell.
 */

const newKey = () => (typeof crypto !== "undefined" && "randomUUID" in crypto ? crypto.randomUUID()
  : `${Date.now()}-${Math.random()}`);
const rowKey = (p: { accountId: number; positionId: number }) => `${p.accountId}:${p.positionId}`;
const isGone = (e: unknown) => e instanceof Error && /^404[ :]/.test(e.message);   // get: "404 why for path"

export function AdminPositionsPage() {
  const me = useQuery({ queryKey: ["exec-me"], queryFn: () => get<ExecMe>("/api/execution/me"), staleTime: 30_000,
    retry: false });
  const desk = useQuery({ queryKey: ["admin-open-positions"], enabled: me.data?.admin === true, refetchInterval: 3_000,
    queryFn: () => get<DeskOpenPositions>("/api/execution/admin/open-positions") });
  const engine = useQuery({ queryKey: ["engine-open-positions"], enabled: me.data?.admin === true, refetchInterval: 3_000,
    retry: false, queryFn: () => get<EngineSessionPositions[]>("/api/sessions/open-positions") });
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [batch, setBatch] = useState<{ rows: DeskPosition[]; title: string; kill?: boolean } | null>(null);
  const [exiting, setExiting] = useState<DeskPosition | null>(null);
  const [flatten, setFlatten] = useState<{ accountId: number; account: string; mode: string; open: number } | null>(null);

  if (me.data && !me.data.admin) {
    return <p className="text-sm text-muted">The exit desk is the platform admin's.</p>;
  }
  const rows = desk.data?.positions ?? [];
  const live = rows.filter((r) => r.group === "LIVE");
  const paper = rows.filter((r) => r.group === "PAPER");
  // a stale screen never exits what is gone: the selection keeps only rows still open
  const chosen = rows.filter((r) => selected.has(rowKey(r)));
  const toggle = (r: DeskPosition) => setSelected((s) => {
    const next = new Set(s);
    if (next.has(rowKey(r))) next.delete(rowKey(r)); else next.add(rowKey(r));
    return next;
  });
  return (
    <div className="mx-auto flex max-w-6xl flex-col gap-3">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h1 className="text-xl font-semibold">Open positions</h1>
        <Freshness at={desk.dataUpdatedAt} />
      </div>
      <ErrorLine error={me.error ?? desk.error} />
      {desk.data && (
        <div className="flex flex-wrap gap-2">
          <Big kind="danger" disabled={chosen.length === 0}
            onClick={() => setBatch({ rows: chosen, title: `Exit ${chosen.length} selected` })}>Exit selected ({chosen.length})</Big>
          <Big kind="danger" disabled={rows.length === 0}
            onClick={() => setBatch({ rows, title: `Exit everything (${rows.length})` })}>Exit everything ({rows.length})</Big>
          {selected.size > 0 && <Big onClick={() => setSelected(new Set())}>Clear selection</Big>}
        </div>
      )}
      <Group title="LIVE" rows={live} selected={selected} toggle={toggle} onExit={setExiting}
        onExitAccount={(accountRows) => setBatch({ rows: accountRows, title: `Exit all in ${accountRows[0].account}`, kill: false })}
        onFlatten={(r, n) => setFlatten({ accountId: r.accountId, account: r.account, mode: r.mode, open: n })}
        staleSec={desk.data?.staleQuoteSec ?? 10} loading={desk.isPending} />
      <Group title="PAPER" rows={paper} selected={selected} toggle={toggle} onExit={setExiting}
        onExitAccount={(accountRows) => setBatch({ rows: accountRows, title: `Exit all in ${accountRows[0].account}`, kill: false })}
        onFlatten={(r, n) => setFlatten({ accountId: r.accountId, account: r.account, mode: r.mode, open: n })}
        staleSec={desk.data?.staleQuoteSec ?? 10} loading={desk.isPending} />
      <EngineGroup sessions={engine.data} error={engine.error} followers={rows} />
      {desk.data && desk.data.mirrors.length > 0 && (
        <Card>
          <h2 className="mb-1 text-sm font-semibold">Mirror accounts</h2>
          <ul className="text-sm">
            {desk.data.mirrors.map((m) => (
              <li key={m.account_id}>{m.account} <span className="text-muted">({m.tenant})</span>: mirror of{" "}
                <a className="font-semibold text-sky-700 underline" href={`#engine-${m.mirror_of}`}>{m.mirror_of}</a>
                <span className="text-muted"> · holds nothing of its own: exit at the source</span></li>
            ))}
          </ul>
        </Card>
      )}
      {exiting && (
        <ExitSheet accountId={exiting.accountId} mode={exiting.mode} reasonRequired
          title={`Exit ${exiting.symbol} · ${exiting.account} (${exiting.tenant})`}
          p={{ id: exiting.positionId, symbol: exiting.symbol, quantity: exiting.quantity, lotSize: exiting.lotSize }}
          onClose={() => setExiting(null)} onDone={() => desk.refetch()} />
      )}
      {batch && <BatchSheet rows={batch.rows} title={batch.title} kill={batch.kill} onClose={() => setBatch(null)}
        onDone={() => { setSelected(new Set()); desk.refetch(); }} />}
      {flatten && <FlattenSheet {...flatten} onClose={() => setFlatten(null)} onDone={() => desk.refetch()} />}
    </div>
  );
}

function Group({ title, rows, selected, toggle, onExit, onExitAccount, onFlatten, staleSec, loading }: {
  title: "LIVE" | "PAPER"; rows: DeskPosition[]; selected: Set<string>; toggle: (r: DeskPosition) => void;
  onExit: (r: DeskPosition) => void; onExitAccount: (rows: DeskPosition[]) => void;
  onFlatten: (r: DeskPosition, open: number) => void; staleSec: number; loading: boolean;
}) {
  const byAccount = useMemo(() => {
    const m = new Map<number, DeskPosition[]>();
    rows.forEach((r) => m.set(r.accountId, [...(m.get(r.accountId) ?? []), r]));
    return [...m.values()];
  }, [rows]);
  const pnl = rows.reduce((a, r) => a + (r.pnl ?? 0), 0);
  const risk = rows.reduce((a, r) => a + r.premiumAtRisk, 0);
  return (
    <Card className={title === "LIVE" ? "border-down/40" : ""}>
      <div className="mb-2 flex flex-wrap items-baseline justify-between gap-2">
        <h2 className="flex items-center gap-2 text-base font-semibold"><ModeBadge mode={title} /> {title === "LIVE" ? "Real money"
          : "Paper"} · {rows.length} open</h2>
        <span className="num text-sm">P&amp;L <span className={`font-semibold ${tone(pnl)}`}>{signed(pnl)}</span>
          <span className="text-muted"> · premium at risk {signed(risk).replace("+", "")}</span></span>
      </div>
      {loading ? <p className="text-sm text-muted">Loading…</p> : rows.length === 0 ? <p className="text-sm text-muted">Nothing open.</p> : (
        <div className="flex flex-col gap-3">
          {byAccount.map((acct) => {
            const a = acct[0];
            const down = a.loggedIn === false;
            return (
              <div key={a.accountId} className="rounded-lg border border-line">
                <div className="flex flex-wrap items-center justify-between gap-2 border-b border-line px-3 py-2">
                  <div className="min-w-0 text-sm">
                    <b>{a.account}</b> <span className="text-muted">· {a.tenant} · {a.broker}{a.brokerApp ? ` (${a.brokerApp})` : ""}</span>
                    {a.loggedIn != null && <span className={`ml-2 text-xs font-semibold ${down ? "text-down" : "text-up"}`}>
                      {down ? `not logged in${a.connection ? ` (${a.connection})` : ""}` : "logged in"}</span>}
                    <span className={`ml-2 text-xs ${a.consent.given ? "text-up" : "text-muted"}`}>
                      {a.consent.given ? `consent given ${istTime(a.consent.at)} by ${a.consent.by}` : "no standing consent"}</span>
                  </div>
                  <div className="flex flex-wrap gap-2">
                    <button type="button" className="min-h-9 rounded-lg border border-down px-3 text-xs font-semibold text-down"
                      onClick={() => onExitAccount(acct)}>Exit all in this account</button>
                    <button type="button" className="min-h-9 rounded-lg border border-down bg-down/10 px-3 text-xs font-semibold text-down"
                      onClick={() => onFlatten(a, acct.length)}>Flatten…</button>
                  </div>
                </div>
                <ul className="divide-y divide-line">
                  {acct.map((r) => <Row key={rowKey(r)} r={r} checked={selected.has(rowKey(r))} toggle={() => toggle(r)}
                    onExit={() => onExit(r)} staleSec={staleSec} />)}
                </ul>
              </div>
            );
          })}
        </div>
      )}
    </Card>
  );
}

function Row({ r, checked, toggle, onExit, staleSec }: { r: DeskPosition; checked: boolean; toggle: () => void;
  onExit: () => void; staleSec: number }) {
  const stale = r.quoteStale;
  return (
    <li className="grid grid-cols-[auto_1fr_auto] items-center gap-x-3 gap-y-1 px-3 py-2 text-sm md:grid-cols-[auto_2fr_1.2fr_1fr_1fr_auto]">
      <input type="checkbox" checked={checked} onChange={toggle} aria-label={`select ${r.symbol}`} className="h-5 w-5" />
      <div className="min-w-0">
        <div className="truncate font-semibold">{r.symbol}{r.status && r.status !== "OPEN"
          ? <span className="text-down"> · {r.status.toLowerCase().replace(/_/g, " ")}</span> : null}</div>
        <div className="text-xs text-muted">{r.strategy}{r.source ? ` · from ${r.source}` : ""} · {r.lots != null ? `${r.lots} lot${r.lots === 1 ? "" : "s"} · ` : ""}{r.quantity} qty
          {r.mode !== r.group ? ` · ${r.mode}` : ""} · since {istTime(r.opened)}</div>
      </div>
      <div className="num col-start-2 text-xs md:col-start-auto md:text-sm">
        {r.entry.toFixed(2)} → {r.bid != null ? r.bid.toFixed(2) : "–"}{r.ask != null ? <span className="text-muted"> / {r.ask.toFixed(2)}</span> : null}
        <div className={`text-xs ${stale ? "font-semibold text-down" : "text-muted"}`}>
          {r.quoteAgeSec == null ? "no quote in 10 min" : `quote ${r.quoteAgeSec} s old${stale ? ` (> ${staleSec} s)` : ""}`}</div>
      </div>
      <div className="num col-start-2 text-xs md:col-start-auto md:text-sm">
        {r.stop != null ? <span className="text-down">stop {r.stop.toFixed(2)}</span> : <span className="text-muted">no stop</span>}
        {r.stopStatus ? <span className="text-muted"> ({r.stopStatus.toLowerCase()})</span> : null}
      </div>
      <div className={`num col-start-2 font-semibold md:col-start-auto ${tone(r.pnl)}`}>{signed(r.pnl)}</div>
      <button type="button" className="row-span-2 min-h-9 rounded-lg border border-down px-3 text-xs font-semibold text-down md:row-span-1"
        onClick={onExit}>Exit…</button>
    </li>
  );
}

/** The engine's own positions (PAPER-1, SIGNAL-1), read-only: no per-position engine exit yet (plan phase C). */
function EngineGroup({ sessions, error, followers }: { sessions: EngineSessionPositions[] | undefined; error: unknown;
  followers: DeskPosition[] }) {
  return (
    <Card>
      <h2 className="mb-1 text-base font-semibold">Engine (read-only)</h2>
      <p className="mb-2 text-xs text-muted">The live sessions' own positions. They exit on their strategy's signal or the session's
        Exit all; an engine position cannot be exited from here yet. Followers that copy one are listed with it.</p>
      {error ? <p className="text-sm text-muted">Engine positions not readable here ({(error as Error).message}).</p>
        : !sessions ? <p className="text-sm text-muted">Loading…</p>
        : sessions.length === 0 ? <p className="text-sm text-muted">No live session is running.</p>
        : sessions.map((s) => {
          const open = s.positions.filter((p) => p.quantity > 0);
          const pnl = open.reduce((a, p) => a + (p.unrealised ?? 0), 0);
          return (
            <div key={s.account} id={`engine-${s.account}`} className="mb-2 rounded-lg border border-line">
              <div className="flex flex-wrap items-baseline justify-between gap-2 border-b border-line px-3 py-2 text-sm">
                <span><b>{s.account}</b> <span className="text-muted">· session {s.session} · {s.status}{s.lastEvent ? ` · last ${s.lastEvent}` : ""}</span></span>
                <span className="num">{open.length} open · <span className={`font-semibold ${tone(pnl)}`}>{signed(pnl)}</span></span>
              </div>
              {open.length === 0 ? <p className="px-3 py-2 text-sm text-muted">Nothing open.</p> : (
                <ul className="divide-y divide-line">
                  {open.map((p, i) => {
                    const copies = followers.filter((f) => f.source === s.account && f.strategy === p.strategy && f.symbol === p.symbol);
                    return (
                      <li key={`${p.symbol}-${i}`} className="px-3 py-2 text-sm">
                        <div className="flex flex-wrap justify-between gap-2">
                          <span className="font-semibold">{p.symbol}</span>
                          <span className={`num font-semibold ${tone(p.unrealised)}`}>{signed(p.unrealised)}</span>
                        </div>
                        <div className="num text-xs text-muted">{p.strategy} · {p.quantity} qty · {p.averageCost.toFixed(2)} → {p.bid != null ? p.bid.toFixed(2) : "–"}
                          {p.opened ? ` · since ${p.opened.slice(0, 5)}` : ""}</div>
                        {copies.length > 0 && <div className="text-xs">copied by {copies.map((c) =>
                          `${c.account}, ${c.lots ?? c.quantity} ${c.lots != null ? (c.lots === 1 ? "lot" : "lots") : "qty"} ${c.mode}`).join("; ")}</div>}
                      </li>
                    );
                  })}
                </ul>
              )}
            </div>
          );
        })}
    </Card>
  );
}

/**
 * Exit selected / everything / all in one account: one reason, the kill switch after (on by default when a LIVE position
 * is in it, decision 2), the count typed ("EXIT 3 POSITIONS"), one key per opened sheet (a double tap or retry answers the
 * first result and sells nothing more). The server runs LIVE first (largest premium at risk first), paced per broker app.
 */
function BatchSheet({ rows, title, kill, onClose, onDone }: { rows: DeskPosition[]; title: string; kill?: boolean;
  onClose: () => void; onDone: () => void }) {
  const client = useQueryClient();
  const anyLive = rows.some((r) => r.mode === "LIVE");
  const [killAfter, setKillAfter] = useState(kill ?? anyLive);
  const [reason, setReason] = useState("");
  const [typed, setTyped] = useState("");
  const [key] = useState(newKey);
  const [snapshot] = useState(rows);                          // what was on screen when the sheet opened
  const sending = useRef(false);
  const phrase = `EXIT ${snapshot.length} POSITIONS`;
  // review 20 Nov 2026: the batch runs in the background (a LIVE batch can outlast the proxy's 150 s); POST answers at
  // once with its id and this sheet follows it every 1.5 s, showing each row as it answers
  const run = useMutation({
    mutationFn: () => postJson<BatchResult>("/api/execution/admin/exit-batch", {
      positions: snapshot.map((r) => ({ accountId: r.accountId, positionId: r.positionId })), reason: reason.trim(), killAfter,
      key, confirm: phrase }),
    onSettled: () => { sending.current = false; },
  });
  const batchId = run.data?.batchId;
  const progress = useQuery({ queryKey: ["exit-batch", batchId], enabled: batchId != null,
    queryFn: () => get<BatchResult>(`/api/execution/admin/exit-batch/${batchId}`),
    // second review 20 Nov 2026: a 404 means this worker no longer knows the batch (it restarted): stop asking
    refetchInterval: (q) => (q.state.data?.status === "DONE" || isGone(q.state.error) ? false : 1_500), retry: false });
  const gone = isGone(progress.error);
  const res = progress.data ?? run.data;
  const finished = res?.status === "DONE";
  const doneOnce = useRef(false);
  useEffect(() => {
    if (finished && !doneOnce.current) {
      doneOnce.current = true;
      onDone();
      client.invalidateQueries({ queryKey: ["live-book"] });
    }
  }, [finished, onDone, client]);
  const unknown = run.error != null && !/^4\d\d:/.test((run.error as Error).message);
  const ready = !run.isPending && reason.trim() !== "" && typed.trim().toUpperCase() === phrase;
  const order = [...snapshot].sort((a, b) => (a.mode === "LIVE" ? 0 : 1) - (b.mode === "LIVE" ? 0 : 1) || b.premiumAtRisk - a.premiumAtRisk);
  const tint = (o: string) => o === "SOLD" ? "text-up" : o === "ALREADY_CLOSED" || o === "PENDING" || o === "RUNNING" ? "text-muted"
    : "text-down";
  return (
    <Sheet title={title} onClose={onClose}>
      {res ? (
        <div className="flex flex-col gap-3">
          <p className="text-sm">{res.repeat ? "Already sent: this is that batch. " : ""}{res.killAfter
            ? `Kill switch engaged first on ${res.killed.join(", ") || "none"}.` : "No kill switch: the accounts keep trading."}
            {Object.keys(res.killFailed ?? {}).length > 0 && <b className="text-down"> Kill switch NOT engaged on {Object.entries(res.killFailed)
              .map(([a, why]) => `${a} (${why})`).join(", ")}.</b>}</p>
          <p className="text-xs text-muted">{finished ? "Done" : gone ? `${res.answered} of ${res.count} answered before it was lost`
            : `Working: ${res.answered} of ${res.count} answered…`}</p>
          {gone ? <p className="text-sm font-semibold text-down">The worker no longer knows this batch (it restarted?): rows not
            answered above may still have sold. Read the open positions again, and do not sell by hand in the broker app before
            checking its order book.</p>
            : progress.error && <p className="text-xs text-warn">Progress not readable now ({(progress.error as Error).message}); retrying.</p>}
          <ul className="flex flex-col gap-1 text-sm">
            {res.results.map((r, i) => r && (
              <li key={`${r.accountId}:${r.positionId}:${i}`} className="rounded border border-line px-2 py-1">
                <b className={tint(r.outcome)}>{r.outcome.replace("_", " ")}</b>
                {" "}{r.account} · {r.symbol ?? `position ${r.positionId}`}
                {r.detail && <div className={`text-xs ${r.outcome === "UNKNOWN" ? "font-semibold text-down" : "text-muted"}`}>{r.detail}</div>}
                {r.lines.length > 0 && <div className="text-xs">{r.lines.join(" · ")}</div>}
                {r.mirrorOf && <a className="text-xs text-sky-700 underline" href={`#engine-${r.mirrorOf}`}>go to {r.mirrorOf}</a>}
              </li>
            ))}
          </ul>
          <Big onClick={onClose}>{finished ? "Done" : "Close (it keeps running)"}</Big>
        </div>
      ) : (
        <div className="flex flex-col gap-3">
          <p className="text-sm">In this order (LIVE first, the largest premium at risk first; LIVE orders paced per broker app):</p>
          <ol className="max-h-56 list-decimal overflow-y-auto pl-5 text-sm">
            {order.map((r) => <li key={rowKey(r)}><ModeBadge mode={r.mode} /> {r.account} · {r.symbol} · {r.lots ?? r.quantity}
              {r.lots != null ? " lots" : " qty"}{r.loggedIn === false ? <b className="text-down"> · broker not logged in</b> : null}</li>)}
          </ol>
          <input className="min-h-11 rounded-lg border border-line bg-bg px-3 text-base" placeholder="Reason (each member is told)"
            value={reason} maxLength={300} onChange={(e) => setReason(e.target.value)} aria-label="Reason" />
          <label className="flex items-center gap-2 text-sm">
            <input type="checkbox" checked={killAfter} onChange={(e) => setKillAfter(e.target.checked)} className="h-5 w-5" />
            Then stop new entries on these accounts (kill switch){anyLive ? " · on by default with LIVE positions" : ""}
          </label>
          <input className="min-h-11 rounded-lg border border-line bg-bg px-3 text-base uppercase" placeholder={`Type ${phrase}`}
            autoCapitalize="characters" autoCorrect="off" value={typed} onChange={(e) => setTyped(e.target.value)} aria-label={`Type ${phrase}`} />
          {run.error && <p className="text-sm text-down">{(run.error as Error).message}</p>}
          {unknown && <p className="text-sm font-semibold text-warn">Not known whether the batch started. Do not sell by hand in the
            broker app; sending again answers with the same batch if it did.</p>}
          <Big kind="danger" disabled={!ready && !unknown}
            onClick={() => { if (!sending.current) { sending.current = true; run.mutate(); } }}>
            {run.isPending ? "Exiting…" : unknown ? "Send the same request again" : `Exit ${snapshot.length} position${snapshot.length === 1 ? "" : "s"}`}</Big>
          <Big onClick={onClose}>Cancel</Big>
        </div>
      )}
    </Sheet>
  );
}

/** Flatten one account: its kill switch, broker stops off, every open position sold now (typed FLATTEN, a reason). */
function FlattenSheet({ accountId, account, mode, open, onClose, onDone }: { accountId: number; account: string; mode: string;
  open: number; onClose: () => void; onDone: () => void }) {
  const [typed, setTyped] = useState("");
  const [reason, setReason] = useState("");
  const sending = useRef(false);
  const flatten = useMutation({
    mutationFn: () => postJson<{ result: string[] }>(`/api/execution/accounts/${accountId}/flatten`,
      { confirm: "FLATTEN", reason: reason.trim() }),
    onSettled: () => { sending.current = false; onDone(); },
  });
  const done: ReactNode = flatten.data ? (Array.isArray(flatten.data.result) ? flatten.data.result.join(" · ") : "Flattened.") : null;
  return (
    <Sheet title={`Flatten ${account}`} onClose={onClose}>
      {done ? (
        <div className="flex flex-col gap-3"><p className="text-sm">{done}</p><Big onClick={onClose}>Done</Big></div>
      ) : (
        <div className="flex flex-col gap-3">
          <p className="text-sm"><ModeBadge mode={mode} /> Stops new entries on {account} (kill switch), takes its broker stops off and
            sells all {open} open position{open === 1 ? "" : "s"} now. The switch stays until it is released.</p>
          <input className="min-h-11 rounded-lg border border-line bg-bg px-3 text-base" placeholder="Reason (the member is told)"
            value={reason} maxLength={300} onChange={(e) => setReason(e.target.value)} aria-label="Reason" />
          <input className="min-h-11 rounded-lg border border-line bg-bg px-3 text-base uppercase" placeholder="Type FLATTEN"
            autoCapitalize="characters" autoCorrect="off" value={typed} onChange={(e) => setTyped(e.target.value)} aria-label="Type FLATTEN" />
          {flatten.error && <p className="text-sm text-down">{(flatten.error as Error).message}</p>}
          <Big kind="danger" disabled={typed.trim().toUpperCase() !== "FLATTEN" || reason.trim() === "" || flatten.isPending}
            onClick={() => { if (!sending.current) { sending.current = true; flatten.mutate(); } }}>
            {flatten.isPending ? "Flattening…" : "Flatten now"}</Big>
          <Big onClick={onClose}>Cancel</Big>
        </div>
      )}
    </Sheet>
  );
}
