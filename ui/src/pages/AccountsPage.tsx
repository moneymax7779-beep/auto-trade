import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import {
  get, postJson, type AuditRow, type ExecMe, type ExecutionAccount, type GlobalLiveStrategies, type KillSwitch,
  type WorkerStatus,
} from "../api";
import { ErrorNote, Loading, Panel, Stat, Table } from "../components/ui";
import { AccountDetailPanel, AddAccountPanel } from "./AccountManagement";

// Live execution: the caller's broker accounts across copy groups, kill switches, flatten and the audit log. Every call
// goes to the execution workers (app-execution) through trading-core; the workers scope it to the user's tenants.

const time = (iso: string | null | undefined) =>
  iso ? new Date(iso).toLocaleTimeString("en-IN", { hour12: false, timeZone: "Asia/Kolkata" }) : "–";
const dateTime = (iso: string) =>
  new Date(iso).toLocaleString("en-IN", { hour12: false, timeZone: "Asia/Kolkata", day: "2-digit", month: "short",
    hour: "2-digit", minute: "2-digit", second: "2-digit" });
const rupees = (v: number) => `₹${Math.round(v).toLocaleString("en-IN")}`;

function Badge({ text, tone }: { text: string; tone: "up" | "down" | "warn" | "muted" | "accent" }) {
  const color = {
    up: "bg-up/15 text-up", down: "bg-down/15 text-down", warn: "bg-warn/15 text-warn",
    muted: "bg-panel-2 text-muted", accent: "bg-accent/15 text-accent",
  }[tone];
  return <span className={`whitespace-nowrap rounded px-1.5 py-0.5 text-[11px] font-semibold ${color}`}>{text}</span>;
}

const connectionTone = (c: string | null) =>
  c === "ACTIVE" ? "up" : c === "PENDING_CONNECT" ? "muted" : c ? "down" : "muted";
const stateTone = (s: string | undefined) =>
  s === "RUNNING" ? "up" : s === "REFUSED" || s === "LEASE_HELD_ELSEWHERE" ? "warn" : s ? "down" : "muted";
const modeTone = (m: string) => (m === "LIVE" ? "down" : m === "DRY_RUN" ? "accent" : "muted");

const BROKER_LABELS: Record<string, string> = {
  ZERODHA: "Zerodha (Kite)", UPSTOX: "Upstox", KOTAK_NEO: "Kotak Neo", GROWW: "Groww", PAPER: "Paper (simulated fills)",
};
const brokerName = (b: string) => BROKER_LABELS[b] ?? b;
const connectionLabel = (c: string | null) => ({
  ACTIVE: "LOGGED IN", TOKEN_EXPIRED: "LOGIN EXPIRED", DISCONNECTED: "DISCONNECTED", PENDING_CONNECT: "NOT CONNECTED",
} as Record<string, string>)[c ?? ""] ?? c ?? "?";
const modeRank = (m: string) => (m === "LIVE" ? 0 : m === "DRY_RUN" ? 1 : 2);

type Row = { a: ExecutionAccount; twin: boolean };

/**
 * The trader's reading order: per workspace (the caller's own first), real broker accounts first (LIVE, then
 * DRY_RUN, then PAPER), each followed by its paper twin; then stand-alone paper accounts; disabled accounts last.
 */
function orderAccounts(rows: ExecutionAccount[], tenantRank: (id: number) => number): Row[] {
  const ids = new Set(rows.map((a) => a.id));
  const twinsOf = new Map<number, ExecutionAccount[]>();
  rows.forEach((a) => { if (a.paperOf != null && ids.has(a.paperOf)) twinsOf.set(a.paperOf, [...(twinsOf.get(a.paperOf) ?? []), a]); });
  const rank = (t: number) => { const r = tenantRank(t); return r < 0 ? Number.MAX_SAFE_INTEGER : r; };
  const heads = rows.filter((a) => a.paperOf == null || !ids.has(a.paperOf)).sort((x, y) =>
    rank(x.tenantId) - rank(y.tenantId) || x.tenantId - y.tenantId
    || Number(!x.enabled) - Number(!y.enabled)
    || Number(x.broker === "PAPER") - Number(y.broker === "PAPER")
    || modeRank(x.mode) - modeRank(y.mode)
    || x.name.localeCompare(y.name));
  return heads.flatMap((a) => [{ a, twin: false }, ...(twinsOf.get(a.id) ?? []).map((t) => ({ a: t, twin: true }))]);
}

