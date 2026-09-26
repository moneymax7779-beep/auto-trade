import { type ReactNode, useState } from "react";

export function Panel({ title, right, children }: { title?: ReactNode; right?: ReactNode; children: ReactNode }) {
  return (
    <section className="rounded-lg border border-line bg-panel">
      {title != null && (
        <header className="flex items-center justify-between border-b border-line px-4 py-2.5">
          <h2 className="text-sm font-semibold tracking-wide">{title}</h2>
          {right}
        </header>
      )}
      <div className="p-4">{children}</div>
    </section>
  );
}

export function Stat({ label, value, tone }: { label: string; value: ReactNode; tone?: "up" | "down" | "warn" | "muted" }) {
  const color = tone === "up" ? "text-up" : tone === "down" ? "text-down" : tone === "warn" ? "text-warn" : "";
  return (
    <div className="min-w-0">
      <div className="text-xs text-muted">{label}</div>
      <div className={`num truncate text-lg font-semibold ${color}`}>{value}</div>
    </div>
  );
}

const STAGE_COLORS: Record<string, string> = {
  IDLE: "bg-panel-2 text-muted",
  WATCH: "bg-sky-500/15 text-sky-400",
  ARMED: "bg-amber-500/15 text-amber-400",
  EARLY_ENTRY: "bg-violet-500/20 text-violet-300",
  CONFIRMED: "bg-emerald-500/20 text-emerald-300",
  RUNNER: "bg-emerald-500/35 text-emerald-200",
  EXITED: "bg-panel-2 text-muted line-through",
};

export function StageBadge({ stage }: { stage: string }) {
  return (
    <span className={`inline-block rounded px-2 py-0.5 text-xs font-semibold ${STAGE_COLORS[stage] ?? "bg-panel-2"}`}>
      {stage.replace("_", " ")}
    </span>
  );
}

/** 0..100 score as a bar with the value. */
export function ScoreBar({ label, value, threshold }: { label: string; value: number; threshold?: number }) {
  const width = Number.isFinite(value) ? Math.max(0, Math.min(100, value)) : 0;
  const passed = threshold != null && value >= threshold;
  return (
    <div className="flex items-center gap-2 text-xs">
      <span className="w-14 text-muted">{label}</span>
      <div className="relative h-2 flex-1 rounded bg-panel-2">
        <div className={`h-2 rounded ${passed ? "bg-up" : "bg-accent"}`} style={{ width: `${width}%` }} />
        {threshold != null && (
          <div className="absolute top-[-2px] h-3 w-px bg-warn" style={{ left: `${threshold}%` }} title={`threshold ${threshold}`} />
        )}
      </div>
      <span className="num w-8 text-right">{Number.isFinite(value) ? Math.round(value) : "–"}</span>
    </div>
  );
}

/** A signed state (−100 bearish … +100 bullish) drawn from the centre. */
export function SignedGauge({ label, value, signed = true }: { label: string; value: number; signed?: boolean }) {
  const v = Number.isFinite(value) ? value : 0;
  const pct = Math.min(100, Math.abs(v));
  const positive = v >= 0;
  return (
    <div className="text-xs">
      <div className="mb-1 flex justify-between">
        <span className="text-muted">{label}</span>
        <span className={`num font-semibold ${signed ? (positive ? "text-up" : "text-down") : ""}`}>
          {Number.isFinite(value) ? (signed && v > 0 ? "+" : "") + Math.round(v) : "–"}
        </span>
      </div>
      <div className="relative h-2 rounded bg-panel-2">
        {signed ? (
          <>
            <div className="absolute left-1/2 h-2 w-px bg-line" />
            <div
              className={`absolute h-2 rounded ${positive ? "bg-up" : "bg-down"}`}
              style={positive ? { left: "50%", width: `${pct / 2}%` } : { right: "50%", width: `${pct / 2}%` }}
            />
          </>
        ) : (
          <div className="h-2 rounded bg-accent" style={{ width: `${pct}%` }} />
        )}
      </div>
    </div>
  );
}

/** A button that asks for a second click before acting (for kill switch, exit-all, stop). */
export function ConfirmButton({ label, confirmLabel, onConfirm, tone = "danger", disabled }: {
  label: string; confirmLabel: string; onConfirm: () => void; tone?: "danger" | "neutral"; disabled?: boolean;
}) {
  const [armed, setArmed] = useState(false);
  const base = "rounded px-3 py-1.5 text-sm font-semibold transition-colors disabled:opacity-40";
  const color = armed
    ? "bg-down text-white"
    : tone === "danger" ? "border border-down/60 text-down hover:bg-down/10" : "border border-line hover:bg-panel-2";
  return (
    <button
      className={`${base} ${color}`}
      disabled={disabled}
      onClick={() => {
        if (armed) {
          setArmed(false);
          onConfirm();
        } else {
          setArmed(true);
          setTimeout(() => setArmed(false), 4000);
        }
      }}
    >
      {armed ? confirmLabel : label}
    </button>
  );
}

export function Table<T>({ rows, columns, empty = "Nothing yet." }: {
  rows: T[];
  columns: { key: string; label: string; render: (row: T) => ReactNode; align?: "right" }[];
  empty?: string;
}) {
  if (rows.length === 0) return <p className="text-sm text-muted">{empty}</p>;
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-line text-left text-xs text-muted">
            {columns.map((c) => (
              <th key={c.key} className={`px-2 py-1.5 font-medium ${c.align === "right" ? "text-right" : ""}`}>{c.label}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row, i) => (
            <tr key={i} className="border-b border-line/50 hover:bg-panel-2/50">
              {columns.map((c) => (
                <td key={c.key} className={`px-2 py-1.5 ${c.align === "right" ? "num text-right" : ""}`}>{c.render(row)}</td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function Loading({ what }: { what: string }) {
  return <p className="text-sm text-muted">Loading {what}…</p>;
}

export function ErrorNote({ error }: { error: unknown }) {
  return <p className="text-sm text-down">Could not load: {error instanceof Error ? error.message : String(error)}</p>;
}

export function Pnl({ value }: { value: number | null | undefined }) {
  if (value == null || Number.isNaN(value)) return <span className="text-muted">–</span>;
  return (
    <span className={`num ${value > 0 ? "text-up" : value < 0 ? "text-down" : ""}`}>
      {value > 0 ? "+" : ""}₹{Math.round(value).toLocaleString("en-IN")}
    </span>
  );
}
