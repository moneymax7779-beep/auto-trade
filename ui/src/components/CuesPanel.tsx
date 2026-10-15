import { useQuery } from "@tanstack/react-query";
import { get } from "../api";
import { ErrorNote, Panel, Stat } from "./ui";

/** /api/cues (feature/missing-data): features v13 cues as of now, participant positioning, collector state. */
interface CueFeatures {
  giftNiftyLast: number | null; giftNiftyChangePct: number | null; giftNiftyQuoteAgeMin: number | null;
  giftNiftyMovePct: number | null; fiiIndexFutLongPct: number | null; fiiIndexFutLongPctChange: number | null;
  fiiIndexCallNet: number | null; fiiIndexPutNet: number | null; clientIndexFutLongPct: number | null;
  proIndexFutLongPct: number | null; positioningAgeDays: number | null;
}
interface PositionRow {
  session: string; type: string; futureIndexLong: number; futureIndexShort: number; futureIndexLongPct: number;
  optionIndexCallNet: number; optionIndexPutNet: number;
}
interface CuesData {
  date: string; asOf: string; features: CueFeatures; positioning: PositionRow[];
  collector: { enabled?: boolean; running?: boolean; giftLastFetch?: string; giftError?: string | null; participantError?: string | null };
}

const pct = (v: number | null | undefined, digits = 2) => (v == null ? "–" : `${v > 0 ? "+" : ""}${v.toFixed(digits)} %`);
const tone = (v: number | null | undefined) => (v == null ? "muted" : v > 0 ? "up" : v < 0 ? "down" : undefined);
const num = (v: number | null | undefined) => (v == null ? "–" : v.toLocaleString("en-IN"));

/** Pre-open and positioning context: GIFT Nifty (overnight) and NSE participant-wise index futures positioning. */
export function CuesPanel() {
  const cues = useQuery({ queryKey: ["cues"], queryFn: () => get<CuesData>("/api/cues"), refetchInterval: 60_000 });
  if (cues.error) return <ErrorNote error={cues.error} />;
  if (!cues.data) return null;
  const f = cues.data.features;
  const fii = cues.data.positioning.filter((p) => p.type === "FII").slice(0, 5);
  const c = cues.data.collector;
  return (
    <Panel title="Pre-open cues" right={<span className="text-xs text-muted">
      {c.running ? `collecting · GIFT ${c.giftLastFetch ? c.giftLastFetch.slice(11, 16) : "–"}` : "collector off"}
      {c.giftError ? ` · GIFT error: ${c.giftError}` : ""}{c.participantError ? ` · OI error: ${c.participantError}` : ""}
    </span>}>
      <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-6">
        <Stat label="GIFT Nifty" value={num(f.giftNiftyLast)} />
        <Stat label="GIFT day change" value={pct(f.giftNiftyChangePct)} tone={tone(f.giftNiftyChangePct)} />
        <Stat label="GIFT since Indian close" value={pct(f.giftNiftyMovePct)} tone={tone(f.giftNiftyMovePct)} />
        <Stat label="FII index futures long" value={pct(f.fiiIndexFutLongPct, 1).replace("+", "")}
          tone={f.fiiIndexFutLongPct == null ? "muted" : f.fiiIndexFutLongPct >= 50 ? "up" : "down"} />
        <Stat label="FII long % change" value={pct(f.fiiIndexFutLongPctChange, 1)} tone={tone(f.fiiIndexFutLongPctChange)} />
        <Stat label="Client index futures long" value={pct(f.clientIndexFutLongPct, 1).replace("+", "")} />
      </div>
      {fii.length > 0 && (
        <table className="mt-4 w-full text-sm">
          <thead className="text-left text-muted">
            <tr><th className="py-1">FII (NSE, end of day)</th><th className="text-right">Index fut long</th>
              <th className="text-right">Index fut short</th><th className="text-right">Long %</th>
              <th className="text-right">Index calls net</th><th className="text-right">Index puts net</th></tr>
          </thead>
          <tbody className="num">
            {fii.map((p) => (
              <tr key={p.session} className="border-t border-line">
                <td className="py-1">{p.session}</td><td className="text-right">{num(p.futureIndexLong)}</td>
                <td className="text-right">{num(p.futureIndexShort)}</td><td className="text-right">{p.futureIndexLongPct.toFixed(1)}</td>
                <td className="text-right">{num(p.optionIndexCallNet)}</td><td className="text-right">{num(p.optionIndexPutNet)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <p className="mt-2 text-xs text-muted">
        GIFT Nifty from NSE IX (quote age {f.giftNiftyQuoteAgeMin == null ? "–" : `${Math.round(f.giftNiftyQuoteAgeMin)} min`});
        positioning is the last session NSE has published ({f.positioningAgeDays ?? "–"} day(s) before). Context only: no strategy uses these yet.
      </p>
    </Panel>
  );
}
