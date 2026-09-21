# VXP-Core v0.8.8.4 — ARM/Lua Hot-Block Fast Path

## Scope

Base: **v0.8.8.3 THUMB-JIT-FASTPATH**. Target validation title is user-supplied `Chetaslua(1).vxp` (516,132 bytes), SHA-256 `9e7ce08d33f5f6caccf446a1ca6db4ee91c39eacd38b05f0fbf234b9cb041b85`. The title binary is not included in the repository.

## Implementation

The ARM cached path now avoids re-entering the monolithic ARM decoder for the Lua-heavy hot instruction mix. It adds compact cached helpers for LDM/STM, word LDR/STR immediate/register forms, hot ADD/MOV data-processing operations, and generic cached fallbacks for the remaining valid families. The full decoder remains authoritative for rare/overlapping paths, tracing and single-step execution. Executable-write invalidation, timer cadence and callback limits are preserved.

## Fixed-work A/B gate

Same host/JVM, OpenJDK 21, `-Xms512m -Xmx512m`, six trials per side with trial 0 excluded from the warm median. Stop condition: exactly 40 framebuffer frames.

Exact semantic signature on both versions:

`40 frames / 205,398,835 instructions / 39 events / 37 timers / timeout=false`

| Metric | v0.8.8.3 | v0.8.8.4 | Delta |
|---|---:|---:|---:|
| warm median MIPS | 49.097 | **63.389** | **+29.11%** |
| warm median wall time | 4183.567 ms | **3240.267 ms** | **-22.55%** |
| fixed-work equivalent FPS | 9.561 | **12.345** | **+29.12%** |
| guest instructions | 205,398,835 | 205,398,835 | exact |
| frames | 40 | 40 | exact |
| events | 39 | 39 | exact |
| timer callbacks | 37 | 37 | exact |

## Continuous performance gate

The repository continuous gate was executed against the v0.8.8.4 JAR with two warm-ups and seven measured 1.8-second policy runs.

- median FPS: **15.00**
- p95 FPS: **15.56**
- median guest instructions: **143,008,808**
- measured frames per trial: 26, 26, 28, 27, 26, 28, 27
- result: **PASS**

The next regression baseline is therefore advanced to **27 frames / 1.8 s (15.0 FPS)** for v0.8.8.4.

## Correctness / safety gates

- self-contained JVM regression: **37/37 PASS**
- fixed malformed/unsupported gate: **33/33 PASS**
- clean-room source check: **PASS**
- Kotlin-only source check: **PASS**
- fuzz-derived suite remains unchanged from the v0.8.8.3 security architecture; the full 8,192-case fuzz gate was not rerun in this re-versioning pass.

## Result

v0.8.8.4 is accepted because it improves the target ARM/Lua workload while preserving identical guest work in the deterministic fixed-frame gate. No timer-speed or gameplay-speed hack is used.