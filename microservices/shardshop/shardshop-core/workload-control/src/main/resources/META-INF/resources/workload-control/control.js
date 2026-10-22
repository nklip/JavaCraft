"use strict";

(() => {
  const POLL_MS = 2000;
  const TIMEOUT_MS = 5000;
  const STATES = ["IDLE", "WAITING", "RUNNING", "PAUSING", "PAUSED", "COMPLETED", "FAILED"];
  const STATE_TEXT = {
    IDLE: "Idle", WAITING: "Waiting for the catalog", RUNNING: "Running", PAUSING: "Pausing",
    PAUSED: "Paused", COMPLETED: "Completed", FAILED: "Failed"
  };
  const PURPOSES = {
    startup: ["Startup", "Catalog lookups and buyer creation"],
    allocation: ["Order allocation", "One allocated order ID for each ordinal"],
    submission: ["Order submission", "PUT of each allocated order"],
    polling: ["Status polling", "Reads until the order is terminal"],
    reads: ["Product reads", "GET of a randomly selected product"],
    creation: ["Creation", "Seller and product POSTs"],
    verification: ["Verification", "Reads by ID and by creation key"]
  };
  const element = (id) => document.getElementById(id);
  const main = element("main");
  const api = main.getAttribute("data-api");
  const workload = main.getAttribute("data-workload");
  const input = element("rate");
  const pause = element("pause");
  const resume = element("resume");
  const runStart = element("run-start");
  const numbers = new Intl.NumberFormat("en-US", { maximumFractionDigits: 3 });
  let status = null;
  let dirty = false;
  let busy = false;
  let sequence = 0;
  let rendered = 0;
  let timer = null;
  let lastState = null;

  function validStatus(data) {
    const count = (value) => Number.isSafeInteger(value) && value >= 0;
    const rate = (value) => typeof value === "number" && Number.isFinite(value) && value >= 0;
    const text = (value) => typeof value === "string" && value.length > 0;
    const nullableText = (value) => value === null || text(value);
    const valid = data && data.workload === workload && text(data.unit) && STATES.includes(data.state)
      && typeof data.paused === "boolean"
      && ["targetRate", "effectiveRate", "defaultRate", "maximumRate", "rateStep", "achievedRate"].every((key) => rate(data[key]))
      && ["starts", "inFlight", "requests", "errors", "revision"].every((key) => count(data[key]))
      && Array.isArray(data.traffic) && data.traffic.length > 0
      && data.traffic.every((item) => item && Object.hasOwn(PURPOSES, item.category)
        && count(item.requests) && count(item.retries) && count(item.errors))
      && (data.limit === null || data.limit && data.limit.code === "CONCURRENCY" && count(data.limit.concurrency)
        && rate(data.limit.meanOperationSeconds) && rate(data.limit.achievableRate))
      && (data.run === null || data.run && count(data.run.number) && ["RUNNING", "COMPLETED", "FAILED"].includes(data.run.state)
        && text(data.run.startedAt) && nullableText(data.run.finishedAt) && count(data.run.completedOperations)
        && count(data.run.plannedOperations) && nullableText(data.run.summary) && nullableText(data.run.error));
    if (!valid) throw new Error("INVALID_RESPONSE");
    return data;
  }

  function node(tag, className, text) {
    const result = document.createElement(tag);
    if (className) result.className = className;
    if (text !== undefined) result.textContent = text;
    return result;
  }

  function rate(value) {
    return numbers.format(value);
  }

  function setValue(id, value, detail) {
    const target = element(id);
    target.replaceChildren();
    target.textContent = value;
    if (detail) target.append(node("small", "", detail));
  }

  function showMessage(text, kind = "error") {
    const message = element("message");
    message.hidden = text === "";
    message.textContent = text;
    message.setAttribute("data-kind", kind);
  }

  function announce(text) {
    element("announcement").textContent = text;
  }

  function controls() {
    const ready = status !== null && !busy;
    element("apply").disabled = element("increase").disabled = element("decrease").disabled = !ready;
    pause.disabled = !ready || status.paused;
    resume.disabled = !ready || !status.paused;
    if (runStart) runStart.disabled = !ready || status.run !== null && status.run.state === "RUNNING";
  }

  function render(data) {
    status = data;
    const badge = element("state-badge");
    badge.textContent = STATE_TEXT[data.state];
    badge.setAttribute("data-state", data.state);
    element("revision").textContent = "Setting revision " + data.revision;
    setValue("effective", rate(data.effectiveRate), data.paused ? "paused · target " + rate(data.targetRate) : data.unit);
    setValue("achieved", rate(data.achievedRate), data.unit + ", last 10 s");
    setValue("in-flight", data.inFlight.toLocaleString("en-US"),
      data.state === "PAUSING" ? "finishing before the pause" : "bounded operations");
    setValue("requests", data.requests.toLocaleString("en-US"), "since process start");
    setValue("errors", data.errors.toLocaleString("en-US"), "failed backend requests");
    element("errors").className = data.errors > 0 ? "has-errors" : "";
    const limit = element("limit");
    limit.hidden = data.limit === null;
    limit.textContent = data.limit === null ? "" : "The target is above what the bounded concurrency can reach: "
      + data.limit.concurrency + " in flight, " + rate(data.limit.meanOperationSeconds) + " s per operation on average, "
      + "thus at most " + rate(data.limit.achievableRate) + " " + data.unit + ".";
    element("rate-help").textContent = "0 to " + rate(data.maximumRate) + " " + data.unit + "; 0 stops new work. Default "
      + rate(data.defaultRate) + ", restored when the process restarts.";
    if (!dirty) input.value = String(data.targetRate);
    input.setAttribute("max", String(data.maximumRate));
    input.setAttribute("step", String(data.rateStep));
    const rows = document.createDocumentFragment();
    for (const item of data.traffic) {
      const row = node("tr");
      const purpose = node("td");
      purpose.append(node("span", "purpose", PURPOSES[item.category][0]), node("span", "purpose-help", PURPOSES[item.category][1]));
      row.append(purpose, node("td", "numeric", item.requests.toLocaleString("en-US")),
        node("td", "numeric", item.retries.toLocaleString("en-US")),
        node("td", "numeric" + (item.errors > 0 ? " has-errors" : ""), item.errors.toLocaleString("en-US")));
      rows.append(row);
    }
    element("traffic").replaceChildren(rows);
    element("state").hidden = true;
    element("results").setAttribute("aria-busy", "false");
    if (runStart) renderRun(data.run);
    element("updated").textContent = "Updated " + new Date().toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" });
    if (lastState !== null && lastState !== data.state) announce("State: " + STATE_TEXT[data.state] + ".");
    lastState = data.state;
    controls();
  }

  function renderRun(run) {
    element("run-number").textContent = run === null ? "No run yet" : "Run " + run.number;
    element("run-state").textContent = run === null ? "—" : run.state;
    element("run-progress").textContent = run === null ? "—"
      : run.completedOperations.toLocaleString("en-US") + " of " + run.plannedOperations.toLocaleString("en-US") + " operations";
    element("run-times").textContent = run === null ? "—" : run.startedAt + (run.finishedAt ? " → " + run.finishedAt : " → running");
    element("run-result").textContent = run === null ? "Start a run to seed and verify the catalog dataset."
      : run.error || run.summary || "In progress";
  }

  async function request(path, method, body) {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), TIMEOUT_MS);
    try {
      const options = { method, signal: controller.signal, cache: "no-store", headers: { Accept: "application/json" } };
      if (body !== undefined) {
        options.headers["Content-Type"] = "application/json";
        options.body = JSON.stringify(body);
      }
      const response = await fetch(api + path, options);
      const data = await response.json().catch(() => null);
      return { ok: response.ok, status: response.status, data };
    } finally {
      clearTimeout(timeout);
    }
  }

  function schedule() {
    clearTimeout(timer);
    timer = setTimeout(refresh, POLL_MS);
  }

  async function refresh() {
    const current = ++sequence;
    try {
      const result = await request("", "GET");
      if (!result.ok) throw new Error("HTTP_" + result.status);
      const data = validStatus(result.data);
      if (current > rendered) {
        rendered = current;
        render(data);
        if (element("message").getAttribute("data-kind") === "stale") showMessage("");
      }
    } catch (error) {
      if (current > rendered) {
        element("updated").textContent = "Update unsuccessful";
        const text = error.message === "INVALID_RESPONSE"
          ? "The workload returned an unexpected status. Retrying every 2 seconds."
          : "The workload status is unavailable right now. Retrying every 2 seconds.";
        if (status === null) {
          element("state").hidden = false;
          element("state-icon").textContent = "!";
          element("state-title").textContent = "Couldn't load the workload status";
          element("state-description").textContent = text;
          element("results").setAttribute("aria-busy", "false");
        } else {
          showMessage(text + " The values below are from the last successful update.", "stale");
        }
      }
    } finally {
      schedule();
    }
  }

  async function act(path, method, body, done) {
    if (busy) return;
    busy = true;
    controls();
    const current = ++sequence;
    try {
      const result = await request(path, method, body);
      if (result.ok) {
        const data = validStatus(result.data);
        if (current > rendered) {
          rendered = current;
          render(data);
        }
        showMessage(done(data), "info");
        announce(done(data));
      } else {
        const reason = result.data && typeof result.data.message === "string" ? result.data.message : "HTTP " + result.status + ".";
        showMessage("Not applied: " + reason);
        announce("Not applied: " + reason);
        if (path === "/rate") input.setAttribute("aria-invalid", "true");
      }
    } catch (error) {
      const text = error.message === "INVALID_RESPONSE"
        ? "The workload returned an unexpected response. The status below shows the current setting."
        : "The request could not be completed. The status below shows the current setting.";
      showMessage(text);
      announce(text);
    } finally {
      busy = false;
      controls();
      schedule();
    }
  }

  // At most three decimal places, without signs or exponents, and within the maximum.
  function parseRate(text) {
    const value = text.trim();
    if (!/^(0|[1-9]\d*)(\.\d{1,3})?$/.test(value)) return null;
    const parsed = Number(value);
    return status !== null && parsed <= status.maximumRate ? parsed : null;
  }

  function stepRate(direction) {
    if (status === null) return;
    const current = parseRate(input.value) ?? status.targetRate;
    const next = Math.round((current + direction * status.rateStep) * 1000) / 1000;
    input.value = String(Math.min(status.maximumRate, Math.max(0, next)));
    dirty = true;
    input.setAttribute("aria-invalid", "false");
    element("rate-error").textContent = "";
  }

  element("rate-form").addEventListener("submit", (event) => {
    event.preventDefault();
    if (status === null) return;
    const value = parseRate(input.value);
    if (value === null) {
      input.setAttribute("aria-invalid", "true");
      element("rate-error").textContent = "Enter a number from 0 to " + rate(status.maximumRate)
        + " with at most three decimal places. The current setting is unchanged.";
      input.focus();
      return;
    }
    input.setAttribute("aria-invalid", "false");
    element("rate-error").textContent = "";
    act("/rate", "PUT", { rate: value }, (data) => {
      dirty = false;
      input.value = String(data.targetRate);
      return "Target rate set to " + rate(data.targetRate) + " " + data.unit + (data.paused ? " (still paused)." : ".");
    });
  });
  input.addEventListener("input", () => {
    dirty = true;
    input.setAttribute("aria-invalid", "false");
    element("rate-error").textContent = "";
  });
  element("decrease").addEventListener("click", () => stepRate(-1));
  element("increase").addEventListener("click", () => stepRate(1));
  pause.addEventListener("click", () => act("/pause", "POST", undefined, (data) => data.inFlight > 0
    ? "Pausing: " + data.inFlight + " operation(s) in flight finish first." : "Paused. No new work starts."));
  resume.addEventListener("click", () => act("/resume", "POST", undefined, (data) =>
    "Resumed at " + rate(data.effectiveRate) + " " + data.unit + "."));
  if (runStart) {
    runStart.addEventListener("click", () => act("/runs", "POST", undefined, (data) =>
      "Seed run " + data.run.number + " started."));
  }
  controls();
  refresh();
})();
