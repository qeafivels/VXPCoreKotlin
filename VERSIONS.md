# VXP-Core Library — Tổng hợp các phiên bản

Thư viện lõi Kotlin-only chạy `.vxp` để nhúng vào UI Android hiện có.
Không `Activity/View/Compose`, không `JNI/NDK/CMake/C++`, không `System.loadLibrary`.
ARM/Thumb trong `.vxp` là dữ liệu guest do interpreter Kotlin thực thi.

```
VXP file
 -> AndroidVxpCore
 -> Kotlin backend (ARM/runtime hoặc Flash Lite)
 -> FrameSnapshot RGB565
 -> controller của bạn
 -> EmulatorScreenCanvas / UI hiện có
```

## 1. Danh sách phiên bản trong repo

| Thư mục | Version code | `dist` | Trạng thái |
|---|---|---|---|
| `VXP-Core-Library-v0.8/` | `0.8.0` (`VxpCoreLibrary.VERSION`, `AndroidVxpCore.VERSION`) | `dist/vxp-core-0.8.0.jar` | Thư viện lõi đầu tiên, bỏ app/demo UI |
| `VXP-Core-Library-v0.8.2/` | `0.8.2` (đồng bộ core + Android facade) | `dist/vxp-core-0.8.2.jar` | Thumb ALU + messaging sandbox + regression dài |
| `VXP-Core-Library-v0.8.3/` | `0.8.3` (đồng bộ core + Android facade) | `dist/vxp-core-0.8.3.jar` | Tương thích ELF/GCC + 3 sample ELF mới |
| `VXP-Core-Library-v0.8.3-cleanroom/` | `0.8.3` clean-room (đồng bộ core + Android facade) | `dist/vxp-core-0.8.3-cleanroom.jar` | Rebase Kotlin-only, bỏ catalog suy từ SDK, + docs provenance/compliance |
| `VXP-Core-Library-v0.8.4.1/` | `0.8.4.1` clean-room (đồng bộ core + Android facade) | `dist/vxp-core-0.8.4.1.jar` | SYSTEM/GRAPHICS/FILE_RESOURCE alias pass, 76/76 observed first-class |
| `VXP-Core-Library-v0.8.4.2/` | `0.8.4.2` clean-room (đồng bộ core + Android facade) | `dist/vxp-core-0.8.4.2.jar` | Bản trung gian: FILE_RESOURCE directory/path/resource-from-file pass (151 files) |
| `VXP-Core-Library-v0.8.4.4/` | `0.8.4.4` clean-room (đồng bộ core + Android facade) | `dist/vxp-core-0.8.4.4.jar` | Mới nhất: AUDIO bridge + playback accuracy + tích hợp audio-system Android (179 files) |

> Ghi chú: `CHANGELOG v0.8.2` có nhắc patch `v0.8.1` (PNG, Thumb BLX immediate, App Manager, operator-code)
> nhưng trong repo hiện chỉ lưu các gói `v0.8`, `v0.8.2`, `v0.8.3`, `v0.8.3-cleanroom`, `v0.8.4.1`.

## 1.1 Release validation mới nhất — v0.8.9

**v0.8.9 — ARM-JIT-FASTPATH** là mốc validation mới nhất của nhánh Kotlin-only. Release artifacts được lưu tại `releases/v0.8.9/`.

Trên đúng file kiểm thử do người dùng cung cấp `Chetaslua.vxp` (SHA-256 `9e7ce08d33f5f6caccf446a1ca6db4ee91c39eacd38b05f0fbf234b9cb041b85`):

- Fixed-work giữ nguyên tuyệt đối: **205.398.835 instructions / 40 frames / 39 events / 37 timers**.
- Warm median: **50,365 → 64,659 MIPS (+28,38%)**.
- Fixed-work equivalent FPS: **9,808 → 12,592 (+28,38%)**.
- Thời gian median cho 40 frame: **4078,242 → 3176,664 ms (-22,11%)**.
- Regression core + prepared/real VXP smoke: **39/39 PASS**.
- Malformed/unsupported fixed gate: **33/33 PASS**.
- Fuzz-derived deterministic: **8.192/8.192 PASS**, 0 catastrophic exit, 0 failure cluster.
- Kiến trúc clean-room/Kotlin-only được giữ nguyên.

