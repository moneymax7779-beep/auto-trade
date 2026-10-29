import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { get, postJson, type MemberTenant } from "../api";
import { ErrorNote, Panel, Table } from "../components/ui";

// Family members (user, 29 Oct 2026: "family only"), ADMIN only. Each member gets a tenant of their own, so their
// capital, limits, kill switches, positions and audit stay apart from yours; you are ADMIN of it too, and it follows your
// signal groups. Their login (password, authenticator app) is made in Keycloak first: nothing here handles a password.

const input = "rounded border border-line bg-bg px-2 py-1 text-sm";
const button = "rounded border border-line px-2 py-0.5 text-xs hover:bg-panel-2 disabled:opacity-40";

export function MembersPanel({ onDone }: { onDone: (text: string, ok: boolean) => void }) {
  const client = useQueryClient();
  const members = useQuery({ queryKey: ["exec-members"], queryFn: () => get<MemberTenant[]>("/api/execution/admin/members") });
  const [username, setUsername] = useState("");
  const [display, setDisplay] = useState("");
  const [role, setRole] = useState("TRADER");
  const done = (text: string) => {
    onDone(text, true);
    ["exec-members", "exec-me", "exec-accounts", "exec-audit"].forEach((k) => client.invalidateQueries({ queryKey: [k] }));
  };
  const fail = (e: unknown) => onDone(e instanceof Error ? e.message : String(e), false);
  const add = useMutation({
    mutationFn: () => postJson<{ username: string; tenantId: number }>("/api/execution/admin/members",
      { username: username.trim().toLowerCase(), displayName: display.trim() || null, role }),
    onSuccess: (r) => { done(`${r.username} added (tenant ${r.tenantId})`); setUsername(""); setDisplay(""); },
    onError: fail,
  });
  const setStatus = useMutation({
    mutationFn: ({ who, status }: { who: string; status: string }) =>
      postJson<{ username: string; status: string }>(`/api/execution/admin/members/${encodeURIComponent(who)}/status`, { status }),
    onSuccess: (r) => done(`${r.username}: ${r.status}`),
    onError: fail,
  });
  const rows = (members.data ?? []).flatMap((t) => t.members.map((m) => ({ ...m, tenant: t.tenant, tenantId: t.tenantId,
    accounts: t.accounts, follows: t.follows.map((g) => `${g.name} (${g.owner})`).join(", ") })));

  return (
    <Panel title="Family members" right={<span className="text-xs text-muted">admin</span>}>
      {members.error ? <ErrorNote error={members.error} /> : (
        <Table rows={rows} empty="No members yet." columns={[
          { key: "u", label: "Login", render: (m) => <span className="font-semibold">{m.username}</span> },
          { key: "n", label: "Name", render: (m) => m.display_name },
          { key: "t", label: "Tenant", render: (m) => m.tenant },
          { key: "r", label: "Role", render: (m) => m.role },
          { key: "a", label: "Accounts", align: "right", render: (m) => m.accounts },
          { key: "f", label: "Follows", render: (m) => <span className="text-xs text-muted">{m.follows || "–"}</span> },
          { key: "s", label: "Status", render: (m) => <span className={m.status === "ACTIVE" ? "text-up" : "text-down"}>{m.status}</span> },
          { key: "x", label: "", render: (m) => m.role === "ADMIN" ? null : (
            <button className={button} disabled={setStatus.isPending}
              onClick={() => setStatus.mutate({ who: m.username, status: m.status === "ACTIVE" ? "DISABLED" : "ACTIVE" })}>
              {m.status === "ACTIVE" ? "Disable" : "Enable"}</button>) },
        ]} />
      )}
      <div className="mt-3 border-t border-line pt-3">
        <p className="mb-2 text-xs text-muted">
          First create their login in Keycloak (username, password, authenticator app; no operator role). Then add them here
          with the same username: they get their own tenant that follows your live signals, and they see only their own
          accounts. OWNER may also change their accounts' capital and strategies; TRADER may log in to the broker, kill and
          flatten; VIEWER only reads. Add their accounts below with "Add account" and choose their tenant.
        </p>
        <div className="flex flex-wrap items-center gap-2">
          <input className={input} placeholder="keycloak username" value={username} onChange={(e) => setUsername(e.target.value)} />
          <input className={input} placeholder="display name" value={display} onChange={(e) => setDisplay(e.target.value)} />
          <select className={input} value={role} onChange={(e) => setRole(e.target.value)}>
            <option value="TRADER">TRADER</option>
            <option value="OWNER">OWNER</option>
            <option value="VIEWER">VIEWER</option>
          </select>
          <button className="rounded bg-accent px-3 py-1 text-sm font-semibold text-white disabled:opacity-40"
            disabled={username.trim().length < 3 || add.isPending} onClick={() => add.mutate()}>
            {add.isPending ? "Adding…" : "Add member"}</button>
        </div>
      </div>
    </Panel>
  );
}
