package dev.nklip.javacraft.elastic.data.cucumber.conf;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

/**
 * Implements fallback for missing 'tc.image.elasticsearch' property.
 *
 * Usually
 */
@SuppressWarnings("SameParameterValue")
final class MavenPomPropertyResolver {

    private MavenPomPropertyResolver() {
    }

    static String resolveRequiredSystemOrPomProperty(String propertyName) {
        String systemProperty = System.getProperty(propertyName);
        if (systemProperty != null && !systemProperty.isBlank()) {
            return systemProperty;
        }

        Path startDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        return findPropertyInAncestorPoms(startDirectory, propertyName)
                .orElseThrow(() -> new IllegalStateException(
                        "Property '%s' is not set as a JVM system property and was not found in an ancestor pom.xml."
                                .formatted(propertyName)
                ));
    }

    static Optional<String> findPropertyInAncestorPoms(Path startDirectory, String propertyName) {
        Objects.requireNonNull(startDirectory, "startDirectory");
        Objects.requireNonNull(propertyName, "propertyName");

        Path currentDirectory = Files.isDirectory(startDirectory)
                ? startDirectory.toAbsolutePath()
                : Optional.ofNullable(startDirectory.toAbsolutePath().getParent())
                .orElse(startDirectory.toAbsolutePath());

        while (currentDirectory != null) {
            Path pomFile = currentDirectory.resolve("pom.xml");
            if (Files.isRegularFile(pomFile)) {
                Optional<String> propertyValue = readPropertyFromPom(pomFile, propertyName);
                if (propertyValue.isPresent()) {
                    return propertyValue;
                }
            }
            currentDirectory = currentDirectory.getParent();
        }

        return Optional.empty();
    }

    private static Optional<String> readPropertyFromPom(Path pomFile, String propertyName) {
        try (Reader reader = Files.newBufferedReader(pomFile)) {
            Model model = new MavenXpp3Reader().read(reader);
            return Optional.ofNullable(model.getProperties().getProperty(propertyName))
                    .map(String::trim)
                    .filter(propertyValue -> !propertyValue.isBlank());
        } catch (IOException | XmlPullParserException e) {
            throw new IllegalStateException("Failed to read Maven property '%s' from %s"
                    .formatted(propertyName, pomFile), e);
        }
    }
}
