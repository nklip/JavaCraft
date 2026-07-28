import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResourceFilesTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void testOpensAnExplicitFile() throws IOException {
        Path file = Files.writeString(temporaryDirectory.resolve("explicit.txt"), "17");

        In in = ResourceFiles.open(ResourceFilesTest.class, file.toString());

        Assertions.assertEquals(17, in.readInt());
        in.close();
    }

    @Test
    void testOpensAFixtureByBareName() {
        In in = ResourceFiles.open(ResourceFilesTest.class, "resource-files.txt");

        Assertions.assertEquals(42, in.readInt());
        Assertions.assertEquals("shared", in.readString());
        in.close();
    }

    @Test
    void testOpensAModuleRelativeFixturePath() {
        In in = ResourceFiles.open(
                ResourceFilesTest.class,
                "src/test/resources/resource-files.txt"
        );

        Assertions.assertEquals(42, in.readInt());
        in.close();
    }

    @Test
    void testFindsTheAnchorModuleFixtureDirectory() {
        Path fixtures = ResourceFiles.fixtureDirectory(ResourceFilesTest.class);

        Assertions.assertNotNull(fixtures);
        Assertions.assertTrue(fixtures.isAbsolute());
        Assertions.assertTrue(Files.isReadable(fixtures.resolve("resource-files.txt")));
    }

    @Test
    void testFallsBackToAClasspathResourceWhenNoSourceTreeExists() {
        In in = ResourceFiles.open(
                Test.class,
                "/org/junit/jupiter/api/Test.class"
        );

        Assertions.assertFalse(in.isEmpty());
        in.close();
        Assertions.assertNull(ResourceFiles.fixtureDirectory(Test.class));
    }

    @Test
    void testMissingFileListsEveryLocationTried() {
        IllegalArgumentException thrown = Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> ResourceFiles.open(ResourceFilesTest.class, "definitely-not-here.txt")
        );

        Assertions.assertTrue(thrown.getMessage().contains("definitely-not-here.txt"));
        Assertions.assertTrue(thrown.getMessage().contains("src/test/resources"));
        Assertions.assertTrue(thrown.getMessage().contains("classpath:"));
    }

    /**
     * {@code resolve} exists for callers that have to hand a path or a {@code File} to something
     * else - {@code Picture} in the seam carving assignment - so it has to find a file in the same
     * places {@code open} does, and hand back somewhere absolute.
     */
    @Test
    void testResolvesAFixtureByBareName() {
        Path resolved = ResourceFiles.resolve(ResourceFilesTest.class, "resource-files.txt");

        Assertions.assertTrue(resolved.isAbsolute());
        Assertions.assertTrue(Files.isReadable(resolved));
        Assertions.assertEquals("resource-files.txt", resolved.getFileName().toString());
    }

    @Test
    void testResolvesAnExplicitFileAndAModuleRelativePath() throws IOException {
        Path file = Files.writeString(temporaryDirectory.resolve("explicit.txt"), "17");

        Assertions.assertEquals(
                file.toAbsolutePath().normalize(),
                ResourceFiles.resolve(ResourceFilesTest.class, file.toString()));
        Assertions.assertEquals(
                ResourceFiles.resolve(ResourceFilesTest.class, "resource-files.txt"),
                ResourceFiles.resolve(ResourceFilesTest.class, "src/test/resources/resource-files.txt"));
    }

    /** A classpath resource that is a real file on disk is resolvable; one inside a jar is not. */
    @Test
    void testResolvesAClasspathResourceOnlyWhenItIsAPlainFile() {
        Path resolved = ResourceFiles.resolve(ResourceFilesTest.class, "/resource-files.txt");

        Assertions.assertTrue(Files.isReadable(resolved));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> ResourceFiles.resolve(Test.class, "/org/junit/jupiter/api/Test.class"),
                "a jar entry has no path on disk"
        );
    }

    /**
     * A classpath name can point at a directory rather than a file - the classpath root itself is
     * one - and a directory is not something to hand to {@code Picture}, so it is rejected like
     * anything else that is not there.
     */
    @Test
    void testResolveRejectsAClasspathNameThatIsADirectory() {
        Assertions.assertNotNull(
                ResourceFilesTest.class.getResource("/"), "precondition: the classpath root resolves");

        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> ResourceFiles.resolve(ResourceFilesTest.class, "/")
        );
    }

    @Test
    void testResolveReportsEveryLocationTriedWhenNothingMatches() {
        IllegalArgumentException thrown = Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> ResourceFiles.resolve(ResourceFilesTest.class, "definitely-not-here.txt")
        );

        Assertions.assertTrue(thrown.getMessage().contains("definitely-not-here.txt"));
        Assertions.assertTrue(thrown.getMessage().contains("src/test/resources"));
        Assertions.assertTrue(thrown.getMessage().contains("classpath:"));
    }

    @Test
    void testRejectsInvalidArguments() {
        Assertions.assertThrows(
                NullPointerException.class,
                () -> ResourceFiles.resolve(null, "resource-files.txt")
        );
        Assertions.assertThrows(
                NullPointerException.class,
                () -> ResourceFiles.resolve(ResourceFilesTest.class, null)
        );
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> ResourceFiles.resolve(ResourceFilesTest.class, " ")
        );
        Assertions.assertThrows(
                NullPointerException.class,
                () -> ResourceFiles.open(null, "resource-files.txt")
        );
        Assertions.assertThrows(
                NullPointerException.class,
                () -> ResourceFiles.open(ResourceFilesTest.class, null)
        );
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> ResourceFiles.open(ResourceFilesTest.class, " ")
        );
        Assertions.assertThrows(
                NullPointerException.class,
                () -> ResourceFiles.fixtureDirectory(null)
        );
    }
}
