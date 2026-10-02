import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router";
import { get, parseSide, type Status } from "../api";
import { splitDecisionKey, STRATEGY_INFO } from "../components/strategies";
import { ErrorNote, Loading, Panel, Pnl, StageBadge } from "../components/ui";

interface RecordRow { id: number; date: string; status: string; file: string | null; trades: number; wins: number; net: number }
interface Card {
  file: string; strategy?: string; version?: string; status?: string; source?: string; hash?: string;
  scope?: Record<string, unknown>; notes?: string; sessions?: RecordRow[]; error?: string;
}
interface StrategiesReport { live: Card[]; shadow: Card[]; featuresFile: string; riskFile: string }

/**
 * Every strategy this service runs: live (PAPER, sharing the capital, deciding in the listed order) and
 * shadow (replayed after the close on its own account, never trading). Each with what it does, its config
 * file's scope, today's stage on each index, and every session it ran in.
 */
export function StrategiesPage() {
  const report = useQuery({ queryKey: ["strategies"], queryFn: () => get<StrategiesReport>("/api/strategies") });
  const status = useQuery({ queryKey: ["status"], queryFn: () => get<Status>("/api/status"), refetchInterval: 5000 });
  if (report.isLoading) return <Loading what="strategies" />;
  if (report.error) return <ErrorNote error={report.error} />;
  const r = report.data!;
  const today = Object.entries(status.data?.strategy ?? {}).map(([key, view]) => ({ ...splitDecisionKey(key), view }));
  return (
    <div className="space-y-4">
      <Panel title="Strategies">
        <p className="text-sm text-muted">
          Live strategies trade PAPER side by side on one feed, sharing the capital; on each snapshot they decide in this order,
          so when two trigger together the first takes the capital. Shadow strategies replay the day after the close on a
          separate account and never trade. Every value lives in a versioned file under config/strategy; a change is a new file.
        </p>
        <p className="mt-2 text-xs text-muted num">features {r.featuresFile} · risk {r.riskFile}</p>
      </Panel>
      <h2 className="text-sm font-semibold">Live · PAPER</h2>
      {r.live.map((c, i) => <StrategyCard key={c.file} card={c} order={i + 1} today={today.filter((t) => t.strategy === c.strategy)} />)}
      {r.shadow.length > 0 && <h2 className="pt-2 text-sm font-semibold">Shadow · replayed after the close, no orders</h2>}
      {r.shadow.map((c) => <StrategyCard key={c.file} card={c} shadow today={[]} />)}
    </div>
  );
}

