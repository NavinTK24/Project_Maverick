# OFFGRID repo audit

Scope: read-only audit of the OFFGRID repository and the external IO-VNBD benchmark. No source files were modified outside the allowed audit paths under [docs/audit](.) and [docs/audit/scripts](scripts/).

## A1. Repo-level finding: OFFGRID is a phone-first dead-reckoning system

The repository is internally consistent about its primary domain:

- [README.md](../../README.md) presents OFFGRID as a smartphone dead-reckoning system for a bicycle, using the phone accelerometer and gyroscope, a learned speed model, and GNSS-withheld outage replay.
- [engine/README.md](../../engine/README.md) describes the real runtime stack: QA, alignment, heading, speed model, filter, corridor model, replay, and evaluation.
- [engine/iovnbd.py](../../engine/iovnbd.py) is explicitly the external benchmark path: it is the same engine on a vehicle IMU, with the GNSS truth masked, and it loads the V dataset from the external benchmark tree.
- [docs/11_ENGINE_RESULTS.md](../11_ENGINE_RESULTS.md) records the main results on the repository’s own phone data and describes the IO-VNBD track as a separate external-vehicle transfer benchmark.

This is the key design distinction:

- The main OFFGRID runtime is a phone-only bicycle pipeline.
- IO-VNBD is an external IMU benchmark used to test transfer to a different sensing stack, not the canonical repo dataset.

## A2. The repository’s own data story is phone-native

The project data narrative is built around a phone logger and a bicycle testbed, not a car CAN bus:

- The logger is described as a foreground Android sensor logger with 21 streams and a strict stand/ride protocol.
- The sensor streams include accelerometer, gravity, gyroscope, magnetometer, and GNSS fixes.
- The speed model is trained on the bike-frame IMU vibration pattern, not on vehicle wheel speeds.
- The route data are described as two roads with a rider and a phone in the bike mount.

This matches the repo’s core claim: the phone is the measurement platform and the bike is the vehicle domain.

## A3. IO-VNBD is an external benchmark, not the repo’s native phone dataset

The vehicle benchmark is physically different from the repo’s own path:

- [engine/iovnbd.py](../../engine/iovnbd.py) documents the V-file as an external 10 Hz vehicle IMU source with CAN yaw rate and acceleration signals.
- The V-file schema contains fields such as `Yaw Rate (deg/sec)`, `Indicated Longitudinal Acceleration (g)`, and wheel-speed channels.
- The phone S-file schema contains GPS, accelerometer, gravity, gyroscope, and magnetometer streams, not vehicle-engine or CAN channels.

The repo therefore treats the external benchmark as a transfer test, not as a second copy of the main phone dataset.

## A4. Repo audit verdict

The repository is consistent and coherent:

1. OFFGRID is a smartphone dead-reckoning system.
2. The bike/phone path is the primary mission.
3. IO-VNBD is a separate vehicle benchmark used to test the engine on external IMU data.
4. The vehicle V files are ground truth and transfer inputs, not phone-only inputs.

## A5. Decision for the build plan

The correct interpretation is not to treat every IO-VNBD file as equal. The repository objective is clear:

- build the phone-first pipeline;
- use IO-VNBD only as an external transfer benchmark;
- keep the phone-only benchmark strict and separate from the vehicle ground-truth stream.

That is the defensible reading of the repo and the data.
