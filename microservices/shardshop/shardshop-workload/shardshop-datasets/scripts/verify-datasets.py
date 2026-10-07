#!/usr/bin/env python3
"""Validate retained workload fixtures using the existing contract-validation environment."""

import csv
import hashlib
import importlib.util
import json
import re
import sys
from collections import Counter
from pathlib import Path

# Dynamic loading below must not leave bytecode beside the repository's scripts.
sys.dont_write_bytecode = True

import yaml

MODULE = Path(__file__).resolve().parents[1]
ROOT = MODULE.parents[1]


def rows(path):
    with path.open(encoding="utf-8", newline="") as source:
        return list(csv.DictReader(source, delimiter="\t"))


def main():
    spec = importlib.util.spec_from_file_location("contracts", ROOT / "scripts/verify-contracts.py")
    contracts = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(contracts)
    product = yaml.safe_load((ROOT / "shardshop-product/src/main/resources/contracts/openapi.yaml").read_text())
    order = yaml.safe_load((ROOT / "shardshop-order/src/main/resources/contracts/openapi.yaml").read_text())
    seller_schema = contracts.validator(product["components"]["schemas"]["CreateSeller"], product)
    product_schema = contracts.validator(product["components"]["schemas"]["CreateProduct"], product)
    buyer_schema = contracts.validator(order["components"]["schemas"]["BuyerCreation"], order)
    emails, phones = set(), set()
    counts = Counter()
    countries = {"US": {"US"}, "EU": set("AT BE BG HR CY CZ DK EE FI FR DE GR HU IE IT LV LT LU MT NL PL PT RO SK SI ES SE".split()),
                 "ASIA": {"CN", "VN", "KR", "JP"}}
    for region in countries:
        pools = {}
        for kind in ("given", "surname"):
            pool = rows(MODULE / f"sources/names/{region}-{kind}.tsv")
            assert pool
            if region != "ASIA":
                assert len(pool) == 1000
            assert len({(r["countryCode"], r["name"]) for r in pool}) == len(pool)
            assert all(r["countryCode"] in countries[region] for r in pool)
            if region == "ASIA":
                assert {r["countryCode"] for r in pool} == countries[region]
                assert all(r["source"].startswith("faker-") and not r["sourceRank"] and not r["sourceCount"] for r in pool)
            assert all(re.fullmatch("[a-z0-9]+", r["emailPart"]) for r in pool)
            pools[kind] = pool
        sellers = rows(MODULE / f"src/main/resources/datasets/catalog-v1/{region}.tsv")
        assert len(sellers) == len({r["companyName"] for r in sellers}) == 50
        for seller in sellers:
            seller_schema.validate({"companyName": seller["companyName"], "region": region})
            assert seller["countryCode"] in countries[region]
            counts["sellers"] += 1
            for currency in ("USD", "EUR"):
                product_schema.validate({"name": f"{seller['companyName']} Sample Product {currency}",
                                         "description": f"Synthetic {currency} catalog item sold by {seller['companyName']}.",
                                         "price": "19.95", "unitCost": "12.50", "currency": currency, "initialStock": 1000})
                counts["products"] += 1
        buyers = rows(MODULE / f"src/main/resources/datasets/buyers-v1/{region}.tsv")
        assert len(buyers) == 1000
        distribution = Counter(r["countryCode"] for r in buyers)
        assert set(distribution) == countries[region]
        if region == "ASIA":
            assert set(distribution.values()) == {250}
        for buyer in buyers:
            payload = {key: buyer[key] for key in ("firstName", "surname", "email", "phone")}
            payload["region"] = region
            payload["address"] = {key: buyer[key] for key in ("line1", "city", "postalCode", "countryCode")}
            buyer_schema.validate(payload)
            parts = buyer["email"].split("@")[0].split(".")
            assert len(parts) == 2
            for kind, field, part in zip(("given", "surname"), ("firstName", "surname"), parts):
                assert any(r["name"] == buyer[field] and r["emailPart"] == part
                           and (region != "ASIA" or r["countryCode"] == buyer["countryCode"]) for r in pools[kind])
            assert buyer["email"].split("@")[1] in {"example.com", "example.net", "example.org"}
            assert re.fullmatch(r"\+120255501[0-9]{2}", buyer["phone"])
            assert buyer["email"] not in emails
            emails.add(buyer["email"])
            phones.add(buyer["phone"])
            counts["buyers"] += 1
    assert len(phones) == 100
    manifest = json.loads((MODULE / "source-manifest.json").read_text())
    for path, expected in manifest["retainedFiles"].items():
        assert hashlib.sha256((MODULE / path).read_bytes()).hexdigest() == expected, path
    print(f"Passed: {counts['sellers']} sellers, {counts['products']} product payloads, "
          f"{counts['buyers']} buyers, regional/name/email checks and {len(manifest['retainedFiles'])} retained hashes.")


if __name__ == "__main__":
    main()
