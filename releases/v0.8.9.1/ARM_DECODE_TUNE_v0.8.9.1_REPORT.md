# VXP-Core v0.8.9.1 — ARM decode-cache capacity + SUB fast path

## Target and acceptance rule

Profile/validation target is the user-supplied `Chetaslua.vxp` (test-only, not distributed):

- size: 516,132 bytes
- SHA-256: `9e7ce08d33f5f6caccf446a1ca6db4ee91c39eacd38b05f0fbf234b9cb041b85`
- backend: ELF32 ARM / EABI5 PIE
- fixed-work signature: `40 frames / 205,398,835 instructions / 39 events / 37 timers / timeout=false`

An optimization is accepted only when the signature stays exact and repeated warm-median throughput improves without failing regression/security gates.

## Profile findings

JFR on v0.8.9 showed the dominant ARM cached path still spending material time in:

- `stepArmCachedDecoded()` condition/dispatch;
- decode-cache refill / `armTerminatesBlock()`;
- `GuestMemory.find()`;
- generic data-processing, with `SUB` prominent in sampled hot lines;
- shifted-register operand decoding.

## Accepted changes

1. **Decode cache capacity: 512 -> 1024 slots per mode.**
   - 1024 repeatedly improved the Chetaslua workload.
   - 2048 produced essentially the same throughput as 1024 and was rejected to avoid unnecessary RAM growth.
2. **Dedicated cached ARM `SUB` decode kind/helper.**
   - `SUB` no longer re-enters the generic data-processing switch on the cached path.
   - The full/reference decoder also recognizes the new kind so trace/single-step semantics remain correct.
3. **Repeated alternating-order performance gate.**
   - `scripts/run_continuous_performance_gate.sh` alternates baseline/candidate order across rounds, verifies the exact semantic signature every round, and evaluates median per-round speedup.
   - `PERF_CONTINUOUS_ROUNDS=0` enables deliberate continuous mode until interrupted; release/CI should use a bounded round count.

## Rejected experiments

The following were measured and not retained:

- 2048 decode-cache slots: no meaningful gain over 1024;
- packed ARM opcode/kind/write metadata: about 1.9% slower in the measured A/B;
- cached condition metadata: improvement was not stable enough to justify added hot-path complexity;
- BX/BLX-register specialization: median fell from about 13.645 to 13.573 FPS-equivalent in the direct comparison;
- earlier 16-instruction block and secondary data-MRU experiments from v0.8.9 remain rejected.

## Final performance validation

Five-trial fixed-frame checks on the final artifacts (trial 0 warm-up, median over remaining trials):

| Comparison | v0.8.9 baseline | v0.8.9.1 | Delta |
|---|---:|---:|---:|
| Prior release JAR MIPS | 68.294 | **69.372** | **+1.58%** |
| Prior release JAR FPS-equivalent | 13.299 | **13.510** | **+1.59%** |
| Same-toolchain source baseline MIPS | 68.563 | **69.372** | **+1.18%** |
| Same-toolchain source baseline FPS-equivalent | 13.352 | **13.510** | **+1.18%** |
| Guest instructions | 205,398,835 | 205,398,835 | exact |
| Frames / events / timers | 40 / 39 / 37 | 40 / 39 / 37 | exact |

Earlier three alternating-order A/B rounds produced a **+2.65% median per-round speedup**, but one round was visibly host-throttled and another slightly favored the baseline. Therefore the release claim uses the more conservative final 5-trial artifact result above rather than the best observed sample.

These are fixed-work host benchmark values, not guaranteed on-device rendering FPS. Android ART, device clocking, thermal state, audio and UI composition can change end-to-end FPS.

## Correctness and security gates

Final candidate validation:

- JVM/core regressions: **38/38 PASS**;
- dedicated cached/reference `SUB` + executable-write invalidation regression: **PASS**;
- malformed/unsupported suite: **33/33 PASS** under `-Xmx96m`;
- deterministic fuzz-derived suite: **8,192/8,192 PASS**;
- catastrophic process exits: **0**;
- deduplicated failure clusters: **0**;
- Kotlin-only check: **PASS**;
- clean-room runtime source check: **PASS**.

During validation, the new `SUB` kind initially exposed a trace/single-step coverage gap. The release was not accepted until the reference decoder recognized `ARM_DP_SUB` and a regression covered cached mode, trace mode and executable-write invalidation.

## Result

v0.8.9.1 is a conservative ARM interpreter tuning release. It improves the validated Chetaslua fixed-work throughput while keeping the exact guest-work signature and all exercised compatibility/security invariants. The automatic repeated gate is included so later optimization work is rejected when a speedup is only a one-off benchmark fluctuation.