Bản này bổ sung ARM cached fast path cho LDM/STM, word LDR/STR immediate/register và hot ADD/MOV, đồng thời thêm fixed-frame A/B performance gate. Gate chỉ chấp nhận tối ưu khi signature guest-work hoàn toàn giống baseline, tránh tăng FPS giả bằng cách thay timer cadence hoặc bỏ bớt công việc guest.

Mốc này **không** được mô tả là tương thích 100% với mọi file VXP/MRE; compatibility chỉ được khẳng định trong phạm vi corpus và release gates đã kiểm thử.

## 2. Module chung

| Module | Vai trò |
|---|---|
| `vxp-core` | Lõi Kotlin/JVM trung lập: ELF ARM + RAW_ARM_ZLIB, compatibility runtime, heap, file, graphics RGB565, input/timer |
| `vxp-core-android` | Host Android Kotlin-only: Flash Lite/SWF renderer bằng `android.graphics`, AVM1 menu/input, system font rasterizer, `FrameSnapshot -> Bitmap` |
| `tests/jvm` (từ v0.8.2) | CPU/ALU, BLX, halfword, PC-semantics, PNG, graphics/file/resource regressions (mở rộng dần theo bản) |
| `tests/android-graphics-jvm` (từ v0.8.2) | Flash Lite backend test + `android.graphics` stubs để compile-test trên JVM |
| `tools/` (từ v0.8.4.1) | Script quét symbol quan sát trong binary do người dùng cung cấp |
| `validation/` | Log chạy thật, sha256 frame, ảnh PNG |
| `docs/` | `TEST_RESULTS.md`, `INTEGRATE_EXISTING_UI.md`, docs clean-room |

Phụ thuộc duy nhất cho app:

```kotlin
implementation(project(":vxp-core-android")) // tự kéo theo :vxp-core
```

## 3. Backend hỗ trợ

| Dạng VXP | Backend | v0.8.0 | v0.8.2 | v0.8.3+ |
|---|---|---|---|---|
| `ELF32 ARM` | Kotlin ARM/Thumb + compatibility runtime | Có | Có | Có, +GCC bootstrap/init-array |
| Raw ARM + zlib (`RAW_ARM_ZLIB`) | Kotlin ARM/Thumb + compatibility runtime | Có | Có | Có, giữ regression |
| Flash Lite `FWS/CWS` | Android Kotlin + SWF/AVM1 | Có, compatibility-first | Có, giữ nguyên + test JVM stubs | Có, giữ menu 4→5 |
| Unknown/proprietary | Detector | Trả `UNKNOWN`, không fallback emulator khác | Giữ nguyên | Giữ nguyên |

## 4. Public API (giữ nguyên qua các bản)

```kotlin
val session = AndroidVxpCore.open(
    bytes = vxpBytes,
    fileName = fileName,
    storageRoot = File(context.filesDir, "vxp_runtime"),
    listener = object : VxpCoreListener {
        override fun onFrame(frame: FrameSnapshot) { /* RGB565 -> UI */ }
        override fun onState(state: VxpSessionState) { /* Starting/Running/Stopped/Failed */ }
    }
)
session.start()
session.keyDownLegacy(5) // OK; 1-4 D-pad, 6 LSK, 7 RSK, 10 CLEAR, 48-57 digits, 42 *, 35 #
session.keyUpLegacy(5)
session.penDown(x, y); session.penMove(x, y); session.penUp(x, y)
session.stop(); session.close()
```

Quy tắc UI: **không hiển thị `bootMessage` sau frame đầu tiên** — `onFrame()` là nguồn LCD duy nhất khi `Running`.
Đường native-bridge cũ đã bỏ.

## 5. So sánh regression thực tế

