import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { type ReactNode, useEffect, useMemo, useRef, useState } from "react";
import { get, postJson, type BatchResult, type DeskOpenPositions, type DeskPosition, type DeskScope,
  type EngineSessionPositions, type ExecMe, type PositionRow, type TargetBatchResult } from "../api";
import { PositionTarget, targetActive, targetPct, targetPrice } from "../components/PositionTarget";
import { Big, Card, ErrorLine, Freshness, istTime, ModeBadge, Sheet, signed, tone } from "../mobile/common";
import { ExitSheet, shareChoices } from "../mobile/PositionsScreen";

/**
 * The platform admin's exit desk (user 20 Nov 2026: "As a admin user, I should be able to manually exit open positions
 * PAPER/LIVE from all trader/demo accounts"; plan 2026-11-20, phases A, B, D): every open position of every workspace and
 * account in one place, LIVE first, then PAPER, then the engine's own (read-only); a quote older than 10 s and a broker that
 * is not logged in in red; refreshed every 3 s. Per position Exit (the account page's exit sheet, with a reason), per
 * account "Exit all in this account" (no kill switch) and Flatten (kill switch), and Exit selected / Exit everything (one
 * typed confirmation with the count, LIVE first, paced per broker app, a result per position). Works in the phone shell.
 *
 * 25 Nov 2026 (user: partial exits like the Overview's open positions, and a LIVE / PAPER choice): the book chosen (LIVE,
 * PAPER or both, remembered in this browser) decides what the page shows and what its exits act on; the server refuses a
 * batch with a position of the other book. Exit (per position) and Exit selected sell all of it or a part: a share (¾,
 * ½, ¼) or a number of lots, in whole lots, through the same exits. A LIVE exit is confirmed in words naming the account,
 * the quantity and LIVE.
 *
 * The first target (25 Nov 2026, user: "first safe target like we have for Open positions in overview for LIVE ... Members
 * too with reason, also admin for global ... all LIVE all workspaces"): each position shows and sets its first target as
 * the Overview does (a reason on a member's account: the members are told, it is marked as the admin's); "First target"
 * sets one % on every position of the book shown (or the selected ones), or removes it, in one confirmation.
 */

const SCOPE_KEY = "exit-desk-scope";
const SCOPES: { value: DeskScope; label: string }[] = [{ value: "LIVE", label: "LIVE" }, { value: "PAPER", label: "PAPER" },
  { value: "BOTH", label: "Both" }];
/** How a book reads on the buttons and in the confirm step. */
const scopeName = (s: DeskScope) => (s === "BOTH" ? "LIVE + PAPER" : s);
const inBook = (s: DeskScope, group: "LIVE" | "PAPER") => s === "BOTH" || s === group;
/**
 * The engine sessions an exit in this book may act on: both, every one; PAPER, only a session that says it is not a
 * signal source (PAPER-1: its exit touches paper only); LIVE, none (PAPER-1 is paper; a SIGNAL-1 exit reaches its paper
 * followers too). The server applies the same rule.
 */
const engineInBook = (s: DeskScope, e: EngineSessionPositions) => s === "BOTH" || (s === "PAPER" && e.signalSource === false);

