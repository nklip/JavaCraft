#!/usr/bin/env python3
"""Check the self-contained step 3.1 contracts and illustrative data, without IO.

These are contract/schema checks, not tests of future HTTP or message providers.
"""

import hashlib
import json
import sys
from copy import deepcopy
from datetime import datetime, timedelta, timezone
from decimal import Decimal, localcontext
from itertools import combinations
from pathlib import Path

import yaml
from jsonschema import Draft202012Validator, FormatChecker, validators
from openapi_spec_validator import validate


ROOT = Path(__file__).resolve().parents[1]
LONG_MAX = 9223372036854775807
EPOCH = datetime(2026, 1, 1, tzinfo=timezone.utc)
HTTP_METHODS = {"get", "put", "post", "delete", "options", "head", "patch", "trace"}


def is_integer(checker, value):
    return (Draft202012Validator.TYPE_CHECKER.is_type(value, "integer") or
            isinstance(value, Decimal) and value.is_finite() and value == value.to_integral_value())


ExactValidator = validators.extend(
    Draft202012Validator,
    type_checker=Draft202012Validator.TYPE_CHECKER.redefine("integer", is_integer),
)


def objects(value, path=""):
    if isinstance(value, dict):
        yield path, value
        for key, child in value.items():
            yield from objects(child, f"{path}/{key}")
    elif isinstance(value, list):
        for index, child in enumerate(value):
            yield from objects(child, f"{path}/{index}")


def local_reference(document, reference):
    if not reference.startswith("#/"):
        raise ValueError(f"Only in-document references are allowed: {reference}")
    target = document
    for part in reference[2:].split("/"):
        target = target[part.replace("~1", "/").replace("~0", "~")]
    return target


def expand(schema, document, ancestors=()):
    """Resolve local schema references, retaining JSON Schema sibling constraints."""
    if isinstance(schema, list):
        return [expand(value, document, ancestors) for value in schema]
    if not isinstance(schema, dict):
        return schema
    siblings = {key: expand(value, document, ancestors)
                for key, value in schema.items() if key != "$ref"}
    if "$ref" not in schema:
        return siblings
    reference = schema["$ref"]
    if reference in ancestors:
        raise ValueError(f"Recursive reference needs an explicit validation strategy: {reference}")
    target = expand(local_reference(document, reference), document, ancestors + (reference,))
    return {"allOf": [target, siblings]} if siblings else target


def validator(schema, document):
    return ExactValidator(expand(schema, document), format_checker=FormatChecker())


def example_values(node, document):
    if "example" in node:
        yield node["example"]
    examples = node.get("examples", {})
    if isinstance(examples, list):
        yield from examples
    else:
        for example in examples.values():
            if "$ref" in example:
                example = local_reference(document, example["$ref"])
            if "externalValue" in example:
                raise ValueError("External examples are not self-contained")
            yield example["value"]


def first_example(node, document):
    if "$ref" in node:
        node = local_reference(document, node["$ref"])
    return next(example_values(node["content"]["application/json"], document))


def unique_members(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"Duplicate JSON member: {key}")
        result[key] = value
    return result


def load_json(path):
    return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_members, parse_float=Decimal)


def fingerprint(buyer_id, order_id, creation):
    normalized = {"buyerId": buyer_id, "orderId": order_id,
                  "currency": creation["currency"], "items": []}
    for item in creation["items"]:
        quantity = Decimal(str(item["quantity"]))
        if quantity != quantity.to_integral_value():
            raise ValueError("A quantity must be an exact integer")
        normalized["items"].append({"sellerId": item["sellerId"],
                                    "productId": item["productId"], "quantity": int(quantity)})
    wire = json.dumps(normalized, ensure_ascii=False, separators=(",", ":"))
    return hashlib.sha256(wire.encode("utf-8")).hexdigest()


