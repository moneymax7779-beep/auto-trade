import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useMemo, useState } from "react";
import {
  get, postJson, type AccountDetails, type CredentialProfile, type ExecPosition, type PnlDay,
} from "../api";
import { ErrorNote, Loading, Panel, Table } from "../components/ui";

// Account management (user, 24 Oct 2026): add a follower account and change its capital, loss limit, mode and
// strategies; its Kite login, today's positions and its daily P&L. Every change goes through the execution workers'
// API (OWNER / ADMIN only, audited). LIVE is never switched here.

const rupees = (v: number | null | undefined) => v == null ? "–" : `₹${Math.round(v).toLocaleString("en-IN")}`;
const input = "rounded border border-line bg-bg px-2 py-1 text-sm";
const button = "rounded border border-line px-3 py-1 text-sm hover:bg-panel-2 disabled:opacity-40";
const primary = "rounded bg-accent px-3 py-1 text-sm font-semibold text-white disabled:opacity-40";

/** Strategy ids a follower can take: the live set (trading-core) plus what the copy group published or has subscribed. */
function useStrategyIds() {
  const live = useQuery({ queryKey: ["strategies-live"],
    queryFn: () => get<{ live: { strategy: string; version: string }[] }>("/api/strategies") });
  const published = useQuery({ queryKey: ["exec-strategy-ids"], queryFn: () => get<string[]>("/api/execution/strategy-ids") });
  return useMemo(() => {
    const versions = new Map<string, string>();
    (live.data?.live ?? []).forEach((s) => versions.set(s.strategy, s.version));
    const ids = new Set<string>([...versions.keys(), ...(published.data ?? [])]);
    return [...ids].sort().map((id) => ({ id, version: versions.get(id) }));
  }, [live.data, published.data]);
}

function StrategyPicker({ value, onChange }: { value: string[]; onChange: (v: string[]) => void }) {
  const ids = useStrategyIds();
  return (
    <div className="flex flex-wrap gap-x-4 gap-y-1">
      {ids.length === 0 && <span className="text-xs text-muted">no strategies known yet</span>}
      {ids.map((s) => (
        <label key={s.id} className="flex items-center gap-1.5 text-sm">
          <input type="checkbox" checked={value.includes(s.id)}
            onChange={(e) => onChange(e.target.checked ? [...value, s.id] : value.filter((v) => v !== s.id))} />
          {s.id}{s.version && <span className="text-xs text-muted">({s.version}, live)</span>}
        </label>
      ))}
    </div>
  );
}

function Field({ label, hint, children }: { label: string; hint?: string; children: React.ReactNode }) {
  return (
    <label className="flex flex-col gap-1 text-xs text-muted">
      <span>{label}</span>
      {children}
      {hint && <span className="text-[11px]">{hint}</span>}
    </label>
  );
}

const amount = (s: string) => {
  const n = Number(s.replace(/[,₹\s]/g, ""));
  return s.trim() === "" || !Number.isFinite(n) ? null : n;
};