/** Today's books added up; live* = entered with a LIVE intent. Accounts without a book (older worker) add nothing. */
function dayTotals(xs: ExecutionAccount[]) {
  const t = { net: 0, trades: 0, open: 0, liveNet: 0, liveTrades: 0, liveOpen: 0 };
  xs.forEach((a) => {
    const d = a.today;
    if (!d) return;
    t.net += d.net; t.trades += d.trades; t.open += d.open;
    t.liveNet += d.liveNet ?? 0; t.liveTrades += d.liveTrades ?? 0; t.liveOpen += d.liveOpen ?? 0;
  });
  return t;
}
const tone = (v: number) => (v > 0 ? "up" : v < 0 ? "down" : "muted") as "up" | "down" | "muted";

const signed = (v: number) => (v > 0 ? "+" : v < 0 ? "−" : "") + rupees(Math.abs(v));
const netTone = (v: number) => (v > 0 ? "text-up" : v < 0 ? "text-down" : "text-muted");

function TodayCell({ a }: { a: ExecutionAccount }) {
  if (!a.today) return <span className="text-muted">–</span>;
  const { net, trades, open } = a.today;
  return (
    <div className="whitespace-nowrap">
      <div className={`font-semibold ${netTone(net)}`}>{trades === 0 && net === 0 ? "–" : signed(net)}</div>
      <div className="text-[11px] text-muted">{trades} closed{open > 0 && <span className="font-semibold text-warn"> · {open} open</span>}</div>
      {(a.today.liveTrades ?? 0) + (a.today.liveOpen ?? 0) > 0 && (a.today.liveTrades !== trades || a.today.liveOpen !== open) && (
        <div className="text-[11px] text-down">of which LIVE {signed(a.today.liveNet)}</div>)}
    </div>
  );
}

/** What a trader checks first: real money today, paper today, what is still open, and what needs a hand. */
function FollowerSummary({ rows, killed }: { rows: ExecutionAccount[]; killed: Set<number> }) {
  const live = rows.filter((a) => a.mode === "LIVE");
  const b = dayTotals(rows);
  const issues: string[] = [];
  rows.forEach((a) => {
    if (!a.enabled) return;
    const st = a.lastTurn?.state;
    // an IDLE account (a workspace's real account that is not the day's broker) needs no login today
    if (a.broker !== "PAPER" && a.connection !== "ACTIVE" && st !== "IDLE") {
      issues.push(`${a.name}: ${connectionLabel(a.connection).toLowerCase()}`);
    }
    if (st && st !== "RUNNING" && st !== "IDLE") issues.push(`${a.name}: ${st.toLowerCase().replaceAll("_", " ")}`);
    if (killed.has(a.id)) issues.push(`${a.name}: entries stopped`);
  });
  const hasBooks = rows.some((a) => a.today);
  return (
    <div className="mb-3 space-y-2 border-b border-line pb-3">
      <div className="grid grid-cols-2 gap-x-6 gap-y-2 sm:grid-cols-4">
        <Stat label={`LIVE today (real money, ${b.liveTrades} closed)`} value={hasBooks ? signed(b.liveNet) : "–"}
          tone={tone(b.liveNet)} />
        <Stat label={`LIVE-eligible capital (${live.length} account${live.length === 1 ? "" : "s"})`}
          value={rupees(live.reduce((s, a) => s + a.allocatedCapital, 0))} />
        <Stat label={`PAPER / DRY_RUN today (${b.trades - b.liveTrades} closed)`} value={hasBooks ? signed(b.net - b.liveNet) : "–"}
          tone={tone(b.net - b.liveNet)} />
        <Stat label="Open positions (live · paper)" value={hasBooks ? `${b.liveOpen} · ${b.open - b.liveOpen}` : "–"}
          tone={b.open > 0 ? "warn" : "muted"} />
      </div>
      {issues.length > 0 && (
        <div className="flex flex-wrap items-center gap-1.5 text-xs">
          <span className="text-muted">Needs attention:</span>
          {issues.map((t, i) => <span key={i} className="rounded bg-warn/15 px-1.5 py-0.5 text-warn">{t}</span>)}
        </div>
      )}
    </div>
  );
}

