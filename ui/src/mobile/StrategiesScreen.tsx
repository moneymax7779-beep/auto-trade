import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useRef, useState } from "react";
import { get, postJson, type GlobalLiveStrategies, type PerformanceRow } from "../api";
import { planGlobal, STATES, type StrategyState } from "../components/strategySwitch";
import { Big, Card, ErrorLine, Sheet, signed, tone } from "./common";

/** Fewer live sessions than this with trades: the record is thin, and switching to Live needs LIVE typed. */
export const THIN_SESSIONS = 10;

export interface StrategyRecord { sessions: number; trades: number; wins: number; net: number; last10: number; first: string | null;
  worstDay: number }

/** Each strategy's own record on the live PAPER sessions (closed positions, net of costs), by session day. */
export function records(rows: PerformanceRow[]): Map<string, StrategyRecord> {
  const byDay = new Map<string, Map<string, { net: number; trades: number; wins: number }>>();
  for (const r of rows) {
    if (!r.strategy_id) continue;
    const days = byDay.get(r.strategy_id) ?? new Map();
    const d = days.get(r.session_date) ?? { net: 0, trades: 0, wins: 0 };
    d.net += r.net; d.trades += 1; d.wins += r.net > 0 ? 1 : 0;
    days.set(r.session_date, d);
    byDay.set(r.strategy_id, days);
  }
  const out = new Map<string, StrategyRecord>();
  for (const [id, days] of byDay) {
    const sorted = [...days.entries()].sort(([a], [b]) => a.localeCompare(b));
    const nets = sorted.map(([, d]) => d.net);
    out.set(id, {
      sessions: sorted.length,
      trades: sorted.reduce((s, [, d]) => s + d.trades, 0),
      wins: sorted.reduce((s, [, d]) => s + d.wins, 0),
      net: nets.reduce((s, v) => s + v, 0),
      last10: nets.slice(-10).reduce((s, v) => s + v, 0),
      first: sorted[0]?.[0] ?? null,
      worstDay: Math.min(0, ...nets),
    });
  }
  return out;
}

const LABEL: { [k in StrategyState]: string } = { OFF: "Off", PAPER: "Paper", LIVE: "Live" };

/**
 * Mobile phase 3 (17 Nov 2026): the owner's Off / Paper / Live switch per strategy for every account, one card each with
 * its version and its own record on the live PAPER sessions, so a switch is made with the evidence in view. The request
 * is the desktop's (components/strategySwitch.ts). Applies from each strategy's next entry; exits always run. A strategy
 * with a thin live record needs LIVE typed before it trades real money. Viewers see the cards without the switch.
 */
export function StrategiesScreen() {
  const client = useQueryClient();
  const q = useQuery({ queryKey: ["live-strategies-global"], refetchInterval: 30_000, retry: false,
    queryFn: () => get<GlobalLiveStrategies>("/api/execution/live-strategies") });
  const versions = useQuery({ queryKey: ["strategies-live"], retry: false, staleTime: 300_000,
    queryFn: () => get<{ live: { strategy: string; version: string }[] }>("/api/strategies") });
  const perf = useQuery({ queryKey: ["performance"], retry: false, staleTime: 300_000,
    queryFn: () => get<PerformanceRow[]>("/api/performance") });
  const [picked, setPicked] = useState<Record<string, StrategyState> | null>(null);
  const [reviewing, setReviewing] = useState(false);
  if (q.error) return <div className="mx-auto max-w-xl"><h1 className="mb-3 text-xl font-semibold">Strategies</h1><ErrorLine error={q.error} /></div>;
  if (!q.data) return <p className="text-sm text-muted">Loading…</p>;
  const g = q.data;
  const plan = planGlobal(g, picked);
  const recs = perf.data ? records(perf.data) : null;
  const version = new Map((versions.data?.live ?? []).map((v) => [v.strategy, v.version]));
  const set = (s: string, to: StrategyState) => setPicked((p) => ({ ...(p ?? {}), [s]: to }));
  const edits = plan.names.filter((s) => plan.state(s) !== plan.saved(s)).length;
  return (
    <div className="mx-auto flex max-w-xl flex-col gap-3 pb-20">
      <h1 className="text-xl font-semibold">Strategies</h1>
      <p className="text-xs text-muted">For every account. <b>Off</b>: no new entries anywhere. <b>Paper</b>: paper accounts only.
        <b> Live</b>: also real money on LIVE accounts, within each account's risk file. Applies from the next entry; open
        positions keep their exits.</p>
      {recs == null && perf.error == null && <p className="text-xs text-muted">Loading the live records…</p>}
      {plan.names.map((s) => {
        const st = plan.state(s);
        const r = recs?.get(s);
        const liveOk = g.available.includes(s);
        const dirty = st !== plan.saved(s);
        return (
          <Card key={s} className={dirty ? "border-sky-700" : ""}>
            <div className="flex items-baseline justify-between gap-2">
              <h2 className={`min-w-0 truncate font-semibold ${st === "OFF" ? "text-muted line-through" : ""}`}>{s}</h2>
              <span className="shrink-0 text-xs text-muted">{version.get(s) ?? ""}{dirty ? " · not saved" : ""}</span>
            </div>
            {recs && (r ? (
              <div className="mt-1 text-xs">
                <span className={`num font-semibold ${tone(r.net)}`}>{signed(r.net)}</span>
                <span className="text-muted"> on {r.sessions} live PAPER session{r.sessions === 1 ? "" : "s"} since {r.first} ·{" "}
                  {r.trades} trades, {Math.round((100 * r.wins) / Math.max(1, r.trades))} % won</span>
                <div className="text-muted">last 10 sessions <span className={`num ${tone(r.last10)}`}>{signed(r.last10)}</span>
                  {" "}· worst day <span className="num text-down">{signed(r.worstDay)}</span></div>
                {r.sessions < THIN_SESSIONS && <div className="font-semibold text-warn">Thin record: fewer than {THIN_SESSIONS} live sessions.</div>}
              </div>
            ) : <p className="mt-1 text-xs font-semibold text-warn">No live PAPER record yet.</p>)}
            {plan.running.length > 0 && !plan.running.includes(s) && <p className="text-xs text-muted">Not in the running set (deploy decides).</p>}
            <div role="group" aria-label={`${s}: off, paper or live`} className="mt-2 grid grid-cols-3 overflow-hidden rounded-lg border border-line text-sm">
              {STATES.map((to) => {
                const disabled = !g.canEdit || (to === "LIVE" && !liveOk);
                return (
                  <button key={to} type="button" aria-pressed={st === to} disabled={disabled} onClick={() => set(s, to)}
                    title={to === "LIVE" && !liveOk ? "No account's risk file lets it trade LIVE" : undefined}
                    className={`min-h-11 ${st === to ? (to === "LIVE" ? "bg-down font-semibold text-white" : to === "PAPER"
                      ? "bg-sky-700 font-semibold text-white" : "bg-muted font-semibold text-white") : "text-muted"} disabled:opacity-40`}>
                    {LABEL[to]}{to === "LIVE" && !liveOk ? " (no risk file)" : ""}</button>
                );
              })}
            </div>
          </Card>
        );
      })}
      {!g.canEdit && <p className="text-xs text-muted">Only the platform owner changes these. Your own account's LIVE list is
        under Brokers → Capital &amp; strategies.</p>}
      {g.canEdit && plan.changed && (
        <div className="fixed inset-x-0 bottom-[calc(3.5rem+env(safe-area-inset-bottom))] z-30 border-t border-line bg-panel px-4 py-2 md:bottom-0">
          <div className="mx-auto flex max-w-xl gap-2">
            <Big onClick={() => setPicked(null)}>Undo</Big>
            <div className="flex-1"><Big kind="primary" onClick={() => setReviewing(true)}>Review {edits} change{edits === 1 ? "" : "s"}</Big></div>
          </div>
        </div>
      )}
      {reviewing && <ReviewSheet g={g} picked={picked} recs={recs} onClose={() => setReviewing(false)}
        onSaved={(r) => { setPicked(null); setReviewing(false); client.setQueryData(["live-strategies-global"], r);
          ["exec-audit", "exec-accounts", "live-strategies"].forEach((k) => client.invalidateQueries({ queryKey: [k] })); }} />}
    </div>
  );
}

