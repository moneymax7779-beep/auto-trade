import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { type ReactNode, useState } from "react";
import { Link, useSearchParams } from "react-router";
import { BROKER_NAMES, brokerLoginUrl, get, postJson, type BrokerApps, type LoginResult, type WorkspaceBroker, type WorkspaceToday } from "../api";
import { loggedIn } from "../pages/TodayPage";
import { useWorkspace } from "../workspace";
import { Big, Card, ErrorLine, istTime } from "./common";

type Broker = "ZERODHA" | "UPSTOX" | "KOTAK_NEO" | "GROWW";
const BROKERS: Broker[] = ["ZERODHA", "UPSTOX", "KOTAK_NEO", "GROWW"];

/** What to do on each broker's own site, in order (the keys page's KEY_SOURCE, step by step). */
const SITE: Record<Broker, { where: string; steps: string[]; cost?: string; key: string; secret: string | null }> = {
  ZERODHA: { where: "developers.kite.trade", key: "API key", secret: "API secret", cost: "Kite Connect is a paid subscription at Zerodha.",
    steps: ["Sign in at developers.kite.trade with your own Zerodha login.", "Create new app → type Connect.",
      "Paste the redirect URL below into the app's Redirect URL.", "Open the app: copy its API key and API secret."] },
  UPSTOX: { where: "account.upstox.com/developer/apps", key: "API key", secret: "API secret",
    steps: ["Sign in at account.upstox.com/developer/apps.", "New app.", "Paste the redirect URL below as its redirect URL.",
      "Register the static IP below on the app (needed before LIVE).", "Copy the app's API key and API secret."] },
  KOTAK_NEO: { where: "the Kotak NEO app", key: "Consumer key (TOKEN)", secret: null,
    steps: ["Open the NEO app → More → Trade API.", "Create an application and register the static IP below.",
      "Copy the application's TOKEN: that is the consumer key.", "Have the mobile number registered with Kotak ready."] },
  GROWW: { where: "Groww → Profile → Settings → Trading APIs", key: "API key", secret: "API secret",
    steps: ["Open Groww → Profile → Settings → Trading APIs.", "Generate API key (key and secret, not TOTP).",
      "Register the static IP below.", "Each morning you approve the key on Groww before logging in here."] },
};

const STEPS = ["Pick", "App", "Keys", "Log in", "Ready"] as const;

/**
 * Mobile phase 2 (17 Nov 2026): a broker set up from the phone, step by step, on the same endpoints as the Brokers page:
 * the account (PAPER, with its paper twin), the app at the broker with copy buttons for the redirect URL and static IP,
 * the keys (stored write-only), a real test login, and Ready only once that login worked. Never asks for, or stores, the
 * broker password, MPIN or TOTP seed; Kotak's TOTP and MPIN are passed to Kotak once.
 * The step and account live in the address (?account=&step=), so leaving for the broker's site and coming back resumes.
 */
export function BrokerWizard() {
  const { current } = useWorkspace();
  const [params, setParams] = useSearchParams();
  const tenant = current?.id;
  const today = useQuery({ queryKey: ["ws-today", tenant], enabled: tenant != null, refetchInterval: 5_000,
    queryFn: () => get<WorkspaceToday>(`/api/execution/workspaces/${tenant}/today`) });
  const apps = useQuery({ queryKey: ["ws-apps", tenant], enabled: tenant != null, refetchInterval: 15_000,
    queryFn: () => get<BrokerApps>(`/api/execution/workspaces/${tenant}/broker-apps`) });
  const accountId = params.get("account") ? Number(params.get("account")) : null;
  const asked = Number(params.get("step") ?? (accountId ? 1 : 0));
  const step = Number.isInteger(asked) ? Math.max(0, Math.min(4, asked)) : accountId ? 1 : 0;   // a bad ?step= starts over
  const go = (next: number, account = accountId) => setParams(account == null ? { step: String(next) }
    : { account: String(account), step: String(next) });
  const b = today.data?.brokers.find((x) => x.id === accountId);

  if (!current) return <Shell step={0}><p className="text-sm text-muted">No workspace to add a broker to.</p></Shell>;
  if (today.isLoading || apps.isLoading) return <Shell step={step}><p className="text-sm text-muted">Loading…</p></Shell>;
  if (today.error || apps.error) return <Shell step={step}><ErrorLine error={today.error ?? apps.error} /></Shell>;
  if (!today.data!.canManage) return <Shell step={step}><p className="text-sm text-muted">Only the workspace's owner or admin can
    add a broker. Ask them, or log in to an existing one on Home.</p></Shell>;
  if (step > 0 && !b && today.isFetching) return <Shell step={step}><p className="text-sm text-muted">Loading…</p></Shell>;
  if (step > 0 && !b) return <Shell step={0}><p className="text-sm text-muted">That broker account is not in this workspace.</p>
    <Big onClick={() => go(0, null)}>Start again</Big></Shell>;
  const broker = b?.broker as Broker | undefined;
  const reg = broker ? apps.data!.register[broker] : undefined;
  return (
    <Shell step={step} name={b?.name}>
      {step === 0 && <PickStep tenant={tenant!} onCreated={(id) => go(1, id)} />}
      {step === 1 && broker && <AppStep broker={broker} redirect={reg?.redirectUrl ?? null} ips={reg?.staticIps ?? []}
        onNext={() => go(2)} />}
      {step === 2 && b && broker && <KeysStep b={b} broker={broker} onNext={() => go(3)} />}
      {step === 3 && b && <LoginStep b={b} onNext={() => go(4)} onBack={() => go(2)} />}
      {step === 4 && b && <ReadyStep b={b} />}
    </Shell>
  );
}

