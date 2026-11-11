import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { get, postJson, type SupportGrant } from "../api";
import { ErrorNote, Panel, Table } from "../components/ui";
import { SupportBanner, useWorkspace } from "../workspace";

// Settings (trader workspaces, 7 Nov 2026): your own Telegram chat for login links and alerts, and who may help run your
// workspace (support access: your grant, time-limited; the admin's break-glass hour is shown here and can be ended).

const input = "rounded border border-line bg-bg px-2 py-1 text-sm";
const button = "rounded border border-line px-3 py-1 text-sm hover:bg-panel-2 disabled:opacity-40";
const when = (iso: string | null | undefined) => iso ? new Date(iso).toLocaleString("en-IN", { timeZone: "Asia/Kolkata",
  hour12: false, day: "2-digit", month: "short", hour: "2-digit", minute: "2-digit" }) : "–";

export function SettingsPage() {
  const client = useQueryClient();
  const { me, current } = useWorkspace();
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null);
  const [link, setLink] = useState<{ code: string; bot: string | null; link: string | null; send: string } | null>(null);
  const [hours, setHours] = useState("2");
  const [reason, setReason] = useState("");
  const done = (text: string, ok = true) => {
    setMessage({ ok, text });
    ["exec-me", "ws-support", "ws-today", "household"].forEach((k) => client.invalidateQueries({ queryKey: [k] }));
  };
  const fail = (e: unknown) => setMessage({ ok: false, text: e instanceof Error ? e.message : String(e) });
  const start = useMutation({
    mutationFn: () => postJson<{ code: string; bot: string | null; link: string | null; send: string }>("/api/execution/me/telegram/link", {}),
    onSuccess: (r) => setLink(r),
    onError: fail,
  });
  const check = useMutation({
    mutationFn: () => postJson<{ linked: boolean; hint?: string }>("/api/execution/me/telegram/check", {}),
    onSuccess: (r) => { if (r.linked) { setLink(null); done("Telegram linked: login links and alerts come to this chat"); }
      else setMessage({ ok: false, text: r.hint ?? "not linked yet" }); },
    onError: fail,
  });
  const call = useMutation({
    mutationFn: ({ path, body }: { path: string; body: unknown; ok: string }) => postJson<unknown>(path, body),
    onSuccess: (_d, v) => done(v.ok),
    onError: fail,
  });
  const grants = useQuery({ queryKey: ["ws-support", current?.id], enabled: current != null, refetchInterval: 30000,
    queryFn: () => get<SupportGrant[]>(`/api/execution/workspaces/${current!.id}/support`) });
  const owner = current?.role === "OWNER";

  return (
    <div className="space-y-4">
      <SupportBanner workspace={current} />
      {message && (
        <div className={`rounded border px-3 py-2 text-sm ${message.ok ? "border-up/40 text-up" : "border-down/40 text-down"}`}>
          {message.text} <button className="ml-2 text-muted" onClick={() => setMessage(null)}>dismiss</button>
        </div>
      )}
      <Panel title="Telegram">
        <p className="text-sm">{me?.telegramLinked ? "Linked: your login links and alerts come to your chat."
          : "Not linked: link your Telegram chat to get your broker login links, LIVE switches and alerts yourself."}</p>
        <div className="mt-3 flex flex-wrap items-center gap-2">
          <button className={button} disabled={start.isPending} onClick={() => start.mutate()}>
            {me?.telegramLinked ? "Link another chat" : "Link Telegram"}</button>
          {me?.telegramLinked && <button className={button} disabled={call.isPending}
            onClick={() => call.mutate({ path: "/api/execution/me/telegram/unlink", body: {}, ok: "Telegram unlinked" })}>Unlink</button>}
        </div>
        {link && (
          <div className="mt-3 rounded border border-accent/40 p-3 text-sm">
            <p>Send <code className="font-semibold">{link.send}</code> to {link.bot ? <code className="font-semibold">@{link.bot}</code> : "the bot"} in Telegram
              {link.link && <> (or open <a className="underline" href={link.link} target="_blank" rel="noreferrer">{link.link}</a> and press Start)</>},
              then press Check. The code works for 30 minutes.</p>
            <button className={`${button} mt-2`} disabled={check.isPending} onClick={() => check.mutate()}>Check</button>
          </div>
        )}
      </Panel>

      {current && (
        <Panel title={`Support access: ${current.name}`}>
          <p className="text-sm text-muted">The admin can always see your workspace and stop trouble (kill switch, PAPER,
            flatten). To change your brokers, capital, strategies or switch LIVE for you, they need your grant. Every change
            they make is in your audit.</p>
          {owner && (
            <div className="mt-3 flex flex-wrap items-center gap-2">
              <select className={input} value={hours} onChange={(e) => setHours(e.target.value)}>
                {[["1", "1 hour"], ["2", "2 hours"], ["8", "today (8 hours)"], ["24", "24 hours"], ["168", "7 days"]].map(([v, l]) =>
                  <option key={v} value={v}>{l}</option>)}
              </select>
              <button className={button} disabled={call.isPending} onClick={() => call.mutate({
                path: `/api/execution/workspaces/${current.id}/support`, body: { hours: Number(hours) },
                ok: `support access granted for ${hours} hour(s)` })}>Let the admin manage</button>
            </div>
          )}
          {current.support && (
            <div className="mt-3 flex flex-wrap items-center gap-2">
              <input className={`${input} w-80`} placeholder="reason (the member reads it)" value={reason}
                onChange={(e) => setReason(e.target.value)} />
              <button className="rounded border border-down/60 px-3 py-1 text-sm text-down disabled:opacity-40"
                disabled={reason.trim().length < 5 || call.isPending}
                onClick={() => { call.mutate({ path: `/api/execution/workspaces/${current.id}/support/break-glass`,
                  body: { reason: reason.trim() }, ok: "break-glass: one hour, the member was told" }); setReason(""); }}>
                Break-glass (1 hour)</button>
            </div>
          )}
          <div className="mt-3">
            {grants.error ? <ErrorNote error={grants.error} /> : (
              <Table rows={grants.data ?? []} empty="No grants yet." columns={[
                { key: "t", label: "To", render: (g) => g.granted_to },
                { key: "b", label: "By", render: (g) => g.break_glass ? <span className="text-down">break-glass</span> : g.granted_by },
                { key: "r", label: "Reason", render: (g) => g.reason ?? "–" },
                { key: "f", label: "From", render: (g) => when(g.granted_at) },
                { key: "u", label: "Until", render: (g) => when(g.revoked_at ?? g.expires_at) },
                { key: "s", label: "", render: (g) => g.active ? (owner || current.support ? <button className={button}
                  disabled={call.isPending} onClick={() => call.mutate({ path: `/api/execution/workspaces/${current.id}/support/${g.id}/revoke`,
                    body: {}, ok: "access ended" })}>End now</button> : "active") : <span className="text-muted">ended</span> },
              ]} />
            )}
          </div>
        </Panel>
      )}
    </div>
  );
}
