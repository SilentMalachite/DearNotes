package app.dearnotes.model

import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** UUID v7 (RFC 9562)。先頭 48bit がミリ秒なので、作った順に並ぶ。 */
@OptIn(ExperimentalAtomicApi::class, ExperimentalUuidApi::class)
object Ids {
    /**
     * 前回の (ミリ秒 shl 12) or カウンタ。同じミリ秒の中でも順序を保つため、rand_a を 12bit のカウンタにする。
     * +1 するとカウンタが 0xFFF を超えたときにミリ秒へ繰り上がるので、並びは崩れない。
     */
    private val last = AtomicLong(0L)

    fun uuidV7(): String {
        // 乱数は暗号論的な乱数から取る（Uuid.random は安全な乱数を使う）
        val (seed, randomLow) = Uuid.random().toLongs { msb, lsb -> (msb and 0x7FF) to lsb }
        val now = Clock.System.now().toEpochMilliseconds()
        var next: Long
        while (true) {
            val prev = last.load()
            next = if (now > prev ushr 12) (now shl 12) or seed else prev + 1
            if (last.compareAndSet(prev, next)) break
        }
        val msb = ((next ushr 12) shl 16) or (0x7L shl 12) or (next and 0xFFF)
        val lsb = (randomLow and 0x3FFF_FFFF_FFFF_FFFFL) or Long.MIN_VALUE
        return Uuid.fromLongs(msb, lsb).toString()
    }
}
