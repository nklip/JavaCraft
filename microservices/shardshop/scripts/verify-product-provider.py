#!/usr/bin/env python3
"""Exercise the packaged product API against isolated databases in a test container.

Run with the contract-validation Python environment after packaging product:
  python -B scripts/verify-product-provider.py CONTAINER [--jdbc-port PORT]

The existing disposable PostgreSQL container must publish port 5432 on localhost.
This check creates four uniquely named databases and a temporary runtime login,
starts/stops its own JVMs, and removes its databases and roles on exit. Entity IDs
always come from HTTP responses; the harness only generates retry keys.
"""

import argparse
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from decimal import Decimal
import hashlib
import http.client
import importlib.util
import json
import os
from pathlib import Path
import re
import select
import socket
import subprocess
import tempfile
import threading
import time
from urllib.parse import quote
import uuid

import yaml


ROOT = Path(__file__).resolve().parents[1]
PRODUCT = ROOT / "shardshop-product"
SHARDS = (("shard-a", "US"), ("shard-b", "EU"), ("shard-c", "ASIA"), ("shard-d", "US"))
GROUPS = ("catalog_reader", "catalog_writer", "catalog_reserver")
BASE = "/api/v1/sellers"
ITEM = {"name": "Verification item", "description": "Synthetic provider verification product",
        "price": "19.95", "unitCost": "12.50", "currency": "USD", "initialStock": 2}


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def free_port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def digest(value):
    return int.from_bytes(hashlib.sha256(value.encode()).digest(), "big")


