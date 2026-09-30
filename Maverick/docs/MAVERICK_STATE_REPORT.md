# Maverick State Report

Inventory date: 2026-09-26. Every recursive IO-VNBD traversal stopped before `IO-VNBD\Synchronised V abd S datasets\Categorised IOVNB Dataset\M (Driver B)`. No contents or entries inside that locked folder were inspected or counted. Totals below precede report creation; sizes are bytes.

## 1. Folder map

| Folder | Files | Size | Belongs to | Description |
|---|---:|---:|---|---|
| `.git` | 501 | 1,624,750,936 | UNKNOWN | Repository metadata. |
| `data` | 3,013 | 1,521,253,382 | UNKNOWN | Mixed OFFGRID datasets and own `tracks_csv`. |
| `docs` | 225 | 61,025,845 | UNKNOWN | Mixed OFFGRID and Maverick documentation, including `docs\idr`. |
| `engine` | 21 | 215,703 | OFFGRID | Python engine. |
| `eval_iovnbd` | 4 | 53,566 | OBSOLETE EXPERIMENT | Earlier IO-VNBD evaluation experiments (as described in supplied project map). |
| `idr` | 12 | 94,388 | OURS | Six Python sources plus six Python 3.13 bytecode files; source is incomplete vs supplied latest criteria. |
| `idr_old` | 14 | 80,462 | OBSOLETE EXPERIMENT | Older IDR experiments. |
| `IDRLogger` | 42 | 187,607 | OFFGRID | Android logger. |
| `IDRNav` | 361 | 8,585,236 | OFFGRID | Android navigation app and `engine` Gradle module. |
| `IO-VNBD` | 727 | 1,875,962,858 | DATASET | External synchronized V/S data; locked folder excluded from count and size. |
| `tools` | 7 | 84,327 | OFFGRID | Tools. |
| `your_script.py` | 1 | 20,183 | UNKNOWN | Root Python file; first line `import geopandas as gpd`, not a docstring/comment. |

Parent `C:\Users\STUDENT\Desktop\MaverickDRMap` contains only `Maverick`. No additional top-level project folders were found. Direct `data` children beyond `field`, `map`, `processed`, `qa`, `raw`, `route1`, `route2`, `speed_model`, `tracks_csv`: none.

Root listing (`Mode | LastWriteTime | Length | Name`, captured before report creation):

```text
d---- | 2026-09-26 10:24:26 |         | .git
d---- | 2026-09-26 10:24:53 |         | data
d---- | 2026-09-26 10:24:55 |         | docs
d---- | 2026-09-26 10:24:55 |         | engine
d---- | 2026-09-26 10:24:55 |         | eval_iovnbd
d---- | 2026-09-26 10:24:56 |         | idr
d---- | 2026-09-26 10:24:58 |         | idr_old
d---- | 2026-09-26 10:24:56 |         | IDRLogger
d---- | 2026-09-26 10:24:58 |         | IDRNav
d---- | 2026-09-26 10:25:07 |         | IO-VNBD
d---- | 2026-09-26 10:25:16 |         | tools
----- | 2026-09-25 01:06:56 | 562    | .gitattributes
----- | 2026-09-25 01:06:56 | 1493   | .gitignore
----- | 2026-09-25 01:06:58 | 1384   | LICENSE
----- | 2026-09-25 01:06:58 | 17167  | README.md
----- | 2026-09-25 12:04:56 | 20183  | your_script.py
```

Identity evidence and requested opening lines:

```text
README.md (first 15 lines)
# OFFGRID — intelligent dead reckoning for smartphones

**Smart India Hackathon 2026 · Team OFFGRID (26168) · "AI-ML based Intelligent Dead Reckoning System for seamless navigation" · Theme: Smart Vehicles · Category: Software**

A phone that keeps navigating after the satellites go away.

When GNSS drops — a tunnel, an underpass, a street with tall buildings on both sides — a phone has nothing left but its own accelerometer and gyroscope, and integrating those is hopeless. We measured it on our own hardware: a 70-second ride, GNSS withheld from the first second, pure inertial integration ends up **2,889 m** from where the bike actually stopped. The bike travelled 197 m.

OFFGRID replaces the integration step with a small neural network that reads forward speed straight out of the vibration pattern of a moving vehicle, and feeds it — with its own uncertainty — into a Kalman filter alongside gyro heading, offline road geometry and a zero-velocity detector. Over 600–850 m outages, four times longer than that baseline ride, the error settles at **1.5 % of distance travelled**, and every single held-out ride stays under 10 %.

Everything here is measured. The rides, the model, the evaluation harness, the Android app and the numbers below are all in this repository.

<p align="center">
	<img src="docs/figures/working_prototype.png" alt="Recording, training, validation, and the app running a GNSS outage on the phone" width="100%">
</p>

LICENSE (first 3 lines)
MIT License

Copyright (c) 2026 Team OFFGRID (Smart India Hackathon 2026, Team ID 26168)
```

Root files: `.gitattributes` 562 B, `.gitignore` 1,493 B, `LICENSE` 1,384 B, `README.md` 17,167 B, `your_script.py` 20,183 B.

## 2. Git

