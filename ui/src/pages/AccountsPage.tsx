import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import {
  get, postJson, type AuditRow, type ExecutionAccount, type KillSwitch, type WorkerStatus,
} from "../api";
import { ErrorNote, Loading, Panel, Table } from "../components/ui";

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
  const [auditAccount, setAuditAccount] = useState<number | "">("");
  const audit = useQuery({
    queryKey: ["exec-audit", auditAccount],
    queryFn: () => get<AuditRow[]>(`/api/execution/audit${auditAccount === "" ? "" : `?account=${auditAccount}`}`),
    refetchInterval: 10000,
  });
  const [flattenFor, setFlattenFor] = useState<number | null>(null);
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

  return (
    <div className="space-y-4">
      <Panel title="Execution worker" right={s && (s.stale ? <Badge text="STALE" tone="down" /> : <Badge text="TICKING" tone="up" />)}>
        {status.error ? <ErrorNote error={status.error} /> : !s ? <Loading what="worker" /> : (
          <div className="flex flex-wrap gap-x-8 gap-y-2 text-sm">
            <span><span className="text-muted">Worker </span><span className="num">{s.worker}</span></span>
            <span><span className="text-muted">Copy groups </span><span className="num">{s.copyGroup === "*" ? "all" : s.copyGroup}</span></span>
            <span><span className="text-muted">Session date </span><span className="num">{s.day}</span></span>
            <span><span className="text-muted">Last tick </span><span className="num">{time(s.lastTick)}</span></span>
            <span className="text-muted">PAPER and DRY_RUN only: LIVE is refused; DRY_RUN orders go to a local broker simulator.</span>
          </div>
        )}
      </Panel>

      {message && (
        <div className={`rounded border px-3 py-2 text-sm ${message.ok ? "border-up/40 text-up" : "border-down/40 text-down"}`}>
          {message.text}
          <button className="ml-3 text-muted" onClick={() => setMessage(null)}>dismiss</button>
        </div>
      )}

      <Panel title="Follower accounts" right={<span className="text-xs text-muted">{rows.length} accounts</span>}>
        <Table rows={rows} columns={[
          { key: "n", label: "Account", render: (a) => <span className="font-semibold">{a.name}</span> },
          { key: "b", label: "Broker", render: (a) => a.broker },
          { key: "m", label: "Mode", render: (a) => <Badge text={a.mode} tone={modeTone(a.mode)} /> },
          { key: "c", label: "Session", render: (a) => a.broker === "PAPER" ? <span className="text-muted">–</span>
              : <Badge text={a.connection ?? "?"} tone={connectionTone(a.connection)} /> },
          { key: "cap", label: "Allocation", align: "right", render: (a) => rupees(a.allocatedCapital) },
          { key: "r", label: "Route", render: (a) => <span className="text-xs">{a.lastTurn?.route ?? (a.enabled ? "not served" : "disabled")}</span> },
          { key: "s", label: "Turn", render: (a) => a.lastTurn
              ? <span title={a.lastTurn.lastResult}><Badge text={a.lastTurn.state} tone={stateTone(a.lastTurn.state)} />
                  <span className="ml-2 text-xs text-muted">{time(a.lastTurn.lastRun)} · epoch {a.lastTurn.leaseEpoch ?? "–"}</span></span>
              : <span className="text-muted">–</span> },
          { key: "res", label: "Last result", render: (a) => <span className="text-xs text-muted">{a.lastTurn?.lastResult ?? ""}</span> },
          { key: "a", label: "", render: (a) => (
            <div className="flex flex-wrap gap-1.5">
              {a.broker !== "PAPER" && a.connection !== "ACTIVE" && (
                <button className="rounded border border-line px-2 py-0.5 text-xs hover:bg-panel-2" disabled={act.isPending}
                  onClick={() => act.mutate({ path: `/api/execution/accounts/${a.id}/session`, body: { status: "ACTIVE" },
                    done: `${a.name} marked logged in` })}>Logged in</button>
              )}
              <button className="rounded border border-warn/60 px-2 py-0.5 text-xs text-warn hover:bg-warn/10" disabled={act.isPending}
                onClick={() => {
                  const reason = window.prompt(`Stop new entries on ${a.name}: reason`);
                  if (reason) act.mutate({ path: "/api/execution/kill-switches",
                    body: { scope: "ACCOUNT", accountId: a.id, reason }, done: `${a.name}: entries stopped` });
                }}>Kill</button>
              <button className="rounded border border-down/60 px-2 py-0.5 text-xs text-down hover:bg-down/10"
                onClick={() => setFlattenFor(flattenFor === a.id ? null : a.id)}>Flatten…</button>
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

      <KillSwitchPanel switches={switches} accounts={rows} names={names} busy={act.isPending}
        onEngage={(body) => act.mutate({ path: "/api/execution/kill-switches", body, done: "kill switch engaged" })}
        onRelease={(id) => act.mutate({ path: `/api/execution/kill-switches/${id}/release`, body: {}, done: `switch ${id} released` })} />

      <Panel title="Flatten all">
        <p className="mb-2 text-sm text-muted">
          Every follower account you may act on: kill switch, broker stops off, every open position sold.
        </p>
        <TypedConfirm word="FLATTEN ALL" label="Flatten all accounts" busy={act.isPending} onConfirm={(reason) =>
          act.mutate({ path: "/api/execution/flatten-all", body: { confirm: "FLATTEN ALL", reason }, done: "flatten all done" })} />
      </Panel>

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

function KillSwitchPanel({ switches, accounts, names, busy, onEngage, onRelease }: {
  switches: { data?: KillSwitch[]; error: unknown; isLoading: boolean };
  accounts: ExecutionAccount[];
  names: Map<number, string>;
  busy: boolean;
  onEngage: (body: Record<string, unknown>) => void;
  onRelease: (id: number) => void;
}) {
  const [scope, setScope] = useState<"ACCOUNT" | "STRATEGY" | "INSTRUMENT" | "GLOBAL_LIVE">("ACCOUNT");
  const [accountId, setAccountId] = useState<number | "">("");
  const [target, setTarget] = useState("");
  const [reason, setReason] = useState("");
  const ready = reason.trim() !== "" && (scope === "GLOBAL_LIVE" || (scope === "ACCOUNT" ? accountId !== "" : target.trim() !== ""));
  return (
    <Panel title="Kill switches" right={<span className="text-xs text-muted">entries and adds stop; exits never</span>}>
      {switches.error ? <ErrorNote error={switches.error} /> : (
        <Table rows={switches.data ?? []} empty="None engaged." columns={[
          { key: "s", label: "Scope", render: (k) => <Badge text={k.scope} tone="down" /> },
          { key: "t", label: "On", render: (k) => k.scope === "ACCOUNT" ? names.get(k.accountId!) ?? k.accountId
              : k.strategyId ?? k.instrumentKey ?? "every LIVE account" },
          { key: "r", label: "Reason", render: (k) => <span className="text-xs">{k.reason}</span> },
          { key: "b", label: "By", render: (k) => <span className="text-xs">{k.engagedBy} · {dateTime(k.engagedAt)}</span> },
          { key: "x", label: "", render: (k) => (
            <button className="rounded border border-line px-2 py-0.5 text-xs hover:bg-panel-2" disabled={busy}
              onClick={() => onRelease(k.id)}>Release</button>
          ) },
        ]} />
      )}
      <div className="mt-3 flex flex-wrap items-center gap-2 border-t border-line pt-3">
        <select className="rounded border border-line bg-bg px-2 py-1 text-sm" value={scope}
          onChange={(e) => setScope(e.target.value as typeof scope)}>
          <option value="ACCOUNT">Account</option>
          <option value="STRATEGY">Strategy (all your accounts)</option>
          <option value="INSTRUMENT">Contract (all your accounts)</option>
          <option value="GLOBAL_LIVE">Every LIVE account (admin)</option>
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
            });
            setReason("");
          }}>Engage</button>
      </div>
    </Panel>
  );
}