function ReviewSheet({ g, picked, recs, onClose, onSaved }: { g: GlobalLiveStrategies; picked: Record<string, StrategyState> | null;
  recs: Map<string, StrategyRecord> | null; onClose: () => void; onSaved: (r: GlobalLiveStrategies) => void }) {
  const plan = planGlobal(g, picked);
  const thin = plan.toLive.filter((s) => (recs?.get(s)?.sessions ?? 0) < THIN_SESSIONS);
  const [typed, setTyped] = useState("");
  const sending = useRef(false);
  const save = useMutation({
    mutationFn: () => postJson<GlobalLiveStrategies>("/api/execution/live-strategies", plan.body),
    onSuccess: onSaved,
    onSettled: () => { sending.current = false; },
  });
  const ready = !save.isPending && (thin.length === 0 || typed.trim().toUpperCase() === "LIVE");
  return (
    <Sheet title="Save strategy changes" onClose={onClose}>
      <ul className="mb-3 flex flex-col gap-2 text-sm">
        {plan.toLive.length > 0 && <li className="font-semibold text-down">Real money: {plan.toLive.join(", ")} will trade LIVE
          {plan.liveAccounts.length > 0 ? ` on ${plan.liveAccounts.join(", ")}` : " on LIVE accounts"} from its next entry.</li>}
        {plan.fromLive.length > 0 && <li>No longer LIVE: {plan.fromLive.join(", ")}.</li>}
        {plan.toOff.length > 0 && <li>Off everywhere (no new entry, paper or real): {plan.toOff.join(", ")}.</li>}
        {plan.backOn.length > 0 && <li>Back on: {plan.backOn.join(", ")}.</li>}
      </ul>
      {thin.length > 0 && <>
        <p className="mb-2 text-sm font-semibold text-warn">{thin.join(", ")} {thin.length === 1 ? "has" : "have"} fewer than {THIN_SESSIONS} live
          PAPER sessions. Type LIVE to trade real money with {thin.length === 1 ? "it" : "them"} anyway.</p>
        <input className="mb-3 min-h-11 w-full rounded-lg border border-line bg-bg px-3 text-base uppercase" placeholder="Type LIVE"
          autoCapitalize="characters" autoCorrect="off" aria-label="Type LIVE to confirm" value={typed} onChange={(e) => setTyped(e.target.value)} />
      </>}
      {save.error && <p className="mb-2 text-sm text-down">{(save.error as Error).message}</p>}
      <div className="flex flex-col gap-2">
        <Big kind={plan.toLive.length > 0 ? "danger" : "primary"} disabled={!ready}
          onClick={() => { if (!sending.current) { sending.current = true; save.mutate(); } }}>
          {save.isPending ? "Saving…" : "Save for all accounts"}</Big>
        <Big onClick={onClose}>Back</Big>
      </div>
    </Sheet>
  );
}
