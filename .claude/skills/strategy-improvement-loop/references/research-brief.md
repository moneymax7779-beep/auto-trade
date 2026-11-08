# Research brief for the Fable 5.1 subagent (step 3)

Fill the `{…}` fields and pass the whole text as the Agent prompt with `model: "fable"`. The subagent starts with no
context, so everything it needs is either in this text or a path it can read. Keep the brief factual: give it the data
and the history, not your own hypothesis (an agent handed a hypothesis tends to confirm it).

---

You are a senior options-microstructure researcher reviewing one intraday index-options strategy of the auto-trade
system (Indian index options, NIFTY and SENSEX, buying ATM options; Rs 5 L paper account, risk file v17). Your job:
study every trade of its live and shadow versions and propose **at most {max_ideas} concrete, testable changes** that
could make the live version earn more without cutting the trades that carry it. Read only; do not edit any file, do
not run replays, do not touch Docker or any database.

**Strategy**: `{strategy_id}`. Live version `{live_label}`, shadows {shadow_labels}.
- Code: `{repo}/strategy-egb/src/main/java/com/autotrade/strategy/egb/{ClassName}.java` (+ its `*Config.java` for the
  YAML keys that exist; a change that needs a new key is allowed but say so).
- Configs: `{repo}/config/strategy/{strategy_id}.v*.yaml` (each version's header says what it changed and why).

**Data (all own tick capture, {n_days} days {first_day} .. {last_day}, each version replayed alone)**:
- Summary table (totals, halves {h1} / {h2}, without-best-day, per-day nets): `{run_dir}/summary.txt`
- Every trade (qty, entry/exit time and price, exit reason, net): `{run_dir}/trades.tsv`
- Screenshots: `{shots_dir}/<label>/` — one full-page image per session day, and in `trades/` one image at each trade's
  entry and one at its exit (file name: `NNN_<day>_s<session>-#k_<INDEX>-<CE|PE>_<1-entry|2-exit>_<HHMM>_<reason>_net<±net>.png`;
  `trades/index.tsv` lists them). Each image is the whole session page pinned at that minute: day facts, the index chart
  with the trade marked, the option price/IV, OI panels and strike ladder at that minute, then the Trades table, the
  stage changes (entry reason, the conditions that were true) and every order. Look at the images: read the charts, the
  OI and the stage details. Start with the biggest losers and biggest winners of the live version, then the rest.

**History — do not re-propose what already failed** (ledger `{repo}/docs/EXPERIMENT-LEDGER.md`, read these rows):
{ledger_ids_and_one_line_results}
Also read the gates and "Rules:" at the top of the ledger.

**What to deliver** (as your final message, plain text, no file writes):
1. Loss anatomy of the live version: where the money is lost (exit reasons, times, entry context), with trade numbers
   from `index.tsv` as evidence. Same for the winners: what the big winners have in common.
2. For each proposal (most promising first):
   - name, one-line idea, and the mechanism (why it should work in this market, not just on these days);
   - the exact change: YAML keys and values on top of `{live_label}`, or the code change (class, method, condition);
     exactly ONE change per proposal so its effect can be measured;
   - which trades in `index.tsv` it would have changed and how (cite numbers) — and which winners it puts at risk;
   - how it could be overfit to these {n_days} days, and what would show that.
3. Proposals you considered and rejected, with the reason.
Be honest about thin evidence: a rule that rests on one or two trades is a hypothesis, say so.
