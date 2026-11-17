import { useQuery } from "@tanstack/react-query";
import { type ReactNode, useEffect, useState } from "react";
import { get, type ExecMe, type ExecutionAccount } from "../api";

/**
 * Mobile phase 1 (17 Nov 2026): pieces the phone screens share. Every money action opens a Sheet that states what will
 * happen (account, mode, lots) before the one button that does it.
 */

/** IST clock time of an ISO instant or IST local date-time, "HH:MM". */
export function istTime(iso: string | null | undefined): string {
  if (!iso) return "–";
  const local = /[zZ]|[+-]\d\d:\d\d$/.test(iso) ? null : iso;   // an IST local date-time has no zone
  if (local) return local.slice(11, 16);
  return new Date(iso).toLocaleTimeString("en-IN", { hour12: false, timeZone: "Asia/Kolkata", hour: "2-digit", minute: "2-digit" });
}

/** Signed rupees with the Indian grouping: "+₹1,10,000", "−₹3,150", "₹0". */
export function signed(value: number | null | undefined): string {
  if (value == null || Number.isNaN(value)) return "–";
  const r = Math.round(value);
  return `${r > 0 ? "+" : r < 0 ? "−" : ""}₹${Math.abs(r).toLocaleString("en-IN")}`;
}

export function tone(value: number | null | undefined): string {
  return value == null || Math.round(value) === 0 ? "" : value > 0 ? "text-up" : "text-down";
}

/** The signed-in user's tenants and what they may do (GET /api/execution/me); a worker without users answers for the owner. */
export function useAccess() {
  const me = useQuery({ queryKey: ["exec-me"], queryFn: () => get<ExecMe>("/api/execution/me"), staleTime: 30_000 });
  const tenants = new Map((me.data?.tenants ?? []).map((t) => [t.id, t]));
  // as on the Accounts page: when /me cannot be read (no-users mode) the server decides; the buttons stay offered
  const legacy = me.isError;
  return {
    me,
    mayAct: (tenantId: number) => legacy || (tenants.get(tenantId)?.mayAct ?? false),
    canStop: (tenantId: number) => legacy || (tenants.get(tenantId)?.canStop ?? tenants.get(tenantId)?.mayAct ?? false),
  };
}

export function useAccounts() {
  return useQuery({ queryKey: ["exec-accounts"], queryFn: () => get<ExecutionAccount[]>("/api/execution/accounts"),
    refetchInterval: 10_000 });
}

/** Accounts worth a phone's screen: enabled ones, and any that still holds a position. */
export function activeAccounts(rows: ExecutionAccount[] | undefined): ExecutionAccount[] {
  return (rows ?? []).filter((a) => a.enabled || (a.today?.open ?? 0) > 0);
}

export function Card({ children, className = "" }: { children: ReactNode; className?: string }) {
  return <section className={`min-w-0 rounded-xl border border-line bg-panel p-3 ${className}`}>{children}</section>;
}

export function ModeBadge({ mode }: { mode: string | null | undefined }) {
  const live = mode === "LIVE";
  return (
    <span className={`rounded-full px-2 py-0.5 text-[10px] font-bold tracking-wider ${live ? "bg-down/15 text-down"
      : "bg-warn/15 text-warn"}`}>{mode ?? "?"}</span>
  );
}

/** A full-width button sized for a thumb (44 px). */
export function Big({ children, onClick, kind = "plain", disabled, type = "button" }: {
  children: ReactNode; onClick?: () => void; kind?: "plain" | "primary" | "danger"; disabled?: boolean;
  type?: "button" | "submit";
}) {
  const look = kind === "danger" ? "border-down bg-down text-white" : kind === "primary" ? "border-sky-700 bg-sky-700 text-white"
    : "border-line bg-panel text-text";
  return (
    <button type={type} disabled={disabled} onClick={onClick}
      className={`min-h-11 rounded-lg border px-3 text-sm font-semibold disabled:opacity-40 ${look}`}>{children}</button>
  );
}

/** A bottom sheet over the screen (above the tab bar); closes on the backdrop, Escape or onClose. */
export function Sheet({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  useEffect(() => {
    const close = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    window.addEventListener("keydown", close);
    return () => window.removeEventListener("keydown", close);
  }, [onClose]);
  return (
    <>
      <div className="fixed inset-0 z-50 bg-black/40" onClick={onClose} aria-hidden="true" />
      <div role="dialog" aria-modal="true" aria-label={title}
        className="fixed inset-x-0 bottom-0 z-50 max-h-[88vh] overflow-y-auto rounded-t-2xl border-t border-line bg-panel pb-[calc(1rem+env(safe-area-inset-bottom))] pl-[max(1rem,env(safe-area-inset-left))] pr-[max(1rem,env(safe-area-inset-right))] pt-3 shadow-2xl md:inset-x-auto md:left-1/2 md:w-[28rem] md:-translate-x-1/2">
        <div className="mx-auto mb-3 h-1 w-10 rounded bg-line" />
        <h2 className="mb-3 text-base font-semibold">{title}</h2>
        {children}
      </div>
    </>
  );
}

/** "updated 10:07:32" from a query's last success; amber after 30 s, so a stale number never reads as live. */
export function Freshness({ at }: { at: number }) {
  // its own clock (review 17 Nov): a hung request re-renders nothing, so the age must move by itself to turn amber
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const t = window.setInterval(() => setNow(Date.now()), 5_000);
    return () => window.clearInterval(t);
  }, []);
  if (!at) return null;
  const age = (Math.max(now, at) - at) / 1000;
  const time = new Date(at).toLocaleTimeString("en-IN", { hour12: false, timeZone: "Asia/Kolkata" });
  return <span className={`text-[11px] ${age > 30 ? "font-semibold text-warn" : "text-muted"}`}>
    {age > 30 ? `not updated since ${time}` : `as of ${time}`}</span>;
}

/**
 * The service is down or out of reach: a 502/503/504 (get() writes "502 reason for path", postJson "502: reason") or a
 * fetch that never reached the server (offline). Review 17 Nov: the check missed get()'s format and showed raw text.
 */
export function serviceDown(error: unknown): boolean {
  if (!error) return false;
  const text = error instanceof Error ? error.message : String(error);
  return /^50[234]\b/.test(text) || /Failed to fetch|NetworkError|Load failed/i.test(text);
}

export function ErrorLine({ error }: { error: unknown }) {
  if (!error) return null;
  const text = error instanceof Error ? error.message : String(error);
  return <p className="rounded-lg border border-down/40 bg-down/5 px-3 py-2 text-sm text-down">
    {serviceDown(error) ? "The trading service is not answering. Accounts and positions show again when it is back." : text}</p>;
}
