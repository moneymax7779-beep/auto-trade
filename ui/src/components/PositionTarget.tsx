import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { postJson, type LiveBook, type LiveOpenPosition, type ManualTarget } from "../api";

/**
 * The trader's manual first target on an open position (24 Nov 2026, user: "I should be able to manually enter the 1st
 * target in % once the position is opened ... so in case it reverses should close at the target entered manually").
 * A lock, as the user chose: nothing happens until the bid reaches entry × (1 + %); the price is fixed then, and a bid back
 * at or below it sells the whole position (what a first sale leaves is sold on, whatever the bid, like a stop order). The
 * strategy's own exits, the stop, Exit and the square-off still work beside it. One component for the desktop card and the
 * phone card ({@code phone}: bigger touch targets); {@code readOnly}: the status only (no role to set it).
 */

/** The % as the server stores it (two decimals), or null when it is not a target. */
export const targetPct = (text: string): number | null => {
  if (text.trim() === "") return null;
  const n = Number(text);
  if (!Number.isFinite(n)) return null;
  const rounded = Math.round(n * 100) / 100;
  return rounded > 0 && rounded <= 1000 ? rounded : null;
};

/** Entry × (1 + pct %), to the 0.05 tick, as the server prices it. */
export const targetPrice = (entry: number, pct: number) => Math.round(entry * (1 + pct / 100) * 20) / 20;

/** Whether the target still stands on the position (drawn on the rail, can be changed or removed). */
export const targetActive = (t: ManualTarget | undefined | null) =>
  t != null && (t.status === "WAITING" || t.status === "ARMED" || t.status === "FIRING" || t.status === "SELLING");

const px = (v: number | null | undefined) => (v == null || !Number.isFinite(v) ? "–" : v.toFixed(2));

const istClock = (iso: string | null | undefined, seconds = false) => !iso ? "–"
  : /[zZ]|[+-]\d\d:\d\d$/.test(iso)
    ? new Date(iso).toLocaleTimeString("en-IN", { hour12: false, timeZone: "Asia/Kolkata", hour: "2-digit", minute: "2-digit",
      ...(seconds ? { second: "2-digit" } : {}) })
    : iso.slice(11, seconds ? 19 : 16);

/** One line on where the target stands. */
export function targetText(t: ManualTarget): string {
  const last = t.lastResult ? ` · last try: ${t.lastResult}` : "";
  // 25 Nov 2026: a target the admin set from the exit desk says so (the member sees whose it is, and why)
  const Your = t.byAdmin ? "The admin's target" : "Your target", your = t.byAdmin ? "the admin's target" : "your target";
  const why = t.byAdmin && t.adminReason ? ` (admin: ${t.adminReason})` : "";
  switch (t.status) {
    case "WAITING": return `${t.byAdmin ? "Admin target" : "Target"} +${t.pct} % = ${px(t.price)}: waiting for the bid to reach it${why}`;
    case "ARMED": return `${t.byAdmin ? "Admin target" : "Target"} ${px(t.price)} reached at ${istClock(t.armedAt)}${t.armedBid != null ? ` (bid ${px(t.armedBid)})` : ""}: `
      + `if the bid falls back to ${px(t.price)} everything is sold${last}${why}`;
    case "FIRING": return `Selling at ${your} ${px(t.price)}${t.firedBid != null ? ` (bid ${px(t.firedBid)})` : ""}…`;
    case "SELLING": return `${Your} ${px(t.price)} triggered at ${istClock(t.firedAt)}: the rest is sold at the next try `
      + `(${istClock(t.nextTryAt, true)})${last}`;
    case "DONE": return `Sold by ${your} ${px(t.price)}${t.firedBid != null ? ` (bid ${px(t.firedBid)})` : ""}`;
    case "ENDED": return `${Your} ${px(t.price)} ended: ${t.lastResult ?? "the position was closed"}`;
    case "FAILED": return `${Your} ${px(t.price)} could not sell after ${t.tries ?? 0} tries (${t.lastResult ?? "no reason given"}): use Exit`;
  }
}

