import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import {
  BROKER_NAMES, get, postJson, type BrokerApp, type BrokerApps, type LoginResult, type WorkspaceBroker, type WorkspaceToday,
} from "../api";
import { ErrorNote, Loading, Panel, Table } from "../components/ui";
import { SupportBanner, useWorkspace } from "../workspace";
import { AccountDetailPanel } from "./AccountManagement";
import { loggedIn, loginUrl } from "./TodayPage";

// Brokers (trader workspaces, user 7 Nov 2026: "dedicated users and broker management in the trader account only"): the
// member's own broker accounts (Zerodha, Upstox, Kotak Neo, Groww), their broker apps with write-only keys, the daily
// login, and the admin's one-time LIVE eligibility. Nothing typed here is ever shown again.

const input = "rounded border border-line bg-bg px-2 py-1 text-sm";
const button = "rounded border border-line px-3 py-1 text-sm hover:bg-panel-2 disabled:opacity-40";
const primary = "rounded bg-accent px-3 py-1 text-sm font-semibold text-white disabled:opacity-40";
const BROKERS = ["ZERODHA", "UPSTOX", "KOTAK_NEO", "GROWW"] as const;
const when = (iso: string | null | undefined) => iso ? new Date(iso).toLocaleString("en-IN", { timeZone: "Asia/Kolkata",
  hour12: false, day: "2-digit", month: "short", hour: "2-digit", minute: "2-digit" }) : "–";

export function BrokersPage() {
  const { current } = useWorkspace();
  if (!current) return <Panel title="Brokers"><p className="text-sm text-muted">No member workspace to show.</p></Panel>;
  return <BrokersFor tenant={current.id} />;
}

function BrokersFor({ tenant }: { tenant: number }) {
  const client = useQueryClient();
  const { current } = useWorkspace();
  const today = useQuery({ queryKey: ["ws-today", tenant], refetchInterval: 10000,
    queryFn: () => get<WorkspaceToday>(`/api/execution/workspaces/${tenant}/today`) });
  const apps = useQuery({ queryKey: ["ws-apps", tenant], refetchInterval: 15000,
    queryFn: () => get<BrokerApps>(`/api/execution/workspaces/${tenant}/broker-apps`) });
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null);
  const [open, setOpen] = useState<number | null>(null);
  const done = (text: string, ok = true) => {
    setMessage({ ok, text });
    ["ws-today", "ws-apps", "exec-accounts", "exec-audit", "household"].forEach((k) => client.invalidateQueries({ queryKey: [k] }));
  };
  const fail = (e: unknown) => setMessage({ ok: false, text: e instanceof Error ? e.message : String(e) });

  if (today.isLoading || apps.isLoading) return <Loading what="brokers" />;
  if (today.error || !today.data) return <ErrorNote error={today.error} />;
  if (apps.error || !apps.data) return <ErrorNote error={apps.error} />;
  const t = today.data;
  return (
    <div className="space-y-4">
      <SupportBanner workspace={current} />
      {message && (
        <div className={`rounded border px-3 py-2 text-sm ${message.ok ? "border-up/40 text-up" : "border-down/40 text-down"}`}>
          {message.text} <button className="ml-2 text-muted" onClick={() => setMessage(null)}>dismiss</button>
        </div>
      )}
      <Panel title="Your brokers">
        <div className="space-y-3">
          {t.brokers.length === 0 && <p className="text-sm text-muted">No broker account yet: add one below.</p>}
          {t.brokers.map((b) => (
            <BrokerRow key={b.id} b={b} apps={apps.data!.apps} t={t} onDone={done} onError={fail}
              onManage={() => setOpen(open === b.id ? null : b.id)} open={open === b.id} />
          ))}
        </div>
      </Panel>
      {open != null && <AccountDetailPanel accountId={open} canManage={t.canManage} canAct={t.canSwitch} dailySwitch
        onClose={() => setOpen(null)} onDone={(text, ok) => (ok ? done(text) : setMessage({ ok, text }))} />}
      {t.canManage && <AddBroker tenant={tenant} onDone={done} onError={fail} />}
      <AppsPanel tenant={tenant} data={apps.data} onDone={done} onError={fail} />
    </div>
  );
}

