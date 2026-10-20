import { useQuery } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import { OiTapePanel, type TapeInsets } from "../components/OiTapePanel";
import { useChartView } from "../components/chartView";
import { LEVEL_COLORS, LevelTogglesBar, levelColor, mergeLevels, useLevels, useLevelToggles, visibleLevels, visibleZones } from "../components/levels";
import { Link, useParams, useSearchParams } from "react-router";
import { fixed, get, type DecisionRow, type OrderRow, type SessionRow, type SessionRunRow, type TradePositionRow } from "../api";
import { TimelineChart } from "../components/TimelineChart";
import { ErrorNote, Loading, Panel, Pnl, StageBadge, Table } from "../components/ui";
import { chartTrades, TradesTable } from "../components/TradesTable";
import { compactRupees } from "../components/TimelineChart";
import { STRATEGY_INFO, strategyShort } from "../components/strategies";

/** Background tints for runs, alternating; translucent so they read in light and dark themes. */
const RUN_TINTS = ["rgba(59,130,246,0.10)", "rgba(245,158,11,0.12)", "rgba(16,185,129,0.10)", "rgba(168,85,247,0.10)",
  "rgba(236,72,153,0.09)", "rgba(20,184,166,0.11)"];
const RUN_DOTS = ["#3b82f6", "#f59e0b", "#10b981", "#a855f7", "#ec4899", "#14b8a6"];

/** Where a session's data came from, in words. */
function feedLabel(feed: string): string {
  if (feed.includes("bars")) return "Upstox one-minute bars (coarse)";
  if (feed.includes("own-capture")) return "own tick capture";
  if (feed.includes("zt-tiger")) return "zt-tiger-v2 tick capture";
  return feed;
}

interface Run { index: number; first: number; size: number; from: string; to: string; net: number; started: number; feeds: Set<string> }

/** The data a session ran on: one-minute bars, or ticks (live, zt or own capture). A batch's tick feeds are one run. */
const dataKind = (feed: string) => (feed.includes("bars") ? "bars" : "ticks");

/** The session's config files, sorted ("expiry-trend-rider.v7.yaml,…,paper-risk.v12.yaml"). */
const configKey = (r: SessionRow) => Object.keys(r.configs ?? {}).sort().join(",");

/** The risk file's version ("risk v13") and each strategy file as "ODB v4"; {@code all} = every config file. */
function configTags(r: Pick<SessionRow, "configs">): { risk: string | null; strategies: string[]; all: string[] } {
  const files = Object.keys(r.configs ?? {}).sort().map((f) => f.replace(/\.yaml$/, ""));
  const risk = files.find((f) => f.startsWith("paper-risk."));
  const strategies = files
    .filter((f) => !/^(paper-risk|features|nse-bse|india-index)/.test(f))
    .map((f) => { const [name, ver] = f.split(/\.(?=v\d+$)/); return `${strategyShort(name)} ${ver ?? ""}`.trim(); });
  return { risk: risk ? `risk ${risk.replace("paper-risk.", "")}` : null, strategies, all: files };
}

/**
 * Groups the sessions (newest first) into runs: sessions with the same mode, data (bars or ticks: a batch's zt days and
 * own-capture days are one run), code and strategy set, started within
 * 30 minutes of each other (a batch of replays), or the live sessions of one code version. Runs started at the same time
 * interleave their session ids (e.g. one replay per risk file and day), so a session joins the newest matching run
 * whether or not it is next to it in the list. Returns the rows reordered run by run, and each row's run.
 */
