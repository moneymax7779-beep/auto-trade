// Thin client for trading-core's API (same origin; Vite proxies /api in development).

export type Json = Record<string, unknown>;

export async function get<T>(path: string): Promise<T> {
  const response = await fetch(path);
  if (!response.ok) throw new Error(`${response.status} ${response.statusText} for ${path}`);
  return (await response.json()) as T;
}

export async function post<T>(path: string): Promise<T> {
  const response = await fetch(path, { method: "POST" });
  if (!response.ok) throw new Error(`${response.status} ${response.statusText} for ${path}`);
  return (await response.json()) as T;
}

export interface PositionRow {
  underlying: string;
  side: string;
  symbol: string;
  state: string;
  quantity: number;
  averageCost: number;
  stages: string[];
  net: number;
  exitReason: string | null;
  opened: string | null;
  closed: string | null;
}

export interface MarketState {
  direction: number;
  participation: number;
  structure: number;
  continuation: number;
  regime: string;
  label?: string | null;
}

export interface StrategyView {
  time: string;
  spot: number;
  CE: string;
  PE: string;
  state: MarketState;
  volatility?: Record<string, number | string>;
  cas?: Record<string, number | string>;
}

export interface Status {
  session?: number;
  status: string;
  mode?: string;
  date?: string;
  account?: string;
  feed?: string;
  events?: number;
  lastEvent?: string | null;
  feedLagSeconds?: number | null;
  dayPnl?: number;
  killSwitches?: string[];
  openPositions?: PositionRow[];
  closedPositions?: PositionRow[];
  strategy?: Record<string, StrategyView>;
  recentRejections?: string[];
  schedule?: { mode: string; nextStart?: string; dailyWindow?: string };
}

export interface SessionRow {
  id: number;
  account: string;
  mode: string;
  session_date: string;
  feed: string;
  strategy_id: string;
  strategy_hash: string;
  code_version: string;
  started_at: string;
  ended_at: string | null;
  status: string;
  summary: { positions?: number; wins?: number; net?: number; costs?: number; events?: number; reconciled?: boolean };
  error: string | null;
}

export interface Scores { stage: string; early: number; confirm: number; runner: number }

export interface DecisionRow {
  t: string;
  spot: number | null;
  ce_stage: string;
  pe_stage: string;
  ce_scores: Scores;
  pe_scores: Scores;
  state: MarketState;
  orders: string | null;
}

export interface OrderRow {
  client_order_id: string;
  underlying: string;
  option_side: string;
  role: string;
  order_side: string;
  order_type: string;
  symbol: string;
  quantity: number;
  limit_price: number;
  trigger_price: number | null;
  sent_at: string;
  status: string | null;
  filled: number | null;
  average_price: number | null;
}

export interface TradePositionRow {
  underlying: string;
  option_side: string;
  symbol: string;
  opened_at: string;
  closed_at: string | null;
  stages: string[];
  exit_reason: string | null;
  realised: number;
  costs: number;
  net: number;
}

export interface RunRow {
  id: number;
  kind: string;
  status: string;
  started_at: string;
  finished_at: string | null;
  code_version: string;
  source: string;
  sessions: string;
  underlyings: string;
  config: Record<string, string>;
  notes: Json;
  error: string | null;
}

export interface EpisodeRow {
  lane: string;
  session_date: string;
  underlying: string;
  side: string;
  symbol: string;
  entry_stage: string;
  stages: string[];
  exit_reason: string;
  tranches: number;
  max_quantity: number;
  average_cost: number | null;
  gross: number;
  costs: number;
  net: number;
  risk: number | null;
  r_multiple: number | null;
  mfe_per_unit: number | null;
  mae_per_unit: number | null;
  opened_at: string | null;
  closed_at: string | null;
}

export interface FrameRow {
  t: string;
  spot: number | null;
  regime: string;
  ce_stage: string;
  pe_stage: string;
  ce_early: number | null;
  ce_confirm: number | null;
  ce_runner: number | null;
  pe_early: number | null;
  pe_confirm: number | null;
  pe_runner: number | null;
  direction: number | null;
  participation: number | null;
  structure: number | null;
  continuation: number | null;
  orders: string | null;
}

export interface ConfigRow { file: string; id?: string; version?: string; status?: string; hash?: string; error?: string }

/** Parses "STAGE early N confirm N runner N" from the live status. */
export function parseSide(text: string): Scores {
  const m = /^(\S+) early (-?\d+) confirm (-?\d+) runner (-?\d+)/.exec(text ?? "");
  return m
    ? { stage: m[1], early: Number(m[2]), confirm: Number(m[3]), runner: Number(m[4]) }
    : { stage: text ?? "?", early: NaN, confirm: NaN, runner: NaN };
}

export const rupees = (value: number | null | undefined) =>
  value == null || Number.isNaN(value) ? "–" : `₹${Math.round(value).toLocaleString("en-IN")}`;

export const fixed = (value: number | null | undefined, digits = 2) =>
  value == null || Number.isNaN(value) ? "–" : value.toFixed(digits);