function Shell({ step, name, children }: { step: number; name?: string; children: ReactNode }) {
  return (
    <div className="mx-auto flex max-w-xl flex-col gap-3">
      <div className="flex items-baseline justify-between"><h1 className="text-xl font-semibold">Add a broker</h1>
        <Link to="/brokers" className="text-sm text-sky-700">All brokers</Link></div>
      <ol className="flex flex-wrap gap-1 text-[11px]" aria-label="Steps">
        {STEPS.map((s, i) => <li key={s} aria-current={i === step ? "step" : undefined}
          className={`rounded-full px-2 py-0.5 ${i === step ? "bg-sky-700 font-semibold text-white" : i < step ? "bg-up/15 text-up" : "bg-panel-2 text-muted"}`}>
          {i + 1} {s}</li>)}
      </ol>
      {name && <p className="text-sm text-muted">Account <b className="text-text">{name}</b></p>}
      {children}
    </div>
  );
}

function PickStep({ tenant, onCreated }: { tenant: number; onCreated: (id: number) => void }) {
  const client = useQueryClient();
  const [broker, setBroker] = useState<Broker>("ZERODHA");
  const [name, setName] = useState("");
  const [clientId, setClientId] = useState("");
  const [capital, setCapital] = useState("");
  const amount = Number(capital.replace(/[,₹\s]/g, ""));
  const create = useMutation({
    mutationFn: () => postJson<{ id: number; name: string }>("/api/execution/accounts", { name: name.trim() || BROKER_NAMES[broker],
      broker, clientId: clientId.trim() || null, allocation: amount, equityMode: "FIXED", strategies: [], enabled: true, tenantId: tenant }),
    // wait for the refreshed list, or step 2 would briefly not find the new account (review 17 Nov)
    onSuccess: async (r) => { await client.refetchQueries({ queryKey: ["ws-today", tenant] }); onCreated(r.id); },
  });
  return (
    <Card>
      <form className="flex flex-col gap-3" autoComplete="off" onSubmit={(e) => { e.preventDefault(); create.mutate(); }}>
        <fieldset className="grid grid-cols-2 gap-2">
          <legend className="mb-1 text-sm font-semibold">Broker</legend>
          {BROKERS.map((x) => (
            <label key={x} className={`flex min-h-12 items-center justify-center rounded-lg border text-sm ${broker === x
              ? "border-sky-700 font-semibold text-sky-700" : "border-line"}`}>
              <input type="radio" name="broker" className="sr-only" checked={broker === x} onChange={() => setBroker(x)} />
              {BROKER_NAMES[x]}</label>
          ))}
        </fieldset>
        <label className="flex flex-col gap-1 text-sm">Name in auto-trade
          <input className="min-h-11 rounded-lg border border-line bg-bg px-3" value={name} placeholder={BROKER_NAMES[broker]}
            onChange={(e) => setName(e.target.value)} /></label>
        <label className="flex flex-col gap-1 text-sm">Client id at {BROKER_NAMES[broker]}
          <input className="min-h-11 rounded-lg border border-line bg-bg px-3 uppercase" value={clientId}
            placeholder={broker === "ZERODHA" ? "AB1234" : "your client code"} autoCapitalize="characters"
            onChange={(e) => setClientId(e.target.value.toUpperCase())} /></label>
        <label className="flex flex-col gap-1 text-sm">Capital for auto-trade (₹)
          <input className="min-h-11 rounded-lg border border-line bg-bg px-3" inputMode="numeric" value={capital}
            placeholder="300000" onChange={(e) => setCapital(e.target.value)} /></label>
        <p className="text-xs text-muted">It starts in PAPER with a paper twin. It trades real money only on a day you choose it,
          log in and switch it LIVE, after the owner has checked it once.</p>
        {create.error && <p className="text-sm text-down">{(create.error as Error).message}</p>}
        <Big type="submit" kind="primary" disabled={create.isPending || !(amount > 0)}>{create.isPending ? "Adding…" : "Add and continue"}</Big>
      </form>
    </Card>
  );
}