class Checks:
    def __init__(self):
        self.failures = []
        self.boundaries = 0
        self.examples = []
        self.responses = 0

    def require(self, condition, label):
        if not condition:
            self.failures.append(label)

    def matrix(self, label, schema, document, valid, invalid):
        check = validator(schema, document)
        for expected, values in ((True, valid), (False, invalid)):
            for value in values:
                self.boundaries += 1
                self.require(check.is_valid(value) == expected,
                             f"{label}: {value!r} should be {'valid' if expected else 'invalid'}")

    def openapi(self, owner, document):
        validate(document)
        for path, node in objects(document):
            if "example" not in node and "examples" not in node:
                continue
            schema = node.get("schema", node)
            for value in example_values(node, document):
                errors = list(validator(schema, document).iter_errors(value))
                self.require(not errors, f"{owner}{path}: invalid example: {errors}")
                self.examples.append(value)
        for path, item in document["paths"].items():
            for method in HTTP_METHODS.intersection(item):
                for status, response in item[method]["responses"].items():
                    if "$ref" in response:
                        response = local_reference(document, response["$ref"])
                    for media_type, media in response.get("content", {}).items():
                        self.responses += 1
                        self.require(bool(list(example_values(media, document))),
                                     f"{owner} {method.upper()} {path} {status} {media_type}: missing example")

    def boundaries_for(self, owner, document, definitions):
        ids = definitions["snowflakeId" if "$defs" in document else "Id"]
        self.matrix(f"{owner} ID", ids, document,
                    ["1", "9007199254740993", str(LONG_MAX - 1), str(LONG_MAX)],
                    [1, "0", "01", "+1", "-1", "1.0", "1e3", "1\n", "1\r\n", "\t1",
                     "１", str(LONG_MAX + 1), "9999999999999999999"])
        money = definitions.get("money", definitions.get("Price"))
        if money:
            self.matrix(f"{owner} money", money, document,
                        ["0.00", "19.95", "99999999999999999.99"],
                        [0, "-0.00", "+1.00", "01.00", "1", "1.0", "1.000", "1e2",
                         "1.00\n", "100000000000000000.00"])
        if "timestamp" in definitions:
            self.matrix(f"{owner} timestamp", definitions["timestamp"], document,
                        ["2026-10-06T12:00:00Z", "2026-10-06T12:00:00.1Z",
                         "2026-10-06T12:00:00.123456Z"],
                        ["2026-10-06T12:00:00.1234567Z", "2026-10-06T12:00:00.123456789Z",
                         "2026-10-06T12:00:00+04:00", "2026-10-06T12:00:00+00:00",
                         "2026-10-06t12:00:00z", "2026-10-06T12:00:00Z\n",
                         "2026-02-30T12:00:00Z", "2026-10-06T25:00:00Z"])
        names = [definitions["Name"]] if "Name" in definitions else []
        if "orderItem" in definitions:
            names.append(definitions["orderItem"]["properties"]["productName"])
        for name in names:
            self.matrix(f"{owner} name", name, document,
                        ["A", " Café ", "\U0001f680", "A" * 200],
                        ["", " \t\n", "A" * 201, "A\x00", "\ud800", "A\udfff", "\udc00\ud800"])

    def illustrative_ids(self, values):
        fixtures = {key: set() for key in ("sellerId", "productId", "buyerId")}
        live = set()
        for value in values:
            for _, node in objects(value):
                for key in fixtures:
                    if key in node:
                        fixtures[key].add(int(node[key]))
                for key in ("orderId", "sagaId", "commandId", "messageId", "resultMessageId"):
                    if key in node:
                        live.add(int(node[key]))
                if "statusUrl" in node:
                    self.require(node["statusUrl"] ==
                                 f"/api/v1/buyers/{node['buyerId']}/orders/{node['orderId']}",
                                 "statusUrl must contain the response buyerId and orderId")
                if "stock" in node:
                    self.require(node["stock"] <= node["initialStock"], "stock exceeds initialStock")
        for key, ids in fixtures.items():
            self.require(bool(ids), f"No illustrative {key} found")
            self.require(all((value >> 12) & 1023 == 0 and value >> 22 > 0 for value in ids),
                         f"{key}: fixture IDs require generator 0 and a positive timestamp")
        for (left, first), (right, second) in combinations(fixtures.items(), 2):
            self.require(max(first) >> 22 < min(second) >> 22 or
                         max(second) >> 22 < min(first) >> 22,
                         f"{left}/{right}: fixture timestamp ranges overlap")
        self.require(all((value >> 12) & 1023 > 0 for value in live),
                     "Live illustrative IDs must use generators 1..1023")


