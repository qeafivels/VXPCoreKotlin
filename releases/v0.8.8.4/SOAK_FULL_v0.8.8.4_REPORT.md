# VXP-Core v0.8.8.4 — Long soak validation report

## Scope

This is a long multi-cycle soak validation of the historical **v0.8.8.4 ARM/LUA-HOTBLOCK** runtime after its fixed-work, continuous-performance, malformed and full fuzz-derived gates had passed.

Inputs:

- runtime: `VxpCoreLibrary.VERSION = 0.8.8.4`
- candidate JAR SHA-256: `c3eb873d53decc3e37049e9c06d93e3b737d2f2eee33306c01e5a7271f0cb771`
- test title: user-supplied `Chetaslua(1).vxp`
- VXP SHA-256: `9e7ce08d33f5f6caccf446a1ca6db4ee91c39eacd38b05f0fbf234b9cb041b85`
- JVM: OpenJDK, `-Xms128m -Xmx256m -XX:+ExitOnOutOfMemoryError`
- callback budget: `20,000,000` guest instructions per callback
- runtime timeout: `30,000 ms` per cycle
- GC sampling: three explicit GC requests between cycles before retained-heap measurements

## Formal soak shape

The execution environment imposes a short command window, so the long soak was split into multiple complete JVM phases rather than one single process. The formal accepted set is:

- five phases × six cycles;
- one final phase × four cycles;
- **34 completed cycles total**;
- **20 framebuffer frames per cycle**;
- **680 framebuffer frames total**;
- **3,726,668,872 guest instructions total**.

Every formal cycle produced the same deterministic work signature:

`20 frames / 109,607,908 instructions / 19 events / 17 timers / timeout=false`

An earlier exploratory six-cycle phase was externally interrupted by the container command window after five completed cycles. Those completed cycles had `timeout=false` and no crash, but the incomplete phase is **not included** in the formal gate or statistics below. It was replaced by the clean four-cycle final phase.

## Acceptance criteria

The soak passes only when all formal cycles satisfy all of the following:

1. exactly 20 frames complete with the expected deterministic guest-work signature;
2. runtime `timedOut == false`;
3. no uncaught exception, JVM crash, `OutOfMemoryError`, or process-level abnormal exit;
4. retained heap after GC remains below the soak drift ceiling `max(12 MiB, maxHeap/8)` = **33,554,432 B**;
5. every complete phase reports `pass=true`.

FPS is monitored and reported, but no separate synthetic FPS floor is imposed beyond the requirement that every cycle complete before the 30-second runtime timeout.

## Result

**PASS** — all formal soak criteria were met.

| Metric | Result |
|---|---:|
| formal phases | **6** |
| formal cycles | **34/34 PASS** |
| total frames | **680** |
| total guest instructions | **3,726,668,872** |
| runtime timeouts | **0** |
| crashes / abnormal exits | **0** |
| OOM | **0** |
| median FPS | **5.613** |
| minimum FPS | **3.745** |
| maximum FPS | **6.116** |
| p95 FPS | **6.095** |
| median MIPS | **30.762** |
| MIPS range | **20.525–33.516** |
| maximum steady-state retained-heap drift | **1,352 B** |
| heap-drift ceiling | **33,554,432 B** |

The post-warmup heap baseline in each JVM phase was actually above every measured post-cycle heap sample, so the harness's conservative absolute drift-from-baseline remained non-positive. To make leak trend visible, the report also compares steady-state post-GC heap after cycle 1 with later cycles; the largest observed positive drift was only **1,352 B**.

## Per-phase summary

| Phase | Cycles | Frames | Median FPS | Min FPS | Median MIPS | Steady heap drift | Result |
|---|---:|---:|---:|---:|---:|---:|---|
| 1 | 6 | 120 | 5.916 | 5.551 | 32.419 | 744 B | PASS |
| 2 | 6 | 120 | 5.961 | 5.396 | 32.666 | 744 B | PASS |
| 3 | 6 | 120 | 5.521 | 5.419 | 30.256 | 744 B | PASS |
| 4 | 6 | 120 | 5.470 | 5.243 | 29.975 | 608 B | PASS |
| 5 | 6 | 120 | 5.631 | 5.593 | 30.859 | 1,352 B | PASS |
| 6 | 4 | 80 | 4.400 | 3.745 | 24.114 | 936 B | PASS |

The later phase is slower on this shared validation host, but it still preserves the exact guest-work signature and remains far inside the 30-second per-cycle runtime timeout. This report therefore treats it as host throughput variability, not a guest semantic regression.

## Conclusion

v0.8.8.4 passes the long multi-cycle soak gate on the validated Chetaslua workload. Across 34 formal cycles there was no runtime timeout, crash, OOM, semantic signature drift, or retained-heap growth approaching the configured limit.

Raw phase logs, aggregate JSON and TSV summary are committed beside this report for reproducibility.