function groupRuns(sessions: SessionRow[]): { rows: SessionRow[]; runOf: Run[] } {
  // the config files (risk, features, every strategy version) are part of a run's identity: two runs of the same days
  // with different settings are different runs
  const key = (r: SessionRow) => `${r.mode}|${dataKind(r.feed)}|${r.code_version}|${r.strategy_id}|${configKey(r)}`;
  const minutes = (s?: string | null) => (s ? new Date(s.replace(" ", "T")).getTime() / 60000 : NaN);
  const groups: { run: Run; rows: SessionRow[]; dates: Set<string> }[] = [];
  const byKey = new Map<string, typeof groups>();
  for (const r of sessions) {
    const k = key(r), started = minutes(r.started_at);
    const candidates = byKey.get(k) ?? [];
    // a replay run never holds the same day twice: a repeated date goes to another run
    let g = r.mode === "PAPER_LIVE"
      ? candidates[candidates.length - 1]
      : candidates.find((c) => Math.abs(c.run.started - started) <= 30 && !c.dates.has(r.session_date));
    if (!g) {
      g = { run: { index: groups.length, first: 0, size: 0, from: r.session_date, to: r.session_date, net: 0, started,
        feeds: new Set<string>() },
        rows: [], dates: new Set<string>() };
      groups.push(g);
      byKey.set(k, [...candidates, g]);
    }
    g.rows.push(r);
    g.dates.add(r.session_date);
    const run = g.run;
    run.size++;
    run.feeds.add(feedLabel(r.feed));
    run.started = started;   // the run's oldest start so far: the next (older) session must be within 30 minutes of it
    if (r.session_date < run.from) run.from = r.session_date;
    if (r.session_date > run.to) run.to = r.session_date;
    run.net += Number(r.summary?.net ?? 0);
  }
  const rows: SessionRow[] = [], runOf: Run[] = [];
  for (const g of groups) {
    g.run.first = rows.length;
    for (const r of g.rows) { rows.push(r); runOf.push(g.run); }
  }
  return { rows, runOf };
}

/** A run's dropdown value: everything the sessions filter needs. */
const runValue = (r: SessionRunRow) => [r.mode, r.code_version, r.strategy_id, r.config_key, r.data].join("|");

/** "zq3 · QP v4 · risk v17 · ticks · 27 days 2026-09-02 – 2026-10-16 · +₹9.62L" */
function runLabel(r: SessionRunRow): string {
  const tags = configTags(r);
  const what = r.mode === "PAPER_LIVE" ? "Live PAPER" : r.data === "bars" ? "bars" : "ticks";
  const days = r.date_from === r.date_to ? r.date_from : `${r.date_from} – ${r.date_to}`;
  return [r.code_version, tags.strategies.join(" + ") || r.strategy_id, tags.risk, what,
    `${r.days} day${r.days === 1 ? "" : "s"} ${days}`, compactRupees(Number(r.net))].filter(Boolean).join(" · ");
}

/** The strategy dropdown's label for a strategy set ("Quiet positioning", or "6 strategies: ETR + MAC + …"). */
function strategyLabel(id: string): string {
  const ids = id.split("+").filter(Boolean);
  if (ids.length === 1) return STRATEGY_INFO[id]?.name ?? id;
  return `${ids.length} strategies: ${ids.map(strategyShort).join(" + ")}`;
}

