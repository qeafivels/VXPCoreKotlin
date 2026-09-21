# VXP-Core Library (Kotlin-only)

A Kotlin-only core library for running `.vxp` packages inside an existing Android UI.
No `Activity / View / Compose`, no `JNI / NDK / CMake / C / C++`, no `System.loadLibrary`.
Guest ARM/Thumb code in `.vxp` is data executed by a Kotlin interpreter, never as Android native code.

```
VXP file
 -> AndroidVxpCore
 -> Kotlin backend (ARM/runtime or Flash Lite)
 -> FrameSnapshot RGB565
 -> your controller
 -> existing EmulatorScreenCanvas / UI
```

## Credits

Maintained by **DOXUANHOP**.

- Website: https://qeafivels.com/
- Namespace: `vn.com.doxuanhop.vxpcore.android`
- If you use this library in an app or article, please credit with a link to https://qeafivels.com/.

## Versions in this repo

| Folder | Version (`VxpCoreLibrary.VERSION` / `AndroidVxpCore.VERSION`) | Artifact | Notes |
|---|---|---|---|
| `VXP-Core-Library-v0.8/` | `0.8.0` | `dist/vxp-core-0.8.0.jar` | First library split, UI removed from deliverable |
| `VXP-Core-Library-v0.8.2/` | `0.8.2` (core + Android facade in sync) | `dist/vxp-core-0.8.2.jar` | Thumb ALU + messaging sandbox + long regression |
| `VXP-Core-Library-v0.8.3/` | `0.8.3` (core + Android facade in sync) | `dist/vxp-core-0.8.3.jar` | ELF/GCC compat (R_ARM_RELATIVE, gcc_entry, CLZ/LDRD/long-multiply) + 3 new ELF samples |
| `VXP-Core-Library-v0.8.3-cleanroom/` | `0.8.3` clean-room edition | `dist/vxp-core-0.8.3-cleanroom.jar` | Rebased Kotlin-only, no SDK-derived catalog, + provenance/compliance docs |
| `VXP-Core-Library-v0.8.4.1/` | `0.8.4.1` clean-room edition | `dist/vxp-core-0.8.4.1.jar` | SYSTEM/GRAPHICS/FILE_RESOURCE alias pass, 76/76 observed symbols first-class |
| `VXP-Core-Library-v0.8.4.2/` | `0.8.4.2` clean-room edition | `dist/vxp-core-0.8.4.2.jar` | Intermediate, FILE_RESOURCE directory/path/resource-from-file pass |
| `VXP-Core-Library-v0.8.4.4/` | `0.8.4.4` clean-room edition | `dist/vxp-core-0.8.4.4.jar` | Latest, AUDIO bridge + playback accuracy + Android audio-system integration |

See `VERSIONS.md` for the full version matrix, and
`VXP-Core-Library-v0.8.4.4/CHANGELOG.md` for details.
The checked-in historical source folders stop at `v0.8.4.4`; newer validated runtime deltas are published under `releases/`. The latest validated runtime milestone is `v0.8.9.1`.

## Latest validated release artifacts

### v0.8.9.1 — ARM-DECODE-TUNE

The latest validated runtime line is **v0.8.9.1**. This follow-up tunes ARM-heavy workloads with a larger bounded decode cache, a dedicated cached ARM `SUB` path, and a repeated alternating-order performance gate.

Release validation on the user-supplied `Chetaslua.vxp` target (SHA-256 `9e7ce08d33f5f6caccf446a1ca6db4ee91c39eacd38b05f0fbf234b9cb041b85`):
- Fixed-work signature: **205,398,835 guest instructions / 40 frames / 39 events / 37 timers / no timeout**, unchanged.
- Prior v0.8.9 release JAR: **68.294 → 69.372 MIPS (+1.58%)**.
- Fixed-work FPS-equivalent: **13.299 → 13.510 (+1.59%)**.
- Same-toolchain source A/B: **+1.18%**.
- JVM/core regression: **38/38 PASS**.
- Malformed/unsupported gate: **33/33 PASS**.
- Deterministic fuzz-derived gate: **8,192/8,192 PASS**, 0 catastrophic exits, 0 deduplicated failure clusters.
- Kotlin-only and clean-room source checks: **PASS**.

Accepted changes are the **512 → 1024 decode-cache slot** increase, dedicated cached/reference ARM `SUB` handling, and the repeated performance gate. Measured alternatives that did not win the repeated median—2048 slots, packed metadata, cached condition metadata, and BX/BLX-register specialization—were rejected.

