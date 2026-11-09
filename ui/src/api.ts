// Thin client for trading-core's API (same origin; Vite proxies /api in development).

export type Json = Record<string, unknown>;

/**
 * The live PAPER account's own sessions (PAPER-1), not the signal source's (SIGNAL-1, also PAPER_LIVE since the live
 * execution signal source runs alongside it): what "the live session" means on every page.
 */
export function isPrimaryLive(row: { mode: string; account?: string }): boolean {
  return row.mode === "PAPER_LIVE" && !(row.account ?? "").startsWith("SIGNAL-");
}

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
  /** A-358: LONG or SHORT (sold to open). */
  direction?: string | null;
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
  id?: number;
  strategy_id?: string | null;
  quantity?: number | null;
  lot_size?: number | null;
  average_cost?: number | null;
  average_exit?: number | null;
  /** A-358: LONG (bought to open; every row before V31) or SHORT (sold to open: average_cost is the credit). */
  direction?: string | null;
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
  /** A-358: LONG or SHORT (sold to open); undefined on rows older than V31 means LONG. */
  direction?: string | null;
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

/** GET /api/execution/me: the signed-in user's tenants and role in each (family members: their own tenant only). */
export interface ExecMe {
  user: string;
  admin: boolean;
  tenants: {
    id: number; name: string; role: string; mayAct: boolean; manages: boolean; follows?: string[];
    /** trader workspaces (V29): DAILY_SWITCH = a member's workspace with today's broker and PAPER/LIVE switch */
    control?: "ACCOUNT_MODE" | "DAILY_SWITCH"; canStop?: boolean; support?: boolean; helping?: boolean;
    grantUntil?: string | null;
  }[];
  telegramLinked?: boolean;
  keycloakAdmin?: boolean;
}

// ---------------------------------------------------------------- trader workspaces (7 Nov 2026)

export interface DayResult { net: number; trades: number; open: number }

export interface WorkspaceBroker {
  id: number;
  name: string;
  broker: "PAPER" | "ZERODHA" | "UPSTOX" | "KOTAK_NEO" | "GROWW";
  clientId: string | null;
  brokerApp: string | null;
  connection: string | null;
  sessionUntil: string | null;
  enabled: boolean;
  liveCapable: boolean;
  liveEligible: boolean;
  paperTwinId: number | null;
  allocation: number;
  equityMode: string;
  sizeStepPct: number;
  strategies: string[];
  liveStrategies: string[];
  chosen: boolean;
  paper: DayResult;
  live: DayResult;
}

/** GET /api/execution/workspaces/{tenant}/today */
export interface WorkspaceToday {
  tenantId: number;
  name: string;
  control: "ACCOUNT_MODE" | "DAILY_SWITCH";
  role: string;
  day: string;
  members: { username: string; display_name: string; role: string; telegram: boolean; status: string }[];
  state: null | {
    brokerAccountId: number | null; broker: string | null; mode: "PAPER" | "LIVE"; since: string | null;
    targetAccountId: number | null; trading: "OFF" | "PAPER" | "LIVE";
  };
  brokers: WorkspaceBroker[];
  totals: { paperNet: number; liveNet: number; paperOpen: number; liveOpen: number };
  lossLimit: { dailyLossLimit: number | null; liveNetToday: number; reached: boolean };
  killSwitches: { id: number; scope: string; broker_account_id: number | null; what: string | null; reason: string;
    engaged_by: string; engaged_at: string }[];
  support: { id: number; granted_to: string; break_glass: boolean; reason: string | null; granted_at: string;
    expires_at: string }[];
  pending: { id: number; name: string; needs: "BROKER_APP" | "CLIENT_ID" | "LOGIN" }[];
  events: { at: string; broker: string; mode: string; reason: string; by: string }[];
  canSwitch: boolean;
  canStop: boolean;
  canManage: boolean;
  platformAdmin: boolean;
}

/** GET /api/execution/household: one card per family workspace (the platform admin). */
export type HouseholdCard = Pick<WorkspaceToday, "tenantId" | "name" | "control" | "members" | "state" | "lossLimit"
  | "killSwitches" | "support" | "pending"> & { today: WorkspaceToday["totals"] };

