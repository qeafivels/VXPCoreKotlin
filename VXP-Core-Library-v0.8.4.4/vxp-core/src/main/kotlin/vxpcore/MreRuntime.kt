package vxpcore

import java.util.concurrent.ConcurrentLinkedQueue
import java.io.File
import java.util.Calendar
import kotlin.math.max

class MreRuntime(
    private val memory: GuestMemory,
    fileSystemRoot: File = File("runtime_fs"),
    textRasterizer: TextRasterizer = BitmapTextRasterizer(),
    private val audioHost: MreAudioHost = StateOnlyMreAudioHost()
) : AutoCloseable {
    companion object {
        const val API_BASE = 0x01000000
        const val API_SIZE = 0x00010000
        const val HOST_RETURN_TRAP = API_BASE + API_SIZE - 4
        const val HEAP_BASE = 0x02000000
        const val HEAP_SIZE = 16 * 1024 * 1024
        const val STACK_BASE = 0x04000000
        const val STACK_SIZE = 1024 * 1024
        const val RESOURCE_BASE = 0x50000000
        const val RESOURCE_MAX_SIZE = 32 * 1024 * 1024
    }

    data class Api(val name: String, val address: Int, val handler: (ArmCpu) -> Unit)

    data class TimerState(
        val id: Int,
        val intervalMs: Long,
        val callback: Int,
        var nextFireNanos: Long,
        var enabled: Boolean = true
    )

    private val byAddress = linkedMapOf<Int, Api>()
    private val byName = linkedMapOf<String, Api>()
    private val events = ConcurrentLinkedQueue<MreEvent>()
    private val timers = linkedMapOf<Int, TimerState>()
    private var nextTimerId = 1
    private val heap = MreHeap(memory, HEAP_BASE, HEAP_SIZE)
    private val resolvedNames = linkedSetOf<String>()
    private val stubbedNames = linkedSetOf<String>()
    private val imagePropertyPtrs = mutableMapOf<Int, Int>()
    private val systemStrings = linkedMapOf<String, Int>()
    private val audioIds = MreAudioIdAllocator()
    @Volatile private var audioInterruptCallback: Int = 0
    @Volatile private var audioVolume: Float = 1f

    data class NamedResource(val name: String, val offset: Int, val size: Int)

    var rawResourceBlob: ByteArray = ByteArray(0)
        private set
    private var resourceMappedSize = 0
    private var resourceBlobSize = 0
    private var namedResources: List<NamedResource> = emptyList()
    private val externalResourceAllocations = linkedSetOf<Int>()
    var rawExecutableName: String = "app.vxp"

    val graphics: MreGraphics
    val fileSystem = MreFileSystem(fileSystemRoot)

    var systemCallback: Int = 0
        private set
    var keyboardCallback: Int = 0
        private set
    var penCallback: Int = 0
        private set
    var exitRequested: Boolean = false
    var exitCode: Int = 0

    init {
        memory.map(API_BASE, API_SIZE, read = true, write = false, exec = true)
        memory.map(HEAP_BASE, HEAP_SIZE, read = true, write = true, exec = false)
        memory.map(STACK_BASE, STACK_SIZE, read = true, write = true, exec = false)
        graphics = MreGraphics(memory, 240, 320, textRasterizer = textRasterizer)

        // Core display information.
        api("vm_graphic_get_screen_width") { it.r[0] = graphics.screenWidth }
        api("vm_graphic_get_screen_height") { it.r[0] = graphics.screenHeight }
        api("vm_graphic_get_bits_per_pixel") { it.r[0] = 16 }
        api("vm_graphic_get_screen_w") { it.r[0] = graphics.screenWidth }
        api("vm_graphic_get_screen_h") { it.r[0] = graphics.screenHeight }

        // Layer creation: VMINT vm_graphic_create_layer(x, y, width, height, trans_color)
        // AAPCS passes the fifth argument at [sp].
        api("vm_graphic_create_layer") { cpu ->
            val handle = graphics.createLayer(
                x = arg(cpu, 0),
                y = arg(cpu, 1),
                width = arg(cpu, 2),
                height = arg(cpu, 3),
                transparentColor = arg(cpu, 4)
            )
            cpu.r[0] = handle
            if (cpu.trace) {
                val layer = graphics.layer(handle)
                println("[GFX ] create layer=$handle ${layer?.width}x${layer?.height} buf=0x${(layer?.bufferAddress ?: 0).toUInt().toString(16)}")
            }
        }
        api("vm_graphic_create_layer_ex") { cpu ->
            // Calling pattern independently observed in GCC-built guest wrappers:
            // x, y, width, height, transparentColor, storageKind, externalBuffer.
            val external = arg(cpu, 6)
            val trans = decodeColorArg(arg(cpu, 4))
            cpu.r[0] = if (external != 0 && memory.isMapped(external, 2)) {
                graphics.createLayerWithBuffer(arg(cpu, 0), arg(cpu, 1), arg(cpu, 2), arg(cpu, 3), trans, external)
            } else {
                graphics.createLayer(arg(cpu, 0), arg(cpu, 1), arg(cpu, 2), arg(cpu, 3), trans)
            }
        }
        api("vm_graphic_get_layer_buffer") { cpu ->
            cpu.r[0] = graphics.getLayerBuffer(arg(cpu, 0))
        }
        api("vm_graphic_active_layer") { cpu ->
            cpu.r[0] = graphics.activeLayer(arg(cpu, 0))
        }
        api("vm_graphic_set_clip") { cpu ->
            cpu.r[0] = graphics.setClip(arg(cpu, 0), arg(cpu, 1), arg(cpu, 2), arg(cpu, 3))
        }
        api("vm_graphic_reset_clip") { cpu ->
            cpu.r[0] = graphics.resetClip()
        }
        api("vm_graphic_delete_layer") { cpu ->
            cpu.r[0] = graphics.deleteLayer(arg(cpu, 0))
        }

        // Compatibility signature inferred from observed guest call sites: vm_graphic_flush_layer(layers, count).
        // Some observed guest wrappers are permissive enough that a single handle may
        // be passed directly; support that as a compatibility fallback.
        api("vm_graphic_flush_layer") { cpu ->
            val pointerOrHandle = arg(cpu, 0)
            val count = arg(cpu, 1)
            val handles = decodeLayerList(pointerOrHandle, count)
            cpu.r[0] = graphics.flushLayers(handles)
            if (cpu.trace) println("[GFX ] flush layers=${handles.joinToString()} frame=${graphics.flushCount}")
        }
        api("vm_graphic_flush_screen") { cpu ->
            cpu.r[0] = graphics.flushActiveLayer()
        }

        api("vm_get_tick_count") { it.r[0] = (System.nanoTime() / 1_000_000L).toInt() }
        api("vm_get_tick") { it.r[0] = (System.nanoTime() / 1_000_000L).toInt() }


        // Compatibility helper observed in stripped ARM VXP binaries.
        // Keep it first-class so a known/implemented function is not reported as a stub.
        api("strtoi") { cpu ->
            val ptr = arg(cpu, 0)
            val raw = if (ptr != 0) runCatching { memory.readCString(ptr, 128) }.getOrDefault("") else ""
            var text = raw.trimStart()
            var sign = 1
            if (text.startsWith("-")) { sign = -1; text = text.substring(1) }
            else if (text.startsWith("+")) text = text.substring(1)
            val base = if (text.startsWith("0x", true)) 16 else 10
            if (base == 16) text = text.substring(2)
            var value = 0L
            var any = false
            for (ch in text) {
                val d = ch.digitToIntOrNull(base) ?: break
                any = true
                value = (value * base + d) and 0xffffffffL
            }
            cpu.r[0] = if (any) (value * sign).toInt() else 0
        }

        api("vm_sscanf") { cpu -> cpu.r[0] = sscanfCompat(cpu) }

        // Never send a real host SMS from an emulated guest. Returning failure preserves
        // guest control flow without crossing the emulator sandbox or bypassing billing.
        api("vm_send_sms") { it.r[0] = -1 }
        api("vm_malloc") { cpu ->
            val requested = arg(cpu, 0)
            cpu.r[0] = heap.malloc(requested)
            if (cpu.trace || (cpu.r[0] == 0 && requested > 0)) {
                traceHeap("malloc", requested, cpu.r[0])
                if (cpu.r[0] == 0 && requested > 0) println("[HEAP] malloc failure callerLR=0x${cpu.r[14].toUInt().toString(16)}")
            }
        }
        // Gameloft/MRE binaries observed in the wild use vm_calloc(size) as a
        // one-argument, zero-initializing allocator rather than ISO C calloc(n,size).
        api("vm_calloc") { cpu ->
            val requested = arg(cpu, 0)
            cpu.r[0] = heap.calloc(requested)
            if (cpu.trace || (cpu.r[0] == 0 && requested > 0)) {
                traceHeap("calloc", requested, cpu.r[0])
                if (cpu.r[0] == 0 && requested > 0) println("[HEAP] calloc failure callerLR=0x${cpu.r[14].toUInt().toString(16)}")
            }
        }
        api("vm_free") { cpu ->
            val ptr = arg(cpu, 0)
            val ok = heap.free(ptr)
            cpu.r[0] = 0
            if (cpu.trace && ptr != 0) println("[HEAP] free ptr=0x${ptr.toUInt().toString(16)} ok=$ok ${heapSummary()}")
        }
        api("vm_realloc") { cpu ->
            val ptr = arg(cpu, 0)
            val requested = arg(cpu, 1)
            cpu.r[0] = heap.realloc(ptr, requested)
            if (cpu.trace || cpu.r[0] == 0) println("[HEAP] realloc ptr=0x${ptr.toUInt().toString(16)} size=$requested -> 0x${cpu.r[0].toUInt().toString(16)} callerLR=0x${cpu.r[14].toUInt().toString(16)} ${heapSummary()}")
        }
        api("vm_reg_sysevt_callback") { cpu ->
            systemCallback = cpu.r[0]
            cpu.r[0] = 0
        }
        api("vm_reg_keyboard_callback") { cpu ->
            keyboardCallback = cpu.r[0]
            cpu.r[0] = 0
        }
        api("vm_reg_pen_callback") { cpu ->
            penCallback = cpu.r[0]
            cpu.r[0] = 0
        }
        api("vm_reg_key_callback") { cpu -> keyboardCallback = cpu.r[0]; cpu.r[0] = 0 }
        api("vm_reg_system_event_callback") { cpu -> systemCallback = cpu.r[0]; cpu.r[0] = 0 }
        api("vm_reg_touch_callback") { cpu -> penCallback = cpu.r[0]; cpu.r[0] = 0 }
        api("vm_app_log") { cpu ->
            val p = cpu.r[0]
            val text = runCatching { memory.readCString(p) }.getOrElse { "<bad string @0x${p.toUInt().toString(16)}>" }
            println("[VXP] $text")
        }
        // Logging aliases observed in GCC-built VXP binaries. Their first argument is a C format/string pointer;
        // keep the implementation side-effect free apart from debug output and do not
        // treat them as unresolved compatibility stubs. Varargs are intentionally not
        // formatted here because guest logging must never affect execution semantics.
        api("_vm_log_info") { cpu ->
            val p = cpu.r[0]
            val text = runCatching { memory.readCString(p, 1024) }.getOrElse { "<bad string @0x${p.toUInt().toString(16)}>" }
            if (cpu.trace) println("[VXP-I] $text")
            cpu.r[0] = 0
        }
        api("_vm_log_error") { cpu ->
            val p = cpu.r[0]
            val text = runCatching { memory.readCString(p, 1024) }.getOrElse { "<bad string @0x${p.toUInt().toString(16)}>" }
            if (cpu.trace) println("[VXP-E] $text")
            cpu.r[0] = 0
        }
        api("vm_exit_app") { cpu ->
            exitCode = cpu.r[0]
            exitRequested = true
            cpu.halted = true
        }
        api("vm_create_timer") { cpu ->
            val intervalMs = max(1, cpu.r[0]).toLong()
            val callback = cpu.r[1]
            if (callback == 0) {
                cpu.r[0] = -1
            } else {
                val id = allocateTimerId()
                val now = System.nanoTime()
                timers[id] = TimerState(
                    id = id,
                    intervalMs = intervalMs,
                    callback = callback,
                    nextFireNanos = now + intervalMs * 1_000_000L
                )
                cpu.r[0] = id
                if (cpu.trace) println("[TIMER] create id=$id interval=${intervalMs}ms callback=0x${callback.toUInt().toString(16)}")
            }
        }
        api("vm_delete_timer") { cpu ->
            val removed = timers.remove(cpu.r[0])
            cpu.r[0] = if (removed != null) 0 else -1
            if (cpu.trace && removed != null) println("[TIMER] delete id=${removed.id}")
        }

        // Frequently used native APIs required by stripped Gameloft RAW_ARM_ZLIB titles.
        api("vm_switch_power_saving_mode") { it.r[0] = 0 }

        // AUDIO compatibility layer. vxp-core owns only guest ABI/state; real playback
        // is delegated to a host-neutral MreAudioHost implementation. Android supplies
        // AndroidMreAudioHost; headless JVM runs use StateOnlyMreAudioHost.
        api("vm_audio_register_interrupt_callback") { cpu ->
            audioInterruptCallback = arg(cpu, 0)
            cpu.r[0] = 0
        }
        api("vm_audio_clear_interrupt_callback") { cpu ->
            audioInterruptCallback = 0
            cpu.r[0] = 0
        }
        api("vm_audio_play_bytes") { cpu -> cpu.r[0] = playBytesCompat(cpu, MreAudioKind.AUDIO) }
        api("vm_audio_play_bytes_no_block") { cpu -> cpu.r[0] = playBytesCompat(cpu, MreAudioKind.AUDIO) }
        api("vm_audio_play_file") { cpu -> cpu.r[0] = playFileCompat(cpu, MreAudioKind.AUDIO) }
        api("vm_audio_play_file_ex") { cpu ->
            // Clean-room extended compatibility profile. The currently unobserved
            // extra arguments are treated conservatively as start-ms and loop flag.
            // Basic path/format behavior remains identical to vm_audio_play_file.
            val startMs = arg(cpu, 2).takeIf { it in 0..86_400_000 } ?: 0
            val loop = arg(cpu, 3) != 0
            cpu.r[0] = playFileCompat(cpu, MreAudioKind.AUDIO, startMs, loop)
        }
        api("vm_audio_bytes_duration") { cpu -> cpu.r[0] = audioBytesDurationCompat(cpu, MreAudioKind.AUDIO) }
        api("vm_audio_duration") { cpu -> cpu.r[0] = audioHost.snapshot().durationMs.coerceAtLeast(0) }
        api("vm_audio_pause") { cpu -> cpu.r[0] = if (audioHost.pause(MreAudioKind.AUDIO)) 0 else -1 }
        api("vm_audio_resume") { cpu -> cpu.r[0] = if (audioHost.resume(MreAudioKind.AUDIO)) 0 else -1 }
        api("vm_audio_stop") { cpu -> cpu.r[0] = if (audioHost.stop(MreAudioKind.AUDIO)) 0 else -1 }
        api("vm_audio_stop_all") { cpu -> cpu.r[0] = if (audioHost.stopAll()) 0 else -1 }
        api("vm_audio_is_app_playing") { cpu ->
            cpu.r[0] = if (audioHost.snapshot().state == MreAudioPlaybackState.PLAYING) 1 else 0
        }
        api("vm_audio_get_time") { cpu -> cpu.r[0] = audioHost.snapshot().positionMs.coerceAtLeast(0) }
        api("vm_set_volume") { cpu ->
            audioVolume = normalizeGuestVolume(arg(cpu, 0))
            cpu.r[0] = if (audioHost.setVolume(audioVolume)) 0 else -1
        }
        api("vm_audio_set_volume_type") { cpu ->
            // Several toolchains expose a leading volume-category argument. Prefer r1
            // when it looks like a plausible level, otherwise fall back to r0.
            val raw = arg(cpu, 1).takeIf { it in 0..100 } ?: arg(cpu, 0)
            audioVolume = normalizeGuestVolume(raw)
            cpu.r[0] = if (audioHost.setVolume(audioVolume)) 0 else -1
        }
        // These APIs suspend/resume platform background audio, not the guest player.
        // A JVM-neutral core cannot control host music, so keep them successful no-ops.
        api("vm_audio_suspend_bg_play") { it.r[0] = 0 }
        api("vm_audio_resume_bg_play") { it.r[0] = 0 }
        api("vm_audio_terminate_background_play") { cpu -> cpu.r[0] = if (audioHost.stopAll()) 0 else -1 }
        api("vm_get_language") { it.r[0] = 0 } // English compatibility profile
        api("vm_get_language_ssc") { it.r[0] = 0 }
        api("vm_get_system_driver") { it.r[0] = 'C'.code }
        api("vm_get_removeable_driver") { it.r[0] = 'E'.code }
        api("vm_get_removable_driver") { it.r[0] = 'E'.code }
        // Returns free bytes for a UCS2 drive/path (e.g. L"C" or L"E").
        // Gameloft startup code compares the return value directly against the
        // required install/save space, so returning 0 produces its low-storage UI.
        api("vm_get_disk_free_space") { cpu ->
            val ptr = arg(cpu, 0)
            val path = if (ptr != 0 && memory.isMapped(ptr, 2)) readUcs2Compat(ptr, 260) else "C:\\"
            val free = fileSystem.freeSpace(path).coerceAtMost(Int.MAX_VALUE.toLong())
            cpu.r[0] = free.toInt()
        }
        api("vm_get_exec_filename") { cpu ->
            val dst = arg(cpu, 0)
            val path = "C:\\$rawExecutableName"
            cpu.r[0] = if (writeUcs2(dst, path)) 0 else -1
        }
        api("vm_get_imei") { cpu ->
            // MRE returns a pointer to a zero-terminated ASCII IMEI. Never expose
            // any host/device identifier; keep a deterministic guest-owned value.
            cpu.r[0] = systemAscii("imei", "000000000000000")
        }
        api("vm_get_imsi") { cpu ->
            // Keep subscriber identity synthetic as well: guest code gets a stable
            // ASCII IMSI-shaped value, never host SIM/subscriber information.
            cpu.r[0] = systemAscii("imsi", "001010000000000")
        }
        api("vm_sim_card_count") { it.r[0] = 1 }
        api("vm_get_time") { cpu ->
            val out = arg(cpu, 0)
            if (out == 0 || !memory.isMapped(out, 24)) {
                cpu.r[0] = -1
            } else {
                val now = Calendar.getInstance()
                memory.write32(out + 0, now.get(Calendar.YEAR))
                memory.write32(out + 4, now.get(Calendar.MONTH) + 1)
                memory.write32(out + 8, now.get(Calendar.DAY_OF_MONTH))
                memory.write32(out + 12, now.get(Calendar.HOUR_OF_DAY))
                memory.write32(out + 16, now.get(Calendar.MINUTE))
                memory.write32(out + 20, now.get(Calendar.SECOND))
                cpu.r[0] = 0
            }
        }
        api("vm_get_vm_tag") { cpu -> cpu.r[0] = systemAscii("vm_tag", "MRE") }
        api("vm_is_support_wifi") { it.r[0] = 0 }
        api("vm_wifi_is_connected") { it.r[0] = 0 }
        // Network transport is intentionally host-sandboxed in the core. Return a
        // real failure instead of a generic compatibility-stub success so browser
        // guests can enter their offline/error path deterministically.
        api("vm_tcp_connect") { it.r[0] = -1 }
        api("vm_tcp_close") { it.r[0] = 0 }
        api("vm_has_sim_card") { it.r[0] = 1 }
        api("vm_get_sim_card_status") { it.r[0] = 1 }
        api("vm_sim_get_active_sim_card") { it.r[0] = 0 }
        api("vm_set_active_sim_card") { it.r[0] = 0 }
        api("vm_get_sys_scene") { it.r[0] = 0 }
        api("vm_midi_play_by_bytes") { cpu -> cpu.r[0] = playBytesCompat(cpu, MreAudioKind.MIDI) }
        api("vm_midi_play_by_bytes_ex") { cpu -> cpu.r[0] = playBytesCompat(cpu, MreAudioKind.MIDI) }
        api("vm_midi_pause") { cpu -> cpu.r[0] = if (audioHost.pause(MreAudioKind.MIDI)) 0 else -1 }
        api("vm_midi_resume") { cpu -> cpu.r[0] = if (audioHost.resume(MreAudioKind.MIDI)) 0 else -1 }
        api("vm_midi_stop") { cpu -> cpu.r[0] = if (audioHost.stop(MreAudioKind.MIDI)) 0 else -1 }
        api("vm_midi_stop_all") { cpu -> cpu.r[0] = if (audioHost.stop(MreAudioKind.MIDI)) 0 else -1 }
        api("vm_midi_get_time") { cpu ->
            val snap = audioHost.snapshot()
            cpu.r[0] = if (snap.kind == MreAudioKind.MIDI) snap.positionMs.coerceAtLeast(0) else 0
        }
        api("vm_appmgr_get_installed_list") { cpu ->
            val countPtr = arg(cpu, 2)
            if (countPtr != 0 && memory.isMapped(countPtr, 4)) memory.write32(countPtr, 0)
            cpu.r[0] = 0
        }
        api("vm_appmgr_get_install_info") { cpu ->
            val out = arg(cpu, 1)
            if (out != 0 && memory.isMapped(out, 64)) memory.writeBytes(out, ByteArray(64))
            cpu.r[0] = -1
        }
        api("vm_query_operator_code") { cpu ->
            val dst = arg(cpu, 0)
            val code = "00101"
            if (dst != 0 && memory.isMapped(dst, code.length + 1)) {
                code.forEachIndexed { i, ch -> memory.write8(dst + i, ch.code) }
                memory.write8(dst + code.length, 0)
                cpu.r[0] = 0
            } else cpu.r[0] = -1
        }
        api("vm_graphic_mirror") { cpu ->
            // If the first argument is one of our image/layer handles, mirror it in-place.
            // Other calling patterns remain a safe no-op until independently observed.
            val handle = arg(cpu, 0)
            val mode = arg(cpu, 1)
            cpu.r[0] = if (graphics.imageWidth(handle) > 0 && mode in 0..3) graphics.mirror(handle, mode) else 0
        }
        api("vm_graphic_setcolor") { cpu -> cpu.r[0] = graphics.setColor(decodeColorArg(arg(cpu, 0))) }
        api("vm_graphic_fill_rect") { cpu ->
            // Common buffer ABI is (buffer, x, y, width, height [, color]).
            // Some compatibility wrappers pass the RGB565 color twice in stack args 5/6.
            val a5 = arg(cpu, 5)
            val a6 = arg(cpu, 6)
            val explicitColor = if ((a5 and 0xFFFF0000.toInt()) == 0 && (a6 and 0xFFFF) == (a5 and 0xFFFF)) {
                decodeColorArg(a5)
            } else graphics.currentColor
            cpu.r[0] = graphics.fillRectBuffer(
                arg(cpu, 0), arg(cpu, 1), arg(cpu, 2), arg(cpu, 3), arg(cpu, 4), explicitColor
            )
        }
        api("vm_graphic_fill_rect_ex") { cpu ->
            // Observed GCC-built wrapper forwards five arguments: (layer, x, y, width, height).
            cpu.r[0] = graphics.fillRectOnLayer(arg(cpu, 0), arg(cpu, 1), arg(cpu, 2), arg(cpu, 3), arg(cpu, 4))
        }
        api("vm_wstrlen") { cpu ->
            cpu.r[0] = readUcs2Compat(arg(cpu, 0)).length
        }
        api("vm_graphic_create_canvas") { cpu ->
            cpu.r[0] = graphics.createCanvas(arg(cpu, 0), arg(cpu, 1))
        }
        api("vm_graphic_get_canvas_buffer") { cpu ->
            cpu.r[0] = graphics.getCanvasBuffer(arg(cpu, 0))
        }
        api("vm_graphic_get_image_buffer") { cpu -> cpu.r[0] = graphics.getImageBuffer(arg(cpu, 0)) }
        api("vm_graphic_get_img_buffer") { cpu -> cpu.r[0] = graphics.getImageBuffer(arg(cpu, 0)) }
        api("vm_graphic_release_canvas") { cpu ->
            val handle = arg(cpu, 0)
            imagePropertyPtrs.remove(handle)?.let { heap.free(it) }
            cpu.r[0] = graphics.releaseCanvas(handle)
        }
        api("vm_graphic_release_image") { cpu ->
            val handle = arg(cpu, 0)
            imagePropertyPtrs.remove(handle)?.let { heap.free(it) }
            cpu.r[0] = graphics.releaseImage(handle)
        }
        api("vm_graphic_load_image") { cpu ->
            val ptr = arg(cpu, 0)
            val size = arg(cpu, 1)
            cpu.r[0] = if (ptr != 0 && size > 0 && size <= 32 * 1024 * 1024 && memory.isMapped(ptr, size)) {
                graphics.loadImage(memory.readBytes(ptr, size))
            } else 0
        }
        api("vm_graphic_load_img") { cpu ->
            val ptr = arg(cpu, 0)
            val size = arg(cpu, 1)
            cpu.r[0] = if (ptr != 0 && size > 0 && size <= 32 * 1024 * 1024 && memory.isMapped(ptr, size)) {
                graphics.loadImage(memory.readBytes(ptr, size))
            } else 0
        }
        api("vm_graphic_get_img_property") { cpu ->
            val handle = arg(cpu, 0)
            val width = graphics.imageWidth(handle)
            val height = graphics.imageHeight(handle)
            if (width <= 0 || height <= 0) {
                cpu.r[0] = 0
            } else {
                val info = imagePropertyPtrs.getOrPut(handle) { heap.calloc(16) }
                if (info == 0) cpu.r[0] = 0 else {
                    memory.write16(info + 0, 1)
                    memory.write16(info + 2, 16)
                    memory.write16(info + 4, 0)
                    memory.write16(info + 6, width)
                    memory.write16(info + 8, height)
                    memory.write16(info + 10, 0)
                    cpu.r[0] = info
                }
            }
        }
        api("vm_graphic_get_image_property") { cpu ->
            val handle = arg(cpu, 0)
            val width = graphics.imageWidth(handle)
            val height = graphics.imageHeight(handle)
            if (width <= 0 || height <= 0) cpu.r[0] = 0 else {
                val info = imagePropertyPtrs.getOrPut(handle) { heap.calloc(16) }
                if (info == 0) cpu.r[0] = 0 else {
                    memory.write16(info + 0, 1); memory.write16(info + 2, 16); memory.write16(info + 4, 0)
                    memory.write16(info + 6, width); memory.write16(info + 8, height); memory.write16(info + 10, 0)
                    cpu.r[0] = info
                }
            }
        }
        api("vm_graphic_canvas_set_trans_color") { cpu ->
            cpu.r[0] = graphics.setCanvasTransparentColor(arg(cpu, 0), decodeColorArg(arg(cpu, 1)))
        }
        api("vm_graphic_blt") { cpu ->
            cpu.r[0] = graphics.blt(
                arg(cpu, 0), arg(cpu, 1), arg(cpu, 2), arg(cpu, 3),
                arg(cpu, 4), arg(cpu, 5), arg(cpu, 6), arg(cpu, 7), arg(cpu, 8)
            )
        }
        api("vm_graphic_line") { cpu ->
            // Observed buffer ABI: (buffer, x1, y1, x2, y2 [, color]).
            // Fall back to current color when the optional stack color is absent.
            val c = arg(cpu, 6).let { if ((it and 0xFFFF0000.toInt()) == 0) decodeColorArg(it) else graphics.currentColor }
            cpu.r[0] = graphics.drawLineBuffer(arg(cpu, 0), arg(cpu, 1), arg(cpu, 2), arg(cpu, 3), arg(cpu, 4), c)
        }
        api("vm_graphic_rect") { cpu -> cpu.r[0] = graphics.drawRect(arg(cpu, 0), arg(cpu, 1), arg(cpu, 2), arg(cpu, 3)) }
        api("vm_graphic_measure_character") { cpu ->
            val widthPtr = arg(cpu, 1)
            val heightPtr = arg(cpu, 2)
            if (widthPtr == 0 || heightPtr == 0 || !memory.isMapped(widthPtr, 4) || !memory.isMapped(heightPtr, 4)) {
                cpu.r[0] = -1
            } else {
                memory.write32(widthPtr, graphics.characterWidth(arg(cpu, 0)))
                memory.write32(heightPtr, graphics.characterHeight())
                cpu.r[0] = 0
            }
        }
        api("vm_graphic_roundrect") { cpu ->
            // Compatibility raster: preserve the raw-buffer ABI and clipping.
            // Corner radius is intentionally approximated by the rectangular frame
            // until an independently validated pixel-exact round-corner contract is needed.
            val buffer = arg(cpu, 0)
            val x = arg(cpu, 1); val y = arg(cpu, 2)
            val width = arg(cpu, 3); val height = arg(cpu, 4)
            val color = decodeColorArg(arg(cpu, 6))
            if (width <= 0 || height <= 0) {
                cpu.r[0] = 0
            } else {
                val x1 = x + width - 1; val y1 = y + height - 1
                val a = graphics.drawLineBuffer(buffer, x, y, x1, y, color)
                val b = graphics.drawLineBuffer(buffer, x, y, x, y1, color)
                val c = graphics.drawLineBuffer(buffer, x1, y, x1, y1, color)
                val d = graphics.drawLineBuffer(buffer, x, y1, x1, y1, color)
                cpu.r[0] = if (a < 0 || b < 0 || c < 0 || d < 0) -1 else 0
            }
        }
        api("vm_graphic_get_character_width") { cpu ->
            cpu.r[0] = graphics.characterWidth(arg(cpu, 0))
        }
        api("vm_graphic_get_character_height") { cpu ->
            cpu.r[0] = graphics.characterHeight()
        }
        api("vm_graphic_get_font_height") { cpu -> cpu.r[0] = graphics.characterHeight() }
        api("vm_graphic_get_string_width") { cpu ->
            val p = arg(cpu, 0)
            val text = readUcs2Compat(p)
            cpu.r[0] = graphics.stringWidth(text)
            if (cpu.trace) println("[TEXT] width=${cpu.r[0]} '$text'")
        }
        api("vm_graphic_get_text_width") { cpu ->
            cpu.r[0] = graphics.stringWidth(readUcs2Compat(arg(cpu, 0)))
        }
        api("vm_graphic_get_string_height") { cpu ->
            val p = arg(cpu, 0)
            val text = readUcs2Compat(p)
            cpu.r[0] = graphics.stringHeight(text)
            if (cpu.trace) println("[TEXT] height=${cpu.r[0]} '$text'")
        }
        api("vm_graphic_set_font") { cpu ->
            cpu.r[0] = graphics.setFont(arg(cpu, 0))
            if (cpu.trace) println("[TEXT] font=${graphics.text.currentFontId()}")
        }
        // Observed VXP binaries use arg4 either as a character count or a pixel width.
        // GCC wrappers commonly pass strlen/UCS2 length; some ARMCC titles pass maxWidth.
        api("vm_graphic_textout") { cpu ->
            val full = readUcs2Compat(arg(cpu, 3))
            val limit = arg(cpu, 4)
            val countAbi = limit > 0 && limit <= full.length + 1
            val value = if (countAbi) full.take(limit.coerceAtMost(full.length)) else full
            val maxWidth = if (countAbi || limit <= 0) Int.MAX_VALUE else limit
            val color = decodeColorArg(arg(cpu, 5))
            cpu.r[0] = graphics.drawTextBuffer(arg(cpu, 0), arg(cpu, 1), arg(cpu, 2), value, maxWidth, color)
            if (cpu.trace) println("[TEXT] out buf=0x${arg(cpu,0).toUInt().toString(16)} x=${arg(cpu,1)} y=${arg(cpu,2)} '$value'")
        }
        // Common ABI used by this title: (layer, x, y, ucs2, maxWidth).
        api("vm_graphic_textout_to_layer") { cpu ->
            val value = readUcs2Compat(arg(cpu, 3))
            val maxWidth = arg(cpu, 4).let { if (it <= 0) Int.MAX_VALUE else it }
            cpu.r[0] = graphics.drawTextLayer(arg(cpu, 0), arg(cpu, 1), arg(cpu, 2), value, maxWidth)
            if (cpu.trace) println("[TEXT] out layer=${arg(cpu,0)} x=${arg(cpu,1)} y=${arg(cpu,2)} '$value'")
        }

        // Charset conversion APIs are registered directly so real titles do not
        // fall through the generic compatibility stub path.
        api("vm_ascii_to_ucs2") { cpu -> cpu.r[0] = asciiToUcs2Compat(cpu) }
        api("vm_gb2312_to_ucs2") { cpu -> cpu.r[0] = asciiToUcs2Compat(cpu) }
        api("vm_ucs2_to_ascii") { cpu -> cpu.r[0] = ucs2ToAsciiCompat(cpu) }
        api("vm_chset_convert") { cpu -> cpu.r[0] = charsetConvertCompat(cpu) }

        // Sandboxed file runtime. Guest paths are UCS2 strings using C:\\... / E:\\... conventions.
        api("vm_file_open") { cpu ->
            val path = readUcs2Compat(arg(cpu, 0))
            val mode = arg(cpu, 1)
            cpu.r[0] = fileSystem.open(path, mode)
            if (cpu.trace) println("[FILE] open '$path' mode=$mode -> ${cpu.r[0]}")
        }
        api("vm_file_close") { cpu -> cpu.r[0] = fileSystem.close(arg(cpu, 0)) }
        api("vm_file_commit") { cpu -> cpu.r[0] = fileSystem.commit(arg(cpu, 0)) }
        api("vm_file_getfilesize") { cpu ->
            val handle = arg(cpu, 0)
            val out = arg(cpu, 1)
            val size = fileSystem.size(handle)
            if (size == null || size > 0xffffffffL || out == 0 || !memory.isMapped(out, 4)) {
                cpu.r[0] = -1
            } else {
                memory.write32(out, size.toInt())
                cpu.r[0] = 0
            }
            if (cpu.trace) println("[FILE] size h=$handle -> ${size ?: -1}")
        }
        api("vm_file_get_file_size") { cpu ->
            val first = arg(cpu, 0)
            val out = arg(cpu, 1)
            val byHandle = fileSystem.size(first)
            val size = byHandle ?: run {
                val path = readUcs2Compat(first, 512)
                if (path.isBlank()) null else fileSystem.sizeByPath(path)
            }
            if (size == null || size > 0xffffffffL || out == 0 || !memory.isMapped(out, 4)) {
                cpu.r[0] = -1
            } else {
                memory.write32(out, size.toInt())
                cpu.r[0] = 0
            }
        }
        api("vm_file_read") { cpu ->
            val handle = arg(cpu, 0); val dst = arg(cpu, 1); val requested = arg(cpu, 2); val out = arg(cpu, 3)
            val argsValid = requested in 0..MreFileSystem.MAX_IO_SIZE &&
                (requested == 0 || (dst != 0 && memory.isMapped(dst, requested))) &&
                (out == 0 || memory.isMapped(out, 4))
            val bytes = if (!argsValid) null else fileSystem.read(handle, requested)
            val ok = bytes != null
            if (ok) {
                if (bytes!!.isNotEmpty()) memory.writeBytes(dst, bytes)
                if (out != 0) memory.write32(out, bytes.size)
                cpu.r[0] = 0
            } else {
                if (out != 0 && memory.isMapped(out, 4)) memory.write32(out, 0)
                cpu.r[0] = -1
            }
            if (cpu.trace) println("[FILE] read h=$handle req=$requested got=${bytes?.size ?: -1}")
        }
        api("vm_file_write") { cpu ->
            val handle = arg(cpu, 0); val src = arg(cpu, 1); val requested = arg(cpu, 2); val out = arg(cpu, 3)
            val valid = requested in 0..MreFileSystem.MAX_IO_SIZE &&
                (requested == 0 || (src != 0 && memory.isMapped(src, requested))) &&
                (out == 0 || memory.isMapped(out, 4))
            val written = if (valid) {
                fileSystem.write(handle, if (requested == 0) ByteArray(0) else memory.readBytes(src, requested))
            } else -1
            if (out != 0 && memory.isMapped(out, 4)) memory.write32(out, written.coerceAtLeast(0))
            cpu.r[0] = if (written >= 0) 0 else -1
            if (cpu.trace) println("[FILE] write h=$handle req=$requested wrote=$written")
        }
        api("vm_file_seek") { cpu ->
            val pos = fileSystem.seek(arg(cpu, 0), arg(cpu, 1).toLong(), arg(cpu, 2))
            cpu.r[0] = if (pos != null) 0 else -1
            if (cpu.trace) println("[FILE] seek h=${arg(cpu,0)} off=${arg(cpu,1)} origin=${arg(cpu,2)} -> ${pos ?: -1}")
        }
        api("vm_file_get_attributes") { cpu ->
            val path = readUcs2Compat(arg(cpu, 0))
            cpu.r[0] = fileSystem.attributes(path)
            if (cpu.trace) println("[FILE] attr '$path' -> 0x${cpu.r[0].toUInt().toString(16)}")
        }
        api("vm_file_set_attributes") { cpu ->
            val path = readUcs2Compat(arg(cpu, 0))
            cpu.r[0] = fileSystem.setAttributes(path, arg(cpu, 1))
            if (cpu.trace) println("[FILE] setattr '$path' =0x${arg(cpu,1).toUInt().toString(16)} -> ${cpu.r[0]}")
        }
        api("vm_file_mkdir") { cpu ->
            val path = readUcs2Compat(arg(cpu, 0)); cpu.r[0] = fileSystem.mkdir(path)
            if (cpu.trace) println("[FILE] mkdir '$path' -> ${cpu.r[0]}")
        }
        api("vm_file_delete") { cpu -> val path = readUcs2Compat(arg(cpu, 0)); cpu.r[0] = fileSystem.delete(path) }
        api("vm_file_rmdir") { cpu -> val path = readUcs2Compat(arg(cpu, 0)); cpu.r[0] = fileSystem.rmdir(path) }
        api("vm_file_rename") { cpu ->
            val from = readUcs2Compat(arg(cpu, 0))
            val to = readUcs2Compat(arg(cpu, 1))
            cpu.r[0] = fileSystem.rename(from, to)
        }
        api("vm_file_copy") { cpu ->
            val from = readUcs2Compat(arg(cpu, 0))
            val to = readUcs2Compat(arg(cpu, 1))
            cpu.r[0] = fileSystem.copy(from, to)
        }
        api("vm_file_copy_abort") { cpu -> cpu.r[0] = 0 } // synchronous core: nothing remains queued
        api("vm_file_tell") { cpu ->
            val pos = fileSystem.tell(arg(cpu, 0))
            cpu.r[0] = if (pos != null && pos <= Int.MAX_VALUE.toLong()) pos.toInt() else -1
        }
        api("vm_file_is_eof") { cpu ->
            cpu.r[0] = when (fileSystem.isEof(arg(cpu, 0))) { true -> 1; false -> 0; null -> -1 }
        }
        api("vm_file_get_modify_time") { cpu ->
            val path = readUcs2Compat(arg(cpu, 0), 512)
            val seconds = fileSystem.modifyTimeSeconds(path)
            val out = arg(cpu, 1)
            if (seconds == null || seconds > 0xffffffffL) {
                cpu.r[0] = -1
            } else if (out != 0) {
                if (memory.isMapped(out, 4)) { memory.write32(out, seconds.toInt()); cpu.r[0] = 0 } else cpu.r[0] = -1
            } else {
                // Compatibility fallback for wrappers that use the return value directly.
                cpu.r[0] = seconds.toInt()
            }
        }

        // Path helpers. These use a conservative clean-room compatibility ABI:
        // input UCS2 path + output UCS2 buffer. vm_get_default_folder_path accepts either
        // (dst, driveHint) or (driveHint, dst), because public binaries differ in wrappers.
        api("vm_get_default_folder_path") { cpu ->
            val a0 = arg(cpu, 0); val a1 = arg(cpu, 1)
            val dst = when {
                a0 != 0 && memory.isMapped(a0, 2) -> a0
                a1 != 0 && memory.isMapped(a1, 2) -> a1
                else -> 0
            }
            val hint = if (dst == a0) a1 else a0
            val drive = if ((hint and 0xff).toChar().uppercaseChar() == 'E') 'E' else 'C'
            cpu.r[0] = if (dst != 0 && writeUcs2(dst, fileSystem.defaultFolderPath(drive))) 0 else -1
        }
        api("vm_get_filename") { cpu ->
            val path = readUcs2Compat(arg(cpu, 0), 512)
            val value = fileSystem.filename(path)
            cpu.r[0] = if (value != null && writeUcs2(arg(cpu, 1), value)) 0 else -1
        }
        api("vm_get_path") { cpu ->
            val path = readUcs2Compat(arg(cpu, 0), 512)
            val value = fileSystem.parentPath(path)
            cpu.r[0] = if (value != null && writeUcs2(arg(cpu, 1), value)) 0 else -1
        }

        // Directory enumeration. The common MRE/GCC ABI used by RetroMRE is
        //   vm_find_first(VMWSTR pattern, VMWSTR filenameOut) -> handle / negative
        //   vm_find_next(handle, VMWSTR filenameOut) -> 0 / negative
        //   vm_find_close(handle)
        // Enumeration is strictly confined to the per-app C:/E: sandbox.
        api("vm_find_first") { cpu ->
            val pattern = readUcs2Compat(arg(cpu, 0), 512)
            val out = arg(cpu, 1)
            val found = fileSystem.findFirst(pattern)
            if (found == null || out == 0 || !writeUcs2(out, found.second)) {
                if (found != null) fileSystem.findClose(found.first)
                cpu.r[0] = -1
            } else {
                cpu.r[0] = found.first
            }
            if (cpu.trace) println("[FILE] find_first '$pattern' -> ${cpu.r[0]} ${found?.second ?: ""}")
        }
        api("vm_find_next") { cpu ->
            val handle = arg(cpu, 0)
            val out = arg(cpu, 1)
            val name = fileSystem.findNext(handle)
            cpu.r[0] = if (name != null && out != 0 && writeUcs2(out, name)) 0 else -1
            if (cpu.trace) println("[FILE] find_next h=$handle -> ${cpu.r[0]} ${name ?: ""}")
        }
        api("vm_find_close") { cpu -> cpu.r[0] = fileSystem.findClose(arg(cpu, 0)) }

        // VXP resource subsystem. ELF/GCC applications commonly call vm_res_init()
        // once before vm_load_resource(). The actual archive is installed by the
        // loader (raw package or ELF .vm_res); init itself only reports readiness.
        api("vm_res_init") { cpu ->
            cpu.r[0] = if (rawResourceBlob.isNotEmpty()) 0 else -1
            if (cpu.trace) println("[RES ] init entries=${namedResources.size} bytes=${rawResourceBlob.size} -> ${cpu.r[0]}")
        }
        api("vm_resource_init") { cpu -> cpu.r[0] = if (rawResourceBlob.isNotEmpty()) 0 else -1 }

        // Raw VXP resource bridge. Gameloft packages expose a small named archive
        // at the beginning of their resource tail (e.g. "mre-2.0"). Offsets in
        // that archive are relative to the start of the raw resource blob.
        api("vm_get_res_header") { cpu ->
            // MRE returns a byte offset/header adjustment used by callers after
            // vm_resource_get_data(), not a host pointer. Raw appended-resource
            // packages used by this Gameloft build have no extra per-resource
            // prefix, therefore the effective adjustment is zero.
            cpu.r[0] = 0
        }
        api("vm_load_resource") { cpu ->
            cpu.r[0] = loadNamedResource(arg(cpu, 0), arg(cpu, 1), cpu.trace)
        }
        api("vm_res_load") { cpu ->
            cpu.r[0] = loadNamedResource(arg(cpu, 0), arg(cpu, 1), cpu.trace)
        }
        api("vm_get_resource_offset") { cpu ->
            val name = readResourceNameCompat(arg(cpu, 0))
            cpu.r[0] = namedResources.firstOrNull { it.name == name }?.offset ?: -1
        }
        api("vm_get_resource_offset_from_file") { cpu ->
            cpu.r[0] = resourceOffsetFromFileCompat(arg(cpu, 0), arg(cpu, 1))
        }
        api("vm_load_resource_from_file") { cpu ->
            cpu.r[0] = loadResourceFromFileCompat(cpu)
        }
        api("vm_res_delete") { cpu ->
            val ptr = arg(cpu, 0)
            cpu.r[0] = when {
                externalResourceAllocations.remove(ptr) -> if (heap.free(ptr)) 0 else -1
                ptr >= RESOURCE_BASE && ptr.toLong() < RESOURCE_BASE.toLong() + resourceBlobSize.toLong() -> 0
                else -> -1
            }
        }
        api("vm_res_deinit") { cpu ->
            externalResourceAllocations.toList().forEach { heap.free(it) }
            externalResourceAllocations.clear()
            cpu.r[0] = 0
        }
        api("vm_resource_get_data") { cpu ->
            val dst = arg(cpu, 0)
            val offset = arg(cpu, 1)
            val size = arg(cpu, 2)
            val validRange = offset >= 0 && size >= 0 &&
                offset.toLong() + size.toLong() <= rawResourceBlob.size.toLong()
            val validDst = size == 0 || (dst != 0 && memory.isMapped(dst, size))
            val valid = validRange && validDst
            if (valid) {
                if (size > 0) memory.writeBytes(dst, rawResourceBlob, offset, size)
                cpu.r[0] = size
            } else {
                cpu.r[0] = -1
            }
            if (cpu.trace) println("[RES ] read off=0x${offset.toUInt().toString(16)} size=$size dst=0x${dst.toUInt().toString(16)} ok=$valid")
        }
        api("vm_resource_get_data_from_file") { cpu ->
            cpu.r[0] = resourceGetDataFromFileCompat(cpu)
        }

        // Some GCC-built VXP binaries request the symbol resolver as an ordinary function.
        api("vm_get_sym_entry") { cpu ->
            val name = runCatching { memory.readCString(arg(cpu, 0), 256) }.getOrDefault("")
            cpu.r[0] = resolveAddressOrStub(name)
        }

        // Lazy import resolver used by raw ARM VXP import veneers. The guest passes a
        // zero-terminated API name in r0 and receives a callable host trampoline.
        api("__vxp_resolver") { cpu ->
            val name = runCatching { memory.readCString(cpu.r[0], 256) }.getOrDefault("")
            cpu.r[0] = resolveAddressOrStub(name)
            if (cpu.trace) println("[BIND] $name -> 0x${cpu.r[0].toUInt().toString(16)}")
        }
    }

    /** Read an AAPCS integer/pointer argument. r0-r3 then stack words at current sp. */
    private fun arg(cpu: ArmCpu, index: Int): Int {
        require(index >= 0)
        return if (index < 4) cpu.r[index] else memory.read32(cpu.r[13] + (index - 4) * 4)
    }

    private fun decodeLayerList(pointerOrHandle: Int, count: Int): IntArray {
        if (count <= 0) {
            val active = graphics.activeLayerHandle
            return if (active == MreGraphics.VM_GRAPHIC_INVALID_LAYER) intArrayOf() else intArrayOf(active)
        }
        val safeCount = count.coerceAtMost(64)
        if (safeCount == 1 && graphics.layer(pointerOrHandle) != null) return intArrayOf(pointerOrHandle)
        if (!memory.isMapped(pointerOrHandle, safeCount * 4)) {
            return if (graphics.layer(pointerOrHandle) != null) intArrayOf(pointerOrHandle) else intArrayOf()
        }
        return IntArray(safeCount) { i -> memory.read32(pointerOrHandle + i * 4) }
    }

    private fun allocateTimerId(): Int {
        while (nextTimerId == 0 || timers.containsKey(nextTimerId)) nextTimerId++
        return nextTimerId++
    }

    private fun api(name: String, handler: (ArmCpu) -> Unit): Int {
        byName[name]?.let { return it.address }
        val address = API_BASE + byAddress.size * 4
        require(address < HOST_RETURN_TRAP) { "MRE API table exhausted" }
        val api = Api(name, address, handler)
        byAddress[address] = api
        byName[name] = api
        return address
    }

    val resolverAddress: Int get() = byName["__vxp_resolver"]!!.address

    fun addressOf(name: String): Int? = byName[name]?.address

    fun registeredApiNames(): List<String> = byName.keys.filterNot { it == "__vxp_resolver" }.sorted()
    fun resolvedSymbolNames(): List<String> = resolvedNames.toList()
    fun stubbedSymbolNames(): List<String> = stubbedNames.toList()

    fun resolveAddressOrStub(name: String): Int {
        if (name.isBlank()) return 0
        byName[name]?.let {
            resolvedNames += name
            return it.address
        }
        val address = api(name, compatibilityHandler(name))
        resolvedNames += name
        stubbedNames += name
        return address
    }

    private fun compatibilityHandler(name: String): (ArmCpu) -> Unit = { cpu ->
        if (cpu.trace) println("[STUB] $name")
        when (name) {
            "strtoi" -> {
                val text = runCatching { memory.readCString(cpu.r[0], 64) }.getOrDefault("")
                cpu.r[0] = text.trim().removePrefix("0x").toIntOrNull(if (text.trim().startsWith("0x", true)) 16 else 10) ?: 0
            }
            "vm_graphic_create_layer_ex" -> {
                // Observed compatibility pattern: (x,y,w,h,transColor,storageKind,externalBuffer).
                val external = arg(cpu, 6)
                val trans = decodeColorArg(arg(cpu, 4))
                cpu.r[0] = if (external != 0 && memory.isMapped(external, 2)) {
                    graphics.createLayerWithBuffer(arg(cpu, 0), arg(cpu, 1), arg(cpu, 2), arg(cpu, 3), trans, external)
                } else {
                    graphics.createLayer(arg(cpu, 0), arg(cpu, 1), arg(cpu, 2), arg(cpu, 3), trans)
                }
            }
            "vm_graphic_fill_rect_ex" -> {
                // Common MRE form: (layer, x, y, width, height), using current color.
                cpu.r[0] = graphics.fillRectOnLayer(arg(cpu, 0), arg(cpu, 1), arg(cpu, 2), arg(cpu, 3), arg(cpu, 4))
            }
            "vm_graphic_rect_ex" -> {
                cpu.r[0] = graphics.drawRectOnLayer(arg(cpu, 0), arg(cpu, 1), arg(cpu, 2), arg(cpu, 3), arg(cpu, 4))
            }
            "vm_graphic_load_image" -> {
                val ptr = arg(cpu, 0)
                val size = arg(cpu, 1)
                cpu.r[0] = if (ptr != 0 && size > 0 && size <= 32 * 1024 * 1024 && memory.isMapped(ptr, size)) {
                    graphics.loadImage(memory.readBytes(ptr, size))
                } else 0
            }
            "vm_graphic_get_img_property" -> {
                val handle = arg(cpu, 0)
                val width = graphics.imageWidth(handle)
                val height = graphics.imageHeight(handle)
                if (width <= 0 || height <= 0) {
                    cpu.r[0] = 0
                } else {
                    val info = imagePropertyPtrs.getOrPut(handle) { heap.calloc(16) }
                    if (info == 0) cpu.r[0] = 0 else {
                        // Observed MRE VM_GDI_IMAGE_INFO layout used by Gameloft: width/height
                        // are 16-bit fields at +6/+8. Populate conservative metadata around them.
                        memory.write16(info + 0, 1) // frame count
                        memory.write16(info + 2, 16) // RGB565 depth hint
                        memory.write16(info + 4, 0)
                        memory.write16(info + 6, width)
                        memory.write16(info + 8, height)
                        memory.write16(info + 10, 0)
                        cpu.r[0] = info
                    }
                }
            }
            "vm_ascii_to_ucs2" -> cpu.r[0] = asciiToUcs2Compat(cpu)
            "vm_ucs2_to_ascii" -> cpu.r[0] = ucs2ToAsciiCompat(cpu)
            "vm_gb2312_to_ucs2" -> cpu.r[0] = asciiToUcs2Compat(cpu)
            "vm_chset_convert" -> cpu.r[0] = charsetConvertCompat(cpu)
            "vm_get_exec_filename" -> {
                // Observed compatibility form writes a UCS2 path into caller buffer; return success.
                val dst = cpu.r[0]
                if (dst != 0 && memory.isMapped(dst, 4)) {
                    val ascii = "C:\\$rawExecutableName"
                    var p = dst
                    for (ch in ascii) { if (!memory.isMapped(p, 2)) break; memory.write16(p, ch.code); p += 2 }
                    if (memory.isMapped(p, 2)) memory.write16(p, 0)
                }
                cpu.r[0] = 0
            }
            "vm_appmgr_get_installed_list" -> {
                // Observed Gameloft ABI: (category/filter, VM_APP_INFO* list, VMINT* count).
                // A NULL list is a count query. We expose no host applications to the guest.
                val countPtr = arg(cpu, 2)
                if (countPtr != 0 && memory.isMapped(countPtr, 4)) memory.write32(countPtr, 0)
                cpu.r[0] = 0
            }
            "vm_appmgr_get_install_info" -> {
                // No host applications are visible. Clear a plausible output record if supplied.
                val out = arg(cpu, 1)
                if (out != 0 && memory.isMapped(out, 64)) memory.writeBytes(out, ByteArray(64))
                cpu.r[0] = -1
            }
            "vm_query_operator_code" -> {
                // ABI observed in Gameloft titles: VMINT vm_query_operator_code(char* mccMnc).
                // Use the reserved test-network MCC/MNC; never expose host SIM information.
                val dst = arg(cpu, 0)
                val code = "00101"
                if (dst != 0 && memory.isMapped(dst, code.length + 1)) {
                    code.forEachIndexed { i, ch -> memory.write8(dst + i, ch.code) }
                    memory.write8(dst + code.length, 0)
                    cpu.r[0] = 0
                } else cpu.r[0] = -1
            }
            "vm_get_res_header" -> cpu.r[0] = 0
            "vm_load_resource" -> cpu.r[0] = 0
            "vm_resource_get_data" -> cpu.r[0] = 0
            "vm_audio_resume_bg_play", "vm_audio_suspend_bg_play", "vm_audio_stop", "vm_midi_stop", "vm_set_volume" -> cpu.r[0] = 0
            "vm_midi_get_time" -> cpu.r[0] = 0
            "vm_midi_play_by_bytes" -> cpu.r[0] = 1
            "vm_open_wap_url", "vm_send_sms", "vm_asyn_http_req", "vm_cancel_asyn_http_req" -> cpu.r[0] = -1
            else -> cpu.r[0] = 0
        }
    }

    private fun playBytesCompat(cpu: ArmCpu, kind: MreAudioKind): Int {
        val ptr = arg(cpu, 0)
        val size = arg(cpu, 1)
        if (size <= 0 || size > MreFileSystem.MAX_IO_SIZE || ptr == 0 || !memory.isMapped(ptr, size)) return -1
        val formatHint = arg(cpu, 2)
        val bytes = runCatching { memory.readBytes(ptr, size) }.getOrNull() ?: return -1
        val id = audioIds.nextId()
        val request = MreAudioRequest(
            playbackId = id,
            kind = kind,
            source = MreAudioSource.Bytes(bytes, formatHint),
            volume = audioVolume
        )
        val accepted = runCatching { audioHost.play(request, ::onAudioHostEvent) }.getOrDefault(false)
        if (cpu.trace) println("[AUD ] play bytes kind=$kind id=$id size=$size format=$formatHint accepted=$accepted")
        // Existing corpus expects a positive success value for non-blocking playback.
        return if (accepted) 1 else -1
    }

    private fun playFileCompat(
        cpu: ArmCpu,
        kind: MreAudioKind,
        startPositionMs: Int = 0,
        loop: Boolean = false
    ): Int {
        val pathPtr = arg(cpu, 0)
        val guestPath = readAudioPathCompat(pathPtr)
        if (guestPath.isBlank()) return -1
        val hostFile = fileSystem.resolveMrePath(guestPath) ?: return -1
        if (!hostFile.isFile || hostFile.length() > MreFileSystem.MAX_IO_SIZE.toLong()) return -1
        val id = audioIds.nextId()
        val request = MreAudioRequest(
            playbackId = id,
            kind = kind,
            source = MreAudioSource.SandboxFile(hostFile, guestPath, arg(cpu, 1)),
            volume = audioVolume,
            startPositionMs = startPositionMs.coerceAtLeast(0),
            loop = loop
        )
        val accepted = runCatching { audioHost.play(request, ::onAudioHostEvent) }.getOrDefault(false)
        if (cpu.trace) println("[AUD ] play file kind=$kind id=$id path=$guestPath start=${request.startPositionMs} loop=${request.loop} accepted=$accepted")
        return if (accepted) 1 else -1
    }

    private fun audioBytesDurationCompat(cpu: ArmCpu, kind: MreAudioKind): Int {
        val ptr = arg(cpu, 0)
        val size = arg(cpu, 1)
        if (size <= 0 || size > MreFileSystem.MAX_IO_SIZE || ptr == 0 || !memory.isMapped(ptr, size)) return 0
        val bytes = runCatching { memory.readBytes(ptr, size) }.getOrNull() ?: return 0
        return MreAudioProbe.durationMs(bytes, kind).coerceAtLeast(0)
    }

    private fun readAudioPathCompat(address: Int): String {
        if (address == 0 || !memory.isMapped(address, 1)) return ""
        // MRE file APIs commonly use UCS2. Accept narrow strings too because some
        // GCC wrappers in the wild pass an already-converted path.
        val looksUcs2 = memory.isMapped(address, 4) && memory.read8(address + 1) == 0
        return if (looksUcs2) {
            readUcs2Compat(address, 512)
        } else {
            runCatching { memory.readCString(address, 1024) }.getOrDefault("")
        }
    }

    private fun normalizeGuestVolume(raw: Int): Float = when {
        raw <= 0 -> 0f
        raw <= 6 -> raw / 6f
        raw <= 15 -> raw / 15f
        raw <= 100 -> raw / 100f
        else -> 1f
    }.coerceIn(0f, 1f)

    private fun onAudioHostEvent(event: MreAudioHostEvent) {
        val callback = audioInterruptCallback
        if (callback == 0) return
        // Completion callback calling shape is intentionally compatibility-first: two
        // integer arguments (event, playbackId). A guest callback that consumes only
        // r0 safely ignores the extra register argument. Exact vendor enum values are
        // not imported into this clean-room project.
        val eventCode = when (event.type) {
            MreAudioHostEventType.COMPLETED -> 1
            MreAudioHostEventType.ERROR -> -1
            MreAudioHostEventType.INTERRUPTED -> 2
            MreAudioHostEventType.RESUMED -> 3
        }
        postGuestCallback(callback, eventCode, event.playbackId)
    }

    fun heapStats(): MreHeap.Stats = heap.stats()

    fun validateHeap(): Boolean = heap.validate()

    private fun traceHeap(op: String, requested: Int, ptr: Int) {
        println("[HEAP] $op size=$requested -> 0x${ptr.toUInt().toString(16)} ${heapSummary()}")
    }

    private fun heapSummary(): String {
        val s = heap.stats()
        return "used=${s.allocatedBytes} free=${s.freeBytes} largest=${s.largestFreeBlock} live=${s.liveAllocations} fail=${s.failedAllocations}"
    }

    private fun decodeColorArg(value: Int): Int {
        if (!memory.isMapped(value, 2)) return value and 0xFFFF
        // vm_graphic_setcolor is commonly passed a VM_COLOR/VMUINT16 object by
        // ARMCC-generated code. Prefer a plausible RGB565 word at the pointer.
        val c16 = memory.read16(value)
        if (memory.isMapped(value, 4)) {
            val b0 = memory.read8(value)
            val b1 = memory.read8(value + 1)
            val b2 = memory.read8(value + 2)
            val b3 = memory.read8(value + 3)
            // Some guest color objects store ARGB8888. If the bytes look like a populated color,
            // accept either ARGB or RGBA layout and convert to RGB565.
            val alphaFirst = b0 == 0xFF || b0 == 0x00
            if (alphaFirst && (b1 != 0 || b2 != 0 || b3 != 0)) return rgb888To565(b1, b2, b3)
        }
        return c16
    }

    private fun rgb888To565(r: Int, g: Int, b: Int): Int =
        ((r and 0xF8) shl 8) or ((g and 0xFC) shl 3) or ((b and 0xF8) ushr 3)

    private fun readUcs2Compat(address: Int, maxChars: Int = 2048): String {
        if (address == 0 || !memory.isMapped(address, 2)) return ""
        val out = StringBuilder()
        var p = address
        repeat(maxChars) {
            if (!memory.isMapped(p, 2)) return@repeat
            val ch = memory.read16(p)
            p += 2
            if (ch == 0) return out.toString()
            out.append(ch.toChar())
        }
        return out.toString()
    }

    /**
     * Resource names are normally narrow C strings. A few compiler wrappers can hand the
     * runtime a UCS2 buffer, so accept that representation only when the first two printable
     * characters clearly show the 8-bit/zero-byte pattern. This keeps ordinary ASCII names
     * exact and does not perform locale/case folding.
     */
    private fun readResourceNameCompat(address: Int): String {
        if (address == 0 || !memory.isMapped(address, 1)) return ""
        val looksUcs2 = memory.isMapped(address, 4) &&
            memory.read8(address) in 0x20..0x7e && memory.read8(address + 1) == 0 &&
            memory.read8(address + 2) in 0x20..0x7e && memory.read8(address + 3) == 0
        return if (looksUcs2) readUcs2Compat(address, 256)
        else runCatching { memory.readCString(address, 256) }.getOrDefault("")
    }

    private fun loadNamedResource(namePtr: Int, sizeOut: Int, trace: Boolean): Int {
        if (sizeOut != 0 && !memory.isMapped(sizeOut, 4)) return 0
        val name = readResourceNameCompat(namePtr)
        val entry = namedResources.firstOrNull {
            it.name == name && it.offset >= 0 && it.size >= 0 &&
                it.offset.toLong() + it.size.toLong() <= rawResourceBlob.size.toLong()
        }
        if (entry == null) {
            if (sizeOut != 0) memory.write32(sizeOut, 0)
            if (trace) println("[RES ] load '$name' -> missing")
            return 0
        }
        if (sizeOut != 0) memory.write32(sizeOut, entry.size)
        val result = RESOURCE_BASE + entry.offset
        if (trace) println("[RES ] load '$name' -> 0x${result.toUInt().toString(16)} size=${entry.size}")
        return result
    }

    private fun looksLikeGuestPath(address: Int): Boolean {
        if (address == 0 || !memory.isMapped(address, 6)) return false
        val text = readUcs2Compat(address, 260)
        return text.length >= 2 && text[1] == ':' && text[0].uppercaseChar() in charArrayOf('C', 'E')
    }

    private fun allocateExternalResource(bytes: ByteArray): Int {
        val allocSize = bytes.size.coerceAtLeast(1)
        if (allocSize > RESOURCE_MAX_SIZE) return 0
        val ptr = heap.malloc(allocSize)
        if (ptr == 0) return 0
        if (bytes.isNotEmpty()) memory.writeBytes(ptr, bytes)
        externalResourceAllocations += ptr
        return ptr
    }

    /**
     * Clean-room compatibility bridge for external resource packs.
     * Supported synthetic call shapes:
     *   vm_load_resource_from_file(VMWSTR file, VMSTR/VMWSTR name, VMUINT* sizeOut)
     *   vm_load_resource_from_file(VMWSTR file, VMUINT offset, VMUINT size, VMUINT* sizeOut)
     */
    private fun loadResourceFromFileCompat(cpu: ArmCpu): Int {
        val filePtr = arg(cpu, 0)
        val path = if (looksLikeGuestPath(filePtr)) readUcs2Compat(filePtr, 512) else return 0
        val bytes = fileSystem.readAll(path, RESOURCE_MAX_SIZE) ?: return 0
        val second = arg(cpu, 1)
        if (second != 0 && memory.isMapped(second, 1)) {
            val name = readResourceNameCompat(second)
            if (name.isNotBlank()) {
                val sizeOut = arg(cpu, 2)
                if (sizeOut != 0 && !memory.isMapped(sizeOut, 4)) return 0
                val entry = parseNamedResources(bytes).firstOrNull { it.name == name } ?: run {
                    if (sizeOut != 0) memory.write32(sizeOut, 0)
                    return 0
                }
                val payload = bytes.copyOfRange(entry.offset, entry.offset + entry.size)
                val ptr = allocateExternalResource(payload)
                if (sizeOut != 0) memory.write32(sizeOut, if (ptr != 0) payload.size else 0)
                return ptr
            }
        }
        val offset = second
        val size = arg(cpu, 2)
        val sizeOut = arg(cpu, 3)
        if (offset < 0 || size < 0 || size > RESOURCE_MAX_SIZE || offset.toLong() + size.toLong() > bytes.size.toLong()) return 0
        if (sizeOut != 0 && !memory.isMapped(sizeOut, 4)) return 0
        val ptr = allocateExternalResource(bytes.copyOfRange(offset, offset + size))
        if (sizeOut != 0) memory.write32(sizeOut, if (ptr != 0) size else 0)
        return ptr
    }

    /** Accept both (file,name) and (name,file) while no corpus ABI has selected one. */
    private fun resourceOffsetFromFileCompat(a0: Int, a1: Int): Int {
        val filePtr: Int
        val namePtr: Int
        if (looksLikeGuestPath(a0)) { filePtr = a0; namePtr = a1 }
        else if (looksLikeGuestPath(a1)) { filePtr = a1; namePtr = a0 }
        else return -1
        val bytes = fileSystem.readAll(readUcs2Compat(filePtr, 512), RESOURCE_MAX_SIZE) ?: return -1
        val name = readResourceNameCompat(namePtr)
        return parseNamedResources(bytes).firstOrNull { it.name == name }?.offset ?: -1
    }

    /** Accept both (file,dst,offset,size) and (dst,file,offset,size). */
    private fun resourceGetDataFromFileCompat(cpu: ArmCpu): Int {
        val a0 = arg(cpu, 0); val a1 = arg(cpu, 1)
        val filePtr: Int
        val dst: Int
        if (looksLikeGuestPath(a0)) { filePtr = a0; dst = a1 }
        else if (looksLikeGuestPath(a1)) { filePtr = a1; dst = a0 }
        else return -1
        val offset = arg(cpu, 2)
        val size = arg(cpu, 3)
        if (offset < 0 || size < 0 || size > MreFileSystem.MAX_IO_SIZE) return -1
        if (size > 0 && (dst == 0 || !memory.isMapped(dst, size))) return -1
        val payload = fileSystem.readRange(readUcs2Compat(filePtr, 512), offset.toLong(), size) ?: return -1
        if (payload.isNotEmpty()) memory.writeBytes(dst, payload)
        return payload.size
    }

    /**
     * Install the GCC/MRE `.vm_res` archive embedded in an ELF. Its directory is
     * a sequence of: C-string name, absolute ELF file offset, byte size. Data
     * begins later in the same section. We convert absolute file offsets to
     * offsets relative to the section so vm_load_resource can return stable guest
     * pointers from RESOURCE_BASE.
     */
    fun installElfVmResources(sectionBlob: ByteArray, sectionFileOffset: Int) {
        if (sectionBlob.isEmpty()) return
        val entries = mutableListOf<NamedResource>()
        var pos = 0
        var firstData = sectionBlob.size
        var count = 0
        while (count++ < 512 && pos < firstData && pos < sectionBlob.size) {
            var end = pos
            while (end < sectionBlob.size && end - pos < 512 && sectionBlob[end].toInt() != 0) end++
            if (end >= sectionBlob.size || end == pos || sectionBlob[end].toInt() != 0) break
            val nameBytes = sectionBlob.copyOfRange(pos, end)
            if (nameBytes.any { (it.toInt() and 0xff) !in 0x20..0x7e }) break
            val name = nameBytes.toString(Charsets.UTF_8)
            pos = end + 1
            if (pos + 8 > sectionBlob.size) break
            val absoluteOffset = readLe32(sectionBlob, pos); pos += 4
            val size = readLe32(sectionBlob, pos); pos += 4
            val relative = absoluteOffset - sectionFileOffset
            if (relative < 0 || size < 0 || relative.toLong() + size.toLong() > sectionBlob.size.toLong()) break
            firstData = minOf(firstData, relative)
            if (entries.none { it.name == name }) entries += NamedResource(name, relative, size)
        }
        rawResourceBlob = sectionBlob.copyOf()
        namedResources = entries
        mapResourceBlob(rawResourceBlob)
    }

    fun installRawResources(blob: ByteArray) {
        rawResourceBlob = blob.copyOf()
        namedResources = parseNamedResources(rawResourceBlob)
        if (blob.isEmpty()) {
            if (resourceBlobSize > 0) {
                memory.writeBytes(RESOURCE_BASE, ByteArray(resourceBlobSize), force = true)
                resourceBlobSize = 0
            }
            return
        }
        mapResourceBlob(blob)
    }

    private fun mapResourceBlob(blob: ByteArray) {
        require(blob.size <= RESOURCE_MAX_SIZE) { "VXP resource blob too large: ${blob.size}" }
        if (resourceMappedSize == 0) {
            resourceMappedSize = ((blob.size + 0xfff) and -0x1000).coerceAtLeast(0x1000)
            memory.map(RESOURCE_BASE, resourceMappedSize, read = true, write = false, exec = false)
        } else {
            require(blob.size <= resourceMappedSize) { "Replacement resource blob exceeds mapped arena" }
        }
        if (resourceBlobSize > blob.size) {
            memory.writeBytes(RESOURCE_BASE + blob.size, ByteArray(resourceBlobSize - blob.size), force = true)
        }
        memory.writeBytes(RESOURCE_BASE, blob, force = true)
        resourceBlobSize = blob.size
    }

    fun rawNamedResources(): List<NamedResource> = namedResources.toList()

    private fun parseNamedResources(blob: ByteArray): List<NamedResource> {
        if (blob.isEmpty()) return emptyList()
        val out = mutableListOf<NamedResource>()
        var pos = 0
        var firstDataOffset = blob.size
        repeat(128) {
            if (pos >= blob.size || pos >= firstDataOffset) return@repeat
            val end = run {
                var e = pos
                while (e < blob.size && e - pos < 255 && blob[e].toInt() != 0) e++
                e
            }
            if (end >= blob.size || blob[end].toInt() != 0) return out
            if (end == pos) return out // sentinel
            val nameBytes = blob.copyOfRange(pos, end)
            if (nameBytes.any { (it.toInt() and 0xff) !in 0x20..0x7e }) return out
            val name = nameBytes.toString(Charsets.US_ASCII)
            pos = end + 1
            if (pos + 8 > blob.size) return out
            val offset = readLe32(blob, pos); pos += 4
            val size = readLe32(blob, pos); pos += 4
            if (offset < 0 || size < 0 || offset.toLong() + size.toLong() > blob.size.toLong()) return out
            firstDataOffset = minOf(firstDataOffset, offset)
            if (out.none { it.name == name }) out += NamedResource(name, offset, size)
        }
        return out
    }

    private fun readLe32(bytes: ByteArray, p: Int): Int =
        (bytes[p].toInt() and 0xff) or
            ((bytes[p + 1].toInt() and 0xff) shl 8) or
            ((bytes[p + 2].toInt() and 0xff) shl 16) or
            ((bytes[p + 3].toInt() and 0xff) shl 24)

    private fun asciiToUcs2Compat(cpu: ArmCpu): Int {
        // Observed binaries differ in whether the second argument is a byte capacity.
        // Identify source/destination by mapped pointers and use the conventional
        // (dst, dstBytes, src) layout first.
        val dst = cpu.r[0]
        val capacity = cpu.r[1].coerceIn(0, 1 shl 20)
        val src = cpu.r[2]
        if (!memory.isMapped(dst, 2) || !memory.isMapped(src, 1)) return 0
        val maxChars = if (capacity >= 2) (capacity / 2 - 1).coerceAtLeast(0) else 1023
        var s = src
        var d = dst
        var count = 0
        while (count < maxChars && memory.isMapped(s, 1) && memory.isMapped(d, 2)) {
            val ch = memory.read8(s++)
            memory.write16(d, ch)
            d += 2
            if (ch == 0) return count
            count++
        }
        if (memory.isMapped(d, 2)) memory.write16(d, 0)
        return count
    }

    private fun ucs2ToAsciiCompat(cpu: ArmCpu): Int {
        val dst = cpu.r[0]
        val capacity = cpu.r[1].coerceIn(0, 1 shl 20)
        val src = cpu.r[2]
        if (!memory.isMapped(dst, 1) || !memory.isMapped(src, 2)) return 0
        val maxChars = if (capacity > 0) (capacity - 1).coerceAtLeast(0) else 2047
        var s = src
        var d = dst
        var count = 0
        while (count < maxChars && memory.isMapped(s, 2) && memory.isMapped(d, 1)) {
            val ch = memory.read16(s)
            s += 2
            if (ch == 0) {
                memory.write8(d, 0)
                return count
            }
            memory.write8(d++, if (ch in 1..255) ch else '?'.code)
            count++
        }
        if (memory.isMapped(d, 1)) memory.write8(d, 0)
        return count
    }

    private fun charsetConvertCompat(cpu: ArmCpu): Int {
        // Gameloft ARM builds call vm_chset_convert(srcCharset,dstCharset,src,dst,...).
        val srcCharset = cpu.r[0]
        val dstCharset = cpu.r[1]
        val src = cpu.r[2]
        val dst = cpu.r[3]
        if (!memory.isMapped(src, 1) || !memory.isMapped(dst, 1)) return -1

        // Character-set IDs used by this game are adjacent (0x25/0x26). Determine
        // direction from source bytes as an additional guard, then always terminate.
        val sourceLooksUcs2 = memory.isMapped(src, 4) && memory.read8(src + 1) == 0 && memory.read8(src + 3) == 0
        val toUcs2 = dstCharset == 0x26 || (!sourceLooksUcs2 && dstCharset != srcCharset)
        var count = 0
        if (toUcs2) {
            var s = src
            var d = dst
            while (count < 2047 && memory.isMapped(s, 1) && memory.isMapped(d, 2)) {
                val ch = memory.read8(s++)
                memory.write16(d, ch)
                d += 2
                if (ch == 0) break
                count++
            }
            if (memory.isMapped(dst + count * 2, 2)) memory.write16(dst + count * 2, 0)
        } else {
            var s = src
            var d = dst
            while (count < 2047 && memory.isMapped(s, 2) && memory.isMapped(d, 1)) {
                val ch = memory.read16(s)
                s += 2
                if (ch == 0) {
                    memory.write8(d, 0)
                    break
                }
                memory.write8(d++, if (ch in 1..255) ch else '?'.code)
                count++
            }
            if (memory.isMapped(dst + count, 1)) memory.write8(dst + count, 0)
        }
        if (cpu.trace) println("[CHAR] convert $srcCharset->$dstCharset count=$count src=0x${src.toUInt().toString(16)} dst=0x${dst.toUInt().toString(16)}")
        return 0
    }
    private fun sscanfCompat(cpu: ArmCpu): Int {
        val input = runCatching { memory.readCString(arg(cpu, 0), 4096) }.getOrDefault("")
        val format = runCatching { memory.readCString(arg(cpu, 1), 2048) }.getOrDefault("")
        var si = 0
        var fi = 0
        var nextOutArg = 2
        var assigned = 0

        fun skipInputSpace() { while (si < input.length && input[si].isWhitespace()) si++ }
        fun writeInt(ptr: Int, value: Int): Boolean {
            if (ptr == 0 || !memory.isMapped(ptr, 4)) return false
            memory.write32(ptr, value)
            return true
        }

        while (fi < format.length) {
            val fc = format[fi]
            if (fc.isWhitespace()) {
                while (fi < format.length && format[fi].isWhitespace()) fi++
                skipInputSpace()
                continue
            }
            if (fc != '%') {
                if (si >= input.length || input[si] != fc) break
                fi++; si++; continue
            }
            fi++
            if (fi < format.length && format[fi] == '%') {
                if (si >= input.length || input[si] != '%') break
                fi++; si++; continue
            }

            var suppress = false
            if (fi < format.length && format[fi] == '*') { suppress = true; fi++ }
            var width = 0
            while (fi < format.length && format[fi].isDigit()) {
                width = width * 10 + (format[fi] - '0')
                fi++
            }
            // Length modifiers do not change the 32-bit guest storage used by these tests.
            while (fi < format.length && format[fi] in charArrayOf('h','l','L','z','t','j')) fi++
            if (fi >= format.length) break
            val conv = format[fi++]
            if (conv != 'c' && conv != '[' && conv != 'n') skipInputSpace()
            val start = si
            val maxChars = if (width > 0) width else Int.MAX_VALUE

            var intValue: Int? = null
            var floatValue: Float? = null
            var textValue: String? = null
            var rawChars: String? = null

            when (conv) {
                'd','u','x','X','o','i' -> {
                    var p = si
                    if (p < input.length && input[p] in charArrayOf('+','-')) p++
                    val tokenStart = si
                    var base = when (conv) { 'x','X' -> 16; 'o' -> 8; else -> 10 }
                    if (conv == 'i') {
                        val rest = input.substring(p)
                        base = when {
                            rest.startsWith("0x", true) -> 16
                            rest.startsWith("0") && rest.length > 1 -> 8
                            else -> 10
                        }
                    }
                    if (base == 16 && p + 1 < input.length && input[p] == '0' && (input[p+1] == 'x' || input[p+1] == 'X')) p += 2
                    val digitsStart = p
                    while (p < input.length && p - tokenStart < maxChars && input[p].digitToIntOrNull(base) != null) p++
                    if (p == digitsStart) return assigned
                    val token = input.substring(tokenStart, p)
                    val negative = token.startsWith('-')
                    val body = token.removePrefix("+").removePrefix("-").removePrefix("0x").removePrefix("0X")
                    val parsed = body.toLongOrNull(base) ?: return assigned
                    val signed = if (negative) -parsed else parsed
                    intValue = signed.toInt()
                    si = p
                }
                'f','F','e','E','g','G' -> {
                    var p = si
                    val allowed = "0123456789+-.eE"
                    while (p < input.length && p - si < maxChars && input[p] in allowed) p++
                    if (p == si) return assigned
                    floatValue = input.substring(si, p).toFloatOrNull() ?: return assigned
                    si = p
                }
                's' -> {
                    var p = si
                    while (p < input.length && !input[p].isWhitespace() && p - si < maxChars) p++
                    if (p == si) return assigned
                    textValue = input.substring(si, p)
                    si = p
                }
                'c' -> {
                    val count = if (width > 0) width else 1
                    if (si + count > input.length) return assigned
                    rawChars = input.substring(si, si + count)
                    si += count
                }
                'n' -> intValue = si
                else -> return assigned
            }

            if (suppress) continue
            val ptr = arg(cpu, nextOutArg++)
            val ok = when {
                intValue != null -> writeInt(ptr, intValue)
                floatValue != null -> writeInt(ptr, java.lang.Float.floatToIntBits(floatValue))
                textValue != null -> {
                    val value = textValue
                    if (ptr == 0 || !memory.isMapped(ptr, value.length + 1)) false else {
                        value.forEachIndexed { i, ch -> memory.write8(ptr + i, ch.code) }
                        memory.write8(ptr + value.length, 0); true
                    }
                }
                rawChars != null -> {
                    val value = rawChars
                    if (ptr == 0 || !memory.isMapped(ptr, value.length)) false else {
                        value.forEachIndexed { i, ch -> memory.write8(ptr + i, ch.code) }; true
                    }
                }
                else -> false
            }
            if (!ok) return assigned
            if (conv != 'n') assigned++
            if (si == start && conv != 'n') break
        }
        return assigned
    }

    private fun systemAscii(key: String, value: String): Int {
        systemStrings[key]?.let { return it }
        val ptr = heap.malloc(value.length + 1)
        if (ptr == 0) return 0
        value.forEachIndexed { i, c -> memory.write8(ptr + i, c.code) }
        memory.write8(ptr + value.length, 0)
        systemStrings[key] = ptr
        return ptr
    }

    private fun writeUcs2(address: Int, value: String): Boolean {
        if (address == 0) return false
        val bytes = (value.length + 1) * 2
        if (!memory.isMapped(address, bytes)) return false
        var p = address
        for (ch in value) { memory.write16(p, ch.code); p += 2 }
        memory.write16(p, 0)
        return true
    }

    override fun close() {
        runCatching { audioHost.close() }
        fileSystem.close()
    }

    fun isApiAddress(pc: Int): Boolean = byAddress.containsKey(pc and -2)

    fun dispatch(cpu: ArmCpu): Boolean {
        val pc = cpu.r[15] and -2
        val api = byAddress[pc] ?: return false
        if (cpu.trace) {
            println(
                "[MRE] ${api.name}(r0=0x${cpu.r[0].toUInt().toString(16)}, " +
                    "r1=0x${cpu.r[1].toUInt().toString(16)}, r2=0x${cpu.r[2].toUInt().toString(16)}, " +
                    "r3=0x${cpu.r[3].toUInt().toString(16)})"
            )
        }
        api.handler(cpu)
        if (!cpu.halted) {
            val ret = cpu.r[14]
            cpu.thumb = (ret and 1) != 0
            cpu.r[15] = ret and -2
        }
        return true
    }

    fun postSystemEvent(message: Int, param: Int = 0) {
        events.add(MreEvent.System(message, param))
    }

    fun postKeyboardEvent(eventType: Int, keyCode: Int) {
        events.add(MreEvent.Keyboard(eventType, keyCode))
    }

    fun postPenEvent(eventType: Int, x: Int, y: Int) {
        events.add(MreEvent.Pen(eventType, x, y))
    }

    fun postGuestCallback(callback: Int, vararg args: Int) {
        if (callback != 0) events.add(MreEvent.GuestCallback(callback, args))
    }

    fun pollEvent(): MreEvent? = events.poll()

    fun enqueueDueTimers(nowNanos: Long = System.nanoTime()) {
        if (timers.isEmpty()) return
        val snapshot = timers.values.toList()
        for (timer in snapshot) {
            if (!timer.enabled || !timers.containsKey(timer.id)) continue
            if (nowNanos < timer.nextFireNanos) continue

            // Keep a repeating timer phase-stable even if the host was briefly late.
            val period = timer.intervalMs * 1_000_000L
            do {
                timer.nextFireNanos += period
            } while (timer.nextFireNanos <= nowNanos)

            events.add(MreEvent.Timer(timer.id, timer.callback))
        }
    }

    fun nanosUntilNextTimer(nowNanos: Long = System.nanoTime()): Long? {
        val next = timers.values.asSequence()
            .filter { it.enabled }
            .minOfOrNull { it.nextFireNanos } ?: return null
        return (next - nowNanos).coerceAtLeast(0L)
    }

    fun timerCount(): Int = timers.size

    fun requestExit(code: Int = 0) {
        exitCode = code
        exitRequested = true
    }

    fun printApiTable() {
        byAddress.values.forEach { println("0x${it.address.toUInt().toString(16).padStart(8, '0')}  ${it.name}") }
    }
}
