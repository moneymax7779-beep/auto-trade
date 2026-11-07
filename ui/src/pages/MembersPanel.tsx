import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { get, postJson, type ExecMe, type MemberTenant } from "../api";
import { ErrorNote, Panel, Table } from "../components/ui";

// Family members (user, 29 Oct 2026: "family only"; trader workspaces, 7 Nov 2026), platform admin only. Each member gets a
// workspace of their own that follows your live signals; they run it themselves (brokers, daily login, PAPER/LIVE) and
// you are SUPPORT there. With Keycloak administration configured their login is created here: a one-time password is
// shown once (or Keycloak emails them), and they set their own password and authenticator app at first sign-in.

const input = "rounded border border-line bg-bg px-2 py-1 text-sm";
const button = "rounded border border-line px-2 py-0.5 text-xs hover:bg-panel-2 disabled:opacity-40";
const RELATIONSHIPS = [["SPOUSE", "Spouse"], ["CHILD", "Child"], ["PARENT", "Parent"], ["SIBLING", "Sibling"]] as const;
const when = (iso: string | null | undefined) => iso ? new Date(iso).toLocaleString("en-IN", { timeZone: "Asia/Kolkata",
  hour12: false, day: "2-digit", month: "short", hour: "2-digit", minute: "2-digit" }) : "never";

