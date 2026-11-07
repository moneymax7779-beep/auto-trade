import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { useNavigate } from "react-router";
import { get, postJson, type HouseholdCard } from "../api";
import { ErrorNote, Loading, Panel, Pnl } from "../components/ui";
import { useWorkspace } from "../workspace";
import { MembersPanel } from "./MembersPanel";

// Household (trader workspaces, 7 Nov 2026): the platform admin watches every family workspace on one screen without
// operating any of them. Stop buttons (PAPER, kill) work without the member's grant; anything else opens their workspace.

const button = "rounded border border-line px-2 py-1 text-xs hover:bg-panel-2 disabled:opacity-40";
const time = (iso: string | null | undefined) =>
  iso ? new Date(iso).toLocaleTimeString("en-IN", { hour12: false, timeZone: "Asia/Kolkata", hour: "2-digit",
    minute: "2-digit" }) : "–";

export function HouseholdPage() {
  const client = useQueryClient();
  const navigate = useNavigate();
  const { select } = useWorkspace();
  const cards = useQuery({ queryKey: ["household"], refetchInterval: 10000,
    queryFn: () => get<HouseholdCard[]>("/api/execution/household") });
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null);
  const call = useMutation({
    mutationFn: ({ path, body }: { path: string; body: unknown; ok: string }) => postJson<unknown>(path, body),
    onSuccess: (_d, v) => { setMessage({ ok: true, text: v.ok }); client.invalidateQueries({ queryKey: ["household"] }); },
    onError: (e) => setMessage({ ok: false, text: e instanceof Error ? e.message : String(e) }),
  });
  const open = (id: number, to: string) => { select(id); navigate(to); };

  return (
    <div className="space-y-4">
      {message && (
        <div className={`rounded border px-3 py-2 text-sm ${message.ok ? "border-up/40 text-up" : "border-down/40 text-down"}`}>
          {message.text} <button className="ml-2 text-muted" onClick={() => setMessage(null)}>dismiss</button>
        </div>
      )}
      <Panel title="Family workspaces today">
        {cards.isLoading ? <Loading what="workspaces" /> : cards.error ? <ErrorNote error={cards.error} /> : (
          <div className="grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-3">
            {(cards.data ?? []).length === 0 && <p className="text-sm text-muted">No family workspace yet: add a member below.</p>}
            {(cards.data ?? []).map((c) => {
              const s = c.state;
              const trading = s?.trading ?? "OFF";
              const tenantKill = c.killSwitches.find((k) => k.scope === "TENANT");
              return (
                <div key={c.tenantId} className="rounded border border-line p-3 text-sm">
                  <div className="flex items-center gap-2">
                    <span className="font-semibold">{c.name}</span>
                    <span className="text-xs text-muted">{c.members.map((m) => m.display_name).join(", ")}</span>
                    <span className={`ml-auto rounded px-2 py-0.5 text-xs font-bold ${trading === "LIVE" ? "bg-down text-white"
                      : trading === "PAPER" ? "bg-warn/20 text-warn" : "bg-panel-2 text-muted"}`}>{trading}</span>
                  </div>
                  <div className="mt-2 space-y-1">
                    <div><span className="text-muted">Broker </span>{s?.broker ?? "Off"}
                      {s?.mode === "LIVE" && <span className="text-muted"> · LIVE since {time(s.since)}</span>}</div>
                    <div><span className="text-muted">Today </span>paper <Pnl value={c.today.paperNet} /> ({c.today.paperOpen} open) ·
                      live <Pnl value={c.today.liveNet} /> ({c.today.liveOpen} open)</div>
                    <div><span className="text-muted">Loss limit </span>{c.lossLimit.dailyLossLimit == null ? "not set"
                      : `₹${Math.round(c.lossLimit.dailyLossLimit).toLocaleString("en-IN")}`}
                      {c.lossLimit.reached && <span className="text-down"> · reached</span>}</div>
                    {tenantKill && <div className="text-down">Kill switch: {tenantKill.reason} ({tenantKill.engaged_by})</div>}
                    {c.support.length > 0 && <div className="text-warn">Support access until {time(c.support[0].expires_at)}
                      {c.support[0].break_glass ? " (break-glass)" : ""}</div>}
                    {c.pending.length > 0 && <div className="text-muted">Setup: {c.pending.map((p) => `${p.name} needs ${
                      p.needs === "BROKER_APP" ? "a broker app" : p.needs === "CLIENT_ID" ? "a client id" : "a first login"}`).join("; ")}</div>}
                  </div>
                  <div className="mt-3 flex flex-wrap gap-1">
                    <button className={button} onClick={() => open(c.tenantId, "/today")}>Open</button>
                    <button className={button} onClick={() => open(c.tenantId, "/brokers")}>Brokers</button>
                    <button className={button} onClick={() => open(c.tenantId, "/settings")}>Access</button>
                    {s?.mode === "LIVE" && <button className={button} disabled={call.isPending}
                      onClick={() => window.confirm(`Switch ${c.name} to PAPER now?`) && call.mutate({
                        path: `/api/execution/workspaces/${c.tenantId}/today/mode`, body: { mode: "PAPER" }, ok: `${c.name}: PAPER` })}>
                      To PAPER</button>}
                    {!tenantKill && <button className={`${button} text-down`} disabled={call.isPending} onClick={() => {
                      const reason = window.prompt(`Stop new entries for ${c.name}: reason?`);
                      if (reason && reason.trim()) call.mutate({ path: "/api/execution/kill-switches", body: { scope: "TENANT",
                        tenantId: c.tenantId, reason: reason.trim() }, ok: `${c.name}: kill switch engaged` });
                    }}>Kill</button>}
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </Panel>
      <MembersPanel onDone={(text, ok) => setMessage({ ok, text })} />
    </div>
  );
}
