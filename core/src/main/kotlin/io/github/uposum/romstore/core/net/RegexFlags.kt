package io.github.uposum.romstore.core.net

/**
 * Prefix for patterns that must treat \s, \d and \b as Unicode, like Python 3.
 * Desktop Java needs the (?U) flag for that; Android's ICU regex engine is
 * Unicode-aware already and rejects the flag (the class fails to load), so it
 * is left out there.
 */
val UNI: String = if (isAndroid()) "" else "(?U)"

private fun isAndroid(): Boolean =
    System.getProperty("java.vm.vendor")?.contains("Android", ignoreCase = true) == true ||
        System.getProperty("java.vm.name")?.equals("Dalvik", ignoreCase = true) == true ||
        runCatching { Class.forName("android.os.Build") }.isSuccess

/*
 * Kotlin's Regex.findAll() and replace(input) { ... } create a new Matcher for
 * every match. On Android each new Matcher copies the whole input into native
 * memory (ICU keeps its own copy), freed only when the GC gets to it: running
 * findAll over a multi-megabyte page (a libretro box-art index) allocated
 * gigabytes in seconds and the app was killed. These walk the input with one
 * Matcher. Use them instead of findAll / replace-with-lambda everywhere.
 */

private class SnapshotMatch(private val m: java.util.regex.MatchResult, private val input: CharSequence) : MatchResult {
    override val range: IntRange get() = m.start() until m.end()
    override val value: String get() = m.group()
    override val groupValues: List<String> by lazy { List(m.groupCount() + 1) { m.group(it) ?: "" } }
    override val groups: MatchGroupCollection = object : AbstractCollection<MatchGroup?>(), MatchGroupCollection {
        override val size: Int get() = m.groupCount() + 1
        override fun iterator(): Iterator<MatchGroup?> = (0 until size).map { get(it) }.iterator()
        override fun get(index: Int): MatchGroup? = m.group(index)?.let { MatchGroup(it, m.start(index) until m.end(index)) }
    }
    override val destructured: MatchResult.Destructured get() = throw UnsupportedOperationException("use groupValues")
    override fun next(): MatchResult? = throw UnsupportedOperationException("use findAllFast")
}

/** Every match of this regex in `input` (from `startIndex`), with one Matcher. */
fun Regex.findAllFast(input: CharSequence, startIndex: Int = 0): Sequence<MatchResult> = sequence {
    val matcher = toPattern().matcher(input)
    if (startIndex > 0) matcher.region(startIndex, input.length)
    while (matcher.find()) yield(SnapshotMatch(matcher.toMatchResult(), input))
}

/** replace(input) { ... } with one Matcher. */
fun Regex.replaceFast(input: CharSequence, transform: (MatchResult) -> CharSequence): String {
    val matcher = toPattern().matcher(input)
    if (!matcher.find()) return input.toString()
    val out = StringBuilder(input.length)
    var last = 0
    do {
        out.append(input, last, matcher.start())
        out.append(transform(SnapshotMatch(matcher.toMatchResult(), input)))
        last = matcher.end()
    } while (matcher.find())
    out.append(input, last, input.length)
    return out.toString()
}