function readScope(): DeskScope {
  try {
    const v = window.localStorage.getItem(SCOPE_KEY);
    if (v === "LIVE" || v === "PAPER" || v === "BOTH") return v;
  } catch { /* storage blocked: the default */ }
  return "PAPER";                                  // a first visit acts on paper money only until LIVE is chosen
}

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
  const [scope, setScopeState] = useState<DeskScope>(readScope);
  const setScope = (s: DeskScope) => {
    setScopeState(s);
    setSelected(new Set());                        // a selection never carries over into another book
    try { window.localStorage.setItem(SCOPE_KEY, s); } catch { /* the choice lasts for this page only */ }
  };
  const [batch, setBatch] = useState<{ rows: DeskPosition[]; title: string; kill?: boolean; everything?: boolean;
    partial?: boolean; scope: DeskScope } | null>(null);
  const [engineExit, setEngineExit] = useState<{ session: EngineSessionPositions; p: PositionRow } | null>(null);
  const [exiting, setExiting] = useState<DeskPosition | null>(null);
  const [flatten, setFlatten] = useState<{ accountId: number; account: string; mode: string; open: number } | null>(null);
  const [targeting, setTargeting] = useState<{ rows: DeskPosition[]; title: string; scope: DeskScope } | null>(null);

  if (me.data && !me.data.admin) {
    return <p className="text-sm text-muted">The exit desk is the platform admin's.</p>;
  }
  const allRows = desk.data?.positions ?? [];
  const live = allRows.filter((r) => r.group === "LIVE");
  const paper = allRows.filter((r) => r.group === "PAPER");
  // the book chosen: only its rows are shown, selected and exited (the server refuses any other)
  const rows = allRows.filter((r) => inBook(scope, r.group));
  const engineSessions = engine.data?.filter((e) => engineInBook(scope, e));
  const engineOpen = (engineSessions ?? []).reduce((n, s) => n + s.positions.filter((p) => p.quantity > 0).length, 0);
  // a stale screen never exits what is gone: the selection keeps only rows still open (and in the book chosen)
  const chosen = rows.filter((r) => selected.has(rowKey(r)));
  // an account holding positions of both books: its Flatten (every position of the account) would reach the other one
  const bothBooks = new Set(live.filter((l) => paper.some((p) => p.accountId === l.accountId)).map((l) => l.accountId));
  const hidden = scope === "LIVE" ? paper.length : scope === "PAPER" ? live.length : 0;
  const book = scopeName(scope);
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
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-sm font-semibold">Book</span>
        <div role="radiogroup" aria-label="Book the page and its exits act on" className="flex overflow-hidden rounded-lg border border-line">
          {SCOPES.map((o) => {
            const n = o.value === "LIVE" ? live.length : o.value === "PAPER" ? paper.length : allRows.length;
            const on = scope === o.value;
            return (
              <button key={o.value} type="button" role="radio" aria-checked={on} onClick={() => setScope(o.value)}
                className={`min-h-11 px-4 text-sm font-semibold ${on ? (o.value === "PAPER" ? "bg-warn/20 text-text"
                  : "bg-down text-white") : "bg-panel text-muted"} ${o.value !== "LIVE" ? "border-l border-line" : ""}`}>
                {o.label}{desk.data ? ` (${n})` : ""}</button>
            );
          })}
        </div>
        <span className="text-xs text-muted">{scope === "BOTH" ? "Exits act on real money and paper." : scope === "LIVE"
          ? "Exits act on real money only: the paper book and the engine are not touched."
          : "Exits act on paper only: nothing in the LIVE book is sold or stopped."}</span>
      </div>
      {desk.data && (
        <div className="flex flex-wrap gap-2">
          <Big kind="danger" disabled={chosen.length === 0}
            onClick={() => setBatch({ rows: chosen, title: `Exit ${chosen.length} selected · ${book}`, partial: true, scope })}>
            Exit selected · {book} ({chosen.length})</Big>
          <Big kind="danger" disabled={rows.length === 0 && engineOpen === 0}
            onClick={() => setBatch({ rows, title: `EXIT EVERYTHING · ${book}`, everything: true, scope })}>
            Exit everything · {book} ({rows.length + engineOpen})</Big>
          <Big kind="primary" disabled={rows.length === 0}
            onClick={() => { const r = chosen.length > 0 ? chosen : rows;
              setTargeting({ rows: r, scope, title: `First target · ${chosen.length > 0 ? `${r.length} selected` : `all ${book}`}` }); }}>
            First target · {chosen.length > 0 ? `selected (${chosen.length})` : `all ${book} (${rows.length})`}</Big>
          {chosen.length > 0 && <Big onClick={() => setSelected(new Set())}>Clear selection</Big>}
        </div>
      )}
      {hidden > 0 && <p className="text-sm font-semibold text-warn">{scope === "PAPER" ? "LIVE" : "PAPER"} book: {hidden} open
        position{hidden === 1 ? "" : "s"} not shown (choose {scope === "PAPER" ? "LIVE" : "PAPER"} or Both to see and exit them).</p>}
      {inBook(scope, "LIVE") && <Group title="LIVE" onTargetChanged={() => desk.refetch()} rows={live} selected={selected} toggle={toggle} onExit={setExiting}
        onExitAccount={(accountRows) => setBatch({ rows: accountRows, title: `Exit all in ${accountRows[0].account} · LIVE`,
          kill: false, scope: "LIVE" })}
        onFlatten={(r, n) => setFlatten({ accountId: r.accountId, account: r.account, mode: r.mode, open: n })}
        bothBooks={bothBooks} staleSec={desk.data?.staleQuoteSec ?? 10} loading={desk.isPending} />}
      {inBook(scope, "PAPER") && <Group title="PAPER" onTargetChanged={() => desk.refetch()} rows={paper} selected={selected} toggle={toggle} onExit={setExiting}
        onExitAccount={(accountRows) => setBatch({ rows: accountRows, title: `Exit all in ${accountRows[0].account} · PAPER`,
          kill: false, scope: "PAPER" })}
        onFlatten={(r, n) => setFlatten({ accountId: r.accountId, account: r.account, mode: r.mode, open: n })}
        bothBooks={bothBooks} staleSec={desk.data?.staleQuoteSec ?? 10} loading={desk.isPending} />}
      <EngineGroup sessions={engineSessions} all={engine.data} scope={scope} error={engine.error} followers={allRows}
        onExit={(session, p) => setEngineExit({ session, p })} onResumed={() => engine.refetch()} />
      {scope !== "LIVE" && desk.data && desk.data.mirrors.length > 0 && (
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
        <ExitSheet accountId={exiting.accountId} mode={exiting.mode} reasonRequired account={`${exiting.account} (${exiting.tenant})`}
          title={`Exit ${exiting.symbol} · ${exiting.account} (${exiting.tenant}) · ${exiting.group}`}
          p={{ id: exiting.positionId, symbol: exiting.symbol, quantity: exiting.quantity, lotSize: exiting.lotSize }}
          onClose={() => setExiting(null)} onDone={() => desk.refetch()} />
      )}
      {batch && <BatchSheet rows={batch.rows} title={batch.title} kill={batch.kill} onClose={() => setBatch(null)}
        scope={batch.scope} partial={batch.partial}
        everything={batch.everything ? { engine: engineSessions ?? [] } : undefined}
        onDone={() => { setSelected(new Set()); desk.refetch(); engine.refetch(); }} />}
      {engineExit && <EngineExitSheet session={engineExit.session} p={engineExit.p} followers={allRows} scope={scope}
        onClose={() => setEngineExit(null)} onDone={() => { engine.refetch(); desk.refetch(); }} />}
      {flatten && <FlattenSheet {...flatten} onClose={() => setFlatten(null)} onDone={() => desk.refetch()} />}
      {targeting && <TargetSheet {...targeting} onClose={() => setTargeting(null)} onDone={() => desk.refetch()} />}
    </div>
  );
}

