# VXP-Core v0.8.8.4 — Full fuzz-derived endurance report

## Scope

This reruns the full deterministic fuzz-derived security gate for the historical **v0.8.8.4 ARM/LUA-HOTBLOCK** runtime after the performance release had already passed its fixed-work and continuous FPS gates.

Candidate identity:

- public version: `VxpCoreLibrary.VERSION = 0.8.8.4`
- candidate JAR SHA-256: `c3eb873d53decc3e37049e9c06d93e3b737d2f2eee33306c01e5a7271f0cb771`
- ARM CPU source SHA-256: `6a794ad02e6ac55c46da228beaf52869926baafd82fcd646bcee1acb351fc88c`
- decode-cache source SHA-256: `dd5a8d25ccad41fc70e63fde808feaaa30ecd002e97bad1c06815a4110feff03`
- `VxpLibrary.kt` source SHA-256: `5d5ce0601dbb4c5172fe853b0b05c28292f5e86e2672a6364540ec9cf99a1961`

The v0.8.8.4 source delta was reapplied to the committed v0.8.8.3 source snapshot. The resulting `ArmCpu.kt` and `BasicBlockDecodeCache.kt` are byte-for-byte identical to the ARM fast-path source used in the later v0.8.9 package, while `VxpLibrary.kt` differs only by the public version constant as expected.

## Gate configuration

- fixed malformed/unsupported layer: 33 cases
- fuzz rounds: 32
- cases per round: 256
- total deterministic fuzz cases: **8,192**
- rounds per child JVM: 4
- child JVM count: 8
- chunk timeout: 30 s
- heap: `-Xms16m -Xmx96m`
- OOM policy: `-XX:+ExitOnOutOfMemoryError`
- retained heap-drift ceiling: **12,582,912 B**
- master seed: `6221811148222257714`
- allowed failure clusters: 0

## Result

**PASS.**

- fixed malformed/unsupported: **33/33 PASS**
- fuzz-derived cases: **8,192/8,192 PASS**
- missing rounds: **0**
- duplicate rounds: **0**
- unique fuzz findings: **0**
- deduplicated failure clusters: **0**
- catastrophic process exits: **0**
- OOM: **0**
- crashes: **0**
- chunk timeouts: **0**
- maximum observed round time: **356.758 ms**
- maximum post-GC chunk heap drift: **92,448 B**
- heap-drift ceiling: **12,582,912 B**

All eight four-round child-JVM chunks completed with exit code 0.

## Comparison with v0.8.8.3 baseline

The v0.8.8.3 release report recorded:

- **8,192/8,192 PASS**
- **0 failure clusters**
- no OOM/crash/timeout
- per-four-round heap drift around **~98 KB**

v0.8.8.4 therefore preserves the same fuzz security outcome while its maximum observed chunk drift in this rerun is **92,448 B (~90.3 KiB)**, still far below the **12 MiB** release ceiling. The older v0.8.8.3 report did not publish an exact maximum round-time value, so no direct round-latency comparison is claimed.

| Gate | v0.8.8.3 baseline | v0.8.8.4 rerun |
|---|---:|---:|
| deterministic cases | 8,192/8,192 PASS | **8,192/8,192 PASS** |
| failure clusters | 0 | **0** |
| catastrophic exits | 0 | **0** |
| OOM/crash/timeout | 0 | **0** |
| chunk heap drift | ~98 KB | **max 92,448 B** |
| heap ceiling | 12 MiB | **12 MiB** |

## Release status

The earlier v0.8.8.4 note saying the full 8,192-case fuzz-derived endurance suite had not been rerun is superseded by this report. With this rerun, v0.8.8.4 has a complete deterministic fuzz-derived PASS record in addition to its previously recorded performance, JVM-regression, malformed/unsupported, clean-room and Kotlin-only checks.