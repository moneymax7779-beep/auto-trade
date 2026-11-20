import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { get, type AdminExitNotice } from "../api";

const KEY = "autotrade.adminExitSeen";

function seen(): Set<number> {
  try {
    return new Set(JSON.parse(window.localStorage.getItem(KEY) ?? "[]") as number[]);
  } catch {
    return new Set();
  }
}

const when = (iso: string) => new Date(iso).toLocaleTimeString("en-IN", { hour12: false, timeZone: "Asia/Kolkata",
  hour: "2-digit", minute: "2-digit" });

/**
 * The in-app notice of an admin exit on one of your accounts (admin exit desk, user 20 Nov 2026, decision 3; Telegram and
 * web push carry the same message): the admin's exits of the last 24 hours, newest first, until you dismiss each (the
 * dismissal is this browser's; the audit keeps the record). Nothing shows when there is none or the server cannot be read.
 */
export function AdminExitBanner() {
  const notices = useQuery({ queryKey: ["admin-exit-notices"], queryFn: () => get<AdminExitNotice[]>("/api/execution/notices"),
    refetchInterval: 30_000, retry: false });
  const [dismissed, setDismissed] = useState<Set<number>>(seen);
  const shown = (notices.data ?? []).filter((n) => !dismissed.has(n.id));
  if (shown.length === 0) return null;
  const dismiss = (id: number) => {
    const next = new Set(dismissed).add(id);
    setDismissed(next);
    try {
      window.localStorage.setItem(KEY, JSON.stringify([...next].slice(-200)));
    } catch {
      // private window: dismissed for this page only
    }
  };
  return (
    <div className="mb-3 space-y-2" role="status">
      {shown.map((n) => {
        let positions: string[] = [];
        try {
          positions = n.positions ? (JSON.parse(n.positions) as string[]) : [];
        } catch {
          positions = [];
        }
        return (
          <div key={n.id} className="rounded border border-warn/60 bg-warn/10 px-3 py-2 text-sm">
            <div className="flex flex-wrap items-start justify-between gap-2">
              <span><b>The admin {n.action === "FLATTEN" ? "flattened" : "exited a position on"} {n.account}</b> at {when(n.at)}
                {n.reason ? <> · reason: {n.reason}</> : null}</span>
              <button type="button" className="text-xs text-muted hover:text-text" onClick={() => dismiss(n.id)}>dismiss</button>
            </div>
            {positions.length > 0 && <div className="text-xs">{positions.join(" · ")}</div>}
          </div>
        );
      })}
    </div>
  );
}
