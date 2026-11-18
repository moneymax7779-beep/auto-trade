# Per-account static IPs, provisioned automatically

User, 18 Nov 2026: "static ip should be auto provisioned to enable LIVE". Decisions the same day: a host service with its
own narrow GCP rights does the work (the application never gets network rights); `zt-nat` is narrowed to the subnet's
primary range after the close (D10). Design: plan 2026-11-01 section 3.9.

## How it works

```
owner: Brokers → Static IP → "Get a static IP"     → acct.broker_egress REQUESTED          (EgressController)
VM: autotrade-egress.timer, every 30 s, as root    → bin/egress run                         (no container involved)
      reserve egress-acct-<id>  → NAT nat-acct-<id> on autotrade-egress-router, serving only acct-slot-<k>
      alias 10.21.0.(8k+2) on zt-autotrade nic0     → docker network egress-acct-<id> (172.30.0.(8k)/29, SNAT to alias)
      connect autotrade-execution at 172.30.0.(8k+2), gateway priority -1000; in its namespace (nsenter from the
      host) rule `from 172.30.0.(8k+2) lookup 1000+k`, table default via 172.30.0.(8k+1)  → PROVISIONED
      (every pass re-checks the connection and the rule: a recreated or restarted container gets them back)
worker: EgressRegistry every 10 s binds the account's broker calls to 172.30.0.(8k+2); every 5 min asks
        api.ipify.org and checkip.amazonaws.com through that address: both must answer the reserved IP (no answer
        through it while the shared route answers = fail: the account's route is broken; no answer anywhere = inconclusive)
owner: registers the IP at the broker, types it on the Brokers page ("I registered it")
LIVE gate: PROVISIONED + a passing check younger than 12 h + registered (plus the broker's LIVE adapter, the admin's
           eligibility and the rest of the existing gates)
```

Everything else (market data, zt-v2, ZERODHA-1, every account without a row) keeps leaving from `zt-nat` /
34.14.137.170: the execution container's default route stays on its own network; only calls bound to an account's
address use that account's IP. An account whose address is missing (deploy just recreated the container, a broken
network) is refused, never sent from the shared IP; the timer reconnects within 30 s.

Why the source rule (E0, 18 Nov 2026): the default route stays on the container's own network (gateway priority), so
without it a call bound to the account's address leaves through that network and its answer never returns.

Slots: 7 (region quota 8 static IPs, 1 used by `zt-egress-ip`). More needs a quota increase (console) and `setup`.

## One-time setup (project owner, after the close; done 18 Nov 2026 if the log below says so)

1. **zt-v2 and auto-trade outbound IP before**: `curl -s https://api.ipify.org` on both VMs → 34.14.137.170.
2. **D10**: `gcloud compute routers nats update zt-nat --router=zt-router --region=asia-south1 --nat-custom-subnet-ip-ranges=zt-subnet`
   (primary range only), then step 1 again on both VMs. Rollback: `--nat-all-subnet-ip-ranges`.
3. **Router and slots**: `bin/egress setup` (prints), `bin/egress setup --apply` (router `autotrade-egress-router`,
   secondary ranges `acct-slot-1..7` = 10.21.0.8/29 … 10.21.0.56/29 on zt-subnet).
4. **Metadata block on the VM first** (before any IAM grant): `sudo /srv/auto-trade/bin/egress install` installs
   `autotrade-metadata-block.service` (before Docker: `DOCKER-USER -d 169.254.169.254 -j DROP`) and the timer. Check
   from a container: `sudo docker exec autotrade-postgres bash -c 'timeout 3 bash -c "</dev/tcp/169.254.169.254/80" && echo OPEN || echo blocked'` → blocked.
