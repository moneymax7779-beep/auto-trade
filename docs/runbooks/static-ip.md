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
      connect autotrade-execution at 172.30.0.(8k+2), gateway priority -1000  → PROVISIONED
worker: EgressRegistry every 10 s binds the account's broker calls to 172.30.0.(8k+2); every 5 min asks
        api.ipify.org and checkip.amazonaws.com through that address: both must answer the reserved IP
owner: registers the IP at the broker, types it on the Brokers page ("I registered it")
LIVE gate: PROVISIONED + a passing check younger than 15 min + registered (plus the broker's LIVE adapter, the admin's
           eligibility and the rest of the existing gates)
```

Everything else (market data, zt-v2, ZERODHA-1, every account without a row) keeps leaving from `zt-nat` /
34.14.137.170: the execution container's default route stays on its own network; only calls bound to an account's
address use that account's IP. An account whose address is missing (deploy just recreated the container, a broken
network) is refused, never sent from the shared IP; the timer reconnects within 30 s.

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
     --permissions=compute.addresses.create,compute.addresses.use,compute.routers.update,compute.instances.updateNetworkInterface,compute.subnetworks.use
   gcloud projects add-iam-policy-binding zt-tiger-v2-prod --member=serviceAccount:zt-autotrade-vm@zt-tiger-v2-prod.iam.gserviceaccount.com \
     --role=projects/zt-tiger-v2-prod/roles/autotradeEgressRead --condition=None
   gcloud projects add-iam-policy-binding zt-tiger-v2-prod --member=serviceAccount:zt-autotrade-vm@zt-tiger-v2-prod.iam.gserviceaccount.com \
     --role=projects/zt-tiger-v2-prod/roles/autotradeEgressWrite \
     --condition='title=egress-only,expression=resource.name == "projects/zt-tiger-v2-prod/regions/asia-south1/routers/autotrade-egress-router" || resource.name.startsWith("projects/zt-tiger-v2-prod/regions/asia-south1/addresses/egress-acct-") || resource.name == "projects/zt-tiger-v2-prod/zones/asia-south1-a/instances/zt-autotrade" || resource.name == "projects/zt-tiger-v2-prod/regions/asia-south1/subnetworks/zt-subnet"'
   ```

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
