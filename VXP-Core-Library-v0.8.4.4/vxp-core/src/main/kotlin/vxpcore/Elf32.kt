package vxpcore

import java.nio.ByteBuffer
import java.nio.ByteOrder

object ElfConst {
    const val EM_ARM = 40
    const val PT_LOAD = 1
    const val SHT_SYMTAB = 2
    const val SHT_STRTAB = 3
    const val SHT_REL = 9
    const val SHT_DYNSYM = 11

    const val SHN_UNDEF = 0

    const val R_ARM_ABS32 = 2
    const val R_ARM_REL32 = 3
    const val R_ARM_THM_CALL = 10
    const val R_ARM_GLOB_DAT = 21
    const val R_ARM_JUMP_SLOT = 22
    const val R_ARM_RELATIVE = 23
    const val R_ARM_CALL = 28
    const val R_ARM_JUMP24 = 29
    const val R_ARM_THM_JUMP24 = 30
}

data class ElfProgramHeader(
    val type: Int,
    val offset: Int,
    val vaddr: Int,
    val filesz: Int,
    val memsz: Int,
    val flags: Int,
    val align: Int
)

data class ElfSectionHeader(
    val nameOffset: Int,
    val type: Int,
    val flags: Int,
    val addr: Int,
    val offset: Int,
    val size: Int,
    val link: Int,
    val info: Int,
    val addralign: Int,
    val entsize: Int,
    var name: String = ""
)

data class ElfSymbol(
    val name: String,
    val value: Int,
    val size: Int,
    val info: Int,
    val other: Int,
    val shndx: Int
)