export function MembersPanel({ onDone }: { onDone: (text: string, ok: boolean) => void }) {
  const client = useQueryClient();
  const members = useQuery({ queryKey: ["exec-members"], queryFn: () => get<MemberTenant[]>("/api/execution/admin/members") });
  const me = useQuery({ queryKey: ["exec-me"], queryFn: () => get<ExecMe>("/api/execution/me"), staleTime: 30_000 });
  const keycloak = me.data?.keycloakAdmin === true;
  const [username, setUsername] = useState("");
  const [display, setDisplay] = useState("");
  const [role, setRole] = useState("OWNER");
  const [relationship, setRelationship] = useState("");
  const [email, setEmail] = useState("");
  const [existing, setExisting] = useState(false);
  const [secret, setSecret] = useState<{ who: string; password: string } | null>(null);
  const done = (text: string) => {
    onDone(text, true);
    ["exec-members", "exec-me", "exec-accounts", "exec-audit", "household"].forEach((k) => client.invalidateQueries({ queryKey: [k] }));
  };
  const fail = (e: unknown) => onDone(e instanceof Error ? e.message : String(e), false);
  const add = useMutation({
    mutationFn: () => postJson<{ username: string; tenantId: number; delivery: string; oneTimePassword?: string }>(
      "/api/execution/admin/members", { username: username.trim().toLowerCase(), displayName: display.trim() || null, role,
        relationship, email: email.trim() || null, login: keycloak && !existing ? "CREATE" : "EXISTING" }),
    onSuccess: (r) => {
      if (r.oneTimePassword) setSecret({ who: r.username, password: r.oneTimePassword });
      done(`${r.username} added (workspace ${r.tenantId}): ${r.delivery === "EMAIL" ? "Keycloak emailed them their link"
        : r.delivery === "TEMP_PASSWORD" ? "give them the one-time password below" : "their existing Keycloak login is linked"}`);
      setUsername(""); setDisplay(""); setEmail(""); setRelationship("");
    },
    onError: fail,
  });
  const act = useMutation({
    mutationFn: ({ path, body }: { path: string; body: unknown; ok: string; who: string }) =>
      postJson<{ oneTimePassword?: string }>(path, body),
    onSuccess: (r, v) => { if (r?.oneTimePassword) setSecret({ who: v.who, password: r.oneTimePassword }); done(v.ok); },
    onError: fail,
  });
  const rows = (members.data ?? []).flatMap((t) => t.members.filter((m) => m.role !== "SUPPORT" && m.role !== "ADMIN")
    .map((m) => ({ ...m, tenant: t.tenant, accounts: t.accounts })));

  return (
    <Panel title="Family members" right={<span className="text-xs text-muted">admin</span>}>
      {secret && (
        <div className="mb-3 rounded border border-warn/60 bg-warn/10 p-3 text-sm">
          One-time password for <b>{secret.who}</b>: <code className="select-all font-semibold">{secret.password}</code>
          <p className="mt-1 text-xs text-muted">Shown once and stored nowhere. Give it to them in person or by phone; at first
            sign-in they choose their own password and set up their authenticator app.</p>
          <button className={`${button} mt-2`} onClick={() => setSecret(null)}>I have passed it on: hide</button>
        </div>
      )}
      {members.error ? <ErrorNote error={members.error} /> : (
        <Table rows={rows} empty="No members yet." columns={[
          { key: "u", label: "Login", render: (m) => <span className="font-semibold">{m.username}</span> },
          { key: "n", label: "Name", render: (m) => m.display_name },
          { key: "rel", label: "Relationship", render: (m) => m.relationship ?? "–" },
          { key: "t", label: "Workspace", render: (m) => m.tenant },
          { key: "r", label: "Role", render: (m) => m.role },
          { key: "a", label: "Brokers", align: "right", render: (m) => m.accounts },
          { key: "seen", label: "Last seen", render: (m) => <span className="text-xs">{m.invite === "PENDING" ? "invited" : when(m.last_seen)}</span> },
          { key: "tg", label: "Telegram", render: (m) => m.telegram ? "linked" : <span className="text-muted">no</span> },
          { key: "s", label: "Status", render: (m) => <span className={m.status === "ACTIVE" ? "text-up" : "text-down"}>{m.status}</span> },
          { key: "x", label: "", render: (m) => (
            <span className="flex flex-wrap gap-1">
              <button className={button} disabled={act.isPending} onClick={() => act.mutate({ who: m.username,
                path: `/api/execution/admin/members/${encodeURIComponent(m.username)}/status`,
                body: { status: m.status === "ACTIVE" ? "DISABLED" : "ACTIVE" }, ok: `${m.username}: ${m.status === "ACTIVE" ? "disabled" : "enabled"}` })}>
                {m.status === "ACTIVE" ? "Disable" : "Enable"}</button>
              {keycloak && <button className={button} disabled={act.isPending}
                onClick={() => window.confirm(`New one-time password for ${m.username}? Their current password stops working.`)
                  && act.mutate({ who: m.username, path: `/api/execution/admin/members/${encodeURIComponent(m.username)}/one-time-password`,
                    body: {}, ok: `${m.username}: new one-time password` })}>New password</button>}
              {keycloak && <button className={button} disabled={act.isPending}
                onClick={() => window.confirm(`Remove ${m.username}'s authenticator app (lost phone)? They set up a new one at next sign-in.`)
                  && act.mutate({ who: m.username, path: `/api/execution/admin/members/${encodeURIComponent(m.username)}/reset-authenticator`,
                    body: {}, ok: `${m.username}: authenticator removed, they enrol again` })}>Reset authenticator</button>}
              {!m.telegram && <button className={button} disabled={act.isPending} onClick={() => {
                const chat = window.prompt(`${m.username}'s Telegram chat id (digits), if the bot link does not work:`);
                if (chat && chat.trim()) act.mutate({ who: m.username, path: `/api/execution/admin/members/${encodeURIComponent(m.username)}/telegram`,
                  body: { chatId: chat.trim() }, ok: `${m.username}: Telegram chat set` });
              }}>Set chat id</button>}
            </span>) },
        ]} />
      )}
      <div className="mt-3 border-t border-line pt-3">
        <p className="mb-2 text-xs text-muted">
          Family only. {keycloak ? "Their login is created here." : "First create their login in Keycloak (username, no operator role); then add them here with the same username."}
          {" "}They get their own workspace that follows your live signals and run it themselves (OWNER); VIEWER only reads.
          You are SUPPORT there: you can always stop trouble; for anything else they grant you access on their Settings page.
        </p>
        <div className="flex flex-wrap items-center gap-2">
          <input className={input} placeholder="username" value={username} onChange={(e) => setUsername(e.target.value)} />
          <input className={input} placeholder="display name" value={display} onChange={(e) => setDisplay(e.target.value)} />
          <select className={input} value={relationship} onChange={(e) => setRelationship(e.target.value)}>
            <option value="">relationship…</option>
            {RELATIONSHIPS.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
          </select>
          <select className={input} value={role} onChange={(e) => setRole(e.target.value)}>
            <option value="OWNER">OWNER (runs it)</option>
            <option value="TRADER">TRADER</option>
            <option value="VIEWER">VIEWER</option>
          </select>
          {keycloak && !existing && <input className={input} placeholder="email (optional)" value={email}
            onChange={(e) => setEmail(e.target.value)} />}
          {keycloak && <label className="flex items-center gap-1 text-xs text-muted"><input type="checkbox" checked={existing}
            onChange={(e) => setExisting(e.target.checked)} /> login already exists</label>}
          <button className="rounded bg-accent px-3 py-1 text-sm font-semibold text-white disabled:opacity-40"
            disabled={username.trim().length < 3 || relationship === "" || add.isPending} onClick={() => add.mutate()}>
            {add.isPending ? "Adding…" : "Add member"}</button>
        </div>
      </div>
    </Panel>
  );
}
