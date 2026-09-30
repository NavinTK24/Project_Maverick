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

## Methods

- **B1**: gyro heading + hold last phone-GPS speed (floor)
- **B2**: gyro heading + VBOX speed (oracle)
- **B3**: VBOX heading (oracle) + hold last phone-GPS speed
- **B4**: gyro heading + accelerometer-integrated speed
- **S0**: gyro heading + IMU-only learned speed (model A) + stop detector
- **S1**: gyro heading + last GPS speed + learned correction from IMU and pre-outage context (model R) + stop detector
- **S2**: gyro heading + Kalman fusion of last GPS speed (prior) with model A speed and its per-sample sigma

## Training (leave-one-drive-out over the USABLE drives)

| held-out | training drives | rows model A | rows model R | heading sign |
|---|---:|---:|---:|---:|
| S1 | 34 | 72268 | 76855 | -1 |
| S2 | 34 | 68064 | 72641 | -1 |
| S3a | 34 | 74987 | 79666 | -1 |
| S3c | 34 | 73725 | 78057 | -1 |

## Results — all outages

| method | T (s) | n | drift median % | p90 % | worst % | % under 10 % | endpoint median m | speed MAE median m/s | speed bias m/s | heading err median deg |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| B1 | 30 | 291 | 26.69 | 99.11 | 6753.47 | 19.6 | 69.8 | 3.04 | 0.46 | 7.7 |
| B1 | 60 | 289 | 29.12 | 80.91 | 1896.61 | 13.5 | 147.0 | 3.31 | 0.71 | 17.2 |
| B1 | 120 | 285 | 36.60 | 74.24 | 965.91 | 10.5 | 329.1 | 3.91 | 1.13 | 31.6 |
| B2 | 30 | 291 | 5.70 | 16.89 | 69.37 | 70.5 | 13.8 | 0.00 | 0.00 | 7.7 |
| B2 | 60 | 289 | 9.57 | 25.54 | 38.28 | 50.9 | 42.7 | 0.00 | 0.00 | 17.2 |
| B2 | 120 | 285 | 14.42 | 43.37 | 65.80 | 41.0 | 128.1 | 0.00 | 0.00 | 31.6 |
| B3 | 30 | 291 | 23.83 | 90.78 | 6737.99 | 27.5 | 59.3 | 3.04 | 0.46 | 0.0 |
| B3 | 60 | 289 | 22.59 | 77.99 | 1074.34 | 23.5 | 112.0 | 3.31 | 0.71 | 0.0 |
| B3 | 120 | 285 | 23.38 | 65.57 | 967.93 | 21.4 | 215.5 | 3.91 | 1.13 | 0.0 |
| B4 | 30 | 291 | 24.18 | 69.42 | 2002.15 | 18.6 | 62.0 | 2.55 | 0.48 | 7.7 |
| B4 | 60 | 289 | 28.89 | 69.39 | 528.81 | 11.4 | 143.5 | 3.05 | 0.79 | 17.2 |
| B4 | 120 | 285 | 35.01 | 72.85 | 647.38 | 9.5 | 327.5 | 3.90 | 0.94 | 31.6 |
| S0 | 30 | 291 | 21.70 | 49.35 | 264.76 | 17.9 | 49.2 | 2.34 | -0.36 | 7.7 |
| S0 | 60 | 289 | 22.06 | 44.67 | 90.03 | 14.2 | 101.8 | 2.32 | -0.27 | 17.2 |
| S0 | 120 | 285 | 25.68 | 51.06 | 76.74 | 15.1 | 250.7 | 2.25 | -0.41 | 31.6 |
| S1 | 30 | 291 | 18.02 | 37.97 | 223.03 | 21.0 | 42.4 | 1.99 | -0.29 | 7.7 |
| S1 | 60 | 289 | 20.38 | 36.85 | 107.73 | 16.6 | 91.5 | 2.00 | -0.26 | 17.2 |
| S1 | 120 | 285 | 23.33 | 46.95 | 91.18 | 15.4 | 218.6 | 2.05 | -0.18 | 31.6 |
| S2 | 30 | 291 | 23.73 | 48.13 | 219.93 | 17.5 | 50.1 | 2.40 | -0.63 | 7.7 |
| S2 | 60 | 289 | 22.48 | 44.35 | 92.31 | 14.2 | 104.5 | 2.38 | -0.58 | 17.2 |
| S2 | 120 | 285 | 25.66 | 49.65 | 68.45 | 13.7 | 244.9 | 2.37 | -0.67 | 31.6 |

