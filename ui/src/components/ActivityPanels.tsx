import { useQuery } from "@tanstack/react-query";
import { Fragment, useState } from "react";
import { get } from "../api";
import { ErrorNote, Loading, Panel } from "./ui";

// User activity (plan docs/plans/2026-11-23-user-activity-logging-plan.md; user 23 Nov 2026: "from which browser, how
// many open sessions, how much time on the application"). The owner's view of everyone, and each user's own devices.

interface SignIn {
  id: number; signed_in_at: string; last_seen_at: string; ended_at: string | null; end_reason: string | null;
  ip: string | null; browser: string | null; browser_version: string | null; os: string | null; os_version: string | null;
  device_type: string | null; installed_app: boolean; tabs?: number; paths?: string | null; current?: boolean;
  visible_seconds?: number; active_seconds?: number; forwarded_chain?: string | null;
}
interface DayRow { day: string; visible_seconds: number; active_seconds: number; logins: number; tabs: number }
interface UserRow {
  subject: string; name: string; online: boolean; openSignIns: number; openTabs: number; todayVisibleSeconds: number;
  todayActiveSeconds: number; weekVisibleSeconds: number; lastSeen: string | null; signIns: SignIn[]; days: DayRow[];
}
interface Overview { enabled: boolean; users: UserRow[]; days?: number; recorder?: Record<string, unknown> }

/** "2h 05m", "12m", "45s", "–" */
export function duration(seconds: number | null | undefined): string {
  if (!seconds) return "–";
  const h = Math.floor(seconds / 3600), m = Math.floor((seconds % 3600) / 60);
  return h > 0 ? `${h}h ${String(m).padStart(2, "0")}m` : m > 0 ? `${m}m` : `${seconds}s`;
}

const when = (iso: string | null | undefined) => iso ? new Date(iso).toLocaleString("en-IN", { timeZone: "Asia/Kolkata",
  day: "numeric", month: "short", hour: "2-digit", minute: "2-digit", hour12: false }) : "–";

export function device(s: SignIn): string {
  const b = [s.browser ?? "Other", s.browser_version].filter(Boolean).join(" ");
  const o = [s.os ?? "Other", s.os_version].filter(Boolean).join(" ");
  const kind = s.device_type === "MOBILE" ? "phone" : s.device_type === "TABLET" ? "tablet"
    : s.device_type === "DESKTOP" ? "computer" : "device";
  return `${b} on ${o} (${kind}${s.installed_app ? ", installed app" : ""})`;
}

const ended = (s: SignIn) => s.ended_at == null ? <span className="text-up">open</span>
  : <span className="text-muted">{({ LOGOUT: "signed out", IDLE: "idle", MAX_AGE: "12 h limit", REVOKED: "signed out by admin" } as Record<string, string>)[s.end_reason ?? ""] ?? s.end_reason} {when(s.ended_at)}</span>;

/** The last {@code n} days (IST) oldest first, as YYYY-MM-DD. */
function lastDays(n: number): string[] {
  const today = new Date(new Date().toLocaleString("en-US", { timeZone: "Asia/Kolkata" }));
  return Array.from({ length: n }, (_, i) => {
    const d = new Date(today);
    d.setDate(today.getDate() - (n - 1 - i));
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
  });
}

/** One small bar of time on screen a day, every day of the window (a day without use is an empty slot). */
function Bars({ days, n = 30 }: { days: DayRow[]; n?: number }) {
  const by = new Map(days.map((d) => [d.day, d]));
  const all = lastDays(n).map((day) => by.get(day) ?? { day, visible_seconds: 0, active_seconds: 0, logins: 0, tabs: 0 });
  const max = Math.max(60, ...all.map((d) => d.visible_seconds));
  return (
    <div className="flex h-10 items-end gap-0.5" aria-label="time on screen per day">
      {all.map((d) => (
        <div key={d.day} title={`${d.day}: ${duration(d.visible_seconds)} on screen, ${duration(d.active_seconds)} in use`}
          className="w-2 rounded-t bg-accent/70" style={{ height: `${Math.max(2, (d.visible_seconds / max) * 40)}px` }} />
      ))}
    </div>
  );
}

function UserSignIns({ subject }: { subject: string }) {
  const q = useQuery({ queryKey: ["activity-sign-ins", subject],
    queryFn: () => get<SignIn[]>(`/api/activity/sign-ins?subject=${encodeURIComponent(subject)}&days=30`) });
  if (q.isLoading) return <Loading what="sign-ins" />;
  if (q.error) return <ErrorNote error={q.error} />;
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-xs">
        <thead className="text-left text-muted">
          <tr><th className="py-1 pr-3">Signed in</th><th className="pr-3">Device</th><th className="pr-3">Address</th>
            <th className="pr-3">Last seen</th><th className="pr-3">Ended</th><th className="pr-3 text-right">Tabs</th>
            <th className="pr-3 text-right">On screen</th><th className="text-right">In use</th></tr>
        </thead>
        <tbody>
          {(q.data ?? []).map((s) => (
            <tr key={s.id} className="border-t border-line">
              <td className="py-1 pr-3 num">{when(s.signed_in_at)}</td>
              <td className="pr-3">{device(s)}</td>
              <td className="pr-3 num" title={s.forwarded_chain ? `forwarded: ${s.forwarded_chain}` : undefined}>{s.ip ?? "–"}</td>
              <td className="pr-3 num">{when(s.last_seen_at)}</td>
              <td className="pr-3">{ended(s)}</td>
              <td className="pr-3 text-right num">{s.tabs ?? 0}</td>
              <td className="pr-3 text-right num">{duration(s.visible_seconds)}</td>
              <td className="text-right num">{duration(s.active_seconds)}</td>
            </tr>
          ))}
          {(q.data ?? []).length === 0 && <tr><td colSpan={8} className="py-2 text-muted">No sign-in in 30 days.</td></tr>}
        </tbody>
      </table>
    </div>
  );
}