Các binary mẫu do người dùng cung cấp chỉ dùng để kiểm thử, không kèm trong gói.
Dưới đây dùng nhãn chung: Sample A (`RAW_ARM_ZLIB`), Sample B (`FLASH_LITE`),
Sample C/D/E (`ELF_ARM`).

### 5.1 Sample A (`RAW_ARM_ZLIB`)

| Chỉ số | v0.8.0 | v0.8.2 |
|---|---|---|
| Thời gian / quy mô test | 6s, `6.896.572` insn | `23.524.795` insn (log ghi >24M / >700 frames trên demo dài) |
| Frames / events / timer | `76 / 77 / -` | `425 / 440 / 424` |
| Framebuffer | `240x320 RGB565`, sha `7613e735…` | `240x320 RGB565` |
| CPU/memory fault | Không | Không |
| `stubbedSymbols` | `[]` | `[]` |

Fix liên quan Sample A:
- v0.8.0: RAW ARM two-zlib loader, ARM/Thumb halfword/signed loads, ARM BLX immediate, Thumb LDMIA base-in-list, heap split/coalesce/realloc, resource/canvas/blt, text/file runtime, `vm_malloc(0)` không tính OOM, `vm_graphic_mirror` no-op, audio suspend/resume compat.
- v0.8.1 (kế thừa trong v0.8.2): PNG image loading/property, Thumb BLX immediate `F001 E9A4`, App Manager installed-list count query, operator-code output.
- v0.8.2: Thumb ALU `ADC/SBC/ROR/NEG/CMN`, đúng opcode `0x4241 = NEG r1,r0`, `strtoi` thành API thật.

An toàn: guest messaging API (`vm_send_sms`, v0.8.2) luôn trả failure trong sandbox, không thực hiện tác vụ tính phí/gửi mạng thật.

### 5.2 Sample B (`FLASH_LITE`)

| Chỉ số | v0.8.0 | v0.8.2 |
|---|---|---|
| Stage | `176x220, 20 FPS, 35 frames` | Giữ nguyên |
| Shapes/Sprites/Bitmaps/Buttons | `25 / 12 / JPEG3 5 / 7` | Giữ nguyên |
| Menu | frame 4, `OK` -> AVM1 button action -> frame 5 | Giữ nguyên |
| AVM1 actions / inputs / playing | `10 / 1 / true` | Giữ nguyên |
| Kiểm chứng | sha menu `fbf54d2f…`, gameplay `417846c0…` | log validation tương ứng |

Flash backend v0.8.x: parser SWF + player + AVM1 menu/input, `ZWS/LZMA` báo chưa hỗ trợ, giữ rule timeline cho scenery động.

### 5.3 v0.8.3 — 3 sample ELF mới (không kèm ZIP)

| Sample | Backend | Kết quả | Ghi chú |
|---|---|---|---|
| Sample C | `ELF_ARM` | PASS gameplay, 218 frames / 28.3M instr | input, timer, resource, file, graphics active |
| Sample D | `ELF_ARM` | PASS menu launcher, 18 frames / 11.8M instr | `vm_find_first/next/close` thật + UCS2 NUL fix, test file trong sandbox E: |
| Sample E | `ELF_ARM` | PASS scene 3D, 9 frames / 33.3M instr | render cube/sphere/cone 240×320 |

Fix lõi nhờ 3 sample này: `R_ARM_RELATIVE` sym-index-0, bootstrap `gcc_entry` + `.init_array`,
Thumb `BLX register` + PC semantics (+4) + `STRH/LDRH`, ARM `CLZ` + `LDRD/STRD` +
`UMULL/UMLAL/SMULL/SMLAL` + Operand2 PC (+8), `_vm_log_info/_vm_log_error`,
`vm_find_*` wildcard, UCS2→ASCII NUL.

### 5.4 v0.8.3-cleanroom — re-validation trên cây Kotlin-only rebase (không bundle binary)