## Results — changed (> 10 km/h speed range) vs steady

| method | T (s) | kind | n | drift median % | speed MAE median m/s |
|---|---:|---|---:|---:|---:|
| B1 | 30 | changed | 262 | 29.17 | 3.23 |
| B1 | 30 | steady | 29 | 8.78 | 0.93 |
| B1 | 60 | changed | 283 | 29.50 | 3.33 |
| B1 | 60 | steady | 6 | 5.88 | 0.86 |
| B1 | 120 | changed | 285 | 36.60 | 3.91 |
| B2 | 30 | changed | 262 | 5.89 | 0.00 |
| B2 | 30 | steady | 29 | 4.19 | 0.00 |
| B2 | 60 | changed | 283 | 9.76 | 0.00 |
| B2 | 60 | steady | 6 | 2.49 | 0.00 |
| B2 | 120 | changed | 285 | 14.42 | 0.00 |
| B3 | 30 | changed | 262 | 27.22 | 3.23 |
| B3 | 30 | steady | 29 | 5.18 | 0.93 |
| B3 | 60 | changed | 283 | 22.88 | 3.33 |
| B3 | 60 | steady | 6 | 2.28 | 0.86 |
| B3 | 120 | changed | 285 | 23.38 | 3.91 |
| B4 | 30 | changed | 262 | 27.15 | 2.74 |
| B4 | 30 | steady | 29 | 9.94 | 0.65 |
| B4 | 60 | changed | 283 | 29.15 | 3.10 |
| B4 | 60 | steady | 6 | 7.02 | 0.89 |
| B4 | 120 | changed | 285 | 35.01 | 3.90 |
| S0 | 30 | changed | 262 | 21.27 | 2.33 |
| S0 | 30 | steady | 29 | 24.42 | 3.08 |
| S0 | 60 | changed | 283 | 20.98 | 2.32 |
| S0 | 60 | steady | 6 | 51.87 | 11.57 |
| S0 | 120 | changed | 285 | 25.68 | 2.25 |
| S1 | 30 | changed | 262 | 18.03 | 2.04 |
| S1 | 30 | steady | 29 | 17.71 | 1.69 |
| S1 | 60 | changed | 283 | 20.29 | 1.99 |
| S1 | 60 | steady | 6 | 23.76 | 5.13 |
| S1 | 120 | changed | 285 | 23.33 | 2.05 |
| S2 | 30 | changed | 262 | 23.75 | 2.37 |
| S2 | 30 | steady | 29 | 23.47 | 3.13 |
| S2 | 60 | changed | 283 | 22.31 | 2.38 |
| S2 | 60 | steady | 6 | 52.06 | 11.14 |
| S2 | 120 | changed | 285 | 25.66 | 2.37 |

## Computed comparisons

- T=30 s, changed: B1 29.17 %, S0 21.27 %, S1 18.03 %, S2 23.75 %. S1 (last GPS speed + learned correction) is better than S0 (IMU only) by 3.24 points; best learned method S1 is better than B1 by 11.14 points.
- T=30 s, steady: B1 8.78 %, S0 24.42 %, S1 17.71 %, S2 23.47 %. S1 (last GPS speed + learned correction) is better than S0 (IMU only) by 6.71 points; best learned method S1 is worse than B1 by 8.93 points.
- T=30 s, all: B1 26.69 %, S0 21.70 %, S1 18.02 %, S2 23.73 %. S1 (last GPS speed + learned correction) is better than S0 (IMU only) by 3.68 points; best learned method S1 is better than B1 by 8.67 points.
- T=60 s, changed: B1 29.50 %, S0 20.98 %, S1 20.29 %, S2 22.31 %. S1 (last GPS speed + learned correction) is better than S0 (IMU only) by 0.69 points; best learned method S1 is better than B1 by 9.21 points.
- T=60 s, steady: B1 5.88 %, S0 51.87 %, S1 23.76 %, S2 52.06 %. S1 (last GPS speed + learned correction) is better than S0 (IMU only) by 28.11 points; best learned method S1 is worse than B1 by 17.88 points.
- T=60 s, all: B1 29.12 %, S0 22.06 %, S1 20.38 %, S2 22.48 %. S1 (last GPS speed + learned correction) is better than S0 (IMU only) by 1.68 points; best learned method S1 is better than B1 by 8.74 points.
- T=120 s, changed: B1 36.60 %, S0 25.68 %, S1 23.33 %, S2 25.66 %. S1 (last GPS speed + learned correction) is better than S0 (IMU only) by 2.35 points; best learned method S1 is better than B1 by 13.27 points.
- T=120 s, all: B1 36.60 %, S0 25.68 %, S1 23.33 %, S2 25.66 %. S1 (last GPS speed + learned correction) is better than S0 (IMU only) by 2.35 points; best learned method S1 is better than B1 by 13.27 points.

