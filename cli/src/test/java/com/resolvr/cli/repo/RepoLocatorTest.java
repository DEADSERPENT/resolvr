package com.resolvr.cli.repo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RepoLocatorTest {

    @TempDir
    Path tempDir;

    private Path makeServerRoot(Path dir) throws Exception {
        Files.createDirectories(dir.resolve("src").resolve("main").resolve("resources"));
        Files.writeString(dir.resolve("pom.xml"), "<project/>");
        Files.writeString(dir.resolve("src").resolve("main").resolve("resources").resolve("application.properties"), "");
        return dir;
    }

    @Test
    void systemProperty_pointsAtValidRoot_isUsedDirectly() throws Exception {
        Path root = makeServerRoot(tempDir.resolve("repo"));
        Path located = RepoLocator.locate(root.toString(), tempDir);
        assertEquals(root.toAbsolutePath().normalize(), located);
    }

    @Test
    void systemProperty_pointsAtInvalidRoot_throws() {
        Path notARepo = tempDir.resolve("not-a-repo");
        RepoLocator.RepoNotFoundException ex = assertThrows(RepoLocator.RepoNotFoundException.class,
                () -> RepoLocator.locate(notARepo.toString(), tempDir));
        assertTrue(ex.getMessage().contains("resolvr.repo.root"));
    }

    @Test
    void noProperty_walksUpFromStartDirToFindRoot() throws Exception {
        Path root = makeServerRoot(tempDir.resolve("repo"));
        Path deepSubdir = root.resolve("src").resolve("main").resolve("java").resolve("com").resolve("resolvr");
        Files.createDirectories(deepSubdir);

        Path located = RepoLocator.locate(null, deepSubdir);
        assertEquals(root.toAbsolutePath().normalize(), located);
    }

    @Test
    void noProperty_blankProperty_treatedSameAsAbsent() throws Exception {
        Path root = makeServerRoot(tempDir.resolve("repo"));
        Path located = RepoLocator.locate("   ", root);
        assertEquals(root.toAbsolutePath().normalize(), located);
    }

    @Test
    void notInARepo_throwsClearException() {
        Path outside = tempDir.resolve("nowhere");
        assertThrows(RepoLocator.RepoNotFoundException.class,
                () -> RepoLocator.locate(null, outside));
    }

    @Test
    void tryLocate_neverThrows_regardlessOfWhetherARepoIsFound() {
        System.clearProperty(RepoLocator.REPO_ROOT_PROPERTY);
        // tryLocate() uses the real CWD via locate() — this only asserts the non-throwing
        // contract holds against whatever the actual test-runner CWD happens to be; the
        // explicit-startDir tests above cover both the "found" and "not found" outcomes.
        assertDoesNotThrow(RepoLocator::tryLocate);
    }
}
