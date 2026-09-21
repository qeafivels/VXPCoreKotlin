# VXP-Core v0.8.8.4 — Hard-watchdog + 16×80-frame soak report

## Scope

This patch fixes the v0.8.8.4 long-soak failure where a guest callback could remain inside `ArmCpu.callGuest*()` long enough that the event-loop `maxRuntimeMs` check could not regain control.

Target workload:

- `Chetaslua(1).vxp`
- SHA-256: `9e7ce08d33f5f6caccf446a1ca6db4ee91c39eacd38b05f0fbf234b9cb041b85`
- public runtime version: `0.8.8.4`

## Hard-watchdog implementation

`ArmCpu` now accepts an absolute monotonic wall deadline for guest callbacks. While executing guest instructions it checks the deadline every 4,096 guest instructions and throws a dedicated `GuestCallbackWallTimeoutException` when the hard wall is exceeded.

`MreEventLoop` now propagates the earlier of:

1. the overall run deadline derived from `maxRuntimeMs`, and
2. a 30-second per-callback hard wall.

The timeout exception is converted into `Stats.timedOut=true`, so a stuck callback can no longer hide the event-loop timeout indefinitely. Existing adaptive instruction-budget behavior remains unchanged for calls that do not supply a wall deadline.

## Watchdog regression

- infinite ARM `B .` callback with a huge instruction budget: interrupted in **46.747 ms**, PASS;
- event-loop with stuck `vm_main` and `maxRuntimeMs=60 ms`: interrupted in **69.248 ms**, `timedOut=true`, PASS;
- adaptive callback >20M instructions still extends and completes normally, PASS;
- cached ARM/Thumb invalidation, self-modifying block, callback ABI, memory fast paths, input priority and timer/vibrator targeted regressions: PASS;
- clean-room source check: PASS;
- Kotlin-only source check: PASS.

## Long soak gate — 16 × 80 frames

One JVM was kept alive for all measured cycles after three warm-up cycles.

Every cycle completed the exact same semantic signature:

`80 frames / 448,586,950 instructions / 79 events / 77 timers / timeout=false`

Aggregate measured work:

- cycles: **16/16 PASS**
- frames: **1,280**
- guest instructions: **7,177,391,200**
- timeouts: **0**
- crashes: **0**
- median FPS: **5.442**
- p95 FPS: **5.974**
- min / max FPS: **4.461 / 5.999**
- median MIPS: **30.516**
- first-half median FPS: **5.442**
- second-half median FPS: **5.467**
- second-half delta: **+0.46%**

There is therefore no sustained FPS collapse in the measured second half.

## Heap gate

After GC between cycles:

- first measured heap: **2,746,224 B**
- minimum heap: **2,746,224 B**
- peak heap: **2,976,408 B**
- end heap: **2,976,408 B**
- steady peak drift: **230,184 B**
- end retained growth: **230,184 B**
- absolute drift ceiling: **67,108,864 B**
- end-growth ceiling: **2,097,152 B**

Both heap gates PASS by a wide margin.

## Result

**PASS.** The hard-watchdog restores bounded execution for stuck guest callbacks, and the v0.8.8.4 runtime completed the requested 16 × 80-frame same-JVM soak with stable semantic work, no timeout/crash, no sustained FPS degradation, and low retained heap growth.