import { useQuery } from "@tanstack/react-query";
import { createContext, type ReactNode, useContext, useEffect, useState } from "react";
import { get, type ExecMe } from "./api";

// Trader workspaces (7 Nov 2026): one workspace on screen at a time. A family member has their own; the platform admin
// picks one of the family workspaces they support (Household page or the header). The choice is a per-browser
// convenience (localStorage); every call still names the workspace and the server checks it.

type Workspace = ExecMe["tenants"][number];

interface WorkspaceState {
  me: ExecMe | undefined;
  /** Workspaces with a daily switch the caller sees (members: their own; the admin: those they support). */
  workspaces: Workspace[];
  current: Workspace | undefined;
  select: (id: number) => void;
}

const Context = createContext<WorkspaceState>({ me: undefined, workspaces: [], current: undefined, select: () => {} });
const KEY = "autotrade.workspace";

function stored(): number | null {
  try {
    const v = window.localStorage.getItem(KEY);
    return v == null ? null : Number(v);
  } catch {
    return null;
  }
}

export function WorkspaceProvider({ children }: { children: ReactNode }) {
  const me = useQuery({ queryKey: ["exec-me"], queryFn: () => get<ExecMe>("/api/execution/me"), staleTime: 30_000,
    retry: false });
  const workspaces = (me.data?.tenants ?? []).filter((t) => t.control === "DAILY_SWITCH");
  const [chosen, setChosen] = useState<number | null>(stored);
  useEffect(() => {
    if (workspaces.length > 0 && !workspaces.some((w) => w.id === chosen)) setChosen(workspaces[0].id);
  }, [workspaces, chosen]);
  const select = (id: number) => {
    setChosen(id);
    try {
      window.localStorage.setItem(KEY, String(id));
    } catch {
      // private window: the choice lasts for this page only
    }
  };
  return (
    <Context.Provider value={{ me: me.data, workspaces, current: workspaces.find((w) => w.id === chosen), select }}>
      {children}
    </Context.Provider>
  );
}

export function useWorkspace(): WorkspaceState {
  return useContext(Context);
}

/** The header's workspace picker: shown when there is more than one to choose from. */
export function WorkspaceSwitcher() {
  const { workspaces, current, select } = useWorkspace();
  if (workspaces.length < 2) return null;
  return (
    <select className="max-w-[12rem] shrink-0 rounded border border-line bg-bg px-2 py-1 text-sm" value={current?.id ?? ""}
      title="whose workspace the Today, Brokers and Settings pages show" onChange={(e) => select(Number(e.target.value))}>
      {workspaces.map((w) => (
        <option key={w.id} value={w.id}>{w.name}{w.support ? (w.helping ? " (helping)" : " (support)") : ""}</option>
      ))}
    </select>
  );
}

/** A banner on every workspace page while the platform admin is in a member's workspace. */
export function SupportBanner({ workspace }: { workspace: Workspace | undefined }) {
  if (!workspace?.support) return null;
  return (
    <div className={`mb-3 rounded border px-3 py-2 text-sm ${workspace.helping ? "border-warn/50 bg-warn/10"
      : "border-line bg-panel-2"}`}>
      {workspace.helping
        ? <>Acting in <b>{workspace.name}</b>'s workspace with their grant
          {workspace.grantUntil ? ` until ${new Date(workspace.grantUntil).toLocaleString("en-IN", { timeZone: "Asia/Kolkata",
            hour12: false, day: "2-digit", month: "short", hour: "2-digit", minute: "2-digit" })}` : ""}: every change is
          recorded in their audit.</>
        : <>Viewing <b>{workspace.name}</b>'s workspace as SUPPORT: you can stop trouble (kill, PAPER, flatten); anything else
          needs their grant (or break-glass on the Settings page).</>}
    </div>
  );
}
