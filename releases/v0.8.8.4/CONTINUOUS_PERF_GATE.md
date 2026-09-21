# VXP-Core v0.8.8.4 — Chetaslua continuous performance gate

This release-preparation step adds a repeatable private-corpus performance gate before further runtime changes are promoted.

## Target workload

- Profile: Chetaslua
- SHA-256: `9e7ce08d33f5f6caccf446a1ca6db4ee91c39eacd38b05f0fbf234b9cb041b85`
- Format: ELF32 ARM EABI5 PIE
- v0.8.8.3 smoke baseline: 11 frames / 86,438,337 guest instructions in the recorded 1.8 s path.
- Binary inspection shows a Lua-heavy workload with `luaV_execute`, timer/event callbacks and framebuffer flush functions.

The title binary is intentionally not committed. CI receives it from a private URL configured as `PERF_CORPUS_URL`, verifies the exact SHA-256, and then executes the configured runtime command.

## Gate behavior

1. Two warm-up runs.
2. Seven measured runs by default.
3. Runner emits a JSON metrics object on its last stdout line.
4. Gate computes median FPS, p95 FPS, median MIPS and median guest instructions when available.
5. Default release floor is no more than 3% median-FPS regression from the checked-in v0.8.8.3 baseline.
6. Every result is uploaded as a GitHub Actions artifact.

## Optimization direction after this gate

Chetaslua did not improve in the v0.8.8.3 Thumb-only optimization, so the next runtime patch should be profile-driven around ARM cached execution, Lua VM hot-block dispatch, callback overhead, framebuffer flush cadence and GC/allocation pressure.

Do not claim an FPS improvement for v0.8.8.4 until the continuous gate produces repeatable measurements on the same corpus and host class.
