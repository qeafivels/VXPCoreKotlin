# VXP-Core v0.8.8.3 — THUMB-JIT-FASTPATH performance report

## Scope

Baseline: **v0.8.8.2 FUZZ-DERIVED-GATE**. The release keeps the same clean-room Kotlin/JVM architecture, security limits and malformed/fuzz gates. The optimization is restricted to cached Thumb instruction execution and decode classification.

## Root cause

JFR/HotSpot diagnostics on the DIBO fixed-work workload showed `ArmCpu.stepThumbDecoded()` as a dominant CPU site. The method is about 3.5 KB of JVM bytecode and HotSpot reported `hot method too big`, so `executeCachedBlock()` could not inline it. Even instructions already classified by `BasicBlockDecodeCache` repeatedly entered that monolithic decoder.

## Implementation

- Add explicit cached Thumb decode kinds for:
  - high-register operations / BX / BLX register;
  - PC-relative LDR;
  - register-offset load/store;
  - halfword immediate load/store;
  - SP-relative load/store;
  - load-address and SP-adjust;
  - PUSH/POP;
  - STMIA/LDMIA.
- Route cached Thumb instructions through a compact dispatcher and small semantic helpers.
- Keep the full decoder as the rare/unsupported fallback and for exact trace/single-step behavior.
- Preserve `thumbWritesMemory()` and block termination semantics for executable-generation invalidation and PC-changing operations.
- No block-size increase, no packed-cache experiment and no removal of self-modifying-code protection.

## DIBO fixed-work A/B

Same package and stop condition: **40 framebuffer frames**. Every run executed exactly **105,863,223 guest instructions**, with 41 events and 39 timer callbacks.

| Metric | v0.8.8.2 | v0.8.8.3 | Delta |
|---|---:|---:|---:|
| warm median MIPS | 38.448 | **45.739** | **+18.96%** |
| median equivalent wall time | 2753.413 ms | **2314.507 ms** | **-15.94%** |
| instructions | 105,863,223 | 105,863,223 | exact |
| frames | 40 | 40 | exact |

Warm median excludes trial 0. Both sides were measured on the same JVM host with `-Xms512m -Xmx512m`.

## Real-title 1.8 s A/B smoke

| Title | v0.8.8.2 | v0.8.8.3 | Observation |
|---|---|---|---|
| DIBO | 25 frames / 66,171,349 insn | **28 frames / 74,109,424 insn** | ~12% more completed timed work |
| Spider-Man Daily Bugle | 23 frames / 6,490,997 insn | **24 frames / 6,492,335 insn** | +1 frame; mostly timer-paced |
| Fnynn | 5 frames / 51 events / 49 timers | **5 frames / 54 events / 52 timers** | same frames, more callbacks completed |
| Chetaslua | 11 frames / 86,438,337 insn | 11 frames / 86,438,337 insn | neutral on this heavy-callback path |

## Correctness / security gates

- JVM regressions: **39/39 PASS**.
- DIBO prepared regression: **5 frames / 13,254,657 instructions PASS**.
- Fixed malformed/unsupported suite: **33/33 PASS** under `-Xmx96m` and `ExitOnOutOfMemoryError`.
- Deterministic fuzz-derived endurance: **8,192/8,192 PASS**, **0 failure clusters**.
- Fuzz heap drift per 4-round chunk stayed around ~98 KB, far below the 12 MiB release ceiling.
- Clean-room source check: **PASS**.
- Kotlin-only source check: **PASS**.

## Build note

The sandbox's full one-shot `kotlinc` build of all core source files exceeded the command execution window. The release JAR was therefore assembled from the validated v0.8.8.2 full JAR plus K2-compiled replacement classes generated directly from the changed v0.8.8.3 source (`ArmCpu`, `BasicBlockDecodeCache`, and the versioned public façade). That exact final JAR was used for the final fixed-work benchmark, malformed gate and 8,192-case fuzz gate.

## Result

v0.8.8.3 is a measurable interpreter-speed release rather than a timer/gameplay acceleration hack: guest work is unchanged in the fixed-frame gate, while host CPU throughput rises by ~19% on the validated DIBO workload. Timer cadence, framebuffer semantics, executable-write invalidation and security gates remain intact.