Release materials are under `releases/v0.8.9.1/`; the user-supplied VXP binary is test-only and is not committed.

### v0.8.9 — ARM-JIT-FASTPATH

The latest validated runtime line is **v0.8.9**. This release extends the JIT-friendly cached execution strategy to ARM-heavy VXP workloads while preserving guest work, timer cadence, framebuffer behavior, and executable-write invalidation.

Release validation on the user-supplied `Chetaslua.vxp` target (SHA-256 `9e7ce08d33f5f6caccf446a1ca6db4ee91c39eacd38b05f0fbf234b9cb041b85`):
- Fixed-work signature: **205,398,835 guest instructions / 40 frames / 39 events / 37 timers**, identical on both versions.
- Warm median throughput: **50.365 → 64.659 MIPS (+28.38%)**.
- Fixed-work equivalent FPS: **9.808 → 12.592 (+28.38%)**.
- Warm median 40-frame wall time: **4078.242 → 3176.664 ms (-22.11%)**.
- Core/self-contained regressions plus prepared/real VXP smokes: **39/39 PASS**.
- Fixed malformed/unsupported gate: **33/33 PASS**.
- Deterministic fuzz-derived gate: **8,192/8,192 PASS**, 0 catastrophic exits, 0 deduplicated failure clusters.
- Clean-room Kotlin-only architecture: preserved.

v0.8.9 adds deterministic fixed-frame A/B performance gating so future optimizations must preserve the exact guest-work signature before speedup is accepted. Release materials are under `releases/v0.8.9/`; the user-supplied VXP binary is test-only and is not committed.

### v0.8.8.4 — ARM/LUA-HOTBLOCK (historical validated milestone)

This historical milestone extends the v0.8.8.3 cached execution path to ARM-heavy Lua workloads. It is retained for reproducibility; newer validated milestones remain v0.8.9 and v0.8.9.1.

Validation on Chetaslua SHA-256 `9e7ce08d33f5f6caccf446a1ca6db4ee91c39eacd38b05f0fbf234b9cb041b85`:
- fixed-work signature: **40 frames / 205,398,835 instructions / 39 events / 37 timers**, identical;
- warm median throughput: **49.097 → 63.389 MIPS (+29.11%)**;
- fixed-work equivalent FPS: **9.561 → 12.345 (+29.12%)**;
- 40-frame wall time: **4183.567 → 3240.267 ms (-22.55%)**;
- continuous 1.8 s gate: **15.00 median FPS**, **15.56 p95 FPS**, PASS;
- JVM regressions: **37/37 PASS**;
- fixed malformed/unsupported gate: **33/33 PASS**;
- clean-room and Kotlin-only checks: **PASS**.

The full deterministic fuzz-derived endurance suite was subsequently rerun: **8,192/8,192 PASS**, **0 failure clusters**, **0 catastrophic exits**, with maximum four-round chunk heap drift **92,448 B** against a **12,582,912 B** ceiling. See `releases/v0.8.8.4/FUZZ_DERIVED_FULL_v0.8.8.4_REPORT.md`. Release materials are under `releases/v0.8.8.4/`; the benchmark binary is not committed.

Hard-watchdog follow-up: stuck guest callbacks are now bounded by an in-interpreter wall deadline. The same-JVM **16 × 80-frame** Chetaslua soak passed **16/16 cycles**, **1,280 frames**, **0 timeout**, **0 crash**, median **5.442 FPS**, second-half median **+0.46%**, and **230,184 B** end retained heap growth. See `releases/v0.8.8.4/HARD_WATCHDOG_SOAK_v0.8.8.4_REPORT.md`.

## Supported backends

| VXP type | Backend | Status |
|---|---|---|
| `ELF32 ARM` | Kotlin ARM/Thumb + compatibility runtime | Supported |
| Raw ARM + zlib (`RAW_ARM_ZLIB`) | Kotlin ARM/Thumb + compatibility runtime | Supported |
| Flash Lite `FWS / CWS` | Android Kotlin + SWF/AVM1 (`android.graphics`) | Compatibility-first |
| Unknown / proprietary | Detector | Returns `UNKNOWN`, no fallback to another emulator |

Validated (user-supplied sample binaries, test-only, never bundled):

