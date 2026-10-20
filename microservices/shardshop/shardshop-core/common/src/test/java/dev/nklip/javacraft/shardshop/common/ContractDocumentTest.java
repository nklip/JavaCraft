package dev.nklip.javacraft.shardshop.common;

import dev.nklip.javacraft.shardshop.common.contract.ContractDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ContractDocumentTest {
    @TempDir
    Path directory;

    @Test
    void traversesNullExamplesAndNestedNullMembersWithoutLosingLaterExamples() throws IOException {
        Path schema = directory.resolve("nullable.json");
        Files.writeString(schema, """
                {
                  "$schema": "https://json-schema.org/draft/2020-12/schema",
                  "$id": "https://shardshop.test/contracts/nullable.json",
                  "type": ["object", "null"],
                  "examples": [null, {"nextAfter": null, "values": [null, {"text": "later"}]}],
                  "$defs": {"text": {"type": "string", "examples": ["still visited"]}}
                }
                """);
        ContractDocument contract = ContractDocument.messageSchema(schema);

        assertEquals(List.of(), contract.structureErrors());
        var examples = contract.examples();
        assertEquals(List.of("#/examples/0", "#/examples/1", "#/$defs/text/examples/0"),
                examples.stream().map(ContractDocument.Example::location).toList());
        assertNull(examples.getFirst().value());
        assertEquals(ContractDocument.json("{\"nextAfter\":null,\"values\":[null,{\"text\":\"later\"}]}"),
                examples.get(1).value());
        for (var example : examples) {
            assertEquals(List.of(), contract.errors(example.schema(), example.value()), example.location());
        }
    }
}
