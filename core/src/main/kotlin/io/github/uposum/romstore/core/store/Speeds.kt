package io.github.uposum.romstore.core.store

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

@Serializable
data class SourceSpeed(
    val speed: Double? = null,
    val delay: Double = 0.0,
    val samples: Int = 0,
    val updated: Long? = null,
    val tested: Long? = null,
    val testError: String? = null,
)

/**
 * How fast each provider has been, measured on real downloads and speed tests
 * (backend/speeds.py). Moving averages of bytes/s and of the start delay.
 */
class SpeedStats(private val file: File?) {
    companion object {
        /** A small file is mostly connection setup; it says little about throughput. */
        const val MIN_BYTES = 2L * 1024 * 1024
        const val ALPHA = 0.4

        /** Seconds until a file of this size is in, from a source's stats. */
        fun estimate(stats: SourceSpeed?, size: Long?): Double? {
            val speed = stats?.speed ?: return null
            if (speed <= 0) return null
            return stats.delay + (size ?: 0L) / speed
        }
    }

    private val serializer = MapSerializer(String.serializer(), SourceSpeed.serializer())
    private val sources: MutableMap<String, SourceSpeed> = (readJson(file, serializer) ?: emptyMap()).toMutableMap()

    @Synchronized
    fun record(source: String, size: Long, seconds: Double, delay: Double = 0.0): Boolean {
        if (source.isEmpty() || size < MIN_BYTES || seconds <= 0) return false
        val speed = size / maxOf(seconds, 0.5)
        val old = sources[source]
        val next = if (old?.speed == null) {
            SourceSpeed(speed, maxOf(0.0, delay), (old?.samples ?: 0) + 1, System.currentTimeMillis(), old?.tested, old?.testError)
        } else {
            old.copy(
                speed = ALPHA * speed + (1 - ALPHA) * old.speed,
                delay = ALPHA * maxOf(0.0, delay) + (1 - ALPHA) * old.delay,
                samples = old.samples + 1,
                updated = System.currentTimeMillis(),
            )
        }
        sources[source] = next
        writeJson(file, serializer, sources)
        return true
    }

    @Synchronized
    fun recordTest(source: String, size: Double, seconds: Double, delay: Double, error: String?) {
        if (source.isEmpty()) return
        val old = sources[source] ?: SourceSpeed()
        val now = System.currentTimeMillis()
        val next = if (error != null || size <= 0 || seconds <= 0) {
            old.copy(tested = now, testError = error ?: "nothing came in")
        } else {
            val speed = size / seconds
            if (old.speed != null) {
                old.copy(
                    speed = ALPHA * speed + (1 - ALPHA) * old.speed,
                    delay = ALPHA * maxOf(0.0, delay) + (1 - ALPHA) * old.delay,
                    samples = old.samples + 1, updated = now, tested = now, testError = null,
                )
            } else {
                old.copy(speed = speed, delay = maxOf(0.0, delay), samples = old.samples + 1, updated = now, tested = now, testError = null)
            }
        }
        sources[source] = next
        writeJson(file, serializer, sources)
    }

    /** When the least recently tested of these sources was tested (0: never). */
    @Synchronized
    fun lastTested(ids: Collection<String>): Long = ids.minOfOrNull { sources[it]?.tested ?: 0L } ?: 0L

    @Synchronized
    fun all(): Map<String, SourceSpeed> = sources.toMap()
}
