# IO-VNBD baseline accuracy

## Protocol
- DATA_ROOT printed by this run: `D:\MaverickGRID\IO-VNBD\Synchronised V abd S datasets\Categorised IOVNB Dataset`
- S files are phone inputs only; V files are truth only; M is never scored.
- Outages: 30/60/120 s, starts every 60 s from t >= 60 s where VBOX speed > 5 km/h.
- Each outage starts from true VBOX position, speed, and heading.

## Required fact checks
| drive | lag s | correlation | usable |
| --- | ---: | ---: | --- |
| S1 | 0.2 | 0.9492 | yes |
| S2 | 195.1 | 0.9120 | yes |
| S3a | -6.7 | 0.9742 | yes |
| S3c | 0.5 | 0.9962 | yes |

Phone-speed proof (phone column is used as m/s without division):
| drive | median VBOX m/s | median phone speed used m/s | ratio |
| --- | ---: | ---: | ---: |
| S1 | 8.466 | 8.260 | 1.025 |
| S2 | 8.824 | 8.680 | 1.017 |
| S3a | 11.589 | 11.610 | 0.998 |
| S3c | 11.005 | 10.970 | 1.003 |

## Lag candidates
The three candidate rows per drive, including speed-correlation selection evidence, are printed in the reproduction output at the end of this file.
| drive | candidate lag s | gyro corr | speed corr | GPS/VBOX median m |
| --- | ---: | ---: | ---: | ---: |
| S1 | 0.2 | 0.9492 | 0.9291 | 25.87 |
| S1 | -557.5 | 0.0538 | 0.0377 | 1207.28 |
| S1 | 557.4 | 0.0530 | 0.0264 | 1219.71 |
| S2 | 195.1 | 0.9120 | 0.9479 | 30.20 |
| S2 | 7.3 | 0.0447 | 0.1108 | 780.65 |
| S2 | -441.0 | 0.0422 | 0.1740 | 2050.39 |
| S3a | -6.7 | 0.9742 | 0.9667 | 38.69 |
| S3a | -18.0 | 0.1912 | 0.9650 | 60.99 |
| S3a | 4.3 | 0.1857 | 0.7947 | 152.80 |
| S3c | 0.5 | 0.9962 | 0.9800 | 39.03 |
| S3c | 10.4 | 0.0821 | 0.9057 | 130.01 |
| S3c | -9.6 | 0.0806 | 0.9802 | 50.63 |

The categorized S2 files on this PC do not reproduce the supplied +8.7 s claim: the measured +7.3 s candidate has speed correlation 0.1108 and median GPS/VBOX distance 776.20 m, while the selected +195.1 s candidate has speed correlation 0.9479 and median distance 30.16 m. The scorer therefore retains the physically supported measured candidate and records the discrepancy rather than forcing an unsupported lag.

## S2 GPS-position proof
S2 median horizontal distance between phone GPS position and re-synced VBOX position: **30.16 m** (required < 60 m).