## Stop detector (IMU-only inputs), held-out drives

| drive | false stops while moving % | capture of true stops % |
|---|---:|---:|
| S1 | 1.58 | 89.5 |
| S2 | 1.85 | 97.3 |
| S3a | 0.00 | 94.2 |
| S3c | 2.56 | 93.0 |

## Uncertainty calibration of model A, held-out drives (target 60–75 % within ±1 sigma)

| drive | within ±1 sigma % | MAE m/s |
|---|---:|---:|
| S1 | 67.7 | 1.88 |
| S2 | 53.4 | 2.70 |
| S3a | 54.2 | 2.93 |
| S3c | 36.8 | 4.86 |

## Reproduce

```
python -m idr.run --root "<...>\Synchronised V abd S datasets\Categorised IOVNB Dataset"
```

## Console output

```text
DATA_ROOT=C:\Users\STUDENT\Desktop\MaverickDRMap\Maverick\IO-VNBD\Synchronised V abd S datasets\Categorised IOVNB Dataset
EXCLUDE Vta3: only 380 overlapping samples after a lag of -26.5 s
EXCLUDE Vta5: only 305 overlapping samples after a lag of -0.2 s
EXCLUDE Vta9: only 111 overlapping samples after a lag of -4.5 s
EXCLUDE Vta11: only 468 overlapping samples after a lag of -4.2 s
EXCLUDE Vta12: only 582 overlapping samples after a lag of 2.8 s
EXCLUDE Vta13: only 388 overlapping samples after a lag of -1.6 s
EXCLUDE Vta19: only 247 overlapping samples after a lag of -4.5 s
EXCLUDE Vtb4: only 511 overlapping samples after a lag of -4.5 s
EXCLUDE Vtb6: only 469 overlapping samples after a lag of -2.9 s
EXCLUDE Vtb7: only 440 overlapping samples after a lag of 2.1 s
EXCLUDE Vtb9: only 440 overlapping samples after a lag of 1.2 s
EXCLUDE Vtb10: only 151 overlapping samples after a lag of -4.4 s
EXCLUDE Vtb11: only 354 overlapping samples after a lag of -0.7 s
EXCLUDE Vtb12: only 404 overlapping samples after a lag of -4.3 s
EXCLUDE Vw9: only 537 overlapping samples after a lag of 1.5 s
EXCLUDE Vw13: only 249 overlapping samples after a lag of -3.5 s
EXCLUDE Vw15: only -44 overlapping samples after a lag of -143.5 s
EXCLUDE Vw17: only 284 overlapping samples after a lag of -4.5 s
USABLE=S1,S2,S3a,S3c  SPEED-ONLY=31  EXCLUDED=36  phone GPS speed late by 4.5 s
feature extraction ...
SPLIT held-out S1
  trained on 34 drives: A rows 72268, R rows 76855, stop thr 0.05 (OOF false 1.27 %, capture 95.9 %), P0 5.164, q 0.490 [35 s]
SPLIT held-out S2
  trained on 34 drives: A rows 68064, R rows 72641, stop thr 0.05 (OOF false 1.44 %, capture 94.3 %), P0 4.820, q 0.500 [32 s]
SPLIT held-out S3a
  trained on 34 drives: A rows 74987, R rows 79666, stop thr 0.25 (OOF false 1.98 %, capture 89.9 %), P0 5.205, q 0.503 [35 s]
SPLIT held-out S3c
  trained on 34 drives: A rows 73725, R rows 78057, stop thr 0.10 (OOF false 2.00 %, capture 94.0 %), P0 5.155, q 0.489 [34 s]
```
