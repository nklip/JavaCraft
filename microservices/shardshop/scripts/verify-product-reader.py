#!/usr/bin/env python3
"""Run the packaged product reader against the packaged product API on isolated databases.

Run with the contract-validation Python environment after you package product, the seeder and the reader:
  python -B scripts/verify-product-reader.py CONTAINER [--jdbc-port PORT]

The disposable PostgreSQL container must publish port 5432 on localhost. The product side, its four
shards (two US) and the cleanup come from verify-product-provider.py; verify-product-seeder.py seeds
catalog-v1 first. The reader uses its default load and connection policy, with 5-second reports.
Checks: a failed startup without read replicas; resolution of the seeded IDs; the request rate; 16
HTTP/1.1 connections that rotate after their lifetime; the 5-second deadline while product is paused;
refused connections and replica unavailability while product restarts; recovery; read-only rows; and
the totals after SIGTERM. lsof counts the reader's TCP connections and their rotation.
"""

import argparse
import importlib.util
import os
from pathlib import Path
import re
import signal
import subprocess
import tempfile
import time


ROOT = Path(__file__).resolve().parents[1]
READER = ROOT / "shardshop-workload/shardshop-product-reader/target/quarkus-app/quarkus-run.jar"
REPORT = re.compile(r"\b(INFO|WARN) .*Product reads, (interval|total) ([0-9.]+) s: (\d+) requests, ([0-9.]+)/s, "
                    r"outcomes \{(.*?)\}, latency ms \{(.*?)\}, regions \{(.*?)\}, pool \{(.*?)\}$")
RESOLVED = re.compile(r"Resolved catalog-v1 \[(.*)\], key-to-ID SHA-256 ([0-9a-f]{64})$")
READING = "Reading at 200 requests/s with 16 workers and 16 connections"
FAILURES = {"NOT_FOUND", "REPLICA_UNAVAILABLE", "UNAVAILABLE", "HTTP_ERROR", "INVALID_RESPONSE", "DEADLINE",
            "TRANSPORT"}


def load(name, file):
    spec = importlib.util.spec_from_file_location(name, ROOT / "scripts" / file)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


provider = load("provider_checks", "verify-product-provider.py")
seeder_checks = load("seeder_checks", "verify-product-seeder.py")
require = provider.require


def counts(text):
    return {name: int(value) for name, value in re.findall(r"(\w+)=(\d+)", text)}


def report(line):
    match = REPORT.search(line)
    if match is None:
        return None
    level, scope, seconds, requests, rate, outcomes, latency, regions, pool = match.groups()
    return {"level": level, "scope": scope, "seconds": float(seconds), "requests": int(requests),
            "rate": float(rate), "outcomes": counts(outcomes),
            "latency": counts(latency), "regions": counts(regions), "pool": counts(pool)}


def total(reports, field):
    summed = {}
    for item in reports:
        for name, value in item[field].items():
            summed[name] = summed.get(name, 0) + value
    return summed


class Reader:
    def __init__(self, checks, work, name):
        self.checks, self.log_path = checks, Path(work) / f"{name}.log"
        environment = {key: value for key, value in os.environ.items()
                       if not key.startswith(("SHARDSHOP_", "QUARKUS_"))}
        self.log = self.log_path.open("w")
        self.process = subprocess.Popen(
            [checks.java, f"-Dshardshop.reader.product-url=http://127.0.0.1:{checks.port}",
             "-Dshardshop.reader.report-interval=5s", "-Dquarkus.profile=prod", "-jar", str(READER)],
            cwd=work, env=environment, stdout=self.log, stderr=subprocess.STDOUT)

    def lines(self):
        return self.log_path.read_text().splitlines()

    def reports(self, start=0):
        return [item for line in self.lines()[start:] if (item := report(line)) is not None]

    def wait(self, condition, seconds, message):
        deadline = time.monotonic() + seconds
        while not condition():
            require(time.monotonic() < deadline, message)
            require(self.process.poll() is None, f"The reader exited with {self.process.returncode}")
            time.sleep(0.1)

    def connections(self, warm):
        """Local ports of the reader's established TCP connections to product, from five samples. After the
        warm-up all 16 are open; later the pool keeps only the connections that the load uses together."""
        samples = []
        for _ in range(5):
            output = subprocess.run(["lsof", "-nP", "-a", "-p", str(self.process.pid), f"-iTCP:{self.checks.port}",
                                     "-sTCP:ESTABLISHED", "-Fn"], capture_output=True, text=True, timeout=10).stdout
            samples.append({line.split("->")[0].rsplit(":", 1)[1] for line in output.splitlines()
                            if line.startswith("n") and "->" in line})
            time.sleep(0.2)
        require(all(len(sample) <= 16 for sample in samples) and max(map(len, samples)) >= (16 if warm else 1),
                f"Expected {'16' if warm else '1 to 16'} connections, sampled {list(map(len, samples))}")
        return set().union(*samples)

    def terminate(self):
        self.process.send_signal(signal.SIGTERM)
        code = self.process.wait(timeout=30)
        self.log.close()
        return code