## Model discovery
A whole-tree search was run for IO-VNBD-related scripts and saved model extensions (`.pkl`, `.joblib`, `.pt`, `.pth`, `.h5`, `.keras`, `.onnx`, `.tflite`). No dedicated user-trained IO-VNBD model was found. The following saved artifacts were found but are repo bicycle speed-model exports, not eligible for CURRENT:
- `data\speed_model\models\cnn_r100_w256_deploy.meta.pt`; training source: `engine/speed_model/train.py`; data: repo `data/speed_model` training outputs; inputs: phone IMU features; output: speed; M use: not established as IO-VNBD and therefore not scored.
- `data\speed_model\models\cnn_r100_w256_deploy.onnx`; training source: `engine/speed_model/train.py`; data: repo `data/speed_model` training outputs; inputs: phone IMU features; output: speed; M use: not established as IO-VNBD and therefore not scored.
- `data\speed_model\models\cnn_r100_w256_deploy.pt`; training source: `engine/speed_model/train.py`; data: repo `data/speed_model` training outputs; inputs: phone IMU features; output: speed; M use: not established as IO-VNBD and therefore not scored.
- `data\speed_model\models\cnn_r100_w256_deploy.tflite`; training source: `engine/speed_model/train.py`; data: repo `data/speed_model` training outputs; inputs: phone IMU features; output: speed; M use: not established as IO-VNBD and therefore not scored.
- `data\speed_model\models\cnn_r100_w256_joint_deploy.meta.pt`; training source: `engine/speed_model/train.py`; data: repo `data/speed_model` training outputs; inputs: phone IMU features; output: speed; M use: not established as IO-VNBD and therefore not scored.
- `data\speed_model\models\cnn_r100_w256_joint_deploy.onnx`; training source: `engine/speed_model/train.py`; data: repo `data/speed_model` training outputs; inputs: phone IMU features; output: speed; M use: not established as IO-VNBD and therefore not scored.
- `data\speed_model\models\cnn_r100_w256_joint_deploy.pt`; training source: `engine/speed_model/train.py`; data: repo `data/speed_model` training outputs; inputs: phone IMU features; output: speed; M use: not established as IO-VNBD and therefore not scored.
- `data\speed_model\models\cnn_r100_w256_joint_deploy.tflite`; training source: `engine/speed_model/train.py`; data: repo `data/speed_model` training outputs; inputs: phone IMU features; output: speed; M use: not established as IO-VNBD and therefore not scored.
- `data\speed_model\models\cnn_r100_w256_r1deploy.meta.pt`; training source: `engine/speed_model/train.py`; data: repo `data/speed_model` training outputs; inputs: phone IMU features; output: speed; M use: not established as IO-VNBD and therefore not scored.
- `data\speed_model\models\cnn_r100_w256_r1deploy.pt`; training source: `engine/speed_model/train.py`; data: repo `data/speed_model` training outputs; inputs: phone IMU features; output: speed; M use: not established as IO-VNBD and therefore not scored.
- `data\speed_model\models\cnn_r100_w256_r2deploy.meta.pt`; training source: `engine/speed_model/train.py`; data: repo `data/speed_model` training outputs; inputs: phone IMU features; output: speed; M use: not established as IO-VNBD and therefore not scored.
- `data\speed_model\models\cnn_r100_w256_r2deploy.onnx`; training source: `engine/speed_model/train.py`; data: repo `data/speed_model` training outputs; inputs: phone IMU features; output: speed; M use: not established as IO-VNBD and therefore not scored.
- `data\speed_model\models\cnn_r100_w256_r2deploy.pt`; training source: `engine/speed_model/train.py`; data: repo `data/speed_model` training outputs; inputs: phone IMU features; output: speed; M use: not established as IO-VNBD and therefore not scored.
- `data\speed_model\models\cnn_r100_w256_r2deploy.tflite`; training source: `engine/speed_model/train.py`; data: repo `data/speed_model` training outputs; inputs: phone IMU features; output: speed; M use: not established as IO-VNBD and therefore not scored.
- `data\speed_model\models\cnn_r100_w256_r2deploy_s1.meta.pt`; training source: `engine/speed_model/train.py`; data: repo `data/speed_model` training outputs; inputs: phone IMU features; output: speed; M use: not established as IO-VNBD and therefore not scored.
- `data\speed_model\models\cnn_r100_w256_r2deploy_s1.onnx`; training source: `engine/speed_model/train.py`; data: repo `data/speed_model` training outputs; inputs: phone IMU features; output: speed; M use: not established as IO-VNBD and therefore not scored.
- `data\speed_model\models\cnn_r100_w256_r2deploy_s1.pt`; training source: `engine/speed_model/train.py`; data: repo `data/speed_model` training outputs; inputs: phone IMU features; output: speed; M use: not established as IO-VNBD and therefore not scored.
- `data\speed_model\models\cnn_r100_w256_r2deploy_s1.tflite`; training source: `engine/speed_model/train.py`; data: repo `data/speed_model` training outputs; inputs: phone IMU features; output: speed; M use: not established as IO-VNBD and therefore not scored.
- `data\speed_model\models\cnn_r100_w256_r2deploy_s2.meta.pt`; training source: `engine/speed_model/train.py`; data: repo `data/speed_model` training outputs; inputs: phone IMU features; output: speed; M use: not established as IO-VNBD and therefore not scored.
- `data\speed_model\models\cnn_r100_w256_r2deploy_s2.pt`; training source: `engine/speed_model/train.py`; data: repo `data/speed_model` training outputs; inputs: phone IMU features; output: speed; M use: not established as IO-VNBD and therefore not scored.
- `docs\speed_model\split_S1.pkl`; training source: `engine/speed_model/train.py`; data: repo `data/speed_model` training outputs; inputs: phone IMU features; output: speed; M use: not established as IO-VNBD and therefore not scored.
- `docs\speed_model\split_S2.pkl`; training source: `engine/speed_model/train.py`; data: repo `data/speed_model` training outputs; inputs: phone IMU features; output: speed; M use: not established as IO-VNBD and therefore not scored.
- `docs\speed_model\split_S3a.pkl`; training source: `engine/speed_model/train.py`; data: repo `data/speed_model` training outputs; inputs: phone IMU features; output: speed; M use: not established as IO-VNBD and therefore not scored.
- `docs\speed_model\split_S3c.pkl`; training source: `engine/speed_model/train.py`; data: repo `data/speed_model` training outputs; inputs: phone IMU features; output: speed; M use: not established as IO-VNBD and therefore not scored.
- `IDRNav\app\src\main\assets\model\cnn_r100_w256_joint_deploy.tflite`; training source: `engine/speed_model/train.py`; data: repo `data/speed_model` training outputs; inputs: phone IMU features; output: speed; M use: not established as IO-VNBD and therefore not scored.
- CURRENT is included as `NOT_FOUND` rows in `results.csv`; no model predictions are claimed.