function StrategyCard({ card, order, shadow, today }: {
  card: Card; order?: number; shadow?: boolean;
  today: { underlying: string; view: { time: string; CE: string; PE: string } }[];
}) {
  if (card.error) return <Panel title={card.file}><p className="text-sm text-warn">{card.error}</p></Panel>;
  const info = STRATEGY_INFO[card.strategy ?? ""];
  const rows = card.sessions ?? [];
  const traded = rows.filter((x) => x.trades > 0);
  const total = rows.reduce((s, x) => s + Number(x.net), 0);
  const trades = rows.reduce((s, x) => s + Number(x.trades), 0), wins = rows.reduce((s, x) => s + Number(x.wins), 0);
  return (
    <Panel
      title={<>
        {order != null && <span className="mr-2 text-muted num">{order}.</span>}
        {info?.name ?? card.strategy} <span className="ml-1 text-xs font-normal text-muted num">{card.version}</span>
        <span className={`ml-2 rounded px-1.5 py-0.5 text-[10px] font-bold ${shadow ? "bg-panel-2 text-muted" : "bg-warn/15 text-warn"}`}>{shadow ? "SHADOW" : "LIVE · PAPER"}</span>
        {card.status && <span className="ml-1 rounded bg-panel-2 px-1.5 py-0.5 text-[10px] text-muted">{card.status}</span>}
      </>}
      right={<span className="text-xs text-muted num">{card.file.replace("config/strategy/", "")}</span>}
    >
      {info && <p className="text-sm">{info.what} <span className="text-muted">({info.when})</span></p>}
      <div className="mt-3 grid gap-4 lg:grid-cols-3">
        <div>
          <div className="mb-1 text-xs font-semibold">Scope (from the file)</div>
          {Object.keys(card.scope ?? {}).length === 0 && <p className="text-xs text-muted">No scope section in this file; see its notes below.</p>}
          <dl className="space-y-0.5 text-xs">
            {Object.entries(card.scope ?? {}).map(([k, v]) => (
              <div key={k} className="flex gap-2"><dt className="w-40 shrink-0 text-muted">{k.replace(/_/g, " ")}</dt><dd className="num">{fmt(v)}</dd></div>
            ))}
          </dl>
          {today.length > 0 && (
            <div className="mt-3">
              <div className="mb-1 text-xs font-semibold">Now</div>
              {today.map((t) => {
                const ce = parseSide(t.view.CE), pe = parseSide(t.view.PE);
                return (
                  <div key={t.underlying} className="flex items-center gap-2 text-xs">
                    <Link to={`/index/${t.underlying}`} className="w-16 hover:underline">{t.underlying}</Link>
                    CE <StageBadge stage={ce.stage} /> PE <StageBadge stage={pe.stage} /> <span className="num text-muted">{t.view.time}</span>
                  </div>
                );
              })}
            </div>
          )}
        </div>
        <div className="lg:col-span-2">
          <div className="mb-1 flex items-baseline gap-3 text-xs">
            <span className="font-semibold">{shadow ? "Shadow record" : "Live record"}</span>
            <span className="text-muted num">{rows.length} sessions · traded on {traded.length} · {trades} trades · {wins} won · net <Pnl value={total} /></span>
          </div>
          {rows.length === 0 ? <p className="text-xs text-muted">Not run yet.</p> : (
            <div className="max-h-56 overflow-y-auto">
              <table className="w-full text-xs num">
                <thead className="text-left text-muted"><tr>
                  <th className="py-1 font-normal">Date</th><th className="font-normal">Session</th><th className="font-normal">File</th>
                  <th className="text-right font-normal">Trades</th><th className="text-right font-normal">Won</th><th className="text-right font-normal">Net</th>
                </tr></thead>
                <tbody>
                  {rows.map((x) => (
                    <tr key={x.id} className={`border-t border-line ${x.trades > 0 ? "" : "text-muted"}`}>
                      <td className="py-1">{x.date}</td>
                      <td><Link to={`/sessions/${x.id}`} className="hover:underline">#{x.id}</Link>{x.status !== "DONE" && <span className="ml-1 text-warn">{x.status}</span>}</td>
                      <td>{x.file?.replace(".yaml", "") ?? "–"}</td>
                      <td className="text-right">{x.trades}</td><td className="text-right">{x.wins}</td>
                      <td className="text-right">{x.trades > 0 ? <Pnl value={Number(x.net)} /> : "–"}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          <p className="mt-2 text-xs text-muted">
            Few sessions: a record this short describes what happened, it is not evidence of an edge. Replays and studies are under
            {" "}<Link to="/research" className="text-accent hover:underline">Research</Link>.
          </p>
        </div>
      </div>
      {card.notes && (
        <details className="mt-3 text-xs">
          <summary className="cursor-pointer text-muted">Version history and notes (the file's header)</summary>
          <pre className="mt-2 max-h-72 overflow-y-auto whitespace-pre-wrap rounded bg-panel-2 p-3 text-[11px] leading-relaxed">{card.notes}</pre>
        </details>
      )}
    </Panel>
  );
}

const fmt = (v: unknown): string =>
  v == null ? "–" : Array.isArray(v) ? v.join(", ") : typeof v === "object"
    ? Object.entries(v as Record<string, unknown>).map(([k, x]) => `${k}: ${fmt(x)}`).join(" · ") : String(v);
