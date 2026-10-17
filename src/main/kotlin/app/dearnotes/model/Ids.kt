package app.dearnotes.model

import java.security.SecureRandom
import java.util.UUID

/** UUID v7 (RFC 9562)。先頭 48bit がミリ秒なので、作った順に並ぶ。 */
object Ids {
    private val random = SecureRandom()
    private var lastMillis = -1L
    private var counter = 0L

    @Synchronized
    fun uuidV7(): String {
        var now = System.currentTimeMillis()
        // 同じミリ秒の中でも順序を保つため、rand_a を 12bit のカウンタにする
        if (now <= lastMillis) {
            counter++
            if (counter > 0xFFF) {
                lastMillis++
                counter = 0
            }
            now = lastMillis
        } else {
            lastMillis = now
            counter = random.nextInt(0x800).toLong()
        }
        val msb = (now shl 16) or (0x7L shl 12) or counter
        val lsb = (random.nextLong() and 0x3FFF_FFFF_FFFF_FFFFL) or Long.MIN_VALUE
        return UUID(msb, lsb).toString()
    }
}