export function AddAccountPanel({ onDone }: { onDone: (text: string, ok: boolean) => void }) {
  const client = useQueryClient();
  const profiles = useQuery({ queryKey: ["exec-profiles"],
    queryFn: () => get<CredentialProfile[]>("/api/execution/credential-profiles") });
  const [open, setOpen] = useState(false);
  const [name, setName] = useState("");
  const [broker, setBroker] = useState<"ZERODHA" | "PAPER">("ZERODHA");
  const [clientId, setClientId] = useState("");
  const [profile, setProfile] = useState("");
  const [allocation, setAllocation] = useState("");
  const [equityMode, setEquityMode] = useState("FIXED");
  const [loss, setLoss] = useState("");
  const [strategies, setStrategies] = useState<string[]>([]);
  const [enabled, setEnabled] = useState(true);
  const zerodhaProfiles = (profiles.data ?? []).filter((p) => p.broker_type === "ZERODHA" && p.status === "ACTIVE");
  useEffect(() => {
    if (profile === "" && zerodhaProfiles.length === 1) setProfile(zerodhaProfiles[0].name);
  }, [zerodhaProfiles, profile]);

  const create = useMutation({
    mutationFn: () => postJson<{ id: number; name: string }>("/api/execution/accounts", {
      name: name.trim(), broker, mode: broker === "PAPER" ? "PAPER" : "DRY_RUN",
      clientId: broker === "ZERODHA" ? clientId.trim() : null, credentialProfile: broker === "ZERODHA" ? profile || null : null,
      allocation: amount(allocation), equityMode, dailyLossLimit: amount(loss), strategies, enabled,
    }),
    onSuccess: (r) => {
      onDone(`${r.name} created (${broker === "PAPER" ? "PAPER" : "DRY_RUN"})`, true);
      setOpen(false); setName(""); setClientId(""); setAllocation(""); setLoss(""); setStrategies([]);
      client.invalidateQueries({ queryKey: ["exec-accounts"] });
    },
    onError: (e) => onDone(e instanceof Error ? e.message : String(e), false),
  });
  const alloc = amount(allocation);
  const lossValue = amount(loss);
  const ready = name.trim().length >= 2 && alloc != null && alloc > 0 && (broker === "PAPER" || clientId.trim().length >= 6)
    && (lossValue == null || (lossValue > 0 && alloc != null && lossValue <= alloc));

  return (
    <Panel title="Add account" right={<button className={button} onClick={() => setOpen(!open)}>{open ? "Close" : "Add account…"}</button>}>
      {!open ? (
        <p className="text-sm text-muted">A follower account takes the chosen strategies' signals from the live PAPER set and
          trades them with its own allocation. New Zerodha accounts start in DRY_RUN (orders go to a local simulator); LIVE is
          a separate step and is not available here.</p>
      ) : (
        <div className="space-y-3">
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <Field label="Account name"><input className={input} value={name} placeholder="e.g. ZERODHA-2"
              onChange={(e) => setName(e.target.value)} /></Field>
            <Field label="Broker">
              <select className={input} value={broker} onChange={(e) => setBroker(e.target.value as "ZERODHA" | "PAPER")}>
                <option value="ZERODHA">Zerodha (DRY_RUN)</option>
                <option value="PAPER">Paper (simulated fills)</option>
              </select>
            </Field>
            {broker === "ZERODHA" && <>
              <Field label="Zerodha client id" hint="the daily Kite login must come from it">
                <input className={input} value={clientId} placeholder="AB1234"
                  onChange={(e) => setClientId(e.target.value.toUpperCase())} /></Field>
              <Field label="Kite app">
                <select className={input} value={profile} onChange={(e) => setProfile(e.target.value)}>
                  {zerodhaProfiles.length !== 1 && <option value="">choose…</option>}
                  {zerodhaProfiles.map((p) => <option key={p.id} value={p.name}>{p.name}</option>)}
                </select>
              </Field>
            </>}
            <Field label="Allocation (₹)" hint="the most it ever uses, whatever the broker holds">
              <input className={input} value={allocation} placeholder="200000" inputMode="numeric"
                onChange={(e) => setAllocation(e.target.value)} /></Field>
            <Field label="Equity">
              <select className={input} value={equityMode} onChange={(e) => setEquityMode(e.target.value)}>
                <option value="FIXED">Fixed (the allocation)</option>
                <option value="COMPOUNDING">Compounding (adds realised P&amp;L)</option>
              </select>
            </Field>
            <Field label="Daily loss limit (₹)" hint="new entries stop for the day once reached; blank = risk file %">
              <input className={input} value={loss} placeholder="20000" inputMode="numeric"
                onChange={(e) => setLoss(e.target.value)} /></Field>
            <Field label="Enabled">
              <label className="flex items-center gap-1.5 text-sm text-text">
                <input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} /> follow signals
              </label>
            </Field>
          </div>
          <Field label="Strategies"><StrategyPicker value={strategies} onChange={setStrategies} /></Field>
          <div className="flex items-center gap-2">
            <button className={primary} disabled={!ready || create.isPending} onClick={() => create.mutate()}>
              {create.isPending ? "Creating…" : "Create account"}</button>
            {lossValue != null && alloc != null && lossValue > alloc &&
              <span className="text-xs text-down">the loss limit cannot exceed the allocation</span>}
          </div>
        </div>
      )}
    </Panel>
  );
}

