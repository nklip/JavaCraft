package dev.nklip.javacraft.shardshop.common.contract;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.json.schema.Draft;
import io.vertx.json.schema.JsonSchema;
import io.vertx.json.schema.JsonSchemaOptions;
import io.vertx.json.schema.OutputFormat;
import io.vertx.json.schema.OutputUnit;
import io.vertx.json.schema.SchemaRepository;
import io.vertx.json.schema.Validator;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * One self-contained contract of its owning module: an OpenAPI 3.1 document or a JSON Schema 2020-12 message schema.
 * It checks the document itself and validates values against its schemas with Vert.x JSON Schema (draft 2020-12),
 * for the contract tests of the owning module and for the cross-module scenarios.
 *
 * <p>Vert.x 4.5.34 needs four corrections, which this class makes:
 * <ul>
 *     <li>Its {@code date-time} format check is a loose pattern that accepts 2026-02-30 and hour 25. A strict
 *     ISO-8601 calendar parse is added.</li>
 *     <li>It compares the remainder of a {@link BigDecimal} with its scale, thus {@code 1E+3} is "not an integer".
 *     Integral numbers are given to it as integers.</li>
 *     <li>Its {@code email} format check reads the first character, thus an empty string throws. That value is
 *     reported as invalid.</li>
 *     <li>{@code OpenAPIContract} rejects the relative server URL {@code /}, and the OpenAPI 3.1 schema accepts any
 *     schema object. The document is validated against the bundled OpenAPI 3.1 schema, and each schema object
 *     against the bundled JSON Schema 2020-12 meta-schema.</li>
 * </ul>
 * Vert.x evaluates {@code pattern} with JDK regular expressions, in which {@code \s} is ASCII whitespace only.
 */
public final class ContractDocument {
    private static final String BASE = "https://shardshop.test/contracts/";
    private static final String OPENAPI_SCHEMA = "https://spec.openapis.org/oas/3.1/schema/2022-10-07";
    private static final String META_SCHEMA = "https://json-schema.org/draft/2020-12/schema";
    private static final List<String> BUNDLED_SCHEMAS = List.of(OPENAPI_SCHEMA, META_SCHEMA,
            "https://json-schema.org/draft/2020-12/meta/core",
            "https://json-schema.org/draft/2020-12/meta/applicator",
            "https://json-schema.org/draft/2020-12/meta/unevaluated",
            "https://json-schema.org/draft/2020-12/meta/validation",
            "https://json-schema.org/draft/2020-12/meta/meta-data",
            "https://json-schema.org/draft/2020-12/meta/format-annotation",
            "https://json-schema.org/draft/2020-12/meta/content");
    private static final Set<String> HTTP_METHODS = Set.of("get", "put", "post", "delete", "options", "head", "patch",
            "trace");
    private static final Pattern TEMPLATE = Pattern.compile("\\{([^}/]+)}");
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final String uri;
    private final JsonObject document;
    private final boolean openApi;
    private final SchemaRepository repository;
    private final Map<String, Validator> validators = new ConcurrentHashMap<>();

    /** An inline example: where it is, the JSON pointer of its schema and its value. */
    public record Example(String location, String schema, Object value) {
    }

    private ContractDocument(String name, JsonObject document, boolean openApi) {
        this.document = document;
        this.openApi = openApi;
        repository = SchemaRepository.create(new JsonSchemaOptions().setDraft(Draft.DRAFT202012).setBaseUri(BASE)
                .setOutputFormat(OutputFormat.Basic), ContractDocument::strictDateTime);
        for (String bundled : BUNDLED_SCHEMAS) {
            repository.dereference(bundled, JsonSchema.of(bundledSchema(bundled)));
        }
        // Vert.x marks the objects that it dereferences, thus it gets a copy of the document.
        if (openApi) {
            uri = BASE + name;
            repository.dereference(uri, JsonSchema.of(document.copy()));
        } else {
            uri = document.getString("$id");
            repository.dereference(JsonSchema.of(document.copy()));
        }
    }

