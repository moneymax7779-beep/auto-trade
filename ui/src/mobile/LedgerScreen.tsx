import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { get, rupees, type ExecPosition, type LedgerRow } from "../api";
import { activeAccounts, Card, ErrorLine, istTime, ModeBadge, signed, tone, useAccounts } from "./common";

type Period = "day" | "week" | "month";

/** Monday of the ISO week of a yyyy-mm-dd day. */
function weekOf(day: string): string {
  const d = new Date(`${day}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() - ((d.getUTCDay() + 6) % 7));
  return d.toISOString().slice(0, 10);
}

const dayLabel = (day: string) => new Date(`${day}T00:00:00Z`).toLocaleDateString("en-IN", { timeZone: "UTC",
  weekday: "short", day: "2-digit", month: "short" });

interface Group { key: string; label: string; net: number; gross: number; charges: number; trades: number; wins: number;
  green: number; red: number; days: LedgerRow[] }

export function groupLedger(rows: LedgerRow[], period: Period): Group[] {
  const groups = new Map<string, Group>();
  for (const r of rows) {
    const key = period === "day" ? r.day : period === "week" ? weekOf(r.day) : r.day.slice(0, 7);
    const label = period === "day" ? dayLabel(r.day) : period === "week" ? `Week of ${dayLabel(key)}`
      : new Date(`${key}-01T00:00:00Z`).toLocaleDateString("en-IN", { timeZone: "UTC", month: "long", year: "numeric" });
    const g = groups.get(key) ?? { key, label, net: 0, gross: 0, charges: 0, trades: 0, wins: 0, green: 0, red: 0, days: [] };
    g.net += r.net; g.gross += r.gross; g.charges += r.charges; g.trades += r.trades; g.wins += r.wins;
    if (r.trades > 0 && r.net > 0) g.green++;
    if (r.trades > 0 && r.net < 0) g.red++;
    g.days.push(r);
    groups.set(key, g);
  }
  return [...groups.values()];
}

/** The ledger as CSV, the numbers exactly as stored (rupees, no grouping). */
export function ledgerCsv(account: string, rows: LedgerRow[]): string {
  const head = "account,day,opening_equity,trades,wins,open,gross,charges,net,closing_equity,broker_net,difference";
  const cell = (v: number | null) => (v == null ? "" : String(Math.round(v * 100) / 100));
  return [head, ...rows.map((r) => [account, r.day, cell(r.openingEquity), r.trades, r.wins, r.open, cell(r.gross),
    cell(r.charges), cell(r.net), cell(r.closingEquity), cell(r.brokerNet), cell(r.difference)].join(","))].join("\n") + "\n";
}

/**
 * Mobile phase 1 Ledger (17 Nov 2026): an account's own ledger (acct.ledger_day via GET .../ledger), by day, week or month,
 * net of charges, with the end-of-day match against the broker's own figures; a day opens to its trades. CSV download.
 */
export function LedgerScreen() {
  const accounts = useAccounts();
  const shown = activeAccounts(accounts.data);
  const [picked, setPicked] = useState<number | null>(null);
  const account = shown.find((a) => a.id === picked) ?? shown[0];
  const [period, setPeriod] = useState<Period>("day");
  const ledger = useQuery({ queryKey: ["ledger", account?.id], enabled: account != null,
    queryFn: () => get<LedgerRow[]>(`/api/execution/accounts/${account!.id}/ledger?days=120`), refetchInterval: 60_000 });
  const [openDay, setOpenDay] = useState<string | null>(null);
  const rows = ledger.data ?? [];
  const groups = groupLedger(rows, period);
  const total = rows.reduce((s, r) => s + r.net, 0);
  const charges = rows.reduce((s, r) => s + r.charges, 0);
  const download = () => {
    const blob = new Blob([ledgerCsv(account!.name, rows)], { type: "text/csv" });
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = `ledger-${account!.name}-${rows[rows.length - 1]?.day ?? ""}-to-${rows[0]?.day ?? ""}.csv`;
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 10_000);
  };
  return (
    <div className="mx-auto flex max-w-xl flex-col gap-3">
      <h1 className="text-xl font-semibold">Ledger</h1>
      <ErrorLine error={accounts.error} />
      {shown.length > 1 && (
        <select aria-label="Account" className="min-h-11 rounded-lg border border-line bg-panel px-3 text-sm"
          value={account?.id ?? ""} onChange={(e) => { setPicked(Number(e.target.value)); setOpenDay(null); }}>
          {shown.map((a) => <option key={a.id} value={a.id}>{a.name} ({a.mode})</option>)}
        </select>
      )}
      <div role="tablist" aria-label="Period" className="grid grid-cols-3 rounded-lg border border-line bg-panel p-1 text-sm">
        {(["day", "week", "month"] as const).map((p) => (
          <button key={p} role="tab" aria-selected={period === p} type="button" onClick={() => { setPeriod(p); setOpenDay(null); }}
            className={`min-h-10 rounded-md capitalize ${period === p ? "bg-sky-700 font-semibold text-white" : "text-muted"}`}>{p}</button>
        ))}
      </div>
      <ErrorLine error={ledger.error} />
      {account && ledger.data && (
        <Card>
          <div className="flex items-center justify-between"><span className="flex items-center gap-2 text-sm">{account.name}
            <ModeBadge mode={account.mode} /></span><span className="text-xs text-muted">last {rows.length} trading days</span></div>
          <div className={`num text-3xl font-semibold ${tone(total)}`}>{signed(total)}</div>
          <div className="text-xs text-muted">{rows.filter((r) => r.trades > 0 && r.net > 0).length} green ·{" "}
            {rows.filter((r) => r.trades > 0 && r.net < 0).length} red · charges {rupees(charges)}</div>
        </Card>
      )}
      {ledger.data && rows.length === 0 && <p className="text-sm text-muted">No ledger days yet for this account.</p>}
      <ul className="flex flex-col divide-y divide-line rounded-xl border border-line bg-panel">
        {groups.map((g) => (
          <li key={g.key}>
            <button type="button" className="flex min-h-14 w-full items-center justify-between gap-2 px-3 py-2 text-left"
              aria-expanded={period === "day" ? openDay === g.key : undefined}
              onClick={() => period === "day" && setOpenDay(openDay === g.key ? null : g.key)}>
              <span className="min-w-0"><span className="block font-semibold">{g.label}</span>
                <span className="text-xs text-muted">{g.trades} trade{g.trades === 1 ? "" : "s"} · charges {rupees(g.charges)}
                  {period !== "day" && ` · ${g.green} green / ${g.red} red`}
                  {period === "day" && <Match row={g.days[0]} />}</span></span>
              <span className={`num shrink-0 font-semibold ${tone(g.net)}`}>{signed(g.net)}</span>
            </button>
            {period === "day" && openDay === g.key && account && <DayTrades accountId={account.id} day={g.key} />}
          </li>
        ))}
      </ul>
      {rows.length > 0 && (
        <button type="button" onClick={download} className="min-h-11 rounded-lg border border-line bg-panel text-sm font-semibold">
          Download CSV</button>
      )}
    </div>
  );
}

/** The end-of-day match with the broker's own figures: matched, the difference, or not checked (paper / not yet). */
function Match({ row }: { row: LedgerRow }) {
  if (row.difference == null) return null;
  return Math.abs(row.difference) < 1
    ? <span className="text-up"> · matches broker</span>
    : <span className="font-semibold text-warn"> · broker differs {signed(row.difference)}</span>;
}

function DayTrades({ accountId, day }: { accountId: number; day: string }) {
  const trades = useQuery({ queryKey: ["positions-day", accountId, day],
    queryFn: () => get<ExecPosition[]>(`/api/execution/accounts/${accountId}/positions?day=${day}`) });
  return (
    <div className="border-t border-line bg-panel-2/60 px-3 py-2">
      <ErrorLine error={trades.error} />
      {trades.data?.length === 0 && <p className="text-sm text-muted">No trades.</p>}
      <ul className="flex flex-col gap-2 text-sm">
        {trades.data?.map((t) => {
          const net = t.realised_gross == null ? null : t.realised_gross - (t.charges ?? 0);
          return (
            <li key={t.id} className="flex items-start justify-between gap-2">
              <span className="min-w-0"><span className="block truncate">{t.symbol}</span>
                <span className="text-xs text-muted">{t.strategy_id} · {istTime(t.opened)}–{istTime(t.closed)} · {t.exit_reason ?? t.status}</span></span>
              <span className={`num shrink-0 ${tone(net)}`}>{net == null ? "open" : signed(net)}</span>
            </li>
          );
        })}
      </ul>
    </div>
  );
}
