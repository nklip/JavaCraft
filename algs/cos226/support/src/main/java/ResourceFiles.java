import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Locates COS226 data files independently of the process working directory.
 *
 * <p>Assignment programs keep their sample data in each consumer module's
 * {@code src/test/resources}. The anchor identifies that consumer module; using this support
 * class as the anchor would incorrectly search relative to the support artifact itself.
 */
public final class ResourceFiles {

    private static final Path FIXTURES = Path.of("src", "test", "resources");

    /** target/classes and target/test-classes are two levels below a module root. */
    private static final int MAX_LEVELS_UP = 6;

    private ResourceFiles() {
    }

    /**
     * Opens a file supplied as an explicit path, a consumer-module fixture name, or a classpath
     * resource.
     *
     * @param anchor class loaded from the module whose resources should be searched
     * @param filename file name or path to open
     * @return an open {@link In} reader
     * @throws IllegalArgumentException if the file cannot be found
     */
    public static In open(Class<?> anchor, String filename) {
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(filename, "filename");
        if (filename.isBlank()) {
            throw new IllegalArgumentException("filename must not be blank");
        }

        List<String> tried = new ArrayList<>();
        Path asGiven = Path.of(filename).normalize();
        tried.add(asGiven.toAbsolutePath().normalize().toString());
        if (isReadableFile(asGiven)) {
            return new In(asGiven.toFile());
        }

        Path fixtures = fixtureDirectory(anchor);
        if (fixtures != null && !asGiven.isAbsolute()) {
            Path fixtureRelativePath = asGiven.startsWith(FIXTURES)
                    ? FIXTURES.relativize(asGiven)
                    : asGiven;
            Path candidate = fixtures.resolve(fixtureRelativePath).normalize();
            tried.add(candidate.toString());
            if (isReadableFile(candidate)) {
                return new In(candidate.toFile());
            }
        }

        String resourceName = filename.startsWith("/") ? filename : "/" + filename;
        tried.add("classpath:" + resourceName);
        URL resource = anchor.getResource(resourceName);
        if (resource != null) {
            return new In(resource);
        }

        throw new IllegalArgumentException(
                "Could not find resource file '" + filename + "'. Tried: "
                        + String.join(", ", tried)
        );
    }

    /**
     * Finds the anchor module's {@code src/test/resources} directory.
     *
     * @param anchor class loaded from the consumer module
     * @return the absolute fixture directory, or {@code null} when the anchor is loaded from a
     *         layout without a source tree
     */
    public static Path fixtureDirectory(Class<?> anchor) {
        Objects.requireNonNull(anchor, "anchor");
        try {
            Path location = Path.of(
                    anchor.getProtectionDomain().getCodeSource().getLocation().toURI()
            );
            if (Files.isRegularFile(location)) {
                location = location.getParent();
            }

            for (int level = 0; location != null && level < MAX_LEVELS_UP; level++) {
                Path candidate = location.resolve(FIXTURES);
                if (Files.isDirectory(candidate)) {
                    return candidate.toAbsolutePath().normalize();
                }
                location = location.getParent();
            }
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
        return null;
    }

    private static boolean isReadableFile(Path path) {
        return Files.isRegularFile(path) && Files.isReadable(path);
    }
}
