from __future__ import annotations

import numpy as np
from lightgbm import LGBMClassifier, LGBMRegressor


class Standardizer:
    def fit(self, x: np.ndarray) -> "Standardizer":
        self.mean = np.nanmean(x, axis=0)
        self.scale = np.nanstd(x, axis=0)
        self.scale[self.scale < 1e-6] = 1.0
        return self

    def transform(self, x: np.ndarray) -> np.ndarray:
        return (np.nan_to_num(x, nan=0.0) - self.mean) / self.scale

class GaussianLightGBM:
    def __init__(self, objective: str = "regression_l2"):
        self.objective = objective

    def fit(self, train_x: np.ndarray, train_y: np.ndarray, valid_x: np.ndarray | None = None, valid_y: np.ndarray | None = None) -> "GaussianLightGBM":
        self.model = LGBMRegressor(objective=self.objective, n_estimators=220, learning_rate=0.035,
                                   num_leaves=31, max_depth=8, min_child_samples=24,
                                   subsample=0.85, colsample_bytree=0.85, reg_lambda=1.0,
                                   random_state=0, verbosity=-1, n_jobs=1)
        self.model.fit(train_x, train_y, eval_set=[(valid_x, valid_y)] if valid_x is not None else None)
        residual = train_y - self.model.predict(train_x)
        self.residual_variance = float(np.var(residual) + 1e-3)
        return self

    def predict(self, values: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
        mean = self.model.predict(values)
        return mean, np.full(len(mean), self.residual_variance)


class StopLightGBM:
    def fit(self, values: np.ndarray, labels: np.ndarray) -> "StopLightGBM":
        self.model = LGBMClassifier(n_estimators=160, learning_rate=0.04, num_leaves=15,
                                    min_child_samples=24, random_state=0, verbosity=-1, n_jobs=1)
        self.model.fit(values, labels)
        return self

    def score(self, values: np.ndarray) -> np.ndarray:
        return self.model.predict_proba(values)[:, 1]