Repository: yes. Remote fetch/push: `https://github.com/NavinTK24/Maverick.git`. Branch/HEAD `main`, `4577c38`. Last author/date: `NavinTK24 Sat Sep 26 07:57:52 2026 +0530`. Status before this report: 0 changes (empty output); adding this report itself creates one untracked file. `git log --oneline -15` returned 11 commits:

```text
4577c38 (HEAD -> main) first commit
72447dc 25/09/mrng
087b1e2 push1
9837fd6 Add IO-VNBD dataset
70bd639 Replace machine-specific paths with repository-relative ones
162990a Project README
08e9693 Engineering record: plans, specs, research reviews and results
8ad7295 Ride data, processed tables, trained models and evaluation output
7293bbc IDR Logger and IDR Nav Android apps
327f929 Python reference engine, speed model and evaluation harness
fc73906 Repository scaffolding: licence, ignore rules, binary attributes
```

## 3. idr package

Source file metadata and exact-byte SHA256 hashes:

| File | Present | Bytes | Modified | SHA256 | Latest comparison / marker |
|---|---|---:|---|---|---|
| `__init__.py` | yes | 364 | 2026-09-25 03:36:22 | `914913B084A8CC7B9546EBC8E1FD56B56547384E2E5276B6EE1B0AF116B17408` | NOT CHECKED: no supplied reference hash |
| `data.py` | yes | 8,463 | 2026-09-25 03:34:40 | `21C96F7B1B556ED2BE2C2C4427FD759140B79927FA1588BAFE1744AF4A9A5243` | NOT CHECKED: no supplied reference hash |
| `features.py` | yes | 3,794 | 2026-09-25 03:40:50 | `9FF28D69652CE5C8EDAF5D25CE494F9C3B260B082F8E09FB2349A5F92906E0C3` | NOT CHECKED: no supplied reference hash |
| `harness.py` | yes | 3,179 | 2026-09-25 03:33:40 | `593C57669247B7DBD95BC020B3A7711126A837821B44606E580EF31F1EFB089F` | no; `yaw_rate` absent; content differs from expected hash |
| `models.py` | yes | 2,345 | 2026-09-25 03:33:20 | `6B816A41B504EF496418E232DE1431260931F6C6A12B2F4C880110242F319CC9` | no; `deterministic=True` absent; content differs from expected hash |
| `run.py` | yes | 13,890 | 2026-09-25 03:41:10 | `35214935CB3C50F7301F0C20BFBE035D75B9002C9399525F46874FF6616BF8C0` | no; `prior_var` absent; content differs from expected hash |
| `calib.py` | no | — | — | — | required, missing |
| `experiments.py` | no | — | — | — | required, missing |
| `bike.py` | no | — | — | — | required, missing |
| `ride_qa.py` | no | — | — | — | optional, absent |

Supplied latest hashes: `harness.py` `063523C9555C0FF1E503B3052BBED126F7565E6A9C5D3073F45246502CFC248B`; `models.py` `23375B757657FF140559AD4B1912A6EE2EA78DC239D39FFBD83D8C2F6F90F117`; `run.py` `1611462364F972394DEA6B36FFED59B39CD1D6345ED726D8D7765CCAE3E32582`. After CRLF-to-LF normalization, computed hashes remain different: `harness.py` `6C902E6132A6D67922D5C446C9BB5415DD4BAA3945DA81CDA49495E9318556D7`; `models.py` `B568190ED5511EF2FC87F1AF34A50E3341F82AAF289BC6452F213ED91CE758C7`; `run.py` `7D6340B38A4670553BD899AE9248AC65DB3E7FEAEAD0A4387CC15C5A2A620949`.

Verdict: **MIXED**. `harness.py`, `models.py`, and `run.py` exist but are not the supplied latest contents; `calib.py`, `experiments.py`, `bike.py` are missing. The latest status of `__init__.py`, `data.py`, `features.py` is NOT CHECKED because no expected hashes/markers were supplied. Optional `ride_qa.py` is absent. Search for `NEW_idr_*.py`, `calib.py`, `bike.py`, `experiments.py` under Downloads and all of MaverickDRMap returned `NO MATCHES` (locked folder excluded). Only root/unclassified `.py` found was `your_script.py`; no unclassified top-level folders.

Additional `idr\__pycache__` files:

| File | Bytes | Modified |
|---|---:|---|
| `__init__.cpython-313.pyc` | 500 | 2026-09-25 03:36:46 |
| `data.cpython-313.pyc` | 15,883 | 2026-09-25 03:36:48 |
| `features.cpython-313.pyc` | 7,269 | 2026-09-25 03:41:36 |
| `harness.cpython-313.pyc` | 6,898 | 2026-09-25 03:41:36 |
| `models.cpython-313.pyc` | 4,617 | 2026-09-25 03:41:36 |
| `run.cpython-313.pyc` | 27,186 | 2026-09-25 03:41:32 |

## 4. Data

`IO-VNBD` and `Categorised IOVNB Dataset` exist. Outside the locked folder: `S-*.csv=71`; `V-*.csv=71`. Requested Driver A file `S (Driver A)\S1\S-S1.csv` exists, 9,631,499 bytes. First line is CSV column names, not an LFS pointer:

