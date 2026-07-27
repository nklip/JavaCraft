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

    @Test
    void testRejectsInvalidArguments() {
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