function GroupHeading({ name, daily, accounts }: { name: string; daily: boolean; accounts: ExecutionAccount[] }) {
  const b = dayTotals(accounts);
  return (
    <div className="flex flex-wrap items-baseline gap-x-3 border-b border-line pb-1">
      <span className="text-sm font-semibold">{name}</span>
      <span className="text-xs text-muted">{daily ? "daily-switch workspace" : "own accounts"} · {accounts.length} account
        {accounts.length === 1 ? "" : "s"}</span>
      {/* real money and paper are never added together */}
      {accounts.some((a) => a.today) && <span className="num text-xs">
        {b.liveTrades + b.liveOpen > 0 && <span className={netTone(b.liveNet)}>LIVE {signed(b.liveNet)} · </span>}
        <span className={netTone(b.net - b.liveNet)}>paper {signed(b.net - b.liveNet)}</span></span>}
    </div>
  );
}

/**
 * The owner's global LIVE strategies (user, 9 Nov 2026: "as admin ... select or deselect the live strategies at global";
 * standing until changed, not per day). A strategy not ticked trades on paper accounts only, never with real money. An
 * account with its own list (its Manage panel) keeps it; neither goes beyond the account's risk file.
 */
function GlobalLivePanel({ onDone }: { onDone: (text: string, ok: boolean) => void }) {
  const client = useQueryClient();
  const q = useQuery({ queryKey: ["live-strategies-global"], refetchInterval: 30000,
    queryFn: () => get<GlobalLiveStrategies>("/api/execution/live-strategies") });
  const [picked, setPicked] = useState<string[] | null>(null);
  const save = useMutation({
    mutationFn: (body: { strategies?: string[]; reset?: boolean }) =>
      postJson<GlobalLiveStrategies>("/api/execution/live-strategies", body),
    onSuccess: (r) => {
      onDone(`LIVE strategies (all accounts): ${r.global == null ? "each account's default" : r.global.join(", ") || "none"}`,
        true);
      setPicked(null);
      client.setQueryData(["live-strategies-global"], r);
      ["exec-audit", "exec-accounts", "live-strategies"].forEach((k) => client.invalidateQueries({ queryKey: [k] }));
    },
    onError: (e) => onDone(e instanceof Error ? e.message : String(e), false),
  });
  if (q.error) return <ErrorNote error={q.error} />;
  if (!q.data || q.data.available.length === 0) return null;
  const g = q.data;
  // not set: what the accounts trade now by their defaults (the union), so the boxes start where things are
  const startingPoint = g.global ?? [...new Set(g.accounts.filter((a) => a.source === "DEFAULT").flatMap((a) => a.effective))];
  const current = picked ?? startingPoint;
  const changed = picked != null && [...picked].sort().join() !== [...startingPoint].sort().join();
  const toggle = (s: string) => setPicked(current.includes(s) ? current.filter((x) => x !== s) : [...current, s]);
  const own = g.accounts.filter((a) => a.source === "ACCOUNT");
  return (
    <Panel title="LIVE strategies · all accounts" right={<span className="text-xs text-muted">
      {g.global == null ? "not set: each account's risk-file default" : "your global list"}</span>}>
      <div className="flex flex-wrap gap-x-5 gap-y-2 text-sm">
        {g.available.map((s) => (
          <label key={s} className={`flex items-center gap-1.5 ${g.canEdit ? "cursor-pointer" : "text-muted"}`}>
            <input type="checkbox" checked={current.includes(s)} disabled={!g.canEdit || save.isPending}
              onChange={() => toggle(s)} />
            <span className={current.includes(s) ? "font-semibold" : "text-muted"}>{s}</span>
            <span className={`text-[10px] font-semibold ${current.includes(s) ? "text-down" : "text-muted"}`}>
              {current.includes(s) ? "LIVE" : "PAPER"}</span>
          </label>
        ))}
      </div>
      {g.canEdit && (
        <div className="mt-3 flex flex-wrap items-center gap-2">
          <button className="rounded bg-accent px-3 py-1 text-sm font-semibold text-white disabled:opacity-40"
            disabled={!changed || save.isPending} onClick={() => save.mutate({ strategies: current })}>
            {save.isPending ? "Saving…" : "Save for all accounts"}</button>
          {picked != null && <button className="rounded border border-line px-3 py-1 text-sm hover:bg-panel-2"
            disabled={save.isPending} onClick={() => setPicked(null)}>Undo</button>}
          {g.global != null && <button className="rounded border border-line px-3 py-1 text-sm hover:bg-panel-2"
            disabled={save.isPending} onClick={() => save.mutate({ reset: true })}>Back to each account's default</button>}
          <span className="text-xs text-muted">Unticked = PAPER only: no real order from it on any account. Applies from the
            next LIVE entry or add; exits of held positions always run. Stays until you change it.</span>
        </div>
      )}
      <div className="mt-3 border-t border-line pt-2 text-xs">
        {g.accounts.map((a) => (
          <div key={a.id} className="flex flex-wrap gap-x-2">
            <span className="font-semibold">{a.account}</span>
            <span className="text-muted">{a.mode} ·</span>
            <span>{a.effective.join(", ") || "nothing LIVE"}</span>
            <span className="text-muted">({a.source === "ACCOUNT" ? "its own list, set on its Manage panel"
              : a.source === "GLOBAL" ? "global list" : "risk-file default"})</span>
          </div>
        ))}
        {own.length > 0 && <p className="mt-1 text-warn">{own.map((a) => a.account).join(", ")} keep their own list; open
          Manage → LIVE strategies → "Follow the global list" to put them under this one.</p>}
      </div>
    </Panel>
  );
}

/** A destructive action that needs its word typed before it is sent. */
function TypedConfirm({ word, label, busy, onConfirm }: {
  word: string; label: string; busy: boolean; onConfirm: (reason: string) => void;
}) {
  const [typed, setTyped] = useState("");
  const [reason, setReason] = useState("");
  return (
    <div className="flex flex-wrap items-center gap-2">
      <input className="w-40 rounded border border-line bg-bg px-2 py-1 text-sm" placeholder={`type ${word}`}
        value={typed} onChange={(e) => setTyped(e.target.value)} />
      <input className="w-48 rounded border border-line bg-bg px-2 py-1 text-sm" placeholder="reason"
        value={reason} onChange={(e) => setReason(e.target.value)} />
      <button className="rounded bg-down px-3 py-1 text-sm font-semibold text-white disabled:opacity-40"
        disabled={typed !== word || busy} onClick={() => { onConfirm(reason || "manual"); setTyped(""); }}>
        {busy ? "Working…" : label}
      </button>
    </div>
  );
}

export function AccountsPage() {
  const client = useQueryClient();
  const status = useQuery({ queryKey: ["exec-status"], queryFn: () => get<WorkerStatus>("/api/execution/status"),
    refetchInterval: 3000 });
  const accounts = useQuery({ queryKey: ["exec-accounts"], queryFn: () => get<ExecutionAccount[]>("/api/execution/accounts"),
    refetchInterval: 5000 });
  const switches = useQuery({ queryKey: ["exec-switches"], queryFn: () => get<KillSwitch[]>("/api/execution/kill-switches"),
    refetchInterval: 5000 });
  // the caller's tenants and role in each (29 Oct 2026, family members): what they may do on which account
  const me = useQuery({ queryKey: ["exec-me"], queryFn: () => get<ExecMe>("/api/execution/me"), staleTime: 30_000 });
  const [auditAccount, setAuditAccount] = useState<number | "">("");
  const audit = useQuery({
    queryKey: ["exec-audit", auditAccount],
    queryFn: () => get<AuditRow[]>(`/api/execution/audit${auditAccount === "" ? "" : `?account=${auditAccount}`}`),
    refetchInterval: 10000,
  });
  const [flattenFor, setFlattenFor] = useState<number | null>(null);
  const [openAccount, setOpenAccount] = useState<number | null>(null);
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null);

  const act = useMutation({
    mutationFn: ({ path, body }: { path: string; body: unknown; done: string }) => postJson<unknown>(path, body),
    onSuccess: (data, v) => {
      setMessage({ ok: true, text: `${v.done}${data ? ": " + JSON.stringify(data) : ""}` });
      setFlattenFor(null);
      ["exec-status", "exec-accounts", "exec-switches", "exec-audit"].forEach((k) => client.invalidateQueries({ queryKey: [k] }));
    },
    onError: (e) => setMessage({ ok: false, text: e instanceof Error ? e.message : String(e) }),
  });

  if (accounts.isLoading) return <Loading what="accounts" />;
  if (accounts.error) return <ErrorNote error={accounts.error} />;
  const rows = accounts.data!.filter((a) => a.role === "FOLLOWER_TRADE");
  const names = new Map(accounts.data!.map((a) => [a.id, a.name]));
  const s = status.data;
  // a worker without /api/execution/me (older build): show the controls as before; the worker refuses what is not allowed
  const legacy = me.isError;
  const tenants = new Map((me.data?.tenants ?? []).map((t) => [t.id, t]));
  const mayAct = (tenantId: number) => legacy || (tenants.get(tenantId)?.mayAct ?? false);
  const manages = (tenantId: number) => legacy || (tenants.get(tenantId)?.manages ?? false);
  // trader workspaces: SUPPORT may stop trouble without the member's grant; a member's workspace switches daily
  const canStop = (tenantId: number) => legacy || (tenants.get(tenantId)?.canStop ?? mayAct(tenantId));
  const daily = (tenantId: number) => tenants.get(tenantId)?.control === "DAILY_SWITCH";
  const several = tenants.size > 1;
  const legacyTenants = legacy ? [...new Set(rows.map((a) => a.tenantId))].map((id) => ({ id, name: String(id), role: "?",
    mayAct: true, manages: true })) : [];
  const acting = legacy ? legacyTenants : [...tenants.values()].filter((t) => t.canStop ?? t.mayAct);
  const managed = legacy ? legacyTenants : [...tenants.values()].filter((t) => t.manages && t.control !== "DAILY_SWITCH");
  // accounts whose own kill switch is engaged (entries and adds stop; exits run)
  const killedAccounts = new Set((switches.data ?? []).filter((k) => k.scope === "ACCOUNT" && k.accountId != null)
    .map((k) => k.accountId!));
  const ordered = orderAccounts(rows, (id) => [...tenants.keys()].indexOf(id));

  return (
    <div className="space-y-4">
      {me.data && !me.data.admin ? (
        <Panel title="Your access" right={s && (s.stale ? <Badge text="STALE" tone="down" /> : <Badge text="RUNNING" tone="up" />)}>
          <div className="space-y-1 text-sm">
            {me.data.tenants.map((t) => (
              <div key={t.id} className="flex flex-wrap gap-x-6 gap-y-1">
                <span><span className="text-muted">Role </span><span className="font-semibold">{t.role}</span></span>
                <span className="text-muted">{t.manages ? "you may change your accounts' capital and strategies, log in to "
                  + "the broker, stop and flatten them" : t.mayAct ? "you may log in to the broker, stop and flatten your "
                  + "accounts" : "you can see your accounts; changes are made by the admin"}</span>
                <span><span className="text-muted">Follows </span>{(t.follows ?? []).join(", ") || "nothing yet"}</span>
              </div>
            ))}
            <p className="text-xs text-muted">{me.data.tenants.some((t) => t.control === "DAILY_SWITCH")
              ? "Your day's broker and its PAPER/LIVE switch are on the Today page; brokers, keys and logins on the Brokers "
                + "page. Each broker account's paper twin (\"… paper\") holds what traded in PAPER. Open positions are "
                + "squared off at 15:20."
              : "PAPER and DRY_RUN only: no real orders. Open positions are squared off at 15:20."}</p>
          </div>
        </Panel>
      ) : (
      <Panel title="Execution worker" right={s && (s.stale ? <Badge text="STALE" tone="down" /> : <Badge text="TICKING" tone="up" />)}>
        {status.error ? <ErrorNote error={status.error} /> : !s ? <Loading what="worker" /> : (
          <div className="flex flex-wrap gap-x-8 gap-y-2 text-sm">
            <span><span className="text-muted">Worker </span><span className="num">{s.worker}</span></span>
            <span><span className="text-muted">Copy groups </span><span className="num">{s.copyGroup === "*" ? "all" : s.copyGroup}</span></span>
            <span><span className="text-muted">Session date </span><span className="num">{s.day}</span></span>
            <span><span className="text-muted">Last tick </span><span className="num">{time(s.lastTick)}</span></span>
            <span className="text-muted">PAPER and DRY_RUN only: LIVE is refused; DRY_RUN orders go to a local broker simulator.
              Open positions are squared off at 15:20 if the signal source sent no exit.</span>
          </div>
        )}
      </Panel>
      )}

      {message && (
        <div className={`rounded border px-3 py-2 text-sm ${message.ok ? "border-up/40 text-up" : "border-down/40 text-down"}`}>
          {message.text}
          <button className="ml-3 text-muted" onClick={() => setMessage(null)}>dismiss</button>
        </div>
      )}

      {me.data?.admin && <GlobalLivePanel onDone={(text, ok) => setMessage({ ok, text })} />}

      <Panel title="Follower accounts" right={<span className="text-xs text-muted">{rows.length} accounts</span>}>
        <FollowerSummary rows={rows} killed={killedAccounts} />
        <Table rows={ordered} rowStyle={(o) => (o.a.enabled || o.a.today == null || o.a.today.open > 0 ? undefined : { opacity: 0.55 })}
          before={(o, i) => several && (i === 0 || ordered[i - 1].a.tenantId !== o.a.tenantId)
            ? <GroupHeading name={tenants.get(o.a.tenantId)?.name ?? String(o.a.tenantId)} daily={daily(o.a.tenantId)}
                accounts={rows.filter((r) => r.tenantId === o.a.tenantId)} />
            : null}
          columns={[
          { key: "n", label: "Account", render: ({ a, twin }) => (
            <div className={`whitespace-nowrap ${twin ? "pl-4" : ""}`}>
              <div className={twin ? "text-muted" : "font-semibold"}>{twin && <span className="mr-1">↳</span>}{a.name}</div>
              <div className="text-[11px] text-muted">{twin ? "paper twin (PAPER-day fills)" : brokerName(a.broker)}</div>
            </div>
          ) },
          { key: "st", label: "Status", render: ({ a }) => (
            <div className="flex min-w-36 flex-wrap items-center gap-1">
              {a.mode === "LIVE" && daily(a.tenantId)
                ? <span title="May trade real money; whether today is LIVE is the workspace's switch on the Today page">
                    <Badge text="LIVE-ELIGIBLE" tone="down" /></span>
                : <Badge text={a.mode} tone={modeTone(a.mode)} />}
              {a.broker !== "PAPER" && <Badge text={connectionLabel(a.connection)} tone={connectionTone(a.connection)} />}
              {killedAccounts.has(a.id) && <Badge text="ENTRIES STOPPED" tone="down" />}
              {!a.enabled && <Badge text="DISABLED" tone="muted" />}
            </div>
          ) },
          { key: "cap", label: "Capital", align: "right", render: ({ a }) => rupees(a.allocatedCapital) },
          { key: "d", label: "Today", align: "right", render: ({ a }) => <TodayCell a={a} /> },
          { key: "e", label: "Engine", render: ({ a }) => a.lastTurn
              ? <span className="whitespace-nowrap" title={`lease epoch ${a.lastTurn.leaseEpoch ?? "–"}`}>
                  <Badge text={a.lastTurn.state} tone={stateTone(a.lastTurn.state)} />
                  <span className="ml-1.5 text-[11px] text-muted">{time(a.lastTurn.lastRun)}</span></span>
              : <span className="text-xs text-muted">{a.enabled ? "not served" : "–"}</span> },
          { key: "r", label: "Route · last result", render: ({ a }) => (
            <div className="max-w-md text-xs" title={[a.lastTurn?.route, a.lastTurn?.lastResult].filter(Boolean).join("\n")}>
              <div className="line-clamp-2">{a.lastTurn?.route ?? (a.enabled ? "no route: the worker does not serve it now"
                : "disabled")}</div>
              {a.lastTurn?.lastResult && <div className="line-clamp-2 text-muted">{a.lastTurn.lastResult}</div>}
            </div>
          ) },
          { key: "a", label: "", render: ({ a }) => (
            <div className="flex flex-nowrap justify-end gap-1.5 whitespace-nowrap">
              <button className="rounded border border-accent/60 px-2 py-0.5 text-xs text-accent hover:bg-accent/10"
                onClick={() => setOpenAccount(openAccount === a.id ? null : a.id)}>
                {openAccount === a.id ? "Close" : manages(a.tenantId) ? "Manage" : "Details"}</button>
              {mayAct(a.tenantId) && a.broker === "ZERODHA" && a.connection !== "ACTIVE" && (
                <a className="whitespace-nowrap rounded border border-line px-2 py-0.5 text-xs hover:bg-panel-2" target="_blank"
                  rel="noreferrer" href={`/api/execution/accounts/${a.id}/kite/login`}>Log in to Kite</a>
              )}
              {/* a disabled account holding nothing can neither enter nor hold: no stop controls to misclick */}
              {canStop(a.tenantId) && (a.enabled || a.today == null || a.today.open > 0) && <>
              {!killedAccounts.has(a.id) && (
              <button className="rounded border border-warn/60 px-2 py-0.5 text-xs text-warn hover:bg-warn/10" disabled={act.isPending}
                title="Stop new entries and adds on this account (exits still run)"
                onClick={() => {
                  const reason = window.prompt(`Stop new entries on ${a.name}: reason`);
                  if (reason) act.mutate({ path: "/api/execution/kill-switches",
                    body: { scope: "ACCOUNT", accountId: a.id, reason }, done: `${a.name}: entries stopped` });
                }}>Kill</button>
              )}
              <button className="rounded border border-down/60 px-2 py-0.5 text-xs text-down hover:bg-down/10"
                title="Stop entries, take broker stops off and sell every open position now"
                onClick={() => setFlattenFor(flattenFor === a.id ? null : a.id)}>Flatten…</button>
              </>}
            </div>
          ) },
        ]} />
        {flattenFor != null && (
          <div className="mt-3 rounded border border-down/40 p-3">
            <p className="mb-2 text-sm">
              Flatten <span className="font-semibold">{names.get(flattenFor)}</span>: stops its entries (kill switch), takes
              each position's broker stop off, then sells every open position now.
            </p>
            <TypedConfirm word="FLATTEN" label="Flatten account" busy={act.isPending} onConfirm={(reason) =>
              act.mutate({ path: `/api/execution/accounts/${flattenFor}/flatten`, body: { confirm: "FLATTEN", reason },
                done: `${names.get(flattenFor)} flattened` })} />
          </div>
        )}
      </Panel>

      {openAccount != null && (
        <AccountDetailPanel key={openAccount} accountId={openAccount} onClose={() => setOpenAccount(null)}
          canManage={manages(accounts.data!.find((a) => a.id === openAccount)?.tenantId ?? -1)}
          canAct={mayAct(accounts.data!.find((a) => a.id === openAccount)?.tenantId ?? -1)}
          dailySwitch={daily(accounts.data!.find((a) => a.id === openAccount)?.tenantId ?? -1)}
          onDone={(text, ok) => setMessage({ ok, text })} />
      )}

      {managed.length > 0 && <AddAccountPanel tenants={managed} onDone={(text, ok) => setMessage({ ok, text })} />}

      {me.data?.admin && <p className="text-sm text-muted">Family members and their workspaces: the Family page.</p>}

      {acting.length > 0 && <>
      <KillSwitchPanel switches={switches} accounts={rows.filter((a) => mayAct(a.tenantId))} names={names} busy={act.isPending}
        tenants={acting} admin={me.data?.admin ?? legacy} canRelease={(k) => k.tenantId == null ? (me.data?.admin ?? legacy)
          : mayAct(k.tenantId)}
        onEngage={(body) => act.mutate({ path: "/api/execution/kill-switches", body, done: "kill switch engaged" })}
        onRelease={(id) => act.mutate({ path: `/api/execution/kill-switches/${id}/release`, body: {}, done: `switch ${id} released` })} />

      <Panel title="Flatten all">
        <p className="mb-2 text-sm text-muted">
          Every follower account you may act on{several ? ` (${acting.map((t) => t.name).join(", ")})` : ""}: kill switch,
          broker stops off, every open position sold.
        </p>
        <TypedConfirm word="FLATTEN ALL" label="Flatten all accounts" busy={act.isPending} onConfirm={(reason) =>
          act.mutate({ path: "/api/execution/flatten-all", body: { confirm: "FLATTEN ALL", reason }, done: "flatten all done" })} />
      </Panel>
      </>}

      <Panel title="Audit log" right={
        <select className="rounded border border-line bg-bg px-2 py-1 text-xs" value={auditAccount}
          onChange={(e) => setAuditAccount(e.target.value === "" ? "" : Number(e.target.value))}>
          <option value="">all accounts</option>
          {rows.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
        </select>
      }>
        {audit.error ? <ErrorNote error={audit.error} /> : (
          <Table rows={audit.data ?? []} empty="No actions yet." columns={[
            { key: "t", label: "At", render: (r) => <span className="num text-xs">{dateTime(r.at)}</span> },
            { key: "a", label: "Account", render: (r) => r.broker_account_id == null ? "–" : names.get(r.broker_account_id) ?? r.broker_account_id },
            { key: "w", label: "By", render: (r) => <span className="text-xs">{r.actor}</span> },
            { key: "x", label: "Action", render: (r) => <span className="font-semibold">{r.action}</span> },
            { key: "d", label: "Detail", render: (r) => <span className="num break-all text-xs text-muted">{r.detail}</span> },
          ]} />
        )}
      </Panel>
    </div>
  );
}

function KillSwitchPanel({ switches, accounts, names, busy, tenants, admin, canRelease, onEngage, onRelease }: {
  switches: { data?: KillSwitch[]; error: unknown; isLoading: boolean };
  accounts: ExecutionAccount[];
  names: Map<number, string>;
  busy: boolean;
  /** Tenants the caller may act for: a strategy or contract switch names one when there are several. */
  tenants: ExecMe["tenants"];
  admin: boolean;
  /** Whether the caller may release this switch (its tenant's actor; a tenant-less one: an admin). */
  canRelease: (k: KillSwitch) => boolean;
  onEngage: (body: Record<string, unknown>) => void;
  onRelease: (id: number) => void;
}) {
  const [scope, setScope] = useState<"ACCOUNT" | "STRATEGY" | "INSTRUMENT" | "GLOBAL_LIVE">("ACCOUNT");
  const [accountId, setAccountId] = useState<number | "">("");
  const [target, setTarget] = useState("");
  const [reason, setReason] = useState("");
  const [tenantId, setTenantId] = useState<number | "all" | "">(tenants.length === 1 ? tenants[0].id : "");
  const needsTenant = (scope === "STRATEGY" || scope === "INSTRUMENT") && tenants.length > 1;
  const ready = reason.trim() !== "" && (!needsTenant || tenantId !== "")
    && (scope === "GLOBAL_LIVE" || (scope === "ACCOUNT" ? accountId !== "" : target.trim() !== ""));
  return (
    <Panel title="Kill switches" right={<span className="text-xs text-muted">entries and adds stop; exits never</span>}>
      {switches.error ? <ErrorNote error={switches.error} /> : (
        <Table rows={switches.data ?? []} empty="None engaged." columns={[
          { key: "s", label: "Scope", render: (k) => <Badge text={k.scope} tone="down" /> },
          { key: "t", label: "On", render: (k) => k.scope === "ACCOUNT" ? names.get(k.accountId!) ?? k.accountId
              : k.strategyId ?? k.instrumentKey ?? "every LIVE account" },
          { key: "r", label: "Reason", render: (k) => <span className="text-xs">{k.reason}</span> },
          { key: "b", label: "By", render: (k) => <span className="text-xs">{k.engagedBy} · {dateTime(k.engagedAt)}</span> },
          { key: "x", label: "", render: (k) => canRelease(k) ? (
            <button className="rounded border border-line px-2 py-0.5 text-xs hover:bg-panel-2" disabled={busy}
              onClick={() => onRelease(k.id)}>Release</button>
          ) : null },
        ]} />
      )}
      <div className="mt-3 flex flex-wrap items-center gap-2 border-t border-line pt-3">
        <select className="rounded border border-line bg-bg px-2 py-1 text-sm" value={scope}
          onChange={(e) => setScope(e.target.value as typeof scope)}>
          <option value="ACCOUNT">Account</option>
          <option value="STRATEGY">Strategy (all your accounts)</option>
          <option value="INSTRUMENT">Contract (all your accounts)</option>
          {admin && <option value="GLOBAL_LIVE">Every LIVE account (admin)</option>}
        </select>
        {scope === "ACCOUNT" && (
          <select className="rounded border border-line bg-bg px-2 py-1 text-sm" value={accountId}
            onChange={(e) => setAccountId(e.target.value === "" ? "" : Number(e.target.value))}>
            <option value="">account…</option>
            {accounts.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
          </select>
        )}
        {(scope === "STRATEGY" || scope === "INSTRUMENT") && (
          <input className="w-56 rounded border border-line bg-bg px-2 py-1 text-sm" value={target}
            placeholder={scope === "STRATEGY" ? "strategy id, e.g. opening-drive" : "contract key, e.g. NSE_FO|47168"}
            onChange={(e) => setTarget(e.target.value)} />
        )}
        {needsTenant && (
          <select className="rounded border border-line bg-bg px-2 py-1 text-sm" value={tenantId}
            onChange={(e) => setTenantId(e.target.value === "" || e.target.value === "all" ? e.target.value as "" | "all"
              : Number(e.target.value))}>
            <option value="">whose accounts…</option>
            <option value="all">everyone's ({tenants.length})</option>
            {tenants.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}
          </select>
        )}
        <input className="w-56 rounded border border-line bg-bg px-2 py-1 text-sm" placeholder="reason" value={reason}
          onChange={(e) => setReason(e.target.value)} />
        <button className="rounded border border-down/60 px-3 py-1 text-sm font-semibold text-down hover:bg-down/10 disabled:opacity-40"
          disabled={!ready || busy}
          onClick={() => {
            onEngage({
              scope,
              accountId: scope === "ACCOUNT" ? accountId : null,
              strategyId: scope === "STRATEGY" ? target.trim() : null,
              instrumentKey: scope === "INSTRUMENT" ? target.trim() : null,
              reason: reason.trim(),
              tenantId: needsTenant && tenantId !== "all" ? tenantId : null,
              allTenants: needsTenant && tenantId === "all",
            });
            setReason("");
          }}>Engage</button>
      </div>
    </Panel>
  );
}