```text
GPS LATITUDE (degrees), GPS LONGITUDE (degrees), GPS ALTITUDE (m), GPS SPEED (Kmh), GPS ACCURACY (m), GPS ORIENTATION (°),GPS SATELLITES IN RANGE, TIME SINCE START (ms), DATE (YYYY-MO-DD HH-MI-SS_SSS), ACCELEROMETER X (m/s�) , ACCELEROMETER Y (m/s�), ACCELEROMETER Z (m/s�), GRAVITY X (m/s�), GRAVITY Y (m/s�), GRAVITY Z (m/s�), GYROSCOPE Yaw (rad/s), GYROSCOPE Pitch (rad/s), GYROSCOPE Roll (rad/s), MAGNETIC FIELD X (μT), MAGNETIC FIELD Y (μT), MAGNETIC FIELD Z (μT), ORIENTATION (Yaw) (°), ORIENTATION (Pitch) (°), ORIENTATION (Roll ) (°)
```

`data\tracks_csv` inventory:

| File | Bytes | Modified |
|---|---:|---|
| `track5.csv` | 14,812,976 | 2026-09-25 04:15:36 |
| `track6.csv` | 6,218,455 | 2026-09-25 04:15:36 |
| `track7.csv` | 23,140,300 | 2026-09-25 04:15:36 |
| `track8.csv` | 7,068,208 | 2026-09-25 04:15:36 |

Sample `track5.csv`: first line has 44 comma-separated fields; second line has 46. First line:

```text
timestamp_utc,elapsed_realtime_ns,event_timestamp_ns,event_type,latitude,longitude,altitude_m,speed_mps,bearing_deg,horizontal_accuracy_m,vertical_accuracy_m,speed_accuracy_mps,bearing_accuracy_deg,provider,satellites_visible,satellites_used,gnss_measurement,accelerometer_x,accelerometer_y,accelerometer_z,gyroscope_x,gyroscope_y,gyroscope_z,gravity_x,gravity_y,gravity_z,linear_acceleration_x,linear_acceleration_y,linear_acceleration_z,magnetic_field_x,magnetic_field_y,magnetic_field_z,rotation_vector_x,rotation_vector_y,rotation_vector_z,rotation_vector_w,game_rotation_vector_x,game_rotation_vector_y,game_rotation_vector_z,game_rotation_vector_w,yaw,pitch,roll,sensor_accuracy
```

Other direct `data` folders beyond the expected list: none. Other nested data folders were NOT separately classified by type; only aggregate data count/size above.

## 5. Models and apps

25 requested model/app artifacts were found. Every `data\speed_model\models` and `docs\speed_model` item below is dated `2026-09-25 01:07:08`; IDRNav's asset is dated `2026-09-25 01:06:56`. No such file was found in accessible IO-VNBD folders; no `.apk` was found.

| Path | Bytes | Folder |
|---|---:|---|
| `data\speed_model\models\cnn_r100_w256_deploy.meta.pt` | 2,009 | OFFGRID `data\speed_model` |
| `data\speed_model\models\cnn_r100_w256_deploy.onnx` | 230,450 | OFFGRID `data\speed_model` |
| `data\speed_model\models\cnn_r100_w256_deploy.pt` | 243,249 | OFFGRID `data\speed_model` |
| `data\speed_model\models\cnn_r100_w256_deploy.tflite` | 239,688 | OFFGRID `data\speed_model` |
| `data\speed_model\models\cnn_r100_w256_joint_deploy.meta.pt` | 2,045 | OFFGRID `data\speed_model` |
| `data\speed_model\models\cnn_r100_w256_joint_deploy.onnx` | 230,450 | OFFGRID `data\speed_model` |
| `data\speed_model\models\cnn_r100_w256_joint_deploy.pt` | 243,541 | OFFGRID `data\speed_model` |
| `data\speed_model\models\cnn_r100_w256_joint_deploy.tflite` | 239,688 | OFFGRID `data\speed_model` |
| `data\speed_model\models\cnn_r100_w256_r1deploy.meta.pt` | 2,021 | OFFGRID `data\speed_model` |
| `data\speed_model\models\cnn_r100_w256_r1deploy.pt` | 243,389 | OFFGRID `data\speed_model` |
| `data\speed_model\models\cnn_r100_w256_r2deploy_s1.meta.pt` | 2,039 | OFFGRID `data\speed_model` |
| `data\speed_model\models\cnn_r100_w256_r2deploy_s1.onnx` | 230,450 | OFFGRID `data\speed_model` |
| `data\speed_model\models\cnn_r100_w256_r2deploy_s1.pt` | 243,503 | OFFGRID `data\speed_model` |
| `data\speed_model\models\cnn_r100_w256_r2deploy_s1.tflite` | 239,688 | OFFGRID `data\speed_model` |
| `data\speed_model\models\cnn_r100_w256_r2deploy_s2.meta.pt` | 2,039 | OFFGRID `data\speed_model` |
| `data\speed_model\models\cnn_r100_w256_r2deploy_s2.pt` | 243,503 | OFFGRID `data\speed_model` |
| `data\speed_model\models\cnn_r100_w256_r2deploy.meta.pt` | 2,021 | OFFGRID `data\speed_model` |
| `data\speed_model\models\cnn_r100_w256_r2deploy.onnx` | 230,450 | OFFGRID `data\speed_model` |
| `data\speed_model\models\cnn_r100_w256_r2deploy.pt` | 243,389 | OFFGRID `data\speed_model` |
| `data\speed_model\models\cnn_r100_w256_r2deploy.tflite` | 239,688 | OFFGRID `data\speed_model` |
| `docs\speed_model\split_S1.pkl` | 1,416,590 | `docs\speed_model` |
| `docs\speed_model\split_S2.pkl` | 1,426,197 | `docs\speed_model` |
| `docs\speed_model\split_S3a.pkl` | 1,431,991 | `docs\speed_model` |
| `docs\speed_model\split_S3c.pkl` | 1,429,684 | `docs\speed_model` |
| `IDRNav\app\src\main\assets\model\cnn_r100_w256_joint_deploy.tflite` | 239,688 | IDRNav asset |

