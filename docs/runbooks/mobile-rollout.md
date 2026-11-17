# Mobile rollout and real-phone checklist (mobile phase 5)

Plan: `docs/plans/2026-11-17-mobile-app-plan.html`. Branch `feature/mobile-phase0` (phases 0-5). Every step that
deploys, changes the VM or turns something on is the user's decision, after market close (deploy-exec refuses
08:50-15:50 IST). Desktop pages are unchanged above 768 px; nothing below changes how anything trades.

## Before the first deploy

1. Merge: main has moved (Telegram position alerts). The merge conflicts in `TelegramNotifier.java` only (keep main's
   `sendTo` and this branch's `sendToUser(…, alsoPush)`); the position alerts also go to phones (`PositionAlerts.webPush`).
   Full `mvn -q install` on the merged tree, then `npm run test:mobile` (needs `npx vite --port 5174` and trading-core on
   8095; the PWA test needs `npm run build && npx vite preview`).
2. Schema: V47 (push tables) only; the deploy script's `SCHEMA` becomes 47.
3. Push stays off (`AUTOTRADE_EXECUTION_PUSH_ENABLED` unset); domain and basic-auth gate unchanged until decided
   (`mobile-front-door.md`).

## Rollout

| Step | Who | What | Moves on when |
|---|---|---|---|
| 1 | Owner, 1 week | Phone on the GCP site (two sign-ins while the gate stays): Home checklist, Positions, Ledger, Strategies read-only | No wrong number vs the desktop for 5 market days; client error log quiet |
| 2 | Owner | Exits from the phone on PAPER only (PAPER-40 or a paper twin) | 10 phone exits match the ledger, no double sale |
| 3 | Family members, PAPER | Invite → wizard → PAPER day by day | Each completes setup and a morning login without help |
| 4 | Owner, LIVE | Phone exits on ZERODHA-1 (exit and stop only; the PAPER/LIVE switch stays on the desktop) | User's explicit go |
| 5 | Domain + gate off + push on + passkeys | Runbook `mobile-front-door.md` | User's decisions 1 and 2 |

## Real-phone checklist (one iPhone, one mid-range Android, on 4G, not Wi-Fi)

Each line: pass / fail with a screenshot. Run once in market hours (data moving) and once after close.

- [ ] Sign in (Keycloak) on the phone; the session survives switching apps for 10 minutes.
- [ ] Every tab and More item opens; nothing scrolls sideways; text readable without zoom; buttons hit first time.
- [ ] Rotate: the chart page in landscape; the More sheet and Exit sheet still fit.
- [ ] Home: the checklist shows each broker's login state; Log in → Kite page → back to Home logged in (same tab).
- [ ] Positions: P&L matches the desktop within one refresh; "as of" turns amber when the phone is put in airplane mode.
- [ ] Exit sheet on a PAPER position: Half, the preview, Exit; then airplane mode during an Exit → "may have sold" and
      Retry (no second sale in the ledger).
- [ ] Ledger: day / week / month match the desktop Accounts ledger; Download CSV opens or saves on the phone.
- [ ] Strategies: the records match; a change on the phone shows on the desktop Accounts panel.
- [ ] Chart: chart first; Chart settings, OI tape and PCR open from their buttons; pinch zoom works.
- [ ] Add to Home Screen (iPhone: Safari → Share → Add to Home Screen; Android: Install app): icon, name, opens on Home,
      no browser bar; after a deploy "A new version is ready" appears and Reload works.
- [ ] (After push is on) Notifications → Turn on → Send a test arrives with the screen locked; the 08:30 login
      reminder arrives; a PAPER BUY alert arrives and opens Positions.
- [ ] (After passkeys) Sign in with Face ID / fingerprint; the password + code still works.
- [ ] Known gap: opening the installed app with no network shows a blank page (not yet solved).

## Watching it

- Client errors: `docker logs autotrade-trading-core 2>&1 | grep "client error"` (at most 30 a minute are logged).
- Push: `select id, user_agent, last_ok_at, last_error, failures from acct.push_subscription`.
- Rollback: redeploy the previous image tag (the deploy log names it); push off = flag off.
