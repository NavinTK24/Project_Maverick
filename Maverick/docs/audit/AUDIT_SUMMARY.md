# Audit summary

## What the repo supports

The OFFGRID repository is a phone-first dead-reckoning system for a bicycle. The core code, data narrative, and published results all point to a smartphone IMU + GNSS + learned speed model workflow, with the vehicle benchmark treated as a separate transfer test.

## What the data actually support

The synchronized IO-VNBD benchmark contains paired phone and vehicle streams:

- `S-*` files are the phone input stream.
- `V-*` files are the vehicle ground-truth stream.

Measured facts:

- S-only benchmark files: 72
- S-only rows: 1,070,745
- S-only size: 189.90 MB
- S-only timing: 100 ms median sample spacing = 10 Hz

## The governing rule for this audit

The correct audit interpretation is strict:

- For IO-VNBD, the phone `S` files are the only allowed inputs.
- The vehicle `V` files are ground truth only.
- The `M (Driver B)` subset is held out from any tuning or benchmark selection logic unless the task explicitly says otherwise.

## Main issue identified

The main risk in prior interpretation is conflating the vehicle benchmark with the phone benchmark. Those are different modalities: the phone stream has accelerometer/gyro/gravity channels; the vehicle stream has wheel speeds, CAN yaw rate, and vehicle acceleration. Using the wrong stream changes the problem definition.

## Verdict

- OFFGRID repo: phone-first, bicycle-domain, valid and internally consistent.
- IO-VNBD benchmark: real but split into two sensor domains.
- Phone benchmark: valid smartphone-only benchmark with measured 10 Hz cadence.
- Vehicle benchmark: valid ground-truth transfer benchmark, not a phone input stream.

## Candidate next actions

- Keep the phone-side dead-reckoning path as the canonical build target.
- Treat the vehicle benchmark strictly as an external transfer benchmark.
- Lock the `M` cluster out of tuning and use it only as a held-out sanity check.
- Keep the audit numbers reproducible through the measurement scripts in [docs/audit/scripts](scripts/).

## Reproducible measurement commands

```bash
python "c:\Users\STUDENT\Desktop\offgrid\docs\audit\scripts\measure_iovnbd_phone_inventory.py"
```

The repo-level findings are documented in [OFFGRID_AUDIT.md](OFFGRID_AUDIT.md), and the phone benchmark findings are documented in [IOVNBD_PHONE_AUDIT.md](IOVNBD_PHONE_AUDIT.md).