`IDRNav` top level: `.gradle`, `app`, `build`, `engine`, `gradle`, `.gitignore`, `build.gradle.kts`, `gradle.properties`, `gradlew`, `gradlew.bat`, `settings.gradle.kts`. `IDRLogger`: `.gradle`, `app`, `gradle`, `.gitignore`, `build.gradle.kts`, `gradle.properties`, `gradlew`, `gradlew.bat`, `settings.gradle.kts`. Manifests found only at `IDRNav\app\src\main\AndroidManifest.xml` and `IDRLogger\app\src\main\AndroidManifest.xml`; Gradle files also include `IDRNav\engine\build.gradle.kts`. No README under either app and no Android app/manifest outside those named apps. Unknown-app description: NOT APPLICABLE (none found).

## 6. Recent results

Recent result/status/report files newer than 2026-09-20, plus the two explicitly requested files:

`docs` subfolders, each modified 2026-09-26: `audit`, `baseline`, `diagrams`, `figures`, `idr`, `qa_app`, `research`, `screens`, `slides`, `speed_model`.

Direct `docs` Markdown files and modification times:

```text
00_PLAN_prototype.md | 2026-09-25 01:07:08
01_APP_SPEC_logger.md | 2026-09-25 01:07:08
02_ENGINE_architecture.md | 2026-09-25 01:07:08
03_REFERENCES.md | 2026-09-25 01:07:08
04_BUILD_BRIEF_logger_apk.md | 2026-09-25 01:07:08
05_PILOT_QA_20260904.md | 2026-09-25 01:07:08
06_BASELINE_run3.md | 2026-09-25 01:07:08
07_ML_RECTIFY_pilot.md | 2026-09-25 01:07:08
08_ROUTE2_QA_and_plan.md | 2026-09-25 01:07:08
09_ROUTE1_verdict_and_next_data.md | 2026-09-25 01:07:08
10_ENGINE_BUILD_BRIEF.md | 2026-09-25 01:07:08
11_ENGINE_RESULTS.md | 2026-09-25 01:07:08
12_ENGINE_SPEC_for_kotlin.md | 2026-09-25 01:07:08
13_ROUTE2_as_ridden_QA.md | 2026-09-25 01:07:08
14_APP_PLAN_nav.md | 2026-09-25 01:07:08
15_NAV_APP_BUILD_BRIEF.md | 2026-09-25 01:07:08
16_APP_RESULTS.md | 2026-09-25 01:07:08
EXPERIMENTS.md | 2026-09-25 06:04:26
README.md | 2026-09-25 01:07:08
MAVERICK_STATE_REPORT.md | 2026-09-26 (this report, created after the reports were enumerated)
```

| File | Modified | Headline numbers (quoted) |
|---|---|---|
| `docs\idr\RESULTS.md` | 2026-09-25 03:45:00 | `Drives: USABLE 4, SPEED-ONLY 31, EXCLUDED 36.` S1, 30 s: `n=291`, median drift `17.91%`, p90 `38.75%`, worst `223.03%`, `20.6%` under 10%, median speed MAE `1.95 m/s`. |
| `docs\EXPERIMENTS.md` | 2026-09-25 06:04:26 | S1 30 s row: `291 | 18.02 | 37.97 | 21.0 | 7.7 | 1.99`; S1g 30 s: `291 | 17.86 | 39.73 | 20.3 | 7.7 | 1.97` (columns after method/T: n, median %, p90 %, under-10 %, heading median deg, speed MAE). |
| `docs\11_ENGINE_RESULTS.md` | 2026-09-25 01:07:08 | Route 1 joint general `2.58 / 6.82 / 20`; route 2 corridor LORO `1.52 / 5.73 / 10` (median / worst drift % / under-10% count at 30 s); route 2 replay `2.43 / 6.40 / 10 of 10`, endpoint `18 m` over ~800 m. |
| `docs\16_APP_RESULTS.md` | 2026-09-25 01:07:08 | PASS: route-2 max Δpos `0.007 m`, heading `0.154°`, modes `0.00 s`; route 1 `0.006 m`, `0.047°`, `0.00 s`. Bench `629 s`, `418.85 Hz`, `0 gaps, 0 dropped rows`, tick `0.83 ms mean`, map `120 fps`. |
| `docs\speed_model\SPEED_MODEL_RESULTS.md` | 2026-09-25 03:10:20 | Pre-fix false-stop: `S1 89.226%, S2 82.754%, S3a 90.022%, S3c 94.413%`; post-fix false-stop/capture: `0.194%/81.460%, 0.878%/80.334%, 0.322%/70.264%, and 2.166%/92.403%`. |

The following are the captured first 25 source lines of each requested/recent report. These are document contents, not independently reproduced results.

