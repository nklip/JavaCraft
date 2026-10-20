"use strict";

(() => {
  const PAGE_SIZE = 25;
  const TIMEOUT_MS = 8000;
  const element = (id) => document.getElementById(id);
  const region = element("region");
  const rows = element("products");
  const first = element("first");
  const previous = element("previous");
  const next = element("next");
  const last = element("last");
  let controller;
  let sequence = 0;
  let cursors = [null];
  let cursorRegion;
  let pageIndex = 0;
  let nextAfter = null;
  let loading = false;
  let hasPage = false;
  let lastRequested = false;

  // Format the decimal text itself: converting money to Number loses precision.
  function money(value, signed = false) {
    const negative = value.startsWith("-");
    const [whole, fraction] = (negative ? value.slice(1) : value).split(".");
    const prefix = negative ? "−" : signed && value !== "0.00" ? "+" : "";
    return prefix + whole.replace(/\B(?=(\d{3})+(?!\d))/g, ",") + "." + fraction;
  }

  function validPage(data, selectedRegion) {
    const id = (value) => typeof value === "string" && /^[1-9]\d{0,18}$/.test(value);
    const decimal = (value, signed) => typeof value === "string"
      && (signed ? /^-?(0|[1-9]\d*)\.\d{2}$/ : /^(0|[1-9]\d*)\.\d{2}$/).test(value)
      && value !== "-0.00";
    const currency = (value) => typeof value === "string" && /^[A-Z]{3}$/.test(value);
    const stock = (value) => Number.isSafeInteger(value) && value >= 0;
    const valid = data && data.region === selectedRegion && Array.isArray(data.items)
      && data.items.length <= PAGE_SIZE && (data.nextAfter === null || id(data.nextAfter))
      && data.items.every((item) => item && id(item.productId) && id(item.sellerId)
        && typeof item.name === "string" && typeof item.sellerName === "string"
        && item.region === selectedRegion && stock(item.initialStock) && stock(item.stock)
        && decimal(item.price, false) && currency(item.currency)
        && item.profitsEarned !== null && typeof item.profitsEarned === "object"
        && !Array.isArray(item.profitsEarned)
        && Object.entries(item.profitsEarned).every(([code, amount]) => currency(code) && decimal(amount, true)));
    if (!valid) throw new Error("INVALID_RESPONSE");
    return data;
  }

  function node(tag, className, text) {
    const result = document.createElement(tag);
    if (className) result.className = className;
    if (text !== undefined) result.textContent = text;
    return result;
  }

  function identity(name, id) {
    const cell = node("td");
    cell.append(node("span", "entity-name", name), node("span", "entity-id", id));
    return cell;
  }

  function amount(value, currency, signed = false) {
    const style = signed && value !== "0.00"
      ? value.startsWith("-") ? " profit-negative" : " profit-positive" : "";
    const line = node("span", "amount" + style, money(value, signed));
    line.append(node("span", "currency", currency));
    return line;
  }

  function render(items) {
    const fragment = document.createDocumentFragment();
    for (const item of items) {
      const row = node("tr");
      row.append(identity(item.name, item.productId), identity(item.sellerName, item.sellerId));
      row.append(node("td", "numeric", item.initialStock.toLocaleString("en-US")));
      row.append(node("td", "numeric" + (item.stock === 0 ? " zero-stock" : ""), item.stock.toLocaleString("en-US")));
      const price = node("td", "numeric");
      price.append(amount(item.price, item.currency));
      const earnings = node("td", "numeric");
      const balances = Object.entries(item.profitsEarned).sort(([left], [right]) => left.localeCompare(right));
      if (balances.length === 0) earnings.append(node("span", "no-earnings", "No applied earnings"));
      for (const [code, value] of balances) earnings.append(amount(value, code, true));
      row.append(price, earnings);
      fragment.append(row);
    }
    rows.replaceChildren(fragment);
  }

  function showState(title, description, icon) {
    element("state").hidden = false;
    element("state-title").textContent = title;
    element("state-description").textContent = description;
    element("state-icon").textContent = icon;
    element("announcement").textContent = title + ". " + description;
  }

  function controls() {
    first.disabled = previous.disabled = loading || !hasPage || pageIndex === 0;
    last.disabled = next.disabled = loading || !hasPage || nextAfter === null;
  }

  function readLocation() {
    const query = new URL(window.location.href).searchParams;
    const selectedRegion = query.get("region") ?? "US";
    const page = query.get("page") || "1";
    if (!["US", "EU", "ASIA"].includes(selectedRegion)) return { region: "US", index: 0 };
    const pageNumber = /^[1-9]\d*$/.test(page) ? Number(page) : NaN;
    return { region: selectedRegion, index: Number.isSafeInteger(pageNumber) ? pageNumber - 1 : 0 };
  }

  function writeLocation(selectedRegion, index, mode) {
    const url = new URL(window.location.href);
    url.searchParams.set("region", selectedRegion);
    url.searchParams.set("page", String(index + 1));
    if (url.href !== window.location.href) window.history[mode + "State"](null, "", url.href);
  }

  async function load(targetIndex, { fresh = false, history = "push", toLast = false } = {}) {
    if (controller) controller.abort();
    controller = new AbortController();
    const requestController = controller;
    const requestSequence = ++sequence;
    const selectedRegion = region.value;
    const knownCursors = !fresh && cursorRegion === selectedRegion ? cursors.slice() : [null];
    let index = Math.min(targetIndex, knownCursors.length - 1);
    pageIndex = targetIndex;
    lastRequested = toLast;
    // Last has no page number until traversal finishes. Keep the current URL meanwhile.
    if (!toLast) writeLocation(selectedRegion, targetIndex, history);
    element("page-label").textContent = "Page " + (targetIndex + 1);
    const deadline = Date.now() + TIMEOUT_MS;
    let timedOut = false;
    const timeout = setTimeout(() => {
      timedOut = true;
      requestController.abort();
    }, TIMEOUT_MS);
    loading = true;
    hasPage = false;
    rows.replaceChildren();
    element("results").setAttribute("aria-busy", "true");
    element("region-badge").textContent = selectedRegion;
    element("item-count").textContent = "Up to 25 products per page";
    element("updated").textContent = "Updating…";
    showState("Loading products", "Getting the latest inventory for " + selectedRegion + ".", "↻");
    controls();
    try {
      let data;
      let lastPage;
      let recoveringEnd = false;
      while (true) {
        if (requestSequence !== sequence) return;
        if (Date.now() >= deadline) {
          timedOut = true;
          requestController.abort();
        }
        requestController.signal.throwIfAborted();
        const query = new URLSearchParams({ region: selectedRegion, limit: String(PAGE_SIZE) });
        if (knownCursors[index] !== null) query.set("after", knownCursors[index]);
        const response = await fetch("/api/v1/products?" + query, {
          signal: requestController.signal, headers: { Accept: "application/json" }, cache: "no-store"
        });
        if (!response.ok) throw new Error("HTTP_" + response.status);
        data = validPage(await response.json(), selectedRegion);
        if (requestSequence !== sequence) return;
        requestController.signal.throwIfAborted();
        if (Date.now() >= deadline) {
          timedOut = true;
          throw new Error("TIMEOUT");
        }
        // A cached cursor can become empty after catalog changes. Find the last real page.
        if (data.items.length === 0 && index > 0) {
          index--;
          recoveringEnd = true;
          if (lastPage) {
            data = { ...lastPage, nextAfter: null };
            break;
          }
          continue;
        }
        if (recoveringEnd || data.items.length === 0) data = { ...data, nextAfter: null };
        if (recoveringEnd || (!toLast && index === targetIndex) || data.nextAfter === null) break;
        lastPage = data;
        knownCursors[++index] = data.nextAfter;
      }
      cursors = knownCursors.slice(0, index + 1);
      if (data.nextAfter !== null) cursors.push(data.nextAfter);
      cursorRegion = selectedRegion;
      pageIndex = index;
      nextAfter = data.nextAfter;
      writeLocation(selectedRegion, pageIndex, toLast ? history : "replace");
      lastRequested = false;
      hasPage = true;
      render(data.items);
      element("state").hidden = data.items.length > 0;
      element("page-label").textContent = "Page " + (pageIndex + 1);
      element("item-count").textContent = data.items.length + (data.items.length === 1 ? " product on this page" : " products on this page");
      element("updated").textContent = "Updated " + new Date().toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" });
      if (data.items.length === 0) {
        showState("No products found", "There are no products on this page for " + selectedRegion + ". Refresh to check again.", "∅");
      } else {
        element("announcement").textContent = data.items.length + (data.items.length === 1 ? " product loaded for " : " products loaded for ") + selectedRegion + ", page " + (pageIndex + 1) + ".";
      }
    } catch (error) {
      if (requestSequence !== sequence) return;
      element("updated").textContent = "Update unsuccessful";
      element("page-label").textContent = "Page " + (targetIndex + 1);
      const description = timedOut
        ? "The request took too long. Refresh to try again."
        : error.message === "INVALID_RESPONSE"
          ? "The catalog returned an unexpected response. Refresh to try again."
          : "The catalog is unavailable right now. Refresh to try again.";
      showState("Couldn't load products", description, "!");
    } finally {
      clearTimeout(timeout);
      if (requestSequence === sequence) {
        loading = false;
        element("results").setAttribute("aria-busy", "false");
        controls();
      }
    }
  }

  function restore() {
    const location = readLocation();
    region.value = location.region;
    load(location.index, { history: "replace" });
  }

  region.addEventListener("change", () => load(0, { fresh: true }));
  element("refresh").addEventListener("click", () => load(pageIndex, {
    fresh: true, history: lastRequested ? "push" : "replace", toLast: lastRequested
  }));
  first.addEventListener("click", () => {
    if (!first.disabled) load(0);
  });
  previous.addEventListener("click", () => {
    if (!loading && hasPage && pageIndex > 0) load(pageIndex - 1);
  });
  next.addEventListener("click", () => {
    if (!loading && hasPage && nextAfter !== null) load(pageIndex + 1);
  });
  last.addEventListener("click", () => {
    if (!last.disabled) load(pageIndex, { toLast: true });
  });
  window.addEventListener("popstate", restore);
  restore();
})();
