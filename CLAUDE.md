# auto-trade — instructions for Claude Code

- Plan and phase status: `docs/IMPLEMENTATION-PLAN.md`. Phase 0 is data foundation only.
- Own database only: `autotrade-postgres` (127.0.0.1:5500, db `autotrade`). Schema changes go
  through Flyway migrations in `marketdata-store/src/main/resources/db/migration`; never edit an
  applied migration, add a new one.
- Market data for testing/implementation is read directly from zt-tiger-v2 (`127.0.0.1:5490`),
  read-only (`ZtSessionSource`, `bin/autotrade replay`). Never write to it, never change its
  containers, volumes, config or retention, and do not run heavy reads during market hours
  (09:00–15:50 IST weekdays). It keeps only its newest 10 sessions; older ones are on
  `/Volumes/Expansion/zt-tiger-v2-archive`.
- PAPER only. No broker order code runs against a live account without the user's explicit request.
- Threshold files in `config/strategy/` are versioned: a change is a new `vN` file.
- Held-out sessions (`research.session_split`, 22–25 Sep 2026) are read once per hypothesis.
- Build and test: `mvn -q install` (integration tests use Testcontainers and need Docker).
- Commit only when the user asks.
