# Common utilities runbook

[ShardShop](../../README.md) › [Core](../README.md) › **Common utilities runbook** · [Architecture](ARCHITECTURE.md)

## ID validation

All six applications use `dev.nklip.javacraft.shardshop.common.IdParser` from
`shardshop-common`; its implementation and regression tests are maintained once
in that library. `parse(String)`
validates path/descriptor text and returns an exact positive `long`.
`parseJson(JsonParser)` requires the current token to be a JSON string, then
applies the same whole-string ASCII and signed-long range checks without
advancing or closing the caller's parser. Call it before typed DTO binding so
numeric tokens cannot be coerced into strings. Invalid IDs, including malformed
string content detected by Jackson when reading the current token, throw
`IllegalArgumentException` with a fixed message that contains no input data;
HTTP providers map this to `400 INVALID_REQUEST`, and message
consumers must apply their invalid-command handling. Plain `IOException` from
the underlying stream propagates unchanged. Surrounding document validation,
including mapping syntax errors from the caller's token reads to HTTP 400,
remains with those providers/consumers.

The allocation `requestOrdinal` permits `"0"`, thus `IdParser` does not validate
it ([wire contracts](../ARCHITECTURE.md#wire-contracts)).

Run the shared utility tests alone with
`mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-core/common clean verify`.

The [six-application audit](../../RUNBOOK.md#dependency-and-test-validation) runs all affected
application checks, including dependency/SBOM reports and packaged routing tests,
without infrastructure.
