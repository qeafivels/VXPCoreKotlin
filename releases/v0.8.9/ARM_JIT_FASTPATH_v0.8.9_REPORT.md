# VXP-Core v0.8.9 — ARM JIT-friendly cached fast path

## Goal

Optimize ARM-heavy VXP workloads without changing guest work, timer cadence, framebuffer semantics, callback limits, or security boundaries.

Target used for profile-guided validation:

- file: `Chetaslua.vxp`
- size: 516,132 bytes
- SHA-256: `9e7ce08d33f5f6caccf446a1ca6db4ee91c39eacd38b05f0fbf234b9cb041b85`
- backend: ELF_ARM / ARM EABI5 PIE

The target file is **not** included in the release or repository. The performance gate accepts a user-supplied VXP path.

## Profile findings

On v0.8.8.3, JFR showed the ARM path dominated by:

- the monolithic `stepArmDecoded()` path;
- word `LDR/STR`;
- data-processing instructions;
- `LDM/STM`;
- GuestMemory region lookup.

The v0.8.8.3 Thumb fast path did not materially accelerate this ARM-heavy workload.

## Implementation

v0.8.9 adds a JIT-friendly ARM cached dispatcher and specialized decode kinds for:

- B/BL and BLX immediate;
- LDM and STM as separate block-transfer paths;
- word LDR/STR split by immediate/register offset and load/store direction;
- hot ADD and MOV data-processing operations;
- generic single-data/data-processing fallback for remaining valid families.

The full ARM decoder remains authoritative for rare/overlapping instruction families, tracing, and single-step execution. Specialized kinds are also recognized by the full decoder so `cpu.step()` and cached execution preserve identical semantics.

### Rejected experiments

Two candidate changes were tested and not kept:

- **16-instruction basic blocks**: no stable median improvement over the existing 8-instruction limit.
- **secondary data-MRU on the hottest GuestMemory path**: improvement was within measurement noise, so the simpler existing memory path was retained.

## Fixed-frame A/B release gate

Same JVM host, `-Xms512m -Xmx512m`, 40 framebuffer frames, four trials per side with trial 0 excluded as warm-up.

The guest-work signature was identical on every trial:

`40 frames / 205,398,835 instructions / 39 events / 37 timers / timeout=false`

| Metric | v0.8.8.3 | v0.8.9 | Delta |
|---|---:|---:|---:|
| warm median MIPS | 50.365 | **64.659** | **+28.38%** |
| warm median 40-frame wall time | 4078.242 ms | **3176.664 ms** | **-22.11%** |
| fixed-work equivalent FPS | 9.808 | **12.592** | **+28.38%** |
| guest instructions | 205,398,835 | 205,398,835 | exact |
| frames | 40 | 40 | exact |
| events | 39 | 39 | exact |
| timer callbacks | 37 | 37 | exact |

An experimental 16-instruction-block candidate produced one **68.655 MIPS / ~13.37 fixed-work equivalent FPS** trial, but its warm median did not improve over the 8-instruction configuration, so that experiment was rejected. Release acceptance uses the final 8-instruction candidate and the median A/B gate below.

## Timed smoke

A nominal 1.8 s runtime budget is checked at callback boundaries, so measured host wall time can exceed 1.8 s on long guest callbacks. It is therefore a secondary indicator rather than the release metric.

- v0.8.8.3: 21 frames / 114,310,993 instructions
- v0.8.9: **28 frames / 147,722,743 instructions**

The candidate completed more guest work in the same runtime policy without modifying the timer cadence.

## Regression and security gates

Final candidate:

- core/self-contained JVM regression: **37/37 PASS**
- prepared-VXP DIBO smoke: **PASS** (5 frames / 13,254,657 instructions)
- unified real-corpus smoke on the target Chetaslua file: **PASS**
- fixed malformed/unsupported security suite: **33/33 PASS** under `-Xmx96m`
- deterministic fuzz-derived endurance: **8,192/8,192 PASS**
- fuzz catastrophic process exits: **0**
- deduplicated fuzz failure clusters: **0**
- observed fuzz heap drift per four-round chunk: ~90–92 KB, far below the 12 MiB gate

## Automatic continuous gates

`tests/jvm/FixedFramePerformanceBenchmark.kt` provides a deterministic fixed-frame benchmark.

`scripts/run_performance_ab_gate.sh`:

1. compiles the benchmark once;
2. runs baseline and candidate JARs against the same VXP;
3. rejects non-deterministic guest-work;
4. rejects any semantic signature difference;
5. computes warm-median MIPS;
6. fails when the candidate does not meet `PERF_MIN_SPEEDUP_PCT`.

`scripts/run_release_gate.sh` chains self-contained JVM regressions, malformed/fuzz security gates, and the fixed-frame performance A/B gate.

## Result

v0.8.9 is a profile-driven ARM interpreter performance release. It improves the validated Chetaslua workload by **28.38% warm-median throughput** in the formal A/B gate while preserving the exact fixed-work signature and all exercised security/compatibility invariants.