- *Sample A* (`RAW_ARM_ZLIB`): 23,524,795 instructions / 425 frames / 440 events / 424 timers, 240x320 RGB565, no CPU/memory fault, `stubbedSymbols = []` (v0.8.2; re-validated in v0.8.3 with 78 frames / 9.4M instr, no stubs).
- *Sample B* (`FLASH_LITE`): stage 176x220, 20 FPS, 35 frames, 25 shapes / 12 sprites / 5 JPEG3 / 7 buttons, startup menu frame 4, `OK` runs a real AVM1 button action to frame 5.
- *v0.8.3 new ELF samples*: gameplay sample (218 frames / 28.3M instr), launcher-menu sample with file enumeration (18 frames / 11.8M instr, real `vm_find_first/next/close` + UCS2 NUL fix), 3D scene sample (9 frames / 33.3M instr).
- *v0.8.3-cleanroom re-validation*: same corpus re-run on the rebased Kotlin-only tree — 218f / 28f / 9f / 82f across samples, menu flow intact. `verify_clean_room.sh` PASS.
- *v0.8.4.1 SYSTEM/GRAPHICS/FILE_RESOURCE pass*: tick/resolver/callback aliases, `vm_sscanf` subset, sandbox disk free-space; graphics `screen_w/h`, image buffer/property/load/release aliases, `create_layer_ex` first-class, image mirror software path; file dual `get_file_size`, hardened open/append, resource init/load aliases. Observed corpus: 76/76 unique `vm_*` first-class, 0 missing.
- *v0.8.4.1 timed runs*: 30.8M instr / 218 frames, 26.8M / 39 frames, 33.3M / 9 frames, 9.4M / 83 frames `stubbedSymbols=[]`, Flash Lite menu 4 → OK (10 AVM1) → frame 5.
- *v0.8.4.2 FILE_RESOURCE directory pass*: first-class `vm_file_copy/tell/is_eof/get_modify_time`, hardened copy/rename (same-path, read-only dest, cross-drive, type conflict), guest path helpers (`default_folder_path/filename/path`, host path never leaked), READ_ONLY/HIDDEN/SYSTEM/ARCHIVE attributes via host-side metadata, resource-from-file APIs (`get_resource_offset[_from_file]`, `load_resource_from_file`, `get_data_from_file`, `res_delete/deinit`) with sandbox copy (no host mmap).
- *v0.8.4.3/0.8.4.4 AUDIO*: host-neutral `MreAudioHost/Request/Snapshot/Event` in `vxp-core` (no Android imports); first-class play/pause/resume/stop/is-playing/get-time/volume/interrupt + MIDI lifecycle; guest buffers copied, file paths sandboxed (32 MiB ceiling); completion marshalled via `MreEventLoop` (codes 1/-1/2/3). v0.8.4.4 adds duration/seek/loop fields, pure-Kotlin RIFF/WAVE + MIDI-PPQN probe, `AudioTrack` base-frame + head-delta position, seek/loop-aware completion marker, `MediaPlayer` duration/seek/loop, Context-aware `open(...)` with audio focus (transient pause/resume, ducking, abandon on stop), `onHostPause/onHostResume/audioSnapshot/seekAudioTo/setAudioLooping`. Corpus smoke after audio: 186f / 91f / 2f, framebuffer hashes unchanged, 76/76 observed first-class.

User-supplied sample binaries are test-only and are not bundled in the ZIP/JAR.

## Requirements / related installs

| Tool | Version required | Notes |
|---|---|---|
| JDK | 17+ (`jvmToolchain(17)`, `jvmTarget = "17"`) | `vxp-core` is pure Kotlin/JVM |
| Gradle | 8.x recommended | Wrapper not bundled; use your Android project Gradle |
| Android Gradle Plugin | `8.7.3` | See root `build.gradle.kts` |
| Kotlin | `2.0.21` (`kotlin.jvm` / `kotlin.android`) | See root `build.gradle.kts` |
| Android SDK | `compileSdk 35`, `minSdk 23` | See `vxp-core-android/build.gradle.kts` |
| Repositories | `google()`, `mavenCentral()` | `FAIL_ON_PROJECT_REPOS` mode |
| GitHub Desktop (optional) | any recent | Only for cloning/publishing this repo |

No NDK, CMake, `abiFilters`, `jniLibs`, or `externalNativeBuild` is needed.
Verify with:

```bash
./verify_kotlin_only.sh
# OK: vxp-core + vxp-core-android are Kotlin-only (no JNI/NDK/C/C++).
./verify_clean_room.sh   # cleanroom edition only
```

## Installation

