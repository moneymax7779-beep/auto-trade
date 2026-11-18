-- Per-account static IP, provisioned automatically (user 18 Nov 2026: "static ip should be auto provisioned to enable
-- LIVE"; plan 2026-11-01 section 3.9, decisions D9/D10). The owner presses "Get a static IP" (a REQUESTED row); the
-- host provisioner on the order VM (bin/egress, its own narrow GCP account; the application has no GCP rights) reserves
-- the IP, puts the account on a slot (a secondary range of zt-subnet, an alias IP on the VM, a Cloud NAT gateway on
-- autotrade-egress-router, a Docker network SNATed to the alias) and writes PROVISIONED. The worker binds the account's
-- broker calls to container_ip, checks every few minutes that they leave from public_ip, and the owner registers
-- public_ip at the broker and ticks it. LIVE needs all three: PROVISIONED, a passing check, registered.
create table acct.broker_egress (
    broker_account_id bigint      primary key references acct.broker_account (id),
    state             text        not null default 'REQUESTED'
                                  check (state in ('REQUESTED', 'PROVISIONED', 'FAILED', 'RETIRED')),
    slot              int         unique check (slot between 1 and 30),
    public_ip         inet,
    address_name      text,
    range_name        text,
    alias_ip          inet,
    docker_network    text,
    container_ip      inet        unique,
    nat_name          text,
    attempts          int         not null default 0,
    last_error        text,
    requested_by      text        not null,
    requested_at      timestamptz not null default now(),
    provisioned_at    timestamptz,
    -- the worker's check: the public IP its calls for this account were seen from (two echo services must agree)
    last_check_at     timestamptz,
    last_check_ip     inet,
    last_check_ok     boolean,
    verified_at       timestamptz,
    -- the owner's word that public_ip is registered at the broker (cleared when the IP changes)
    registered_at     timestamptz,
    registered_by     text,
    updated_at        timestamptz not null default now(),
    check (state <> 'PROVISIONED' or (slot is not null and public_ip is not null and container_ip is not null))
);
