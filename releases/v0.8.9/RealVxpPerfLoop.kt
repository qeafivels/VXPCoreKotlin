package vxpcore

import java.io.File

private data class PerfSample(
    val trial: Int,
    val frames: Long,
    val events: Long,
    val timers: Long,
    val instructions: Long,
    val timedOut: Boolean
)

private fun median(values: List<Long>): Long {
    val sorted = values.sorted()
    return sorted[sorted.size / 2]
}

fun main(args: Array<String>) {
    require(args.size >= 3) {
        "usage: RealVxpPerfLoop <file.vxp> <runtime-ms> <trials> [storage-root]"
    }
    val vxp = File(args[0]).canonicalFile
    val runtimeMs = args[1].toLong().coerceAtLeast(250L)
    val trials = args[2].toInt().coerceIn(1, 100)
    val storageBase = File(args.getOrElse(3) { File(System.getProperty("java.io.tmpdir"), "vxp-perf-loop").path })
    require(vxp.isFile) { "VXP not found: $vxp" }

    val samples = ArrayList<PerfSample>(trials)
    repeat(trials) { index ->
        val storage = File(storageBase, "trial-${index + 1}")
        storage.deleteRecursively()
        storage.mkdirs()
        val bytes = vxp.readBytes()
        val session = VxpCoreLibrary.launch(
            bytes = bytes,
            fileName = vxp.name,
            storageRoot = storage,
            options = VxpSessionOptions(maxRuntimeMs = runtimeMs)
        )
        val result = requireNotNull(session.awaitResult(runtimeMs + 15_000L)) { "no result for ${vxp.name}" }
        session.close()
        val sample = PerfSample(
            trial = index + 1,
            frames = result.frames,
            events = result.events,
            timers = result.timerCallbacks,
            instructions = result.instructions,
            timedOut = result.timedOut
        )
        samples += sample
        val gateFps = sample.frames * 1000.0 / runtimeMs.toDouble()
        println(
            "trial=${sample.trial} frames=${sample.frames} gate_fps=%.3f events=${sample.events} timers=${sample.timers} insn=${sample.instructions} timedOut=${sample.timedOut}".format(gateFps)
        )
    }

    val medianFrames = median(samples.map { it.frames })
    val medianInstructions = median(samples.map { it.instructions })
    val bestFrames = samples.maxOf { it.frames }
    val bestInstructions = samples.maxOf { it.instructions }
    println(
        "summary trials=$trials runtime_ms=$runtimeMs median_frames=$medianFrames median_gate_fps=%.3f best_frames=$bestFrames median_insn=$medianInstructions best_insn=$bestInstructions".format(
            medianFrames * 1000.0 / runtimeMs.toDouble()
        )
    )
}