### `docs\idr\RESULTS.md` — first 25 lines

```text
# idr — phone-only speed methods on IO-VNBD

All numbers below are computed by `idr/run.py` in this run. Drive M is never loaded. Every method is scored by the same integrator (`idr/harness.py:integrate`) on the same outage windows (`outages.csv`).

## Data checks

- Phone GPS speed used as m/s. Median VBOX/phone speed ratio while moving, USABLE drives: S1 1.025, S2 1.011, S3a 0.999, S3c 1.003 (1.0 expected; 3.6 would mean a double conversion).
- Phone GPS speed runs 4.5 s late relative to the gyro-based alignment (median over USABLE drives).
- Drives: USABLE 4, SPEED-ONLY 31, EXCLUDED 36. Full table: `drive_status.csv`.

| drive | role | sync | lag used (s) | gyro corr | speed corr | hours | km |
|---|---|---|---:|---:|---:|---:|---:|
| S2 | USABLE | gyro | -8.7 | 0.918 | 0.961 | 2.61 | 75.5 |
| S3c | USABLE | gyro | -0.5 | 0.996 | 0.980 | 1.03 | 43.7 |
| S1 | USABLE | gyro | -0.2 | 0.948 | 0.929 | 1.44 | 37.9 |
| S3a | USABLE | gyro | 6.7 | 0.971 | 0.967 | 0.68 | 25.9 |
| Vw4 | SPEED-ONLY | speed-corrected | -0.4 | -0.026 | 0.986 | 3.51 | 214.4 |
| Vfa02 | SPEED-ONLY | speed-corrected | -8.3 | 0.022 | 0.989 | 1.88 | 163.1 |
| Vtb5 | SPEED-ONLY | speed-corrected | 26.4 | 0.188 | 0.988 | 1.78 | 111.1 |
| Vw2 | SPEED-ONLY | speed-corrected | -2.4 | 0.267 | 0.983 | 1.46 | 98.6 |
| Y1 | SPEED-ONLY | speed-corrected | -114.8 | 0.787 | 0.937 | 1.92 | 58.5 |
| Vw14b | SPEED-ONLY | speed-corrected | -3.8 | 0.054 | 0.991 | 0.54 | 41.1 |
| Vta1a | SPEED-ONLY | speed-corrected | 9.8 | 0.239 | 0.997 | 0.71 | 40.6 |
| Vta29 | SPEED-ONLY | speed-corrected | 4.3 | 0.224 | 0.961 | 0.66 | 26.0 |

(first 12 rows; see `drive_status.csv` for all)
```

### `docs\EXPERIMENTS.md` — first 25 lines

```text
# Online self-calibration experiments (IO-VNBD, held-out USABLE drives)

All numbers computed by `idr/experiments.py`. Calibration uses only phone data from before each outage.

## speed

| method | T (s) | n | drift median % | p90 % | % under 10 % | heading err median deg | speed MAE m/s |
|---|---:|---:|---:|---:|---:|---:|---:|
| B1 | 30 | 291 | 26.69 | 99.11 | 19.6 | 7.7 | 3.04 |
| B1 | 60 | 289 | 29.12 | 80.91 | 13.5 | 17.2 | 3.31 |
| B1 | 120 | 285 | 36.60 | 74.24 | 10.5 | 31.6 | 3.91 |
| S0c | 30 | 291 | 22.42 | 61.97 | 15.1 | 7.7 | 2.62 |
| S0c | 60 | 289 | 22.93 | 45.92 | 15.9 | 17.2 | 2.61 |
| S0c | 120 | 285 | 26.51 | 53.04 | 15.8 | 31.6 | 2.57 |
| S1 | 30 | 291 | 18.02 | 37.97 | 21.0 | 7.7 | 1.99 |
| S1 | 60 | 289 | 20.38 | 36.85 | 16.6 | 17.2 | 2.00 |
| S1 | 120 | 285 | 23.33 | 46.95 | 15.4 | 31.6 | 2.05 |
| S1g | 30 | 291 | 17.86 | 39.73 | 20.3 | 7.7 | 1.97 |
| S1g | 60 | 289 | 19.57 | 37.61 | 18.0 | 17.2 | 1.96 |
| S1g | 120 | 285 | 23.71 | 46.81 | 16.5 | 31.6 | 2.07 |
| S4 | 30 | 291 | 18.16 | 41.09 | 21.3 | 7.7 | 2.09 |
| S4 | 60 | 289 | 19.90 | 40.00 | 16.3 | 17.2 | 2.10 |
| S4 | 120 | 285 | 23.50 | 46.73 | 17.5 | 31.6 | 2.14 |

## heading|oracle speed
```

### `docs\11_ENGINE_RESULTS.md` — first 25 lines