export function PositionTarget({ accountId, p, live, phone, readOnly, reasonRequired, onChanged, account }: { accountId: number;
  p: Pick<LiveOpenPosition, "id" | "entry" | "bid" | "quantity" | "lotSize" | "manualTarget">;
  /** a LIVE book: the sale is a real order at the broker */
  live?: boolean; phone?: boolean; readOnly?: boolean;
  /** the exit desk (25 Nov 2026): the admin outside the workspace gives a reason (the members are told) */
  reasonRequired?: boolean;
  /** after a save or removal (the exit desk reads its own list again) */
  onChanged?: () => void;
  /** the account's name, said in the LIVE line (the exit desk) */
  account?: string }) {
  const client = useQueryClient();
  const t = p.manualTarget;
  const [editing, setEditing] = useState(false);
  const [text, setText] = useState("");
  const [reason, setReason] = useState("");
  const why = reasonRequired ? { reason: reason.trim() } : {};
  const noReason = reasonRequired === true && reason.trim() === "";
  const base = `/api/execution/accounts/${accountId}/positions/${p.id}/target`;
  // the card shows the new state at once (the book's next poll confirms it), never the old one for up to 5 s
  const put = (target: ManualTarget | undefined) => client.setQueryData<LiveBook>(["live-book", accountId], (b) => b && ({
    ...b, open: b.open.map((o) => (o.id === p.id ? { ...o, manualTarget: target } : o)) }));
  const refresh = () => { client.invalidateQueries({ queryKey: ["live-book", accountId] }); onChanged?.(); };
  const save = useMutation({
    mutationFn: (pct: number) => postJson<{ target: ManualTarget }>(base, { pct, ...why }),
    onSuccess: (r) => { put(r.target); setEditing(false); },
    onSettled: refresh,
  });
  const remove = useMutation({
    mutationFn: () => postJson<{ cleared: boolean }>(`${base}/clear`, why),
    onSuccess: () => { put(undefined); setEditing(false); },
    onSettled: refresh,
  });
  const pct = targetPct(text);
  const price = pct != null ? targetPrice(p.entry, pct) : null;
  const lots = p.lotSize ? Math.max(1, Math.round(p.quantity / p.lotSize)) : null;
  const all = lots != null ? `all ${lots} lot${lots === 1 ? "" : "s"}` : `all ${p.quantity}`;
  const busy = save.isPending || remove.isPending;
  const active = targetActive(t);
  const btn = phone ? "min-h-11 rounded-lg border px-3 text-sm font-semibold disabled:opacity-40"
    : "rounded-md border px-3 py-1.5 text-sm font-medium disabled:opacity-50";
  const open = () => { save.reset(); remove.reset(); setText(active ? String(t!.pct) : ""); setReason(""); setEditing(true); };
  const tone = t?.status === "ARMED" || t?.status === "FIRING" || t?.status === "SELLING" ? "text-accent font-semibold"
    : t?.status === "FAILED" ? "text-down" : "text-muted";
  const status = t ? <span className={`min-w-0 ${tone}`}>{targetText(t)}</span>
    : <span className="text-muted">No target of yours</span>;

  if (readOnly) {
    return t ? <p className="mt-2 text-sm" data-testid={`target-${p.id}`} aria-live="polite">{status}</p> : null;
  }
  if (!editing) {
    return (
      <div className="mt-2 flex flex-wrap items-center gap-2 text-sm" data-testid={`target-${p.id}`}>
        <span aria-live="polite" className="min-w-0">{status}</span>
        {t?.status !== "FIRING" && (
          <button type="button" className={`${btn} border-line bg-white text-text`} onClick={open}>
            {active ? "Change target" : "Set target %"}</button>
        )}
        {remove.error && <span className="w-full text-down">Not removed: {(remove.error as Error).message}</span>}
      </div>
    );
  }
  const armed = t?.status === "ARMED" || t?.status === "SELLING";
  const changed = pct != null && pct !== t?.pct;
  return (
    // noValidate: the browser's own number checks (step, min) would block a valid % with a bubble and no message here
    <form noValidate className="mt-2 space-y-2 rounded-md border border-line bg-panel-2 px-3 py-2 text-sm"
      onSubmit={(e) => { e.preventDefault(); if (pct != null && !busy && !noReason) save.mutate(pct); }}>
      <label className="flex flex-wrap items-center gap-2">
        <span>Target % above entry {px(p.entry)}</span>
        <input type="number" inputMode="decimal" step="any" value={text} autoFocus
          aria-label="Target % above entry" onChange={(e) => setText(e.target.value)}
          className={`num w-24 rounded border border-line bg-white px-2 ${phone ? "min-h-11 text-base" : "py-1"} text-text`} />
        <span>%</span>
        {price != null && <b className="num">= {px(price)}</b>}
      </label>
      {text.trim() !== "" && pct == null && <div className="text-down" role="alert">A % above 0 and at most 1000.</div>}
      {armed && changed && (
        <div className="font-semibold text-amber-800" role="alert">Your target {px(t!.price)} is {t!.status === "ARMED" ? "armed" : "selling"} now:
          a new % starts over and waits for the bid to reach the new price.</div>
      )}
      {price != null && (
        <div className="text-muted">
          {p.bid == null ? <>No bid now. </>
            : p.bid >= price ? <>The bid ({px(p.bid)}) is already above it: </>
            : <>Bid {px(p.bid)}: nothing happens until the bid reaches {px(price)}; then </>}
          if the bid falls back to {px(price)}, {all} {lots === 1 ? "is" : "are"} sold. Above it the position rides on;
          the strategy's exits and the stop still apply.
          {live && <> <b className="text-amber-800">LIVE{account ? ` (${account})` : ""}: a real sell order at the broker for {all}
            ({p.quantity} qty); in a fast fall it can fill below {px(price)}.</b></>}
        </div>
      )}
      {reasonRequired && (
        <input value={reason} maxLength={300} onChange={(e) => setReason(e.target.value)} aria-label="Reason for the target"
          placeholder="Reason (the members are told; needed to set or remove it)"
          className={`w-full rounded border border-line bg-white px-2 ${phone ? "min-h-11 text-base" : "py-1"} text-text`} />
      )}
      <div className="flex flex-wrap items-center gap-2">
        <button type="submit" className={`${btn} border-accent bg-accent text-white`} disabled={pct == null || busy || noReason}>
          {save.isPending ? "Saving…" : "Save target"}</button>
        {active && (
          <button type="button" className={`${btn} border-line bg-white text-down`} disabled={busy || noReason}
            onClick={() => remove.mutate()}>{remove.isPending ? "Removing…" : "Remove target"}</button>
        )}
        <button type="button" className={`${btn} border-line bg-white text-text`} disabled={busy}
          onClick={() => setEditing(false)}>Cancel</button>
      </div>
      {save.error && <div className="text-down" role="alert">Not saved: {(save.error as Error).message}</div>}
      {remove.error && <div className="text-down" role="alert">Not removed: {(remove.error as Error).message}</div>}
    </form>
  );
}