class ReaderChecks:
    def __init__(self, checks, work):
        self.checks, self.work = checks, work
        self.seeder = seeder_checks.SeederChecks(checks, work)

    def failed_startup(self):
        reader = Reader(self.checks, self.work, "reader-replica")
        code = reader.process.wait(timeout=120)
        reader.log.close()
        lines = reader.lines()
        problems = [line for line in lines if re.search(r"\b(?:WARN|ERROR)\b", line)]
        require(code == 1, f"A reader without read replicas exited with {code}")
        require(len(problems) == 1 and re.search(r"ERROR .*Product reader startup failed: Look up seller "
                                                 r"catalog-v1\.(US|EU|ASIA)\.seller\.\d+ failed with "
                                                 r"REPLICA_UNAVAILABLE$", problems[0]),
                "Unexpected startup failure lines:\n" + "\n".join(problems))
        require(not any(READING in line for line in lines), "The reader started load without its catalog")

    def healthy(self, reader, digest):
        reader.wait(lambda: any(READING in line for line in reader.lines()), 120, "The reader did not start")
        started = time.monotonic()
        resolved = [match for line in reader.lines() if (match := RESOLVED.search(line))]
        regions = "; ".join(f"{region} 50 sellers, 100 products" for region in seeder_checks.REGIONS)
        require(len(resolved) == 1 and resolved[0].group(1) == regions, "Unexpected resolution summary")
        require(resolved[0].group(2) == digest, "The reader resolved other IDs than the seeder verified")
        time.sleep(2)
        first = reader.connections(warm=True)
        time.sleep(max(0.0, started + 38 - time.monotonic()))
        second = reader.connections(warm=False)
        require(not first & second, "A connection from the warm-up still exists after its maximum lifetime")
        reports = reader.reports()
        require(len(reports) >= 7 and all(item["scope"] == "interval" for item in reports), "Missing reports")
        require(all(item["level"] == "INFO" and set(item["outcomes"]) == {"OK"} for item in reports),
                "Healthy reads had failures")
        require(all(item["rate"] <= 201 for item in reports) and max(item["rate"] for item in reports) >= 180,
                "Unexpected request rates: " + str([item["rate"] for item in reports]))
        require(all(item["latency"]["p99"] < 1000 for item in reports), "Healthy reads were slow")
        # Open connections are leased plus available ones; no read waits for a connection when healthy.
        require(all(item["pool"]["max"] == 16 and item["pool"]["pending"] == 0
                    and item["pool"]["leased"] + item["pool"]["available"] <= 16 for item in reports),
                "Unexpected pool state: " + str([item["pool"] for item in reports]))
        require(set(total(reports, "regions")) == set(seeder_checks.REGIONS), "A region received no reads")
        require(not any(re.search(r"\b(?:WARN|ERROR)\b", line) for line in reader.lines()), "Reader warnings")
        self.checks.clean_log()
        return reports

    def recovered(self, reader, start, seconds, message):
        """Waits for failed reads after the line number start, and then for a report with only OK reads."""
        def done():
            reports = reader.reports(start)
            failed = any(name in FAILURES for name in total(reports, "outcomes"))
            return failed and set(reports[-1]["outcomes"]) == {"OK"}
        reader.wait(done, seconds, message)
        return reader.reports(start)

    def paused(self, reader):
        start = len(reader.lines())
        product = self.checks.server
        product.send_signal(signal.SIGSTOP)
        try:
            time.sleep(7)
        finally:
            product.send_signal(signal.SIGCONT)
        reports = self.recovered(reader, start, 30, "The reader did not recover after product resumed")
        outcomes = total(reports, "outcomes")
        # After product resumes, its own server deadline can end requests that waited during the pause (503).
        require(outcomes.get("DEADLINE", 0) >= 16 and set(outcomes) <= {"OK", "DEADLINE", "UNAVAILABLE"},
                f"Unexpected outcomes while product was paused: {outcomes}")
        longest = max(item["latency"]["max"] for item in reports)
        require(5000 <= longest <= 5100, f"A read took {longest} ms; expected the 5-second deadline")
        require(any(item["level"] == "WARN" for item in reports), "Failed reads did not log a warning")
        return outcomes, longest

    def restarted(self, reader):
        start = len(reader.lines())
        self.checks.stop()
        time.sleep(6)
        self.checks.start(replica=True)
        time.sleep(6)
        self.checks.start()
        reports = self.recovered(reader, start, 30, "The reader did not recover after the product restart")
        outcomes = total(reports, "outcomes")
        require(outcomes.get("TRANSPORT", 0) >= 1 and outcomes.get("REPLICA_UNAVAILABLE", 0) >= 1,
                f"Missing connection or replica failures: {outcomes}")
        require(not set(outcomes) & {"NOT_FOUND", "INVALID_RESPONSE", "HTTP_ERROR"},
                f"Unexpected outcomes during the restart: {outcomes}")
        require(max(item["latency"]["max"] for item in reports) <= 5100, "A read passed the 5-second deadline")
        return outcomes

    def run(self):
        seeded = self.seeder.seed(0)
        before = self.seeder.snapshot()
        require(seeded.group(2) == self.seeder.digest(before), "The seeder digest differs from stored IDs")

        self.checks.start(replica=True)
        self.failed_startup()

        self.checks.start()
        reader = Reader(self.checks, self.work, "reader")
        try:
            healthy = self.healthy(reader, seeded.group(2))
            paused, longest = self.paused(reader)
            restarted = self.restarted(reader)
        finally:
            code = reader.terminate()
        require(code in (0, 143), f"The reader exited with {code} after SIGTERM")
        reports = reader.reports()
        intervals = [item for item in reports if item["scope"] == "interval"]
        require(reports[-1]["scope"] == "total" and len(reports) == len(intervals) + 1,
                "The totals are not the single last report")
        require(reports[-1]["requests"] >= sum(item["requests"] for item in intervals), "Totals lost requests")
        require(not any("ERROR" in line for line in reader.lines()), "The reader logged an error")
        require(self.seeder.snapshot() == before, "The reader changed stored rows")
        rate = sum(item["requests"] for item in healthy) / sum(item["seconds"] for item in healthy)
        return seeded.group(2), rate, paused, longest, restarted, reports[-1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("container")
    parser.add_argument("--jdbc-port", type=int)
    parser.add_argument("--java", default="java")
    args = parser.parse_args()
    require(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*", args.container), "Invalid container name")
    require((provider.PRODUCT / "target/quarkus-app/quarkus-run.jar").is_file(), "Package shardshop-product first")
    require(seeder_checks.SEEDER.is_file(), "Package shardshop-product-seeder first")
    require(READER.is_file(), "Package shardshop-product-reader first")
    jdbc_port = args.jdbc_port
    if jdbc_port is None:
        published = subprocess.run(["docker", "port", args.container, "5432/tcp"],
                                   capture_output=True, text=True, check=True, timeout=10).stdout
        jdbc_port = int(published.splitlines()[0].rsplit(":", 1)[1])
    with tempfile.TemporaryDirectory(prefix="shardshop-product-reader-") as work:
        checks = provider.ProviderChecks(args.container, jdbc_port, args.java, work)
        try:
            checks.setup()
            checks.start()
            digest, rate, paused, longest, restarted, final = ReaderChecks(checks, work).run()
            print(f"Passed: the reader resolved the seeded IDs (key-to-ID SHA-256 {digest}) and read at "
                  f"{rate:.1f} requests/s through up to 16 rotating connections; a paused product gave {paused} "
                  f"(longest read {longest} ms); the restart gave {restarted}; final totals: "
                  f"{final['requests']} requests, {final['outcomes']}, pool {final['pool']}. "
                  "Startup without read replicas failed, and stored rows did not change.")
        except BaseException:
            for log in sorted(Path(work).glob("*.log")):
                print(f"Last lines of {log.name}:\n" + "\n".join(log.read_text().splitlines()[-20:]))
            raise
        finally:
            checks.close()


if __name__ == "__main__":
    main()
