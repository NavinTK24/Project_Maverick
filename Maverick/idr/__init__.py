"""idr — phone-only intelligent dead reckoning for cars, evaluated on IO-VNBD.

Rules enforced in code:
  * phone S- files are the only model inputs; vehicle V- files are ground truth only;
  * drive M (Driver B) is never loaded (see data.TEST_LOCKED);
  * during a simulated outage nothing reads phone GPS after the outage start (harness.OutageGuard).
"""