export function AccountDetailPanel({ accountId, onClose, onDone }: {
  accountId: number; onClose: () => void; onDone: (text: string, ok: boolean) => void;
}) {
  const client = useQueryClient();
  const details = useQuery({ queryKey: ["exec-details", accountId], refetchInterval: 10000,
    queryFn: () => get<AccountDetails>(`/api/execution/accounts/${accountId}/details`) });
  const [day, setDay] = useState("");
  const positions = useQuery({ queryKey: ["exec-positions", accountId, day], refetchInterval: 10000,
    queryFn: () => get<ExecPosition[]>(`/api/execution/accounts/${accountId}/positions${day ? `?day=${day}` : ""}`) });
  const pnl = useQuery({ queryKey: ["exec-pnl", accountId], refetchInterval: 30000,
    queryFn: () => get<PnlDay[]>(`/api/execution/accounts/${accountId}/pnl?days=30`) });

  const d = details.data;
  const [form, setForm] = useState<{ allocation: string; equityMode: string; loss: string; mode: string; enabled: boolean;
    clientId: string; strategies: string[] } | null>(null);
  useEffect(() => {
    if (d && form == null) {
      setForm({ allocation: String(d.allocatedCapital), equityMode: d.equityMode, loss: d.dailyLossLimit == null ? "" : String(d.dailyLossLimit),
        mode: d.mode, enabled: d.enabled, clientId: d.clientId ?? "",
        strategies: d.strategies.filter((s) => s.enabled).map((s) => s.strategy_id) });
    }
  }, [d, form]);

  const save = useMutation({
    mutationFn: () => {
      if (!d || !form) throw new Error("not loaded");
      const body: Record<string, unknown> = {};
      const alloc = amount(form.allocation);
      if (alloc != null && alloc !== d.allocatedCapital) body.allocation = alloc;
      if (form.equityMode !== d.equityMode) body.equityMode = form.equityMode;
      const loss = amount(form.loss);
      if (form.loss.trim() === "" && d.dailyLossLimit != null) body.clearDailyLossLimit = true;
      else if (loss != null && loss !== d.dailyLossLimit) body.dailyLossLimit = loss;
      if (form.mode !== d.mode) body.mode = form.mode;
      if (form.enabled !== d.enabled) body.enabled = form.enabled;
      if (d.broker === "ZERODHA" && form.clientId.trim() !== (d.clientId ?? "")) body.clientId = form.clientId.trim();
      const before = d.strategies.filter((s) => s.enabled).map((s) => s.strategy_id).sort().join(",");
      if ([...form.strategies].sort().join(",") !== before) body.strategies = form.strategies;
      if (Object.keys(body).length === 0) throw new Error("nothing changed");
      return postJson<AccountDetails>(`/api/execution/accounts/${accountId}/settings`, body);
    },
    onSuccess: (r) => {
      onDone(`${r.name}: settings saved`, true);
      setForm(null);
      ["exec-details", "exec-accounts", "exec-audit"].forEach((k) => client.invalidateQueries({ queryKey: [k] }));
    },
    onError: (e) => onDone(e instanceof Error ? e.message : String(e), false),
  });

  if (details.isLoading || !form) return <Panel title="Account"><Loading what="account" /></Panel>;
  if (details.error) return <Panel title="Account"><ErrorNote error={details.error} /></Panel>;
  const a = d!;
  const total30 = (pnl.data ?? []).reduce((sum, r) => sum + Number(r.net ?? 0), 0);
  const kite = a.broker === "ZERODHA";
  const sessionActive = a.session?.status === "ACTIVE";

  return (
    <Panel title={<span>Account <span className="font-semibold">{a.name}</span></span>}
      right={<button className={button} onClick={onClose}>Close</button>}>
      <div className="space-y-4">
        <div className="flex flex-wrap items-center gap-x-6 gap-y-2 text-sm">
          <span><span className="text-muted">Broker </span>{a.broker}{a.clientId ? ` · ${a.clientId}` : ""}
            {a.credentialProfile ? <span className="text-muted"> · app {a.credentialProfile}</span> : null}</span>
          <span><span className="text-muted">Today </span>{a.today.open} open · {a.today.closed} closed ·{" "}
            <span className={Number(a.today.net) >= 0 ? "text-up" : "text-down"}>{rupees(Number(a.today.net))}</span></span>
          {kite && <span><span className="text-muted">Kite session </span>
            <span className={sessionActive ? "text-up" : "text-down"}>{sessionActive ? `active until ${a.session!.expires}` : "not logged in"}</span>
          </span>}
          {kite && <a className={primary} href={`/api/execution/accounts/${a.id}/kite/login`} target="_blank" rel="noreferrer">
            {sessionActive ? "Log in to Kite again" : "Log in to Kite"}</a>}
        </div>

        <div className="rounded border border-line p-3">
          <div className="mb-2 text-xs font-semibold uppercase tracking-wide text-muted">Settings</div>
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <Field label="Allocation (₹)"><input className={input} value={form.allocation} inputMode="numeric"
              onChange={(e) => setForm({ ...form, allocation: e.target.value })} /></Field>
            <Field label="Equity">
              <select className={input} value={form.equityMode} onChange={(e) => setForm({ ...form, equityMode: e.target.value })}>
                <option value="FIXED">Fixed</option><option value="COMPOUNDING">Compounding</option>
              </select>
            </Field>
            <Field label="Daily loss limit (₹)" hint="blank = the risk file's %">
              <input className={input} value={form.loss} inputMode="numeric"
                onChange={(e) => setForm({ ...form, loss: e.target.value })} /></Field>
            <Field label="Mode" hint="LIVE is a separate step">
              <select className={input} value={form.mode} onChange={(e) => setForm({ ...form, mode: e.target.value })}
                disabled={a.broker === "PAPER" || a.mode === "LIVE"}>
                {a.broker !== "PAPER" && <option value="DRY_RUN">DRY_RUN (broker simulator)</option>}
                <option value="PAPER">PAPER (simulated fills)</option>
                {a.mode === "LIVE" && <option value="LIVE">LIVE</option>}
              </select>
            </Field>
            {kite && <Field label="Zerodha client id"><input className={input} value={form.clientId}
              onChange={(e) => setForm({ ...form, clientId: e.target.value.toUpperCase() })} /></Field>}
            <Field label="Enabled">
              <label className="flex items-center gap-1.5 text-sm text-text">
                <input type="checkbox" checked={form.enabled} onChange={(e) => setForm({ ...form, enabled: e.target.checked })} />
                follow signals</label>
            </Field>
          </div>
          <div className="mt-3"><Field label="Strategies">
            <StrategyPicker value={form.strategies} onChange={(v) => setForm({ ...form, strategies: v })} /></Field></div>
          <div className="mt-3 flex flex-wrap items-center gap-2">
            <button className={primary} disabled={save.isPending} onClick={() => save.mutate()}>
              {save.isPending ? "Saving…" : "Save settings"}</button>
            <button className={button} onClick={() => setForm(null)}>Reset</button>
            <span className="text-xs text-muted">Applies from the account's next turn (about a second); sizing uses the new
              allocation for new entries only.</span>
          </div>
        </div>

        <div>
          <div className="mb-2 flex items-center gap-2">
            <span className="text-xs font-semibold uppercase tracking-wide text-muted">Positions</span>
            <input type="date" className={input} value={day} onChange={(e) => setDay(e.target.value)} />
            {day && <button className="text-xs text-muted" onClick={() => setDay("")}>today</button>}
          </div>
          {positions.error ? <ErrorNote error={positions.error} /> : (
            <Table rows={positions.data ?? []} empty="No positions that day." columns={[
              { key: "s", label: "Strategy", render: (p) => p.strategy_id },
              { key: "c", label: "Contract", render: (p) => <span className="num">{p.symbol}</span> },
              { key: "o", label: "Opened", render: (p) => <span className="num text-xs">{p.opened}</span> },
              { key: "x", label: "Closed", render: (p) => <span className="num text-xs">{p.closed ?? "–"}</span> },
              { key: "e", label: "Entry", align: "right", render: (p) => <span className="num">{p.average_entry.toFixed(2)}</span> },
              { key: "q", label: "Open qty", align: "right", render: (p) => <span className="num">{p.quantity_open}</span> },
              { key: "r", label: "Exit", render: (p) => <span className="text-xs">{p.exit_reason ?? p.status}</span> },
              { key: "n", label: "Net", align: "right", render: (p) => p.realised_gross == null ? "–" : (
                <span className={`num ${p.realised_gross - (p.charges ?? 0) >= 0 ? "text-up" : "text-down"}`}>
                  {rupees(p.realised_gross - (p.charges ?? 0))}</span>) },
            ]} />
          )}
        </div>

        <div>
          <div className="mb-2 text-xs font-semibold uppercase tracking-wide text-muted">
            Daily P&amp;L, last 30 days · total <span className={total30 >= 0 ? "text-up" : "text-down"}>{rupees(total30)}</span>
          </div>
          {pnl.error ? <ErrorNote error={pnl.error} /> : (
            <Table rows={pnl.data ?? []} empty="No closed trades yet." columns={[
              { key: "d", label: "Day", render: (r) => <span className="num">{r.day}</span> },
              { key: "t", label: "Trades", align: "right", render: (r) => <span className="num">{r.trades}</span> },
              { key: "w", label: "Won", align: "right", render: (r) => <span className="num">{r.wins}</span> },
              { key: "n", label: "Net", align: "right", render: (r) => (
                <span className={`num ${Number(r.net) >= 0 ? "text-up" : "text-down"}`}>{rupees(Number(r.net))}</span>) },
            ]} />
          )}
        </div>
      </div>
    </Panel>
  );
}
