import { useQuery } from "@tanstack/react-query";
import { get } from "../api";

interface Alert { key: string; level: "CRITICAL" | "WARN" | "INFO" | "RESOLVED"; message: string; since: string }
interface Alerts { telegram: boolean; active: Alert[] }

const time = (iso: string) =>
  new Date(iso).toLocaleTimeString("en-IN", { hour: "2-digit", minute: "2-digit", hour12: false, timeZone: "Asia/Kolkata" });

/** Active operational alerts (token, zt, disk, session, feed, kill switch); empty when all is well. */
export function AlertBanner() {
  const q = useQuery({ queryKey: ["alerts"], queryFn: () => get<Alerts>("/api/alerts"), refetchInterval: 30_000 });
  if (!q.data || q.data.active.length === 0) return null;
  return (
    <div className="space-y-2" role="alert">
      {q.data.active.map((a) => (
        <div key={a.key}
          className={`rounded border px-3 py-2 text-sm ${a.level === "CRITICAL" ? "border-down text-down" : "border-warn text-warn"}`}>
          <span className="mr-2 font-semibold">{a.level === "CRITICAL" ? "Alert" : "Warning"}</span>
          {a.message}
          <span className="ml-2 text-xs text-muted num">since {time(a.since)}</span>
        </div>
      ))}
      {!q.data.telegram && (
        <p className="text-xs text-muted">Telegram is not set up, so these alerts show here and in the log only.</p>
      )}
    </div>
  );
}
