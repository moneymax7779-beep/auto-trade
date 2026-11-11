import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Link } from "react-router";
import { BROKER_NAMES, get, postJson, type LoginResult, type WorkspaceBroker, type WorkspaceToday } from "../api";
import { ErrorNote, Loading, Panel, Pnl, Stat, Table } from "../components/ui";
import { SupportBanner, useWorkspace } from "../workspace";

// Today (trader workspaces, user 7 Nov 2026): the member picks the day's broker and switches it between PAPER (every
// morning, and whenever not logged in) and LIVE (after logging in, only signals from then on). One broker at a time.

const input = "rounded border border-line bg-bg px-2 py-1 text-sm";
const button = "rounded border border-line px-3 py-1 text-sm hover:bg-panel-2 disabled:opacity-40";
const time = (iso: string | null | undefined) =>
  iso ? new Date(iso).toLocaleTimeString("en-IN", { hour12: false, timeZone: "Asia/Kolkata", hour: "2-digit",
    minute: "2-digit" }) : "–";

/** The URL that starts a broker's login in the browser (Zerodha, Upstox); null for the others. */
export function loginUrl(b: WorkspaceBroker): string | null {
  if (b.broker === "ZERODHA") return `/api/execution/accounts/${b.id}/kite/login`;
  if (b.broker === "UPSTOX") return `/api/execution/accounts/${b.id}/upstox/login`;
  return null;
}

export function loggedIn(b: WorkspaceBroker): boolean {
  return b.connection === "ACTIVE" && b.sessionUntil != null && new Date(b.sessionUntil) > new Date();
}

function ModeBadge({ trading }: { trading: "OFF" | "PAPER" | "LIVE" }) {
  const tone = trading === "LIVE" ? "bg-down text-white" : trading === "PAPER" ? "bg-warn/20 text-warn" : "bg-panel-2 text-muted";
  return <span className={`rounded px-3 py-1 text-lg font-bold tracking-wider ${tone}`}>{trading}</span>;
}

export function TodayPage() {
  const { current } = useWorkspace();
  if (!current) {
    return <Panel title="Today"><p className="text-sm text-muted">No workspace with a daily broker switch. A family member's
      workspace has one; yours trades each account in its own mode (see Accounts).</p></Panel>;
  }
  return <TodayFor tenant={current.id} />;
}

