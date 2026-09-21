package vxpcore

import java.io.File

fun main() {
    val memory = GuestMemory()
    memory.map(0x1000, 0x1000, read = true, write = true, exec = true)
    val runtime = MreRuntime(memory, File("/mnt/data/vxp-arm-sub-fastpath-regression"))
    val cpu = ArmCpu(memory, runtime)

    // MOV r0,#10 ; MOV r1,#3 ; SUB r0,r0,r1 ; BX LR
    memory.write32(0x1000, 0xE3A0000A.toInt(), force = true)
    memory.write32(0x1004, 0xE3A01003.toInt(), force = true)
    memory.write32(0x1008, 0xE0400001.toInt(), force = true)
    memory.write32(0x100C, 0xE12FFF1E.toInt(), force = true)

    cpu.reset(0x1000)
    check(cpu.callGuest(0x1000) == 7) { "cached SUB produced wrong result" }
    val fills = cpu.decodeCacheStats().blockFills
    check(cpu.callGuest(0x1000) == 7)
    check(cpu.decodeCacheStats().blockFills == fills) { "hot SUB block unexpectedly re-decoded" }

    // Trace/single-step path must recognize the new decode kind too.
    cpu.trace = true
    cpu.reset(0x1000)
    check(cpu.callGuest(0x1000) == 7) { "trace/reference SUB decode produced wrong result" }
    cpu.trace = false

    // Executable-write invalidation must still expose modified operands.
    memory.write32(0x1004, 0xE3A01004.toInt(), force = true)
    cpu.reset(0x1000)
    check(cpu.callGuest(0x1000) == 6) { "stale SUB block survived executable write" }

    println("[OK] v0.8.9.1 ARM SUB cached/reference paths + invalidation regression")
    runtime.close()
}
