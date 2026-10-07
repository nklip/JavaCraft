#!/usr/bin/env python3
"""Reproduce buyers-v1 from the checked-in, attributed name pools; no network or IDs."""

import csv
import hashlib
from pathlib import Path

MODULE = Path(__file__).resolve().parents[1]
VERSION = "buyers-v1"
DOMAINS = ("example.com", "example.net", "example.org")
HEADER = ["firstName", "surname", "email", "phone", "line1", "city", "postalCode", "countryCode"]


def read_rows(path):
    with path.open(encoding="utf-8", newline="") as source:
        return list(csv.DictReader(source, delimiter="\t"))


def number(label):
    return int.from_bytes(hashlib.sha256(f"{VERSION}:{label}".encode()).digest(), "big")


def generate():
    places = {row["countryCode"]: row for row in read_rows(MODULE / "sources/address-patterns.tsv")}
    emails = set()
    for region in ("US", "EU", "ASIA"):
        first_names = read_rows(MODULE / f"sources/names/{region}-given.tsv")
        surnames = read_rows(MODULE / f"sources/names/{region}-surname.tsv")
        if not first_names or not surnames:
            raise ValueError("Regional name pools must not be empty")
        countries = {"US": ["US"], "EU": sorted(set(places) - {"US", "CN", "JP", "KR", "VN"}),
                     "ASIA": ["CN", "VN", "KR", "JP"]}[region]
        rows = []
        for index in range(1000):
            country = countries[index % len(countries)]
            # EU is one market: names and addresses are independently combined across EU27.
            given_pool = [r for r in first_names if r["countryCode"] == country] if region == "ASIA" else first_names
            surname_pool = [r for r in surnames if r["countryCode"] == country] if region == "ASIA" else surnames
            if not given_pool or not surname_pool:
                raise ValueError("Every selected country needs given names and surnames")
            ordinal = index // len(countries) if region == "ASIA" else index
            given = given_pool[ordinal % len(given_pool)]
            start = number(f"{region}:{index}:surname") % len(surname_pool)
            for offset in range(len(DOMAINS) * len(surname_pool)):
                surname = surname_pool[(start + offset) % len(surname_pool)]
                domain = DOMAINS[(ordinal + offset // len(surname_pool)) % len(DOMAINS)]
                email = f"{given['emailPart']}.{surname['emailPart']}@{domain}"
                if email not in emails:
                    break
            else:
                raise ValueError("Name pool cannot provide another unique email")
            emails.add(email)
            place = places[country]
            # NANPA reserves 555-0100 through 555-0199 as fictitious, non-working numbers.
            # Reuse this finite range across regions; phone is not an entity identity.
            phone = f"+120255501{number(f'{region}:{index}:phone') % 100:02d}"
            rows.append([given["name"], surname["name"], email, phone,
                         f"{index + 1} {place['street']}", place["city"], place["postalCode"], country])
        target = MODULE / f"src/main/resources/datasets/{VERSION}/{region}.tsv"
        target.parent.mkdir(parents=True, exist_ok=True)
        with target.open("w", encoding="utf-8", newline="") as output:
            writer = csv.writer(output, delimiter="\t", lineterminator="\n")
            writer.writerow(HEADER)
            writer.writerows(rows)


if __name__ == "__main__":
    generate()