```text
# Engine results — route 1 first (living document; updated after every build step)

Brief: `docs/10_ENGINE_BUILD_BRIEF.md`. Every number below is from held-out runs unless it is a data statistic. Research pages land in `docs/research/`.

## Status (final for routes 1 and 2, 5 Sep 2026 — the route-1 numbers in this table are re-measured after the two corrections found through route 2: the label-lag sign and the heading bias state; the original route-1 sections below are kept as the record)

| Step | Module | Status | Key result (held-out runs only) |
|---|---|---|---|
| 1 | `engine/qa.py`, `engine/session.py` | done, both routes | Route 1: 21 runs, 20 kept (run 8 aborted). Route 2: 12 runs, 10 kept (runs 1 and 7 false starts, 0 s ride); mount pitch sd 0.24° on runs 3–12, run 2's start stand contaminated (18° re-seat, 1.9 °/s bias) and handled. |
| 2 | `engine/align.py` | done, both routes | Sway-axis forward direction: 2.1° spread on route 1, 1.0° on route 2; ride-time levelling absorbs run 2's 18° stand offset; moved-phone monitor silent on route 2. |
| 3 | `engine/heading.py` | done, changed | Bias state dropped: `kfpsic` (ψ from the position course, robust stand bias held) is 28/30 runs under 3° at the 30 s cut over both routes (worst 4.1°); the v1 `kfc` state cost up to 20° on route 2's 200 s outages. Causal fallback for a moved stand measured (60 s to recover). |
| 4 | `engine/speed_model/` | done, corrected | Label lag re-measured (0.2 s, both routes) and its sign fixed (v1 model output was 0.8 s late). Joint model `cnn_r100_w256_joint` held out by pair: MAE 0.179 m/s on route 1 (route-1-only 0.195), 0.164 on route 2 (route-2-only LORO 0.172, transfer 0.228, GBR 0.212); 0 false stops on 30 runs; stop rule fires 0.7 s after rolling ends. |
| 5 | `engine/filter.py` (scale state k) | closed | Off on bicycle roads in both modes (30 runs: costs 0.3–3 % in the general mode, flips corridor runs both ways; Doppler labels within 0.5 % of position distance, so no scale to learn). On only for a grossly biased external source (IO-VNBD 24 → 14 %). |
| 6 | `engine/filter.py`, `engine/corridor.py` | done, both routes | 30 s cut, drift median / worst / under 10 %: route 1 (joint model) general 2.58 / 6.82 / 20, corridor 4.31 / 7.13 / 20; route 2 (800 m outages; route-2 corridor model, LORO) corridor 1.52 / 5.73 / 10, general 1.73 / 6.86 / 10; joint set under the same LORO 2.50 / 7.95 / 10; route 1 → route 2 transfer corridor 1.74 / 5.24 / 10. `LAG_V` 0.7 → 0.2 s (neutral). Route-2 corridor polyline validated on all runs, 20 m overhang added. |
| 7 | `engine/mapmatch.py` | display only | Unchanged (feedback rejected on route 1). |
| 8 | `engine/replay.py` | done, both routes | Route 2 corridor mode, all runs: 30 s cut 2.43 / 6.40 / 10 of 10, 18 m at the end of ~800 m. Conformance references regenerated for route-2 run 4 (2.62 %) and route-1 run 1 (2.92 %). |
| 9 | `docs/12_ENGINE_SPEC_for_kotlin.md` | v2 | Heading without bias state + stand rule, LAG_V 0.2, k off, label-lag constant, route-2 corridor, joint model section, two conformance references, v1 → v2 changelog. |
| 4-export | `engine/export/export.py` | done | Joint model: ONNX 230 KB / TFLite 240 KB, both within 1.9e-6 of PyTorch, XNNPACK-only ops, 0.07 ms per window on the Mac; phone latency still to be measured in the APK. |
| 10 | `engine/iovnbd.py` | done | Unchanged. |
| 11 | route 2 | **done** | QA → align → heading → LORO → transfer both ways → joint model → like-for-like LORO → engine and replay on route 2 → demo road = **route 2** (corridor mode 1.52 % / 5.73 % over 800 m with the route-2 corridor model against route 1's 4.31 / 7.13 over 200 m; four times the outage length through a 120° turn). Deployment: joint model everywhere, route-2 corridor model (seed-1 instance, acceptance-checked) on the demo corridor. Rider-held-out impossible (all sessions `rider_1`). |

### Targets vs achieved (held out; route 1 with the joint model by pair, route 2 with its corridor model under leave-one-run-out; engine with the v2 heading)

| Target | Route 1 (20 runs) | Route 2 (10 runs, outages 4× longer) |
|---|---|---|
```

### `docs\16_APP_RESULTS.md` — first 25 lines