export interface BrokerApp {
  id: number;
  name: string;
  broker: "ZERODHA" | "UPSTOX" | "KOTAK_NEO" | "GROWW";
  status: "ACTIVE" | "DISABLED";
  redirect_uri: string | null;
  ips: string | null;
  settings: Record<string, string>;
  key_hint: string | null;
  secret_set_at: string | null;
  secret_set_by: string | null;
  verified_at: string | null;
  last_check_error: string | null;
  keysSet: boolean;
  /** false for the owner's server-set-up apps (env:/file: keys): keys, name and removal are changed on the server */
  managedHere: boolean;
  accounts: string[];
}

/** GET /api/execution/workspaces/{tenant}/broker-apps */
export interface BrokerApps {
  apps: BrokerApp[];
  register: Record<string, { redirectUrl: string | null; staticIps: string[]; liveCapable: boolean }>;
  canManage: boolean;
}

export interface SupportGrant {
  id: number; granted_to: string; granted_by: string; reason: string | null; break_glass: boolean; granted_at: string;
  expires_at: string; revoked_at: string | null; active: boolean;
}

export interface LoginResult { ok: boolean; account: string | null; message: string }

export const BROKER_NAMES: Record<string, string> = {
  PAPER: "Paper", ZERODHA: "Zerodha", UPSTOX: "Upstox", KOTAK_NEO: "Kotak Neo", GROWW: "Groww",
};

