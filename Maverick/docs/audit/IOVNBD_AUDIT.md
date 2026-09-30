# IO-VNBD audit

Scope: read-only audit of the repository and the external benchmark. No source files were modified outside the permitted audit paths under [docs/audit](.) and the scripts under [docs/audit/scripts](scripts/).

## Part A — repo-level finding: OFFGRID is phone-first; IO-VNBD is an external vehicle benchmark

The repository is internally consistent on this split:

- [README.md](../../README.md) describes OFFGRID as a smartphone dead-reckoning system that uses a phone accelerometer/gyroscope, learned speed inference, and a Kalman filter under GNSS outages.
- [engine/README.md](../../engine/README.md) identifies the engine modules and the runtime flow: QA, alignment, heading, speed model, filter, corridor, replay, and evaluation.
- [engine/iovnbd.py](../../engine/iovnbd.py) is the external IMU benchmark path. Its docstring explicitly says it is the engine on an external 10 Hz vehicle IMU with the VBOX GNSS masked, and it loads the V-dataset files from the external benchmark tree.
- [docs/11_ENGINE_RESULTS.md](../11_ENGINE_RESULTS.md) confirms this distinction: the phone path is the main project; IO-VNBD is a separate vehicle benchmark used to test transfer onto an external IMU.

The measured repo evidence points to one clear conclusion:

- The main OFFGRID runtime is phone-only and bicycle-specific.
- The IO-VNBD branch is a separate external-vehicle track, using CAN yaw and longitudinal acceleration plus GNSS-derived truth, not the phone logger or the bicycle pipeline.

The data also confirm that this benchmark is not an Android-phone dataset in the same sense as the repo’s rides. The representative V-file header is:

- `No of GPS Satellites Available`
- `Time Since Start of Day (seconds)`
- `Latitude (degrees)`
- `Longitude (degrees)`
- `Velocity (km/hr)`
- `Heading (degrees)`
- `Yaw Rate (deg/sec)`
- `Indicated Longitudinal Acceleration (g)`
- `Indicated Lateral Acceleration (g)`

That is a vehicle data schema, not a phone IMU stream with a bicycle mount and the repo’s app-specific QA stream layout.

## Part B — measured inventory of the external benchmark

I measured the actual files under the external benchmark tree:

- Root: `C:\Users\STUDENT\Desktop\Maverick\Maverick45\IO-VNBD\Unsynchronised V and S Dataset\Categorised IOVNB (V) Dataset\V Dataset`
- Count: 89 CSVs
- Total rows: 1,413,263
- Total size: 274.89 MB

Driver-group inventory from the actual CSVs:

| Driver group | Files | Rows | Size |
|---|---:|---:|---:|
| M (Driver B) | 1 | 105,995 | 21.2 MB |
| S (Driver A) | 6 | 312,234 | 62.4 MB |
| St (Driver C) | 4 | 166,591 | 32.9 MB |
| Vf (Driver E) | 13 | 194,482 | 38.9 MB |
| Vta (Driver E) | 30 | 130,102 | 25.9 MB |
| Vtb (Driver E) | 13 | 116,183 | 23.1 MB |
| Vw (Driver E) | 20 | 260,122 | 51.9 MB |
| Y (Driver D) | 2 | 127,554 | 25.3 MB |
| Total | 89 | 1,413,263 | 274.89 MB |

The non-M subset is the practical benchmark ceiling pool used in the audit because it filters out the single `M` cluster and leaves 88 files for the general external-vehicle population.

### Benchmark composition

The drive-level mix is heterogeneous:

- The benchmark is split by driver, not by a single route or a single IMU setup.
- The data are short to medium sequences rather than a single long route.
- The measured cadence is 10 Hz across the set: the median sample spacing is 0.100 s and the mean is 0.100 s.

This matters because the repo’s vehicle benchmark logic is a transfer test on external IMU rather than a direct phone deployment benchmark.

## Part C — ceiling baselines from the non-M V files

I measured the sensor quality ceiling on the non-M subset, using the actual V-dataset signals against the GNSS-derived course and speed derivative.

### Overall ceiling measurements

| Metric | Value |
|---|---:|
| Files in non-M subset | 88 |
| Sample period (median) | 0.100 s |
| Sample period (mean) | 0.100 s |
| Drive duration (median) | 246.95 s |
| Drive duration (mean) | 1493.45 s |
| Drive duration (max) | 12657.20 s |
| Yaw-rate correlation vs GNSS course rate (median) | 0.731 |
| Yaw-rate correlation vs GNSS course rate (mean) | 0.679 |
| Yaw-rate correlation vs GNSS course rate (min) | 0.099 |
| Longitudinal acceleration correlation vs GNSS dv/dt (median) | 0.722 |
| Longitudinal acceleration correlation vs GNSS dv/dt (mean) | 0.696 |
| Longitudinal acceleration correlation vs GNSS dv/dt (min) | 0.170 |