function Copy({ label, text }: { label: string; text: string }) {
  const [done, setDone] = useState(false);
  const copy = async () => {
    try { await navigator.clipboard.writeText(text); setDone(true); setTimeout(() => setDone(false), 2_000); }
    catch { window.getSelection()?.selectAllChildren(document.getElementById(`copy-${label}`)!); }
  };
  return (
    <div className="flex flex-col gap-1">
      <span className="text-xs text-muted">{label}</span>
      <div className="flex items-center gap-2 rounded-lg bg-panel-2 p-2">
        <code id={`copy-${label}`} className="min-w-0 flex-1 break-all text-xs">{text}</code>
        <button type="button" onClick={copy} className="min-h-11 shrink-0 rounded-lg border border-line bg-panel px-3 text-sm font-semibold">
          {done ? "Copied" : "Copy"}</button>
      </div>
    </div>
  );
}

function AppStep({ broker, redirect, ips, onNext }: { broker: Broker; redirect: string | null; ips: string[]; onNext: () => void }) {
  const s = SITE[broker];
  return (
    <Card>
      <h2 className="mb-2 font-semibold">Create your app at {s.where}</h2>
      <ol className="mb-3 list-decimal pl-5 text-sm">{s.steps.map((x) => <li key={x} className="mb-1">{x}</li>)}</ol>
      <div className="flex flex-col gap-3">
        {redirect && <Copy label="Redirect URL" text={redirect} />}
        {ips.length > 0 && <Copy label="Static IP" text={ips.join(", ")} />}
        {ips.length === 0 && broker !== "ZERODHA" && <p className="text-xs text-warn">No static IP is set up for this server yet:
          PAPER works, LIVE waits until the owner adds one.</p>}
        {s.cost && <p className="text-xs text-muted">{s.cost}</p>}
        <Big kind="primary" onClick={onNext}>I created the app</Big>
      </div>
    </Card>
  );
}

function KeysStep({ b, broker, onNext }: { b: WorkspaceBroker; broker: Broker; onNext: () => void }) {
  const client = useQueryClient();
  const s = SITE[broker];
  const [key, setKey] = useState("");
  const [secret, setSecret] = useState("");
  const [mobile, setMobile] = useState("");
  const kotak = broker === "KOTAK_NEO";
  const save = useMutation({
    mutationFn: () => postJson<{ name: string; keyHint: string }>(`/api/execution/accounts/${b.id}/broker-app/setup`,
      { apiKey: key.trim(), apiSecret: kotak ? null : secret.trim(), mobileNumber: kotak ? mobile : null }),
    onSuccess: () => { setKey(""); setSecret(""); client.invalidateQueries({ queryKey: ["ws-apps"] }); onNext(); },
    onError: () => setSecret(""),
  });
  const ok = key.trim().length >= 6 && (kotak ? mobile.replace(/\D/g, "").length >= 10 : secret.trim().length >= 6);
  return (
    <Card>
      <form className="flex flex-col gap-3" autoComplete="off" onSubmit={(e) => { e.preventDefault(); save.mutate(); }}>
        <h2 className="font-semibold">Paste the keys</h2>
        <label className="flex flex-col gap-1 text-sm">{s.key}
          <input className="min-h-11 rounded-lg border border-line bg-bg px-3" type="password" autoComplete="new-password"
            value={key} onChange={(e) => setKey(e.target.value)} /></label>
        {kotak ? (
          <label className="flex flex-col gap-1 text-sm">Mobile number registered with Kotak
            <input className="min-h-11 rounded-lg border border-line bg-bg px-3" inputMode="tel" placeholder="98765 43210"
              value={mobile} onChange={(e) => setMobile(e.target.value)} /></label>
        ) : (
          <label className="flex flex-col gap-1 text-sm">{s.secret}
            <input className="min-h-11 rounded-lg border border-line bg-bg px-3" type="password" autoComplete="new-password"
              value={secret} onChange={(e) => setSecret(e.target.value)} /></label>
        )}
        <p className="text-xs text-muted">Stored on the server's secret store, never shown again (only the last 4 characters).</p>
        {save.error && <p className="text-sm text-down">{(save.error as Error).message}</p>}
        <Big type="submit" kind="primary" disabled={!ok || save.isPending}>{save.isPending ? "Saving…" : "Save keys"}</Big>
      </form>
    </Card>
  );
}