def semantic_checks(checks, product, order, command, results):
    order_path = order["paths"]["/api/v1/buyers/{buyerId}/orders/{orderId}"]["put"]
    request = first_example(order_path["requestBody"], order)
    accepted = first_example(order_path["responses"]["202"], order)
    checks.require((accepted["buyerId"], accepted["orderId"]) ==
                   (command["buyerId"], command["orderId"]), "HTTP order IDs differ from command IDs")
    snapshot = command["snapshot"]
    creation = {"currency": snapshot["currency"], "items": [
        {key: item[key] for key in ("sellerId", "productId", "quantity")}
        for item in snapshot["items"]]}
    checks.require(request == creation, "HTTP order request differs from message creation request")
    checks.require(command["requestFingerprint"] == fingerprint(command["buyerId"], command["orderId"], creation),
                   "Command requestFingerprint differs from canonical SHA-256")
    for token in ("2", "2.0", "2e0"):
        normalized = deepcopy(creation)
        normalized["items"][0]["quantity"] = json.loads(token, parse_float=Decimal)
        checks.require(fingerprint(command["buyerId"], command["orderId"], normalized) ==
                       command["requestFingerprint"], f"Quantity {token} changes the example fingerprint")
    with localcontext() as context:
        context.prec = 50
        amount = sum(Decimal(item["unitPrice"]) * item["quantity"] for item in snapshot["items"])
    checks.require(amount == Decimal(snapshot["amount"]), "Snapshot total differs from price × quantity")
    checks.require(amount <= Decimal("99999999999999999.99"), "Snapshot total exceeds NUMERIC(19,2)")
    checks.require(len({(item['sellerId'], item['productId']) for item in snapshot["items"]}) ==
                   len(snapshot["items"]), "Snapshot contains duplicate seller/product pairs")
    product_path = product["paths"]["/api/v1/sellers/{sellerId}/products/{productId}"]["put"]
    catalog = first_example(product_path["responses"]["201"], product)
    for position, item in enumerate(snapshot["items"]):
        checks.require(item["itemPosition"] == position and item["currency"] == snapshot["currency"],
                       "Snapshot position/currency mismatch")
        checks.require(all(item[left] == catalog[right] for left, right in
                           (("sellerId", "sellerId"), ("productId", "productId"),
                            ("productName", "name"), ("unitPrice", "price"), ("currency", "currency"))),
                       "Message item differs from the illustrative catalog product")
    transport_ids = [command[key] for key in ("orderId", "sagaId", "commandId", "messageId", "resultMessageId")]
    checks.require(len(set(transport_ids)) == len(transport_ids), "Command reserved IDs must be distinct")
    for result in results:
        checks.require(all(result[key] == command[key] for key in
                           ("commandId", "sagaId", "orderId", "buyerId", "requestFingerprint")),
                       f"{result['type']}: command correlation differs")
        checks.require(result["messageId"] == command["resultMessageId"], "Result uses an unreserved ID")
        checks.require(all(result[key] == snapshot[key] for key in ("amount", "currency")),
                       "Result amount/currency differs from snapshot")
        checks.require(datetime.fromisoformat(command["occurredAt"]) <=
                       datetime.fromisoformat(result["decidedAt"]) <= datetime.fromisoformat(result["occurredAt"]),
                       "Result decision/envelope times precede their cause")
    for envelope in [command, *results]:
        for _, node in objects(envelope):
            for key, value in node.items():
                if key.endswith("Id"):
                    generated = EPOCH + timedelta(milliseconds=int(value) >> 22)
                    checks.require(generated <= datetime.fromisoformat(envelope["occurredAt"]),
                                   f"{envelope['type']} {key}: ID timestamp is after occurredAt")