## Methods
B1 phone gyro heading + last phone-GPS speed; B2 phone gyro heading + VBOX speed oracle; B3 VBOX heading oracle + last phone-GPS speed; B4 phone gyro heading + leave-one-drive-out calibrated phone forward-acceleration speed; X is the engine/CAN comparison and is **NOT PHONE-ONLY (uses CAN inputs)**; CURRENT is unavailable.

## Metrics
Each cell is `endpoint median/p90/worst m; drift median/p90/worst %; RMSE median/p90/worst m; heading median/p90/worst deg`. The final item in each cell is `outages / percent under 10% drift`.

| method | 30 s | 60 s | 120 s |
| --- | --- | --- | --- |
| B1 | endpoint 68.92/192.29/419.44 m<br>drift 25.07/98.08/6772.55 %<br>RMSE 39.59/109.96/225.46 m<br>heading 8.22/19.98/178.09 deg<br>296 outages / 15.88% under 10% | endpoint 148.65/417.64/921.37 m<br>drift 30.46/80.38/3959.42 %<br>RMSE 87.56/221.28/512.06 m<br>heading 16.87/35.51/179.83 deg<br>295 outages / 12.88% under 10% | endpoint 332.52/890.29/2083.69 m<br>drift 35.32/76.28/513.09 %<br>RMSE 197.80/471.16/1103.46 m<br>heading 34.59/66.26/178.99 deg<br>291 outages / 11.00% under 10% |
| B2 | endpoint 13.92/46.44/166.54 m<br>drift 5.73/16.93/164.86 %<br>RMSE 7.90/22.62/65.25 m<br>heading 8.22/19.98/178.09 deg<br>296 outages / 67.57% under 10% | endpoint 44.68/154.78/632.98 m<br>drift 9.97/25.19/176.69 %<br>RMSE 24.05/73.96/317.58 m<br>heading 16.87/35.51/179.83 deg<br>295 outages / 50.85% under 10% | endpoint 132.98/555.04/1434.22 m<br>drift 15.03/44.11/158.93 %<br>RMSE 78.03/251.36/706.50 m<br>heading 34.59/66.26/178.99 deg<br>291 outages / 39.52% under 10% |
| B3 | endpoint 53.33/189.34/419.44 m<br>drift 23.71/85.45/6757.66 %<br>RMSE 35.21/109.45/224.82 m<br>heading 0.00/0.00/0.00 deg<br>296 outages / 25.34% under 10% | endpoint 113.85/383.86/916.16 m<br>drift 22.82/80.11/3077.57 %<br>RMSE 71.42/217.34/512.06 m<br>heading 0.00/0.00/0.00 deg<br>295 outages / 23.39% under 10% | endpoint 225.69/704.80/1995.70 m<br>drift 23.40/63.99/554.70 %<br>RMSE 140.50/405.86/1070.06 m<br>heading 0.00/0.00/0.00 deg<br>291 outages / 20.96% under 10% |
| B4 | endpoint 63.58/144.67/337.85 m<br>drift 25.25/68.19/1916.45 %<br>RMSE 33.82/80.44/159.83 m<br>heading 8.22/19.98/178.09 deg<br>296 outages / 15.54% under 10% | endpoint 145.49/366.76/997.68 m<br>drift 29.71/71.65/1615.35 %<br>RMSE 83.72/193.77/475.86 m<br>heading 16.87/35.51/179.83 deg<br>295 outages / 11.86% under 10% | endpoint 321.15/852.18/2564.57 m<br>drift 35.23/70.56/392.71 %<br>RMSE 187.97/442.18/1333.22 m<br>heading 34.59/66.26/178.99 deg<br>291 outages / 9.97% under 10% |
| X (NOT PHONE-ONLY; uses CAN inputs) | endpoint 43.07/100.83/225.73 m<br>drift 15.90/50.69/781.93 %<br>RMSE 20.54/51.22/516.44 m<br>heading 2.57/8.29/178.75 deg<br>296 outages / 32.43% under 10% | endpoint 114.68/296.12/850.75 m<br>drift 21.84/62.78/2907.20 %<br>RMSE 61.30/147.22/1975.27 m<br>heading 4.31/12.99/178.86 deg<br>295 outages / 19.32% under 10% | endpoint 285.76/782.58/2393.79 m<br>drift 27.38/71.23/1203.83 %<br>RMSE 149.21/427.67/8083.32 m<br>heading 7.71/24.52/179.91 deg<br>291 outages / 14.09% under 10% |
| CURRENT | NOT FOUND; no eligible IO-VNBD model | NOT FOUND; no eligible IO-VNBD model | NOT FOUND; no eligible IO-VNBD model |

