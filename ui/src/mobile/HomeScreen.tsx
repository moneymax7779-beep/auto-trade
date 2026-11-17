import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { type ReactNode, useRef, useState } from "react";
import { Link } from "react-router";
import { brokerLoginUrl, get, isPrimaryLive, postJson, type ExecutionAccount, type KillSwitch, type SessionRow,
  type Status } from "../api";
import { pushSupported, usePush } from "./NotificationsScreen";
import { activeAccounts, Big, Card, ErrorLine, Freshness, serviceDown, istTime, ModeBadge, Sheet, signed, tone, useAccess,
  useAccounts } from "./common";

/** GET /api/execution/login-board: every account the caller sees with its broker login state today. */
interface LoginBoard {
  day: string;
  tradingDay: boolean;
  accounts: { accountId: number; tenant: string; account: string; broker: string; mode: string; connection: string | null;
    sessionUntil: string | null; strategies: string[]; ready: boolean }[];
}

/**
 * Mobile phase 1 Home (17 Nov 2026): what a trader needs before and during the day on one screen. The checklist says
 * what is missing and fixes it in one tap (broker logins); the day's net per account; the indices; and stopping new
 * entries, which is one tap to open and one to confirm. Exits are on Positions.
 */
export function HomeScreen() {
  const access = useAccess();
  const accounts = useAccounts();
  const board = useQuery({ queryKey: ["login-board"], queryFn: () => get<LoginBoard>("/api/execution/login-board"),
    refetchInterval: 30_000 });
  const switches = useQuery({ queryKey: ["exec-switches"], queryFn: () => get<KillSwitch[]>("/api/execution/kill-switches"),
    refetchInterval: 10_000 });
  // the PAPER system session (owner): feed, indices, its day; a member's sign-in may not read it, then it is left out
  const status = useQuery({ queryKey: ["status-home"], queryFn: () => get<Status>("/api/status"), refetchInterval: 10_000,
    retry: false });
  const [stopping, setStopping] = useState(false);
  const push = usePush();
  // PAPER-1's strategy session: today's while it runs (status.dayPnl), else its last finished session's net (review 17 Nov:
  // after the close it vanished from Home while the desktop showed +1,82,995); never added into either total
  const running = typeof status.data?.dayPnl === "number";
  const sessions = useQuery({ queryKey: ["sessions-home"], enabled: status.isFetched && !running, staleTime: 300_000,
    retry: false, queryFn: () => get<SessionRow[]>("/api/sessions") });
  const lastSession = (sessions.data ?? []).filter((r) => isPrimaryLive(r) && r.status !== "RUNNING"
    && typeof r.summary?.net === "number").sort((a, b) => b.session_date.localeCompare(a.session_date) || b.id - a.id)[0];
  const strategySession = running
    ? { account: status.data?.account ?? "PAPER-1", net: status.data!.dayPnl!, day: status.data?.date ?? "", last: false }
    : lastSession ? { account: lastSession.account, net: lastSession.summary.net!, day: lastSession.session_date, last: true }
    : null;

  const shown = activeAccounts(accounts.data);
  const stopped = new Set((switches.data ?? []).filter((k) => k.scope === "ACCOUNT").map((k) => k.accountId));
  // real money and paper apart (review 17 Nov: one total added ZERODHA-1's LIVE rupees to the paper accounts'); each
  // account's book says which part of its day was entered LIVE (today.liveNet), the rest is paper
  const live = shown.reduce((s, a) => s + (a.today?.liveNet ?? 0), 0);
  const paper = shown.reduce((s, a) => s + (a.today?.net ?? 0) - (a.today?.liveNet ?? 0), 0);
  const hasLive = shown.some((a) => a.mode === "LIVE" || (a.today?.liveTrades ?? 0) > 0);
  const open = shown.reduce((s, a) => s + (a.today?.open ?? 0), 0);
  const logins = (board.data?.accounts ?? []).filter((r) => r.broker !== "PAPER");
  const spots = indexSpots(status.data);

  return (
    <div className="mx-auto flex max-w-xl flex-col gap-3">
      <div className="flex items-baseline justify-between">
        <h1 className="text-xl font-semibold">Today</h1>
        <Freshness at={accounts.dataUpdatedAt} />
      </div>
      {/* one line for the whole service being down (review 17 Nov: it showed once per request, in raw text) */}
      <ErrorLine error={accounts.error ?? (serviceDown(board.error) ? board.error : null)} />

      <Card>
        <div className="mb-1 flex items-center justify-between">
          <h2 className="text-sm font-semibold">{checklistTitle()}</h2>
          {board.data && !board.data.tradingDay && <span className="text-xs text-muted">market closed today</span>}
        </div>
        <ul className="flex flex-col gap-2 text-sm">
          {/* a member's trimmed status, or another shape, must never take Home down (review 17 Nov) */}
          {typeof status.data?.status === "string" && (status.data.status === "NO_SESSION" ? (
            // no session outside market hours is normal, not a warning (review 17 Nov: it read "Trading session no_session")
            <Check ok={null} text="No trading session now"
              note={status.data.schedule?.nextStart ? `next starts ${startsAt(status.data.schedule.nextStart)}` : null} />
          ) : (
            <Check ok={status.data.feed != null && (status.data.feedLagSeconds ?? 0) < 60}
              text={status.data.feed ? `Market data ${String(status.data.feed).toLowerCase()}` : `Trading session ${status.data.status.toLowerCase().replace(/_/g, " ")}`}
              note={status.data.feedLagSeconds != null ? `${Math.round(status.data.feedLagSeconds)} s behind` : status.data.date} />
          ))}
          {logins.map((r) => {
            const url = brokerLoginUrl(r.broker, r.accountId);
            return (
              <Check key={r.accountId} ok={r.ready}
                text={`${r.account} ${r.ready ? "logged in" : "not logged in"}`}
                note={r.ready ? (r.sessionUntil ? `until ${istTime(r.sessionUntil)}` : r.broker) : `${r.broker} · ${r.mode}`}
                action={!r.ready && (url
                  // same tab: the broker's page sends the browser back to Home after the login (phase 2)
                  ? <a className="min-h-11 rounded-lg bg-sky-700 px-3 py-2.5 text-sm font-semibold text-white" href={url}>Log in</a>
                  : <Link className="min-h-11 rounded-lg border border-line px-3 py-2.5 text-sm" to="/brokers">Log in</Link>)} />
            );
          })}
          {[...stopped].map((id) => {
            const a = accounts.data?.find((x) => x.id === id);
            const k = switches.data?.find((s) => s.scope === "ACCOUNT" && s.accountId === id);
            return a && <Check key={`k${id}`} ok={false} text={`${a.name}: new entries stopped`} note={k?.reason ?? ""} />;
          })}
          {board.data && logins.length === 0 && <li className="text-muted">No broker accounts: PAPER only.</li>}
          {push.state.data?.enabled && pushSupported() && push.here === null && push.permission !== "denied" && (
            <Check ok={false} text="Notifications off on this phone" note="login reminder, LIVE trades, stops"
              action={<Link className="min-h-11 rounded-lg border border-line px-3 py-2.5 text-sm" to="/notifications">Turn on</Link>} />
          )}
        </ul>
        {!serviceDown(board.error) && <ErrorLine error={board.error} />}
      </Card>

      {/* not without the accounts: "₹0 · 0 open" would read as a flat day (review 17 Nov) */}
      {accounts.data && (
      <Card>
        <div className="flex items-baseline justify-between">
          <span className="text-xs text-muted">Today, closed trades after charges</span>
          <span className="text-xs text-muted">{open} open</span>
        </div>
        {hasLive ? (
          <div className="mt-1 grid grid-cols-2 gap-3">
            <div><div className="text-xs font-semibold text-down">Real money (LIVE)</div>
              <div className={`num text-3xl font-semibold ${tone(live)}`}>{signed(live)}</div></div>
            <div><div className="text-xs font-semibold text-warn">Paper</div>
              <div className={`num text-2xl font-semibold ${tone(paper)}`}>{signed(paper)}</div></div>
          </div>
        ) : (
          <div><div className="text-xs font-semibold text-warn">Paper</div>
            <div className={`num text-3xl font-semibold ${tone(paper)}`}>{signed(paper)}</div></div>
        )}
        <ul className="mt-2 flex flex-col divide-y divide-line text-sm">
          {shown.map((a) => (
            <li key={a.id} className="flex items-center justify-between gap-2 py-2">
              <span className="min-w-0">
                <span className="flex items-center gap-2"><span className="truncate">{a.name}</span><ModeBadge mode={a.mode} />
                  {stopped.has(a.id) && <span className="text-[11px] font-semibold text-warn">stopped</span>}</span>
                <span className="text-xs text-muted">{a.today?.trades ?? 0} trades · {a.today?.open ?? 0} open</span>
              </span>
              <span className={`num shrink-0 ${tone(a.today?.net)}`}>{signed(a.today?.net ?? 0)}</span>
            </li>
          ))}
          {strategySession && (
            <li className="flex items-center justify-between gap-2 py-2">
              <span className="min-w-0"><span className="flex items-center gap-2"><span className="truncate">{strategySession.account}</span>
                <ModeBadge mode="PAPER" /></span><span className="text-xs text-muted">strategy session{strategySession.last
                  ? ` · last session ${shortDay(strategySession.day)}` : ""} · not in the totals</span></span>
              <span className={`num ${tone(strategySession.net)}`}>{signed(strategySession.net)}</span>
            </li>
          )}
        </ul>
        {open > 0 && <Link to="/positions" className="mt-2 block text-sm font-semibold text-sky-700">See {open} open position{open === 1 ? "" : "s"} →</Link>}
      </Card>
      )}

      {spots.length > 0 && (
        <Card>
          <ul className="flex flex-col gap-1 text-sm">
            {spots.map(([u, spot, at]) => (
              <li key={u} className="flex justify-between"><span className="text-muted">{u}</span>
                <span className="num font-semibold">{spot.toLocaleString("en-IN", { maximumFractionDigits: 1 })}
                  <span className="ml-2 text-xs font-normal text-muted">{at}</span></span></li>
            ))}
          </ul>
        </Card>
      )}

      {shown.some((a) => access.canStop(a.tenantId)) && (
        <button type="button" onClick={() => setStopping(true)}
          className="min-h-12 rounded-xl border-2 border-down bg-panel text-base font-semibold text-down">Stop new entries…</button>
      )}
      {stopping && <StopSheet accounts={shown.filter((a) => access.canStop(a.tenantId))} stopped={stopped}
        onClose={() => setStopping(false)} />}
    </div>
  );
}