function TodayFor({ tenant }: { tenant: number }) {
  const client = useQueryClient();
  const { current } = useWorkspace();
  const today = useQuery({ queryKey: ["ws-today", tenant], refetchInterval: 5000,
    queryFn: () => get<WorkspaceToday>(`/api/execution/workspaces/${tenant}/today`) });
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null);
  const [confirmLive, setConfirmLive] = useState("");
  const [lossInput, setLossInput] = useState("");
  const [killReason, setKillReason] = useState("");
  const done = (text: string) => {
    setMessage({ ok: true, text });
    ["ws-today", "exec-switches", "exec-audit", "household"].forEach((k) => client.invalidateQueries({ queryKey: [k] }));
  };
  const fail = (e: unknown) => setMessage({ ok: false, text: e instanceof Error ? e.message : String(e) });
  const call = useMutation({
    mutationFn: ({ path, body }: { path: string; body: unknown; ok: string }) => postJson<unknown>(path, body),
    onSuccess: (_d, v) => done(v.ok),
    onError: fail,
  });
  const groww = useMutation({
    mutationFn: (id: number) => postJson<LoginResult>(`/api/execution/accounts/${id}/groww/login`, {}),
    onSuccess: (r) => (r.ok ? done(r.message) : setMessage({ ok: false, text: r.message })),
    onError: fail,
  });

  if (today.isLoading) return <Loading what="today" />;
  if (today.error || !today.data) return <ErrorNote error={today.error} />;
  const t = today.data;
  const s = t.state;
  if (t.control !== "DAILY_SWITCH" || !s) {
    return <Panel title={t.name}><p className="text-sm text-muted">This workspace trades each account in its own mode.</p></Panel>;
  }
  const chosen = t.brokers.find((b) => b.id === s.brokerAccountId);
  const live = s.trading === "LIVE";
  const tenantKill = t.killSwitches.find((k) => k.scope === "TENANT");
  const liveBlock = !chosen ? "choose a broker first"
    : chosen.broker === "PAPER" ? "a paper account never trades LIVE"
    : !chosen.liveCapable ? `${BROKER_NAMES[chosen.broker]} has no LIVE order adapter yet: it trades PAPER`
    : !chosen.liveEligible ? "not LIVE-eligible yet (the admin's one-time check)"
    : !loggedIn(chosen) ? `log in to ${chosen.name} first`
    : tenantKill ? "your kill switch is engaged"
    : t.lossLimit.reached ? "your daily loss limit is reached"
    : null;

  return (
    <div className="space-y-4">
      <SupportBanner workspace={current} />
      {message && (
        <div className={`rounded border px-3 py-2 text-sm ${message.ok ? "border-up/40 text-up" : "border-down/40 text-down"}`}>
          {message.text} <button className="ml-2 text-muted" onClick={() => setMessage(null)}>dismiss</button>
        </div>
      )}

      <Panel title={`${t.name}: today, ${t.day}`} right={<ModeBadge trading={s.trading} />}>
        <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
          <Stat label="Broker" value={s.broker ?? "Off"} />
          <Stat label={live ? "LIVE since" : "Mode"} value={live ? time(s.since) : s.trading === "OFF" ? "no trading" : "PAPER"}
            tone={live ? "down" : "warn"} />
          <Stat label="Paper today" value={<Pnl value={t.totals.paperNet} />} />
          <Stat label="Live today" value={<Pnl value={t.totals.liveNet} />} />
        </div>
        {s.mode === "LIVE" && s.trading !== "LIVE" && (
          <p className="mt-3 text-sm text-warn">Switched LIVE, but entries go to PAPER now (no session, or the account cannot
            trade LIVE at the moment). Log in again.</p>
        )}
        <p className="mt-3 text-xs text-muted">Every day starts PAPER. LIVE needs a login and your switch, and only signals after
          the switch go to the broker. Open positions stay where they were opened and exit there.</p>
      </Panel>

      <Panel title="Broker for today">
        <div className="space-y-2">
          {t.brokers.length === 0 && <p className="text-sm text-muted">No broker yet: add one on the <Link className="underline"
            to="/brokers">Brokers</Link> page.</p>}
          {[...t.brokers, null].map((b) => {
            const id = b?.id ?? null;
            const selected = s.brokerAccountId === id;
            return (
              <label key={id ?? "off"} className={`flex flex-wrap items-center gap-x-3 gap-y-1 rounded border px-3 py-2 text-sm ${
                selected ? "border-accent bg-accent/5" : "border-line"}`}>
                <input type="radio" name="broker" checked={selected} disabled={!t.canSwitch || call.isPending || (b != null && !b.enabled)}
                  onChange={() => {
                    if (live && !window.confirm("Today is LIVE on " + s.broker + ". The new choice starts PAPER; open LIVE "
                      + "positions stay at their broker and exit there. Change?")) return;
                    call.mutate({ path: `/api/execution/workspaces/${tenant}/today/broker`, body: { accountId: id },
                      ok: b ? `${b.name} is today's broker (PAPER)` : "Off for today" });
                  }} />
                {b ? <>
                  <span className="font-semibold">{b.name}</span>
                  <span className="text-muted">{BROKER_NAMES[b.broker]}{b.clientId ? ` ${b.clientId}` : ""}</span>
                  {b.broker !== "PAPER" && <span className={loggedIn(b) ? "text-up" : "text-muted"}>
                    {loggedIn(b) ? `logged in until ${time(b.sessionUntil)}` : "not logged in"}</span>}
                  {b.broker !== "PAPER" && !b.liveCapable && <span className="text-xs text-muted">(PAPER only)</span>}
                  {b.liveEligible && <span className="text-xs text-up">LIVE-eligible</span>}
                  <span className="text-xs text-muted">₹{Math.round(b.allocation).toLocaleString("en-IN")} · {b.strategies.join(", ") || "no strategies"}</span>
                  {!b.enabled && <span className="text-xs text-down">disabled</span>}
                </> : <span className="text-muted">Off: no trading today</span>}
              </label>
            );
          })}
        </div>
        {chosen && chosen.broker !== "PAPER" && !loggedIn(chosen) && t.canSwitch && (
          <div className="mt-3 flex flex-wrap items-center gap-2 text-sm">
            <span className="text-muted">Log in to {chosen.name} (any time of the day):</span>
            {loginUrl(chosen) && <a className={button} href={loginUrl(chosen)!} target="_blank" rel="noreferrer">Log in at {BROKER_NAMES[chosen.broker]}</a>}
            {chosen.broker === "GROWW" && <button className={button} disabled={groww.isPending}
              onClick={() => groww.mutate(chosen.id)}>Get today's Groww token</button>}
            {chosen.broker === "KOTAK_NEO" && <Link className={button} to="/brokers">Log in on the Brokers page</Link>}
          </div>
        )}
      </Panel>

      <Panel title="PAPER / LIVE">
        <div className="flex flex-wrap items-center gap-3">
          <button className={`${button} ${s.mode === "PAPER" ? "bg-warn/15 font-semibold" : ""}`}
            disabled={!t.canStop || s.mode === "PAPER" || call.isPending}
            onClick={() => call.mutate({ path: `/api/execution/workspaces/${tenant}/today/mode`, body: { mode: "PAPER" },
              ok: "PAPER from now: new signals paper-trade" })}>PAPER</button>
          {s.mode !== "LIVE" && t.canManage && (
            <>
              <input className={`${input} w-28`} placeholder="type LIVE" value={confirmLive}
                onChange={(e) => setConfirmLive(e.target.value)} disabled={liveBlock != null} />
              <button className="rounded bg-down px-3 py-1 text-sm font-semibold text-white disabled:opacity-40"
                disabled={liveBlock != null || confirmLive !== "LIVE" || call.isPending}
                onClick={() => { call.mutate({ path: `/api/execution/workspaces/${tenant}/today/mode`,
                  body: { mode: "LIVE", confirm: "LIVE" }, ok: "LIVE from now: real orders from the next signal" }); setConfirmLive(""); }}>
                Switch LIVE</button>
            </>
          )}
          {s.mode === "LIVE" && <span className="text-sm text-down">LIVE on {s.broker} since {time(s.since)}</span>}
        </div>
        {s.mode !== "LIVE" && t.canManage && liveBlock && <p className="mt-2 text-xs text-muted">LIVE not possible now: {liveBlock}.</p>}
        {s.mode !== "LIVE" && !t.canManage && <p className="mt-2 text-xs text-muted">Only the workspace's owner switches LIVE.</p>}
        {chosen && chosen.liveStrategies.length > 0 && (
          <p className="mt-2 text-xs text-muted">LIVE trades these strategies only: {chosen.liveStrategies.join(", ")} ({chosen.sizeStepPct > 0
            ? "lots from the allocation" : "one lot an entry"}). The others keep paper-trading.</p>
        )}
      </Panel>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <Panel title="Loss limit (LIVE, all brokers)">
          <div className="grid grid-cols-2 gap-4">
            <Stat label="Limit" value={t.lossLimit.dailyLossLimit == null ? "not set"
              : `₹${Math.round(t.lossLimit.dailyLossLimit).toLocaleString("en-IN")}`} />
            <Stat label="LIVE net today" value={<Pnl value={t.lossLimit.liveNetToday} />}
              tone={t.lossLimit.reached ? "down" : undefined} />
          </div>
          {t.lossLimit.reached && <p className="mt-2 text-sm text-down">Reached: no LIVE entries for the rest of today.</p>}
          {t.canManage && (
            <div className="mt-3 flex flex-wrap items-center gap-2">
              <input className={`${input} w-32`} placeholder="₹ e.g. 20000" value={lossInput}
                onChange={(e) => setLossInput(e.target.value)} />
              <button className={button} disabled={call.isPending || !(Number(lossInput.replace(/[,₹\s]/g, "")) > 0)}
                onClick={() => call.mutate({ path: `/api/execution/workspaces/${tenant}/limits`,
                  body: { dailyLossLimit: Number(lossInput.replace(/[,₹\s]/g, "")) }, ok: "loss limit set" })}>Set</button>
              {t.lossLimit.dailyLossLimit != null && <button className={button} disabled={call.isPending}
                onClick={() => call.mutate({ path: `/api/execution/workspaces/${tenant}/limits`, body: { clear: true },
                  ok: "loss limit cleared" })}>Clear</button>}
            </div>
          )}
        </Panel>

        <Panel title="Kill switch (all your accounts)">
          {tenantKill ? (
            <div className="space-y-2 text-sm">
              <p className="text-down">Engaged by {tenantKill.engaged_by}: {tenantKill.reason}. New entries stop; exits continue.</p>
              {t.canSwitch && <button className={button} disabled={call.isPending}
                onClick={() => call.mutate({ path: `/api/execution/kill-switches/${tenantKill.id}/release`, body: {},
                  ok: "kill switch released" })}>Release</button>}
            </div>
          ) : t.canStop ? (
            <div className="flex flex-wrap items-center gap-2">
              <input className={`${input} w-56`} placeholder="reason" value={killReason} onChange={(e) => setKillReason(e.target.value)} />
              <button className="rounded border border-down/60 px-3 py-1 text-sm font-semibold text-down disabled:opacity-40"
                disabled={killReason.trim() === "" || call.isPending}
                onClick={() => { call.mutate({ path: "/api/execution/kill-switches", body: { scope: "TENANT", tenantId: tenant,
                  reason: killReason.trim() }, ok: "kill switch engaged: no new entries on any of your accounts" }); setKillReason(""); }}>
                Stop new entries</button>
            </div>
          ) : <p className="text-sm text-muted">Not engaged.</p>}
          {t.killSwitches.filter((k) => k.scope !== "TENANT").map((k) => (
            <p key={k.id} className="mt-2 text-xs text-down">{k.scope}{k.what ? ` ${k.what}` : ""}: {k.reason} ({k.engaged_by})</p>
          ))}
        </Panel>
      </div>

      {t.pending.length > 0 && (
        <Panel title="To finish setting up">
          <ul className="list-disc pl-5 text-sm">
            {t.pending.map((p) => <li key={p.id}>{p.name}: {p.needs === "BROKER_APP" ? "add and link its broker app"
              : p.needs === "CLIENT_ID" ? "set its client id" : "log in once"} (<Link className="underline" to="/brokers">Brokers</Link>)</li>)}
          </ul>
        </Panel>
      )}

      <Panel title="Today's switches">
        <Table rows={t.events} empty="No change today: the default broker in PAPER." columns={[
          { key: "at", label: "Time", render: (e) => e.at },
          { key: "b", label: "Broker", render: (e) => e.broker },
          { key: "m", label: "Mode", render: (e) => <span className={e.mode === "LIVE" ? "text-down" : ""}>{e.mode}</span> },
          { key: "r", label: "Why", render: (e) => e.reason },
          { key: "by", label: "By", render: (e) => e.by },
        ]} />
      </Panel>
    </div>
  );
}
