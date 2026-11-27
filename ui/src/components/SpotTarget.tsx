import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { postJson, type LiveBook, type LiveOpenPosition, type SpotTarget as Spot } from "../api";

/**
 * The trader's spot exit on an open position (27 Nov 2026, user: "I should exit from LIVE open position when it reaches the
 * spot not just on the percentage target"). A level of the position's index: the whole position is sold when the index
 * reaches it, a CE at or above it, a PE at or below it. A take-profit at the touch (the % target beside it is a lock);
 * the strategy's exits, the stop, Exit, the % target and the square-off still work: whichever comes first.
 * {@code phone}: bigger touch targets; {@code readOnly}: the status only.
 */

/** The level as the server stores it (two decimals), or null. */
export const spotLevel = (text: string): number | null => {
  if (text.trim() === "") return null;
  const n = Number(text.replace(/,/g, ""));
  if (!Number.isFinite(n)) return null;
  const rounded = Math.round(n * 100) / 100;
  return rounded > 0 ? rounded : null;
};

export const spotActive = (t: Spot | undefined | null) =>
  t != null && (t.status === "WAITING" || t.status === "FIRING" || t.status === "SELLING");

/** UP for a CE (the index rising to the level is the profit), DOWN for a PE, null otherwise. */
export const spotDirection = (side: string | undefined): "UP" | "DOWN" | null =>
  side === "CE" ? "UP" : side === "PE" ? "DOWN" : null;

/** Why a level is refused against the index now (as the server says it), or null. */
export function spotRefusal(dir: "UP" | "DOWN", level: number, spot: number | null | undefined): string | null {
  if (spot == null) return null;
  if (dir === "UP" ? spot >= level : spot <= level) return `The index (${idx(spot)}) is already ${dir === "UP" ? "at or above" : "at or below"} it: use Exit to sell now.`;
  if (Math.abs(level / spot - 1) > 0.10) return `More than 10 % away from the index (${idx(spot)}): check the level.`;
  return null;
}

const idx = (v: number | null | undefined) => (v == null || !Number.isFinite(v) ? "–" : v.toFixed(2));

/** The index price is not live when older than this (the server refuses a level in market hours then). */
export const STALE_SECONDS = 120;

/** " (12 min old: not live)" when the index price is old, else "". */
export const staleText = (age: number | undefined) => age == null || age <= STALE_SECONDS ? ""
  : ` (${age < 3600 ? `${Math.round(age / 60)} min` : age < 86_400 ? `${Math.round(age / 3600)} h` : `${Math.round(age / 86_400)} d`} old: not live)`;
const sign = (dir: "UP" | "DOWN") => (dir === "UP" ? "≥" : "≤");

const istClock = (iso: string | null | undefined, seconds = false) => !iso ? "–"
  : new Date(iso).toLocaleTimeString("en-IN", { hour12: false, timeZone: "Asia/Kolkata", hour: "2-digit", minute: "2-digit",
    ...(seconds ? { second: "2-digit" } : {}) });

export function spotText(t: Spot, spot?: number | null, spotAge?: number): string {
  const at = `${t.underlying} ${sign(t.direction)} ${idx(t.level)}`;
  const last = t.lastResult ? ` · last try: ${t.lastResult}` : "";
  const who = t.byAdmin ? "Admin spot exit" : "Spot exit";
  switch (t.status) {
    case "WAITING": {
      const away = spot != null ? ` · ${t.underlying} ${idx(spot)}${staleText(spotAge)}, ${Math.abs(t.level - spot).toFixed(1)} pts to go` : "";
      return `${who} at ${at}: waiting${away}`;
    }
    case "FIRING": return `Selling: ${t.underlying} reached ${idx(t.firedSpot ?? t.level)} (spot exit ${idx(t.level)})…`;
    case "SELLING": return `Spot exit ${at} triggered at ${istClock(t.firedAt)}: the rest is sold at the next try (${istClock(t.nextTryAt, true)})${last}`;
    case "DONE": return `Sold by the spot exit ${at}${t.firedSpot != null ? ` (${t.underlying} ${idx(t.firedSpot)})` : ""}`;
    case "ENDED": return `Spot exit ${at} ended: ${t.lastResult ?? "the position was closed"}`;
    case "FAILED": return `Spot exit ${at} could not sell after ${t.tries ?? 0} tries (${t.lastResult ?? "no reason given"}): use Exit`;
  }
}