```text
# 16 — IDR Nav: build results, measurements, deviations, field protocol

Built 5 Sep 2026 (16:20–22:30 IST) from `docs/15_NAV_APP_BUILD_BRIEF.md`. Everything below was measured on this Mac (M2 Pro, JDK 21) and on the connected Samsung Galaxy S23 Ultra (SM-S918B, Android 16) unless a line says otherwise. Illustrative values are labelled as such; there are none in the tables.

## 0. Status against the acceptance list (handoff §11)

| # | Acceptance | Result | Where |
|---|---|---|---|
| 11.1 | JVM conformance on both reference sessions: max Δpos < 1.0 m, modes ±0.2 s, heading < 1°, model speed < 0.02 m/s | **PASS.** Route-2 run 4: max Δpos **0.007 m**, heading **0.154°**, modes **0.00 s**. Route-1 run 1: **0.006 m**, **0.047°**, **0.00 s**. Kotlin CNN vs PyTorch on 500 real windows: max Δμ **1.2e-6**, Δv **1.2e-6 m/s** | §3.1, §3.2; `./gradlew :engine:test` |
| 11.2 | LiteRT = Kotlin CNN within 1e-4 on 500 windows; < 5 ms per window on the phone, both paths | **PASS.** max LiteRT − Kotlin **1.9e-6**; LiteRT **0.074 ms** median / 0.086 p95; Kotlin CNN **1.41 ms** median / 1.71 p95 (SM-S918B, 1 thread) | §4 |
| 11.3 | Device replay of route-2 run 4 reproduces the JVM run within 0.5 m; band, dead reckoning and reveal shown with the right figure | **PASS.** Device (LiteRT) vs JVM (Kotlin CNN): max Δpos **0.001 m**, heading 0.000°, Δv 0.001 m/s over 2977 ticks; band, dashed trail + ribbon, reveal "Off by 17.3 m after 569 m without GNSS" on screen | §3.3, §6 screenshots |
| 11.4 | Live bench 10 min: acc/gyr ≥ 400 Hz, zero dropped rows, tick ≤ 2 ms mean, UI ≥ 55 fps, no ANR, service survives screen-off | **PASS** on the desk: 629 s, acc/gyr **418.85 Hz**, **0 gaps, 0 dropped rows**, tick **0.83 ms mean** (p99 1.35, max 4.05 ms), map **120 fps** / Compose 115 fps with the map moving, thermal status 0, no ANR; screen-off ride logged through 75 s of doze without a gap. GNSS gave no fix indoors (see §7) | §7 |
| 11.5 | Scenario gate on the raw fix; real-outage detector still fires; recovery inflation + 3 s RECOVERING; trail never rewritten | **PASS** (JVM tests `BandScenarioTest`, `RealOutageTest`): band withheld at 96.1 s while armed; a forced 10 s fix gap fires REAL_OUTAGE at 100.6 s without the SIM flag, RECOVERING 111.1→114.1 s (3.0 s), reveal after the real gap "off 3.3 m after 41 m"; the trail is only ever appended | §5 |
| 11.6 | Corridor recognised within 10 s of motion start with the right direction; general mode outside the library | **PASS.** Route 2: corridor at t = 8.1 s (during the stand), direction A→B at 37.1 s = motion start + 1.0 s; route 1: corridor at 7.3 s, direction at motion start + 1.0 s (device and JVM identical). Route-1 session against a library holding only route 2: stays `general` for the whole ride, 13 % of ticks map-matched with confidence | §5 |
| 11.7 | Nav ride writes the 22 logger streams + `engine_out.csv` + `engine_events.csv`, `route_id` set, summary, zip/share | **PASS**: 24 files per ride (21 present logger streams — geomagrot does not exist on this phone — + engine_out + engine_events + session.json), `route_id`/`direction`/`speed_model` in session.json, summary screen with outages and files, Zip + share through the FileProvider | §7 |
| 11.8 | Design pass on the device, dark theme + font scale 1.3, screenshots in `docs/screens/`, puck chosen and justified, no clipping, contrast, 48 dp targets | **DONE.** 26 screenshots; puck = Blade (§6.2); font scale 1.3 wraps without clipping; one label/value spacing fix applied afterwards | §6 |
| 11.9 | Satellite online with attribution; airplane mode + Location on still positions | Satellite **PASS** (Esri imagery renders under our overlays, attribution in Settings and on the map). Airplane mode: **inconclusive indoors** (no GNSS fix at the desk before, during or after the 64 s window); the app requests `GPS_PROVIDER` only, so it must be confirmed on the first field ride | §6, §7 |
| 11.10 | This document | this file | — |

Everything in the handoff was built. Two things are honestly weaker than the brief's wording and are explained in §8: the raw phone path differs from the Python reference by a few metres because the reference used a batch alignment (the maths path matches to 7 mm), and the brief's prediction files were not the ones that produced the reference CSVs.

## 1. What was built

```
IDRNav/                              Gradle 8.14.3 · AGP 8.13.2 · Kotlin 2.2.10 · compileSdk 36 · minSdk 29 · applicationId com.snu.idrlogger (unchanged)
```
```

### `docs\speed_model\SPEED_MODEL_RESULTS.md` — first 25 lines

```text
# IO-VNBD phone-only speed model results

## Pre-fix diagnostics (before LightGBM rebuild)
- Label check: S1 8.466 vs 8.463 m/s; S2 8.824 vs 8.614 m/s; S3a 11.589 vs 11.592 m/s. Units matched.
- Stop rule: false-stop rates were S1 89.226%, S2 82.754%, S3a 90.022%, S3c 94.413%; this was a root cause. True-stop capture was 99.145% or higher.
- Held-out S1 prediction distribution: predicted 5/25/50/75/95 = 13.105/13.105/13.105/13.105/13.105 m/s versus true 2.685/5.900/8.438/11.214/14.393 m/s; constant output was a root cause.
- Feature sanity: S1/S2/S3a were phone-S features, 0.000% NaN and 0.000% constant columns.
- Context sanity: last phone-GPS speed equaled the phone speed at each outage start on S1/S2/S3a/S3c.

The root causes were the false-stop gate, constant stump predictions, and incorrect context-age construction. They were fixed before the measurements below.

## Post-fix stop-rule check
The fitted LightGBM stop classifier is trained only on training drives. On S1/S2/S3a/S3c, false-stop/capture rates are 0.194%/81.460%, 0.878%/80.334%, 0.322%/70.264%, and 2.166%/92.403%; S3c's held-out false-stop rate is a noted residual generalization miss, while training selection enforces <2%.

LightGBM Gaussian regressors use residual formulation R and absolute formulation A. S0 uses A IMU-only speed; S1 uses R with stale speed-history context; S2 adds the fitted-variance speed Kalman filter.

## Training
- Inputs: all phone accelerometer, gravity, and gyroscope axes from S files.
- Labels: re-synced VBOX speed in m/s.
- M was never loaded for training or scoring.
- Split artifacts are leave-one-scored-drive-out and use only training-drive normalization, thresholds, validation, and process noise.

## Metrics
Cells are median / p90 / worst. Drift is endpoint error divided by true distance.
Changed means true speed range during the outage exceeds 10 km/h.
```

