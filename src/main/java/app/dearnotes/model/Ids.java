package app.dearnotes.model;

import java.security.SecureRandom;
import java.util.UUID;

/** UUID v7 (RFC 9562)。先頭 48bit がミリ秒なので、作った順に並ぶ。 */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static long lastMillis = -1;
    private static long counter;

    private Ids() {}

    public static synchronized String uuidV7() {
        long now = System.currentTimeMillis();
        // 同じミリ秒の中でも順序を保つため、rand_a を 12bit のカウンタにする
        if (now <= lastMillis) {
            counter++;
            if (counter > 0xFFF) {
                lastMillis++;
                counter = 0;
            }
            now = lastMillis;
        } else {
            lastMillis = now;
            counter = RANDOM.nextInt(0x800);
        }
        long msb = (now << 16) | (0x7L << 12) | counter;
        long lsb = (RANDOM.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
        return new UUID(msb, lsb).toString();
    }
}
