package one.zovo.bucketbackup;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldPathsTest {
    @TempDir
    Path root;

    @Test
    void paper26DimensionFoldersMapToTheLevelFolder() throws IOException {
        Path world = Files.createDirectories(root.resolve("world"));
        Files.writeString(world.resolve("level.dat"), "x");
        Path over = Files.createDirectories(world.resolve("dimensions/minecraft/overworld"));
        Path nether = Files.createDirectories(world.resolve("dimensions/minecraft/the_nether"));
        assertEquals(world, WorldPaths.levelRoot(over, root));
        assertEquals(List.of("world", "server.properties"), WorldPaths.relativeUnique(List.of(
                WorldPaths.levelRoot(over, root), WorldPaths.levelRoot(nether, root),
                root.resolve("server.properties")), root));
    }

    @Test
    void paper121WorldsKeepTheirOwnFolders() throws IOException {
        for (String w : List.of("world", "world_nether", "world_the_end")) {
            Files.createDirectories(root.resolve(w));
            Files.writeString(root.resolve(w + "/level.dat"), "x");
        }
        List<Path> picked = List.of(WorldPaths.levelRoot(root.resolve("world"), root),
                WorldPaths.levelRoot(root.resolve("world_nether"), root),
                WorldPaths.levelRoot(root.resolve("world_the_end"), root));
        assertEquals(List.of("world", "world_nether", "world_the_end"), WorldPaths.relativeUnique(picked, root));
    }

    @Test
    void folderWithoutLevelDatIsKept() throws IOException {
        Path f = Files.createDirectories(root.resolve("custom/dim"));
        assertEquals(f, WorldPaths.levelRoot(f, root));
    }
}