| Workload | Backend | Kết quả clean-room |
|---|---|---|
| Sample C | `ELF_ARM` | 30.163.160 instr / 218 frames, stable timed run |
| Sample D | `ELF_ARM` | 18.960.170 instr / 28 frames, stable timed run |
| Sample E | `ELF_ARM` | 33.296.269 instr / 9 frames, stable timed run |
| Sample A | `RAW_ARM_ZLIB` | 9.480.936 instr / 82 frames, no unresolved symbols |
| Sample B | `FLASH_LITE` | menu 4 → OK → frame 5 |

- `verify_clean_room.sh`: PASS; không còn file C/C++/JNI/NDK hay catalog sinh từ SDK trong gói.
- Tài liệu mới: `NOTICE-CLEANROOM.txt`, `docs/CLEAN_ROOM_POLICY.md`, `docs/PROVENANCE.md`,
  `docs/COMMERCIAL_DISTRIBUTION_CHECKLIST.md`, `validation/CLEANROOM_VALIDATION_v0.8.3.md`.

### 5.5 v0.8.4.1 — SYSTEM/GRAPHICS/FILE_RESOURCE alias pass (clean-room)

- SYSTEM: `vm_get_tick`, `vm_get_sym_entry`, `vm_reg_key/system_event/touch_callback`,
  spelling alias cho removable-driver query, `vm_sscanf` subset, disk free-space từ sandbox.
- GRAPHICS: `screen_w/h`, image buffer/property/load/release aliases,
  `create_layer_ex` first-class, image mirror software path + safe no-op.
- FILE_RESOURCE: dual `get_file_size`, open/append chặt, resource init/load aliases.
- Corpus quan sát (`tools/observed_vxp_surface.py`): 76 unique `vm_*` / 76 first-class / 0 missing —
  chỉ là corpus coverage, không tuyên bố mọi VXP.
- Timed runs: 30.787.098 instr / 218f, 26.838.009 / 39f,
  33.296.269 / 9f, 9.432.148 / 83f `stubbedSymbols=[]`,
  Flash Lite menu 4 → OK (10 AVM1) → frame 5.
- Thêm test graphics/file/resource + `docs/OBSERVED_COMPATIBILITY_SURFACE.md` (tổng 141 files).

### 5.6 v0.8.4.2 — FILE_RESOURCE directory/path/resource-from-file pass (clean-room, bản trung gian)

- FILE: first-class `vm_file_copy/tell/is_eof/get_modify_time`; copy/rename cứng (same-path, read-only dest, cross-drive, directory/type conflict, metadata remap).
- Path helpers: `vm_get_default_folder_path/filename/path`; host path không lộ ra guest.
- Attributes READ_ONLY/HIDDEN/SYSTEM/ARCHIVE giữ bằng metadata host-side (nhất quán Linux/Android).
- Resource-from-file: `vm_get_resource_offset[_from_file]`, `vm_load_resource_from_file`, `vm_resource_get_data_from_file`, `vm_res_delete/deinit`; external data copy qua sandbox vào guest memory/heap, không mmap file host.
- Regression cũ CPU/Thumb/PNG PASS; corpus 76/76 first-class giữ nguyên.

### 5.7 v0.8.4.3 / v0.8.4.4 — AUDIO host-neutral bridge + playback accuracy (clean-room)

- v0.8.4.3: `MreAudioHost/Request/Snapshot/Event` trong `vxp-core` (không import Android API); handlers play/pause/resume/stop/is-playing/get-time/volume/interrupt + MIDI lifecycle; buffer copy, file qua sandbox (ceiling 32 MiB); completion marshal qua `MreEventLoop`; `StateOnlyMreAudioHost` cho headless/JVM; `AndroidMreAudioHost` (WAV→AudioTrack, encoded/MIDI/file→MediaPlayer, cache cleanup); `open()` nhận optional `audioHost`; `AudioRegression.kt` PASS.
- v0.8.4.4: duration/seek/loop/lifecycle trong `Snapshot/Host`; probe Kotlin thuần RIFF/WAVE + MIDI-PPQN; `AudioTrack` base-frame + head-delta, completion marker sau seek/loop, arm-before-play; `MediaPlayer` duration/seek/loop + start offset; `open(context=...)` + `AndroidMreAudioHost(context, cacheDir)` với audio focus (transient pause/resume, ducking 20%, abandon on stop); API `onHostPause/onHostResume/audioSnapshot/seekAudioTo/setAudioLooping`; callback vẫn qua `MreEventLoop`, không thread Android nào vào `ArmCpu`.
- Smoke sau audio: 186f / 91f / 2f, hash framebuffer không đổi, 76/76 observed first-class; xem `validation/AUDIO_v0.8.4.4.md`, `validation/COMPATIBILITY_v0.8.4.4.md`.
- Tổng 179 files (v0.8.4.4 gộp cả 0.8.4.2 + 0.8.4.3).

