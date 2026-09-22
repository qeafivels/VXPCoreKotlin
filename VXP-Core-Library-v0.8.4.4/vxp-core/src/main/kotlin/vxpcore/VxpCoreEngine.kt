package vxpcore

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android/JVM-neutral VXP runner. The guest ARM code is always interpreted in Kotlin;
 * it is never loaded as host-native code.
 */
class VxpCoreEngine(
    private val storageRoot: File,
    private val textRasterizer: TextRasterizer = BitmapTextRasterizer(),
    private val trace: Boolean = false,
    private val audioHost: MreAudioHost = StateOnlyMreAudioHost()
) {
    enum class Backend { ELF_ARM, RAW_ARM_ZLIB, FLASH_LITE, UNKNOWN }

    data class Result(
        val backend: Backend,
        val exitCode: Int,
        val instructions: Long,
        val frames: Long,
        val events: Long,
        val timerCallbacks: Long,
        val timedOut: Boolean,
        val resolvedSymbols: List<String>,
        val stubbedSymbols: List<String>
    )

    @Volatile private var runtime: MreRuntime? = null
    @Volatile private var cpu: ArmCpu? = null
    private val running = AtomicBoolean(false)

    val instructions: Long get() = cpu?.instructions ?: 0L
    val isRunning: Boolean get() = running.get()

    fun detect(bytes: ByteArray): Backend = when (VxpContainer.detect(bytes)) {
        VxpContainer.Kind.ELF32_ARM,
        VxpContainer.Kind.ZLIB_ELF,
        VxpContainer.Kind.EMBEDDED_ELF -> Backend.ELF_ARM
        VxpContainer.Kind.RAW_ARM_ZLIB -> Backend.RAW_ARM_ZLIB
        VxpContainer.Kind.FLASH_LITE_SWF -> Backend.FLASH_LITE
        else -> Backend.UNKNOWN
    }

    /** Blocking run loop. Call this on a worker thread. */
    fun run(
        bytes: ByteArray,
        fileName: String,
        maxInstructionsPerCallback: Long = 20_000_000L,
        maxRuntimeMs: Long = 0L,
        onFrame: ((FrameSnapshot) -> Unit)? = null
    ): Result {
        check(running.compareAndSet(false, true)) { "VXP engine is already running" }
        try {
            return when (detect(bytes)) {
                Backend.RAW_ARM_ZLIB -> runRaw(bytes, fileName, maxInstructionsPerCallback, maxRuntimeMs, onFrame)
                Backend.ELF_ARM -> runElf(bytes, fileName, maxInstructionsPerCallback, maxRuntimeMs, onFrame)
                Backend.FLASH_LITE -> {
                    val info = FlashLiteSwf.inspect(bytes)
                    error(
                        "Flash Lite renderer is not yet ported to the Android host-neutral renderer " +
                            "(SWF v${info.version}, ${info.width}x${info.height}, ${info.frameCount} frames). " +
                            "The parser/probe is present; do not fall back to an external emulator/JNI."
                    )
                }
                Backend.UNKNOWN -> error("Unsupported/unknown VXP container")
            }
        } finally {
            cpu = null
            runtime?.close()
            runtime = null
            running.set(false)
        }
    }

    fun requestStop(exitCode: Int = 0) {
        runtime?.requestExit(exitCode)
    }

    fun postKeyboardEvent(eventType: Int, vmKeyCode: Int) {
        runtime?.postKeyboardEvent(eventType, vmKeyCode)
    }

    fun postPenEvent(eventType: Int, x: Int, y: Int) {
        runtime?.postPenEvent(eventType, x, y)
    }

    private fun runRaw(
        bytes: ByteArray,
        fileName: String,
        maxInstructionsPerCallback: Long,
        maxRuntimeMs: Long,
        onFrame: ((FrameSnapshot) -> Unit)?
    ): Result {
        val pkg = RawVxpPackage.parse(bytes)
        val vmMainOffset = pkg.vmMainOffset
            ?: error("Could not locate vm_main in stripped RAW_ARM_ZLIB image")

        val memory = GuestMemory()
        val codeSize = alignUp(pkg.code.size, 0x1000)
        memory.map(CODE_BASE, codeSize, read = true, write = false, exec = true)
        memory.writeBytes(CODE_BASE, pkg.code, force = true)

        val hintedData = maxOf(pkg.initialData.size, pkg.dataSizeHint)
        val hintedBss = maxOf(pkg.bssSizeHint, 0x4000)
        val dataRegionSize = alignUp(0x100 + hintedData + hintedBss + 0x4000, 0x1000)
        memory.map(DATA_REGION_BASE, dataRegionSize, read = true, write = true, exec = false)
        if (pkg.initialData.isNotEmpty()) memory.writeBytes(DATA_BASE, pkg.initialData)
        memory.write32(DATA_BASE, DATA_BASE)

        val rt = newRuntime(memory, fileName)
        runtime = rt
        rt.installRawResources(pkg.resources)
        rt.rawExecutableName = fileName
        rt.graphics.onFrame = onFrame
        memory.write32(DATA_BASE + pkg.resolverSlotOffset, rt.resolverAddress)

        val vmMain = CODE_BASE + vmMainOffset
        val c = ArmCpu(memory, rt, trace)
        cpu = c
        c.reset(vmMain)
        c.r[9] = DATA_BASE

        val stats = MreEventLoop(c, rt, maxInstructionsPerCallback, maxRuntimeMs).run(vmMain)
        return Result(
            backend = Backend.RAW_ARM_ZLIB,
            exitCode = rt.exitCode,
            instructions = c.instructions,
            frames = rt.graphics.flushCount,
            events = stats.eventsDispatched,
            timerCallbacks = stats.timerCallbacks,
            timedOut = stats.timedOut,
            resolvedSymbols = rt.resolvedSymbolNames(),
            stubbedSymbols = rt.stubbedSymbolNames()
        )
    }

    private fun runElf(
        bytes: ByteArray,
        fileName: String,
        maxInstructionsPerCallback: Long,
        maxRuntimeMs: Long,
        onFrame: ((FrameSnapshot) -> Unit)?
    ): Result {
        val payload = VxpContainer.extractElf(bytes)
        val elf = Elf32Arm.parse(payload.elf)
        val memory = GuestMemory()
        val loaded = elf.load(memory)
        val relativeStaticDataSize = elf.relativeStaticDataSize()
        if (relativeStaticDataSize > 0) {
            val staticRegionSize = alignUp(0x100 + relativeStaticDataSize, 0x1000)
            memory.map(DATA_REGION_BASE, staticRegionSize, read = true, write = true, exec = false)
            if (trace) println("[ELF ] RWPI static base=0x${DATA_BASE.toUInt().toString(16)} size=${relativeStaticDataSize}")
        }
        val rt = newRuntime(memory, fileName)
        runtime = rt
        rt.rawExecutableName = fileName
        if (relativeStaticDataSize > 0) {
            // Legacy ARMCC/RVCT MRE loaders provide a small control block directly
            // below r9/SB. The C runtime reads these words before vm_main:
            //   SB-0x80 saved startup stack, SB-0x7c vm_get_sym_entry,
            //   SB-0x78 heap base, SB-0x74 heap limit, SB-0x70 stack size.
            // Keep the block host-owned and outside the guest RW/ZI image.
            val startupFrame = MreRuntime.STACK_BASE + MreRuntime.STACK_SIZE - 16
            memory.write32(DATA_BASE - 0x80, startupFrame)
            memory.write32(DATA_BASE - 0x7c, rt.resolverAddress)
            memory.write32(DATA_BASE - 0x78, MreRuntime.HEAP_BASE)
            memory.write32(DATA_BASE - 0x74, MreRuntime.HEAP_BASE + MreRuntime.HEAP_SIZE)
            memory.write32(DATA_BASE - 0x70, MreRuntime.STACK_SIZE)
            // __rt_exit restores this loader frame with POP {r0,pc}.
            memory.write32(startupFrame, 0)
            memory.write32(startupFrame + 4, MreRuntime.HOST_RETURN_TRAP)
        }
        rt.graphics.onFrame = onFrame
        elf.sections.firstOrNull { it.name == ".vm_res" && it.size > 0 }?.let { sec ->
            require(sec.offset >= 0 && sec.size >= 0 && sec.offset.toLong() + sec.size <= elf.bytes.size.toLong())
            rt.installElfVmResources(elf.bytes.copyOfRange(sec.offset, sec.offset + sec.size), sec.offset)
            if (trace) println("[ELF ] .vm_res entries=${rt.rawNamedResources().size} bytes=${sec.size}")
        }
        val unresolved = elf.applyRelocations(memory, loaded, rt)
        if (trace && unresolved.isNotEmpty()) {
            println("[WARN] unresolved imports: ${unresolved.joinToString()}")
        }

        val vmMain = elf.definedSymbolAddress("vm_main", loaded)
            ?: elf.definedSymbolAddress("_vm_main", loaded)
            ?: loaded.entry
        val gccEntry = elf.definedSymbolAddress("gcc_entry", loaded)
        val c = ArmCpu(memory, rt, trace)
        cpu = c

        val legacyRwpiEntry = gccEntry == null && relativeStaticDataSize > 0
        val bootstrappedByEntry = gccEntry != null || legacyRwpiEntry
        if (gccEntry != null) {
            // GCC-based MRE VXP binaries are entered through gcc_entry, not vm_main.
            // ABI observed in real VXP builds:
            //   r0 = vm_get_sym_entry resolver
            //   r1 = .init_array base
            //   r2 = .init_array entry count
            // gcc_entry stores the resolver, executes constructors (starting at index 1),
            // then calls vm_main. Calling vm_main directly leaves the local vm_* wrappers
            // unbound and skips C/C++ static initialization.
            val initArray = elf.sectionAddress(".init_array", loaded) ?: 0
            val initCount = elf.sectionSize(".init_array") / 4
            if (trace) println("[ELF ] gcc_entry @0x${gccEntry.toUInt().toString(16)} resolver=0x${rt.resolverAddress.toUInt().toString(16)} init=0x${initArray.toUInt().toString(16)} count=$initCount")
            c.reset(gccEntry)
            c.callGuest(gccEntry, intArrayOf(rt.resolverAddress, initArray, initCount), maxInstructionsPerCallback)
        } else {
            c.reset(loaded.entry)
            if (relativeStaticDataSize > 0) c.r[9] = DATA_BASE
            if (legacyRwpiEntry) {
                // RVCT/ADS MRE entry stubs receive vm_get_sym_entry in r0, store it
                // in r9-relative static data, run scatter initialization, then call
                // the application's vm_main body.
                if (trace) println("[ELF ] RWPI entry resolver=0x${rt.resolverAddress.toUInt().toString(16)}")
                c.callGuest(loaded.entry, intArrayOf(rt.resolverAddress), maxInstructionsPerCallback)
            }
        }

        val stats = MreEventLoop(c, rt, maxInstructionsPerCallback, maxRuntimeMs).run(vmMain, invokeVmMain = !bootstrappedByEntry)
        return Result(
            backend = Backend.ELF_ARM,
            exitCode = rt.exitCode,
            instructions = c.instructions,
            frames = rt.graphics.flushCount,
            events = stats.eventsDispatched,
            timerCallbacks = stats.timerCallbacks,
            timedOut = stats.timedOut,
            resolvedSymbols = rt.resolvedSymbolNames(),
            stubbedSymbols = (rt.stubbedSymbolNames() + unresolved).distinct()
        )
    }

    private fun newRuntime(memory: GuestMemory, fileName: String): MreRuntime {
        val safe = fileName.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._-]"), "_")
        val fsRoot = File(storageRoot, safe.ifBlank { "app" })
        fsRoot.mkdirs()
        return MreRuntime(memory, fsRoot, textRasterizer, audioHost)
    }

    private fun alignUp(v: Int, a: Int): Int = (v + a - 1) and -a

    companion object {
        const val CODE_BASE = 0x10000000
        const val DATA_REGION_BASE = 0x30000000
        const val DATA_BASE = DATA_REGION_BASE + 0x100
    }
}
