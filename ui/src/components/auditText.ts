import type { AuditRow } from "../api";

/**
 * The audit log in plain words (user, 11 Nov 2026: the Accounts page showed each row's raw JSON). One row becomes a
 * short title and a sentence; a settings change names what changed from what to what, and a strategy change names the
 * strategies added and removed (against the account's previous list, from the older rows).
 */
export interface AuditLine { title: string; text: string; tone: "down" | "warn" | "up" | "muted" | "plain" }

type Detail = Record<string, unknown>;

const TITLES: Record<string, string> = {
  ACCOUNT_CREATED: "Account created", ACCOUNT_SETTINGS: "Settings changed", ACCOUNT_REMOVED: "Account removed",
  LIVE_ON: "LIVE switched on", LIVE_OFF: "LIVE switched off", LIVE_ELIGIBLE_ON: "Real money allowed",
  LIVE_ELIGIBLE_OFF: "Real money not allowed", LIVE_STRATEGIES: "LIVE strategies changed", TODAYS_BROKER: "Today's broker",
  KILL_ACCOUNT: "Entries stopped", KILL_ACCOUNT_ALREADY_ENGAGED: "Entries already stopped", KILL_RELEASED: "Entries allowed again",
  FLATTEN: "Flattened", POSITION_EXIT: "Position exited", MANUAL_EXIT: "Exited by hand at the broker",
  EOD_SQUARE_OFF: "End-of-day square-off", END_OF_DAY: "End-of-day check", MODE_PAPER_SESSION_LOST: "Moved to PAPER",
  MODE_PAPER_NEW_SESSION: "Moved to PAPER", MODE_PAPER_MEMBER_DISABLED: "Moved to PAPER", BROKER_SESSION_LOST: "Broker login lost",
  MEMBER_ADDED: "Member added", STRATEGIES_OFF: "Strategies switched off", LIVE_STRATEGIES_GLOBAL: "LIVE strategies (all accounts)",
  TENANT_LOSS_LIMIT: "Workspace loss limit",
};
const TONES: Record<string, AuditLine["tone"]> = {
  KILL_ACCOUNT: "down", KILL_ACCOUNT_ALREADY_ENGAGED: "down", FLATTEN: "down", LIVE_ON: "down", LIVE_ELIGIBLE_ON: "down",
  BROKER_SESSION_LOST: "warn", MODE_PAPER_SESSION_LOST: "warn", MODE_PAPER_NEW_SESSION: "warn", MANUAL_EXIT: "warn",
  POSITION_EXIT: "warn", KILL_RELEASED: "up",
};

const LABELS: Record<string, string> = {
  allocation: "Capital", dailyLossLimit: "Daily loss limit", equityMode: "Sizing", mode: "Mode", enabled: "Enabled",
  broker: "Broker", reason: "Reason", positions: "Positions", symbol: "Contract", quantity: "Quantity",
};
const MONEY = new Set(["allocation", "dailyLossLimit", "allocatedCapital"]);

const words = (key: string) => LABELS[key] ?? key.replace(/([a-z])([A-Z])/g, "$1 $2").replace(/_/g, " ").replace(/^./, (c) => c.toUpperCase());
const humanAction = (a: string) => TITLES[a] ?? a.toLowerCase().replace(/_/g, " ").replace(/^./, (c) => c.toUpperCase());

function value(key: string, v: unknown): string {
  if (v == null || v === "null") return "none";
  if (MONEY.has(key) && Number.isFinite(Number(v))) return `₹${Math.round(Number(v)).toLocaleString("en-IN")}`;
  if (typeof v === "boolean") return v ? "yes" : "no";
  if (key === "equityMode") return String(v).toLowerCase();
  if (Array.isArray(v)) return v.length ? v.map((x) => (typeof x === "object" ? JSON.stringify(x) : String(x))).join(", ") : "none";
  if (typeof v === "object") return Object.entries(v as Detail).map(([k, x]) => `${words(k)} ${value(k, x)}`).join(", ");
  return String(v);
}

const parse = (row: AuditRow): Detail => {
  try { const d = JSON.parse(row.detail); return d && typeof d === "object" && !Array.isArray(d) ? d as Detail : {}; } catch { return {}; }
};

/** The account's strategy list before this row: the nearest older row of the same account that names one. */
function previousStrategies(row: AuditRow, rows: AuditRow[], index: number): string[] | null {
  for (let i = index + 1; i < rows.length; i++) {
    const r = rows[i];
    if (r.broker_account_id !== row.broker_account_id || (r.action !== "ACCOUNT_SETTINGS" && r.action !== "ACCOUNT_CREATED")) continue;
    const s = parse(r).strategies;
    if (Array.isArray(s)) return s.map(String);
  }
  return null;
}

function strategyChange(now: string[], before: string[] | null): string {
  if (!before) return `Strategies: ${now.join(", ") || "none"}`;
  const added = now.filter((s) => !before.includes(s)), removed = before.filter((s) => !now.includes(s));
  const parts = [added.length ? `added ${added.join(", ")}` : "", removed.length ? `removed ${removed.join(", ")}` : ""].filter(Boolean);
  return parts.length ? `Strategies: ${parts.join("; ")}` : "Strategies unchanged";
}

/** Row {@code index} of the newest-first log {@code rows} (the older rows give a strategy list's previous version). */
export function describeAudit(rows: AuditRow[], index: number): AuditLine {
  const row = rows[index], d = parse(row);
  const title = humanAction(row.action), tone = TONES[row.action] ?? "plain";
  if (row.action === "ACCOUNT_SETTINGS") {
    const before = (d.before ?? {}) as Detail;
    const parts = Object.entries(d).filter(([k]) => k !== "before").map(([k, v]) => k === "strategies" && Array.isArray(v)
      ? strategyChange(v.map(String), previousStrategies(row, rows, index))
      : k in before && value(k, before[k]) !== value(k, v) ? `${words(k)} ${value(k, before[k])} → ${value(k, v)}` : `${words(k)} ${value(k, v)}`);
    return { title, text: parts.join(" · ") || "No change recorded", tone };
  }
  if (row.action === "ACCOUNT_CREATED") {
    const parts = ["mode", "broker", "allocation", "equityMode", "dailyLossLimit"].filter((k) => d[k] != null)
      .map((k) => `${words(k)} ${value(k, d[k])}`);
    if (Array.isArray(d.strategies)) parts.push(`Strategies: ${d.strategies.join(", ")}`);
    return { title, text: parts.join(" · "), tone };
  }
  if (row.action === "EOD_SQUARE_OFF") {
    // positions: the worker's lines, one per position closed (or one "STILL OPEN" warning)
    const lines = Array.isArray(d.positions) ? d.positions.map(String)
      : typeof d.positions === "number" ? [`${d.positions} position${d.positions === 1 ? "" : "s"} closed`] : [];
    const stuck = lines.some((l) => l.includes("STILL OPEN"));
    return { title, text: lines.length ? lines.join("; ") : "nothing was open", tone: stuck ? "down" : tone };
  }
  if (row.action === "MODE_PAPER_SESSION_LOST") return { title, text: "the broker login was lost; it trades on paper until the next login", tone };
  if (row.action === "MODE_PAPER_NEW_SESSION") return { title, text: "a new broker login started; it trades on paper for the rest of the day", tone };
  // the reason first, then the rest as "Key value"
  const entries = Object.entries(d).sort(([a], [b]) => Number(b === "reason") - Number(a === "reason"));
  return { title, text: entries.map(([k, v]) => (k === "reason" ? String(v) : `${words(k)} ${value(k, v)}`)).join(" · "), tone };
}
