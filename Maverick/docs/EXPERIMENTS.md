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

| method | T (s) | n | drift median % | p90 % | % under 10 % | heading err median deg | speed MAE m/s |
|---|---:|---:|---:|---:|---:|---:|---:|
| H0 raw gyro | 30 | 291 | 5.70 | 16.89 | 70.5 | 7.7 | 0.00 |
| H0 raw gyro | 60 | 289 | 9.57 | 25.54 | 50.9 | 17.2 | 0.00 |
| H0 raw gyro | 120 | 285 | 14.42 | 43.37 | 41.0 | 31.6 | 0.00 |
| Hb course bias | 30 | 291 | 4.32 | 13.35 | 81.1 | 5.5 | 0.00 |
| Hb course bias | 60 | 289 | 7.33 | 19.95 | 65.4 | 10.2 | 0.00 |
| Hb course bias | 120 | 285 | 9.87 | 32.06 | 50.5 | 20.2 | 0.00 |
| Hc course bias+scale | 30 | 291 | 8.48 | 25.39 | 58.1 | 9.4 | 0.00 |
| Hc course bias+scale | 60 | 289 | 13.72 | 32.62 | 36.7 | 13.9 | 0.00 |
| Hc course bias+scale | 120 | 285 | 18.43 | 46.15 | 22.5 | 23.9 | 0.00 |
| Hs stop bias | 30 | 291 | 4.52 | 12.96 | 81.4 | 5.0 | 0.00 |
| Hs stop bias | 60 | 289 | 6.76 | 18.29 | 64.7 | 8.8 | 0.00 |
| Hs stop bias | 120 | 285 | 8.36 | 31.58 | 55.8 | 15.9 | 0.00 |
| Ht trust (course fit, else hold course) | 30 | 291 | 12.45 | 71.38 | 44.3 | 14.7 | 0.00 |
| Ht trust (course fit, else hold course) | 60 | 289 | 19.23 | 100.94 | 27.7 | 19.2 | 0.00 |
| Ht trust (course fit, else hold course) | 120 | 285 | 25.68 | 102.52 | 17.2 | 30.2 | 0.00 |

## heading|S1 speed

| method | T (s) | n | drift median % | p90 % | % under 10 % | heading err median deg | speed MAE m/s |
|---|---:|---:|---:|---:|---:|---:|---:|
| H0 raw gyro | 30 | 291 | 18.02 | 37.97 | 21.0 | 7.7 | 1.99 |
| H0 raw gyro | 60 | 289 | 20.38 | 36.85 | 16.6 | 17.2 | 2.00 |
| H0 raw gyro | 120 | 285 | 23.33 | 46.95 | 15.4 | 31.6 | 2.05 |
| Hb course bias | 30 | 291 | 17.59 | 37.20 | 24.4 | 5.5 | 1.99 |
| Hb course bias | 60 | 289 | 18.48 | 34.30 | 20.1 | 10.2 | 2.00 |
| Hb course bias | 120 | 285 | 19.45 | 40.11 | 21.8 | 20.2 | 2.05 |
| Hc course bias+scale | 30 | 291 | 19.28 | 42.53 | 19.6 | 9.4 | 1.99 |
| Hc course bias+scale | 60 | 289 | 22.47 | 48.10 | 13.8 | 13.9 | 2.00 |
| Hc course bias+scale | 120 | 285 | 24.38 | 52.03 | 11.2 | 23.9 | 2.05 |
| Hs stop bias | 30 | 291 | 17.36 | 37.19 | 27.5 | 5.0 | 1.99 |
| Hs stop bias | 60 | 289 | 17.05 | 34.48 | 23.2 | 8.8 | 2.00 |
| Hs stop bias | 120 | 285 | 18.65 | 38.00 | 23.5 | 15.9 | 2.05 |
| Ht trust (course fit, else hold course) | 30 | 291 | 26.41 | 88.20 | 13.1 | 14.7 | 1.99 |
| Ht trust (course fit, else hold course) | 60 | 289 | 28.66 | 103.52 | 9.0 | 19.2 | 2.00 |
| Ht trust (course fit, else hold course) | 120 | 285 | 33.27 | 113.35 | 8.4 | 30.2 | 2.05 |

## heading|oracle speed|UNUSABLE-gyro drives

| method | T (s) | n | drift median % | p90 % | % under 10 % | heading err median deg | speed MAE m/s |
|---|---:|---:|---:|---:|---:|---:|---:|
| H0 raw gyro | 30 | 676 | 19.17 | 60.14 | 26.0 | 23.1 | 0.00 |
| H0 raw gyro | 60 | 669 | 29.41 | 82.68 | 16.6 | 34.1 | 0.00 |
| H0 raw gyro | 120 | 659 | 44.25 | 101.73 | 7.4 | 51.0 | 0.00 |
| Ht trust (course fit, else hold course) | 30 | 676 | 11.35 | 46.28 | 46.0 | 12.9 | 0.00 |
| Ht trust (course fit, else hold course) | 60 | 669 | 18.05 | 64.22 | 30.5 | 18.9 | 0.00 |
| Ht trust (course fit, else hold course) | 120 | 659 | 24.77 | 83.49 | 23.5 | 22.6 | 0.00 |
| hold last GPS course | 30 | 676 | 11.22 | 48.48 | 46.3 | 13.2 | 0.00 |
| hold last GPS course | 60 | 669 | 18.30 | 67.86 | 30.9 | 19.1 | 0.00 |
| hold last GPS course | 120 | 659 | 25.29 | 83.49 | 23.5 | 22.3 | 0.00 |

## Calibration estimates (held-out USABLE drives)

- course-fit pairs per outage: median 60; correlation r median 0.611; scale median 0.800; bias median -0.155 deg/s
- stop-based bias available on 98 % of outages; median -0.252 deg/s
- gyro trusted on 55.4 % of outages; speed-band calibration pairs median 86
