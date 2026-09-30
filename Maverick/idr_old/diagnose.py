from __future__ import annotations

import pickle
from pathlib import Path

import numpy as np

from eval_iovnbd.run import DRIVES, find_root, load, resync
from idr.data import add_phone_gyro_axes
from idr.features import context_features, imu_features

REPO = Path(__file__).resolve().parents[1]
MODEL_DIR = REPO / "docs" / "speed_model"
LAGS = {"S1": 0.2, "S2": 195.1, "S3a": -6.7, "S3c": 0.5}


def align(d: dict) -> dict:
    q = d["pt"] + LAGS[d["key"]]
    for out, source in (("tx", "vx"), ("ty", "vy"), ("tspeed", "vspeed"), ("theading", "vheading")):
        d[out] = np.interp(q, d["vt"], d[source])
    return d


def main() -> None:
    root = find_root(None)
    drives = {}
    for key in DRIVES:
        d = align(load(root, key)); add_phone_gyro_axes(root, d); drives[key] = d
    print("DIAGNOSE_MODEL=LightGBM artifacts after rebuild")
    print("LABEL_CHECK")
    for key in ("S1", "S2", "S3a"):
        d = drives[key]; moving = d["tspeed"] * 3.6 > 5
        print(f"  {key}: label_median_mps={np.median(d['tspeed'][moving]):.3f} vbox_median_mps={np.median(d['vspeed'][moving]):.3f}")
    print("STOP_RULE_CHECK")
    for key in DRIVES:
        d = drives[key]
        with (MODEL_DIR / f"split_{key}.pkl").open("rb") as handle:
            artifact = pickle.load(handle)
        n = 10
        linear = d["acc"] - d["grav"]
        vibration = np.sqrt(np.convolve(np.mean(linear ** 2, axis=1), np.ones(n) / n, mode="same"))
        gyro = np.sqrt(np.convolve(np.mean(d["gyro_axes"] ** 2, axis=1), np.ones(n) / n, mode="same"))
        if "stop_model" in artifact:
            stop = artifact["stop_model"].score(np.column_stack([d["speed"], vibration, gyro])) >= artifact["stop_threshold"]
        else:
            stop = (d["speed"] < artifact.get("phone_stop_threshold", 1.0 / 3.6)) & (vibration < artifact["vibration_threshold"]) & (gyro < artifact["gyro_threshold"])
        moving = d["tspeed"] * 3.6 > 5
        true_stop = d["tspeed"] < 1 / 3.6
        run_length = max(1, int(round(2.0 / np.median(np.diff(d["pt"])))))
        long_stop = np.zeros(len(true_stop), dtype=bool)
        start = 0
        while start < len(true_stop):
            end = start
            while end < len(true_stop) and true_stop[end]:
                end += 1
            if end - start >= run_length:
                long_stop[start:end] = True
            start = max(end, start + 1)
        true_stop = long_stop
        print(f"  {key}: false_stop_pct={100*np.mean(stop[moving]):.3f} true_stop_capture_pct={100*np.mean(stop[true_stop]):.3f}")
    print("PREDICTION_DISTRIBUTION held_out=S1")
    d = drives["S1"]
    with (MODEL_DIR / "split_S1.pkl").open("rb") as handle:
        artifact = pickle.load(handle)
    dt = float(np.median(np.diff(d["pt"])))
    features, indices = imu_features(d["acc"], d["grav"], d["gyro_axes"], dt, int(round(5 / dt)), stride=20)
    context = np.vstack([context_features(d["speed"], d["pt"], int(i), 0, 0) for i in indices])
    predicted, _ = artifact["model1"].predict(artifact["scale1"].transform(np.hstack([features, context])))
    predicted = np.maximum(0.0, context[:, 0] + artifact.get("residual_scale", 1.0) * predicted)
    truth = d["tspeed"][indices]; moving = truth * 3.6 > 5
    print("  predicted_mps_percentiles=" + ",".join(f"{x:.3f}" for x in np.percentile(predicted[moving], [5, 25, 50, 75, 95])))
    print("  true_mps_percentiles=" + ",".join(f"{x:.3f}" for x in np.percentile(truth[moving], [5, 25, 50, 75, 95])))
    print("FEATURE_SANITY")
    for key in ("S1", "S2", "S3a"):
        d = drives[key]; dt = float(np.median(np.diff(d["pt"])))
        features, _ = imu_features(d["acc"], d["grav"], d["gyro_axes"], dt, int(round(5 / dt)), stride=20)
        print(f"  {key}: shape={features.shape} nan_pct={100*np.mean(~np.isfinite(features)):.3f} constant_pct={100*np.mean(np.std(features,axis=0)<1e-8):.3f} source=phone_S_file")
    print("CONTEXT_SANITY")
    for key in DRIVES:
        d = drives[key]; i = int(np.searchsorted(d["pt"], 60.0)); context = context_features(d["speed"], d["pt"], i, 0, 0)
        print(f"  {key}: context_last_phone_speed={context[0]:.6f} phone_speed_at_outage_start={d['speed'][i]:.6f} equal={bool(np.isclose(context[0], d['speed'][i]))}")


if __name__ == "__main__":
    main()
