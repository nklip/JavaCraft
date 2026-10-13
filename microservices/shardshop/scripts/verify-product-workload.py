#!/usr/bin/env python3
"""Verify scenario 1 for the product reader and scenario 3 for primary reads on kind-shardshop.

Needs the lab and current product, seeder and reader images from up.sh, followed by
migrate.sh. The check calls deploy.sh for two new runs:

1. A complete run: the seeder Job completes, the reader resolves the IDs that the seeder
   verified, and it reads through both ready product pods within 120 s, also after one pod
   replacement. The replacement pod must reserve a new generator ID in its own JVM (step 3.11).
2. A repeated run with the same checks must recover the same IDs.

The reader waits in Java until product serves the complete dataset. On this lab the dataset
is already complete, thus CatalogWaitTest of the reader module covers that wait.
Each run uses the Kubernetes API server pod proxy to read the request counters of each
product pod. Each product pod start uses one generator slot. At the end the reader stops.
"""
import json
import pathlib
import re
import subprocess
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parent.parent
READS = 'uri="/api/v1/sellers/{sellerId}/products/{productId}"'
READER = "app.kubernetes.io/name=shardshop-product-reader"
REPORT = re.compile(r"Product reads, interval [0-9.]+ s: ([0-9]+) requests, ([0-9.]+)/s, outcomes \{([^}]*)\}.*"
                    r"pool \{([^}]*)\}")
RESERVED = re.compile(r"Reserved generator ID ([1-9][0-9]{0,3}) for this JVM start\.")


def fail(message):
    sys.exit(f"FAILED: {message}")


def require(condition, message):
    if not condition:
        fail(message)


def kubectl(*args, check=True):
    result = subprocess.run(["kubectl", "--context", "kind-shardshop", "--request-timeout=30s", "-n", "shardshop",
                             *args], capture_output=True, text=True)
    if check and result.returncode != 0:
        fail(f"kubectl {' '.join(args)}: {result.stderr.strip()}")
    return result.stdout


def resource(*args):
    return json.loads(kubectl("get", *args, "-o", "json"))


def wait_for(description, timeout, probe):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        value = probe()
        if value:
            return value
        time.sleep(2)
    fail(f"{description} within {timeout} s")


def deploy(run):
    print(f"--- deploy.sh {run}", flush=True)
    result = subprocess.run(["bash", str(ROOT / "scripts/deploy.sh"), run], capture_output=True, text=True)
    print(result.stdout + result.stderr, end="", flush=True)
    return result


def sql(shard, statement):
    primary = resource("cluster", shard)["status"]["currentPrimary"]
    return kubectl("exec", "-i", primary, "-c", "postgres", "--", "psql", "-U", "postgres", "-d", "shardshop",
                   "-v", "ON_ERROR_STOP=1", "-At", "-c", statement).strip()


def catalog_counts(shards):
    """Seller and product rows on each shard primary."""
    return {shard: tuple(map(int, sql(shard, "SELECT (SELECT count(*) FROM catalog.sellers) || ' ' || "
                                             "(SELECT count(*) FROM catalog.products)").split()))
            for shard in shards}


def ready_product_pods():
    pods = resource("pods", "-l", "app.kubernetes.io/name=shardshop-product")["items"]
    return sorted(pod["metadata"]["name"] for pod in pods if "deletionTimestamp" not in pod["metadata"]
                  and any(c["type"] == "Ready" and c["status"] == "True" for c in pod["status"].get("conditions", [])))


def product_reads(pod):
    metrics = kubectl("get", "--raw", f"/api/v1/namespaces/shardshop/pods/{pod}:8080/proxy/q/metrics")
    return sum(float(line.rsplit(" ", 1)[1]) for line in metrics.splitlines()
               if line.startswith("http_server_requests_seconds_count{") and 'method="GET"' in line and READS in line)


def high_water():
    return int(kubectl("get", "configmap", "shardshop-snowflake-generators", "-o", "jsonpath={.data.highWaterMark}"))


def generator_ids(pods):
    """The generator ID that the current JVM of each product pod reserved, from its log."""
    ids = {}
    for pod in pods:
        reserved = RESERVED.findall(kubectl("logs", pod))
        require(len(reserved) == 1, f"{pod} logged {len(reserved)} generator reservations")
        ids[pod] = int(reserved[0])
    require(len(set(ids.values())) == len(ids), f"product pods share a generator ID: {ids}")
    return ids


def reader_log():
    return kubectl("logs", "deployment/shardshop-product-reader", check=False)


def reports():
    return REPORT.findall(reader_log())


def check_traffic(label):
    """Scenario 1: positive read-counter deltas on both ready product pods within 120 s."""
    pods = ready_product_pods()
    require(len(pods) == 2, f"{label}: expected two ready product pods, found {pods}")
    start = {pod: product_reads(pod) for pod in pods}
    began = time.monotonic()
    deltas = {}
    while time.monotonic() - began < 120:
        time.sleep(5)
        deltas = {pod: product_reads(pod) - start[pod] for pod in pods}
        if all(delta > 0 for delta in deltas.values()):
            print(f"{label}: read deltas {deltas} after {time.monotonic() - began:.0f} s", flush=True)
            return
    last = reports()[-1:] or ["no reader report"]
    fail(f"{label}: a ready product pod received no reads in 120 s; deltas {deltas}, last report {last}")


