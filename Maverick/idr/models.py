"""LightGBM speed models with per-sample uncertainty, and an IMU-only stop detector."""
from __future__ import annotations

import numpy as np
from lightgbm import LGBMClassifier, LGBMRegressor

PARAMS = dict(n_estimators=400, learning_rate=0.05, num_leaves=31, min_child_samples=50, subsample=0.8,
              subsample_freq=1, colsample_bytree=0.8, reg_lambda=1.0, random_state=0, verbosity=-1, n_jobs=2, deterministic=True, force_row_wise=True)


class SpeedModel:
    """Mean regressor plus 16 % / 84 % quantile regressors -> sigma = (q84 - q16) / 2."""

    def fit(self, X: np.ndarray, y: np.ndarray) -> "SpeedModel":
        self.mean = LGBMRegressor(objective="regression", **PARAMS).fit(X, y)
        self.q16 = LGBMRegressor(objective="quantile", alpha=0.16, **PARAMS).fit(X, y)
        self.q84 = LGBMRegressor(objective="quantile", alpha=0.84, **PARAMS).fit(X, y)
        return self

    def predict(self, X: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
        m = self.mean.predict(X)
        s = np.maximum((self.q84.predict(X) - self.q16.predict(X)) / 2.0, 0.2)
        return m, s


class StopDetector:
    """P(vehicle stopped) from IMU features only. Threshold chosen on out-of-fold training predictions
    so that false stops while moving stay <= max_false."""

    def fit(self, X: np.ndarray, stopped: np.ndarray, moving: np.ndarray, groups: np.ndarray, max_false: float = 0.02) -> "StopDetector":
        oof = np.full(len(X), np.nan)
        ug = np.unique(groups); folds = np.array_split(np.random.default_rng(0).permutation(ug), min(4, len(ug)))
        for f in folds:
            te = np.isin(groups, f)
            m = LGBMClassifier(**PARAMS).fit(X[~te], stopped[~te]); oof[te] = m.predict_proba(X[te])[:, 1]
        self.model = LGBMClassifier(**PARAMS).fit(X, stopped)
        self.threshold, self.oof_false, self.oof_capture = 1.01, 0.0, 0.0
        for t in np.linspace(0.05, 0.99, 95):
            fire = oof >= t
            fr, cap = float(fire[moving].mean()), float(fire[stopped].mean()) if stopped.any() else 0.0
            if fr <= max_false and cap > self.oof_capture:
                self.threshold, self.oof_false, self.oof_capture = float(t), fr, cap
        return self

    def predict(self, X: np.ndarray) -> np.ndarray:
        return self.model.predict_proba(X)[:, 1] >= self.threshold
