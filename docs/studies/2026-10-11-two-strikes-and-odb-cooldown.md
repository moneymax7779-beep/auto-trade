# Two strikes (risk v13) and an opening-drive cool-down (odb-v4): ledger A-071 and A-072

Registered 2026-10-11, before any code ran on a replay. User: "do 2 and 3, register and test them", after the review of
30 Sep SENSEX (session 728): opening drive bought a call at 09:21 (stopped 09:23, −₹69.2K) and again at 09:24 (stopped
09:31, −₹27.4K); break-retest then bought five breakouts in a 72,650–72,950 range and lost four (−₹12.7K, −₹5.0K,
−₹5.6K, −₹14.4K) around one +₹42.0K. Both rules come from that one day; 30 Sep is therefore a design day for both.

## A-071: two strikes (risk v13)

After 2 losing trades in a row by one strategy on one index, that strategy makes no new entry on that index for the
rest of the day. A straddle's two legs are one trade. Other strategies and the other index are not affected; exits are
never blocked. `config/risk/paper-risk.v13.yaml` (`account.max_consecutive_losses: 2`); everything else as v12.

## A-072: opening-drive cool-down (odb-v4)

No new opening-drive entry for 15 minutes after a losing trade closes (`scope.reentry_cooldown_after_loss_min: 15`).
The trigger window is 09:16–09:26, so in practice: no second trade after a first trade that lost. After a winning
trade odb-v3's re-entry rule (a fresh break of a level) stands. `config/strategy/opening-drive.v4.yaml`.

## Test

The 22 full tick days on the Mac (zt-tiger-v2 capture, scratch DB `zt_archive`): 2, 3, 7, 8, 9, 10, 15, 17, 18, 22,
23, 24, 25, 28, 29, 30 Sep, 1, 5, 6, 7, 8, 9 Oct. Held-out = 22, 23, 24, 25 Sep (`research.session_split`), read once
for each hypothesis and reported apart; test = the other 18 (30 Sep, the design day, is among them and reported too).
Three runs on ONE new image, so only the config differs:
- control: the live set as deployed (odb-v3, risk v12);
- A-071: the live set with risk v13;
- A-072: the live set with odb-v4 in odb-v3's place (risk v12).
The control run must reproduce the earlier sessions 537–549 / 720–734 day by day; if it does not, the comparison is
void and the cause is found first.

## Pass criteria (fixed now)

A-071 PASS if, on the 18 test days: (a) set net ≥ control net − ₹50,000; (b) the summed loss of the control's losing
days is at least 20 % smaller with v13; (c) the rule refuses at least 3 entries (else INCONCLUSIVE); and on the
held-out days set net ≥ control − ₹50,000.

A-072 PASS if, on the 18 test days: (a) opening-drive net with v4 ≥ with v3; (b) the set net is not lower by more than
₹50,000; (c) at least 2 re-entries are prevented (else INCONCLUSIVE); and on the held-out days opening-drive net with
v4 ≥ v3 − ₹25,000.

A PASS means a decision for the user about the live set, not an automatic switch.

## Result (11 Oct 2026, 08:20 IST; image c61f805-a71; `.local/a71/summary.txt`)

The control reproduced the earlier sessions 537–549 / 720–734 on all 22 days to the rupee, so the runs differ only in
the config.

| Run | Test (18 days) net | Test trades | Held-out (4 days) net |
|---|---|---|---|
| control (risk v12, odb-v3) | +23,48,902 | 112 | +4,31,141 |
| A-071 risk v13 (two strikes) | +21,26,101 | 101 | +2,90,495 |
| A-072 odb-v4 (cool-down) | +23,97,974 | 112 | +4,31,141 |

**A-071: FAIL.** (a) test net −2,22,801 against the control (bar: −50,000). (b) the control's five losing test days
lost 3,96,842; with v13 3,61,861, 8.8 % less (bar: 20 %). (c) 16 entries refused (12 break-retest, 4 ma-cross).
Held-out −1,40,646 (25 Sep: +96,568 → −44,078). The cost is ma-cross: −1,99,062 on test days and −1,40,645 held-out.
On 9, 25 and 28 Sep it lost twice and then hit its target for about +₹1L; the rule had already stopped it. Break-retest
was also worse with v13 (−31,656 → −55,394): some of the refused third entries were its rare winners. These strategies
win seldom and big; their winning days often begin with small losses, and a two-strikes stop removes the payoff.

**A-072: PASS as registered, on two cases.** (a) opening-drive on test days +39,483 vs +33,802. (b) set net +49,072
better. (c) 2 re-entries prevented (exactly the minimum). Held-out: no opening-drive trade on 22–25 Sep (0 vs 0).
Where it came from: 30 Sep (the design day) the prevented 09:24 re-entry would have lost 27.4K; 8 Oct the prevented
re-entry would have made about 21.7K. So outside the design day the cool-down cost money, and most of the set's +49K is
a side effect: break-retest went −31,656 → +11,735 because the capital and sequence changed after 09:30 on 30 Sep, not
because of anything opening-drive did. The evidence is one favourable case on the day the rule was drawn from and one
unfavourable case on an unseen day.
