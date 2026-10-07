#!/usr/bin/env python3
"""Qualify PostgreSQL TLS and pool recovery using an owned disposable container.

After packaging product, run in the contract-validation Python environment:
  python -B scripts/verify-product-transport.py

Requires Docker and OpenSSL. Uses the parent POM's digest-pinned PostgreSQL image,
ephemeral loopback ports and temporary certificates. It does not modify the lab's
containers or databases. This verifies PostgreSQL TLS, not HTTPS or CNPG promotion.
"""

import argparse
import importlib.util
from pathlib import Path
import shutil
import subprocess
import tempfile
import threading
import time
import uuid
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("product_provider_checks", ROOT / "scripts/verify-product-provider.py")
PROVIDER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PROVIDER)


def run(arguments, *, timeout=20):
    result = subprocess.run(arguments, capture_output=True, text=True, timeout=timeout)
    PROVIDER.require(result.returncode == 0,
                     "Transport verification command failed: " + arguments[0] + "\n" + result.stderr[-4000:])
    return result.stdout.strip()


def postgres_image():
    pom = ET.parse(ROOT / "pom.xml").getroot()
    image = pom.findtext("{*}properties/{*}tc.image.postgresql")
    PROVIDER.require(image is not None and "@sha256:" in image, "The PostgreSQL image must be digest-pinned")
    return image


def certificates(work):
    ca = work / "ca.crt"
    ca_key = work / "ca.key"
    server = work / "server.crt"
    server_key = work / "server.key"
    request = work / "server.csr"
    wrong_ca = work / "untrusted-ca.crt"
    run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-sha256", "-days", "2",
         "-keyout", str(ca_key), "-out", str(ca), "-subj", "/CN=ShardShop temporary test CA",
         "-addext", "basicConstraints=critical,CA:TRUE"])
    run(["openssl", "req", "-new", "-newkey", "rsa:2048", "-nodes", "-sha256",
         "-keyout", str(server_key), "-out", str(request), "-subj", "/CN=localhost"])
    extensions = work / "server.ext"
    # Deliberately omit an IP SAN: localhost succeeds and 127.0.0.1 must fail verification.
    extensions.write_text("subjectAltName=DNS:localhost\nextendedKeyUsage=serverAuth\n"
                          "keyUsage=digitalSignature,keyEncipherment\nbasicConstraints=CA:FALSE\n")
    run(["openssl", "x509", "-req", "-in", str(request), "-CA", str(ca), "-CAkey", str(ca_key),
         "-CAcreateserial", "-out", str(server), "-days", "2", "-sha256", "-extfile", str(extensions)])
    run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-sha256", "-days", "2",
         "-keyout", str(work / "untrusted-ca.key"), "-out", str(wrong_ca),
         "-subj", "/CN=ShardShop unrelated temporary CA", "-addext", "basicConstraints=critical,CA:TRUE"])
    for key in (ca_key, server_key, work / "untrusted-ca.key"):
        key.chmod(0o600)
    return ca, wrong_ca, server, server_key


def wait_postgres(container):
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        result = subprocess.run(["docker", "exec", container, "pg_isready", "-U", "postgres"],
                                capture_output=True, timeout=5)
        if result.returncode == 0:
            return
        threading.Event().wait(0.1)
    raise AssertionError("Disposable PostgreSQL did not become ready within 20 seconds")


def configure_tls(checks, server, server_key):
    for path in (server, server_key):
        run(["docker", "cp", str(path), checks.container + ":/tmp/" + path.name])
    run(["docker", "exec", "--user", "root", checks.container, "chown", "postgres:postgres",
         "/tmp/server.crt", "/tmp/server.key"])
    run(["docker", "exec", "--user", "root", checks.container, "chmod", "600", "/tmp/server.key"])
    checks.sql("ALTER SYSTEM SET ssl = 'on'; "
               "ALTER SYSTEM SET ssl_cert_file = '/tmp/server.crt'; "
               "ALTER SYSTEM SET ssl_key_file = '/tmp/server.key'; SELECT pg_reload_conf()")
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        if checks.sql("SHOW ssl") == "on":
            return
        threading.Event().wait(0.05)
    raise AssertionError("PostgreSQL did not enable TLS after configuration reload")


