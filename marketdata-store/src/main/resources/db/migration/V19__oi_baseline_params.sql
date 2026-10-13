-- Features v12 (A-090, code review 13 Oct): the baseline's definition as columns, so the engine can refuse a baseline
-- measured with other strikes, weights or window than the running features file (the free-text params stay).
alter table ref.oi_baseline
    add column near_atm_strikes     integer,
    add column heavy_weight_strikes integer,
    add column outer_strike_weight  double precision,
    add column window_min           integer;