/** ok: done (✓), false: needs doing (!), null: nothing to do now (·). */
function Check({ ok, text, note, action }: { ok: boolean | null; text: string; note?: string | null; action?: ReactNode }) {
  return (
    <li className="flex items-center gap-2">
      <span aria-hidden="true" className={`grid h-6 w-6 shrink-0 place-items-center rounded-full text-xs font-bold ${ok === null
        ? "bg-panel-2 text-muted" : ok ? "bg-up/15 text-up" : "bg-warn/20 text-warn"}`}>{ok === null ? "·" : ok ? "✓" : "!"}</span>
      <span className="min-w-0 flex-1"><span className={ok === false ? "font-semibold" : ""}>{text}</span>
        {note && <span className="block truncate text-xs text-muted">{note}</span>}</span>
      {action}
    </li>
  );
}

/** "17 Nov" from a yyyy-mm-dd day. */
function shortDay(day: string): string {
  const d = new Date(`${day}T00:00:00Z`);
  return Number.isNaN(d.getTime()) ? day : d.toLocaleDateString("en-IN", { timeZone: "UTC", day: "2-digit", month: "short" });
}

/** The checklist's heading by the IST clock: before the open, during the day, and for tomorrow after the close. */
function checklistTitle(): string {
  const [h, m] = new Date().toLocaleTimeString("en-GB", { timeZone: "Asia/Kolkata", hour12: false }).split(":").map(Number);
  const t = h * 60 + m;
  return t < 9 * 60 + 15 ? "Before 09:15" : t < 15 * 60 + 30 ? "Today" : "For tomorrow";
}