function LoginStep({ b, onNext, onBack }: { b: WorkspaceBroker; onNext: () => void; onBack: () => void }) {
  const [totp, setTotp] = useState("");
  const [mpin, setMpin] = useState("");
  const login = useMutation({
    mutationFn: (path: string) => postJson<LoginResult>(path, b.broker === "KOTAK_NEO" ? { totp, mpin } : {}),
    onSettled: () => { setTotp(""); setMpin(""); },
  });
  const done = loggedIn(b);
  const url = brokerLoginUrl(b.broker, b.id);
  return (
    <Card>
      <h2 className="mb-2 font-semibold">Test the login</h2>
      {done ? (
        <div className="flex flex-col gap-3">
          <p className="text-sm text-up">Logged in until {istTime(b.sessionUntil)}. The keys work.</p>
          <Big kind="primary" onClick={onNext}>Continue</Big>
        </div>
      ) : (
        <div className="flex flex-col gap-3">
          {url && <>
            <p className="text-sm">Log in on {BROKER_NAMES[b.broker]}'s own page (your password and code go to them, not here). Come back
              to this tab: it notices the login by itself.</p>
            <a href={url} target="_blank" rel="noreferrer"
              className="flex min-h-11 items-center justify-center rounded-lg bg-sky-700 text-sm font-semibold text-white">
              Log in at {BROKER_NAMES[b.broker]}</a>
            <p className="text-xs text-muted">Waiting for the login… (checks every 5 s)</p>
          </>}
          {b.broker === "KOTAK_NEO" && (
            <form className="flex flex-col gap-2" autoComplete="off" onSubmit={(e) => { e.preventDefault();
              login.mutate(`/api/execution/accounts/${b.id}/kotak/login`); }}>
              <input className="min-h-11 rounded-lg border border-line bg-bg px-3 text-base" placeholder="TOTP from your authenticator"
                inputMode="numeric" autoComplete="one-time-code" aria-label="TOTP" value={totp}
                onChange={(e) => setTotp(e.target.value.replace(/\D/g, "").slice(0, 6))} />
              <input className="min-h-11 rounded-lg border border-line bg-bg px-3 text-base" placeholder="MPIN" type="password"
                inputMode="numeric" autoComplete="off" aria-label="MPIN" value={mpin}
                onChange={(e) => setMpin(e.target.value.replace(/\D/g, "").slice(0, 6))} />
              <p className="text-xs text-muted">Passed to Kotak once, never stored.</p>
              <Big type="submit" kind="primary" disabled={login.isPending || totp.length !== 6 || mpin.length < 4}>Log in to Kotak</Big>
            </form>
          )}
          {b.broker === "GROWW" && <>
            <p className="text-sm">Approve the API key on Groww's API keys page first, then:</p>
            <Big kind="primary" disabled={login.isPending} onClick={() => login.mutate(`/api/execution/accounts/${b.id}/groww/login`)}>
              Get today's token</Big>
          </>}
          {login.data && <p className={`text-sm ${login.data.ok ? "text-up" : "text-down"}`}>{login.data.message}</p>}
          {login.error && <p className="text-sm text-down">{(login.error as Error).message}</p>}
          <Big onClick={onBack}>Back to keys</Big>
        </div>
      )}
    </Card>
  );
}

function ReadyStep({ b }: { b: WorkspaceBroker }) {
  return (
    <Card>
      <h2 className="mb-2 font-semibold text-up">{b.name} is ready</h2>
      <ul className="mb-3 list-disc pl-5 text-sm">
        <li>It trades PAPER until you switch it LIVE on Today{b.liveCapable ? "" : ` (no LIVE adapter for ${BROKER_NAMES[b.broker]} yet)`}.</li>
        <li>Choose its strategies and capital under Brokers → Capital &amp; strategies.</li>
        <li>Each morning: Home shows the login; tap Log in before 09:15.</li>
        <li>For the 08:30 login reminder and trade alerts on Telegram, link it under More → Settings.</li>
      </ul>
      <div className="grid grid-cols-2 gap-2">
        <Link to="/home" className="flex min-h-11 items-center justify-center rounded-lg bg-sky-700 text-sm font-semibold text-white">Home</Link>
        <Link to="/brokers" className="flex min-h-11 items-center justify-center rounded-lg border border-line text-sm font-semibold">Brokers</Link>
      </div>
    </Card>
  );
}
