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
Cells are median / p90 / worst. Drift is endpoint error divided by true distance. Changed means true speed range during the outage exceeds 10 km/h.

| method | 30 s | 60 s | 120 s |
| --- | --- | --- | --- |
| S0 | changed: MAE 2.94/5.90/15.29 m/s; bias 0.96; drift 32.76/77.62/246.67%<br>steady: MAE 3.31/11.62/16.39 m/s; bias -1.35; drift 26.96/62.23/197.44% | changed: MAE 3.02/5.28/14.74 m/s; bias 0.69; drift 33.33/69.31/223.54%<br>steady: MAE 10.02/16.38/16.51 m/s; bias -9.39; drift 45.59/53.12/54.29% | changed: MAE 3.00/5.33/16.09 m/s; bias 0.55; drift 32.16/75.19/208.63%<br>steady: MAE 16.32/16.47/16.51 m/s; bias -16.32; drift 54.21/54.28/54.29% |
| S1 | changed: MAE 3.13/6.37/14.40 m/s; bias -0.06; drift 34.78/83.45/262.52%<br>steady: MAE 1.03/3.32/4.96 m/s; bias -0.30; drift 18.54/45.73/357.43% | changed: MAE 3.06/6.68/16.15 m/s; bias 0.26; drift 37.48/82.17/371.65%<br>steady: MAE 0.79/1.64/2.90 m/s; bias -0.13; drift 6.33/97.68/269.76% | changed: MAE 3.28/7.35/16.95 m/s; bias 0.01; drift 37.73/80.26/135.29%<br>steady: MAE 0.78/0.81/0.81 m/s; bias 0.04; drift 15.37/18.62/19.43% |
| S2 | changed: MAE 3.12/6.35/14.40 m/s; bias -0.00; drift 34.78/88.13/354.82%<br>steady: MAE 1.01/3.29/4.96 m/s; bias -0.30; drift 18.54/45.73/494.40% | changed: MAE 3.01/6.70/16.15 m/s; bias 0.30; drift 37.57/82.96/454.86%<br>steady: MAE 0.73/1.55/2.06 m/s; bias -0.13; drift 6.33/100.01/251.78% | changed: MAE 3.28/7.35/16.95 m/s; bias 0.04; drift 37.76/80.26/135.29%<br>steady: MAE 0.78/0.81/0.81 m/s; bias 0.04; drift 15.37/18.62/19.43% |
| B1 | changed: MAE 3.26 m/s; bias -0.01; drift 27.23/98.04/6772.55%<br>steady: MAE 1.27 m/s; bias -0.49; drift 14.39/74.63/3073.30% | changed: MAE 3.46 m/s; bias 0.45; drift 30.60/78.82/3959.42%<br>steady: MAE 0.93 m/s; bias 0.37; drift 10.62/726.34/1883.55% | changed: MAE 4.06 m/s; bias 0.60; drift 35.32/76.28/513.09%<br>steady: n/a |
| B2 | changed: MAE 0.00 m/s; bias 0.00; drift 5.80/16.76/164.86%<br>steady: MAE 0.00 m/s; bias 0.00; drift 4.77/17.03/24.12% | changed: MAE 0.00 m/s; bias 0.00; drift 9.97/25.18/176.69%<br>steady: MAE 0.00 m/s; bias 0.00; drift 8.31/24.20/26.59% | changed: MAE 0.00 m/s; bias 0.00; drift 15.03/44.11/158.93%<br>steady: n/a |

## Context conclusion
Changed outages: S1 median drift 37.23% vs S0 32.82%. Steady outages: S1 median drift 17.10% vs S0 29.33%. Context therefore does not help this model on either class. These are measured without tuning on the scored drive.

## Model comparison
Formulation R is retained for S1 because it predicts the pre-outage residual around the last phone-GPS speed; A remains the S0 IMU-only comparison. LightGBM is the retained learner; the torch CNN is only a comparison path.

## Reproduction
```powershell
python -m idr.train_speed
python eval_iovnbd/run.py
```

## Console output (both required commands)
```text
TRAIN_DRIVE_COUNT=33
TRAIN_HOURS=18.937
TRAIN_KM=969.182
LIGHTGBM_STATUS=installed
DATA_ROOT=D:\MaverickGRID\IO-VNBD\Synchronised V abd S datasets\Categorised IOVNB Dataset
OUTAGES=882 ROWS=5292
SPEED_MODEL_ROWS=4410
Wrote D:\MaverickGRID\docs\speed_model\SPEED_MODEL_RESULTS.md
```
