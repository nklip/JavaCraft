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
  let position = {};
  let nextCursor = null;
  let previousCursor = null;
  let invalidLink = false;
  let emptyCursor = false;
  let loading = false;
  let hasPage = false;

  // Format the decimal text itself: converting money to Number loses precision.
  function money(value, signed = false, currency) {
    const negative = value.startsWith("-");
    const [whole, fraction] = (negative ? value.slice(1) : value).split(".");
    const prefix = negative ? "−" : signed && value !== "0.00" ? "+" : "";
    return prefix + whole.replace(/\B(?=(\d{3})+(?!\d))/g, ",")
      + (["JPY", "KRW", "VND"].includes(currency) && fraction === "00" ? "" : "." + fraction);
  }

  function validPage(data, selectedRegion) {
    const id = (value) => typeof value === "string" && /^[1-9]\d{0,18}$/.test(value);
    const decimal = (value, signed) => typeof value === "string"
      && (signed ? /^-?(0|[1-9]\d*)\.\d{2}$/ : /^(0|[1-9]\d*)\.\d{2}$/).test(value)
      && value !== "-0.00";
    const currency = (value) => typeof value === "string" && /^[A-Z]{3}$/.test(value);
    const stock = (value) => Number.isSafeInteger(value) && value >= 0;
    const valid = data && data.region === selectedRegion && Array.isArray(data.items)
      && data.items.length <= PAGE_SIZE
      && (data.nextCursor === null || validCursor(data.nextCursor, selectedRegion, "a"))
      && (data.previousCursor === null || validCursor(data.previousCursor, selectedRegion, "b"))
      && data.items.every((item) => item && id(item.productId) && id(item.sellerId)
        && typeof item.name === "string" && typeof item.sellerName === "string"
        && item.region === selectedRegion
        && (item.countryCode === null || typeof item.countryCode === "string" && /^[A-Z]{2}$/.test(item.countryCode))
        && typeof item.newOrderEligible === "boolean"
        && (item.newOrderEligible ? item.legacyReason === null :
          ["UNRESOLVED_SELLER_COUNTRY", "DISALLOWED_SELLER_CURRENCY", "INVALID_CURRENCY_PRECISION"].includes(item.legacyReason))
        && stock(item.initialStock) && stock(item.stock)
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
    const line = node("span", "amount" + style, money(value, signed, currency));
    line.append(node("span", "currency", currency));
    return line;
  }

  function render(items) {
    const fragment = document.createDocumentFragment();
    for (const item of items) {
      const row = node("tr");
      const productCell = identity(item.name, item.productId);
      if (item.newOrderEligible === false) {
        productCell.append(node("span", "legacy-status", "Unavailable for new orders: " + item.legacyReason));
      }
      const sellerCell = identity(item.sellerName, item.sellerId);
      sellerCell.append(node("span", "seller-country", item.countryCode || "Country unresolved"));
      row.append(productCell, sellerCell);
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

  function validCursor(value, selectedRegion, direction) {
    if (typeof value !== "string" || !/^p1\.(US|EU|ASIA)\.([1-9]\d?|100)\.[ab]\.[1-9]\d{0,18}$/.test(value)) return false;
    const [, cursorRegion, limit, cursorDirection, boundary] = value.split(".");
    return BigInt(boundary) <= 9223372036854775807n
      && (selectedRegion === undefined || cursorRegion === selectedRegion && limit === String(PAGE_SIZE) && cursorDirection === direction);
  }

  function controls() {
    first.disabled = loading || (!invalidLink && !emptyCursor && (!hasPage || previousCursor === null));
    previous.disabled = loading || !hasPage || previousCursor === null;
    last.disabled = loading || (!invalidLink && !emptyCursor && (!hasPage || nextCursor === null));
    next.disabled = loading || !hasPage || nextCursor === null;
  }

  function readLocation() {
    const query = new URL(window.location.href).searchParams;
    const selectedRegion = query.get("region") ?? "US";
    const keys = ["region", "page", "after", "before", "last"];
    const cursorKeys = ["after", "before", "last"].filter(key => query.has(key));
    const invalid = keys.some(key => query.getAll(key).length > 1)
      || !["US", "EU", "ASIA"].includes(selectedRegion)
      || cursorKeys.length > 1 || query.has("page") && cursorKeys.length > 0
      || ["after", "before"].some(key => query.has(key) && !validCursor(query.get(key)))
      || query.has("last") && query.get("last") !== "true";
    if (invalid) return { error: "This catalog link has invalid region or cursor parameters. Use First or Last to select a page.", region: ["US", "EU", "ASIA"].includes(selectedRegion) ? selectedRegion : "US" };
    if (query.has("page") && query.get("page") !== "1") {
      return { region: selectedRegion, error: "Numbered links no longer identify a catalog position. Use First or Last to select a page." };
    }
    const target = {};
    for (const key of cursorKeys) target[key] = query.get(key);
    return { region: selectedRegion, target };
  }

  function writeLocation(selectedRegion, target, mode) {
    const url = new URL(window.location.href);
    for (const key of ["page", "after", "before", "last"]) url.searchParams.delete(key);
    url.searchParams.set("region", selectedRegion);
    for (const [key, value] of Object.entries(target)) url.searchParams.set(key, value);
    if (url.href !== window.location.href) window.history[mode + "State"](null, "", url.href);
  }

  async function load(target = {}, { history = "push" } = {}) {
    if (controller) controller.abort();
    controller = new AbortController();
    const requestController = controller;
    const requestSequence = ++sequence;
    const selectedRegion = region.value;
    position = target;
    invalidLink = false;
    emptyCursor = false;
    writeLocation(selectedRegion, target, history);
    element("page-label").textContent = target.last ? "Last page" : Object.keys(target).length ? "Catalog page" : "First page";
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
      const query = new URLSearchParams({ region: selectedRegion, limit: String(PAGE_SIZE), ...target });
      const response = await fetch("/api/v1/products?" + query, {
        signal: requestController.signal, headers: { Accept: "application/json" }, cache: "no-store"
      });
      if (!response.ok) throw new Error("HTTP_" + response.status);
      const data = validPage(await response.json(), selectedRegion);
      if (requestSequence !== sequence) return;
      requestController.signal.throwIfAborted();
      if (Date.now() >= deadline) {
        timedOut = true;
        throw new Error("TIMEOUT");
      }
      emptyCursor = data.items.length === 0 && Boolean(target.after || target.before);
      nextCursor = data.nextCursor;
      previousCursor = data.previousCursor;
      hasPage = true;
      render(data.items);
      element("state").hidden = data.items.length > 0;
      element("item-count").textContent = data.items.length + (data.items.length === 1 ? " product on this page" : " products on this page");
      element("updated").textContent = "Updated " + new Date().toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" });
      if (data.items.length === 0) {
        showState("No products found", "There are no products on this page for " + selectedRegion + ". Refresh to check again.", "∅");
      } else {
        element("announcement").textContent = data.items.length + (data.items.length === 1 ? " product loaded for " : " products loaded for ") + selectedRegion + ".";
      }
    } catch (error) {
      if (requestSequence !== sequence) return;
      element("updated").textContent = "Update unsuccessful";
      const description = timedOut
        ? "The request took too long. Refresh to try again."
        : error.message === "HTTP_400"
          ? "The catalog link has an invalid cursor or does not match the selected region. Use First or Last to select a page."
        : error.message === "INVALID_RESPONSE"
          ? "The catalog returned an unexpected response. Refresh to try again."
          : "The catalog is unavailable right now. Refresh to try again.";
      invalidLink = error.message === "HTTP_400";
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
    if (location.error) {
      if (controller) controller.abort();
      sequence++;
      invalidLink = true;
      emptyCursor = false;
      loading = false;
      hasPage = false;
      rows.replaceChildren();
      element("results").setAttribute("aria-busy", "false");
      element("page-label").textContent = "Catalog page";
      element("updated").textContent = "Link needs updating";
      showState("Couldn't open catalog link", location.error, "!");
      controls();
      return;
    }
    load(location.target, { history: "replace" });
  }

  region.addEventListener("change", () => load());
  element("refresh").addEventListener("click", () => invalidLink ? restore() : load(position, { history: "replace" }));
  first.addEventListener("click", () => { if (!first.disabled) load(); });
  previous.addEventListener("click", () => { if (!previous.disabled) load({ before: previousCursor }); });
  next.addEventListener("click", () => { if (!next.disabled) load({ after: nextCursor }); });
  last.addEventListener("click", () => { if (!last.disabled) load({ last: "true" }); });
  window.addEventListener("popstate", restore);
  restore();
})();
