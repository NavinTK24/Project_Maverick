# Edge engine on the external IMU (IO-VNBD vehicle unit; no wheel speed / speedometer)

| method | T (s) | n | drift median % | p90 % | % under 10 % | endpoint median m | heading err deg | speed MAE m/s |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| B1 hold speed, raw yaw | 30 | 291 | 20.14 | 69.74 | 25.8 | 54.2 | 2.5 | 2.63 |
| B1 hold speed, raw yaw | 60 | 290 | 24.01 | 76.73 | 17.6 | 124.0 | 4.3 | 3.13 |
| B1 hold speed, raw yaw | 120 | 286 | 24.47 | 62.27 | 14.3 | 250.8 | 7.8 | 3.72 |
| B2 true speed, stop-bias yaw | 30 | 291 | 2.18 | 6.99 | 94.5 | 5.7 | 2.8 | 0.00 |
| B2 true speed, stop-bias yaw | 60 | 290 | 3.66 | 9.98 | 90.0 | 16.8 | 4.9 | 0.00 |
| B2 true speed, stop-bias yaw | 120 | 286 | 4.93 | 15.74 | 75.9 | 51.7 | 9.0 | 0.00 |
| E1 learned speed, stop-bias yaw | 30 | 291 | 12.46 | 41.63 | 40.5 | 31.8 | 2.8 | 1.83 |
| E1 learned speed, stop-bias yaw | 60 | 290 | 12.63 | 35.85 | 35.5 | 63.9 | 4.9 | 2.02 |
| E1 learned speed, stop-bias yaw | 120 | 286 | 13.69 | 27.82 | 31.1 | 124.0 | 9.0 | 2.13 |
| E2 learned speed, raw yaw | 30 | 291 | 11.14 | 31.71 | 45.7 | 28.9 | 2.5 | 1.49 |
| E2 learned speed, raw yaw | 60 | 290 | 11.48 | 29.84 | 43.8 | 57.0 | 4.3 | 1.72 |
| E2 learned speed, raw yaw | 120 | 286 | 12.28 | 24.97 | 40.9 | 118.8 | 7.8 | 1.94 |
| E2 learned speed, stop-bias yaw | 30 | 291 | 11.53 | 32.32 | 45.7 | 28.7 | 2.8 | 1.49 |
| E2 learned speed, stop-bias yaw | 60 | 290 | 11.99 | 30.44 | 40.7 | 60.1 | 4.9 | 1.72 |
| E2 learned speed, stop-bias yaw | 120 | 286 | 12.77 | 26.00 | 35.0 | 128.8 | 9.0 | 1.94 |