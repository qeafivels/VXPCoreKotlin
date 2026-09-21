# VXP-Core v0.8.9 — ARM Cached Fast Path

## Scope

This iteration targets ARM-heavy VXP workloads that do not benefit materially from the Thumb cached fast path added in v0.8.8.3.

The cached ARM execution path now dispatches already-classified basic-block entries directly to compact helpers for:

- BLX immediate
- B/BL branch
- single data transfer (LDR/STR, byte/word)
- data-processing / ALU instructions

Rare or overlapping ARM instruction families continue through the existing reference decoder. The basic-block cache, executable-write generation checks and self-modifying-code invalidation rules are unchanged.

## Chetaslua A/B benchmark

Workload: `Chetaslua.vxp`
Runtime gate: 3,500 ms per trial
Storage: fresh isolated directory per trial
Host: same container/JVM for baseline and candidate

### v0.8.8.3 baseline — 5 trials

- frames: 17, 16, 16, 17, 16
- median frames: **16**
- median gate FPS: **4.571**
- median guest instructions: **90,468,089**
- best frames: **17**

### v0.8.9 ARM cached fast path — 3 stable trials

- frames: 17, 18, 18
- median frames: **18**
- median gate FPS: **5.143**
- median guest instructions: **100,179,194**
- best frames: **18**

Median gate FPS improves from 4.571 to 5.143, approximately **+12.5%** on this workload. The higher guest-instruction count within the same runtime gate is consistent with improved host-side ARM dispatch throughput rather than skipped emulation work.

These figures are a same-machine performance gate, not a guarantee of device FPS. Android ART, device clocks, rendering cadence, audio and thermal throttling can change end-to-end FPS.

## Correctness / regression gate

PASS:

- ARM cached fast-path parity: ALU + LDR/STR + conditional branch matches reference decoder
- BasicBlockDecodeCacheRegression
- InBlockSelfModifyRegression
- ArmPcOperandRegression
- ArmLongMultiplyRegression
- ArmHalfwordMultiplyRegression
- ArmDoublewordRegression
- ArmClzRegression
- GuestMemoryFastPathRegression

The parity test compares cached execution with the reference decoder and checks register state, memory state and instruction accounting.

## Rejected experiment

Increasing cached ARM block length from 8 to 16 instructions was tested first. It did not produce a sufficiently stable Chetaslua improvement, so it is intentionally not part of this change.

## Compatibility

- no ABI changes
- no loader changes
- no graphics/audio/input changes
- no guest memory layout changes
- no proprietary SDK dependency
- Kotlin-only clean-room runtime remains the target

## Next performance loop

Use `RealVxpPerfLoop.kt` with isolated storage per trial and compare median frames/instructions. Future optimizations should be accepted only when they improve repeated real-VXP measurements and pass the CPU/cache/self-modifying-code regression gate.
