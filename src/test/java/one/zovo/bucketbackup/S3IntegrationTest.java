package one.zovo.bucketbackup;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs against a real S3 server that checks SigV4 signatures (scripts/test-s3.sh starts rclone serve s3).
 * Skipped when BB_IT_ENDPOINT is not set.
 */
class S3IntegrationTest {
    @TempDir
    Path dir;
    private Settings settings;

    @BeforeEach
    void setUp() {
        String ep = System.getenv("BB_IT_ENDPOINT");
        Assumptions.assumeTrue(ep != null && !ep.isEmpty(), "BB_IT_ENDPOINT not set");
        YamlConfiguration c = new YamlConfiguration();
        c.set("storage.endpoint", ep);
        c.set("storage.region", "us-east-1");
        c.set("storage.bucket", System.getenv().getOrDefault("BB_IT_BUCKET", "bb-it"));
        c.set("storage.access-key", System.getenv("BB_IT_ACCESS"));
        c.set("storage.secret-key", System.getenv("BB_IT_SECRET"));
        c.set("backup.prefix", "it/" + System.nanoTime());
        c.set("retention.keep-last", 2);
        c.set("upload.part-size-mb", 5);
        settings = Settings.from(c, Map.of());
        assertTrue(settings.valid(), settings.errors().toString());
    }

    @Test
    void multipartUploadRoundTrip() throws Exception {
        S3Client s3 = new S3Client(settings);
        byte[] data = new byte[12 * 1024 * 1024 + 123];
        new Random(7).nextBytes(data);
        Path f = Files.write(dir.resolve("big.bin"), data);
        String key = settings.prefix() + "/big file+1.bin";
        long[] seen = {0};
        s3.upload(key, f, 5, d -> seen[0] = d);
        assertEquals(data.length, seen[0]);
        assertArrayEquals(data, s3.getObject(key));
        s3.deleteObject(key);
        assertTrue(s3.list(settings.prefix() + "/").isEmpty());
    }

    @Test
    void backupsAndRetention() throws Exception {
        Path server = Files.createDirectories(dir.resolve("server"));
        Files.createDirectories(server.resolve("world/region"));
        Files.writeString(server.resolve("world/level.dat"), "level");
        Files.write(server.resolve("world/region/r.0.0.mca"), new byte[100_000]);
        BackupRunner r = new BackupRunner(settings, server, dir.resolve("work"), m -> { });
        Instant t = Instant.parse("2026-11-01T00:00:00Z");
        for (int i = 0; i < 3; i++) {
            Instant when = t.plus(Duration.ofHours(i));
            BackupRunner.Report rep = r.upload(r.archive(List.of("world"), when), when, Duration.ZERO);
            assertEquals(Retention.keyFor(settings.prefix(), when), rep.key());
            assertEquals(i == 2 ? 1 : 0, rep.deleted().size());
        }
        S3Client s3 = new S3Client(settings);
        List<S3Client.S3Object> left = Retention.backups(settings.prefix(), s3.list(settings.prefix() + "/"));
        assertEquals(2, left.size());
        assertTrue(Files.list(dir.resolve("work")).noneMatch(p -> p.toString().endsWith(".part")));
        for (S3Client.S3Object o : left) {
            s3.deleteObject(o.key());
        }
    }

    @Test
    void wrongSecretIsRejected() {
        S3Client bad = new S3Client(settings.endpoint(), settings.region(), settings.bucket(), settings.accessKey(),
                "wrong-secret", true);
        S3Client.S3Exception e = assertThrows(S3Client.S3Exception.class, () -> bad.putObject("x/y", new byte[]{1}));
        assertEquals(403, e.status());
    }
}
