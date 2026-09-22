# VXP-Core v0.8.8.4 — Independent hard-watchdog soak rerun

## Scope

This is an independent rerun of the post-release v0.8.8.4 hard-watchdog validation already published in commit `db69ccadc839b77a61a92b52ba54274d71c6d6eb`.

The candidate was rebuilt from the persisted v0.8.8.3 source snapshot plus the v0.8.8.4 ARM/Lua hot-block patch, then the hard-watchdog source change was applied and compiled as replacement runtime classes. The test did not reuse the previously published soak result.

Target workload:

- `Chetaslua(1).vxp`
- SHA-256: `9e7ce08d33f5f6caccf446a1ca6db4ee91c39eacd38b05f0fbf234b9cb041b85`
- runtime version: `0.8.8.4`
- rebuilt watchdog candidate JAR SHA-256: `c0332941e044719159b0dd7e9cad197bdb6542a85d32d6b005ede23523b9d7b2`

## Hard-watchdog behavior

`ArmCpu.callGuest*()` receives an absolute monotonic deadline and checks it inside the guest interpreter every 4,096 guest instructions. `MreEventLoop` passes the earlier of the overall run deadline and the per-callback 30-second hard wall into each callback and converts `GuestCallbackWallTimeoutException` into `Stats.timedOut=true`.

Independent targeted checks:

- infinite ARM `B .` callback: interrupted in **116.592 ms**, PASS;
- stuck event-loop `vm_main` with a 60 ms run limit: interrupted in **250.113 ms**, `timedOut=true`, PASS;
- adaptive callback watchdog >20M valid callback: PASS;
- ARM/Thumb basic-block cache + executable-write invalidation: PASS;
- in-block self-modifying executable write invalidation: PASS;
- legacy + allocation-light guest callback ABI: PASS;
- clean-room source check: PASS;
- Kotlin-only source check: PASS.

## Soak gate

One JVM was kept alive for the full run. Three 80-frame warm-up cycles were completed before measurement, followed by **16 measured cycles × 80 frames**.

Every measured cycle produced the exact same guest-work signature:

`80 frames / 448,586,950 instructions / 79 events / 77 timers / timeout=false`

Aggregate measured result:

- cycles: **16/16 PASS**
- total frames: **1,280**
- total guest instructions: **7,177,391,200**
- runtime timeouts: **0**
- crashes/abnormal exits: **0**
- median FPS: **10.723**
- p95 FPS: **10.938**
- min / max FPS: **7.381 / 10.958**
- median MIPS: **60.129**
- first-half median FPS: **10.748**
- second-half median FPS: **10.614**
- second-half delta: **-1.24%**

The one slower host cycle did not alter guest work and later cycles recovered. The second-half median stayed within the no-collapse gate (> -10%). Absolute FPS differs from the earlier published run because host/JVM conditions differ; the deterministic guest-work signature is identical.

## Heap gate

Retained heap was sampled after repeated GC between cycles:

- pre-measure heap: **3,040,472 B**
- first measured heap: **3,045,192 B**
- minimum heap: **3,045,192 B**
- peak heap: **3,047,808 B**
- end heap: **3,047,808 B**
- steady peak drift: **2,616 B**
- end retained growth: **2,616 B**
- absolute drift ceiling: **67,108,864 B**
- end-growth ceiling: **2,097,152 B**

Both heap gates PASS by a wide margin.

## Result

**PASS.** The independently rebuilt v0.8.8.4 hard-watchdog candidate bounded stuck guest callbacks and completed the requested same-JVM **16 × 80-frame** soak with stable deterministic work, zero timeout/crash, no sustained FPS collapse, and negligible retained heap growth.