function Group({ title, rows, selected, toggle, onExit, onExitAccount, onFlatten, bothBooks, staleSec, loading, onTargetChanged }: {
  title: "LIVE" | "PAPER"; rows: DeskPosition[]; selected: Set<string>; toggle: (r: DeskPosition) => void;
  onExit: (r: DeskPosition) => void; onExitAccount: (rows: DeskPosition[]) => void;
  onFlatten: (r: DeskPosition, open: number) => void;
  /** accounts holding positions of both books: Flatten sells every one of them, so it is not offered from one book */
  bothBooks: Set<number>; staleSec: number; loading: boolean; onTargetChanged: () => void;
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
                    <button type="button" className="min-h-9 rounded-lg border border-down bg-down/10 px-3 text-xs font-semibold text-down disabled:opacity-40"
                      disabled={bothBooks.has(a.accountId)}
                      title={bothBooks.has(a.accountId) ? "This account also holds positions of the other book: Flatten would sell those too" : undefined}
                      onClick={() => onFlatten(a, acct.length)}>Flatten…</button>
                  </div>
                </div>
                <ul className="divide-y divide-line">
                  {acct.map((r) => <Row key={rowKey(r)} r={r} checked={selected.has(rowKey(r))} toggle={() => toggle(r)}
                    onExit={() => onExit(r)} staleSec={staleSec} onTargetChanged={onTargetChanged} />)}
                </ul>
              </div>
            );
          })}
        </div>
      )}
    </Card>
  );
}

function Row({ r, checked, toggle, onExit, staleSec, onTargetChanged }: { r: DeskPosition; checked: boolean;
  toggle: () => void; onExit: () => void; staleSec: number; onTargetChanged: () => void }) {
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
      {/* the first target (25 Nov 2026), as on the Overview; a member's account takes a reason */}
      <div className="col-span-full">
        <PositionTarget accountId={r.accountId} live={r.mode === "LIVE"} reasonRequired={r.targetNeedsReason}
          account={`${r.account} (${r.tenant})`} onChanged={onTargetChanged}
          p={{ id: r.positionId, entry: r.entry, bid: r.bid, quantity: r.quantity, lotSize: r.lotSize,
            manualTarget: r.manualTarget ?? undefined }} />
      </div>
    </li>
  );
}

/**
 * The engine's own positions (PAPER-1, SIGNAL-1), each with Exit (plan 2026-11-23 A1): through the session's order manager
 * as the strategy's own exit; a SIGNAL-1 exit is sent to every follower holding the trade (listed with it).
 */