/** GET /api/execution/admin/members (ADMIN): each tenant the caller administers, its members and the groups it follows. */
export interface MemberTenant {
  tenantId: number;
  tenant: string;
  members: { username: string; display_name: string; status: string; role: string; relationship?: string | null;
    last_seen?: string | null; telegram?: boolean; invite?: string | null }[];
  accounts: number;
  follows: { id: number; name: string; owner: string }[];
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
  scope: "GLOBAL_LIVE" | "TENANT" | "ACCOUNT" | "STRATEGY" | "INSTRUMENT";
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

// ---------------------------------------------------------------- account management (Accounts page)

/** GET /api/execution/accounts/{id}/details */
export interface AccountDetails {
  id: number;
  name: string;
  broker: string;
  mode: string;
  enabled: boolean;
  allocatedCapital: number;
  equityMode: string;
  equityFrom: string;
  riskFile: string;
  sizeStepPct: number;
  clientId: string | null;
  credentialProfile: string | null;
  /** rupees; null = the risk file's percentage */
  dailyLossLimit: number | null;
  connection: string | null;
  session: { status: string; expires: string } | null;
  strategies: { strategy_id: string; enabled: boolean; size_multiplier: number; copy_group: string }[];
  today: { open: number; closed: number; net: number };
}

/** GET /api/execution/accounts/{id}/positions?day= */
export interface ExecPosition {
  id: number;
  strategy_id: string;
  underlying: string;
  option_side: string;
  symbol: string;
  quantity_open: number;
  average_entry: number;
  opened: string;
  closed: string | null;
  exit_reason: string | null;
  realised_gross: number | null;
  charges: number | null;
  status: string;
}

/** GET /api/execution/accounts/{id}/live-book?days=: a LIVE account's own positions (LIVE intents only), open and closed. */
export interface LiveBook {
  account: string;
  mode: string;
  /** whether the caller may act on the account (exit, book a hand exit); a viewer only reads */
  canAct: boolean;
  /** engaged switches that stop the account's entries, oldest first */
  killSwitches: { id: number; text: string; releasable: boolean }[];
  summary: { openPnl: number; atRisk: number; realisedToday: number; sinceLiveNet: number; sinceLiveTrades: number;
    sinceLiveFrom: string | null };
  open: LiveOpenPosition[];
  closed: LiveClosedPosition[];
}

interface LiveBookRow {
  id: number;
  strategy: string;
  underlying: string;
  side: string;
  symbol: string;
  lotSize: number | null;
  entry: number;
  /** IST, ISO local date-time */
  opened: string;
  quantity: number;
}

export interface LiveOpenPosition extends LiveBookRow {
  status: string;
  /** null when no quote was captured in the last 10 minutes */
  bid: number | null;
  ask?: number;
  ltp?: number | null;
  quoteAt?: string;
  best: number | null;
  stop: number | null;
  stopStatus: string | null;
  stopWorking: boolean;
  pnl?: number;
  pnlPct?: number | null;
  atRisk?: number;
  /**
   * The reconciler found the broker no longer holds it (sold outside the app): the window to price the hand sale from
   * (IST, its stop's cancel or a minute before the kill, to the kill) and the captured bids in it.
   */
  notAtBroker?: { from: string; to: string; bidLow: number | null; bidHigh: number | null; bidFirst: number | null };
}

export interface LiveClosedPosition extends LiveBookRow {
  exit: number | null;
  exitReason: string | null;
  closed: string;
  gross: number;
  charges: number;
  net: number;
  pct: number | null;
}

/** GET /api/execution/accounts/{id}/ledger?days=: the account's own ledger, newest day first. */
export interface LedgerRow {
  day: string;
  openingEquity: number;
  trades: number;
  wins: number;
  open: number;
  gross: number;
  charges: number;
  net: number;
  closingEquity: number;
  brokerNet: number | null;
  difference: number | null;
}

/** GET /api/execution/accounts/{id}/pnl?days= */
export interface PnlDay {
  day: string;
  trades: number;
  wins: number;
  net: number;
}

/** GET /api/execution/credential-profiles: a broker app's settings, never its secrets */
export interface CredentialProfile {
  id: number;
  /** whose app it is: a member's account can only use an app of its own tenant */
  tenant_id: number;
  name: string;
  broker_type: string;
  redirect_uri: string | null;
  ips: string;
  status: string;
}

/** A session's index in 3-minute bars with ma-cross's EMA9 / EMA20 and swings, and each ma-cross trade's stop / target. */
export interface SessionStructure {
  source: "ticks" | "bars";
  candles: [string, number, number, number, number][];
  ema9: [string, number][];
  ema20: [string, number][];
  swings: { t: string; at: string; price: number; high: boolean }[];
  trades: { position: number; side: string; at: string; exit: string | null; entry: number; stop: number | null;
    target: number | null; rewardToRisk: number; rewardToRiskAssumed: boolean }[];
}

/** One traded option's minute bars, entry, exit and best / worst premium while held. */
export interface TradePremium {
  symbol: string;
  error?: string;
  source?: "ticks" | "bars";
  expiry?: string;
  strike?: number;
  side?: string;
  quantity?: number;
  entry?: { t: string; price: number };
  exit?: { t: string; price: number } | null;
  bars?: [string, number, number, number, number][];
  iv?: [string, number][];
  excursion?: { bestPrice?: number; bestAt?: string; mfe?: number; mfeRupees?: number; worstPrice?: number;
    worstAt?: string; mae?: number; maeRupees?: number };
}

/** The day around a session: VIX, the ATM straddle and its implied volatility, gap, expiry, participants, shape. */
export interface DayContext {
  date: string;
  underlying: string;
  vix: [string, number][];
  vixDay: { open?: number; last?: number; high?: number; low?: number; percentile1y?: number | null; prevClose?: number;
    prevDay?: string; historyDays: number };
  index?: { open: number; high: number; low: number; last: number; lastAt: string; prevClose: number | null;
    gapPct: number | null };
  expectedMoveVix?: number | null;
  shape?: { range: number; bodyPct: number; rangeVsExpected: number | null; direction: string; type: string; final: boolean };
  expiry?: { expiry: string; calendarDays: number; expiryDay: boolean };
  /** minute, straddle (points), strike, implied volatility % (null near the expiry close) */
  straddle?: [string, number, number, number | null][];
  participants?: { who: string; asOf: string; indexFutLongPct: number; indexFutLongPctChange: number | null;
    indexCallNet: number; indexPutNet: number }[];
}