These are report claims, not independently reproduced measurements.

## 7. Environment

```text
Python 3.13.5
C:\Users\STUDENT\AppData\Local\Programs\Python\Python313\python.exe
C:\Users\STUDENT\AppData\Local\Microsoft\WindowsApps\python.exe
```

Versions: `numpy 2.3.5`, `pandas 2.3.3`, `scipy 1.16.3`, `sklearn 1.7.2`. `lightgbm` import failed: `ModuleNotFoundError: No module named 'lightgbm'`. Nothing was installed. Drive `C:\` free bytes `131908366336`, total bytes `1023150125056`.

## 8. Surprises

- Repository identity is OFFGRID, not MaverickGRID: README and LICENSE name Team OFFGRID.
- `idr` is incomplete vs supplied latest criteria; all source markers are absent and latest `calib.py`, `experiments.py`, `bike.py` were not found elsewhere.
- `idr` contains six Python 3.13 bytecode cache files.
- `data\tracks_csv` contains `track5.csv`–`track8.csv`; track5 header/first row field counts are 44/46.
- `docs\idr\RESULTS.md` and `docs\EXPERIMENTS.md` exist and contain IDR results claims dated 2026-09-25; not independently rerun.
- `M (Driver B)` was not inspected. Its file count/size/contents and matching artifact presence are NOT CHECKED by design.
- `lightgbm` is unavailable in the selected `python`; no install/environment change was made.
- No project file other than this report was created or modified. A2 totals precede report creation.

## 9. Commands run

Shell was PowerShell; project-relative commands ran from `C:\Users\STUDENT\Desktop\MaverickDRMap\Maverick`. Only project write: this report. Locked folder was excluded from recursive traversals; only the named Driver A CSV first line was opened among IO-VNBD data.

```powershell
Get-ChildItem -Force | Select-Object Mode, LastWriteTime, Length, Name
git status --short | Select-Object -First 40
git remote -v
git log --oneline -15
git log -1 --format="%an %ad"
Get-Content README.md -TotalCount 15
Get-Content LICENSE -TotalCount 3
Get-ChildItem idr -Force -Recurse -File | Sort-Object FullName
Get-FileHash idr\*.py -Algorithm SHA256
Select-String -Path idr\models.py -Pattern 'deterministic=True' -SimpleMatch
Select-String -Path idr\harness.py -Pattern 'yaw_rate' -SimpleMatch
Select-String -Path idr\run.py -Pattern 'prior_var' -SimpleMatch
Get-ChildItem data\tracks_csv -File | Sort-Object Name
Get-ChildItem docs -File -Filter *.md | Sort-Object Name
Get-ChildItem docs -Directory
Get-ChildItem docs -Recurse -File -Filter *.md
$n=0; Get-Content $file.FullName | ForEach-Object { if ($n -lt 25) { '{0,2}: {1}' -f ($n+1), $_; $n++ } }
Get-ChildItem IDRNav -Force
Get-ChildItem IDRLogger -Force
Get-ChildItem $root.FullName -Recurse -File -ErrorAction SilentlyContinue | Where-Object { $_.Extension -in $patterns }
python --version
where.exe python
python -c "import numpy, pandas, scipy, sklearn, lightgbm; print(numpy.__version__, pandas.__version__, scipy.__version__, sklearn.__version__, lightgbm.__version__)"
python -c "import importlib; names=['numpy','pandas','scipy','sklearn','lightgbm']; [(lambda n: print(n, importlib.import_module(n).__version__))(name) for name in names]"
```

Custom safe recursive PowerShell enumeration (top-level totals, file-name search, IO-VNBD counts) used a stack and skipped this exact path before listing children: `C:\Users\STUDENT\Desktop\MaverickDRMap\Maverick\IO-VNBD\Synchronised V abd S datasets\Categorised IOVNB Dataset\M (Driver B)`. Candidate names searched were `NEW_idr_*.py`, `calib.py`, `bike.py`, `experiments.py`. Hashes used `Get-FileHash`; newline check normalized CRLF/CR to LF in memory then SHA256-hashed UTF-8 bytes. `StreamReader.ReadLine()` read the one allowed sample CSV line.

The first broad artifact scan was interrupted (`^C`) and replaced with scans excluding `.git`/IO-VNBD plus a safe IO-VNBD scan. An initial recursive `data` subfolder output was narrowed to direct children. Two malformed hash-loop attempts returned `ParserError`; the subsequent absolute-path hash check succeeded. These attempts made no file changes.