## 6. Thay đổi chi tiết

### v0.8.4.4
- AUDIO playback accuracy + tích hợp audio-system Android như mục 5.7; `vxp-core` giữ JVM-neutral.
- Smoke corpus + toàn bộ regression cũ PASS; hash framebuffer không đổi.
- Bản khuyến nghị cho tích hợp/phân phối mới.

### v0.8.4.3
- AUDIO host-neutral bridge + Android playback như mục 5.7 (được gộp trong gói v0.8.4.4).

### v0.8.4.2
- FILE_RESOURCE directory/path/resource-from-file pass như mục 5.6 (được gộp trong gói v0.8.4.4).

### v0.8.4.1
- SYSTEM/GRAPHICS/FILE_RESOURCE first-class aliases như mục 5.5; giữ clean-room (không JNI/NDK/C/C++).
- Tool quét `vm_*` trong binary do người dùng cung cấp + đối chiếu handler Kotlin.
- Thêm test graphics/file/resource + validation PNG/log (`validation/vxp/*`, `vxp2/*`, `regression/*`).
- Giữ toàn bộ regression v0.8.3.

### v0.8.3-cleanroom
- Rebase trên implementation Kotlin-only v0.8.3; xóa workflow catalog suy từ SDK và mọi wording SDK trong source/docs.
- Thêm chính sách clean-room/provenance; handler tương thích chỉ giữ khi implement độc lập từ hành vi quan sát + regression tests.
- Không JNI/NDK/C/C++ runtime; `vm_*` là compatibility identifiers do guest import, implement bằng Kotlin độc lập.
- Binary mẫu không kèm trong gói; checklist phát hành (SAF import, sandbox, messaging/network opt-in, ads/Pro tách khỏi content).

### v0.8.3
- ELF `R_ARM_RELATIVE` sym-index-0; GOT/init-array relocate đúng.
- Bootstrap `gcc_entry` + chạy `.init_array` trước `vm_main`.
- Thumb `BLX register` (LR + interworking), PC high-register = current+4, thêm `STRH/LDRH` immediate.
- ARM `CLZ`, `LDRD/STRD`, `UMULL/UMLAL/SMULL/SMLAL`; Operand2 đọc r15 = current+8.
- `_vm_log_info/_vm_log_error` thành logging API; `vm_find_first/next/close` enumerate thật + wildcard; UCS2→ASCII giữ NUL.
- Regression: 3 sample ELF mới; giữ Sample A + Sample B.
- Thêm 7 CPU regression tests + validation tương ứng, CSV + SHA256.

### v0.8.2
- `ArmCpu`: đủ nhóm Thumb ALU register: `ADC, SBC, ROR, NEG, CMN`.
- `MreRuntime`: `strtoi` API thật; guest messaging API sandbox failure.
- Đồng bộ version `0.8.2` core + Android facade.
- Thêm `PngDecoder.kt`, tests JVM + validation PNG.
- Giữ hành vi Flash Lite menu frame 4 -> 5.

### v0.8.0
- Tách thành thư viện, xóa app/demo UI khỏi deliverable.
- `vxp-core` JVM thuần cho ELF + RAW_ARM_ZLIB.
- `vxp-core-android` Kotlin host, port Flash renderer sang `android.graphics`.
- Public `VxpCoreLibrary`, `VxpSession`, `AndroidVxpCore`, `AndroidVxpSession`; output duy nhất `FrameSnapshot RGB565`; legacy key helpers.
- Flash Lite: Shape/PlaceObject2/RemoveObject2/JPEG3/timeline/button action/AVM1 Start.
- Sample A: regression 6s không fault.

