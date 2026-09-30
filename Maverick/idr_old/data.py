from __future__ import annotations

from pathlib import Path

import numpy as np
import pandas as pd


def add_phone_gyro_axes(root: Path, drive: dict) -> dict:
    path = next(root.rglob(f"S-{drive['key']}.csv"))
    for encoding in ("utf-8-sig", "utf-8", "cp1252", "latin-1"):
        try:
            frame = pd.read_csv(path, encoding=encoding)
            break
        except UnicodeDecodeError:
            continue
    values = []
    for axis in ("Yaw", "Pitch", "Roll"):
        matches = [name for name in frame.columns if name.strip() == f"GYROSCOPE {axis} (rad/s)"]
        if not matches:
            raise KeyError(f"Missing phone gyro axis {axis} in {path}")
        values.append(pd.to_numeric(frame[matches[0]], errors="coerce").to_numpy(float))
    drive["gyro_axes"] = np.column_stack(values)
    return drive