export function SessionsPage() {
  const [params, setParams] = useSearchParams();
  const pickedStrategy = params.get("strategy") ?? "";
  // a run value has exactly five parts (mode|code|strategy|config key|data); anything else in the URL is ignored
  const runParts = (params.get("run") ?? "").split("|");
  const pickedRun = runParts.length === 5 && runParts.every(Boolean) ? runParts.join("|") : "";
  const runList = useQuery({ queryKey: ["session-runs"], queryFn: () => get<SessionRunRow[]>("/api/sessions/runs") });
  const path = useMemo(() => {
    if (pickedRun) {
      const [mode, code, strategy, configs, data] = pickedRun.split("|");
      return `/api/sessions?${new URLSearchParams({ mode, code, strategy, configs, data })}`;
    }
    return pickedStrategy ? `/api/sessions?${new URLSearchParams({ strategy: pickedStrategy })}` : "/api/sessions";
  }, [pickedRun, pickedStrategy]);
  // the unfiltered list shares its cache with the Live, Index and Performance pages (same key, same request)
  const sessions = useQuery({ queryKey: path === "/api/sessions" ? ["sessions"] : ["sessions", path],
    queryFn: () => get<SessionRow[]>(path) });
  const { rows, runOf: runs } = useMemo(() => groupRuns(sessions.data ?? []), [sessions.data]);
  const strategies = useMemo(() => {
    const count = new Map<string, number>();
    for (const r of runList.data ?? []) count.set(r.strategy_id, (count.get(r.strategy_id) ?? 0) + 1);
    return [...count].sort((a, b) => strategyLabel(a[0]).localeCompare(strategyLabel(b[0])));
  }, [runList.data]);
  const runChoices = (runList.data ?? []).filter((r) => !pickedStrategy || r.strategy_id === pickedStrategy);
  // a run picked from a bookmark that is no longer among the newest runs still shows as selected
  const pickedMissing = pickedRun !== "" && runList.isSuccess && !runChoices.some((r) => runValue(r) === pickedRun);
  const pick = (strategy: string, run: string) => {
    const next = new URLSearchParams();
    if (strategy) next.set("strategy", strategy);
    if (run) next.set("run", run);
    setParams(next);
  };
  const filters = (
    <div className="flex flex-wrap items-center gap-2 text-sm">
      <select aria-label="Strategy" value={pickedStrategy} onChange={(e) => pick(e.target.value, "")}
        className="max-w-72 rounded border border-line bg-panel px-2 py-1">
        <option value="">All strategies</option>
        {strategies.map(([id, n]) => <option key={id} value={id}>{strategyLabel(id)} ({n} run{n === 1 ? "" : "s"})</option>)}
      </select>
      <select aria-label="Run" value={pickedRun} onChange={(e) => pick(pickedStrategy, e.target.value)}
        className="max-w-[36rem] rounded border border-line bg-panel px-2 py-1">
        <option value="">{pickedStrategy ? "All runs of this strategy" : "Newest sessions (all runs)"}</option>
        {runList.isError && <option value="" disabled>Run list unavailable</option>}
        {pickedMissing && <option value={pickedRun}>{`${pickedRun.split("|")[1]} · run from the link (not in the run list)`}</option>}
        {runChoices.map((r) => <option key={runValue(r)} value={runValue(r)}>{runLabel(r)}</option>)}
      </select>
      {(pickedRun || pickedStrategy) && (
        <button type="button" onClick={() => pick("", "")} className="rounded border border-line px-2 py-1 text-muted hover:text-text">
          Clear
        </button>
      )}
    </div>
  );
  if (sessions.isLoading) return <Panel title="Trading sessions (PAPER)" right={filters}><Loading what="sessions" /></Panel>;
  // keep the filters on an error, so a bad filter can be changed or cleared
  if (sessions.error) return <Panel title="Trading sessions (PAPER)" right={filters}><ErrorNote error={sessions.error} /></Panel>;
  return (
    <Panel title="Trading sessions (PAPER)" right={filters}>
      <p className="mb-2 text-xs text-muted">
        Each colour band is one run: a batch of replays started together (same data, code and strategies; a batch's zt and
        own-capture tick days are one run), or the live sessions of one code version. The line above each band says what the
        run was. {pickedRun || pickedStrategy ? "Filtered: every matching session (at most 2,000)." :
          "Without a filter: the live sessions and the newest 200; pick a strategy or run to see older ones."}
      </p>
      <Table
        rows={rows}
        rowStyle={(_, i) => ({ background: RUN_TINTS[runs[i].index % RUN_TINTS.length] })}
        before={(r, i) => {
          const run = runs[i];
          if (run.first !== i) return null;
          const n = r.strategy_id.split("+").filter(Boolean).length;
          return (
            <div className="flex flex-wrap items-center gap-x-2 text-xs">
              <span className="inline-block h-2.5 w-2.5 rounded-full" style={{ background: RUN_DOTS[run.index % RUN_DOTS.length] }} />
              <span className="font-semibold">{r.mode === "PAPER_LIVE" ? "Live PAPER sessions" : "Replay run"}</span>
              <span className="text-muted">· {run.size} session{run.size === 1 ? "" : "s"} · {run.from === run.to ? run.from : `${run.from} – ${run.to}`}</span>
              <span className="text-muted">· {[...run.feeds].join(" + ")}</span>
              <span className="text-muted" title={configTags(r).all.join("\n")}>
                · {n > 1 ? `${n} strategies` : STRATEGY_INFO[r.strategy_id]?.name ?? r.strategy_id}
                {" "}({configTags(r).strategies.join(" · ")})
                {configTags(r).risk && <> · <span className="font-semibold">{configTags(r).risk}</span></>}
              </span>
              <span className="num text-muted">· code {r.code_version}</span>
              <span className="text-muted">· net</span> <Pnl value={run.net} />
            </div>
          );
        }}
        empty="No sessions yet. Run bin/trading-core --mode=replay --session=YYYY-MM-DD or --mode=live."
        columns={[
          { key: "id", label: "#", render: (r) => <Link className="text-accent underline" to={`/sessions/${r.id}`}>{r.id}</Link> },
          { key: "d", label: "Date", render: (r) => r.session_date },
          { key: "m", label: "Mode", render: (r) => r.mode },
          { key: "f", label: "Feed", render: (r) => r.feed },
          { key: "st", label: "Status", render: (r) => r.status },
          { key: "p", label: "Positions", align: "right", render: (r) => r.summary?.positions ?? "–" },
          { key: "w", label: "Wins", align: "right", render: (r) => r.summary?.wins ?? "–" },
          { key: "n", label: "Net", align: "right", render: (r) => <Pnl value={r.summary?.net} /> },
          { key: "r", label: "Reconciled", render: (r) => (r.summary?.reconciled == null ? "–" : r.summary.reconciled ? "yes" : "NO") },
          { key: "strat", label: "Strategy", render: (r) => <StrategyChips ids={r.strategy_id} configs={r.configs} /> },
          { key: "c", label: "Code", render: (r) => <span className="num text-xs">{r.code_version}</span> },
          { key: "s", label: "Started", render: (r) => r.started_at },
        ]}
      />
    </Panel>
  );
}