## Sanity check
B2 median drift at 30 s = **5.73%**; required 3% to 10%: **PASS**.

## Re-run
```powershell
python eval_iovnbd/run.py
```

## Reproduction console output (last section)
```text
DATA_ROOT=D:\MaverickGRID\IO-VNBD\Synchronised V abd S datasets\Categorised IOVNB Dataset
FACT_CHECK drive=S1 lag_s=0.2 corr=0.9492 usable=True
LAG_CANDIDATE drive=S1 lag_s=0.2 gyro_corr=0.9492 speed_lag_s=-4.0 speed_corr=0.9291
LAG_CANDIDATE drive=S1 lag_s=-557.5 gyro_corr=0.0538 speed_lag_s=-562.1 speed_corr=0.0377
LAG_CANDIDATE drive=S1 lag_s=557.4 gyro_corr=0.0530 speed_lag_s=554.4 speed_corr=0.0264
SPEED_PROOF drive=S1 vbox_median_mps=8.466 phone_median_mps=8.260 ratio=1.025
FACT_CHECK drive=S2 lag_s=195.1 corr=0.9120 usable=True
LAG_CANDIDATE drive=S2 lag_s=195.1 gyro_corr=0.9120 speed_lag_s=190.5 speed_corr=0.9479
LAG_CANDIDATE drive=S2 lag_s=7.3 gyro_corr=0.0447 speed_lag_s=7.9 speed_corr=0.1108
LAG_CANDIDATE drive=S2 lag_s=-441.0 gyro_corr=0.0422 speed_lag_s=-435.6 speed_corr=0.1740
SPEED_PROOF drive=S2 vbox_median_mps=8.824 phone_median_mps=8.680 ratio=1.017
FACT_CHECK drive=S3a lag_s=-6.7 corr=0.9742 usable=True
LAG_CANDIDATE drive=S3a lag_s=-6.7 gyro_corr=0.9742 speed_lag_s=-11.3 speed_corr=0.9667
LAG_CANDIDATE drive=S3a lag_s=-18.0 gyro_corr=0.1912 speed_lag_s=-12.2 speed_corr=0.9650
LAG_CANDIDATE drive=S3a lag_s=4.3 gyro_corr=0.1857 speed_lag_s=-1.7 speed_corr=0.7947
SPEED_PROOF drive=S3a vbox_median_mps=11.589 phone_median_mps=11.610 ratio=0.998
FACT_CHECK drive=S3c lag_s=0.5 corr=0.9962 usable=True
LAG_CANDIDATE drive=S3c lag_s=0.5 gyro_corr=0.9962 speed_lag_s=-3.5 speed_corr=0.9800
LAG_CANDIDATE drive=S3c lag_s=10.4 gyro_corr=0.0821 speed_lag_s=4.4 speed_corr=0.9057
LAG_CANDIDATE drive=S3c lag_s=-9.6 gyro_corr=0.0806 speed_lag_s=-4.4 speed_corr=0.9802
SPEED_PROOF drive=S3c vbox_median_mps=11.005 phone_median_mps=10.970 ratio=1.003
OUTAGES=882 ROWS=5292
```
