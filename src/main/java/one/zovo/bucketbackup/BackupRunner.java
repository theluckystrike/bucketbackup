package one.zovo.bucketbackup;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** The parts of a backup that do not touch the Minecraft server: zip, upload, retention, local copies. */
public final class BackupRunner {
    public record Report(String key, int files, long bytesIn, long bytesOut, String sha256, Duration zipTime,
                         Duration uploadTime, List<String> deleted, List<String> skipped) {
        public String summary() {
            return String.format("%s: %d files, %s zipped to %s in %ds, uploaded in %ds, %d old backup(s) removed",
                    key, files, human(bytesIn), human(bytesOut), zipTime.toSeconds(), uploadTime.toSeconds(),
                    deleted.size());
        }
    }

    private final Settings settings;
    private final Path serverDir;
    private final Path workDir;
    private final Consumer<String> log;

    public BackupRunner(Settings settings, Path serverDir, Path workDir, Consumer<String> log) {
        this.settings = settings;
        this.serverDir = serverDir;
        this.workDir = workDir;
        this.log = log;
    }

    /** Step 1, while world saving is paused: check free disk and write the zip. */
    public Archiver.Result archive(List<String> relPaths, Instant when) throws IOException {
        Files.createDirectories(workDir);
        long need = Archiver.sizeOf(serverDir, relPaths);
        long free = Files.getFileStore(workDir).getUsableSpace();
        // A zip is rarely bigger than its input. Keep 256 MB spare for the server itself.
        if (free - need < 256L * 1024 * 1024) {
            throw new IOException("Not enough free disk for the temporary zip: need about " + human(need)
                    + ", free " + human(free) + ". Nothing was changed.");
        }
        Path zip = workDir.resolve(Retention.STAMP.format(when) + ".zip.part");
        try {
            return Archiver.zip(serverDir, relPaths, settings.exclude(), settings.compressionLevel(), zip);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(zip);
            throw e;
        }
    }

    /** Step 2, with saving back on: upload, apply retention, keep or delete the local zip. */
    public Report upload(Archiver.Result zip, Instant when, Duration zipTime) throws IOException {
        S3Client s3 = new S3Client(settings);
        String key = Retention.keyFor(settings.prefix(), when);
        long t0 = System.nanoTime();
        long size = zip.bytesOut();
        long[] lastLog = {0};
        try {
            s3.upload(key, zip.zip(), settings.partSizeMb(), done -> {
                if (size > 64L * 1024 * 1024 && done - lastLog[0] >= size / 4) {
                    lastLog[0] = done;
                    log.accept("Uploaded " + (100 * done / size) + "% (" + human(done) + ")");
                }
            });
        } finally {
            if (settings.keepLocalCopies() == 0) {
                Files.deleteIfExists(zip.zip());
            }
        }
        Duration up = Duration.ofNanos(System.nanoTime() - t0);
        if (settings.keepLocalCopies() > 0) {
            Path local = workDir.resolve("local");
            Files.createDirectories(local);
            Files.move(zip.zip(), local.resolve(key.substring(key.lastIndexOf('/') + 1)),
                    StandardCopyOption.REPLACE_EXISTING);
            pruneLocal(local, settings.keepLocalCopies());
        }
        List<String> deleted = new ArrayList<>();
        List<S3Client.S3Object> all = s3.list(settings.prefix() + "/");
        boolean found = all.stream().anyMatch(o -> o.key().equals(key) && o.size() == size);
        if (!found) {
            throw new IOException("Upload finished but " + key + " is not in the bucket listing with size "
                    + size + ". Old backups were not removed.");
        }
        for (String k : Retention.toDelete(settings.prefix(), all, settings.keepLast(), settings.maxAgeDays(),
                Instant.now())) {
            s3.deleteObject(k);
            deleted.add(k);
        }
        return new Report(key, zip.files(), zip.bytesIn(), size, zip.sha256(), zipTime, up, deleted, zip.skipped());
    }

    static void pruneLocal(Path dir, int keep) throws IOException {
        List<Path> zips = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.zip")) {
            ds.forEach(zips::add);
        }
        zips.sort((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()));
        for (int i = keep; i < zips.size(); i++) {
            Files.deleteIfExists(zips.get(i));
        }
    }

    /** Removes .zip.part files left by a crash. */
    public void cleanStale() {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(workDir, "*.zip.part")) {
            for (Path p : ds) {
                Files.deleteIfExists(p);
                log.accept("Removed a temporary file from an earlier run: " + p.getFileName());
            }
        } catch (IOException ignored) {
            // The folder does not exist yet.
        }
    }

    public static String human(long b) {
        if (b < 1024) {
            return b + " B";
        }
        String[] u = {"KB", "MB", "GB", "TB"};
        double v = b;
        int i = -1;
        while (v >= 1024 && i < u.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format("%.1f %s", v, u[i]);
    }
}
