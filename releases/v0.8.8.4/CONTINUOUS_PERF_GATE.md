# VXP-Core v0.8.8.4 — Chetaslua continuous performance gate

## Target workload

- Profile: Chetaslua
- SHA-256: `9e7ce08d33f5f6caccf446a1ca6db4ee91c39eacd38b05f0fbf234b9cb041b85`
- Format: ELF32 ARM EABI5 PIE
- Binary is user-supplied and is not committed.

## Completed v0.8.8.4 validation

Two warm-ups plus seven measured 1.8-second policy runs:

- measured frames: `26, 26, 28, 27, 26, 28, 27`
- median FPS: **15.00**
- p95 FPS: **15.56**
- median guest instructions: **143,008,808**
- result: **PASS**

The historical CI regression baseline is advanced from v0.8.8.3 to **v0.8.8.4 / 27 frames / 1.8 s**. Newer validated releases remain documented separately.

## Deterministic fixed-work A/B

Same host/JVM and exact 40-frame work signature:

- v0.8.8.3: **49.097 MIPS / 9.561 equivalent FPS**
- v0.8.8.4: **63.389 MIPS / 12.345 equivalent FPS**
- delta: **+29.11% MIPS / +29.12% FPS**
- wall time: **4183.567 → 3240.267 ms (-22.55%)**

Semantic signature:
`40 frames / 205,398,835 instructions / 39 events / 37 timers / timeout=false`.

## Correctness gates

- JVM regression: **37/37 PASS**
- fixed malformed/unsupported: **33/33 PASS**
- clean-room: **PASS**
- Kotlin-only: **PASS**
- full 8,192-case fuzz-derived endurance was not rerun in this re-versioning pass.

See `ARM_LUA_HOTBLOCK_v0.8.8.4_REPORT.md`.