def check_clean_interval(label, after):
    """Scenario 3: a complete report interval with only OK reads at the configured rate of 200/s."""
    def clean():
        return next((report for report in reports()[after:]
                     if re.fullmatch(r"OK=[0-9]+", report[2]) and float(report[1]) >= 180), None)
    report = wait_for(f"{label}: no report interval had only OK reads at 180/s or more", 75, clean)
    print(f"{label}: {report[0]} reads, {report[1]}/s, outcomes {{{report[2]}}}, pool {{{report[3]}}}", flush=True)


def completed_job(name):
    """The Job after its Complete condition. A Failed condition stops the check."""
    job = resource("job", name)
    conditions = {c["type"]: c["status"] for c in job["status"].get("conditions", [])}
    require(conditions.get("Failed") != "True", f"Job {name} failed")
    return job if conditions.get("Complete") == "True" else None


def single_reader(run):
    """The one reader pod, when it is of this run and no earlier reader pod remains."""
    pods = resource("pods", "-l", READER)["items"]
    current = len(pods) == 1 and "deletionTimestamp" not in pods[0]["metadata"]
    return pods if current and pods[0]["metadata"]["labels"].get("shardshop.javacraft/run") == run else None


def check_http_only(pod):
    spec = pod["spec"]
    require(spec.get("automountServiceAccountToken") is False, f"{pod['metadata']['name']} mounts an API token")
    require(all("secret" not in volume and "projected" not in volume for volume in spec.get("volumes", [])),
            f"{pod['metadata']['name']} mounts a Secret")
    for container in spec["containers"]:
        require(all("valueFrom" not in variable for variable in container.get("env", [])),
                f"{pod['metadata']['name']} reads a Secret or ConfigMap value")


def complete_run(run, shards, expected_digest):
    require(deploy(run).returncode == 0, f"run {run} failed")
    job = f"shardshop-product-seeder-{run}"
    wait_for(f"run {run}: the seeder Job did not complete", 330, lambda: completed_job(job))
    seeder = resource("pods", "-l", f"batch.kubernetes.io/job-name={job}")["items"]
    succeeded = [pod["metadata"]["name"] for pod in seeder if pod["status"].get("phase") == "Succeeded"]
    require(len(succeeded) == 1, f"run {run}: expected one succeeded seeder pod, found {succeeded}")
    digest = re.search(r"Seeded and verified catalog-v1 .*key-to-ID SHA-256 ([0-9a-f]{64})",
                       kubectl("logs", succeeded[0]))
    require(digest, f"run {run} printed no seeding summary")
    require(expected_digest in (None, digest[1]), f"run {run} recovered different IDs: {digest[1]}")
    readers = wait_for(f"run {run}: no single reader pod of this run", 120, lambda: single_reader(run))
    for pod in seeder + readers:
        check_http_only(pod)
    wait_for(f"run {run}: the reader did not resolve the seeded IDs", 360,
             lambda: re.search(rf"Resolved catalog-v1 .*key-to-ID SHA-256 {digest[1]}", reader_log()))
    counts = catalog_counts(shards).values()
    require(sum(sellers for sellers, _ in counts) == 150 and sum(products for _, products in counts) == 300,
            f"run {run} did not keep exactly 150 sellers and 300 products")
    check_traffic(f"run {run}")
    check_clean_interval(f"run {run}", 0)
    before = generator_ids(ready_product_pods())
    replaced = min(before)
    kubectl("delete", "pod", replaced, "--wait=false")
    wait_for(f"run {run}: no replacement product pod became ready", 120,
             lambda: len(ready_product_pods()) == 2 and replaced not in ready_product_pods())
    after = generator_ids(ready_product_pods())
    replacement = {pod: generator for pod, generator in after.items() if pod not in before}
    reserved = high_water()
    require(len(replacement) == 1 and max(before.values()) < min(replacement.values()) <= reserved,
            f"run {run}: the replacement pod did not reserve a new generator ID: before {before}, after {after}, "
            f"high-water {reserved}")
    [(new_pod, new_id)] = replacement.items()
    print(f"run {run}: replaced {replaced} (generator ID {before[replaced]}) with {new_pod} (generator ID {new_id}); "
          f"high-water {reserved}", flush=True)
    seen = len(reports())
    check_traffic(f"run {run} after the replacement")
    check_clean_interval(f"run {run} after the replacement", seen + 1)
    return digest[1]


def main():
    require(len(sys.argv) == 1, "Usage: verify-product-workload.py")
    routing = dict(line.split("=", 1) for line in
                   kubectl("get", "configmap", "shardshop-routing", "-o",
                           "jsonpath={.data.application\\.properties}").splitlines())
    shards = routing["shardshop.routing.shards"].split(",")
    prefix = f"check-{time.strftime('%Y%m%d%H%M%S', time.gmtime())}"
    before = high_water()

    digest = complete_run(f"{prefix}-first", shards, None)
    complete_run(f"{prefix}-repeat", shards, digest)
    kubectl("scale", "deployment/shardshop-product-reader", "--replicas=0")
    print(f"Passed: two runs recovered key-to-ID SHA-256 {digest}, and the reader read through both product pods "
          f"before and after a pod replacement, which reserved a new generator ID. Generator high-water {before} -> "
          f"{high_water()}. The reader is scaled to zero.")


if __name__ == "__main__":
    main()
