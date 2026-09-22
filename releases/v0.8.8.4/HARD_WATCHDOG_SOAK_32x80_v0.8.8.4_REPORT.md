# VXP-Core v0.8.8.4 — Extended hard-watchdog soak 32×80

## Scope

This extends the validated same-JVM hard-watchdog soak from **16 × 80 frames (1,280 frames)** to **32 × 80 frames (2,560 measured frames)** on the same reconstructed v0.8.8.4 watchdog candidate.

Target:

- `Chetaslua(1).vxp`
- SHA-256: `9e7ce08d33f5f6caccf446a1ca6db4ee91c39eacd38b05f0fbf234b9cb041b85`
- runtime version: `0.8.8.4`
- watchdog candidate JAR SHA-256: `c0332941e044719159b0dd7e9cad197bdb6542a85d32d6b005ede23523b9d7b2`
- 3 warm-up cycles, then 32 measured cycles
- 80 frames per measured cycle
- per-run `maxRuntimeMs`: 60,000 ms
- same JVM retained for all warm-up and measured cycles

## Deterministic guest work

Every measured cycle completed exactly:

`80 frames / 448,586,950 instructions / 79 events / 77 timers / timeout=false`

Aggregate work:

- cycles: **32/32 PASS**
- total frames: **2,560**
- total guest instructions: **14,354,782,400**
- timeouts: **0**
- crashes/abnormal exits: **0**
- process exit status: **0**

## Performance stability

- median FPS: **10.716**
- p95 FPS: **11.358**
- min / max FPS: **7.954 / 11.481**
- median MIPS: **60.090**
- first-half median FPS: **10.621**
- second-half median FPS: **10.936**
- second-half delta: **+2.97%**

The extended second half is faster than the first half, so there is no sustained FPS collapse after the previous 1,280-frame validation point. Individual host-side dips recovered without changing the guest-work signature.

For comparison, the prior independent 16×80 rerun recorded **10.723 median FPS**, **-1.24%** second-half delta and **2,616 B** end heap growth. The 32×80 extension remains effectively stable at **10.716 FPS** while doubling measured guest work.

## Heap stability

Retained heap after GC between cycles:

- pre-measure heap: **2,971,640 B**
- first measured heap: **2,976,848 B**
- minimum heap: **2,976,848 B**
- peak heap: **2,980,368 B**
- end heap: **2,980,368 B**
- steady peak drift: **3,520 B**
- end retained growth: **3,520 B**
- absolute drift ceiling: **67,108,864 B**
- end-growth ceiling: **2,097,152 B**

Both heap gates PASS by a wide margin. End retained growth is only about **3.44 KiB** after 2,560 measured frames.

## Result

**PASS.** The v0.8.8.4 hard-watchdog runtime remains stable through **32 × 80 measured cycles**, doubling the prior 1,280-frame soak to **2,560 frames** with exact guest semantics, zero timeout/crash, stable FPS, and negligible retained heap growth.
