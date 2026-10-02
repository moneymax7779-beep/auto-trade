import { type ReactNode } from "react";
import { type MarketState } from "../api";
import { Panel, SignedGauge } from "./ui";

/** One index's market state from the latest snapshot: the four gauges, the volatility regime and the auction. */
export function MarketCard({ title, spot, time, state, volatility, cas, right }: {
  title: ReactNode; spot: number | null; time: string; state: MarketState;
  volatility?: Record<string, number | string>; cas?: Record<string, number | string>; right?: ReactNode;
}) {
  return (
    <Panel
      title={<>{title} <span className="num ml-2 text-base">{spot != null && Number.isFinite(spot) ? spot.toFixed(2) : "–"}</span></>}
      right={right ?? <span className="text-xs text-muted">{time} IST · {state?.label ? state.label + " · " : ""}{state?.regime}</span>}
    >
      <div className="grid grid-cols-2 gap-x-6 gap-y-3 sm:grid-cols-4">
        <SignedGauge label="Direction" value={state?.direction} />
        <SignedGauge label="Structure" value={state?.structure} />
        <SignedGauge label="Participation" value={state?.participation} signed={false} />
        <SignedGauge label="Continuation" value={state?.continuation} />
      </div>
      <FactPanel title="Volatility regime" facts={volatility} />
      {cas && (cas.indicative !== undefined || cas.iepPressurePct !== undefined)
        ? <FactPanel title="Closing auction" facts={cas} /> : null}
    </Panel>
  );
}

const FACT_LABELS: Record<string, string> = {
  dte: "DTE", minutesToExpiry: "Min to expiry", atmIvPct: "ATM IV %", ivPercentile: "IV pctl", ivTrend: "IV trend",
  vix: "India VIX", vixPercentile252d: "VIX pctl 252d", vixChange15m: "VIX Δ15m", realizedVolPct: "Realised vol %",
  realizedVolPercentile: "RV pctl", atrPercentile: "ATR pctl", futuresRvol: "Futures RVOL",
  expectedMoveRemaining: "Exp. move left", event: "Event", phase: "Phase", indicative: "Indicative",
  reference: "Reference", iepPressurePct: "IEP pressure %", imbalance: "Imbalance", breadthPct: "CAS breadth %",
  top3: "Top-3 share", futuresVsIndicative: "Fut − indicative", liquidityPercentile: "Liquidity pctl",
  coveragePct: "Coverage %",
};

export function FactPanel({ title, facts }: { title: string; facts?: Record<string, number | string> }) {
  if (!facts || Object.keys(facts).length === 0) return null;
  return (
    <div className="mt-4 rounded border border-line p-3">
      <div className="mb-2 text-xs font-semibold">{title}</div>
      <dl className="grid grid-cols-2 gap-x-4 gap-y-1 text-xs sm:grid-cols-3">
        {Object.entries(facts).map(([key, value]) => (
          <div key={key} className="flex min-w-0 justify-between gap-2">
            <dt className="truncate text-muted">{FACT_LABELS[key] ?? key}</dt>
            <dd className="num">{String(value)}</dd>
          </div>
        ))}
      </dl>
    </div>
  );
}

