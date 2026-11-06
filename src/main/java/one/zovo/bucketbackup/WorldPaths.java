package one.zovo.bucketbackup;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Turns world folders into the folders to back up. */
public final class WorldPaths {
    private WorldPaths() { }

    /**
     * Paper 26.x reports a dimension folder (world/dimensions/minecraft/overworld) as the world folder, while
     * level.dat, players and data stay in world/. This walks up to the folder that holds level.dat, so the
     * whole save is backed up. On 1.21.x the world folder already holds level.dat.
     */
    public static Path levelRoot(Path worldFolder, Path serverRoot) {
        Path start = worldFolder.toAbsolutePath().normalize();
        Path root = serverRoot.toAbsolutePath().normalize();
        for (Path p = start; p != null && p.startsWith(root) && !p.equals(root); p = p.getParent()) {
            if (Files.isRegularFile(p.resolve("level.dat"))) {
                return p;
            }
        }
        return start;
    }

    /** Relative unix paths, without duplicates and without paths inside another picked path. */
    public static List<String> relativeUnique(List<Path> picked, Path serverRoot) {
        Path root = serverRoot.toAbsolutePath().normalize();
        List<Path> abs = picked.stream().map(p -> p.toAbsolutePath().normalize()).toList();
        List<String> out = new ArrayList<>();
        for (Path p : abs) {
            boolean nested = abs.stream().anyMatch(o -> !o.equals(p) && p.startsWith(o));
            String rel = Archiver.unix(root.relativize(p));
            if (!nested && !out.contains(rel)) {
                out.add(rel);
            }
        }
        return out;
    }
}