def verify_transport(checks, ca, wrong_ca):
    payload = {"companyName": "Synthetic TLS verification company", "region": "US"}
    key = "tls-and-restart"
    checks.start(jdbc_host="localhost", jdbc_options="", root_certificate=ca)
    seller = checks.request("POST", PROVIDER.BASE, payload, key=key, expected=201)[1]
    path = PROVIDER.BASE + "/" + seller["sellerId"]
    PROVIDER.require(checks.request("GET", path)[1] == seller, "TLS seller read changed the entity")
    encrypted = checks.sql("SELECT count(*) > 0 AND bool_and(ssl) FROM pg_stat_ssl "
                           "JOIN pg_stat_activity USING (pid) WHERE usename = '" + checks.login + "'")
    PROVIDER.require(encrypted == "t", "Product connections did not actually negotiate PostgreSQL TLS")
    checks.oversized_requests()
    checks.clean_log()

    checks.start(jdbc_host="localhost", jdbc_options="", root_certificate=wrong_ca)
    elapsed = checks.request("GET", path, expected=503, code="CATALOG_UNAVAILABLE")[2]
    PROVIDER.require(elapsed < 4, "Untrusted PostgreSQL certificate exceeded the server deadline")
    checks.clean_log()

    checks.start(jdbc_host="127.0.0.1", jdbc_options="", root_certificate=ca)
    elapsed = checks.request("GET", path, expected=503, code="CATALOG_UNAVAILABLE")[2]
    PROVIDER.require(elapsed < 4, "PostgreSQL hostname verification exceeded the server deadline")
    checks.clean_log()

    checks.start(jdbc_host="localhost", jdbc_options="", root_certificate=ca)
    PROVIDER.require(checks.request("GET", path)[1] == seller, "Trusted TLS control failed after negative checks")
    process_id = checks.server.pid
    run(["docker", "stop", "--time", "10", checks.container])
    elapsed = checks.request("GET", path, expected=503, code="CATALOG_UNAVAILABLE")[2]
    PROVIDER.require(elapsed < 4, "Stopped PostgreSQL exceeded the server deadline")
    run(["docker", "start", checks.container])
    published = run(["docker", "port", checks.container, "5432/tcp"])
    PROVIDER.require(int(published.splitlines()[0].rsplit(":", 1)[1]) == checks.jdbc_port,
                     "Restart changed the database endpoint under test")
    wait_postgres(checks.container)
    deadline = time.monotonic() + 8
    while time.monotonic() < deadline:
        status, recovered, elapsed = checks.request("GET", path, expected=(200, 503))
        PROVIDER.require(elapsed < 4, "Reconnect attempt exceeded the server deadline")
        if status == 200:
            PROVIDER.require(recovered == seller, "PostgreSQL restart changed the stored entity")
            break
        threading.Event().wait(0.1)
    else:
        raise AssertionError("The pool did not reconnect after PostgreSQL restarted")
    PROVIDER.require(checks.server.pid == process_id and checks.server.poll() is None,
                     "Recovery must keep the original product process running")
    PROVIDER.require(checks.request("POST", PROVIDER.BASE, payload, key=key)[1] == seller,
                     "Creation retry after PostgreSQL restart changed the saved identity")
    encrypted = checks.sql("SELECT count(*) > 0 AND bool_and(ssl) FROM pg_stat_ssl "
                           "JOIN pg_stat_activity USING (pid) WHERE usename = '" + checks.login + "'")
    PROVIDER.require(encrypted == "t", "Reconnected product pool lost TLS")
    checks.clean_log()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", default="java")
    args = parser.parse_args()
    PROVIDER.require(shutil.which("openssl") is not None, "OpenSSL is required for temporary test certificates")
    PROVIDER.require((PROVIDER.PRODUCT / "target/quarkus-app/quarkus-run.jar").is_file(),
                     "Package shardshop-product before running this check")
    container = "shardshop-transport-" + uuid.uuid4().hex[:12]
    created = False
    checks = None
    with tempfile.TemporaryDirectory(prefix="shardshop-product-transport-") as directory:
        work = Path(directory)
        try:
            ca, wrong_ca, server, server_key = certificates(work)
            # Pin this temporary port explicitly: Docker may reassign an automatic port on restart.
            port = PROVIDER.free_port()
            created = True  # Also clean up if docker run times out after creating the container.
            run(["docker", "run", "--detach", "--name", container, "--publish", f"127.0.0.1:{port}:5432",
                 "--env", "POSTGRES_PASSWORD=" + uuid.uuid4().hex, postgres_image()], timeout=60)
            published = run(["docker", "port", container, "5432/tcp"])
            PROVIDER.require(int(published.splitlines()[0].rsplit(":", 1)[1]) == port,
                             "Docker did not bind the requested temporary port")
            wait_postgres(container)
            checks = PROVIDER.ProviderChecks(container, port, args.java, work)
            checks.setup()
            configure_tls(checks, server, server_key)
            verify_transport(checks, ca, wrong_ca)
            print(f"Passed {checks.responses} OpenAPI-validated HTTP responses: PostgreSQL verify-full TLS, "
                  "untrusted CA rejection, hostname rejection, bounded outage and pool reconnection "
                  "after a database restart without restarting product.")
        except BaseException:
            if checks is not None and checks.log is not None:
                checks.log.flush()
                checks.log.seek(0)
                print("Last provider log:\n" + checks.log.read()[-10000:])
            raise
        finally:
            try:
                if checks is not None:
                    checks.stop()
            finally:
                if created:
                    removed = subprocess.run(["docker", "rm", "--force", "--volumes", container],
                                             capture_output=True, text=True, timeout=20)
                    PROVIDER.require(removed.returncode == 0 or "No such container" in removed.stderr,
                                     "Could not remove the owned transport test container: " + removed.stderr)


if __name__ == "__main__":
    main()
