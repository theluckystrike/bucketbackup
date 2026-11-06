package one.zovo.bucketbackup;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArchiverTest {
    @TempDir
    Path dir;

    private Map<String, byte[]> read(Path zip) throws IOException {
        Map<String, byte[]> m = new HashMap<>();
        try (ZipInputStream z = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                m.put(e.getName(), z.readAllBytes());
            }
        }
        return m;
    }

    @Test
    void zipsWorldsAndSkipsLocksAndExcludes() throws IOException {
        Path w = Files.createDirectories(dir.resolve("world/region"));
        Files.write(w.resolve("r.0.0.mca"), new byte[]{1, 2, 3});
        Files.writeString(dir.resolve("world/level.dat"), "lvl");
        Files.writeString(dir.resolve("world/session.lock"), "lock");
        Files.writeString(dir.resolve("world/cache.tmp"), "tmp");
        Files.writeString(dir.resolve("server.properties"), "motd=hi");
        Path out = dir.resolve("work/out.zip");
        Archiver.Result r = Archiver.zip(dir, List.of("world", "server.properties", "missing.json"),
                List.of("**/*.tmp"), 6, out);
        Map<String, byte[]> m = read(out);
        assertArrayEquals(new byte[]{1, 2, 3}, m.get("world/region/r.0.0.mca"));
        assertEquals("lvl", new String(m.get("world/level.dat")));
        assertEquals("motd=hi", new String(m.get("server.properties")));
        assertTrue(!m.containsKey("world/session.lock"));
        assertTrue(!m.containsKey("world/cache.tmp"));
        assertEquals(3, r.files());
        assertEquals(List.of("missing.json (not found)"), r.skipped());
        assertEquals(SigV4.sha256Hex(Files.readAllBytes(out)), r.sha256());
        assertEquals(13, Archiver.sizeOf(dir, List.of("world")));
    }

    @Test
    void refusesPathsOutsideServer() throws IOException {
        Path out = dir.resolve("o.zip");
        Archiver.Result r = Archiver.zip(dir, List.of("../etc"), List.of(), 1, out);
        assertEquals(0, r.files());
        assertTrue(r.skipped().get(0).contains("outside"));
    }

    @Test
    void humanSizes() {
        assertEquals("512 B", BackupRunner.human(512));
        assertEquals("1.5 KB", BackupRunner.human(1536));
        assertEquals("2.0 GB", BackupRunner.human(2L << 30));
    }
}
