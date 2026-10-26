package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;

/** Private, exclusive backup bundles. A valid manifest is the final completion marker. */
final class BackupArtifacts {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final int MAX_MANIFEST_BYTES = 1024 * 1024;
    private static final Set<PosixFilePermission> DIRECTORY_MODE = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_MODE = PosixFilePermissions.fromString("rw-------");
    private final Path directory;
    private ObjectNode manifest;

    private BackupArtifacts(Path directory) {
        this.directory = directory;
    }

    static BackupArtifacts create(Path directory) throws IOException {
        requireAbsolute(directory);
        Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(DIRECTORY_MODE));
        requireDirectory(directory);
        return new BackupArtifacts(directory);
    }

    static BackupArtifacts open(Path directory) throws IOException {
        requireAbsolute(directory);
        requireDirectory(directory);
        BackupArtifacts bundle = new BackupArtifacts(directory);
        Path manifestFile = bundle.file("manifest.json");
        require(Files.size(manifestFile) <= MAX_MANIFEST_BYTES, "Backup manifest exceeds its size bound");
        JsonNode parsed;
        try (var input = Files.newInputStream(manifestFile, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_MANIFEST_BYTES + 1);
            require(bytes.length <= MAX_MANIFEST_BYTES, "Backup manifest exceeds its size bound");
            parsed = JSON.readTree(bytes);
        } catch (IOException failure) {
            throw new IOException("Backup manifest is invalid");
        }
        require(parsed instanceof ObjectNode && parsed.path("formatVersion").isIntegralNumber()
                && parsed.path("formatVersion").canConvertToInt() && parsed.path("formatVersion").intValue() == 1 && parsed.path("files").isArray(),
                "Backup manifest format is invalid");
        Set<String> expected = new HashSet<>(Set.of("manifest.json"));
        for (JsonNode entry : parsed.path("files")) {
            require(entry.isObject() && entry.path("name").isTextual()
                    && entry.path("size").isIntegralNumber() && entry.path("size").canConvertToLong()
                    && entry.path("size").longValue() >= 0 && entry.path("sha256").isTextual()
                    && entry.path("sha256").textValue().matches("[0-9a-f]{64}"), "Backup manifest file entry is invalid");
            String name = entry.path("name").textValue();
            require(expected.add(name), "Backup manifest contains duplicate or reserved files");
            Path file = bundle.file(name);
            require(Files.size(file) == entry.path("size").longValue()
                    && digest(file).equals(entry.path("sha256").textValue()), "Backup file integrity check failed");
        }
        require(expected.equals(bundle.inventory()), "Backup file inventory differs from its manifest");
        bundle.manifest = ((ObjectNode) parsed).deepCopy();
        return bundle;
    }

    Path directory() {
        return directory;
    }

    ObjectNode manifest() throws IOException {
        require(manifest != null, "Backup has no completed manifest");
        return manifest.deepCopy();
    }

    Path file(String leaf) throws IOException {
        Path path = resolve(leaf);
        requirePrivateFile(path);
        return path;
    }

    Path newFile(String leaf) throws IOException {
        require(manifest == null && !Files.exists(directory.resolve("manifest.json"), LinkOption.NOFOLLOW_LINKS),
                "Completed backup cannot be modified");
        require(!"manifest.json".equals(leaf), "Backup manifest is reserved");
        Path path = resolve(leaf);
        Files.createFile(path, PosixFilePermissions.asFileAttribute(FILE_MODE));
        return path;
    }

    void writeJson(String leaf, JsonNode value) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(value);
        Path path = newFile(leaf);
        Files.write(path, bytes, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    }

    void seal(ObjectNode metadata) throws IOException {
        require(manifest == null && !metadata.has("formatVersion") && !metadata.has("files"),
                "Backup metadata contains reserved fields or backup is completed");
        requireDirectory(directory);
        ObjectNode completed = metadata.deepCopy();
        completed.put("formatVersion", 1);
        var entries = completed.putArray("files");
        for (String name : inventory().stream().sorted().toList()) {
            require(!"manifest.json".equals(name), "Backup manifest already exists");
            Path file = file(name);
            entries.addObject().put("name", name).put("size", Files.size(file)).put("sha256", digest(file));
        }
        byte[] bytes = JSON.writeValueAsBytes(completed);
        require(bytes.length <= MAX_MANIFEST_BYTES, "Backup manifest exceeds its size bound");
        Path marker = resolve("manifest.json");
        try (var output = Files.newOutputStream(Files.createFile(marker,
                PosixFilePermissions.asFileAttribute(FILE_MODE)), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            output.write(bytes);
        }
        manifest = completed;
    }

    private Set<String> inventory() throws IOException {
        try (var paths = Files.list(directory)) {
            Set<String> names = new HashSet<>();
            for (Path path : paths.toList()) {
                requirePrivateFile(path);
                names.add(path.getFileName().toString());
            }
            return names;
        }
    }

    private Path resolve(String leaf) throws IOException {
        require(leaf != null && leaf.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,199}")
                && !leaf.equals(".") && !leaf.equals(".."), "Backup filename is invalid");
        requireDirectory(directory);
        return directory.resolve(leaf);
    }

    static void requirePrivateFile(Path path) throws IOException {
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                && Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).equals(FILE_MODE),
                "Backup file must be a private regular file");
    }

    private static void requireAbsolute(Path path) throws IOException {
        require(path != null && path.isAbsolute() && path.equals(path.normalize()),
                "Backup directory must be an absolute normalized path");
        for (Path ancestor = path; ancestor != null; ancestor = ancestor.getParent()) {
            require(!Files.isSymbolicLink(ancestor), "Backup path cannot contain symbolic links");
        }
    }

    private static void requireDirectory(Path path) throws IOException {
        require(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                && Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).equals(DIRECTORY_MODE),
                "Backup directory must be private");
    }

    private static String digest(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[64 * 1024];
                for (int count; (count = input.read(buffer)) != -1;) digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }
}