5. **IAM** for `zt-autotrade-vm@zt-tiger-v2-prod.iam.gserviceaccount.com`:

   ```bash
   gcloud iam roles create autotradeEgressRead --project=zt-tiger-v2-prod --title="auto-trade egress read" \
     --permissions=compute.addresses.get,compute.addresses.list,compute.routers.get,compute.instances.get,compute.subnetworks.get,compute.regionOperations.get,compute.zoneOperations.get,compute.networks.get
   gcloud iam roles create autotradeEgressWrite --project=zt-tiger-v2-prod --title="auto-trade egress write" \
     --permissions=compute.addresses.create,compute.addresses.use,compute.routers.update,compute.instances.updateNetworkInterface,compute.subnetworks.use,compute.networks.updatePolicy
   gcloud projects add-iam-policy-binding zt-tiger-v2-prod --member=serviceAccount:zt-autotrade-vm@zt-tiger-v2-prod.iam.gserviceaccount.com \
     --role=projects/zt-tiger-v2-prod/roles/autotradeEgressRead --condition=None
   gcloud projects add-iam-policy-binding zt-tiger-v2-prod --member=serviceAccount:zt-autotrade-vm@zt-tiger-v2-prod.iam.gserviceaccount.com \
     --role=projects/zt-tiger-v2-prod/roles/autotradeEgressWrite \
     --condition='title=egress-only,expression=resource.name == "projects/zt-tiger-v2-prod/regions/asia-south1/routers/autotrade-egress-router" || resource.name.startsWith("projects/zt-tiger-v2-prod/regions/asia-south1/addresses/egress-acct-") || resource.name == "projects/zt-tiger-v2-prod/zones/asia-south1-a/instances/zt-autotrade" || resource.name == "projects/zt-tiger-v2-prod/regions/asia-south1/subnetworks/zt-subnet" || resource.name == "projects/zt-tiger-v2-prod/global/networks/zt-net"'
   ```

   `compute.networks.updatePolicy` on zt-net is what `routers nats create` asks for (18 Nov 2026); alone it cannot
   create firewall rules or routes (each also needs its own create permission, which neither role has).
   Check from the VM host that the conditions hold: `gcloud compute addresses create probe-denied --region=asia-south1`
   must be refused (name outside `egress-acct-`) and `gcloud compute routers describe zt-router --region=asia-south1`
   allowed (read role) while nothing on zt-router can be updated. If a provisioning step is refused for a permission
   not listed above, add exactly that permission to the matching role (the error names it), never a broader role.
6. **Deploy** the code (V48, the worker's registry and check, the Brokers page card) the usual way.

## Daily

Nothing. `journalctl -u autotrade-egress --since today` on the VM; `sudo /srv/auto-trade/bin/egress list`.

## Retiring an account

`bin/egress retire --account <id>` prints, `--apply` removes the NAT gateway, alias and Docker network (needs delete
rights: run by the project owner). The reserved IP is **kept** (it may still be registered at the broker; a released IP
can go to a stranger): release it by hand once the owner has removed it at the broker.

## Rollback

- Stop the service: `sudo systemctl disable --now autotrade-egress.timer`.
- Remove the IAM bindings (the two `remove-iam-policy-binding` of step 5).
- Per account: `retire` as above. Accounts then use the default route again (PAPER only for brokers that need an IP).
- D10 back: `--nat-all-subnet-ip-ranges` on zt-nat (only after every `nat-acct-*` gateway is gone).

## Log

| When (IST) | What | Result |
|---|---|---|
| 18 Nov 16:47 | deploy main d1f9bdb (V48, registry, Brokers card), deploy-exec SCHEMA=48 + egress hook | ok; ZERODHA-1 LIVE route unchanged |
| 18 Nov 16:50 | step 1: outbound IP of zt-v2 and zt-autotrade | both 34.14.137.170 |
| 18 Nov 16:51 | step 2 D10: zt-nat → zt-subnet PRIMARY_IP_RANGE | done; both VMs still 34.14.137.170 |
| 18 Nov 16:53 | step 3 setup --apply | autotrade-egress-router, acct-slot-1..7 on zt-subnet |
| 18 Nov 16:55 | step 4 install | DOCKER-USER drop 169.254.169.254; containers blocked, host not; execution still 34.14.137.170; timer every 30 s |
| 18 Nov 16:58 | step 5 IAM roles + bindings | conditions hold (probe-denied refused, reads allowed) |
| 18 Nov 17:00 | E0 via the provisioner's steps, account 21 slot 1 | egress-acct-21 = 34.100.156.146 reserved; NAT create refused: needs compute.networks.updatePolicy on zt-net (added to this runbook; grant pending the owner) |
| 18 Nov 18:00 | owner added compute.networks.updatePolicy + zt-net to the condition; E0 steps rerun | NAT nat-acct-21, alias 10.21.0.10, docker network egress-acct-21 |
| 18 Nov 18:02 | throwaway container (default network + egress-acct-21 at -1000) | default route kept, unbound 34.14.137.170, BOUND TIMED OUT (asymmetric route) |
| 18 Nov 18:05 | same + source rule from 172.30.0.11 lookup 1001 | bound 34.100.156.146 on both services, Kite 200, unbound 34.14.137.170 → bdee022 |
| 18 Nov 18:09 | new bin/egress connect + rule on a throwaway, restart, drop | rule restored after restart by the next pass, dropped on retire |