/** "09:00 tomorrow" / "09:00 Tue 18 Nov" (IST) from the scheduler's next start. */
function startsAt(iso: string): string {
  const d = new Date(iso.replace(/\[.*\]$/, ""));
  if (Number.isNaN(d.getTime())) return iso;
  const ist = (x: Date) => x.toLocaleDateString("en-CA", { timeZone: "Asia/Kolkata" });
  const time = d.toLocaleTimeString("en-IN", { hour12: false, timeZone: "Asia/Kolkata", hour: "2-digit", minute: "2-digit" });
  const tomorrow = ist(new Date(Date.now() + 86_400_000));
  if (ist(d) === ist(new Date())) return `${time} today`;
  if (ist(d) === tomorrow) return `${time} tomorrow`;
  return `${time} ${d.toLocaleDateString("en-IN", { timeZone: "Asia/Kolkata", weekday: "short", day: "2-digit", month: "short" })}`;
}

/** One spot per index from the session's strategies ("NIFTY · ecr-v11" keys hold the same spot), with its time. */
function indexSpots(status: Status | undefined): [string, number, string][] {
  const seen = new Map<string, [number, string]>();
  for (const [key, view] of Object.entries(status?.strategy ?? {})) {
    const u = key.split(" · ")[0];
    if (!seen.has(u) && Number.isFinite(view.spot) && view.spot > 0) seen.set(u, [view.spot, view.time?.slice(11, 19) ?? ""]);
  }
  return [...seen.entries()].map(([u, [s, t]]) => [u, s, t]);
}

