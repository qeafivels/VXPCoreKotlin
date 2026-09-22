package vxpcore

import java.nio.ByteBuffer
import java.nio.ByteOrder

private fun put16(b: ByteBuffer, off: Int, v: Int) { b.putShort(off, v.toShort()) }
private fun put32(b: ByteBuffer, off: Int, v: Int) { b.putInt(off, v) }

private fun syntheticLegacyElf(): ByteArray {
    val data = ByteArray(0x220)
    val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    data[0] = 0x7f
    data[1] = 'E'.code.toByte()
    data[2] = 'L'.code.toByte()
    data[3] = 'F'.code.toByte()
    data[4] = 1
    data[5] = 1
    data[6] = 1

    put16(b, 16, 2)
    put16(b, 18, 40)
    put32(b, 20, 1)
    put32(b, 24, 0x8000)

    val shoff = 0x34
    val shnum = 4
    val phoff = shoff + shnum * 40
    put32(b, 28, phoff)
    put32(b, 32, shoff)
    put16(b, 40, 52)
    put16(b, 42, 32)
    put16(b, 44, 1)
    put16(b, 46, 40)
    put16(b, 48, shnum)
    put16(b, 50, 0)

    var s = shoff + 40
    put32(b, s + 4, 1)
    put32(b, s + 8, 0x6)
    put32(b, s + 12, 0x8000)
    put32(b, s + 16, 0x180)
    put32(b, s + 20, 8)
    put32(b, s + 32, 4)

    s += 40
    put32(b, s + 4, 1)
    put32(b, s + 8, 0x3)
    put32(b, s + 12, 0)
    put32(b, s + 16, 0x188)
    put32(b, s + 20, 0xb0)
    put32(b, s + 32, 4)

    s += 40
    put32(b, s + 4, 8)
    put32(b, s + 8, 0x3)
    put32(b, s + 12, 0xb0)
    put32(b, s + 20, 0xe58)
    put32(b, s + 32, 4)

    // Deliberately malformed legacy PT_LOAD: p_offset points into ELF metadata.
    put32(b, phoff + 0, 1)
    put32(b, phoff + 4, 0x34)
    put32(b, phoff + 8, 0x8000)
    put32(b, phoff + 16, 8)
    put32(b, phoff + 20, 0x1000)
    put32(b, phoff + 24, 5)
    put32(b, phoff + 28, 4)

    put32(b, 0x180, 0xE3A0002A.toInt()) // MOV r0,#42
    put32(b, 0x184, 0xE12FFF1E.toInt()) // BX lr
    return data
}

fun main() {
    val elf = Elf32Arm.parse(syntheticLegacyElf())
    val memory = GuestMemory()
    val loaded = elf.load(memory)

    check(loaded.entry == 0x8000)
    check(memory.read32(0x8000, exec = true) == 0xE3A0002A.toInt()) {
        "legacy PT_LOAD correction failed: entry contains 0x${memory.read32(0x8000, true).toUInt().toString(16)}"
    }
    check(elf.relativeStaticDataSize() == 0xf08) {
        "RWPI size wrong: ${elf.relativeStaticDataSize()}"
    }

    println("[OK] legacy ELF PT_LOAD correction + RWPI static-data detection")
}
