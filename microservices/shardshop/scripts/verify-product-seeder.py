#!/usr/bin/env python3
"""Run the packaged product seeder against the packaged product API on isolated databases.

Run with the contract-validation Python environment after you package product and the seeder:
  python -B scripts/verify-product-seeder.py CONTAINER [--jdbc-port PORT]

The disposable PostgreSQL container must publish port 5432 on localhost. The product
side, its four shards (two US) and the cleanup come from verify-product-provider.py.
Expected payloads come from the dataset TSVs, independently of the Java definitions.
Checks: a failed partial run, recovery after a product restart, an identical repeated
run, and a failed run when product cannot complete its read verification.
"""

import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time


ROOT = Path(__file__).resolve().parents[1]
SEEDER = ROOT / "shardshop-workload/shardshop-product-seeder/target/quarkus-app/quarkus-run.jar"
CATALOG = ROOT / "shardshop-workload/shardshop-datasets/src/main/resources/datasets/catalog-v1"
REGIONS = ("US", "EU", "ASIA")
SUMMARY = re.compile(r"Seeded and verified catalog-v1 \[(.*)\], key-to-ID SHA-256 ([0-9a-f]{64})$")
SNAPSHOT = """SELECT json_build_object(
  'sellers', (SELECT coalesce(json_agg(json_build_object('key', creation_key, 'sellerId', seller_id::text,
      'region', region, 'companyName', company_name)), '[]') FROM catalog.sellers),
  'products', (SELECT coalesce(json_agg(json_build_object('key', creation_key, 'sellerId', seller_id::text,
      'productId', product_id::text, 'name', name, 'description', description, 'price', price::text,
      'unitCost', unit_cost::text, 'currency', currency, 'initialStock', initial_stock)), '[]') FROM catalog.products),
  'profits', (SELECT coalesce(json_agg(json_build_object('sellerId', seller_id::text, 'currency', currency,
      'amount', amount::text)), '[]') FROM catalog.seller_profits))"""

spec = importlib.util.spec_from_file_location("provider_checks", ROOT / "scripts" / "verify-product-provider.py")
provider = importlib.util.module_from_spec(spec)
spec.loader.exec_module(provider)
require = provider.require


def dataset():
    """Return ordered seller and product definitions, written from the TSVs and the step 3.5 rules."""
    sellers, products = {}, {}
    for region in REGIONS:
        rows = (CATALOG / f"{region}.tsv").read_text(encoding="utf-8").splitlines()
        require(rows[0] == "companyName\tcountryCode\trank" and len(rows) == 51, f"Unexpected {region} catalog")
        for ordinal, row in enumerate(rows[1:], start=1):
            company, seller_key = row.split("\t")[0], f"catalog-v1.{region}.seller.{ordinal}"
            sellers[seller_key] = {"region": region, "companyName": company}
            for offset, currency in enumerate(("USD", "EUR"), start=1):
                products[f"catalog-v1.{region}.product.{2 * ordinal - 2 + offset}"] = {
                    "sellerKey": seller_key, "name": f"{company} Sample Product {currency}",
                    "description": f"Synthetic {currency} catalog item sold by {company}.",
                    "price": "19.95", "unitCost": "12.50", "currency": currency, "initialStock": 1000}
    return sellers, products


