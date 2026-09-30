from __future__ import annotations

import numpy as np


BANDS = ((0.5, 1.0), (1.0, 2.0), (2.0, 5.0))


def _one_window(acc: np.ndarray, grav: np.ndarray, gyro: np.ndarray, dt: float) -> np.ndarray:
    absolute = np.linalg.norm(acc, axis=1)
    vertical = np.sum((acc - grav) * (grav / np.maximum(np.linalg.norm(grav, axis=1, keepdims=True), 1e-6)), axis=1)
    features = []
    for signal in (acc[:, 0], acc[:, 1], acc[:, 2], absolute, gyro[:, 0], gyro[:, 1], gyro[:, 2]):
        features.extend((float(np.sqrt(np.mean(signal ** 2))), float(np.std(signal)), float(np.mean(np.abs(signal)))))
    features.extend((float(np.mean(vertical)), float(np.std(vertical)), float(np.sqrt(np.mean(vertical ** 2))), float(np.min(vertical)), float(np.max(vertical))))
    spectrum = np.abs(np.fft.rfft(absolute - np.mean(absolute))) ** 2
    frequencies = np.fft.rfftfreq(len(absolute), dt)
    for low, high in BANDS:
        selected = (frequencies >= low) & (frequencies < high)
        features.append(float(np.sum(spectrum[selected]) / max(len(spectrum), 1)))
    features.extend((float(np.sqrt(np.mean(gyro ** 2))), float(np.std(gyro)), float(np.max(np.abs(gyro)))))
    return np.asarray(features, dtype=np.float32)


def imu_features(acc: np.ndarray, grav: np.ndarray, gyro: np.ndarray, dt: float, window: int, stride: int = 5) -> tuple[np.ndarray, np.ndarray]:
    indices = np.arange(window - 1, len(acc), stride)
    values = np.vstack([_one_window(acc[i - window + 1:i + 1], grav[i - window + 1:i + 1], gyro[i - window + 1:i + 1], dt) for i in indices])
    return values, indices


def context_features(speed: np.ndarray, times: np.ndarray, index: int, age: float, outage_age: float) -> np.ndarray:
    end = max(0, index - int(round(age / max(np.median(np.diff(times)), 1e-3))))
    last = float(speed[end])
    n30 = max(1, int(round(30.0 / max(np.median(np.diff(times)), 1e-3))))
    n60 = max(1, int(round(60.0 / max(np.median(np.diff(times)), 1e-3))))
    a = max(0, end - n30 + 1); b = max(0, end - n60 + 1)
    mean30, mean60 = float(np.mean(speed[a:end + 1])), float(np.mean(speed[b:end + 1]))
    trend = float((speed[end] - speed[b]) / max(times[end] - times[b], 1e-3))
    return np.asarray([last, mean30, mean60, trend, age, outage_age], dtype=np.float32)
