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
  strategy?: string;
  /** "" for a single-leg position, "CE"/"PE" for a straddle leg */
  leg?: string;
  boughtQuantity?: number;
  lotSize?: number;
  premiumPaid?: number;
  bid?: number | null;
  value?: number | null;
  unrealised?: number;
  realised?: number;
  costs?: number;
  exitPrice?: number | null;
  targetPct?: number | null;
  stopPct?: number | null;
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
  /** risk v8/v9: the session's capital (equity at its start), the starting capital, sizing and the daily loss limit */
  capital?: number;
  startingCapital?: number;
  budgetScale?: number;
  dailyLossLimit?: number;
  /** risk v16 (A-101): operator releases of the daily-loss switch left today, and the limit raise per release (% of equity). */
  dailyLossReleasesLeft?: number;
  dailyLossReleaseRaisePct?: number;
  dailyLossAutoRelease?: boolean;
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
  /** config file name -> content hash, e.g. {"ma-cross.v1.yaml": "sha256:..."} */
  configs?: Record<string, string>;
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

/** One run on the Sessions page's dropdown (GET /api/sessions/runs): sessions with the same mode, code, strategy set,
 * config files and data kind; a re-run day counts once. */
export interface SessionRunRow {
  mode: string;
  code_version: string;
  strategy_id: string;
  /** hash of the config files: the run filter's {@code configs} parameter */
  config_key: string;
  data: "bars" | "ticks";
  configs?: Record<string, string>;
  feeds: string;
  days: number;
  sessions: number;
  date_from: string;
  date_to: string;
  net: number;
  last_started: string;
}

export interface Scores { stage: string; early: number; confirm: number; runner: number }

export interface DecisionRow {
  t: string;
  strategy_id?: string;
  spot: number | null;
  ce_stage: string;
  pe_stage: string;
  ce_scores: Scores;
  pe_scores: Scores;
  state: MarketState;
  orders: string | null;
}

/** The last decision of one underlying and strategy in a session (the Live page's last-session view). */
export interface LastDecisionRow extends DecisionRow {
  underlying: string;
  strategy_id: string;
}

export interface RejectionRow {
  strategy_id: string | null;
  underlying: string;
  intent: string;
  reason: string;
  at: string;
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
  strategy_id?: string | null;
  quantity?: number | null;
  lot_size?: number | null;
  average_cost?: number | null;
  average_exit?: number | null;
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

/** /api/equity: the live account's equity, starting capital + realised net of every finished live session. */
export interface EquityDay {
  date: string;
  equityBefore: number;
  net: number;
  equityAfter: number;
}
export interface Equity {
  account: string;
  riskVersion?: string | null;
  equityMode: boolean;
  startingCapital: number;
  /** risk v10: the date the equity counts from (null = every live session) */
  equityFrom?: string | null;
  realisedNet: number;
  equity: number;
  budgetScale: number;
  dailyLossLimit: number;
  asOf: string;
  days: EquityDay[];
}

/** One closed position with its day, from /api/performance (the Performance page aggregates these). */
export interface PerformanceRow {
  session_id: number;
  session_date: string;
  strategy_id: string | null;
  underlying: string;
  option_side: string;
  symbol: string;
  opened_at: string;
  closed_at: string;
  exit_reason: string | null;
  realised: number;
  costs: number;
  net: number;
  quantity: number | null;
  lot_size: number | null;
  average_cost: number | null;
  average_exit: number | null;
}

// ---------------------------------------------------------------- execution workers (Accounts page)
// Same origin: trading-core forwards /api/execution/** to app-execution (ExecutionProxyController).

/** POST a JSON body; a refusal's reason (the API's "message" or "error") becomes the thrown error. */
export async function postJson<T>(path: string, body: unknown): Promise<T> {
  const response = await fetch(path, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body ?? {}),
  });
  const text = await response.text();
  let parsed: Json | null = null;
  try {
    parsed = text ? (JSON.parse(text) as Json) : null;
  } catch {
    parsed = null;
  }
  if (!response.ok) {
    const reason = (parsed?.message as string) || (parsed?.error as string) || response.statusText;
    throw new Error(`${response.status}: ${reason}`);
  }
  return parsed as T;
}

export interface AccountTurn {
  accountId: number;
  tenantId: number;
  account: string;
  mode: string;
  broker: string;
  route: string | null;
  leaseEpoch: number | null;
  state: string;
  lastRun: string;
  lastResult: string;
}

export interface WorkerStatus {
  worker: string;
  copyGroup: string;
  day: string;
  lastTick: string | null;
  stale: boolean;
  accounts: AccountTurn[];
}

export interface ExecutionAccount {
  id: number;
  tenantId: number;
  name: string;
  broker: string;
  mode: string;
  role: string;
  enabled: boolean;
  allocatedCapital: number;
  riskFile: string;
  connection: string | null;
  lastTurn: AccountTurn | null;
}

export interface KillSwitch {
  id: number;
  tenantId: number | null;
  scope: "GLOBAL_LIVE" | "ACCOUNT" | "STRATEGY" | "INSTRUMENT";
  accountId: number | null;
  strategyId: string | null;
  instrumentKey: string | null;
  reason: string;
  engagedBy: string;
  engagedAt: string;
}

export interface AuditRow {
  id: number;
  tenant_id: number | null;
  broker_account_id: number | null;
  actor: string;
  action: string;
  detail: string;
  at: string;
}