class ProviderChecks:
    def __init__(self, container, jdbc_port, java, work):
        self.container, self.jdbc_port, self.java, self.work = container, jdbc_port, java, Path(work)
        self.prefix = "catalog_api_" + uuid.uuid4().hex
        self.login, self.password = self.prefix + "_app", uuid.uuid4().hex
        self.databases = {shard: self.prefix + "_" + shard[-1] for shard, _ in SHARDS}
        self.created_databases, self.created_roles = [], []
        self.server, self.log = None, None
        self.generation, self.port, self.responses = 0, free_port(), 0
        self.count_lock = threading.Lock()
        spec = importlib.util.spec_from_file_location("contract_checks", ROOT / "scripts" / "verify-contracts.py")
        contracts = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(contracts)
        document = yaml.safe_load((PRODUCT / "src/main/resources/contracts/openapi.yaml").read_text())
        self.schemas = []
        for path, methods in document["paths"].items():
            pattern = re.compile(re.sub(r"\{[^}]+\}", "[^/]+", path) + "$")
            for method, operation in methods.items():
                if method not in {"get", "post"}:
                    continue
                responses = {}
                for status, response in operation["responses"].items():
                    if "$ref" in response:
                        response = contracts.local_reference(document, response["$ref"])
                    responses[int(status)] = contracts.validator(response["content"]["application/json"]["schema"], document)
                self.schemas.append((pattern, method.upper(), responses))

    def psql(self, database):
        return ["docker", "exec", "-i", self.container, "env",
                "PGOPTIONS=-c statement_timeout=10000 -c lock_timeout=2000", "PGCONNECT_TIMEOUT=5",
                "psql", "-X", "-qAt", "-v", "ON_ERROR_STOP=1", "-U", "postgres", "-d", database]

    def sql(self, statement, database="postgres"):
        result = subprocess.run(self.psql(database), input=statement, text=True,
                                capture_output=True, timeout=15)
        require(result.returncode == 0, "PostgreSQL verification command failed: " + result.stderr)
        return result.stdout.strip()

    def setup(self):
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            ready = subprocess.run(["docker", "exec", self.container, "pg_isready", "-U", "postgres", "-t", "1"],
                                   capture_output=True, timeout=3)
            if ready.returncode == 0:
                break
            threading.Event().wait(0.2)
        else:
            raise AssertionError("PostgreSQL did not become ready within 20 seconds")
        existing = self.sql("SELECT rolname FROM pg_roles").splitlines()
        for group in GROUPS:
            if group not in existing:
                self.sql(f"CREATE ROLE {group} NOLOGIN")
                self.created_roles.append(group)
        self.sql(f"CREATE ROLE {self.login} LOGIN PASSWORD '{self.password}'; GRANT catalog_writer TO {self.login}")
        self.created_roles.append(self.login)
        catalog = ROOT / "database/shard/catalog"
        for index, (shard, region) in enumerate(SHARDS):
            database = self.databases[shard]
            self.sql(f"CREATE DATABASE {database}")
            self.created_databases.append(database)
            v1 = (catalog / "V1__catalog.sql").read_text().replace("${shardRegion}", region)
            v1 = v1.replace("${shardCount}", str(len(SHARDS))).replace("${shardIndex}", str(index))
            self.sql("CREATE SCHEMA catalog;\n" + v1 + "\n"
                     + (catalog / "V2__catalog_creation.sql").read_text() + "\n"
                     + (catalog / "R__catalog_grants.sql").read_text(), database)

    def start(self, *, unavailable_primary=False, replica=False, jdbc_host="127.0.0.1",
              jdbc_options="sslmode=disable", root_certificate=None):
        self.stop()
        self.generation += 1
        unavailable_port = free_port()
        properties = ["shardshop.routing.shards=" + ",".join(shard for shard, _ in SHARDS),
                      "shardshop.routing.regions=" + ",".join(region for _, region in SHARDS),
                      "shardshop.catalog.max-pool-size=2",
                      "shardshop.catalog.read-profile=" + ("replica" if replica else "primary")]
        for shard, _ in SHARDS:
            prefix = f"shardshop.catalog.connections.{shard}."
            port = unavailable_port if unavailable_primary else self.jdbc_port
            url = f"jdbc:postgresql://{jdbc_host}:{port}/{self.databases[shard]}"
            if jdbc_options:
                url += "?" + jdbc_options
            properties.extend((prefix + "primary-url=" + url,
                               prefix + f"replica-url=jdbc:postgresql://127.0.0.1:{unavailable_port}/{self.databases[shard]}?sslmode=disable",
                               prefix + "username=" + self.login, prefix + "password=" + self.password))
            if root_certificate is not None:
                properties.append(prefix + "root-certificate=" + str(root_certificate))
        config = self.work / "routing.properties"
        config.write_text("\n".join(properties) + "\n")
        config.chmod(0o600)
        self.log = (self.work / f"server-{self.generation}.log").open("w+")
        environment = {key: value for key, value in os.environ.items()
                       if not key.startswith(("SHARDSHOP_", "QUARKUS_"))}
        self.server = subprocess.Popen(
            [self.java, f"-Dshardshop.routing.config={config.as_uri()}",
             f"-Dshardshop.id.generator-id={self.generation}",
             f"-Dshardshop.launcher.reserved-generator-id={self.generation}",
             f"-Dquarkus.http.port={self.port}", "-Dquarkus.http.host=127.0.0.1",
             "-Dquarkus.profile=prod", "-jar", str(PRODUCT / "target/quarkus-app/quarkus-run.jar")],
            cwd=self.work, env=environment, stdout=self.log, stderr=subprocess.STDOUT)
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            if self.server.poll() is not None:
                self.log.seek(0)
                raise AssertionError("Product exited during startup:\n" + self.log.read()[-10000:])
            try:
                self.request("GET", BASE + "/0", expected=400, code="INVALID_REQUEST")
                return
            except (ConnectionError, OSError, http.client.HTTPException):
                threading.Event().wait(0.05)
        raise AssertionError("Product did not become ready within 30 seconds")

    def stop(self):
        if self.server is not None:
            self.server.terminate()
            try:
                self.server.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.server.kill()
                self.server.wait(timeout=5)
            self.server = None
        if self.log is not None:
            self.log.close()
            self.log = None

    def request(self, method, path, body=None, *, key=None, expected=200, code=None,
                raw=None, headers=None, content_type="application/json", chunked=False):
        data = raw if raw is not None else (json.dumps(body, separators=(",", ":")).encode() if body is not None else None)
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=5)
        started = time.monotonic()
        try:
            connection.putrequest(method, path)
            if content_type is not None:
                connection.putheader("Content-Type", content_type)
            if key is not None:
                connection.putheader("Idempotency-Key", key)
            for name, value in headers or ():
                connection.putheader(name, value)
            if data is not None:
                if chunked:
                    connection.putheader("Transfer-Encoding", "chunked")
                    data = (f"{len(data):x}\r\n".encode() + data + b"\r\n" if data else b"") + b"0\r\n\r\n"
                else:
                    connection.putheader("Content-Length", str(len(data)))
            connection.endheaders(data)
            response = connection.getresponse()
            response_body = response.read(65536)
            try:
                document = json.loads(response_body.decode("utf-8"), parse_float=Decimal)
            except (UnicodeDecodeError, ValueError) as failure:
                raise AssertionError(f"{method} {path} with Content-Type {content_type!r}: "
                                     f"status {response.status} returned non-JSON body {response_body[:200]!r}") from failure
            elapsed = time.monotonic() - started
            allowed = expected if isinstance(expected, tuple) else (expected,)
            require(response.status in allowed, f"{method} {path} with Content-Type {content_type!r}: "
                    f"expected {allowed}, got {response.status}: {document}")
            require(response.getheader("Content-Type", "").startswith("application/json"), "Response must be JSON")
            for pattern, schema_method, schemas in self.schemas:
                if schema_method == method and pattern.fullmatch(path.split("?", 1)[0]):
                    require(response.status in schemas, f"Undocumented status {response.status}")
                    schemas[response.status].validate(document)
                    break
            else:
                raise AssertionError("No contract operation for " + path)
            if code is not None:
                require(document["code"] == code, f"Expected {code}, got {document}")
            with self.count_lock:
                self.responses += 1
            return response.status, document, elapsed
        finally:
            connection.close()

    def create_retry(self, body, key, path=BASE):
        for _ in range(5):
            status, value, _ = self.request("POST", path, body, key=key, expected=(200, 201, 503))
            if status != 503:
                return status, value
            threading.Event().wait(0.05)
        raise AssertionError("Creation remained unavailable after bounded identical retries")

    def key_shard(self, region, key):
        eligible = [shard for shard, home in SHARDS if home == region]
        return eligible[digest("seller\0" + region + "\0" + key) % len(eligible)]

    def placement(self, seller, key):
        identifier = seller["sellerId"]
        routed = SHARDS[digest(identifier) % len(SHARDS)][0]
        require(routed == self.key_shard(seller["region"], key), "Seller ID and creation key route differently")
        for shard, database in self.databases.items():
            require(self.sql(f"SELECT count(*) FROM catalog.sellers WHERE seller_id = {identifier}", database)
                    == ("1" if shard == routed else "0"), "Seller is stored on the wrong shard")
        return routed

    def happy_paths(self):
        sellers = {}
        for region in ("US", "EU", "ASIA"):
            payload = {"companyName": "Synthetic " + region + " company", "region": region}
            _, seller, _ = self.request("POST", BASE, payload, key="shared-region-key", expected=201)
            require(seller["companyName"] == payload["companyName"] and seller["region"] == region,
                    "Seller creation changed its requested fields")
            require(seller["profitsEarned"] == {"USD": "0.00", "EUR": "0.00"}, "Initial profit balances changed")
            self.placement(seller, "shared-region-key")
            require(self.request("POST", BASE, payload, key="shared-region-key")[1] == seller, "Seller retry changed identity")
            require(self.request("GET", BASE + "/" + seller["sellerId"])[1] == seller, "Seller GET differs")
            require(self.request("GET", BASE + "/by-key/shared-region-key?region=" + region)[1] == seller, "Seller key lookup differs")
            sellers[region] = seller
        require(len({value["sellerId"] for value in sellers.values()}) == 3, "Regional scopes collided")
        us_shard = self.key_shard("US", "shared-region-key")
        second_key = next(f"second-us-{index}" for index in range(100)
                          if self.key_shard("US", f"second-us-{index}") != us_shard)
        second = self.request("POST", BASE, {"companyName": "Second US shard", "region": "US"},
                              key=second_key, expected=201)[1]
        require(self.placement(second, second_key) != us_shard, "Multiple shards per region were not exercised")
        seller = sellers["US"]
        seller_path = BASE + "/" + seller["sellerId"]
        item_path = seller_path + "/products"
        product = self.request("POST", item_path, ITEM, key="shared-item-key", expected=201)[1]
        require(all(product[field] == value for field, value in ITEM.items()) and product["stock"] == 2
                and product["sellerId"] == seller["sellerId"], "Product creation changed its requested fields")
        require(self.request("GET", item_path + "/" + product["productId"])[1] == product, "Product GET differs")
        require(self.request("GET", item_path + "/by-key/shared-item-key")[1] == product, "Product key lookup differs")
        other = self.request("POST", BASE + "/" + sellers["EU"]["sellerId"] + "/products", ITEM,
                             key="shared-item-key", expected=201)[1]
        require(other["productId"] != product["productId"], "Parent-scoped product keys collided")
        for shard, database in self.databases.items():
            require(self.sql(f"SELECT count(*) FROM catalog.products WHERE product_id = {product['productId']}", database)
                    == ("1" if shard == us_shard else "0"), "Product did not inherit seller placement")
        self.request("POST", BASE, {"companyName": "Changed", "region": "US"}, key="shared-region-key",
                     expected=409, code="SELLER_IDEMPOTENCY_CONFLICT")
        for change in ({"name": "Changed"}, {"description": "Changed"}, {"price": "20.00"},
                       {"unitCost": "13.00"}, {"currency": "EUR"}, {"initialStock": 3}):
            self.request("POST", item_path, ITEM | change, key="shared-item-key",
                         expected=409, code="PRODUCT_IDEMPOTENCY_CONFLICT")
        self.request("GET", BASE + "/by-key/missing?region=US", expected=404, code="SELLER_NOT_FOUND")
        self.request("GET", item_path + "/by-key/missing", expected=404, code="PRODUCT_NOT_FOUND")
        self.request("GET", BASE + "/" + product["productId"], expected=404, code="SELLER_NOT_FOUND")
        self.request("POST", BASE + "/" + product["productId"] + "/products", ITEM, key="missing-parent",
                     expected=422, code="SELLER_NOT_FOUND")
        self.request("GET", item_path + "/" + seller["sellerId"], expected=404, code="PRODUCT_NOT_FOUND")
        self.request("GET", BASE + "/" + sellers["EU"]["sellerId"] + "/products/" + product["productId"],
                     expected=404, code="PRODUCT_NOT_FOUND")
        self.sql(f"UPDATE catalog.products SET stock = 1 WHERE product_id = {product['productId']}; "
                 f"UPDATE catalog.seller_profits SET amount = -7.45 WHERE seller_id = {seller['sellerId']} AND currency = 'USD'",
                 self.databases[us_shard])
        current_seller = self.request("POST", BASE, {"companyName": seller["companyName"], "region": "US"}, key="shared-region-key")[1]
        require(current_seller["profitsEarned"]["USD"] == "-7.45", "Retry reset current profits")
        for number in ("2.0", "2e0"):
            raw = json.dumps(ITEM, separators=(",", ":")).replace('"initialStock":2', '"initialStock":' + number).encode()
            current = self.request("POST", item_path, raw=raw, key="shared-item-key")[1]
            require(current["productId"] == product["productId"] and current["stock"] == 1, "Normalized retry reset stock")
        return current_seller, current

    def concurrency(self, seller):
        payload = {"companyName": "Concurrent company", "region": "EU"}
        with ThreadPoolExecutor(max_workers=8) as threads:
            values = list(threads.map(lambda _: self.create_retry(payload, "concurrent-seller"), range(8)))
        require(sum(status == 201 for status, _ in values) == 1, "Concurrent sellers did not have one first creation")
        require(len({value["sellerId"] for _, value in values}) == 1, "Concurrent sellers produced multiple IDs")
        path = BASE + "/" + seller["sellerId"] + "/products"
        with ThreadPoolExecutor(max_workers=8) as threads:
            values = list(threads.map(lambda _: self.create_retry(ITEM, "concurrent-product", path), range(8)))
        require(sum(status == 201 for status, _ in values) == 1, "Concurrent products did not have one first creation")
        require(len({value["productId"] for _, value in values}) == 1, "Concurrent products produced multiple IDs")

    def invalid_requests(self, seller):
        valid = {"companyName": "Valid company", "region": "US"}
        for body in (valid | {"companyName": value} for value in ("", "\u00a0", "\u0085", "\x00", "\ud800", "x" * 201, 123, None)):
            self.request("POST", BASE, body, key="invalid", expected=400, code="INVALID_REQUEST")
        for body in (valid | {"region": "AFRICA"}, valid | {"sellerId": seller["sellerId"]},
                     valid | {"profitsEarned": {"USD": "0.00"}}, [], None):
            self.request("POST", BASE, body, key="invalid", expected=400, code="INVALID_REQUEST")
        for path in (BASE, BASE + "/" + seller["sellerId"] + "/products"):
            for raw, chunked in ((None, False), (b"", False), (b"", True)):
                self.request("POST", path, raw=raw, chunked=chunked, key="empty-body",
                             expected=400, code="INVALID_REQUEST")
        for raw in (b'{"companyName":"A","companyName":"B","region":"US"}', b'{"companyName":"\xff","region":"US"}',
                    b'{', b'null', b'{"companyName":"A","region":"US"}{}'):
            self.request("POST", BASE, raw=raw, key="invalid", expected=400, code="INVALID_REQUEST")
        for key in (None, "", "bad key", "-bad", "x" * 129):
            self.request("POST", BASE, valid, key=key, expected=400, code="INVALID_REQUEST")
        self.request("POST", BASE, valid, key="duplicate", headers=[("Idempotency-Key", "duplicate")], expected=400, code="INVALID_REQUEST")
        for content_type in ("text/plain", "not-a-media-type", "application/json; charset", None,
                             "multipart/form-data", "multipart/form-data; boundary",
                             'multipart/form-data; boundary=""', "multipart/form-data; boundary=example"):
            self.request("POST", BASE, valid, key="invalid", content_type=content_type, expected=400, code="INVALID_REQUEST")
        for path in (BASE + "?unknown=1", BASE + "/" + seller["sellerId"] + "?region=US",
                     BASE + "/by-key/missing", BASE + "/by-key/missing?region=US&region=US",
                     BASE + "/by-key/missing?region=US&unknown=1", BASE + "/by-key/missing?region=AFRICA"):
            method = "POST" if path.startswith(BASE + "?") else "GET"
            self.request(method, path, valid if method == "POST" else None, key="invalid", expected=400, code="INVALID_REQUEST")
        for identifier in ("0", "01", "-1", "+1", "1.0", "1e0", "9223372036854775808", " 1", "1\n"):
            self.request("GET", BASE + "/" + quote(identifier, safe=""), expected=400, code="INVALID_REQUEST")
            self.request("GET", BASE + "/" + seller["sellerId"] + "/products/" + quote(identifier, safe=""),
                         expected=400, code="INVALID_REQUEST")
        path = BASE + "/" + seller["sellerId"] + "/products"
        for change in ({"name": "\u00a0"}, {"description": ""}, {"description": "x" * 2001}, {"price": "01.00"},
                       {"price": 1}, {"unitCost": "-0.01"}, {"currency": "usd"}, {"initialStock": 2.5},
                       {"initialStock": True}, {"initialStock": "2"}, {"initialStock": 2147483648},
                       {"stock": 2}, {"productId": seller["sellerId"]}):
            self.request("POST", path, ITEM | change, key="invalid", expected=400, code="INVALID_REQUEST")
        raw = json.dumps(ITEM, separators=(",", ":")).replace('"initialStock":2', '"initialStock":2.000000000000000000001').encode()
        self.request("POST", path, raw=raw, key="invalid", expected=400, code="INVALID_REQUEST")
        boundary = ITEM | {"name": "🛍" * 200, "description": "界" * 2000,
                           "price": "99999999999999999.99", "unitCost": "0.00", "initialStock": 2147483647}
        saved = self.request("POST", path, boundary, key="valid-boundary", expected=201)[1]
        require(all(saved[field] == value for field, value in boundary.items()), "Valid product boundaries changed")
        unicode_body = valid | {"companyName": "🛍" * 200}
        unicode_seller = self.request("POST", BASE, unicode_body, key="unicode-scalars", expected=201)[1]
        require(unicode_seller["companyName"] == unicode_body["companyName"], "Unicode scalar name was changed")

    def dropped_response(self):
        payload = {"companyName": "Lost response company", "region": "ASIA"}
        body = json.dumps(payload).encode()
        request = (f"POST {BASE} HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\n"
                   f"Idempotency-Key: dropped-response\r\nContent-Length: {len(body)}\r\nConnection: close\r\n\r\n").encode() + body
        with socket.create_connection(("127.0.0.1", self.port), timeout=5) as connection:
            connection.sendall(request)
            connection.shutdown(socket.SHUT_WR)
            # Intentionally discard the response: the commit outcome is unknown to this client.
        _, saved = self.create_retry(payload, "dropped-response")
        require(self.request("POST", BASE, payload, key="dropped-response")[1] == saved, "Lost response retry changed identity")
        require(self.request("GET", BASE + "/by-key/dropped-response?region=ASIA")[1] == saved, "Lost response lookup differs")
        self.placement(saved, "dropped-response")
        # Also guarantee that the first request committed before discarding its unread response.
        # The independent lookup observes the commit; the creating client never reads its result.
        with socket.create_connection(("127.0.0.1", self.port), timeout=5) as connection:
            connection.sendall(request.replace(b"dropped-response", b"committed-unread"))
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline:
                status, committed, _ = self.request("GET", BASE + "/by-key/committed-unread?region=ASIA", expected=(200, 404, 503))
                if status == 200:
                    break
                threading.Event().wait(0.05)
            else:
                raise AssertionError("The unread creation response never became durably discoverable")
        require(self.request("POST", BASE, payload, key="committed-unread")[1] == committed,
                "Discarding a committed creation response produced a different ID")

    def slow_body(self):
        body = json.dumps({"companyName": "Incomplete request company", "region": "US"}).encode()
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=5)
        started = time.monotonic()
        try:
            connection.putrequest("POST", BASE)
            connection.putheader("Content-Type", "application/json")
            connection.putheader("Idempotency-Key", "slow-body")
            connection.putheader("Content-Length", str(len(body)))
            connection.endheaders(body[:1])
            original_socket = connection.sock
            response = connection.getresponse()
            document = json.loads(response.read().decode())
            require(response.status == 503 and document["code"] == "CATALOG_UNAVAILABLE",
                    "Incomplete body did not receive the contract deadline response")
            require(time.monotonic() - started < 4.5, "Incomplete body exceeded the four-second deadline")
            schema = next(schemas[503] for pattern, method, schemas in self.schemas
                          if method == "POST" and pattern.fullmatch(BASE))
            schema.validate(document)
            self.responses += 1
            try:
                original_socket.sendall(body[1:])  # Never reconnect when completing the expired request late.
            except OSError:
                pass  # Closing the expired connection is also valid.
        finally:
            connection.close()
        self.request("GET", BASE + "/by-key/slow-body?region=US", expected=404, code="SELLER_NOT_FOUND")

    def oversized_requests(self):
        payload = json.dumps({"companyName": "Body boundary company", "region": "US"}).encode()
        maximum = 32768
        boundary = payload + b" " * (maximum - len(payload))
        self.request("POST", BASE, raw=boundary, key="body-boundary", expected=201)
        self.request("POST", BASE, key="oversized-declared", headers=[("Content-Length", str(maximum + 1))],
                     expected=400, code="INVALID_REQUEST")
        for chunked in (False, True):
            key = "oversized-chunked" if chunked else "oversized-fixed"
            self.request("POST", BASE, raw=boundary + b" ", key=key, chunked=chunked,
                         expected=400, code="INVALID_REQUEST")
            self.request("GET", BASE + "/by-key/" + key + "?region=US", expected=404, code="SELLER_NOT_FOUND")

    def clean_log(self):
        text = (self.work / f"server-{self.generation}.log").read_text()
        unexpected = [line for line in text.splitlines() if re.search(r"\b(?:WARN|ERROR)\b", line)]
        require(not unexpected, "Unexpected provider warnings/errors:\n" + "\n".join(unexpected))

    @contextmanager
    def held_key(self, database, region, key):
        unsigned = digest("seller\0" + region + "\0" + key) & ((1 << 64) - 1)
        lock = unsigned if unsigned < (1 << 63) else unsigned - (1 << 64)
        process = subprocess.Popen(self.psql(database), stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            process.stdin.write(f"BEGIN; SELECT pg_advisory_xact_lock({lock}); SELECT 'locked';\n".encode())
            process.stdin.flush()
            output, deadline = b"", time.monotonic() + 5
            while b"locked\n" not in output and time.monotonic() < deadline:
                if select.select([process.stdout], [], [], 0.1)[0]:
                    output += os.read(process.stdout.fileno(), 1024)
                require(process.poll() is None, "Lock holder exited before acquiring its lock")
            require(b"locked\n" in output, "Could not acquire the external advisory lock")
            yield
        finally:
            if process.poll() is None:
                process.stdin.write(b"ROLLBACK;\n\\q\n")
                process.stdin.flush()
            _, error = process.communicate(timeout=5)
            require(process.returncode == 0, "Advisory lock cleanup failed: " + error.decode())

    def timeouts(self, seller):
        shard = SHARDS[digest(seller["sellerId"]) % len(SHARDS)][0]
        key = next(f"locked-{index}" for index in range(100) if self.key_shard("US", f"locked-{index}") == shard)
        database = self.databases[shard]
        payload = {"companyName": "Blocked company", "region": "US"}
        with self.held_key(database, "US", key), ThreadPoolExecutor(max_workers=2) as threads:
            futures = [threads.submit(self.request, "POST", BASE, payload, key=key,
                                      expected=503, code="CATALOG_UNAVAILABLE") for _ in range(2)]
            deadline = time.monotonic() + 2
            while time.monotonic() < deadline:
                waiting = self.sql(f"SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND NOT granted "
                                   f"AND pid IN (SELECT pid FROM pg_stat_activity WHERE usename='{self.login}' AND datname='{database}')")
                if waiting == "2":
                    break
                threading.Event().wait(0.03)
            require(waiting == "2", "Both bounded pool connections did not reach the lock")
            _, _, elapsed = self.request("GET", BASE + "/" + seller["sellerId"], expected=503, code="CATALOG_UNAVAILABLE")
            require(elapsed < 4, "Pool acquisition exceeded the server deadline")
            connections = int(self.sql(f"SELECT count(*) FROM pg_stat_activity WHERE usename='{self.login}' AND datname='{database}'"))
            require(connections <= 2, "The configured per-endpoint pool limit was exceeded")
            for future in futures:
                require(future.result(timeout=5)[2] < 4, "Blocked database work exceeded the server deadline")
        deadline, recovery = time.monotonic() + 5, []
        while time.monotonic() < deadline:
            status, result, elapsed = self.request("GET", BASE + "/by-key/" + key + "?region=US", expected=(404, 503))
            recovery.append((status, round(elapsed, 3)))
            if status == 404:
                require(result["code"] == "SELLER_NOT_FOUND", "Unexpected recovery lookup outcome")
                break
            threading.Event().wait(0.2)
        else:
            raise AssertionError("The connection pool did not recover within five seconds: " + str(recovery))
        _, saved = self.create_retry(payload, key)
        require(self.request("POST", BASE, payload, key=key)[1] == saved, "Retry after timeout did not recover")

    def restart_and_outages(self, seller, product):
        seller_path = BASE + "/" + seller["sellerId"]
        item_path = seller_path + "/products"
        self.start()
        require(self.request("POST", BASE, {"companyName": seller["companyName"], "region": "US"}, key="shared-region-key")[1] == seller,
                "Restart changed persisted seller state")
        require(self.request("POST", item_path, ITEM, key="shared-item-key")[1] == product, "Restart changed persisted product state")
        self.clean_log()
        self.start(unavailable_primary=True)
        for path in (seller_path, BASE + "/by-key/shared-region-key?region=US", item_path + "/" + product["productId"]):
            require(self.request("GET", path, expected=503, code="CATALOG_UNAVAILABLE")[2] < 4, "Unavailable primary exceeded deadline")
        self.request("POST", BASE, {"companyName": "\u00a0", "region": "US"}, key="invalid-before-io", expected=400, code="INVALID_REQUEST")
        self.start(replica=True)
        for path in (seller_path, BASE + "/by-key/shared-region-key?region=US", item_path + "/by-key/shared-item-key"):
            require(self.request("GET", path, expected=503, code="READ_REPLICA_UNAVAILABLE")[2] < 4,
                    "Unavailable replica exceeded deadline or fell back to primary")
        written = self.request("POST", BASE, {"companyName": "Replica profile write", "region": "EU"},
                               key="replica-profile-write", expected=201)[1]
        self.start()
        require(self.request("GET", BASE + "/" + written["sellerId"])[1] == written, "Replica profile did not write to primary")

    def close(self):
        self.stop()
        for database in reversed(self.created_databases):
            self.sql(f"DROP DATABASE {database} WITH (FORCE)")
        for role in reversed(self.created_roles):
            self.sql(f"DROP ROLE {role}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("container")
    parser.add_argument("--jdbc-port", type=int)
    parser.add_argument("--java", default="java")
    args = parser.parse_args()
    require(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*", args.container), "Invalid container name")
    require((PRODUCT / "target/quarkus-app/quarkus-run.jar").is_file(), "Package shardshop-product before running this check")
    jdbc_port = args.jdbc_port
    if jdbc_port is None:
        published = subprocess.run(["docker", "port", args.container, "5432/tcp"],
                                   capture_output=True, text=True, check=True, timeout=10).stdout
        jdbc_port = int(published.splitlines()[0].rsplit(":", 1)[1])
    with tempfile.TemporaryDirectory(prefix="shardshop-product-provider-") as work:
        checks = ProviderChecks(args.container, jdbc_port, args.java, work)
        try:
            checks.setup()
            checks.start()
            seller, product = checks.happy_paths()
            checks.concurrency(seller)
            checks.invalid_requests(seller)
            checks.dropped_response()
            checks.slow_body()
            checks.oversized_requests()
            checks.timeouts(seller)
            checks.clean_log()
            checks.restart_and_outages(seller, product)
            print(f"Passed {checks.responses} OpenAPI-validated HTTP responses across four shards, concurrent retries, "
                  "lost responses, restarts, pool/deadline limits and primary/replica outages.")
        except BaseException:
            if checks.log is not None:
                checks.log.flush()
                checks.log.seek(0)
                print("Last provider log:\n" + checks.log.read()[-10000:])
            raise
        finally:
            checks.close()


if __name__ == "__main__":
    main()
