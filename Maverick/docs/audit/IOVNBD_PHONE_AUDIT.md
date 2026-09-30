# IO-VNBD phone-data audit

Scope: strict phone-only audit of the synchronized IO-VNBD benchmark. Hard rules used here:

- Only the phone `S-` files are allowed as inputs.
- The vehicle `V-` files are used only as ground truth for comparison, never as the input stream.
- The `M (Driver B)` set is treated as a locked-out benchmark subset and is not used to tune the phone pipeline.

## B1. Dataset root and allowed inputs

The synchronized benchmark root is:

`C:\Users\STUDENT\Desktop\Maverick\Maverick45\IO-VNBD\Synchronised V abd S datasets\Categorised IOVNB Dataset`

Within this folder, the phone stream is the `S` CSV family and the vehicle stream is the `V` CSV family. The phone files are the only allowed data for the benchmark input path.

## B2. Measured phone-only inventory

I measured the actual synchronized `S` files using a reproducible script in [docs/audit/scripts/measure_iovnbd_phone_inventory.py](scripts/measure_iovnbd_phone_inventory.py).

Measured phone-only totals:

- Files: 72
- Rows: 1,070,745
- Size: 189.90 MB

Per-driver inventory:

| Driver group | Files | Rows | Size |
|---|---:|---:|---:|
| M (Driver B) | 1 | 105,974 | 18.88 MB |
| S (Driver A) | 6 | 308,839 | 54.73 MB |
| Vf (Driver E) | 2 | 79,009 | 14.06 MB |
| Vta (Driver E) | 30 | 128,619 | 22.75 MB |
| Vtb (Driver E) | 12 | 114,438 | 20.34 MB |
| Vw (Driver E) | 20 | 263,581 | 46.70 MB |
| Y (Driver D) | 1 | 70,285 | 12.44 MB |
| Total | 72 | 1,070,745 | 189.90 MB |

The phone benchmark is therefore a real synchronized phone dataset with multiple drivers and multiple drive sequences, but it is still a phone stream, not a vehicle CAN stream.

## B3. Phone schema confirms the phone modality

Representative phone columns from the synchronized `S` files are:

- `GPS LATITUDE (degrees)`
- `GPS LONGITUDE (degrees)`
- `GPS ALTITUDE (m)`
- `GPS SPEED (Kmh)`
- `GPS ACCURACY (m)`
- `GPS ORIENTATION (°)`
- `GPS SATELLITES IN RANGE`
- `TIME SINCE START (ms)`
- `ACCELEROMETER X (m/s²)`
- `ACCELEROMETER Y (m/s²)`
- `ACCELEROMETER Z (m/s²)`
- `GRAVITY X (m/s²)`
- `GRAVITY Y (m/s²)`
- `GRAVITY Z (m/s²)`
- `GYROSCOPE Yaw (rad/s)`
- `GYROSCOPE Pitch (rad/s)`
- `GYROSCOPE Roll (rad/s)`
- `MAGNETIC FIELD X (μT)`
- `MAGNETIC FIELD Y (μT)`

This is a phone IMU/GNSS stream, not a vehicle CAN data frame. The V files are the separate ground-truth channel with fields such as vehicle yaw rate, wheel speed, and vehicle acceleration.

## B4. Timing and cadence: phone stream is 10 Hz

I measured the time stamps in the synchronized phone files. Every phone file has a 100 ms cadence, which is a 10 Hz sample rate.

Measured timing:

- Sample-period median: 100.0 ms
- Sample-period mean: 100.0 ms
- Sample-period min: 100.0 ms
- Sample-period max: 100.0 ms

This is consistent with the repo’s phone-first view: the phone streams are regular, synchronized, and usable as an IMU/GNSS benchmark input.

## C. Phone-only benchmark ceiling and what it means

The phone benchmark is not the same as the vehicle benchmark, and the difference is physically meaningful:

- The phone `S` stream is a phone inertial signal with accelerometer/gyro/gravity behavior.
- The `V` stream is a vehicle CAN/GNSS truth stream with wheel-speed and yaw-rate channels.
- The phone signal has the geometry and noise characteristics of a mounted smartphone; the vehicle stream has the geometry and noise characteristics of a car.

The correct interpretation is this:

- The `S` files are the valid phone input benchmark.
- The `V` files are the ground-truth channel for validation.
- The `S` benchmark should be evaluated on its own phone-only signal quality and not conflated with the car benchmark.

## D. Audit verdict for the smartphone benchmark

The synchronized IO-VNBD benchmark is actually two coupled streams:

1. `S-*` phone files — valid smartphone inputs
2. `V-*` vehicle files — the ground-truth and transfer comparison stream

The phone-only benchmark is valid and real: it contains 72 synchronized smartphone sessions, 10 Hz timing, and a phone-native schema. The vehicle channel remains useful as ground truth, but it is not the phone input stream and should not be used as a replacement for the phone benchmark.

## Reproducible commands

The inventory script is in [docs/audit/scripts/measure_iovnbd_phone_inventory.py](scripts/measure_iovnbd_phone_inventory.py):

```bash
python "c:\Users\STUDENT\Desktop\offgrid\docs\audit\scripts\measure_iovnbd_phone_inventory.py"
```

The same dataset can be checked with a quick read of the phone-side schema and sample timing using the same root above.

## Bottom line

The strict phone-only interpretation is the correct one. For IO-VNBD, the phone `S` files are the valid input domain and the vehicle `V` files are ground truth only.
