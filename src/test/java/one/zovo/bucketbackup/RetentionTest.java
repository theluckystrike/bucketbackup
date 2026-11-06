package one.zovo.bucketbackup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RetentionTest {
    private static final String P = "minecraft/s1";
    private final Instant now = Instant.parse("2026-11-06T12:00:00Z");

    private List<S3Client.S3Object> daily(int n) {
        List<S3Client.S3Object> l = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            l.add(new S3Client.S3Object(Retention.keyFor(P, now.minus(Duration.ofDays(i))), 10, Instant.EPOCH));
        }
        return l;
    }

    @Test
    void keyNamesSortByTime() {
        assertEquals("minecraft/s1/2026-11-06T12-00-00Z.zip", Retention.keyFor(P, now));
        assertTrue(Retention.isOurs(P, "minecraft/s1/2026-11-06T12-00-00Z.zip"));
        assertFalse(Retention.isOurs(P, "minecraft/s1/notes.zip"));
        assertFalse(Retention.isOurs(P, "minecraft/s1/sub/2026-11-06T12-00-00Z.zip"));
        assertFalse(Retention.isOurs(P, "minecraft/s10/2026-11-06T12-00-00Z.zip"));
        assertFalse(Retention.isOurs(P, "other/2026-11-06T12-00-00Z.zip"));
    }

    @Test
    void keepLastDeletesOldest() {
        List<String> del = Retention.toDelete(P, daily(5), 3, 0, now);
        assertEquals(List.of(Retention.keyFor(P, now.minus(Duration.ofDays(3))),
                Retention.keyFor(P, now.minus(Duration.ofDays(4)))), del);
    }

    @Test
    void maxAgeDeletesOldButNeverNewest() {
        assertEquals(3, Retention.toDelete(P, daily(5), 100, 2, now).size());
        List<S3Client.S3Object> one = List.of(new S3Client.S3Object(
                Retention.keyFor(P, now.minus(Duration.ofDays(400))), 1, Instant.EPOCH));
        assertTrue(Retention.toDelete(P, one, 1, 1, now).isEmpty());
    }

    @Test
    void foreignFilesAreNeverDeleted() {
        List<S3Client.S3Object> l = new ArrayList<>(daily(3));
        l.add(new S3Client.S3Object(P + "/important.zip", 1, Instant.EPOCH));
        l.add(new S3Client.S3Object(P + "/manual/2020-01-01T00-00-00Z.zip", 1, Instant.EPOCH));
        List<String> del = Retention.toDelete(P, l, 1, 0, now);
        assertEquals(2, del.size());
        assertTrue(del.stream().allMatch(k -> Retention.isOurs(P, k)));
    }
}