function BrokerRow({ b, apps, t, open, onManage, onDone, onError }: {
  b: WorkspaceBroker; apps: BrokerApp[]; t: WorkspaceToday; open: boolean; onManage: () => void;
  onDone: (text: string, ok?: boolean) => void; onError: (e: unknown) => void;
}) {
  const [clientId, setClientId] = useState(b.clientId ?? "");
  const [totp, setTotp] = useState("");
  const [mpin, setMpin] = useState("");
  const [eligibleName, setEligibleName] = useState("");
  const mine = apps.filter((a) => a.broker === b.broker && a.status === "ACTIVE");
  const call = useMutation({
    mutationFn: ({ path, body }: { path: string; body: unknown; ok: string }) => postJson<unknown>(path, body),
    onSuccess: (_d, v) => onDone(v.ok),
    onError,
  });
  const login = useMutation({
    mutationFn: ({ path, body }: { path: string; body: unknown }) => postJson<LoginResult>(path, body),
    onSuccess: (r) => { onDone(r.message, r.ok); setTotp(""); setMpin(""); },
    onError: (e) => { onError(e); setTotp(""); setMpin(""); },
  });
  const isLogged = loggedIn(b);
  const real = b.broker !== "PAPER";
  return (
    <div className="rounded border border-line p-3 text-sm">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
        <span className="font-semibold">{b.name}</span>
        <span className="text-muted">{BROKER_NAMES[b.broker]}</span>
        {b.chosen && <span className="rounded bg-accent/15 px-1.5 text-xs text-accent">today's broker</span>}
        {real && <span className={isLogged ? "text-up" : "text-muted"}>{isLogged ? `logged in until ${when(b.sessionUntil)}`
          : b.connection === "PENDING_CONNECT" ? "never logged in" : "not logged in"}</span>}
        {real && !b.liveCapable && <span className="text-xs text-muted">PAPER only (no LIVE adapter for {BROKER_NAMES[b.broker]} yet)</span>}
        {b.liveEligible && <span className="text-xs text-up">LIVE-eligible</span>}
        <button className={`${button} ml-auto`} onClick={onManage}>{open ? "Close" : "Capital & strategies"}</button>
      </div>
      {real && (
        <div className="mt-2 flex flex-wrap items-center gap-2">
          <label className="flex items-center gap-1 text-xs text-muted">Client id
            <input className={`${input} w-28`} value={clientId} disabled={!t.canManage}
              onChange={(e) => setClientId(e.target.value.toUpperCase())} /></label>
          {t.canManage && clientId !== (b.clientId ?? "") && <button className={button} disabled={call.isPending}
            onClick={() => call.mutate({ path: `/api/execution/accounts/${b.id}/settings`, body: { clientId },
              ok: `${b.name}: client id ${clientId}` })}>Save</button>}
          <label className="flex items-center gap-1 text-xs text-muted">Broker app
            <select className={input} value={mine.find((a) => a.name === b.brokerApp)?.id ?? ""} disabled={!t.canManage || isLogged}
              onChange={(e) => e.target.value && call.mutate({ path: `/api/execution/accounts/${b.id}/broker-app`,
                body: { profileId: Number(e.target.value) }, ok: `${b.name} uses that app` })}>
              <option value="">{mine.length === 0 ? `no ${BROKER_NAMES[b.broker]} app yet` : "choose…"}</option>
              {mine.map((a) => <option key={a.id} value={a.id}>{a.name}{a.keysSet ? "" : " (keys not set)"}</option>)}
            </select></label>
        </div>
      )}
      {real && t.canSwitch && (
        <div className="mt-2 flex flex-wrap items-center gap-2">
          {loginUrl(b) && <a className={primary} href={loginUrl(b)!} target="_blank" rel="noreferrer">
            {isLogged ? "Log in again" : "Log in"} at {BROKER_NAMES[b.broker]}</a>}
          {b.broker === "GROWW" && <button className={primary} disabled={login.isPending}
            onClick={() => login.mutate({ path: `/api/execution/accounts/${b.id}/groww/login`, body: {} })}>
            Get today's token</button>}
          {b.broker === "GROWW" && <span className="text-xs text-muted">approve the API key on Groww's API keys page first</span>}
          {b.broker === "KOTAK_NEO" && <>
            <input className={`${input} w-24`} placeholder="TOTP" inputMode="numeric" autoComplete="one-time-code" maxLength={6}
              value={totp} onChange={(e) => setTotp(e.target.value.replace(/\D/g, ""))} />
            <input className={`${input} w-24`} placeholder="MPIN" type="password" inputMode="numeric" autoComplete="off"
              maxLength={6} value={mpin} onChange={(e) => setMpin(e.target.value.replace(/\D/g, ""))} />
            <button className={primary} disabled={login.isPending || totp.length !== 6 || mpin.length < 4}
              onClick={() => login.mutate({ path: `/api/execution/accounts/${b.id}/kotak/login`, body: { totp, mpin } })}>
              Log in to Kotak</button>
            <span className="text-xs text-muted">passed to Kotak once, never stored</span>
          </>}
          {isLogged && <button className={button} disabled={call.isPending}
            onClick={() => call.mutate({ path: `/api/execution/accounts/${b.id}/logout`, body: {}, ok: `${b.name} logged out here` })}>
            Log out here</button>}
        </div>
      )}
      {real && b.liveCapable && t.platformAdmin && (
        <div className="mt-2 flex flex-wrap items-center gap-2 border-t border-line pt-2">
          <span className="text-xs text-muted">Admin: {b.liveEligible ? "LIVE-eligible (the member may switch LIVE)" : "one-time check before the member may switch LIVE"}</span>
          <input className={`${input} w-40`} placeholder={`type ${b.name}`} value={eligibleName}
            onChange={(e) => setEligibleName(e.target.value)} />
          <button className={button} disabled={eligibleName !== b.name || call.isPending}
            onClick={() => { call.mutate({ path: `/api/execution/accounts/${b.id}/live-eligible`,
              body: { on: !b.liveEligible, confirm: eligibleName }, ok: `${b.name}: LIVE eligibility ${b.liveEligible ? "removed" : "granted"}` });
              setEligibleName(""); }}>{b.liveEligible ? "Remove LIVE eligibility" : "Mark LIVE-eligible"}</button>
        </div>
      )}
    </div>
  );
}

function AddBroker({ tenant, onDone, onError }: { tenant: number; onDone: (t: string) => void; onError: (e: unknown) => void }) {
  const [open, setOpen] = useState(false);
  const [name, setName] = useState("");
  const [broker, setBroker] = useState<string>("ZERODHA");
  const [clientId, setClientId] = useState("");
  const [allocation, setAllocation] = useState("");
  const create = useMutation({
    mutationFn: () => postJson<{ id: number; name: string }>("/api/execution/accounts", {
      name: name.trim(), broker, clientId: broker === "PAPER" ? null : clientId.trim() || null,
      allocation: Number(allocation.replace(/[,₹\s]/g, "")), equityMode: "FIXED", strategies: [], enabled: true, tenantId: tenant,
    }),
    onSuccess: (r) => { onDone(`${r.name} added (PAPER, with its paper twin): choose its strategies under "Capital & strategies"`);
      setOpen(false); setName(""); setClientId(""); setAllocation(""); },
    onError,
  });
  const alloc = Number(allocation.replace(/[,₹\s]/g, ""));
  return (
    <Panel title="Add a broker account" right={<button className={button} onClick={() => setOpen(!open)}>{open ? "Close" : "Add…"}</button>}>
      {!open ? <p className="text-sm text-muted">Zerodha, Upstox, Kotak Neo or Groww (or a paper-only account). It starts in
        PAPER; it trades LIVE only on a day you choose it, log in and switch LIVE.</p> : (
        <div className="flex flex-wrap items-end gap-3">
          <label className="flex flex-col gap-1 text-xs text-muted">Name
            <input className={input} value={name} placeholder="e.g. Zerodha" onChange={(e) => setName(e.target.value)} /></label>
          <label className="flex flex-col gap-1 text-xs text-muted">Broker
            <select className={input} value={broker} onChange={(e) => setBroker(e.target.value)}>
              {BROKERS.map((b) => <option key={b} value={b}>{BROKER_NAMES[b]}</option>)}
              <option value="PAPER">Paper only</option>
            </select></label>
          {broker !== "PAPER" && <label className="flex flex-col gap-1 text-xs text-muted">Client id
            <input className={input} value={clientId} placeholder={broker === "ZERODHA" ? "AB1234" : "your client code"}
              onChange={(e) => setClientId(e.target.value.toUpperCase())} /></label>}
          <label className="flex flex-col gap-1 text-xs text-muted">Capital (₹)
            <input className={input} value={allocation} placeholder="300000" onChange={(e) => setAllocation(e.target.value)} /></label>
          <button className={primary} disabled={create.isPending || name.trim().length < 2 || !(alloc > 0)}
            onClick={() => create.mutate()}>Add</button>
        </div>
      )}
    </Panel>
  );
}

function AppsPanel({ tenant, data, onDone, onError }: {
  tenant: number; data: BrokerApps; onDone: (t: string) => void; onError: (e: unknown) => void;
}) {
  const [broker, setBroker] = useState<string>("ZERODHA");
  const [name, setName] = useState("");
  const [keysFor, setKeysFor] = useState<number | null>(null);
  const [renameFor, setRenameFor] = useState<number | null>(null);
  const [newName, setNewName] = useState("");
  const [apiKey, setApiKey] = useState("");
  const [apiSecret, setApiSecret] = useState("");
  const [mobile, setMobile] = useState("");
  const call = useMutation({
    mutationFn: ({ path, body }: { path: string; body: unknown; ok: string }) => postJson<unknown>(path, body),
    onSuccess: (_d, v) => { onDone(v.ok); setApiKey(""); setApiSecret(""); setMobile(""); setKeysFor(null); setRenameFor(null); },
    onError: (e) => { onError(e); setApiSecret(""); },
  });
  const editing = data.apps.find((a) => a.id === keysFor);
  const renaming = data.apps.find((a) => a.id === renameFor);
  return (
    <Panel title="Broker apps (API keys)">
      <p className="mb-3 text-xs text-muted">Create the app at your broker's developer site under your own login, register the
        redirect URL and static IP shown here, then paste its keys below. Keys go to the server's secret store and are never
        shown again (only their last 4 characters).</p>
      <Table rows={data.apps} empty="No broker app yet." columns={[
        { key: "n", label: "App", render: (a) => <span className="font-semibold">{a.name}</span> },
        { key: "b", label: "Broker", render: (a) => BROKER_NAMES[a.broker] },
        { key: "k", label: "Keys", render: (a) => a.keysSet ? <span>set {a.key_hint ?? ""} <span className="text-xs text-muted">
          {a.secret_set_by ? `by ${a.secret_set_by} ${when(a.secret_set_at)}` : ""}</span></span> : <span className="text-warn">not set</span> },
        { key: "c", label: "Last check", render: (a) => a.last_check_error ? <span className="text-down">{a.last_check_error}</span>
          : a.verified_at ? <span className="text-up">login worked {when(a.verified_at)}</span> : <span className="text-muted">not yet</span> },
        { key: "r", label: "Register at the broker", render: (a) => <span className="text-xs">
          {a.redirect_uri ? <>redirect <code>{a.redirect_uri}</code><br /></> : null}
          {a.ips ? <>IP <code>{a.ips}</code></> : null}
          {a.broker === "KOTAK_NEO" && a.settings.mobileNumber ? <>mobile {a.settings.mobileNumber}</> : null}</span> },
        { key: "u", label: "Used by", render: (a) => a.accounts.join(", ") || "–" },
        { key: "x", label: "", render: (a) => !data.canManage ? null : <span className="flex gap-1">
          {a.managedHere ? <>
            <button className={button} onClick={() => { setRenameFor(null); setKeysFor(keysFor === a.id ? null : a.id); }}>
              {a.keysSet ? "Replace keys" : "Set keys"}</button>
            <button className={button} onClick={() => { setKeysFor(null); setNewName(a.name); setRenameFor(renameFor === a.id ? null : a.id); }}>
              Rename</button>
            <button className={button} disabled={call.isPending || a.accounts.length > 0}
              title={a.accounts.length > 0 ? `Used by ${a.accounts.join(", ")}: link it to another app first` : undefined}
              onClick={() => window.confirm(`Delete ${a.name} (${BROKER_NAMES[a.broker]})? Its stored keys are removed too. `
                + "This cannot be undone.") && call.mutate({ path: `/api/execution/broker-apps/${a.id}/delete`, body: {},
                ok: `${a.name} deleted` })}>Delete</button>
          </> : <span className="self-center text-xs text-muted">set up on the server</span>}
          <button className={button} disabled={call.isPending} onClick={() => call.mutate({ path: `/api/execution/broker-apps/${a.id}/status`,
            body: { active: a.status !== "ACTIVE" }, ok: `${a.name} ${a.status === "ACTIVE" ? "disabled" : "enabled"}` })}>
            {a.status === "ACTIVE" ? "Disable" : "Enable"}</button></span> },
      ]} />
      {renaming && (
        <form className="mt-3 flex flex-wrap items-end gap-3 rounded border border-line p-3" autoComplete="off"
          onSubmit={(e) => { e.preventDefault(); call.mutate({ path: `/api/execution/broker-apps/${renaming.id}/name`,
            body: { name: newName.trim() }, ok: `${renaming.name} renamed to ${newName.trim()}` }); }}>
          <label className="flex flex-col gap-1 text-xs text-muted">New name for {renaming.name} (a label only, not sent to the broker)
            <input className={`${input} w-64`} value={newName} maxLength={40} onChange={(e) => setNewName(e.target.value)} /></label>
          <button className={primary} type="submit" disabled={call.isPending || newName.trim().length < 2
            || newName.trim() === renaming.name}>Save</button>
          <button className={button} type="button" onClick={() => setRenameFor(null)}>Cancel</button>
        </form>
      )}
      {editing && (
        <form className="mt-3 flex flex-wrap items-end gap-3 rounded border border-line p-3" autoComplete="off"
          onSubmit={(e) => { e.preventDefault(); call.mutate({ path: `/api/execution/broker-apps/${editing.id}/keys`,
            body: { apiKey, apiSecret: editing.broker === "KOTAK_NEO" ? null : apiSecret,
              mobileNumber: editing.broker === "KOTAK_NEO" ? mobile : null }, ok: `${editing.name}: keys stored` }); }}>
          <span className="w-full text-sm font-semibold">Keys for {editing.name} ({BROKER_NAMES[editing.broker]})</span>
          <label className="flex flex-col gap-1 text-xs text-muted">{editing.broker === "KOTAK_NEO" ? "Consumer key (NEO app → Trade API)" : "API key"}
            <input className={`${input} w-64`} type="password" autoComplete="new-password" value={apiKey}
              onChange={(e) => setApiKey(e.target.value)} /></label>
          {editing.broker !== "KOTAK_NEO" ? <label className="flex flex-col gap-1 text-xs text-muted">API secret
            <input className={`${input} w-64`} type="password" autoComplete="new-password" value={apiSecret}
              onChange={(e) => setApiSecret(e.target.value)} /></label>
            : <label className="flex flex-col gap-1 text-xs text-muted">Mobile number registered with Kotak
              <input className={`${input} w-40`} inputMode="tel" value={mobile} placeholder="98765 43210"
                onChange={(e) => setMobile(e.target.value)} /></label>}
          <button className={primary} type="submit" disabled={call.isPending || apiKey.trim().length < 6
            || (editing.broker === "KOTAK_NEO" ? mobile.replace(/\D/g, "").length < 10 : apiSecret.trim().length < 6)}>Store</button>
        </form>
      )}
      {data.canManage && (
        <div className="mt-4 flex flex-wrap items-end gap-3 border-t border-line pt-3">
          <label className="flex flex-col gap-1 text-xs text-muted">New app: broker
            <select className={input} value={broker} onChange={(e) => setBroker(e.target.value)}>
              {BROKERS.map((b) => <option key={b} value={b}>{BROKER_NAMES[b]}</option>)}
            </select></label>
          <label className="flex flex-col gap-1 text-xs text-muted">Name
            <input className={input} value={name} placeholder={`My ${BROKER_NAMES[broker]} app`} onChange={(e) => setName(e.target.value)} /></label>
          <button className={button} disabled={call.isPending || name.trim().length < 2}
            onClick={() => { call.mutate({ path: `/api/execution/workspaces/${tenant}/broker-apps`, body: { broker, name: name.trim() },
              ok: `${name.trim()} created: set its keys` }); setName(""); }}>Create app</button>
          {data.register[broker] && <span className="text-xs text-muted">
            {data.register[broker].redirectUrl ? <>register redirect <code>{data.register[broker].redirectUrl}</code>; </> : null}
            {data.register[broker].staticIps.length > 0 ? <>static IP <code>{data.register[broker].staticIps.join(", ")}</code></> : null}
          </span>}
        </div>
      )}
    </Panel>
  );
}
