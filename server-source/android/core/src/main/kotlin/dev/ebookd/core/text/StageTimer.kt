package dev.ebookd.core.text

/**
 * Named milliseconds along one path, so an open can say *where* its time went.
 *
 * The acceptance number is a stopwatch — "reopening feels instant" — and a bare total turns the next
 * step into a guess: of everything this pipeline does, the parts that look expensive and the parts
 * that are expensive turned out not to be the same list. Each [lap] closes a segment, so the parts
 * sum to the whole and a log line can be read as a sequence rather than as durations measured
 * separately and quietly overlapping.
 *
 * A lap covers everything since the previous lap, including a dispatcher hop the timer sat across.
 * That is deliberate: the hop is part of the path the reader waits on.
 */
class StageTimer {
    private val parts = mutableListOf<Pair<String, Long>>()
    private var lapNanos = System.nanoTime()

    fun lap(name: String) {
        val now = System.nanoTime()
        parts += name to (now - lapNanos) / 1_000_000
        lapNanos = now
    }

    /** For a stage whose clock lives elsewhere — a coroutine that rounded to its own millisecond. */
    fun add(
        name: String,
        millis: Long,
    ) {
        parts += name to millis
    }

    /** `a=1ms b=2ms`, in the order the laps were taken. */
    override fun toString(): String = parts.joinToString(" ") { "${it.first}=${it.second}ms" }
}