/** The owner's view: who is signed in now, on what, with how many tabs, and time on screen per day. */
export function ActivityPanel() {
  const q = useQuery({ queryKey: ["activity"], refetchInterval: 30_000,
    queryFn: () => get<Overview>("/api/activity?days=30") });
  const [open, setOpen] = useState<string | null>(null);
  return (
    <Panel title="Activity · sign-ins and time on the app" collapsible="family-activity"
      right={<span className="text-xs text-muted">on screen = the app in the foreground; in use = with input in the last 2 min</span>}>
      {q.isLoading ? <Loading what="activity" /> : q.error ? <ErrorNote error={q.error} /> : !q.data?.enabled
        ? <p className="text-sm text-muted">Activity is not recorded on this server (sign-in is off).</p> : (
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="text-left text-xs text-muted">
              <tr><th className="py-1 pr-3">User</th><th className="pr-3 text-right">Open sign-ins</th>
                <th className="pr-3 text-right">Tabs</th><th className="pr-3 text-right">Today on screen</th>
                <th className="pr-3 text-right">in use</th><th className="pr-3 text-right">7 days</th>
                <th className="pr-3">Last seen</th><th className="pr-3">Devices now</th><th>30 days</th></tr>
            </thead>
            <tbody>
              {q.data.users.map((u) => (
                <Fragment key={u.subject}>
                  <tr className="cursor-pointer border-t border-line hover:bg-panel-2" onClick={() => setOpen(open === u.subject ? null : u.subject)}>
                    <td className="py-1.5 pr-3">
                      <span className={`mr-1.5 inline-block h-2 w-2 rounded-full align-middle ${u.online ? "bg-up" : "bg-line"}`}
                        title={u.online ? "online: a tab of the app open now (a request in the last 2 minutes)" : "no tab open"} />
                      <button type="button" className="font-semibold hover:underline" aria-expanded={open === u.subject}
                        onClick={(e) => { e.stopPropagation(); setOpen(open === u.subject ? null : u.subject); }}>{u.name}</button>
                      {u.name !== u.subject && <span className="ml-1 text-xs text-muted">{u.subject}</span>}
                    </td>
                    <td className="pr-3 text-right num">{u.openSignIns}</td>
                    <td className="pr-3 text-right num">{u.openTabs}</td>
                    <td className="pr-3 text-right num">{duration(u.todayVisibleSeconds)}</td>
                    <td className="pr-3 text-right num">{duration(u.todayActiveSeconds)}</td>
                    <td className="pr-3 text-right num">{duration(u.weekVisibleSeconds)}</td>
                    <td className="pr-3 num">{when(u.lastSeen)}</td>
                    <td className="pr-3 text-xs">{u.signIns.length === 0 ? <span className="text-muted">–</span>
                      : u.signIns.map((s) => <div key={s.id}>{device(s)}{s.tabs ? ` · ${s.tabs} tab${s.tabs > 1 ? "s" : ""}` : ""}</div>)}</td>
                    <td><Bars days={u.days} /></td>
                  </tr>
                  {open === u.subject && (
                    <tr><td colSpan={9} className="bg-panel-2/40 px-2 py-2"><UserSignIns subject={u.subject} /></td></tr>
                  )}
                </Fragment>
              ))}
              {q.data.users.length === 0 && <tr><td colSpan={9} className="py-2 text-muted">Nobody has signed in yet.</td></tr>}
            </tbody>
          </table>
          <p className="mt-2 text-xs text-muted">Click a user for their sign-ins of the last 30 days. Time counts only while
            the app is on screen; two tabs side by side count once.</p>
        </div>
      )}
    </Panel>
  );
}

/** Each user's own sign-ins (Settings), with what is recorded and for how long. */
export function MyDevicesPanel() {
  const q = useQuery({ queryKey: ["my-devices"], refetchInterval: 60_000,
    queryFn: () => get<{ enabled: boolean; signIns: SignIn[] }>("/api/me/devices") });
  if (q.data && !q.data.enabled) return null;
  return (
    <Panel title="Your devices" collapsible="my-devices">
      <p className="mb-2 text-xs text-muted">auto-trade records your sign-ins (browser, device and address) and how long the
        app is on screen, to show you and the platform owner. Sign-ins are kept 180 days, addresses shortened after 30 days.</p>
      {q.isLoading ? <Loading what="devices" /> : q.error ? <ErrorNote error={q.error} /> : (
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="text-left text-xs text-muted">
              <tr><th className="py-1 pr-3">Device</th><th className="pr-3">Address</th><th className="pr-3">Signed in</th>
                <th className="pr-3">Last seen</th><th>Status</th></tr>
            </thead>
            <tbody>
              {(q.data?.signIns ?? []).map((s) => (
                <tr key={s.id} className="border-t border-line">
                  <td className="py-1.5 pr-3">{device(s)}{s.current && <span className="ml-2 rounded bg-accent/15 px-1.5 text-xs text-accent">this device</span>}</td>
                  <td className="pr-3 num">{s.ip ?? "–"}</td>
                  <td className="pr-3 num">{when(s.signed_in_at)}</td>
                  <td className="pr-3 num">{when(s.last_seen_at)}</td>
                  <td>{ended(s)}</td>
                </tr>
              ))}
              {(q.data?.signIns ?? []).length === 0 && <tr><td colSpan={5} className="py-2 text-muted">No sign-in recorded yet.</td></tr>}
            </tbody>
          </table>
        </div>
      )}
    </Panel>
  );
}