Interpretation:

- The vehicle benchmark is clearly a 10 Hz IMU set with clean, regular timing.
- The yaw-rate signal is strongly informative in aggregate, which aligns with the repo’s use of CAN yaw-rate calibration in [engine/iovnbd.py](../../engine/iovnbd.py).
- The longitudinal acceleration enters in a less clean but still usable way; this is consistent with a real car sensor that has acceleration and CAN dynamics, but not with the same vibration-rich phone signal used by the bicycle pipeline.

### Representative per-drive quality summary

This is not a formal benchmark result; it is a measurement of the available ceiling. The raw non-M set shows a broad but consistent range:

| Driver | Median yaw corr | Median ax corr | Median duration (s) |
|---|---:|---:|---:|
| S (Driver A) | 0.839 | 0.704 | 4,721.9 |
| St (Driver C) | 0.574 | 0.354 | 5,121.5 |
| Vf (Driver E) | 0.745 | 0.759 | 1,099.9 |
| Vta (Driver E) | 0.708 | 0.673 | 285.2 |
| Vtb (Driver E) | 0.592 | 0.218 | 571.1 |
| Vw (Driver E) | 0.849 | 0.604 | 394.0 |
| Y (Driver D) | 0.829 | 0.424 | 6,696.8 |

This reinforces the same point as the repo: the vehicle benchmark is usable as an external-IMU transfer test, but it is not the same signal quality or constraint set as the phone dataset used in the main OFFGRID model.

## Issues found

1. The benchmark is external, not in-repo, and not phone-native.
   - The repo’s actual phone pipeline is the primary design.
   - The V dataset is a car benchmark with CAN signals and VBOX GNSS, which is a different modality and should not be treated as equivalent to the phone data.

2. The benchmark is heterogeneous and grouped by driver.
   - The file set is not a single route or a single driver distribution.
   - It is a set of vehicle sequences assembled for transfer experimentation, not a “one-route phone ride” dataset.

3. The exact repo code does not use the full benchmark as a single monolithic train/test set.
   - [engine/iovnbd.py](../../engine/iovnbd.py) trains on one or more selected V files and tests on another selected V file. This is a carefully chosen subset workflow, not a full-benchmark audit pipeline.

4. The sensor ceiling is informative but still weaker than a bike-phone vibration signal for the learned speed model.
   - The measured yaw-rate correlation is good enough to support heading fusion.
   - The longitudinal-acceleration correlation is plausible but not as rich in the vibration cue that the phone model uses.
   - That is exactly why the repo documents the V benchmark as an external IMU transfer challenge rather than as a primary phone benchmark.

## Open questions

- Which exact V sequence subset is the official benchmark test set used for publication claims in the repo’s external-IMU results?
- Is the “synchronised” branch intended to be the canonical one for engine runs, or is the unsynchronised branch still used for some sequences and script variants?
- Should the audit lock the benchmark to the non-M set only, or should it report both the full set and the non-M subset separately when comparing to the repo’s phone results?
- What is the exact held-out split used by the benchmark script, beyond the examples Vfa01/Vfa02 and the subset noted in [docs/11_ENGINE_RESULTS.md](../11_ENGINE_RESULTS.md)?

## Reproducible measurement commands

All measurement scripts are in [docs/audit/scripts](scripts/):

```bash
python "c:\Users\STUDENT\Desktop\offgrid\docs\audit\scripts\measure_iovnbd_inventory.py"
python "c:\Users\STUDENT\Desktop\offgrid\docs\audit\scripts\measure_iovnbd_ceiling.py"
```

For the repo’s benchmark pipeline itself, the exact script in use is also declared in [engine/iovnbd.py](../../engine/iovnbd.py):

```bash
cd "c:\Users\STUDENT\Desktop\offgrid"
python engine/iovnbd.py --train Vfa01 --test Vfa02 --mask 120 --every 300 --gnss_hz 1
```

This is the exact external-IMU path that the project uses to measure benchmark transfer behavior.

## Bottom line

The data and the repo agree: OFFGRID’s main claim is a phone-based dead-reckoning system, while IO-VNBD is a separate external-vehicle transfer benchmark. The benchmark is real, measured, and physically heterogeneous, but it is not the same signal domain as the bicycle/phone dataset used for the repo’s main results.
