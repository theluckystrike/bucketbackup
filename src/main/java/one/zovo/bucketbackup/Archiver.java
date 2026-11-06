package one.zovo.bucketbackup;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Zips folders and files from the server folder into one archive. */
public final class Archiver {
    public record Result(Path zip, int files, long bytesIn, long bytesOut, String sha256, List<String> skipped) { }

    /** Files that are never useful in a backup and are locked while the server runs. */
    static final List<String> ALWAYS_EXCLUDE = List.of("**/session.lock", "session.lock");

    private Archiver() { }

    /** Total size of the inputs, used for the free-disk check before zipping. */
    public static long sizeOf(Path base, List<String> relPaths) throws IOException {
        long[] total = {0};
        for (String rel : relPaths) {
            Path p = base.resolve(rel).normalize();
            if (!Files.exists(p)) {
                continue;
            }
            if (Files.isRegularFile(p)) {
                total[0] += Files.size(p);
                continue;
            }
            Files.walkFileTree(p, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                    total[0] += a.size();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path f, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return total[0];
    }

    public static Result zip(Path base, List<String> relPaths, List<String> excludeGlobs, int level, Path out)
            throws IOException {
        Path root = base.toAbsolutePath().normalize();
        List<PathMatcher> excludes = new ArrayList<>();
        for (String g : ALWAYS_EXCLUDE) {
            excludes.add(FileSystems.getDefault().getPathMatcher("glob:" + g));
        }
        for (String g : excludeGlobs) {
            excludes.add(FileSystems.getDefault().getPathMatcher("glob:" + g));
        }
        List<String> skipped = new ArrayList<>();
        int[] files = {0};
        long[] in = {0};
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        Files.createDirectories(out.toAbsolutePath().getParent());
        try (OutputStream fos = Files.newOutputStream(out);
             DigestOutputStream dos = new DigestOutputStream(fos, md);
             ZipOutputStream zos = new ZipOutputStream(new java.io.BufferedOutputStream(dos, 1 << 16))) {
            zos.setLevel(level);
            for (String rel : relPaths) {
                Path start = root.resolve(rel).normalize();
                if (!start.startsWith(root)) {
                    skipped.add(rel + " (outside the server folder)");
                    continue;
                }
                if (!Files.exists(start)) {
                    skipped.add(rel + " (not found)");
                    continue;
                }
                Files.walkFileTree(start, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) throws IOException {
                        Path r = root.relativize(dir);
                        if (matches(excludes, r)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        if (!r.toString().isEmpty()) {
                            ZipEntry e = new ZipEntry(unix(r) + "/");
                            e.setLastModifiedTime(a.lastModifiedTime());
                            zos.putNextEntry(e);
                            zos.closeEntry();
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                        Path r = root.relativize(f);
                        if (matches(excludes, r) || !a.isRegularFile() || f.equals(out.toAbsolutePath())) {
                            return FileVisitResult.CONTINUE;
                        }
                        ZipEntry e = new ZipEntry(unix(r));
                        e.setLastModifiedTime(a.lastModifiedTime());
                        try (InputStream is = Files.newInputStream(f)) {
                            zos.putNextEntry(e);
                            in[0] += is.transferTo(zos);
                            zos.closeEntry();
                            files[0]++;
                        } catch (IOException ex) {
                            skipped.add(unix(r) + " (" + ex.getClass().getSimpleName() + ")");
                            throw ex;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path f, IOException ex) {
                        skipped.add(unix(root.relativize(f)) + " (" + ex.getClass().getSimpleName() + ")");
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        }
        return new Result(out, files[0], in[0], Files.size(out), HexFormat.of().formatHex(md.digest()),
                List.copyOf(skipped));
    }

    private static boolean matches(List<PathMatcher> ms, Path rel) {
        for (PathMatcher m : ms) {
            if (m.matches(rel)) {
                return true;
            }
        }
        return false;
    }

    static String unix(Path p) {
        return p.toString().replace('\\', '/');
    }
}