class SeederChecks:
    def __init__(self, checks, work):
        self.checks, self.work, self.runs, self.elapsed = checks, Path(work), 0, 0.0
        self.sellers, self.products = dataset()

    def seed(self, expected_exit, *properties):
        self.runs += 1
        environment = {key: value for key, value in os.environ.items()
                       if not key.startswith(("SHARDSHOP_", "QUARKUS_"))}
        command = [self.checks.java, f"-Dshardshop.seeder.product-url=http://127.0.0.1:{self.checks.port}",
                   "-Dquarkus.profile=prod", *("-Dshardshop.seeder." + item for item in properties), "-jar", str(SEEDER)]
        started = time.monotonic()
        result = subprocess.run(command, cwd=self.work, env=environment, capture_output=True, text=True, timeout=180)
        self.elapsed = time.monotonic() - started
        output = (result.stdout + result.stderr).splitlines()
        (self.work / f"seeder-{self.runs}.log").write_text("\n".join(output) + "\n")
        require(result.returncode == expected_exit,
                f"Seeder run {self.runs} exited with {result.returncode}:\n" + "\n".join(output[-20:]))
        problems = [line for line in output if re.search(r"\b(?:WARN|ERROR)\b", line)]
        if expected_exit == 0:
            require(not problems, "Unexpected seeder warnings/errors:\n" + "\n".join(problems))
            summaries = [match for line in output if (match := SUMMARY.search(line))]
            require(len(summaries) == 1, "The seeder did not log exactly one summary")
            return summaries[0]
        require(len(problems) == 1 and "ERROR" in problems[0], "A failed run must log exactly one error")
        return problems[0]

    def snapshot(self):
        state = {}
        for shard, _ in provider.SHARDS:
            rows = json.loads(self.checks.sql(SNAPSHOT, self.checks.databases[shard]))
            profits = {(row["sellerId"], row["currency"]): row["amount"] for row in rows["profits"]}
            for row in rows["sellers"] + rows["products"]:
                require(row["key"] not in state, "A creation key is stored more than once: " + row["key"])
                state[row["key"]] = dict(row, shard=shard)
            for row in rows["sellers"]:
                require({currency: profits.get((row["sellerId"], currency)) for currency in ("USD", "EUR")}
                        == {"USD": "0.00", "EUR": "0.00"}, "Seller profits are not zero USD/EUR balances")
            for row in rows["products"]:
                require(any(seller["sellerId"] == row["sellerId"] for seller in rows["sellers"]),
                        "A product is stored apart from its seller")
        return state

    def require_dataset(self, state, *, complete=True):
        for key, expected in self.sellers.items():
            if key in state:
                row = state[key]
                routed = provider.SHARDS[provider.digest(row["sellerId"]) % len(provider.SHARDS)][0]
                require({"region": row["region"], "companyName": row["companyName"]} == expected
                        and row["shard"] == routed == self.checks.key_shard(expected["region"], key),
                        "Seller payload, ID route or key placement differs: " + key)
        for key, expected in self.products.items():
            if key in state:
                row, seller = state[key], state[expected["sellerKey"]]
                require(row["sellerId"] == seller["sellerId"] and row["shard"] == seller["shard"]
                        and {name: row[name] for name in expected if name != "sellerKey"}
                        == {name: value for name, value in expected.items() if name != "sellerKey"},
                        "Product payload, seller binding or placement differs: " + key)
        require(set(state) <= set(self.sellers) | set(self.products), "Unexpected creation keys are stored")
        if complete:
            require(len(state) == 450, f"Expected 150 sellers and 300 products, found {len(state)} entities")

    def digest(self, state):
        lines = [f"{key}={state[key]['sellerId']}\n" for key in self.sellers]
        lines += [f"{key}={state[key]['sellerId']}/{state[key]['productId']}\n" for key in self.products]
        return hashlib.sha256("".join(lines).encode()).hexdigest()

    def run(self):
        blocked = "catalog-v1.EU.seller.10"
        database = self.checks.databases[self.checks.key_shard("EU", blocked)]
        with self.checks.held_key(database, "EU", blocked):
            error = self.seed(1, "max-attempts=2", "retry-backoff=100ms")
        require(error.endswith(f"Create seller {blocked} failed on attempt 2: HTTP 503 CATALOG_UNAVAILABLE"),
                "Unexpected partial-run failure: " + error)
        partial = self.snapshot()
        self.require_dataset(partial, complete=False)
        require(len(partial) == 59 + 118 and blocked not in partial, f"Unexpected partial state: {len(partial)} entities")

        self.checks.start()
        first = self.seed(0)
        first_elapsed = self.elapsed
        complete = self.snapshot()
        self.require_dataset(complete)
        require(all(complete[key] == row for key, row in partial.items()), "A partial-run entity changed on recovery")
        regions = "; ".join(f"{region} 50 sellers, 100 products {{USD=50, EUR=50}}" for region in REGIONS)
        require(first.group(1) == regions, "Unexpected regional summary: " + first.group(1))
        require(first.group(2) == self.digest(complete), "The logged key-to-ID digest differs from stored IDs")

        second = self.seed(0)
        require(self.snapshot() == complete and second.group(2) == first.group(2), "A repeated run changed IDs or rows")
        self.checks.clean_log()

        self.checks.start(replica=True)
        error = self.seed(1, "max-attempts=2", "retry-backoff=100ms")
        require(error.endswith("Read seller catalog-v1.US.seller.1 failed on attempt 2: HTTP 503 READ_REPLICA_UNAVAILABLE"),
                "Unexpected verification failure: " + error)
        require(self.snapshot() == complete, "A run without read verification changed stored rows")
        return first.group(2), first_elapsed


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("container")
    parser.add_argument("--jdbc-port", type=int)
    parser.add_argument("--java", default="java")
    args = parser.parse_args()
    require(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*", args.container), "Invalid container name")
    require((provider.PRODUCT / "target/quarkus-app/quarkus-run.jar").is_file(), "Package shardshop-product first")
    require(SEEDER.is_file(), "Package shardshop-product-seeder first")
    jdbc_port = args.jdbc_port
    if jdbc_port is None:
        published = subprocess.run(["docker", "port", args.container, "5432/tcp"],
                                   capture_output=True, text=True, check=True, timeout=10).stdout
        jdbc_port = int(published.splitlines()[0].rsplit(":", 1)[1])
    with tempfile.TemporaryDirectory(prefix="shardshop-product-seeder-") as work:
        checks = provider.ProviderChecks(args.container, jdbc_port, args.java, work)
        try:
            checks.setup()
            checks.start()
            digest, elapsed = SeederChecks(checks, work).run()
            print("Passed: a blocked partial run kept 177 issued entities; after a product restart, two complete "
                  f"runs stored and verified the same 150 sellers and 300 products (key-to-ID SHA-256 {digest}; "
                  f"the first complete JVM run took {elapsed:.1f} s); unavailable read verification failed the run "
                  "without changing rows.")
        except BaseException:
            for log in sorted(Path(work).glob("*.log")):
                print(f"Last lines of {log.name}:\n" + "\n".join(log.read_text().splitlines()[-20:]))
            raise
        finally:
            checks.close()


if __name__ == "__main__":
    main()