## 7. Cấu trúc file khác biệt

- v0.8.0: `16` file `vxp-core/*.kt`, không có `tests/`, `validation/` có 4 txt.
- v0.8.2: thêm `PngDecoder.kt`, thêm `tests/jvm/*` (4 file) + `tests/android-graphics-jvm/*` (2 file), `validation/` mở rộng (7 txt + 3 png).
- v0.8.3: thêm 7 CPU regression tests (tổng 11 tests/jvm), validation mở rộng, ma trận tương thích, CSV + SHA256 (tổng 116 files).
- v0.8.3-cleanroom: + `NOTICE-CLEANROOM.txt`, `verify_clean_room.sh`, probe nguồn, docs clean-room/provenance/checklist, validation clean-room (tổng 90 files).
- v0.8.4.1: + `docs/OBSERVED_COMPATIBILITY_SURFACE.md`, tool quét symbol, test graphics/file/resource, validation tương thích + PNG/log (tổng 141 files).
- v0.8.4.2: + resource-from-file APIs, path helpers, attribute metadata, validation `FILE_RESOURCE_v0.8.4.2.md` (tổng 151 files, bản trung gian).
- v0.8.4.4: + `MreAudioHost/Probe`, `AndroidMreAudioHost`, `AudioRegression`, docs `AUDIO_v0.8.4.x.md`, validation audio/corpus (tổng 179 files).

## 8. Nên dùng bản nào?

- Dùng `v0.8.4.4` cho mọi tích hợp mới và mọi bản phát hành/phân phối: superset clean-room đầy đủ nhất (audio + alias SYSTEM/GRAPHICS/FILE_RESOURCE), corpus 76/76.
- `v0.8.4.2` chỉ để đối chiếu bước trung gian; `v0.8.4.1` để đối chiếu trước audio.
- Chỉ tham khảo `v0.8.0` khi cần đối chiếu sha frame cũ hoặc hành vi trước Thumb-ALU fix.
- `v0.8.2` giữ lại để đối chiếu regression dài 23.5M insn trước thay đổi CPU v0.8.3.

## 9. Nguồn đối chiếu

- `VXP-Core-Library-v0.8/README.md`, `CHANGELOG.md`, `docs/TEST_RESULTS.md`
- `VXP-Core-Library-v0.8.2/README.md`, `CHANGELOG.md`, `docs/TEST_RESULTS.md`, `docs/INTEGRATE_EXISTING_UI.md`
- `VXP-Core-Library-v0.8.3/README.md`, `CHANGELOG.md`, `docs/TEST_RESULTS.md`
- `VXP-Core-Library-v0.8.3-cleanroom/README.md`, `CHANGELOG.md`, `NOTICE-CLEANROOM.txt`
- `VXP-Core-Library-v0.8.3-cleanroom/docs/CLEAN_ROOM_POLICY.md`, `docs/PROVENANCE.md`, `docs/COMMERCIAL_DISTRIBUTION_CHECKLIST.md`
- `VXP-Core-Library-v0.8.4.1/README.md`, `CHANGELOG.md`, `docs/OBSERVED_COMPATIBILITY_SURFACE.md`
- `VXP-Core-Library-v0.8.4.1/validation/COMPATIBILITY_v0.8.4.1.md`, `validation/observed_surface_v0.8.4.1.txt`
- `VXP-Core-Library-v0.8.4.4/README.md`, `CHANGELOG.md`
- `VXP-Core-Library-v0.8.4.4/validation/COMPATIBILITY_v0.8.4.4.md`, `validation/AUDIO_v0.8.4.4.md`
- `vxp-core/.../VxpLibrary.kt: VERSION`, `vxp-core-android/.../AndroidVxpCore.kt: VERSION`