/**
 * Stop new entries on chosen accounts: an ACCOUNT kill switch each (exits, stops and the square-off still run). The sheet
 * names every account and its mode; nothing is sold here (Flatten is on Positions).
 */
function StopSheet({ accounts, stopped, onClose }: { accounts: ExecutionAccount[]; stopped: Set<number | null>;
  onClose: () => void }) {
  const client = useQueryClient();
  const candidates = accounts.filter((a) => !stopped.has(a.id));
  const [picked, setPicked] = useState<Set<number>>(() => new Set(candidates.map((a) => a.id)));
  const [reason, setReason] = useState("");
  const [result, setResult] = useState<string[]>([]);
  const sending = useRef(false);
  const stop = useMutation({
    mutationFn: async () => {
      const out: string[] = [];
      for (const a of candidates.filter((x) => picked.has(x.id))) {
        try {
          await postJson("/api/execution/kill-switches", { scope: "ACCOUNT", accountId: a.id, reason: reason || "stopped from the phone" });
          out.push(`${a.name}: new entries stopped`);
        } catch (e) {
          out.push(`${a.name}: not stopped (${e instanceof Error ? e.message : String(e)})`);
        }
      }
      return out;
    },
    onSuccess: setResult,
    onSettled: () => { sending.current = false; client.invalidateQueries({ queryKey: ["exec-switches"] }); },
  });
  return (
    <Sheet title="Stop new entries" onClose={onClose}>
      {result.length > 0 ? (
        <div className="flex flex-col gap-3">
          <ul className="text-sm">{result.map((r) => <li key={r}>{r}</li>)}</ul>
          <Big onClick={onClose}>Done</Big>
        </div>
      ) : candidates.length === 0 ? (
        <div className="flex flex-col gap-3"><p className="text-sm text-muted">Every account you can stop is already stopped.
          Release a stop on the Accounts page.</p><Big onClick={onClose}>Close</Big></div>
      ) : (
        <div className="flex flex-col gap-3">
          <p className="text-sm text-muted">No new entries or adds. Open positions keep their stops and exits; nothing is sold.</p>
          <ul className="flex flex-col gap-2">
            {candidates.map((a) => (
              <li key={a.id}>
                <label className="flex min-h-11 items-center gap-3 rounded-lg border border-line px-3">
                  <input type="checkbox" className="h-5 w-5" checked={picked.has(a.id)} onChange={(e) => {
                    const next = new Set(picked);
                    if (e.target.checked) next.add(a.id); else next.delete(a.id);
                    setPicked(next);
                  }} />
                  <span className="flex-1">{a.name}</span><ModeBadge mode={a.mode} />
                </label>
              </li>
            ))}
          </ul>
          <input className="min-h-11 rounded-lg border border-line bg-bg px-3 text-sm" placeholder="Reason (optional)"
            value={reason} onChange={(e) => setReason(e.target.value)} aria-label="Reason" />
          <Big kind="danger" disabled={picked.size === 0 || stop.isPending} onClick={() => { if (!sending.current) { sending.current = true; stop.mutate(); } }}>
            {stop.isPending ? "Stopping…" : `Stop ${picked.size} account${picked.size === 1 ? "" : "s"}`}</Big>
          <Big onClick={onClose}>Cancel</Big>
        </div>
      )}
    </Sheet>
  );
}