1. Copy `vxp-core/` and `vxp-core-android/` from `VXP-Core-Library-v0.8.4.4/` into your Android project.
2. Register modules in `settings.gradle.kts`:

```kotlin
include(":vxp-core")
include(":vxp-core-android")
```

3. Depend only on the Android host (it pulls `vxp-core` automatically):

```kotlin
dependencies {
    implementation(project(":vxp-core-android"))
}
```

Or drop in the prebuilt artifact:

```kotlin
dependencies {
    implementation(files("libs/vxp-core-0.8.4.4.jar"))
}
```

## Usage

```kotlin
val session = AndroidVxpCore.open(
    context = context, // recommended overload: enables audio focus/interruption
    bytes = vxpBytes,
    fileName = fileName,
    storageRoot = File(context.filesDir, "vxp_runtime"),
    listener = object : VxpCoreListener {
        override fun onFrame(frame: FrameSnapshot) {
            // frame.pixels = RGB565; render into your existing UI.
            // Callbacks may arrive on a worker thread.
        }
        override fun onState(state: VxpSessionState) {
            // Starting / Running / Stopped / Failed
        }
    }
)
session.start()

// Host lifecycle (audio-aware):
// session.onHostPause() in Activity/Fragment onPause()
// session.onHostResume() in onResume()

// Audio state (host-facing; guest ABI stays inside MreRuntime):
// val snap = session.audioSnapshot() // positionMs / durationMs
// session.seekAudioTo(1500); session.setAudioLooping(true)

// Legacy keypad keys from your existing controller:
session.keyDownLegacy(5) // OK
session.keyUpLegacy(5)
session.keyDownLegacy(6) // LSK
session.keyDownLegacy(7) // RSK

// Touch (main path for the ARM backend):
session.penDown(x, y)
session.penMove(x, y)
session.penUp(x, y)

session.stop()
session.close()
```

Legacy key map: `1 UP, 2 DOWN, 3 LEFT, 4 RIGHT, 5 OK, 6 LSK, 7 RSK, 10 CLEAR, 48-57 digits, 42 *, 35 #`.

Important: show boot text only before the first frame. Once `onFrame()` fires while `Running`,
the guest framebuffer is the only LCD source. Do not overlay instruction counters on it.
The old native-bridge path has been removed.

See `VXP-Core-Library-v0.8.4.4/docs/INTEGRATE_EXISTING_UI.md`.

## Project layout (v0.8.4.4)

```
VXP-Core-Library-v0.8.4.4/
  vxp-core/            # ARM CPU, ELF, compatibility runtime, heap, file, graphics, PNG decoder, MreAudioHost/Probe
  vxp-core-android/    # AndroidVxpCore session, Flash Lite backend, Rgb565BitmapAdapter, text rasterizer, AndroidMreAudioHost
  tests/jvm/           # CPU/ALU, BLX, halfword, PC-semantics, PNG, graphics/file/resource, audio regressions
  tests/android-graphics-jvm/  # Flash Lite backend test with android.graphics stubs
  tools/               # observed-symbol surface scanner (reads user-supplied binaries only)
  validation/          # real-run logs, frame sha256, PNG screenshots, AUDIO/COMPATIBILITY reports
  docs/                # TEST_RESULTS.md, INTEGRATE_EXISTING_UI.md, clean-room policy docs
  dist/vxp-core-0.8.4.4.jar
```

## Safety

Messaging-related guest calls (e.g. `vm_send_sms`) are sandboxed compatibility APIs that
always return failure. The core never performs billed or off-device actions on behalf of guests.

## Links

- Project site: https://qeafivels.com/
- Version matrix: `./VERSIONS.md`
- v0.8.4.4 README: `./VXP-Core-Library-v0.8.4.4/README.md`
- Clean-room policy: `./VXP-Core-Library-v0.8.4.4/docs/CLEAN_ROOM_POLICY.md`, `docs/PROVENANCE.md`, `docs/OBSERVED_COMPATIBILITY_SURFACE.md`, `docs/COMMERCIAL_DISTRIBUTION_CHECKLIST.md`
- v0.8.4.4 validation: `./VXP-Core-Library-v0.8.4.4/validation/COMPATIBILITY_v0.8.4.4.md`, `validation/AUDIO_v0.8.4.4.md`
- Integration guide: `./VXP-Core-Library-v0.8.4.4/docs/INTEGRATE_EXISTING_UI.md`
- Test results: `./VXP-Core-Library-v0.8.4.4/docs/TEST_RESULTS.md`
