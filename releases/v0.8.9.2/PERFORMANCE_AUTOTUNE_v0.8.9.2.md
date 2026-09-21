# VXP-Core v0.8.9.2 — continuous performance autotune gate

## Scope

This milestone upgrades the performance validation/tooling layer used after v0.8.9.1. It does not claim a new runtime speedup by itself and does not replace the latest validated runtime until a candidate wins the gate below.

Default target workload:

- user-supplied Chetaslua.vxp (test-only; not committed)
- size: 516,132 bytes
- SHA-256: 9e7ce08d33f5f6caccf446a1ca6db4ee91c39eacd38b05f0fbf234b9cb041b85
- fixed-work signature: 40/205398835/39/37/false

The v0.8.9.1 validated baseline remains approximately 69.372 MIPS / 13.510 fixed-work FPS-equivalent on the release host. These are host benchmark values, not guaranteed Android render FPS.

## Why this gate is stricter

A single fastest trial is not enough to accept an interpreter optimization. JVM JIT warm-up, CPU scheduling, background load and thermal state can make one run look faster while the repeated median regresses.

The v0.8.9.2 autotune gate:

1. accepts one baseline JAR plus one or more candidate JARs;
2. verifies the exact Chetaslua SHA-256 by default;
3. rotates baseline/candidate execution order by round and candidate;
4. requires an identical semantic signature for every pair;
5. records every round into a TSV file;
6. ranks candidates using median speedup, median absolute deviation (MAD), and win ratio;
7. supports bounded release runs or PERF_AUTOTUNE_ROUNDS=0 continuous mode;
8. prints a rolling scoreboard after every round;
9. traps Ctrl+C/TERM so an interrupted endurance run still prints the current best candidate;
10. identifies candidates by JAR filename plus a 12-character SHA-256 prefix.

Default acceptance thresholds:

- median speedup >= +1.0%;
- positive round win ratio >= 0.60;
- MAD <= 2.50 percentage points;
- at least 3 completed rounds;
- semantic signature stable and identical to baseline.

## Usage

Run from an extracted/current VXP-Core source tree containing tests/jvm/FixedFramePerformanceBenchmark.kt:

    PERF_SOURCE_ROOT=/path/to/VXP-Core-Library-v0.8.9.1 \
    PERF_AUTOTUNE_ROUNDS=7 \
    ./releases/v0.8.9.2/run_autotune_performance_gate.sh \
      /path/to/vxp-core-0.8.9.1.jar \
      /path/to/Chetaslua.vxp \
      /path/to/candidate-shift-imm.jar \
      /path/to/candidate-dispatch.jar

Continuous endurance mode:

    PERF_AUTOTUNE_ROUNDS=0 \
    PERF_AUTOTUNE_SLEEP_SECONDS=1 \
    ./releases/v0.8.9.2/run_autotune_performance_gate.sh \
      baseline.jar Chetaslua.vxp candidate-a.jar candidate-b.jar

If the benchmark source is elsewhere, set PERF_BENCHMARK_SOURCE to its absolute path.

## Next runtime experiments

Based on the remaining v0.8.9.1 profile findings, measure these candidates independently:

- split/predecode common ARM shifted-register Operand2 forms so cached ALU execution avoids repeated field extraction and generic shift dispatch;
- reduce cached ARM dispatch/decode-refill overhead without changing the validated 8-instruction block limit or executable-write invalidation;
- investigate instruction-cache refill locality before touching GuestMemory data lookup again, because a secondary data-MRU experiment was already rejected in v0.8.9.

Do not combine speculative optimizations before measuring them independently.

## Tooling validation before commit

- bash syntax check: PASS;
- Python bytecode compile: PASS;
- synthetic scorer test: a stable ~+2.02% candidate was selected while a noisy/negative candidate was held;
- uploaded Chetaslua SHA-256 and size rechecked: PASS.

No user-supplied VXP binary is committed.