export function SpotTarget({ accountId, p, live, phone, readOnly }: { accountId: number;
  p: Pick<LiveOpenPosition, "id" | "side" | "underlying" | "spot" | "spotAge" | "quantity" | "lotSize" | "spotTarget">;
  /** a LIVE book: the sale is a real order at the broker */
  live?: boolean; phone?: boolean; readOnly?: boolean }) {
  const client = useQueryClient();
  const t = p.spotTarget;
  const dir = spotDirection(p.side);
  const [editing, setEditing] = useState(false);
  const [text, setText] = useState("");
  const base = `/api/execution/accounts/${accountId}/positions/${p.id}/spot-target`;
  const put = (target: Spot | undefined) => client.setQueryData<LiveBook>(["live-book", accountId], (b) => b && ({
    ...b, open: b.open.map((o) => (o.id === p.id ? { ...o, spotTarget: target } : o)) }));
  const refresh = () => client.invalidateQueries({ queryKey: ["live-book", accountId] });
  const save = useMutation({
    mutationFn: (level: number) => postJson<{ spotTarget: Spot }>(base, { level }),
    onSuccess: (r) => { put(r.spotTarget); setEditing(false); },
    onSettled: refresh,
  });
  const remove = useMutation({
    mutationFn: () => postJson<{ cleared: boolean }>(`${base}/clear`, {}),
    onSuccess: () => { put(undefined); setEditing(false); },
    onSettled: refresh,
  });
  if (dir == null) return null;
  const active = spotActive(t);
  const status = t ? <span className={`min-w-0 ${t.status === "FAILED" ? "text-down" : t.status === "WAITING" ? "text-muted" : "text-accent font-semibold"}`}>
    {spotText(t, p.spot, p.spotAge)}</span> : null;
  if (readOnly) {
    return t ? <p className="mt-1 text-sm" data-testid={`spot-${p.id}`} aria-live="polite">{status}</p> : null;
  }
  const btn = phone ? "min-h-11 rounded-lg border px-3 text-sm font-semibold disabled:opacity-40"
    : "rounded-md border px-3 py-1.5 text-sm font-medium disabled:opacity-50";
  const busy = save.isPending || remove.isPending;
  if (!editing) {
    return (
      <div className="mt-1 flex flex-wrap items-center gap-2 text-sm" data-testid={`spot-${p.id}`}>
        {status && <span aria-live="polite" className="min-w-0">{status}</span>}
        {/* SELLING (triggered, the rest pending) or FAILED: no new level over it; it can be removed (then use Exit) */}
        {(t?.status === "SELLING" || t?.status === "FAILED") ? (
          <button type="button" className={`${btn} border-line bg-white text-down`} disabled={busy}
            onClick={() => remove.mutate()}>{remove.isPending ? "Removing…" : "Remove spot exit"}</button>
        ) : t?.status !== "FIRING" && (
          <button type="button" className={`${btn} border-line bg-white text-text`}
            onClick={() => { save.reset(); remove.reset(); setText(active ? String(t!.level) : ""); setEditing(true); }}>
            {active ? "Change spot exit" : `Set spot exit (${p.underlying} ${sign(dir)})`}</button>
        )}
        {remove.error && <span className="w-full text-down">Not removed: {(remove.error as Error).message}</span>}
      </div>
    );
  }
  const level = spotLevel(text);
  const refused = level != null ? spotRefusal(dir, level, p.spot) : null;
  const lots = p.lotSize ? Math.max(1, Math.round(p.quantity / p.lotSize)) : null;
  const all = lots != null ? `all ${lots} lot${lots === 1 ? "" : "s"}` : `all ${p.quantity}`;
  return (
    <form noValidate className="mt-2 space-y-2 rounded-md border border-line bg-panel-2 px-3 py-2 text-sm"
      onSubmit={(e) => { e.preventDefault(); if (level != null && !refused && !busy) save.mutate(level); }}>
      <label className="flex flex-wrap items-center gap-2">
        <span>Sell when {p.underlying} {sign(dir)}</span>
        <input type="text" inputMode="decimal" value={text} autoFocus aria-label={`Spot exit level of ${p.underlying}`}
          onChange={(e) => setText(e.target.value)}
          className={`num w-28 rounded border border-line bg-white px-2 ${phone ? "min-h-11 text-base" : "py-1"} text-text`} />
        {p.spot != null && <span className={staleText(p.spotAge) ? "text-down" : "text-muted"}>now {idx(p.spot)}{staleText(p.spotAge)}
          {level != null ? ` · ${Math.abs(level - p.spot).toFixed(1)} pts away` : ""}</span>}
      </label>
      {text.trim() !== "" && level == null && <div className="text-down" role="alert">An index level above 0.</div>}
      {refused && <div className="text-down" role="alert">{refused}</div>}
      {p.spot == null && <div className="text-down" role="alert">No {p.underlying} price is being captured: a spot exit cannot be watched.</div>}
      {level != null && !refused && (
        <div className="text-muted">
          When {p.underlying} trades at {idx(level)} or {dir === "UP" ? "above" : "below"}, {all} {lots === 1 ? "is" : "are"} sold at once
          (beside the % target, the stop and the strategy's exits: whichever comes first).
          {live && <> <b className="text-amber-800">LIVE: a real sell order at the broker for {all} ({p.quantity} qty) at the option's price then.</b></>}
        </div>
      )}
      <div className="flex flex-wrap items-center gap-2">
        <button type="submit" className={`${btn} border-accent bg-accent text-white`} disabled={level == null || refused != null || p.spot == null || busy}>
          {save.isPending ? "Saving…" : "Save spot exit"}</button>
        {active && (
          <button type="button" className={`${btn} border-line bg-white text-down`} disabled={busy}
            onClick={() => remove.mutate()}>{remove.isPending ? "Removing…" : "Remove spot exit"}</button>
        )}
        <button type="button" className={`${btn} border-line bg-white text-text`} disabled={busy}
          onClick={() => setEditing(false)}>Cancel</button>
      </div>
      {save.error && <div className="text-down" role="alert">Not saved: {(save.error as Error).message}</div>}
      {remove.error && <div className="text-down" role="alert">Not removed: {(remove.error as Error).message}</div>}
    </form>
  );
}