    /** An OpenAPI 3.1 document in YAML; duplicate mapping keys are invalid. */
    public static ContractDocument openApi(Path file) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        try {
            Object yaml = new Yaml(new SafeConstructor(options)).load(Files.readString(file));
            return new ContractDocument(file.getFileName().toString(), (JsonObject) vertx(yaml), true);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** A JSON Schema 2020-12 message schema with an {@code $id}; duplicate member names are invalid. */
    public static ContractDocument messageSchema(Path file) {
        return new ContractDocument(file.getFileName().toString(), (JsonObject) read(file), false);
    }

    /** A JSON file with exact numbers; duplicate member names and trailing data are invalid. */
    public static Object read(Path file) {
        try {
            return vertx(JSON.readTree(file.toFile()));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** JSON text with exact numbers, for example {@code "2.0"}; duplicate member names are invalid. */
    public static Object json(String text) {
        try {
            return vertx(JSON.readTree(text));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /**
     * The Vert.x JSON value of a JSON value: a Jackson node, a map, a list, text, a number, a boolean or null.
     * Integral numbers become integers, because Vert.x compares {@link BigDecimal} remainders with their scale.
     */
    public static Object vertx(Object value) {
        return switch (value) {
            case null -> null;
            case JsonNode node -> vertx(JSON.convertValue(node, Object.class));
            case JsonObject object -> vertx(object.getMap());
            case JsonArray array -> vertx(array.getList());
            case Map<?, ?> map -> {
                JsonObject object = new JsonObject();
                map.forEach((key, member) -> object.put((String) key, vertx(member)));
                yield object;
            }
            case List<?> list -> {
                List<Object> items = new ArrayList<>();
                list.forEach(item -> items.add(vertx(item)));
                yield new JsonArray(items);
            }
            case BigDecimal decimal when decimal.signum() == 0 || decimal.stripTrailingZeros().scale() <= 0 ->
                    decimal.toBigIntegerExact();
            case String _, Number _, Boolean _ -> value;
            default -> throw new IllegalArgumentException("Not a JSON value: " + value.getClass().getName());
        };
    }

    public JsonObject document() {
        return document;
    }

    /** The node at a JSON pointer such as {@code #/components/schemas/Id}, without following references. */
    public Object node(String pointer) {
        Object node = document;
        for (String token : tokens(pointer)) {
            node = switch (node) {
                case JsonObject object when object.containsKey(token) -> object.getValue(token);
                case JsonArray array when token.matches("0|[1-9][0-9]*") && Integer.parseInt(token) < array.size() ->
                        array.getValue(Integer.parseInt(token));
                default -> throw new IllegalArgumentException("No node at " + pointer);
            };
        }
        return node;
    }

    /** The JSON pointer that a {@code $ref} object refers to, or the pointer itself for another node. */
    public String target(String pointer) {
        String current = pointer;
        for (Set<String> seen = new LinkedHashSet<>(); node(current) instanceof JsonObject object
                && object.getValue("$ref") instanceof String reference; ) {
            if (!seen.add(current) || !reference.startsWith("#/")) {
                throw new IllegalArgumentException("Unresolvable reference at " + current + ": " + reference);
            }
            current = reference;
        }
        return current;
    }

    /** The messages of all violations of the value against the schema at a JSON pointer; empty if it is valid. */
    public List<String> errors(String pointer, Object value) {
        Validator validator = validators.computeIfAbsent(pointer, key -> repository.validator(JsonSchema.of(
                new JsonObject().put("$ref", "#".equals(key) ? uri : uri + escapeFragment(key)))));
        OutputUnit result;
        try {
            result = validator.validate(vertx(value));
        } catch (StringIndexOutOfBoundsException failure) {
            // Vert.x 4.5.34 reads the first character of an email address; only an empty string throws here.
            return List.of("An empty string does not match format \"email\"");
        }
        if (Boolean.TRUE.equals(result.getValid())) {
            return List.of();
        }
        List<OutputUnit> units = result.getErrors() == null ? List.of(result) : result.getErrors();
        return units.stream().map(unit -> unit.getInstanceLocation() + " " + unit.getKeywordLocation() + ": "
                + unit.getError()).toList();
    }

    public boolean isValid(String pointer, Object value) {
        return errors(pointer, value).isEmpty();
    }

    /** The values of a boundary matrix whose result differs from the expectation; empty if all results match. */
    public List<String> unexpected(String pointer, List<?> valid, List<?> invalid) {
        List<String> unexpected = new ArrayList<>();
        for (Object value : valid) {
            List<String> errors = errors(pointer, value);
            if (!errors.isEmpty()) {
                unexpected.add(pointer + " rejected " + describe(value) + ": " + errors);
            }
        }
        for (Object value : invalid) {
            if (isValid(pointer, value)) {
                unexpected.add(pointer + " accepted " + describe(value));
            }
        }
        return unexpected;
    }

    /** The variants of a valid object for a closed schema: each forbidden member added, each member absent or null. */
    public static List<Object> invalidVariants(JsonObject valid, JsonObject forbidden) {
        List<Object> variants = new ArrayList<>();
        forbidden.forEach(member -> variants.add(valid.copy().put(member.getKey(), member.getValue())));
        valid.fieldNames().forEach(name -> variants.add(without(valid, name)));
        valid.fieldNames().forEach(name -> variants.add(valid.copy().putNull(name)));
        return variants;
    }

    /** A copy of an object without one member. */
    public static JsonObject without(JsonObject value, String name) {
        JsonObject copy = value.copy();
        copy.remove(name);
        return copy;
    }

    /** A readable form of a value: text with escapes for control and non-ASCII characters, long text shortened. */
    public static String describe(Object value) {
        return switch (vertx(value)) {
            case null -> "null";
            case String text -> {
                StringBuilder escaped = new StringBuilder("\"");
                text.chars().limit(40).forEach(character -> escaped.append(character >= 0x20 && character < 0x7f
                        ? String.valueOf((char) character) : String.format("\\u%04x", character)));
                yield escaped.append(text.length() > 40 ? "...\" (" + text.length() + " chars)" : "\"").toString();
            }
            case JsonObject object -> object.fieldNames().stream().map(name -> describe(name) + ":"
                    + describe(object.getValue(name))).collect(Collectors.joining(",", "{", "}"));
            case JsonArray array -> array.stream().map(ContractDocument::describe)
                    .collect(Collectors.joining(",", "[", "]"));
            case Object other -> other.getClass().getSimpleName() + " " + other;
        };
    }

    /**
     * The violations of the document itself: the OpenAPI 3.1 schema or the JSON Schema 2020-12 meta-schema, the
     * meta-schema for each OpenAPI schema object, references outside the document or to absent nodes, path
     * parameters that differ from their path template, and repeated operation IDs.
     */
    public List<String> structureErrors() {
        List<String> errors = new ArrayList<>();
        BiConsumer<String, List<String>> add = (location, found) -> found.forEach(error -> errors.add(location
                + ": " + error));
        add.accept("#", schemaErrors(openApi ? OPENAPI_SCHEMA : META_SCHEMA, document));
        walk(document, "#", (pointer, object) -> {
            if (object.getValue("$ref") instanceof String reference) {
                try {
                    target(pointer);
                } catch (IllegalArgumentException failure) {
                    errors.add(pointer + ": " + reference + " is not a resolvable in-document reference");
                }
            }
        });
        if (openApi) {
            for (String schema : schemaObjects()) {
                add.accept(schema, schemaErrors(META_SCHEMA, node(schema)));
            }
            Set<String> operationIds = new TreeSet<>();
            for (String operation : operations()) {
                String id = ((JsonObject) node(operation)).getString("operationId");
                if (id != null && !operationIds.add(id)) {
                    errors.add(operation + ": operationId " + id + " is repeated");
                }
                Set<String> declared = pathParameters(operation);
                Set<String> template = templateNames(tokens(operation).get(1));
                if (!declared.equals(template)) {
                    errors.add(operation + ": path parameters " + declared + " differ from the template " + template);
                }
            }
        }
        return errors;
    }

    /** All inline examples with the JSON pointer of the schema that they illustrate. */
    public List<Example> examples() {
        List<Example> examples = new ArrayList<>();
        walk(document, "#", (pointer, object) -> {
            if (!object.containsKey("example") && !object.containsKey("examples")) {
                return;
            }
            String schema = object.containsKey("schema") ? pointer + "/schema" : pointer;
            if (object.containsKey("example")) {
                examples.add(new Example(pointer + "/example", schema, object.getValue("example")));
            }
            switch (object.getValue("examples")) {
                case JsonArray values -> {
                    for (int index = 0; index < values.size(); index++) {
                        examples.add(new Example(pointer + "/examples/" + index, schema, values.getValue(index)));
                    }
                }
                case JsonObject named -> named.fieldNames().stream().sorted().forEach(name -> {
                    String location = target(pointer + "/examples/" + escape(name));
                    JsonObject example = (JsonObject) node(location);
                    if (example.containsKey("externalValue") || !example.containsKey("value")) {
                        throw new IllegalStateException(location + " is not a self-contained example");
                    }
                    examples.add(new Example(location + "/value", schema, example.getValue("value")));
                });
                case null -> {
                }
                default -> throw new IllegalStateException(pointer + "/examples is not an array or a map");
            }
        });
        return examples;
    }

    /** Each documented response content, as "METHOD path status media type", that has no example. */
    public List<String> responsesWithoutExample() {
        List<String> missing = new ArrayList<>();
        for (String operation : operations()) {
            JsonObject responses = ((JsonObject) node(operation)).getJsonObject("responses", new JsonObject());
            for (String status : new TreeSet<>(responses.fieldNames())) {
                String response = target(operation + "/responses/" + status);
                JsonObject content = ((JsonObject) node(response)).getJsonObject("content", new JsonObject());
                for (String mediaType : new TreeSet<>(content.fieldNames())) {
                    JsonObject media = content.getJsonObject(mediaType);
                    boolean example = media.containsKey("example")
                            || media.getValue("examples") instanceof JsonObject named && !named.isEmpty()
                            || media.getValue("examples") instanceof JsonArray values && !values.isEmpty();
                    if (!example) {
                        missing.add(operationName(operation) + " " + status + " " + mediaType);
                    }
                }
            }
        }
        return missing;
    }

    /** The JSON pointers of all operations, such as {@code #/paths/~1api~1v1~1sellers/post}. */
    public List<String> operations() {
        List<String> operations = new ArrayList<>();
        JsonObject paths = document.getJsonObject("paths", new JsonObject());
        for (String path : new TreeSet<>(paths.fieldNames())) {
            for (String method : new TreeSet<>(paths.getJsonObject(path).fieldNames())) {
                if (HTTP_METHODS.contains(method)) {
                    operations.add("#/paths/" + escape(path) + "/" + method);
                }
            }
        }
        return operations;
    }

    /**
     * The JSON pointer of the response body schema for a request and a response status. It is empty if the
     * documented response has no JSON body. An undocumented operation or status is an {@link AssertionError}.
     */
    public Optional<String> responseSchema(String method, String requestTarget, int status) {
        String path = requestTarget.split("\\?", 2)[0];
        String operation = operations().stream()
                .filter(candidate -> candidate.endsWith("/" + method.toLowerCase()))
                .filter(candidate -> templatePattern(tokens(candidate).get(1)).matcher(path).matches())
                .min(Comparator.comparingLong(candidate -> TEMPLATE.matcher(tokens(candidate).get(1)).results()
                        .count()))
                .orElseThrow(() -> new AssertionError("The contract has no operation " + method + " " + path));
        JsonObject responses = ((JsonObject) node(operation)).getJsonObject("responses", new JsonObject());
        if (!responses.containsKey(String.valueOf(status))) {
            throw new AssertionError("The contract has no status " + status + " for " + operationName(operation));
        }
        String response = target(operation + "/responses/" + status);
        JsonObject content = ((JsonObject) node(response)).getJsonObject("content", new JsonObject());
        return content.containsKey("application/json")
                ? Optional.of(response + "/content/" + escape("application/json") + "/schema")
                : Optional.empty();
    }

    private List<String> schemaErrors(String schemaUri, Object value) {
        OutputUnit result = repository.validator(schemaUri).validate(value);
        if (Boolean.TRUE.equals(result.getValid())) {
            return List.of();
        }
        List<OutputUnit> units = result.getErrors() == null ? List.of(result) : result.getErrors();
        return units.stream().map(unit -> unit.getInstanceLocation() + " " + unit.getError()).toList();
    }

    /** Component schemas and the schemas of parameters, headers and media types; nested schemas are in them. */
    private List<String> schemaObjects() {
        List<String> schemas = new ArrayList<>();
        JsonObject components = document.getJsonObject("components", new JsonObject())
                .getJsonObject("schemas", new JsonObject());
        components.fieldNames().stream().sorted().forEach(name -> schemas.add("#/components/schemas/"
                + escape(name)));
        walk(document, "#", (pointer, object) -> {
            if (object.getValue("schema") instanceof JsonObject && !pointer.startsWith("#/components/schemas/")) {
                schemas.add(pointer + "/schema");
            }
        });
        return schemas;
    }

    private Set<String> pathParameters(String operation) {
        Set<String> names = new TreeSet<>();
        String pathItem = operation.substring(0, operation.lastIndexOf('/'));
        for (String owner : List.of(pathItem, operation)) {
            JsonArray parameters = ((JsonObject) node(owner)).getJsonArray("parameters", new JsonArray());
            for (int index = 0; index < parameters.size(); index++) {
                JsonObject parameter = (JsonObject) node(target(owner + "/parameters/" + index));
                if ("path".equals(parameter.getString("in"))) {
                    names.add(parameter.getString("name"));
                }
            }
        }
        return names;
    }

    private static Set<String> templateNames(String path) {
        Set<String> names = new TreeSet<>();
        Matcher matcher = TEMPLATE.matcher(path);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private static Pattern templatePattern(String path) {
        StringBuilder pattern = new StringBuilder();
        Matcher matcher = TEMPLATE.matcher(path);
        int start = 0;
        while (matcher.find()) {
            pattern.append(Pattern.quote(path.substring(start, matcher.start()))).append("[^/]+");
            start = matcher.end();
        }
        return Pattern.compile(pattern.append(Pattern.quote(path.substring(start))).toString());
    }

    private static String operationName(String operation) {
        List<String> tokens = tokens(operation);
        return tokens.get(2).toUpperCase() + " " + tokens.get(1);
    }

    /** Visits every JSON object with its JSON pointer, the document first. */
    private static void walk(Object node, String pointer, BiConsumer<String, JsonObject> visitor) {
        if (node == null) {
            return;
        }
        switch (node) {
            case JsonObject object -> {
                visitor.accept(pointer, object);
                object.fieldNames().stream().sorted().forEach(name -> walk(object.getValue(name),
                        pointer + "/" + escape(name), visitor));
            }
            case JsonArray array -> {
                for (int index = 0; index < array.size(); index++) {
                    walk(array.getValue(index), pointer + "/" + index, visitor);
                }
            }
            default -> {
            }
        }
    }

    private static List<String> tokens(String pointer) {
        if (!pointer.equals("#") && !pointer.startsWith("#/")) {
            throw new IllegalArgumentException("Not an in-document JSON pointer: " + pointer);
        }
        List<String> tokens = new ArrayList<>();
        for (String token : pointer.substring(1).split("/", -1)) {
            tokens.add(token.replace("~1", "/").replace("~0", "~"));
        }
        return tokens.isEmpty() ? tokens : tokens.subList(1, tokens.size());
    }

    private static String escape(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }

    /** A JSON pointer as a URI fragment: Vert.x looks up references by their URL form. */
    private static String escapeFragment(String pointer) {
        return pointer.replace("%", "%25").replace("{", "%7B").replace("}", "%7D");
    }

    private static String strictDateTime(String instanceType, String format, Object instance) {
        if (!"date-time".equals(format) || !(instance instanceof String text)) {
            return null;
        }
        try {
            OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            return null;
        } catch (DateTimeParseException failure) {
            return "String is not a valid calendar date and time";
        }
    }

    private static JsonObject bundledSchema(String uri) {
        String resource = uri.substring("https://".length());
        try (InputStream stream = ContractDocument.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Vert.x does not bundle " + uri);
            }
            return new JsonObject(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