class Elf32Arm private constructor(
    val bytes: ByteArray,
    val type: Int,
    val entry: Int,
    val programHeaders: List<ElfProgramHeader>,
    val sections: List<ElfSectionHeader>
) {
    companion object {
        fun parse(bytes: ByteArray): Elf32Arm {
            require(bytes.size >= 52) { "ELF file too small" }
            require(bytes[0] == 0x7f.toByte() && bytes[1] == 'E'.code.toByte() && bytes[2] == 'L'.code.toByte() && bytes[3] == 'F'.code.toByte()) { "Not an ELF file" }
            require(bytes[4].toInt() == 1) { "Only ELF32 is supported" }
            require(bytes[5].toInt() == 1) { "Only little-endian ELF is supported" }
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val type = u16(b, 16)
            val machine = u16(b, 18)
            require(machine == ElfConst.EM_ARM) { "ELF machine=$machine, expected ARM (${ElfConst.EM_ARM})" }
            val entry = b.getInt(24)
            val phoff = b.getInt(28)
            val shoff = b.getInt(32)
            val phentsize = u16(b, 42)
            val phnum = u16(b, 44)
            val shentsize = u16(b, 46)
            val shnum = u16(b, 48)
            val shstrndx = u16(b, 50)

            val ph = ArrayList<ElfProgramHeader>()
            for (i in 0 until phnum) {
                val o = phoff + i * phentsize
                require(o >= 0 && o + 32 <= bytes.size) { "Broken program header #$i" }
                ph += ElfProgramHeader(
                    type = b.getInt(o),
                    offset = b.getInt(o + 4),
                    vaddr = b.getInt(o + 8),
                    filesz = b.getInt(o + 16),
                    memsz = b.getInt(o + 20),
                    flags = b.getInt(o + 24),
                    align = b.getInt(o + 28)
                )
            }

            val sh = ArrayList<ElfSectionHeader>()
            if (shoff != 0 && shentsize >= 40 && shnum > 0) {
                for (i in 0 until shnum) {
                    val o = shoff + i * shentsize
                    require(o >= 0 && o + 40 <= bytes.size) { "Broken section header #$i" }
                    sh += ElfSectionHeader(
                        nameOffset = b.getInt(o),
                        type = b.getInt(o + 4),
                        flags = b.getInt(o + 8),
                        addr = b.getInt(o + 12),
                        offset = b.getInt(o + 16),
                        size = b.getInt(o + 20),
                        link = b.getInt(o + 24),
                        info = b.getInt(o + 28),
                        addralign = b.getInt(o + 32),
                        entsize = b.getInt(o + 36)
                    )
                }
                if (shstrndx in sh.indices) {
                    val str = sh[shstrndx]
                    for (s in sh) s.name = readZ(bytes, str.offset + s.nameOffset, str.offset + str.size)
                }
            }

            return Elf32Arm(bytes, type, entry, ph, sh)
        }

        private fun u16(b: ByteBuffer, o: Int) = b.getShort(o).toInt() and 0xffff
        private fun readZ(data: ByteArray, start: Int, limit: Int): String {
            if (start !in data.indices || start >= limit) return ""
            var e = start
            val max = minOf(limit, data.size)
            while (e < max && data[e].toInt() != 0) e++
            return data.copyOfRange(start, e).toString(Charsets.UTF_8)
        }
    }

    data class Loaded(val entry: Int, val loadBias: Int, val imageStart: Int, val imageEnd: Int)

    fun load(memory: GuestMemory): Loaded {
        val loads = programHeaders.filter { it.type == ElfConst.PT_LOAD && it.memsz > 0 }
        require(loads.isNotEmpty()) { "ELF has no PT_LOAD segment" }

        val minV = loads.minOf { it.vaddr.toUInt().toLong() }
        val loadBias = if (type == 3 && minV < 0x10000L) 0x00010000 else 0
        var imageStart = Int.MAX_VALUE
        var imageEnd = 0

        for (p in loads) {
            require(p.filesz >= 0 && p.memsz >= p.filesz) { "Invalid PT_LOAD sizes" }
            val sourceOffset = correctedLoadOffset(p)
            require(sourceOffset >= 0 && sourceOffset.toLong() + p.filesz <= bytes.size.toLong()) { "PT_LOAD outside file" }
            val start = pageDown(p.vaddr + loadBias)
            val end = pageUp(p.vaddr + loadBias + p.memsz)
            val size = end - start
            val read = (p.flags and 4) != 0
            val write = (p.flags and 2) != 0
            val exec = (p.flags and 1) != 0
            if (!memory.isMapped(start, size)) memory.map(start, size, read = read || exec, write = write, exec = exec)
            if (p.filesz > 0) memory.writeBytes(p.vaddr + loadBias, bytes, sourceOffset, p.filesz, force = true)
            imageStart = minOf(imageStart, start)
            imageEnd = maxOf(imageEnd, end)
        }
        return Loaded(entry + loadBias, loadBias, imageStart, imageEnd)
    }

    /**
     * Some legacy ARM/ADS-produced VXP ELF files contain a PT_LOAD whose file offset
     * points at the ELF metadata even though the executable section that owns e_entry
     * starts later in the file. Loading that segment literally makes the CPU execute
     * section/program headers before reaching the real entry code.
     *
     * A conforming ELF has identical file offsets for an address whether calculated
     * through PT_LOAD or through the executable SHF_ALLOC section. When they disagree,
     * prefer the section mapping only for the PT_LOAD that contains e_entry, and only
     * when the corrected full segment still fits in the file. This keeps normal ELF
     * behavior unchanged while accepting the legacy scatter-loaded layout.
     */
    private fun correctedLoadOffset(p: ElfProgramHeader): Int {
        val entryU = entry.toUInt().toLong()
        val pStart = p.vaddr.toUInt().toLong()
        val pEnd = pStart + p.memsz.toLong()
        if (entryU !in pStart until pEnd) return p.offset

        val entrySection = sections.firstOrNull { sec ->
            val start = sec.addr.toUInt().toLong()
            val end = start + sec.size.toLong()
            sec.type != 8 &&
                (sec.flags and 0x2) != 0 && // SHF_ALLOC
                (sec.flags and 0x4) != 0 && // SHF_EXECINSTR
                sec.size > 0 && entryU in start until end
        } ?: return p.offset

        val segmentEntryOffset = p.offset.toLong() + (entryU - pStart)
        val sectionStart = entrySection.addr.toUInt().toLong()
        val sectionEntryOffset = entrySection.offset.toLong() + (entryU - sectionStart)
        val correction = sectionEntryOffset - segmentEntryOffset
        if (correction == 0L) return p.offset

        val corrected = p.offset.toLong() + correction
        val correctedEnd = corrected + p.filesz.toLong()
        if (corrected < 0L || correctedEnd > bytes.size.toLong()) return p.offset

        return corrected.toInt()
    }

    /**
     * ARM/ADS RWPI images may describe writable sections as offsets from r9/SB
     * instead of fixed virtual addresses. A zero-based writable SHF_ALLOC range
     * is the observable signature used by the legacy MRE scatter loader.
     */
    fun relativeStaticDataSize(): Int {
        val writable = sections.filter { sec ->
            sec.size > 0 && (sec.flags and 0x2) != 0 && (sec.flags and 0x1) != 0
        }
        if (writable.none { it.addr == 0 }) return 0
        var maxEnd = 0L
        for (sec in writable) {
            val start = sec.addr.toUInt().toLong()
            val end = start + sec.size.toLong()
            if (end > 0x01000000L) return 0
            maxEnd = maxOf(maxEnd, end)
        }
        return maxEnd.toInt()
    }

    fun undefinedSymbols(): Set<String> {
        val out = linkedSetOf<String>()
        for ((index, sec) in sections.withIndex()) {
            if (sec.type != ElfConst.SHT_SYMTAB && sec.type != ElfConst.SHT_DYNSYM) continue
            val symbols = readSymbols(index)
            symbols.filter { it.shndx == ElfConst.SHN_UNDEF && it.name.isNotBlank() }.forEach { out += it.name }
        }
        return out
    }


    fun sectionAddress(name: String, loaded: Loaded): Int? =
        sections.firstOrNull { it.name == name }?.let { it.addr + loaded.loadBias }

    fun sectionSize(name: String): Int = sections.firstOrNull { it.name == name }?.size ?: 0

    fun definedSymbolAddress(name: String, loaded: Loaded): Int? {
        for ((index, sec) in sections.withIndex()) {
            if (sec.type != ElfConst.SHT_SYMTAB && sec.type != ElfConst.SHT_DYNSYM) continue
            val symbol = readSymbols(index).firstOrNull { it.shndx != ElfConst.SHN_UNDEF && it.name == name } ?: continue
            return symbol.value + loaded.loadBias
        }
        return null
    }

    fun applyRelocations(memory: GuestMemory, loaded: Loaded, runtime: MreRuntime): List<String> {
        val unresolved = linkedSetOf<String>()
        for (relSec in sections) {
            if (relSec.type != ElfConst.SHT_REL || relSec.entsize <= 0 || relSec.link !in sections.indices) continue
            val symSecIndex = relSec.link
            val symbols = readSymbols(symSecIndex)
            var o = relSec.offset
            val end = relSec.offset + relSec.size
            while (o + 8 <= end && o + 8 <= bytes.size) {
                val rOffsetRaw = getI32(o)
                val rInfo = getI32(o + 4)
                val type = rInfo and 0xff
                val symIndex = rInfo ushr 8
                val place = rOffsetRaw + loaded.loadBias
                val addend = memory.read32(place)
                // R_ARM_RELATIVE is defined with symbol index 0. It must be
                // applied from the in-place addend even though symbol #0 is SHN_UNDEF.
                // Skipping it leaves GOT/init_array/data pointers at their unbased VAs.
                if (type == ElfConst.R_ARM_RELATIVE) {
                    memory.write32(place, loaded.loadBias + addend, force = true)
                    o += relSec.entsize.coerceAtLeast(8)
                    continue
                }
                if (type == 0) {
                    o += relSec.entsize.coerceAtLeast(8)
                    continue
                }

                val sym = symbols.getOrNull(symIndex)
                val symValue = when {
                    sym == null -> 0
                    sym.shndx == ElfConst.SHN_UNDEF -> {
                        val resolved = runtime.addressOf(sym.name)
                        if (resolved == null) {
                            if (sym.name.isNotBlank()) unresolved += sym.name
                            0
                        } else resolved
                    }
                    else -> sym.value + loaded.loadBias
                }
                if (sym == null || sym.shndx != ElfConst.SHN_UNDEF || symValue != 0) {
                    when (type) {
                        ElfConst.R_ARM_ABS32 -> memory.write32(place, symValue + addend, force = true)
                        ElfConst.R_ARM_REL32 -> memory.write32(place, symValue + addend - place, force = true)
                        ElfConst.R_ARM_GLOB_DAT, ElfConst.R_ARM_JUMP_SLOT -> memory.write32(place, symValue, force = true)
                        ElfConst.R_ARM_CALL, ElfConst.R_ARM_JUMP24 -> patchArmBranch(memory, place, symValue)
                        ElfConst.R_ARM_THM_CALL, ElfConst.R_ARM_THM_JUMP24 -> patchThumbBranch(memory, place, symValue)
                        else -> System.err.println("[WARN] unsupported ARM relocation type=$type at 0x${place.toUInt().toString(16)}")
                    }
                }
                o += relSec.entsize.coerceAtLeast(8)
            }
        }
        return unresolved.toList()
    }

    private fun patchArmBranch(memory: GuestMemory, place: Int, target: Int) {
        val old = memory.read32(place)
        val delta = target.toLong() - (place.toLong() + 8L)
        require(delta % 4L == 0L && delta in -33554432L..33554428L) {
            "ARM branch relocation out of range: 0x${place.toUInt().toString(16)} -> 0x${target.toUInt().toString(16)}"
        }
        val imm24 = ((delta shr 2).toInt() and 0x00ffffff)
        memory.write32(place, (old and 0xff000000.toInt()) or imm24, force = true)
    }

    private fun patchThumbBranch(memory: GuestMemory, place: Int, target: Int) {
        // ARMv5 Thumb-1 BL pair. This intentionally handles the classic 2x16-bit encoding used by ARM7EJ-S toolchains.
        val delta = target.toLong() - (place.toLong() + 4L)
        require(delta % 2L == 0L && delta in -4194304L..4194302L) {
            "Thumb BL relocation out of range: 0x${place.toUInt().toString(16)} -> 0x${target.toUInt().toString(16)}"
        }
        val off = delta.toInt()
        val hi = (off shr 12) and 0x7ff
        val lo = (off shr 1) and 0x7ff
        memory.write16(place, 0xF000 or hi, force = true)
        memory.write16(place + 2, 0xF800 or lo, force = true)
    }

    private fun readSymbols(sectionIndex: Int): List<ElfSymbol> {
        val sec = sections[sectionIndex]
        if (sec.entsize <= 0 || sec.link !in sections.indices) return emptyList()
        val str = sections[sec.link]
        val out = ArrayList<ElfSymbol>()
        var o = sec.offset
        val end = minOf(sec.offset + sec.size, bytes.size)
        while (o + 16 <= end) {
            val nameOff = getI32(o)
            val name = readString(str.offset + nameOff, str.offset + str.size)
            out += ElfSymbol(
                name = name,
                value = getI32(o + 4),
                size = getI32(o + 8),
                info = bytes[o + 12].toInt() and 0xff,
                other = bytes[o + 13].toInt() and 0xff,
                shndx = getU16(o + 14)
            )
            o += sec.entsize
        }
        return out
    }

    private fun readString(start: Int, limit: Int): String {
        if (start !in bytes.indices || start >= limit) return ""
        var e = start
        val max = minOf(limit, bytes.size)
        while (e < max && bytes[e].toInt() != 0) e++
        return bytes.copyOfRange(start, e).toString(Charsets.UTF_8)
    }

    private fun getI32(o: Int): Int =
        (bytes[o].toInt() and 0xff) or
            ((bytes[o + 1].toInt() and 0xff) shl 8) or
            ((bytes[o + 2].toInt() and 0xff) shl 16) or
            ((bytes[o + 3].toInt() and 0xff) shl 24)

    private fun getU16(o: Int): Int = (bytes[o].toInt() and 0xff) or ((bytes[o + 1].toInt() and 0xff) shl 8)

    private fun pageDown(v: Int) = v and -4096
    private fun pageUp(v: Int) = (v + 4095) and -4096
}