function EngineGroup({ sessions, all, scope, error, followers, onExit, onResumed }: {
  /** the sessions in the book chosen ({@link engineInBook}); {@code all}: every running one */
  sessions: EngineSessionPositions[] | undefined; all: EngineSessionPositions[] | undefined; scope: DeskScope;
  error: unknown; followers: DeskPosition[]; onExit: (s: EngineSessionPositions, p: PositionRow) => void;
  onResumed: () => void }) {
  const left = (all ?? []).filter((s) => !(sessions ?? []).includes(s));
  const resume = useMutation({
    mutationFn: (s: EngineSessionPositions) => postJson(`/api/sessions/${s.session}/entries/release`,
      { reason: "resumed from the exit desk" }),
    onSettled: onResumed,
  });
  return (
    <Card>
      <h2 className="mb-1 text-base font-semibold">Engine</h2>
      <p className="mb-2 text-xs text-muted">The live sessions' own positions. Exiting a SIGNAL-1 position also exits every follower
        copy of it (they follow SIGNAL-1); PAPER-1's is your own paper account (DEMO-1 mirrors it).</p>
      {left.length > 0 && <p className="mb-2 text-xs font-semibold text-warn">Not shown with {scopeName(scope)} chosen: {left.map((s) =>
        s.account).join(", ")} ({scope === "LIVE" ? "the engine's exits reach the paper book" : "a signal source's exit reaches its LIVE followers too"};
        choose Both to act on {left.length === 1 ? "it" : "them"}).</p>}
      {resume.error && <p className="text-sm text-down">{(resume.error as Error).message}</p>}
      {error ? <p className="text-sm text-muted">Engine positions not readable here ({(error as Error).message}).</p>
        : !sessions ? <p className="text-sm text-muted">Loading…</p>
        : sessions.length === 0 ? <p className="text-sm text-muted">{left.length > 0 ? "No session in this book."
          : "No live session is running."}</p>
        : sessions.map((s) => {
          const open = s.positions.filter((p) => p.quantity > 0);
          const pnl = open.reduce((a, p) => a + (p.unrealised ?? 0), 0);
          return (
            <div key={s.account} id={`engine-${s.account}`} className="mb-2 rounded-lg border border-line">
              <div className="flex flex-wrap items-baseline justify-between gap-2 border-b border-line px-3 py-2 text-sm">
                <span><b>{s.account}</b> <span className="text-muted">· session {s.session} · {s.status}{s.lastEvent ? ` · last ${s.lastEvent}` : ""}</span>
                  {s.killSwitches?.includes("GLOBAL") && <>
                    <span className="ml-2 rounded bg-down/15 px-1.5 py-0.5 text-[11px] font-semibold text-down">NEW ENTRIES STOPPED</span>
                    <button type="button" className="ml-2 min-h-9 rounded-lg border border-line px-3 text-xs font-semibold"
                      disabled={resume.isPending} onClick={() => { if (window.confirm(`Let ${s.account} enter new trades again?`
                        + (s.account.startsWith("SIGNAL") ? " Its entries go to every follower again." : ""))) resume.mutate(s); }}>
                      Resume entries</button></>}</span>
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
                        {p.strategy && <button type="button" className="mt-1 min-h-9 rounded-lg border border-down px-3 text-xs font-semibold text-down"
                          onClick={() => onExit(s, p)}>Exit…</button>}
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

/** How much of each position a batch sells: all of it, a share of its lots, or a number of lots (whole lots). */
type Part = { kind: "all" } | { kind: "share"; f: number; label: string } | { kind: "lots"; n: number };
const SHARES: Part[] = [{ kind: "share", f: 0.75, label: "¾" }, { kind: "share", f: 0.5, label: "½" },
  { kind: "share", f: 0.25, label: "¼" }];
/** Lots to sell of one row (0: it stays open; null: all of it, no quantity sent). */
function lotsOf(r: DeskPosition, part: Part): number | null {
  if (part.kind === "all") return null;
  const held = r.lots ?? 0;
  if (!r.lotSize || held <= 0) return 0;                              // lot size unknown: only all of it
  const n = part.kind === "share" ? Math.floor(held * part.f) : Math.min(part.n, held);
  return n >= held ? null : n;
}
const lotWord = (n: number) => `${n} lot${n === 1 ? "" : "s"}`;
const amount = (r: DeskPosition, lots: number | null) => lots == null
  ? (r.lots != null ? `all ${lotWord(r.lots)} (${r.quantity} qty)` : `all ${r.quantity} qty`)
  : `${lots} of ${r.lots} lots (${lots * (r.lotSize ?? 0)} qty)`;

/**
 * Exit selected / everything / all in one account: one reason, the kill switch after (on by default when a LIVE position
 * is in it, decision 2), the count typed ("EXIT 3 POSITIONS"), one key per opened sheet (a double tap or retry answers the
 * first result and sells nothing more). The server runs LIVE first (largest premium at risk first), paced per broker app.
 * 25 Nov 2026: the book chosen is said at the top and sent with it (the server refuses a position of the other book);
 * Exit selected may sell a part of each (a share or a number of lots); a LIVE position in it is confirmed by a tick that
 * names the accounts and quantities.
 */
function BatchSheet({ rows, title, kill, everything, partial, scope, onClose, onDone }: { rows: DeskPosition[]; title: string;
  kill?: boolean; everything?: { engine: EngineSessionPositions[] }; partial?: boolean; scope: DeskScope; onClose: () => void;
  onDone: () => void }) {
  // the engine's open positions when the sheet opened, in this book (exit-everything exits whatever is open when sent)
  const [engineRows] = useState(() => (everything?.engine ?? []).flatMap((s) => s.positions.filter((p) => p.quantity > 0)
    .map((p) => ({ account: s.account, p }))));
  const [engineAccounts] = useState(() => (everything?.engine ?? []).map((s) => s.account));
  const client = useQueryClient();
  const [snapshot] = useState(rows);                          // what was on screen when the sheet opened
  const [part, setPart] = useState<Part>({ kind: "all" });
  // what is sent: each row with its lots (null: all), the rows a part leaves at 0 lots stay open and are not sent
  const plan = snapshot.map((r) => ({ r, lots: lotsOf(r, part) }));
  const sent = plan.filter((x) => x.lots !== 0);
  const kept = plan.filter((x) => x.lots === 0);
  const anyLive = sent.some((x) => x.r.mode === "LIVE");
  const liveRows = sent.filter((x) => x.r.mode === "LIVE");
  const maxLots = Math.max(1, ...snapshot.map((r) => r.lots ?? 1));
  // plan 2026-11-23 decision 3: the LIVE accounts' kill switches on by default, the paper ones only when asked;
  // "Exit everything" also engages GLOBAL_LIVE first and exits the engine's own positions (25 Nov 2026: in its book only)
  const [killAfter, setKillAfter] = useState(kill ?? snapshot.some((r) => r.mode === "LIVE"));
  const [killPaper, setKillPaper] = useState(false);
  const [globalLive, setGlobalLive] = useState(everything != null && scope !== "PAPER");
  const engineAllowed = everything != null && scope !== "LIVE";
  const [withEngine, setWithEngine] = useState(engineAllowed && engineRows.length > 0);
  // review 23 Nov 2026: "everything" stays flat only if the engine cannot enter again on its next signal (and SIGNAL-1
  // send that entry to every follower): its sessions' new entries are stopped first, resumed from the Engine card
  // both books: as before, asked of every live session even when the desk could not read them
  const engineStoppable = engineAllowed && (scope === "BOTH" || engineAccounts.length > 0);
  const [stopEngine, setStopEngine] = useState(engineStoppable);
  const [engineDone, setEngineDone] = useState<string | null>(null);
  const [globalDone, setGlobalDone] = useState<string | null>(null);
  const [reason, setReason] = useState("");
  const [typed, setTyped] = useState("");
  const [liveOk, setLiveOk] = useState(false);
  const [key, setKey] = useState(newKey);
  const sending = useRef(false);
  const countPhrase = `EXIT ${sent.length} POSITIONS`;
  const phrase = everything ? "EXIT EVERYTHING" : countPhrase;
  const choosePart = (p: Part) => {
    if (run.data || run.isPending) return;
    setPart(p); setTyped(""); setLiveOk(false); setKey(newKey()); run.reset();
  };
  // review 20 Nov 2026: the batch runs in the background (a LIVE batch can outlast the proxy's 150 s); POST answers at
  // once with its id and this sheet follows it every 1.5 s, showing each row as it answers
  const run = useMutation({
    mutationFn: async () => {
      // the followers' batch starts first (GLOBAL_LIVE and the kill switches before any of its exits, then LIVE first,
      // paced; it runs on in the background), then the engine's exits are sent at once. A SIGNAL-1 exit fanned out to a
      // follower whose position the batch is also selling is run on that account's own lane after it: one sells, the
      // other answers ALREADY_CLOSED / NOT_HELD; nothing is sold twice
      const wantGlobal = everything != null && globalLive && scope !== "PAPER";
      if (sent.length === 0 && wantGlobal) {
        // review 23 Nov 2026: with no follower position there is no batch to carry GLOBAL_LIVE: engaged on its own
        await postJson("/api/execution/kill-switches", { scope: "GLOBAL_LIVE", reason: `EXIT EVERYTHING: ${reason.trim()}` });
        setGlobalDone("engaged first: no LIVE entry anywhere until it is released");
      }
      const followers = sent.length === 0 ? null : await postJson<BatchResult>("/api/execution/admin/exit-batch", {
        positions: sent.map(({ r, lots }) => ({ accountId: r.accountId, positionId: r.positionId,
          quantity: lots == null ? null : lots * (r.lotSize ?? 0) })),
        reason: reason.trim(), killAfter: killAfter || (killPaper && scope !== "LIVE"), killPaper: killPaper && scope !== "LIVE",
        globalLive: wantGlobal, key, confirm: countPhrase, scope });
      if (engineAllowed && (withEngine || stopEngine)) {
        try {
          const e = await postJson<{ sessions: { account: string; positions: number }[] }>("/api/sessions/exit-everything",
            { reason: reason.trim(), confirm: "EXIT EVERYTHING", stopEntries: stopEngine, exitPositions: withEngine, scope });
          setEngineDone(`Engine: ${withEngine ? "exit sent for " : ""}${e.sessions.map((x) => withEngine ? `${x.account} (${x.positions})`
            : x.account).join(", ") || "no live session in this book"}`
            + `${stopEngine ? "; its new entries stopped (Resume on the Engine card)" : ""}.`);
        } catch (err) {
          setEngineDone(`Engine exit NOT sent: ${(err as Error).message}`);
        }
      }
      return followers ?? ({ batchId: "", status: "DONE", count: 0, answered: 0, killAfter: false, killed: [], killFailed: {},
        order: [], results: [], startedAt: "", finishedAt: "" } as BatchResult);
    },
    onSettled: () => { sending.current = false; },
  });
  const batchId = run.data?.batchId || undefined;
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
  const nothing = sent.length === 0 && !(everything && (withEngine || stopEngine || globalLive));
  const ready = !run.isPending && !nothing && reason.trim() !== "" && typed.trim().toUpperCase() === phrase
    && (liveRows.length === 0 || liveOk);
  const order = [...sent].sort((a, b) => (a.r.mode === "LIVE" ? 0 : 1) - (b.r.mode === "LIVE" ? 0 : 1)
    || b.r.premiumAtRisk - a.r.premiumAtRisk);
  const tint = (o: string) => o === "SOLD" ? "text-up" : o === "ALREADY_CLOSED" || o === "PENDING" || o === "RUNNING" ? "text-muted"
    : "text-down";
  const liveAccounts = [...new Set(liveRows.map((x) => x.r.account))];
  const partOn = (p: Part) => JSON.stringify(p) === JSON.stringify(part);
  return (
    <Sheet title={title} onClose={onClose}>
      {res ? (
        <div className="flex flex-col gap-3">
          <p className="text-sm"><ScopeBadge scope={res.scope ?? scope} /> book</p>
          {globalDone && <p className="text-sm">GLOBAL_LIVE: {globalDone}</p>}
          {res.globalLive && <p className={`text-sm ${res.globalLive.startsWith("FAILED") ? "font-semibold text-down" : ""}`}>
            GLOBAL_LIVE: {res.globalLive === "ENGAGED" ? "engaged first: no LIVE entry anywhere until it is released"
              : res.globalLive === "ALREADY" ? "was already engaged" : res.globalLive}</p>}
          {res.count > 0 && <p className="text-sm">{res.repeat ? "Already sent: this is that batch. " : ""}{res.killAfter
            ? `Kill switch engaged first on ${res.killed.join(", ") || "none"}.` : "No kill switch: the accounts keep trading."}
            {Object.keys(res.killFailed ?? {}).length > 0 && <b className="text-down"> Kill switch NOT engaged on {Object.entries(res.killFailed)
              .map(([a, why]) => `${a} (${why})`).join(", ")}.</b>}</p>}
          <p className="text-xs text-muted">{finished ? "Done" : gone ? `${res.answered} of ${res.count} answered before it was lost`
            : `Working: ${res.answered} of ${res.count} answered…`}</p>
          {gone ? <p className="text-sm font-semibold text-down">The worker no longer knows this batch (it restarted?): rows not
            answered above may still have sold. Read the open positions again, and do not sell by hand in the broker app before
            checking its order book.</p>
            : progress.error && <p className="text-xs text-warn">Progress not readable now ({(progress.error as Error).message}); retrying.</p>}
          {engineDone && <p className={`text-sm ${engineDone.startsWith("Engine exit NOT") ? "font-semibold text-down" : ""}`}>{engineDone}</p>}
          <ul className="flex flex-col gap-1 text-sm">
            {res.results.map((r, i) => r && (
              <li key={`${r.accountId}:${r.positionId}:${i}`} className="rounded border border-line px-2 py-1">
                <b className={tint(r.outcome)}>{r.outcome.replace("_", " ")}</b>
                {" "}{r.mode && <ModeBadge mode={r.mode} />} {r.account} · {r.symbol ?? `position ${r.positionId}`}
                {r.asked != null && <span className="text-muted"> · {r.asked} qty asked</span>}
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
          <p className={`rounded-lg border p-2 text-sm ${scope === "PAPER" ? "border-warn/50 bg-warn/10" : "border-down/50 bg-down/10"}`}>
            <ScopeBadge scope={scope} />{" "}{scope === "PAPER" ? "Paper book only: nothing in the LIVE book is sold or stopped."
              : scope === "LIVE" ? "LIVE book only (real money): the paper book and the engine are not touched."
              : "Both books: real money and paper."}</p>
          {partial && snapshot.length > 0 && (
            <div className="flex flex-col gap-2">
              <span className="text-sm font-semibold">How much of each</span>
              <div className="flex flex-wrap gap-2" role="group" aria-label="How much of each position">
                <Big kind={part.kind === "all" ? "primary" : "plain"} onClick={() => choosePart({ kind: "all" })}>All</Big>
                {SHARES.map((p) => <Big key={(p as { label: string }).label} kind={partOn(p) ? "primary" : "plain"}
                  onClick={() => choosePart(p)}>{(p as { label: string }).label}</Big>)}
                <label className="flex items-center gap-1 rounded-lg border border-line px-2 text-sm">
                  <input type="number" inputMode="numeric" min={1} max={maxLots} step={1} aria-label="Lots of each"
                    value={part.kind === "lots" ? part.n : ""} placeholder="n"
                    onChange={(e) => { const n = Math.floor(Number(e.target.value));
                      choosePart(n >= 1 ? { kind: "lots", n: Math.min(n, maxLots) } : { kind: "all" }); }}
                    className="num min-h-11 w-14 bg-transparent text-center font-semibold" /> lots of each</label>
              </div>
              {snapshot.length === 1 && snapshot[0].lots != null && snapshot[0].lots > 1 && <p className="text-xs text-muted">
                Shares of {lotWord(snapshot[0].lots)}: {shareChoices(snapshot[0].lots).map((c) => `${c.label} = ${c.lots}`).join(", ")}.</p>}
              {part.kind !== "all" && <p className="text-xs text-muted">Whole lots, rounded down; the rest of each stays open with its stop.</p>}
            </div>
          )}
          {sent.length > 0 && <p className="text-sm">In this order (LIVE first, the largest premium at risk first; LIVE orders paced per broker app):</p>}
          <ol className="max-h-56 list-decimal overflow-y-auto pl-5 text-sm">
            {order.map(({ r, lots }) => <li key={rowKey(r)}><ModeBadge mode={r.mode} /> {r.account} · {r.symbol} · {amount(r, lots)}
              {r.loggedIn === false ? <b className="text-down"> · broker not logged in</b> : null}</li>)}
          </ol>
          {kept.length > 0 && <p className="text-xs text-warn">Not sent, stays open ({part.kind === "all" ? "" : "less than one lot of it, or its lot size is not known"}):{" "}
            {kept.map(({ r }) => `${r.account} · ${r.symbol}`).join("; ")}.</p>}
          {everything && engineRows.length > 0 && engineAllowed && <>
            <p className="text-sm">And the engine&apos;s own in this book ({engineAccounts.join(", ")}{engineAccounts.some((a) => a.startsWith("SIGNAL"))
              ? "; a SIGNAL-1 exit also reaches every follower copy" : ""}):</p>
            <ul className="max-h-40 list-disc overflow-y-auto pl-5 text-sm">
              {engineRows.map(({ account, p }, i) => <li key={`${account}-${p.symbol}-${i}`}>
                {`${account} · ${p.strategy} · ${p.symbol} · ${p.quantity} qty`}</li>)}
            </ul></>}
          {scope !== "PAPER" && <label className="flex items-center gap-2 text-sm">
            <input type="checkbox" checked={killAfter} onChange={(e) => setKillAfter(e.target.checked)} className="h-5 w-5 shrink-0" />
            Stop new entries on the LIVE accounts (kill switch, engaged before any exit){anyLive ? " · on by default" : ""}
          </label>}
          {scope !== "LIVE" && <label className="flex items-center gap-2 text-sm">
            <input type="checkbox" checked={killPaper} onChange={(e) => setKillPaper(e.target.checked)} className="h-5 w-5 shrink-0" />
            Stop the paper accounts{scope === "PAPER" ? " (kill switch, engaged before any exit)" : " too"}
          </label>}
          {everything && scope !== "PAPER" && <label className="flex items-center gap-2 text-sm">
            <input type="checkbox" checked={globalLive} onChange={(e) => setGlobalLive(e.target.checked)} className="h-5 w-5 shrink-0" />
            Engage GLOBAL_LIVE first (no LIVE entry anywhere until you release it)
          </label>}
          {engineAllowed && engineRows.length > 0 && <label className="flex items-center gap-2 text-sm">
            <input type="checkbox" checked={withEngine} onChange={(e) => setWithEngine(e.target.checked)} className="h-5 w-5 shrink-0" />
            Exit the engine&apos;s positions too ({engineAccounts.join(", ")})
          </label>}
          {engineStoppable && <label className="flex items-center gap-2 text-sm">
            <input type="checkbox" checked={stopEngine} onChange={(e) => setStopEngine(e.target.checked)} className="h-5 w-5 shrink-0" />
            Stop the engine&apos;s new entries ({engineAccounts.length > 0 ? engineAccounts.join(" and ") : "every live session"}
            {scope === "BOTH" ? ": no follower gets a new signal" : ""}) until resumed on the Engine card
          </label>}
          {liveRows.length > 0 && (
            <label className="flex items-start gap-2 rounded-lg border border-down bg-down/10 p-2 text-sm font-semibold text-down">
              <input type="checkbox" checked={liveOk} onChange={(e) => setLiveOk(e.target.checked)} className="mt-0.5 h-5 w-5 shrink-0"
                aria-label="Confirm the LIVE sale" />
              <span>LIVE · real money: sell {liveRows.length} position{liveRows.length === 1 ? "" : "s"} at the broker now in{" "}
                {liveAccounts.join(", ")}: {liveRows.map(({ r, lots }) => `${r.account} ${r.symbol} ${amount(r, lots)}`).join("; ")}.</span>
            </label>
          )}
          <input className="min-h-11 rounded-lg border border-line bg-bg px-3 text-base" placeholder="Reason (each member is told)"
            value={reason} maxLength={300} onChange={(e) => setReason(e.target.value)} aria-label="Reason" />
          <input className="min-h-11 rounded-lg border border-line bg-bg px-3 text-base uppercase" placeholder={`Type ${phrase}`}
            autoCapitalize="characters" autoCorrect="off" value={typed} onChange={(e) => setTyped(e.target.value)} aria-label={`Type ${phrase}`} />
          {run.error && <p className="text-sm text-down">{(run.error as Error).message}</p>}
          {unknown && <p className="text-sm font-semibold text-warn">Not known whether the batch started. Do not sell by hand in the
            broker app; sending again answers with the same batch if it did.</p>}
          <Big kind="danger" disabled={!ready && !unknown}
            onClick={() => { if (!sending.current) { sending.current = true; run.mutate(); } }}>
            {run.isPending ? "Exiting…" : unknown ? "Send the same request again" : everything ? `Exit everything · ${scopeName(scope)}`
              : `Exit ${sent.length} position${sent.length === 1 ? "" : "s"}${part.kind === "all" ? "" : " (part)"} · ${scopeName(scope)}`}</Big>
          <Big onClick={onClose}>Cancel</Big>
        </div>
      )}
    </Sheet>
  );
}

function ScopeBadge({ scope }: { scope: DeskScope }) {
  return scope === "BOTH" ? <><ModeBadge mode="LIVE" /> <ModeBadge mode="PAPER" /></> : <ModeBadge mode={scope} />;
}

/**
 * One engine position's exit (plan 2026-11-23 A1): the strategy's position on its index in that session, through the
 * session's order manager; a reason and the typed word EXIT. On SIGNAL-1 the follower copies are listed: they exit too.
 */
function EngineExitSheet({ session, p, followers, scope, onClose, onDone }: { session: EngineSessionPositions; p: PositionRow;
  followers: DeskPosition[]; scope: DeskScope; onClose: () => void; onDone: () => void }) {
  const [reason, setReason] = useState("");
  const [typed, setTyped] = useState("");
  const sending = useRef(false);
  const copies = followers.filter((f) => f.source === session.account && f.strategy === p.strategy && f.underlying === p.underlying);
  const exit = useMutation({
    mutationFn: () => postJson<{ outcome: string }>(`/api/sessions/${session.session}/positions/exit`,
      { strategy: p.strategy, underlying: p.underlying, reason: reason.trim(), scope }),
    onSettled: () => { sending.current = false; onDone(); },
  });
  return (
    <Sheet title={`Exit ${p.strategy} on ${p.underlying} · ${session.account} · ${scopeName(scope)}`} onClose={onClose}>
      {exit.data ? (
        <div className="flex flex-col gap-3">
          <p className="text-sm">{exit.data.outcome === "EXITING" ? "Exit sent: the session's order manager is selling it now."
            : "Nothing to exit: it is closed, or its exit is already under way."}</p>
          <Big onClick={onClose}>Done</Big>
        </div>
      ) : (
        <div className="flex flex-col gap-3">
          <p className="text-sm">{p.leg ? `${p.strategy}'s straddle on ${p.underlying}: both legs` : `${p.symbol} · ${p.quantity} qty`},
            through {session.account}&apos;s own exit (marketable limit, chased).</p>
          {session.account.startsWith("SIGNAL") && <p className="text-sm font-semibold text-down">{copies.length > 0
            ? `This also exits every follower copy: ${copies.map((c) => `${c.account} (${c.mode})`).join(", ")}.`
            : "Followers holding this trade exit too (none open on the desk now)."}</p>}
          <input className="min-h-11 rounded-lg border border-line bg-bg px-3 text-base" placeholder="Reason (audited)"
            value={reason} maxLength={300} onChange={(e) => setReason(e.target.value)} aria-label="Reason" />
          <input className="min-h-11 rounded-lg border border-line bg-bg px-3 text-base uppercase" placeholder="Type EXIT"
            autoCapitalize="characters" autoCorrect="off" value={typed} onChange={(e) => setTyped(e.target.value)} aria-label="Type EXIT" />
          {exit.error && <p className="text-sm text-down">{(exit.error as Error).message}</p>}
          <Big kind="danger" disabled={typed.trim().toUpperCase() !== "EXIT" || reason.trim().length < 3 || exit.isPending}
            onClick={() => { if (!sending.current) { sending.current = true; exit.mutate(); } }}>
            {exit.isPending ? "Exiting…" : "Exit now"}</Big>
          <Big onClick={onClose}>Cancel</Big>
        </div>
      )}
    </Sheet>
  );
}

/**
 * The admin target (25 Nov 2026, user: "admin for global to be exited from all LIVE all workspaces with the first
 * target"): one % above each position's own entry, set on every position sent (the book shown, or the selected ones), or
 * removed from them; a reason (the members are told, per account), the count typed, a tick for LIVE naming the accounts.
 * Each position answers SET, UNCHANGED, CLEARED or REFUSED (why).
 */
function TargetSheet({ rows, title, scope, onClose, onDone }: { rows: DeskPosition[]; title: string; scope: DeskScope;
  onClose: () => void; onDone: () => void }) {
  const [snapshot] = useState(rows);
  const [mode, setMode] = useState<"set" | "clear">("set");
  const [text, setText] = useState("");
  const [reason, setReason] = useState("");
  const [typed, setTyped] = useState("");
  const [liveOk, setLiveOk] = useState(false);
  const sending = useRef(false);
  const pct = targetPct(text);
  const sent = mode === "set" ? snapshot : snapshot.filter((r) => targetActive(r.manualTarget));
  const phrase = mode === "set" ? `TARGET ${sent.length} POSITIONS` : `CLEAR ${sent.length} TARGETS`;
  const live = sent.filter((r) => r.mode === "LIVE");
  const run = useMutation({
    mutationFn: () => postJson<TargetBatchResult>(`/api/execution/admin/targets${mode === "clear" ? "/clear" : ""}`, {
      positions: sent.map((r) => ({ accountId: r.accountId, positionId: r.positionId })), pct: mode === "set" ? pct : null,
      reason: reason.trim(), confirm: phrase, scope }),
    onSettled: () => { sending.current = false; onDone(); },
  });
  const ready = !run.isPending && sent.length > 0 && (mode === "clear" || pct != null) && reason.trim() !== ""
    && typed.trim().toUpperCase() === phrase && (live.length === 0 || liveOk);
  const switchTo = (m: "set" | "clear") => { setMode(m); setTyped(""); setLiveOk(false); run.reset(); };
  const tint = (o: string) => (o === "SET" || o === "CLEARED" ? "text-up" : o === "UNCHANGED" ? "text-muted" : "text-down");
  return (
    <Sheet title={`${title} · ${scopeName(scope)}`} onClose={onClose}>
      {run.data ? (
        <div className="flex flex-col gap-3">
          <p className="text-sm"><ScopeBadge scope={run.data.scope} /> book · {run.data.count} position{run.data.count === 1 ? "" : "s"}</p>
          <ul className="flex flex-col gap-1 text-sm">
            {run.data.results.map((r, i) => (
              <li key={`${r.accountId}:${r.positionId}:${i}`} className="rounded border border-line px-2 py-1">
                <b className={tint(r.outcome)}>{r.outcome}</b> {r.mode && <ModeBadge mode={r.mode} />} {r.account} · {r.symbol ?? `position ${r.positionId}`}
                {r.detail && <div className="text-xs text-muted">{r.detail}</div>}
              </li>
            ))}
          </ul>
          <Big onClick={onClose}>Done</Big>
        </div>
      ) : (
        <div className="flex flex-col gap-3">
          <p className={`rounded-lg border p-2 text-sm ${scope === "PAPER" ? "border-warn/50 bg-warn/10" : "border-down/50 bg-down/10"}`}>
            <ScopeBadge scope={scope} /> A first target is a lock: nothing happens until the bid reaches entry × (1 + %); if the
            bid then falls back to that price, the whole position is sold (beside its strategy, stop and Exit). On members&apos;
            accounts it is the admin&apos;s target: they are told, with the reason.</p>
          <div className="grid grid-cols-2 gap-2">
            <Big kind={mode === "set" ? "primary" : "plain"} onClick={() => switchTo("set")}>Set a target</Big>
            <Big kind={mode === "clear" ? "primary" : "plain"} onClick={() => switchTo("clear")}>Remove targets</Big>
          </div>
          {mode === "set" && (
            <label className="flex flex-wrap items-center gap-2 text-sm">
              Target % above each entry
              <input type="number" inputMode="decimal" step="any" value={text} onChange={(e) => { setText(e.target.value); setTyped(""); }}
                aria-label="Target % above each entry" className="num min-h-11 w-24 rounded-lg border border-line bg-bg px-2 text-base" /> %
            </label>
          )}
          {mode === "set" && text.trim() !== "" && pct == null && <p className="text-sm text-down">A % above 0 and at most 1000.</p>}
          <ol className="max-h-56 list-decimal overflow-y-auto pl-5 text-sm">
            {sent.map((r) => <li key={rowKey(r)}><ModeBadge mode={r.mode} /> {r.account} ({r.tenant}) · {r.symbol} ·{" "}
              {r.lots != null ? `${r.lots} lot${r.lots === 1 ? "" : "s"}` : `${r.quantity} qty`}
              {mode === "set" ? (pct != null ? ` · entry ${r.entry.toFixed(2)} → target ${targetPrice(r.entry, pct).toFixed(2)}` : "")
                : r.manualTarget ? ` · removes +${r.manualTarget.pct} % (${r.manualTarget.price.toFixed(2)})` : ""}
              {mode === "set" && targetActive(r.manualTarget) ? <span className="text-warn"> · replaces +{r.manualTarget!.pct} %{r.manualTarget!.status === "ARMED" ? " (armed: starts over)" : ""}</span> : null}
              {r.targetNeedsReason ? <span className="text-muted"> · member told</span> : null}</li>)}
          </ol>
          {sent.length === 0 && <p className="text-sm text-muted">{mode === "clear" ? "None of these has a first target." : "Nothing to set."}</p>}
          {live.length > 0 && (
            <label className="flex items-start gap-2 rounded-lg border border-down bg-down/10 p-2 text-sm font-semibold text-down">
              <input type="checkbox" checked={liveOk} onChange={(e) => setLiveOk(e.target.checked)} className="mt-0.5 h-5 w-5 shrink-0"
                aria-label="Confirm the LIVE target" />
              <span>LIVE · real money: {mode === "set" ? `a ${pct ?? "?"} % first target on` : "remove the first target from"}{" "}
                {live.length} LIVE position{live.length === 1 ? "" : "s"} in {[...new Set(live.map((r) => r.account))].join(", ")}
                {mode === "set" ? ": when it triggers, a real sell order of the whole position at the broker (it can fill below the target in a fast fall)" : ""}.</span>
            </label>
          )}
          <input className="min-h-11 rounded-lg border border-line bg-bg px-3 text-base" placeholder="Reason (each member is told)"
            value={reason} maxLength={300} onChange={(e) => setReason(e.target.value)} aria-label="Reason" />
          <input className="min-h-11 rounded-lg border border-line bg-bg px-3 text-base uppercase" placeholder={`Type ${phrase}`}
            autoCapitalize="characters" autoCorrect="off" value={typed} onChange={(e) => setTyped(e.target.value)} aria-label={`Type ${phrase}`} />
          {run.error && <p className="text-sm text-down">{(run.error as Error).message}</p>}
          <Big kind="danger" disabled={!ready}
            onClick={() => { if (!sending.current) { sending.current = true; run.mutate(); } }}>
            {run.isPending ? "Saving…" : mode === "set" ? `Set +${pct ?? "?"} % on ${sent.length} position${sent.length === 1 ? "" : "s"} · ${scopeName(scope)}`
              : `Remove ${sent.length} target${sent.length === 1 ? "" : "s"} · ${scopeName(scope)}`}</Big>
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