def main():
    checks = Checks()
    documents = {}
    messages = {}
    for owner in ("product", "order", "ledger"):
        directory = ROOT / f"shardshop-{owner}/src/main/resources/contracts"
        api_path = directory / "openapi.yaml"
        if api_path.exists():
            document = yaml.safe_load(api_path.read_text(encoding="utf-8"))
            documents[owner] = document
        else:
            document = None
        schemas = [(path, load_json(path)) for path in sorted(directory.glob("*.schema.json"))]
        for current in ([document] if document else []) + [schema for _, schema in schemas]:
            for _, node in objects(current):
                if "$ref" in node:
                    local_reference(current, node["$ref"])
        if document:
            checks.openapi(owner, document)
            checks.boundaries_for(owner, document, document["components"]["schemas"])
        for path, schema in schemas:
            Draft202012Validator.check_schema(schema)
            checks.boundaries_for(path.name, schema, schema["$defs"])
            for example in sorted(directory.glob("examples/*.json")):
                value = load_json(example)
                validator(schema, schema).validate(value)
                messages[example.stem] = value

    product, order = documents["product"], documents["order"]
    product_schemas, order_schemas = product["components"]["schemas"], order["components"]["schemas"]
    integral_tokens = [json.loads(token, parse_float=Decimal) for token in ("2", "2.0", "2e0")]
    fractional_tokens = [Decimal("2.5"), Decimal("2.000000000000000001"), Decimal("2147483647.000000001")]
    checks.matrix("initialStock", product_schemas["Stock"], product,
                  [0, *integral_tokens, 2147483647], [-1, *fractional_tokens, "2", True, 2147483648])
    checks.matrix("quantity", order_schemas["OrderItem"]["properties"]["quantity"], order,
                  [1, *integral_tokens, 2147483647], [0, *fractional_tokens, "2", True, 2147483648])
    checks.matrix("requestOrdinal", order_schemas["RequestOrdinal"], order,
                  ["0", "1", str(LONG_MAX)], [0, "00", "-1", "1.0", "1e0", "1\n", str(LONG_MAX + 1)])
    checks.matrix("buyer request", order_schemas["BuyerCreation"], order,
                  [{"name": "Buyer", "region": "US"}],
                  [{"name": "Buyer", "region": "US", "extra": 1}, {"name": 123, "region": "US"}])
    checks.matrix("statusUrl", order_schemas["OrderStatus"]["properties"]["statusUrl"], order,
                  [f"/api/v1/buyers/1/orders/{LONG_MAX}", f"/api/v1/buyers/{LONG_MAX}/orders/1"],
                  [f"/api/v1/buyers/{LONG_MAX + 1}/orders/1", f"/api/v1/buyers/1/orders/{LONG_MAX + 1}",
                   "/api/v1/buyers/01/orders/1", "/api/v1/buyers/1/orders/1\n"])
    checks.illustrative_ids(checks.examples + list(messages.values()))
    semantic_checks(checks, product, order, messages["record-order"],
                    [messages["ledger-recorded"], messages["ledger-rejected"]])
    if checks.failures:
        print("Contract validation failed:", file=sys.stderr)
        for failure in checks.failures:
            print(f"- {failure}", file=sys.stderr)
        return 1
    print(f"Passed: 2 OpenAPI documents, {len(checks.examples)} inline examples, "
          f"{checks.responses} response content examples, 2 message schemas, 3 message examples, "
          f"{checks.boundaries} boundary cases, IDs, fingerprints, totals and correlation.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
