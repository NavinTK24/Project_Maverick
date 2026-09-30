from __future__ import annotations

import pickle
from pathlib import Path

import numpy as np

from eval_iovnbd.run import DRIVES, find_root, load, resync, corr_at
from idr.features import imu_features, context_features
from idr.model import GaussianLightGBM, Standardizer, StopLightGBM
from idr.data import add_phone_gyro_axes

REPO = Path(__file__).resolve().parents[1]
OUT = REPO / "docs" / "speed_model"


def speed_resync(d: dict) -> dict:
    """Use the frozen evaluator loader, then retain its speed-aligned truth arrays."""
    return resync(d)


def eligible_drives(root: Path) -> tuple[list[dict], list[dict]]:
    all_drives = []
    for path in sorted(root.rglob("S-*.csv")):
        key = path.stem[2:]
        if key == "M" or any(d["key"] == key for d in all_drives):
            continue
        try:
            d = speed_resync(load(root, key))
            add_phone_gyro_axes(root, d)
            speed_corr = float(d["speed_corr"])
            d["speed_corr"] = speed_corr
            all_drives.append(d)
        except (KeyError, ValueError, FileNotFoundError):
            continue
    selected = [d for d in all_drives if d["speed_corr"] >= 0.9]
    return all_drives, selected


def training_rows(drives: list[dict], with_context: bool) -> tuple[np.ndarray, np.ndarray]:
    x_rows, y_rows = [], []
    for d in drives:
        dt = float(np.median(np.diff(d["pt"])))
        imu, indices = imu_features(d["acc"], d["grav"], d["gyro_axes"], dt, int(round(5.0 / dt)), stride=200)
        if len(indices) > 200:
            selected = np.linspace(0, len(indices) - 1, 200).astype(int)
            imu, indices = imu[selected], indices[selected]
        labels = d["tspeed"][indices]
        if with_context:
            contexts = []
            for index in indices:
                for age in (0.0, 10.0, 30.0):
                    start_index = max(0, int(index - round(age / dt)))
                    contexts.append(context_features(d["speed"], d["pt"], start_index, 0.0, age))
            imu = np.repeat(imu, 3, axis=0)
            labels = np.repeat(labels, 3)
            imu = np.hstack([imu, np.asarray(contexts, dtype=np.float32)])
        x_rows.append(imu); y_rows.append(labels)
    return np.vstack(x_rows), np.concatenate(y_rows)


def fit_split(train: list[dict], held_out: dict) -> dict:
    dt = float(np.median(np.diff(train[0]["pt"])))
    x0, y = training_rows(train, False)
    x1, y1 = training_rows(train, True)
    scale0 = Standardizer().fit(x0); scale1 = Standardizer().fit(x1)
    model0 = GaussianLightGBM().fit(scale0.transform(x0), y)
    residual = y1 - x1[:, -6]
    model1 = GaussianLightGBM().fit(scale1.transform(x1), residual)
    vibration, gyro_energy, stopped, moving, stop_features = [], [], [], [], []
    for d in train:
        n = max(1, int(round(1.0 / np.median(np.diff(d["pt"]))))); linear = d["acc"] - d["grav"]
        vibration.extend(np.sqrt(np.convolve(np.mean(linear ** 2, axis=1), np.ones(n) / n, mode="same")))
        gyro_energy.extend(np.sqrt(np.convolve(np.mean(d["gyro_axes"] ** 2, axis=1), np.ones(n) / n, mode="same")))
        stopped.extend(d["tspeed"] < 1 / 3.6)
        moving.extend(d["tspeed"] * 3.6 > 5)
        stop_features.extend(np.column_stack([d["speed"], np.sqrt(np.mean(linear ** 2, axis=1)), np.sqrt(np.mean(d["gyro_axes"] ** 2, axis=1))]))
    vibration = np.asarray(vibration); gyro_energy = np.asarray(gyro_energy)
    stopped = np.asarray(stopped, bool); moving = np.asarray(moving, bool)
    stop_features = np.asarray(stop_features)
    threshold_v, threshold_g = float(np.quantile(vibration, .01)), float(np.quantile(gyro_energy, .01))
    best_capture = 0.0
    for qv in np.linspace(.005, .5, 30):
        for qg in np.linspace(.005, .5, 30):
            candidate_v, candidate_g = float(np.quantile(vibration, qv)), float(np.quantile(gyro_energy, qg))
            fire = (vibration < candidate_v) & (gyro_energy < candidate_g)
            false_rate = float(np.mean(fire[moving]))
            capture = float(np.mean(fire[stopped])) if stopped.any() else 0.0
            if false_rate < .02 and capture > best_capture:
                threshold_v, threshold_g, best_capture = candidate_v, candidate_g, capture
    stop_model = StopLightGBM().fit(stop_features[::10], stopped[::10].astype(int))
    stop_scores = stop_model.score(stop_features[::10])
    stop_labels = stopped[::10]; stop_moving = moving[::10]
    base_capture = float(np.mean(((vibration < threshold_v) & (gyro_energy < threshold_g))[stopped])) if stopped.any() else 0.0
    stop_threshold = 0.5
    for candidate in np.linspace(0.05, 0.95, 91):
        fired = stop_scores >= candidate
        if np.mean(fired[stop_moving]) < .02 and np.mean(fired[stop_labels]) > base_capture:
            stop_threshold = float(candidate)
    return dict(window_s=5.0, feature_note="hand features: RMS, band energies, gravity-projected vertical statistics, gyro energy",
                model0=model0, model1=model1, scale0=scale0, scale1=scale1,
                vibration_threshold=threshold_v, gyro_threshold=threshold_g,
                process_noise=float(np.var(residual - model1.predict(scale1.transform(x1))[0]) + .04),
                train_keys=[d["key"] for d in train], held_out=held_out["key"], formulation="R residual and A absolute",
                phone_stop_threshold=1.0 / 3.6,
                stop_model=stop_model, stop_threshold=stop_threshold,
                residual_scale=0.0,
                stop_capture=best_capture, cnn_status="LightGBM selected; CNN validation is registered separately")


def main() -> None:
    root = find_root(None)
    all_drives, selected = eligible_drives(root)
    usable = {d["key"] for d in (resync(load(root, key)) for key in DRIVES)}
    OUT.mkdir(parents=True, exist_ok=True)
    print(f"TRAIN_ROOT={root}")
    print("TRAIN_DRIVES=" + ",".join(d["key"] for d in selected))
    print(f"TRAIN_DRIVE_COUNT={len(selected)}")
    print(f"TRAIN_HOURS={sum(d['pt'][-1] for d in selected) / 3600.0:.3f}")
    print(f"TRAIN_KM={sum(np.sum(np.hypot(np.diff(d['tx']), np.diff(d['ty']))) for d in selected) / 1000.0:.3f}")
    print("LIGHTGBM_STATUS=installed; residual R and absolute A trained")
    for held_out in sorted((d for d in selected if d["key"] in usable), key=lambda x: x["key"]):
        train = [d for d in selected if d["key"] != held_out["key"]]
        artifact = fit_split(train, held_out)
        with (OUT / f"split_{held_out['key']}.pkl").open("wb") as handle:
            pickle.dump(artifact, handle, protocol=pickle.HIGHEST_PROTOCOL)
        print(f"SPLIT held_out={held_out['key']} train={','.join(artifact['train_keys'])}")
    print(f"Wrote models to {OUT}")


if __name__ == "__main__":
    main()
