package vxpcore

import java.io.File

private data class ArmFastSnapshot(
    val regs: List<Int>,
    val dataWord: Int,
    val instructions: Long
)

private fun runArmFastParity(trace: Boolean): ArmFastSnapshot {
    val memory = GuestMemory()
    val code = 0x00100000
    val data = 0x00200000
    memory.map(code, 0x1000, read = true, write = true, exec = true)
    memory.map(data, 0x1000, read = true, write = true, exec = false)

    val words = intArrayOf(
        0xE3A00005.toInt(),
        0xE3A01007.toInt(),
        0xE0802001.toInt(),
        0xE5842000.toInt(),
        0xE5943000.toInt(),
        0xE353000C.toInt(),
        0x1A000001.toInt(),
        0xE2830001.toInt(),
        0xE12FFF1E.toInt(),
        0xE3A00000.toInt(),
        0xE12FFF1E.toInt()
    )
    words.forEachIndexed { i, word -> memory.write32(code + i * 4, word, force = true) }

    val runtime = MreRuntime(memory, File("/mnt/data/arm-cached-fastpath-${if (trace) "ref" else "cached"}"))
    val cpu = ArmCpu(memory, runtime, trace)
    cpu.reset(code)
    cpu.r[4] = data
    val result = cpu.callGuest(code)
    check(result == 13) { "unexpected result=$result trace=$trace" }
    val snapshot = ArmFastSnapshot(cpu.r.toList(), memory.read32(data), cpu.instructions)
    runtime.close()
    return snapshot
}

fun main() {
    val cached = runArmFastParity(trace = false)
    val reference = runArmFastParity(trace = true)
    check(cached.regs == reference.regs) { "cached ARM register state diverged from reference decoder" }
    check(cached.dataWord == reference.dataWord) { "cached ARM store/load state diverged" }
    check(cached.instructions == reference.instructions) { "cached ARM instruction accounting diverged" }
    println("[OK] ARM cached fast-path parity: ALU + LDR/STR + conditional branch match reference decoder")
}
