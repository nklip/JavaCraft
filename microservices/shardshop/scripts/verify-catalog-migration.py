#!/usr/bin/env python3
"""Verify catalog V1 -> V2 against an already running, disposable PostgreSQL container.

Usage: python3 -B scripts/verify-catalog-migration.py CONTAINER
The container must permit local postgres access. The check creates and removes a
uniquely named database, and creates missing catalog group roles without changing
existing roles. A temporary role inherits catalog_reader and catalog_reserver,
matching order_app's catalog permissions. It does not connect to a deployed
ShardShop database.
"""

import argparse
import hashlib
from pathlib import Path
import re
import subprocess
import threading
import time
import uuid


ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / "database" / "shard" / "catalog"
V1_SHA256 = "baf6759eb9934ddd239b7da4413084293b69302be32442103863a089255871b7"
ROLES = ("catalog_reader", "catalog_writer", "catalog_reserver")


class Checks:
    def __init__(self, container):
        self.container = container
        self.database = "shardshop_catalog_v2_check_" + uuid.uuid4().hex
        self.order_role = "shardshop_order_check_" + uuid.uuid4().hex
        self.count = 0

    def sql(self, statement, *, database=None, state=None, expected=None):
        result = subprocess.run(
            ["docker", "exec", "-i", self.container, "env",
             "PGOPTIONS=-c statement_timeout=10000 -c lock_timeout=2000",
             "PGCONNECT_TIMEOUT=5", "psql", "-X", "-qAt",
             "-v", "ON_ERROR_STOP=1", "-v", "VERBOSITY=verbose",
             "-U", "postgres", "-d", database or self.database],
            input=statement, text=True, capture_output=True, timeout=20,
        )
        if state is not None:
            if result.returncode == 0 or not re.search(r"\b" + state + r"\b", result.stderr):
                raise AssertionError(f"Expected SQLSTATE {state}: {result.stderr}")
            self.count += 1
        elif result.returncode != 0:
            raise AssertionError(result.stderr)
        elif expected is not None:
            if result.stdout.strip() != expected:
                raise AssertionError(f"Expected {expected!r}, got {result.stdout.strip()!r}")
            self.count += 1
        return result.stdout.strip()

    def as_role(self, role, statement, **kwargs):
        return self.sql(f"SET ROLE {role};\n{statement}", **kwargs)

    def verify(self):
        v1 = (CATALOG / "V1__catalog.sql").read_bytes()
        if hashlib.sha256(v1).hexdigest() != V1_SHA256:
            raise AssertionError("The deployed V1 catalog migration changed")
        self.count += 1
        rendered = v1.decode().replace("${shardRegion}", "US")
        rendered = rendered.replace("${shardCount}", "1").replace("${shardIndex}", "0")
        self.sql("CREATE SCHEMA catalog;\n" + rendered)
        self.sql("""
            INSERT INTO catalog.sellers(seller_id, name) VALUES (1, 'Legacy company');
            INSERT INTO catalog.products(product_id, seller_id, name, price, currency, initial_stock, stock)
            VALUES (11, 1, 'Legacy item', 19.95, 'USD', 10, 8);
        """)
        self.sql("BEGIN;\n" + (CATALOG / "V2__catalog_creation.sql").read_text() + "\nCOMMIT;")
        grants = (CATALOG / "R__catalog_grants.sql").read_text()
        self.sql(grants)
        self.sql("""
            GRANT INSERT (amount), UPDATE (currency) ON catalog.seller_profits TO catalog_writer;
            GRANT SELECT ON catalog.seller_profits, catalog.profit_credits TO catalog_reserver;
            GRANT INSERT, UPDATE ON catalog.seller_profits TO catalog_reserver;
            GRANT INSERT (seller_id, currency), UPDATE (amount) ON catalog.seller_profits TO catalog_reserver;
            GRANT INSERT ON catalog.profit_credits TO catalog_reserver;
            GRANT INSERT (order_id, product_id, seller_id, currency, amount)
                ON catalog.profit_credits TO catalog_reserver;
            GRANT UPDATE (amount), INSERT (credited_at) ON catalog.profit_credits TO catalog_reserver;
        """)
        # Reproduce the previous privilege exposure through order_app's inherited groups.
        self.as_role(self.order_role, """
            BEGIN;
            UPDATE catalog.seller_profits SET amount = 5.00 WHERE seller_id = 1 AND currency = 'USD';
            INSERT INTO catalog.seller_profits(seller_id, currency) VALUES (1, 'JPY');
            INSERT INTO catalog.profit_credits(order_id, product_id, seller_id, currency, amount)
            VALUES (100, 11, 1, 'USD', 5.00);
            SELECT amount FROM catalog.seller_profits WHERE seller_id = 1 AND currency = 'USD';
            SELECT count(*) FROM catalog.seller_profits WHERE seller_id = 1 AND currency = 'JPY';
            SELECT count(*) FROM catalog.profit_credits WHERE order_id = 100;
            ROLLBACK;
        """, expected="5.00\n1\n1")
        self.sql(grants)  # Reapplying must also remove obsolete column-level privileges.
        self.as_role("catalog_reader", """
            SELECT company_name, creation_key IS NULL FROM catalog.sellers WHERE seller_id = 1;
            SELECT description, unit_cost, initial_stock, stock, creation_key IS NULL
            FROM catalog.products WHERE product_id = 11;
            SELECT currency, amount FROM catalog.seller_profits WHERE seller_id = 1 ORDER BY currency;
        """, expected="Legacy company|t\nLegacy item|0.00|10|8|t\nEUR|0.00\nUSD|0.00")
        self.as_role("catalog_writer", """
            BEGIN;
            INSERT INTO catalog.sellers(seller_id, name, creation_key)
            VALUES (2, 'Current company', 'catalog-v1.US.seller.1'), (3, 'Other company', 'other');
            INSERT INTO catalog.seller_profits(seller_id, currency)
            VALUES (2, 'USD'), (2, 'EUR'), (3, 'USD'), (3, 'EUR');
            INSERT INTO catalog.products
                (product_id, seller_id, name, description, price, unit_cost, currency,
                 initial_stock, stock, creation_key)
            VALUES (12, 2, 'Current item', 'A complete description', 19.95, 12.50, 'USD', 3, 3, 'item.1'),
                   (13, 3, 'Other item', 'Another complete description', 19.95, 12.50, 'EUR', 3, 3, 'item.1');
            COMMIT;
        """)
        self.as_role("catalog_writer", """
            SELECT seller_id, company_name FROM catalog.sellers
            WHERE region = 'US' AND creation_key = 'catalog-v1.US.seller.1';
            SELECT product_id FROM catalog.products WHERE seller_id = 2 AND creation_key = 'item.1';
            SELECT product_id FROM catalog.products WHERE seller_id = 3 AND creation_key = 'item.1';
        """, expected="2|Current company\n12\n13")
        self.as_role("catalog_writer", """
            INSERT INTO catalog.sellers(seller_id, name, creation_key)
            VALUES (4, 'Duplicate company', 'catalog-v1.US.seller.1');
        """, state="23505")
        self.as_role("catalog_writer", """
            INSERT INTO catalog.products
                (product_id, seller_id, name, price, currency, initial_stock, stock, creation_key)
            VALUES (14, 2, 'Duplicate item', 1.00, 'USD', 1, 1, 'item.1');
        """, state="23505")
        self.as_role("catalog_writer", """
            INSERT INTO catalog.sellers(seller_id, name) VALUES (4, 'Legacy second company');
            INSERT INTO catalog.products(product_id, seller_id, name, price, currency, initial_stock, stock)
            VALUES (14, 4, 'Legacy second item', 1.00, 'USD', 1, 1);
            SELECT description, unit_cost FROM catalog.products WHERE product_id = 14;
        """, expected="Legacy product|0.00")
        for key in ("", "bad key", "-bad", "bad\n", "é"):
            self.as_role("catalog_writer", f"""
                INSERT INTO catalog.sellers(seller_id, name, creation_key)
                VALUES (50, 'Rejected company', '{key}');
            """, state="23514")
            self.as_role("catalog_writer", f"""
                INSERT INTO catalog.products
                    (product_id, seller_id, name, price, currency, initial_stock, stock, creation_key)
                VALUES (50, 2, 'Rejected item', 1.00, 'USD', 1, 1, '{key}');
            """, state="23514")
        self.as_role("catalog_writer", """
            INSERT INTO catalog.sellers(seller_id, name, company_name)
            VALUES (50, 'Rejected company', 'Separate company');
        """, state="428C9")
        for table, columns, values in (
                ("sellers", "seller_id, name, creation_key", "50, 'Boundary company'"),
                ("products", "product_id, seller_id, name, price, currency, initial_stock, stock, creation_key",
                 "50, 2, 'Boundary item', 1.00, 'USD', 1, 1")):
            self.as_role("catalog_writer", f"INSERT INTO catalog.{table}({columns}) "
                         f"VALUES ({values}, '{'K' * 129}')", state="22001")
            self.as_role("catalog_writer", f"INSERT INTO catalog.{table}({columns}) "
                         f"VALUES ({values}, '{'K' * 128}'); "
                         f"SELECT length(creation_key) FROM catalog.{table} WHERE creation_key = '{'K' * 128}'",
                         expected="128")
        for description, cost, state in (("''", "0.00", "23514"), ("NULL", "0.00", "23502"),
                                         ("'Valid'", "-0.01", "23514"), ("'Valid'", "'NaN'", "23514")):
            self.as_role("catalog_writer", f"""
                INSERT INTO catalog.products
                    (product_id, seller_id, name, description, price, unit_cost, currency, initial_stock, stock)
                VALUES (51, 2, 'Rejected item', {description}, 1.00, {cost}, 'USD', 1, 1);
            """, state=state)
        self.as_role("catalog_writer", """
            BEGIN;
            INSERT INTO catalog.sellers(seller_id, name, creation_key) VALUES (99, 'Rolled back', 'rollback');
            INSERT INTO catalog.seller_profits(seller_id, currency) VALUES (99, 'USD'), (99, 'EUR');
            INSERT INTO catalog.sellers(seller_id, name, creation_key)
            VALUES (100, 'Duplicate company', 'catalog-v1.US.seller.1');
            COMMIT;
        """, state="23505")
        self.sql("SELECT count(*) FROM catalog.sellers WHERE seller_id = 99; "
                 "SELECT count(*) FROM catalog.seller_profits WHERE seller_id = 99;", expected="0\n0")
        # Future credit schema constraints remain testable as the migration owner;
        # no application role may execute the credit protocol before PLAN step 4.6.
        self.sql("""
            BEGIN;
            INSERT INTO catalog.profit_credits(order_id, product_id, seller_id, currency, amount)
            VALUES (101, 12, 2, 'USD', -2.50);
            UPDATE catalog.seller_profits SET amount = amount - 2.50 WHERE seller_id = 2 AND currency = 'USD';
            INSERT INTO catalog.seller_profits(seller_id, currency) VALUES (2, 'JPY');
            COMMIT;
        """)
        self.as_role("catalog_writer", "SELECT currency, amount FROM catalog.seller_profits "
                     "WHERE seller_id = 2 ORDER BY currency;", expected="EUR|0.00\nJPY|0.00\nUSD|-2.50")
        for order, product, seller, currency, amount, state in (
                (101, 12, 2, "USD", "-2.50", "23505"),
                (102, 12, 3, "USD", "1.00", "23503"),
                (102, 12, 2, "EUR", "1.00", "23503"),
                (102, 999, 2, "USD", "1.00", "23503"),
                (0, 12, 2, "USD", "1.00", "23514"),
                (102, 12, 2, "USD", "'NaN'", "23514")):
            self.sql(f"""
                INSERT INTO catalog.profit_credits(order_id, product_id, seller_id, currency, amount)
                VALUES ({order}, {product}, {seller}, '{currency}', {amount});
            """, state=state)
        for statement in (
                "INSERT INTO catalog.seller_profits(seller_id, currency, amount) VALUES (2, 'GBP', 100)",
                "UPDATE catalog.seller_profits SET amount = 0 WHERE seller_id = 2",
                "DELETE FROM catalog.seller_profits WHERE seller_id = 2",
                "TRUNCATE catalog.seller_profits",
                "UPDATE catalog.seller_profits SET currency = 'GBP' WHERE seller_id = 2",
                "UPDATE catalog.products SET stock = 3 WHERE product_id = 12",
                "DELETE FROM catalog.sellers WHERE seller_id = 2",
                "UPDATE catalog.sellers SET creation_key = 'changed' WHERE seller_id = 2",
                "SELECT * FROM catalog.profit_credits"):
            self.as_role("catalog_writer", statement, state="42501")
        profit_mutations = (
                "UPDATE catalog.seller_profits SET amount = 0 WHERE seller_id = 2",
                "INSERT INTO catalog.seller_profits(seller_id, currency) VALUES (2, 'GBP')",
                "INSERT INTO catalog.profit_credits(order_id, product_id, seller_id, currency, amount) "
                "VALUES (102, 12, 2, 'USD', 1.00)",
                "SELECT * FROM catalog.profit_credits",
                "UPDATE catalog.profit_credits SET amount = 100 WHERE order_id = 101",
                "DELETE FROM catalog.profit_credits WHERE order_id = 101",
                "TRUNCATE catalog.profit_credits",
                "DELETE FROM catalog.seller_profits WHERE seller_id = 2",
                "UPDATE catalog.seller_profits SET currency = 'GBP' WHERE seller_id = 2",
                "INSERT INTO catalog.seller_profits(seller_id, currency, amount) VALUES (2, 'GBP', 100)",
                "INSERT INTO catalog.profit_credits(order_id, product_id, seller_id, currency, amount, credited_at) "
                "VALUES (102, 12, 2, 'USD', 1.00, CURRENT_TIMESTAMP)")
        for role in ("catalog_reserver", self.order_role):
            for statement in profit_mutations:
                self.as_role(role, statement, state="42501")
        self.as_role("catalog_reserver", "SELECT * FROM catalog.seller_profits", state="42501")
        self.as_role(self.order_role, "SELECT currency, amount FROM catalog.seller_profits "
                     "WHERE seller_id = 2 ORDER BY currency;", expected="EUR|0.00\nJPY|0.00\nUSD|-2.50")
        self.sql("UPDATE catalog.seller_profits SET amount = 'NaN' "
                 "WHERE seller_id = 2 AND currency = 'USD'", state="23514")
        self.as_role(self.order_role, """
            BEGIN;
            UPDATE catalog.products SET stock = stock - 1 WHERE product_id = 12;
            INSERT INTO catalog.stock_reservations(order_id, product_id, quantity) VALUES (201, 12, 1);
            SELECT stock FROM catalog.products WHERE product_id = 12;
            SELECT quantity FROM catalog.stock_reservations WHERE order_id = 201 AND product_id = 12;
            UPDATE catalog.stock_reservations SET released_at = CURRENT_TIMESTAMP
            WHERE order_id = 201 AND product_id = 12;
            SELECT released_at IS NOT NULL FROM catalog.stock_reservations
            WHERE order_id = 201 AND product_id = 12;
            DELETE FROM catalog.stock_reservations WHERE order_id = 201 AND product_id = 12;
            UPDATE catalog.products SET stock = stock + 1 WHERE product_id = 12;
            COMMIT;
        """, expected="2\n1\nt")
        self.as_role("catalog_reader", "INSERT INTO catalog.seller_profits(seller_id, currency) "
                     "VALUES (2, 'GBP')", state="42501")
        self.as_role("catalog_reader", "SELECT * FROM catalog.profit_credits", state="42501")
        self.sql("UPDATE catalog.products SET stock = 1 WHERE product_id = 12")
        self.as_role("catalog_writer", "SELECT initial_stock, stock FROM catalog.products "
                     "WHERE seller_id = 2 AND creation_key = 'item.1'", expected="3|1")
        self.sql("SELECT count(*) FROM pg_proc WHERE pronamespace = 'catalog'::regnamespace; "
                 "SELECT count(*) FROM pg_trigger WHERE tgrelid IN "
                 "(SELECT oid FROM pg_class WHERE relnamespace = 'catalog'::regnamespace) "
                 "AND NOT tgisinternal;", expected="0\n0")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("container", help="Already running disposable PostgreSQL container")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*", args.container):
        parser.error("Invalid Docker container name")
    checks = Checks(args.container)
    created_roles = []
    created_database = False
    try:
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            ready = subprocess.run(["docker", "exec", args.container, "pg_isready", "-U", "postgres", "-t", "1"],
                                   capture_output=True, timeout=3)
            if ready.returncode == 0:
                break
            threading.Event().wait(0.2)
        else:
            raise AssertionError("PostgreSQL did not become ready within 20 seconds")
        existing_roles = checks.sql("SELECT rolname FROM pg_roles", database="postgres").splitlines()
        for role in ROLES:
            if role not in existing_roles:
                checks.sql(f"CREATE ROLE {role} NOLOGIN", database="postgres")
                created_roles.append(role)
        checks.sql(f"CREATE ROLE {checks.order_role} NOLOGIN IN ROLE catalog_reader, catalog_reserver",
                   database="postgres")
        created_roles.append(checks.order_role)
        checks.sql(f"CREATE DATABASE {checks.database}", database="postgres")
        created_database = True
        checks.verify()
        print(f"Passed {checks.count} catalog migration, creation-key, profit identity and privilege checks.")
    finally:
        if created_database:
            checks.sql(f"DROP DATABASE {checks.database} WITH (FORCE)", database="postgres")
        for role in reversed(created_roles):
            checks.sql(f"DROP ROLE {role}", database="postgres")


if __name__ == "__main__":
    main()