const WHOLE_DAY = { a: 9 * 60 + 15, b: 15 * 60 + 30 };

export function SessionDetailPage() {
  const { id } = useParams();
  const [underlying, setUnderlying] = useState("NIFTY");
  const [chosenStrategy, setChosenStrategy] = useState<string | null>(null);
  const sessions = useQuery({ queryKey: ["sessions"], queryFn: () => get<SessionRow[]>("/api/sessions") });
  const sessionRow = sessions.data?.find((s) => String(s.id) === id);
  // A session may run several strategies ("a+b"); the charts show one at a time.
  const strategyIds = (sessionRow?.strategy_id ?? "").split("+").filter(Boolean);
  const strategy = chosenStrategy ?? strategyIds[0] ?? null;
  const decisions = useQuery({
    queryKey: ["decisions", id, underlying, strategy],
    enabled: strategy !== null,
    queryFn: () => get<DecisionRow[]>(`/api/sessions/${id}/decisions?underlying=${underlying}`
      + `&strategy=${encodeURIComponent(strategy ?? "")}`),
  });
  const orders = useQuery({ queryKey: ["orders", id], queryFn: () => get<OrderRow[]>(`/api/sessions/${id}/orders`) });
  const positions = useQuery({
    queryKey: ["positions", id],
    queryFn: () => get<TradePositionRow[]>(`/api/sessions/${id}/positions`),
  });
  const session = sessionRow;
  const rows = decisions.data ?? [];

  const levels = useLevels(session?.session_date, underlying);
  const [toggles, setToggles] = useLevelToggles();
  const price = useMemo(() => {
    const main = { name: underlying, color: "--color-text", points: rows.map((r) => ({ t: r.t, value: r.spot })) };
    const lv = levels.data;
    const spots = rows.map((r) => r.spot).filter((v): v is number => v != null);
    if (!lv || spots.length === 0) return [main];
    const lo = Math.min(...spots), hi = Math.max(...spots), near = (hi - lo) * 0.15;
    const inRange = (v: number) => v >= lo - near && v <= hi + near;
    const end = rows[rows.length - 1].t;
    // every level starts at the minute it was knowable (no hindsight lines)
    const shownLevels = mergeLevels(visibleLevels(lv.levels, toggles), lv.tolerancePct).filter((l) => inRange(l.price) && l.from <= end);
    // only the nearest two above and below the last price get an axis tag; a tag for every level piles up
    const last = spots[spots.length - 1];
    // the key levels (PDH, PDL, ORH, ORL and their prior-day versions) always carry their name; of the rest only the
    // nearest two above and below do
    const key = /\b(PDH|PDL|ORH|ORL)\b/;
    const tagged = new Set([
      ...shownLevels.filter((l) => key.test(l.name)),
      ...shownLevels.filter((l) => l.price >= last).sort((p, q) => p.price - q.price).slice(0, 2),
      ...shownLevels.filter((l) => l.price < last).sort((p, q) => q.price - p.price).slice(0, 2),
    ]);
    const flat = shownLevels
      .map((l) => ({ name: tagged.has(l) ? l.name : "", color: levelColor(l.group), width: 1 as const,
        dashed: true, label: tagged.has(l), points: [{ t: l.from, value: l.price }, { t: end, value: l.price }] }));
    const zones = visibleZones(lv.zones, toggles).filter((z) => inRange(z.lo) && z.from <= end).flatMap((z) => {
      const color = z.kind === "support" ? LEVEL_COLORS.support : LEVEL_COLORS.resistance;
      const until = z.until && z.until < end ? z.until : end;
      const name = `${z.kind === "support" ? "S" : "R"} ${z.touches}×`;
      return [z.lo, z.hi].map((v, i) => ({ name: i === 0 ? name : "", color, width: 1 as const, dashed: true,
        points: [{ t: z.from, value: v }, { t: until, value: v }] }));
    });
    const lines = toggles.lines ? [
      { name: "VWAP", color: LEVEL_COLORS.vwap, width: 1 as const, points: lv.lines.vwap.filter(([t]) => t <= end).map(([t, v]) => ({ t, value: v })) },
      { name: "EMA20", color: LEVEL_COLORS.ema20, width: 1 as const, points: lv.lines.ema20.filter(([t]) => t <= end).map(([t, v]) => ({ t, value: v })) },
    ] : [];
    return [main, ...zones, ...flat, ...lines];
  }, [rows, underlying, levels.data, toggles]);
  // Stored scores use −1 for "not computed" (e.g. the straddle has none): a gap, not a value.
  const score = (v: number | undefined) => (v == null || v < 0 ? null : v);
  const scores = useMemo(() => [
    { name: "CE confirm", color: "#22c55e", points: rows.map((r) => ({ t: r.t, value: score(r.ce_scores?.confirm) })) },
    { name: "PE confirm", color: "#ef4444", points: rows.map((r) => ({ t: r.t, value: score(r.pe_scores?.confirm) })) },
    { name: "CE runner", color: "#86efac", width: 1, dashed: true, points: rows.map((r) => ({ t: r.t, value: score(r.ce_scores?.runner) })) },
    { name: "PE runner", color: "#fca5a5", width: 1, dashed: true, points: rows.map((r) => ({ t: r.t, value: score(r.pe_scores?.runner) })) },
  ], [rows]);
  const hasScores = scores.some((line) => line.points.some((p) => p.value != null));
  // Entries and exits from the filled positions (what was really bought and sold, including the executor's stops and
  // a straddle's combined target or stop); by default every strategy's trades on this index, labelled by strategy.
  // futures volume per minute (and its normal for that minute) from the surge report of the session's date
  const surgeData = useQuery({
    queryKey: ["surges", session?.session_date ?? "none"],
    enabled: !!session?.session_date,
    queryFn: () => get<{ underlyings: { underlying: string; volume?: [string, number, number | null][] }[] }>(
      `/api/surges?date=${session!.session_date}`),
  });
  const volume = useMemo(() => {
    const u = surgeData.data?.underlyings.find((x) => x.underlying === underlying);
    return (u?.volume ?? []).map(([t, v, normal]) => ({
      t, value: v, color: normal && v >= 5 * normal ? "rgba(245,158,11,0.9)" : "rgba(148,163,184,0.45)",
    }));
  }, [surgeData.data, underlying]);
  const [markerScope, setMarkerScope] = useState<"all" | "selected">("all");
  const [focus, setFocus] = useState<{ from: string; to: string } | null>(null);
  // one time window for the price chart and the OI panels under it (zoom either; the other follows)
  const [view, setView] = useChartView(session?.session_date ?? "", underlying, WHOLE_DAY);
  const [axisWidth, setAxisWidth] = useState(64);
  const tapeInsets = useMemo<TapeInsets>(() => () => ({ L: 0, R: axisWidth }), [axisWidth]);
  const wholeDay = () => { setFocus(null); setView(WHOLE_DAY); };
  const spotAt = useMemo(() => {
    const byMinute = new Map(rows.map((r) => [r.t, r.spot ?? null]));
    return (t: string) => byMinute.get(t) ?? null;
  }, [rows]);
  const trades = useMemo(() => chartTrades(positions.data ?? [], underlying,
    markerScope === "all" || strategyIds.length <= 1 ? null : strategy, strategyShort, spotAt),
  [positions.data, underlying, strategy, markerScope, strategyIds.length, spotAt]);
  const pickIndex = (u: string) => { setUnderlying(u); wholeDay(); };
  const changes = useMemo(() => rows.filter((r, i) => i === 0 || r.ce_stage !== rows[i - 1].ce_stage || r.pe_stage !== rows[i - 1].pe_stage), [rows]);

  return (
    <div className="space-y-4">
      <Panel
        title={<>Session {id} {session && <>· {session.session_date} · {session.mode}
          {strategyIds.length === 1 && <> · {STRATEGY_INFO[strategyIds[0]]?.name ?? strategyIds[0]}</>}
          {strategyIds.length > 1 && <> · {strategyIds.length} strategies</>}
          {" "}· net <Pnl value={session.summary?.net} /></>}</>}
        right={
          <div className="flex flex-wrap gap-1">
            {strategyIds.length > 1 && strategyIds.map((sid) => (
              <button key={sid} onClick={() => setChosenStrategy(sid)}
                className={`rounded px-3 py-1 text-xs ${sid === strategy ? "bg-accent text-black" : "border border-line"}`}>{sid}</button>
            ))}
            {["NIFTY", "BANKNIFTY", "SENSEX"].map((u) => (
              <button key={u} onClick={() => pickIndex(u)}
                className={`rounded px-3 py-1 text-sm ${u === underlying ? "bg-accent text-black" : "border border-line"}`}>{u}</button>
            ))}
          </div>
        }
      >
        {decisions.isLoading ? <Loading what="decisions" /> : decisions.error ? <ErrorNote error={decisions.error} /> :
          rows.length === 0 ? <p className="text-sm text-muted">No decisions for {underlying}.</p> : (
            <>
              <div className="mb-2 flex flex-wrap items-center gap-3">
                <LevelTogglesBar toggles={toggles} onChange={setToggles} />
                {strategyIds.length > 1 && (
                  <div className="flex items-center gap-1 text-xs" role="group" aria-label="Trades on the chart">
                    <span className="text-muted">trades on chart</span>
                    {(["all", "selected"] as const).map((s) => (
                      <button key={s} type="button" onClick={() => setMarkerScope(s)}
                        className={`rounded-full border px-2.5 py-0.5 ${markerScope === s ? "border-accent bg-accent/10 font-semibold" : "border-line text-muted"}`}>
                        {s === "all" ? "all strategies" : strategyShort(strategy ?? "")}
                      </button>
                    ))}
                  </div>
                )}
              </div>
              <div id="session-chart">
                <TimelineChart date={session?.session_date ?? "2026-01-01"} lines={price} trades={trades} volume={volume}
                  focus={focus} height={420} view={view} onView={setView} onLayout={setAxisWidth} momentsOf={underlying} />
              </div>
              <div className="mt-2 flex flex-wrap items-center gap-1.5 text-xs">
                <span className="text-muted">
                  <span style={{ color: "#16a34a" }}>▲</span> call bought · <span style={{ color: "#dc2626" }}>▼</span> put bought ·
                  <span style={{ color: "#2563eb" }}> ●</span> straddle · ■ exit (green profit, red loss) · thick line = the index
                  from entry to exit ·{" "}
                  {volume.length > 0
                    ? <>bars = futures volume (<span style={{ color: "#f59e0b" }}>amber</span> ≥ 5× that minute's normal) · </>
                    : <>no futures volume on this server for {session?.session_date} · </>}
                  click a trade to zoom:
                </span>
                {trades.length === 0 && <span className="text-muted">no trades on {underlying}</span>}
                {trades.map((tr) => {
                  const active = focus != null && focus.from === tr.from && focus.to === (tr.to ?? tr.from);
                  return (
                    <button key={tr.n} type="button" onClick={() => setFocus({ from: tr.from, to: tr.to ?? tr.from })}
                      className={`rounded border px-2 py-0.5 ${active ? "border-accent bg-accent/10" : "border-line"}`}>
                      <span className="font-semibold">#{tr.n} {tr.who}</span>{" "}
                      <span className="text-muted">{tr.side === "STRADDLE" ? "straddle" : tr.side} {tr.from}→{tr.to ?? "open"}</span>{" "}
                      <span className="num font-semibold" style={{ color: tr.net >= 0 ? "#16a34a" : "#dc2626" }}>{compactRupees(tr.net)}</span>
                    </button>
                  );
                })}
                {focus && (
                  <button type="button" onClick={wholeDay} className="rounded border border-accent px-2 py-0.5 text-accent">whole day</button>
                )}
              </div>
              {session && (
                <OiTapePanel bare underlying={underlying} date={session.session_date} insets={tapeInsets}
                  title={`${underlying} · OI under the chart · this session's trades`}
                  trades={(positions.data ?? []).filter((p) => p.underlying === underlying)
                    .map((p) => ({ label: `${p.strategy_id ?? ""} ${p.option_side}`, opened: p.opened_at, closed: p.closed_at, net: p.net }))} />
              )}
              {hasScores ? (
                <>
                  <div className="mt-3 text-xs text-muted">Confirm and runner scores (0–100) over the day</div>
                  <TimelineChart date={session?.session_date ?? "2026-01-01"} lines={scores} height={180} view={view} onView={setView} />
                </>
              ) : (
                <p className="mt-3 text-xs text-muted">{strategy} has no confirm or runner scores; its conditions are in the stage changes below.</p>
              )}
            </>
          )}
      </Panel>

      <Panel title="Trades">
        <TradesTable rows={positions.data ?? []} showStrategy={strategyIds.length > 1} empty="No trades." numbered
          onPick={(t) => {
            setUnderlying(t.underlying);
            setFocus({ from: t.opened.slice(11, 16), to: (t.closed ?? t.opened).slice(11, 16) });
            document.getElementById("session-chart")?.scrollIntoView({ behavior: "smooth", block: "center" });
          }} />
      </Panel>

      <Panel title="Stage changes">
        <Table rows={changes} columns={[
          { key: "t", label: "Time", render: (r) => r.t },
          { key: "s", label: "Spot", align: "right", render: (r) => fixed(r.spot) },
          { key: "ce", label: "CE", render: (r) => <StageBadge stage={r.ce_stage} /> },
          { key: "pe", label: "PE", render: (r) => <StageBadge stage={r.pe_stage} /> },
          { key: "o", label: "Orders", render: (r) => <span className="num text-xs">{r.orders ?? ""}</span> },
        ]} />
      </Panel>


      <Panel title="Orders (last state of each)">
        <Table rows={orders.data ?? []} empty="No orders." columns={[
          { key: "id", label: "Client id", render: (r) => <span className="num text-xs">{r.client_order_id}</span> },
          { key: "r", label: "Role", render: (r) => r.role },
          { key: "sd", label: "Side", render: (r) => r.order_side },
          { key: "ty", label: "Type", render: (r) => r.order_type },
          { key: "sy", label: "Contract", render: (r) => r.symbol },
          { key: "q", label: "Qty", align: "right", render: (r) => r.quantity },
          { key: "l", label: "Limit", align: "right", render: (r) => fixed(r.limit_price) },
          { key: "tr", label: "Trigger", align: "right", render: (r) => fixed(r.trigger_price) },
          { key: "sn", label: "Sent", render: (r) => r.sent_at?.slice(11) },
          { key: "st", label: "Status", render: (r) => r.status ?? "–" },
          { key: "f", label: "Filled", align: "right", render: (r) => r.filled ?? 0 },
          { key: "a", label: "Avg", align: "right", render: (r) => fixed(r.average_price) },
        ]} />
      </Panel>
    </div>
  );
}

/** The strategy a session ran, by full name with its config version; several strategies as short codes (full names on hover). */
function StrategyChips({ ids, configs }: { ids: string; configs?: Record<string, string> }) {
  const list = (ids ?? "").split("+").filter(Boolean);
  if (list.length === 0) return <span className="text-muted">–</span>;
  if (list.length === 1) {
    const id = list[0];
    return (
      <span className="text-xs" title={id}>
        <span className="font-semibold">{STRATEGY_INFO[id]?.name ?? id}</span>
        <span className="num text-muted"> · {configVersion(id, configs) ?? id}</span>
      </span>
    );
  }
  return (
    <span className="text-xs" title={list.map((id) => `${STRATEGY_INFO[id]?.name ?? id} (${configVersion(id, configs) ?? id})`).join("\n")}>
      {list.map((id, i) => <span key={id}>{i > 0 && <span className="text-muted"> · </span>}{strategyShort(id)}</span>)}
    </span>
  );
}

/** "ma-cross.v1" from the session's config file names; undefined when the session did not record the file. */
function configVersion(strategyId: string, configs?: Record<string, string>): string | undefined {
  const file = Object.keys(configs ?? {}).find((f) => f.startsWith(strategyId + ".v"));
  return file?.replace(/\.yaml$/, "");
}

