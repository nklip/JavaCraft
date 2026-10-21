"use strict";

(() => {
  const PAGE_SIZE = 25;
  const TIMEOUT_MS = 8000;
  const element = (id) => document.getElementById(id);
  const region = element("region");
  const sort = element("sort");
  const productInput = element("product-id");
  let productFilter = { key: "productId", value: "" };
  let sortDirection = "DESC";
  let activeEntity;
  let entitySequence = 0;
  const rows = element("orders");
  const first = element("first");
  const previous = element("previous");
  const next = element("next");
  const last = element("last");
  let controller;
  let sequence = 0;
  let cursors = [null];
  let cursorView;
  let pageIndex = 0;
  let nextAfter = null;
  let loading = false;
  let hasPage = false;
  let lastRequested = false;

  // Format the decimal text itself: converting money to Number loses precision.
  function money(value, currency) {
    const negative = value.startsWith("-");
    const [whole, fraction] = (negative ? value.slice(1) : value).split(".");
    const prefix = negative ? "−" : "";
    const decimals = ["JPY", "KRW", "VND"].includes(currency) && fraction === "00" ? "" : "." + fraction;
    return prefix + whole.replace(/\B(?=(\d{3})+(?!\d))/g, ",") + decimals;
  }

  function canonicalId(value) {
    return typeof value === "string" && /^[1-9]\d{0,18}(?![\s\S])/.test(value)
      && (value.length < 19 || value <= "9223372036854775807");
  }

  function timestamp(value) {
    return typeof value === "string" && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?Z(?![\s\S])/.test(value)
      && Number.isFinite(Date.parse(value));
  }

  function validPage(data, selectedRegion) {
    const cursor = data && data.nextAfter;
    const valid = data && data.region === selectedRegion && Array.isArray(data.items)
      && data.items.length <= PAGE_SIZE
      && (cursor === null || cursor && timestamp(cursor.createdAt) && canonicalId(cursor.orderId))
      && data.items.every((item) => item && canonicalId(item.orderId) && canonicalId(item.buyerId)
        && validName(item.buyerName) && timestamp(item.createdAt) && ["PENDING_STOCK", "PENDING_LEDGER", "CONFIRMED", "CANCELLED"].includes(item.status)
        && (item.cancellationReason === null || typeof item.cancellationReason === "string" && item.cancellationReason.length > 0)
        && typeof item.total === "string" && /^(0|[1-9]\d{0,16})\.\d{2}(?![\s\S])/.test(item.total)
        && typeof item.currency === "string" && /^[A-Z]{3}(?![\s\S])/.test(item.currency)
        && Array.isArray(item.items)
        && item.items.every((line) => canonicalId(line.productId) && validName(line.productName)
          && Number.isSafeInteger(line.quantity) && line.quantity > 0 && line.quantity <= 2147483647));
    if (!valid) throw new Error("INVALID_RESPONSE");
    return data;
  }

  function node(tag, className, text) {
    const result = document.createElement(tag);
    if (className) result.className = className;
    if (text !== undefined) result.textContent = text;
    return result;
  }

  function validName(value) {
    return typeof value === "string" && value.trim().length > 0;
  }

  function closeEntity(returnFocus = false) {
    if (!activeEntity) return;
    const current = activeEntity;
    activeEntity = undefined;
    clearTimeout(current.timer);
    current.panel.hidden = true;
    current.trigger.setAttribute("aria-expanded", "false");
    if (returnFocus) {
      current.suppressFocus = true;
      current.trigger.focus();
      current.suppressFocus = false;
    }
  }

  function positionEntity(current) {
    const margin = 12;
    const viewportWidth = document.documentElement?.clientWidth || window.innerWidth;
    const viewportHeight = document.documentElement?.clientHeight || window.innerHeight;
    current.panel.style.maxWidth = Math.max(0, viewportWidth - margin * 2) + "px";
    current.panel.style.maxHeight = Math.max(0, viewportHeight - margin * 2) + "px";
    const anchor = current.trigger.getBoundingClientRect();
    const bounds = current.panel.getBoundingClientRect();
    const left = Math.max(margin, Math.min(anchor.left, viewportWidth - bounds.width - margin));
    const below = anchor.bottom + 7;
    const top = below + bounds.height <= viewportHeight - margin ? below
      : Math.max(margin, anchor.top - bounds.height - 7);
    current.panel.style.left = left + "px";
    current.panel.style.top = top + "px";
  }

  async function copyDetail(value, status) {
    try {
      if (navigator.clipboard && navigator.clipboard.writeText) {
        await navigator.clipboard.writeText(value);
      } else {
        const field = node("textarea");
        field.value = value;
        field.setAttribute("readonly", "");
        field.style.position = "fixed";
        field.style.opacity = "0";
        const previousFocus = document.activeElement;
        document.body.append(field);
        field.select();
        let copied;
        try { copied = document.execCommand("copy"); }
        finally { field.remove(); if (previousFocus) previousFocus.focus(); }
        if (!copied) throw new Error("COPY_DENIED");
      }
      status.textContent = "Copied to clipboard.";
    } catch (_) {
      status.textContent = "Copy unavailable. Select the text above to copy it manually.";
    }
  }

  function entityName(kind, name, id) {
    const wrapper = node("span", "entity");
    const trigger = node("button", "name-button", name);
    trigger.type = "button";
    const panel = node("span", "entity-popover");
    panel.id = "entity-details-" + ++entitySequence;
    panel.hidden = true;
    panel.setAttribute("role", "dialog");
    panel.setAttribute("aria-label", kind + " details");
    trigger.setAttribute("aria-haspopup", "dialog");
    trigger.setAttribute("aria-expanded", "false");
    trigger.setAttribute("aria-controls", panel.id);
    trigger.setAttribute("aria-label", name + ", " + kind.toLowerCase() + " details");
    for (const [label, value, className] of [["Full name", name, ""], [kind + " ID", id, " detail-id"]]) {
      const field = node("span", "detail-field");
      field.append(node("span", "detail-label", label), node("span", "detail-value" + className, value));
      panel.append(field);
    }
    const actions = node("span", "copy-actions");
    const status = node("span", "copy-status");
    status.setAttribute("role", "status");
    for (const [label, value] of [["Copy name", name], ["Copy ID", id]]) {
      const button = node("button", "", label);
      button.type = "button";
      button.addEventListener("click", () => copyDetail(value, status));
      actions.append(button);
    }
    panel.append(actions, status);
    wrapper.append(trigger, panel);
    const current = { wrapper, trigger, panel, pinned: false, timer: undefined, suppressFocus: false };
    const open = () => {
      clearTimeout(current.timer);
      if (activeEntity !== current) { closeEntity(); current.pinned = false; }
      activeEntity = current;
      panel.hidden = false;
      trigger.setAttribute("aria-expanded", "true");
      positionEntity(current);
    };
    const delayedClose = () => {
      clearTimeout(current.timer);
      current.timer = setTimeout(() => {
        if (activeEntity === current && !current.pinned && !wrapper.contains(document.activeElement)) closeEntity();
      }, 180);
    };
    trigger.addEventListener("mouseenter", open);
    trigger.addEventListener("focus", () => { if (!current.suppressFocus) open(); });
    trigger.addEventListener("click", () => {
      if (activeEntity === current && current.pinned) closeEntity();
      else { open(); current.pinned = true; }
    });
    wrapper.addEventListener("mouseleave", delayedClose);
    panel.addEventListener("mouseenter", () => clearTimeout(current.timer));
    wrapper.addEventListener("focusout", (event) => {
      if (!wrapper.contains(event.relatedTarget)) { current.pinned = false; delayedClose(); }
    });
    return wrapper;
  }

  function render(items) {
    const fragment = document.createDocumentFragment();
    for (const item of items) {
      const row = node("tr");
      const order = node("td");
      order.append(node("span", "entity-id", item.orderId));
      const buyer = node("td");
      buyer.append(entityName("Buyer", item.buyerName, item.buyerId));
      row.append(order, buyer);
      // Keep fractional seconds as supplied; Date would round microseconds to milliseconds.
      row.append(node("td", "order-date", item.createdAt.replace("T", " ").replace("Z", " UTC")));
      const status = node("td");
      status.append(node("span", "order-status", item.status));
      if (item.cancellationReason !== null) {
        status.append(node("span", "cancellation-reason", item.cancellationReason));
      }
      const total = node("td", "numeric");
      const amount = node("span", "amount", money(item.total, item.currency));
      amount.append(node("span", "currency", item.currency));
      total.append(amount);
      const products = node("td");
      if (item.items.length === 0) products.append(node("span", "no-earnings", "No recorded items"));
      for (const line of item.items) {
        const product = node("span", "order-product");
        product.append(entityName("Product", line.productName, line.productId),
          node("span", "quantity", "× " + line.quantity.toLocaleString("en-US")));
        products.append(product);
      }
      row.append(status, total, products);
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

  // Match the API: trim Unicode separators, preserving controls for validation and FEFF literally.
  function normalizeFilter(value) {
    return value.replace(/^\p{Z}+|\p{Z}+$/gu, "");
  }

  function readLocation() {
    const query = new URL(window.location.href).searchParams;
    const selectedRegion = query.get("region") ?? "US";
    const selectedSort = query.get("sort") ?? "DESC";
    const page = query.get("page") || "1";
    const pageNumber = /^[1-9]\d*(?![\s\S])/.test(page) ? Number(page) : NaN;
    return {
      region: ["US", "EU", "ASIA"].includes(selectedRegion) ? selectedRegion : "US",
      sort: ["ASC", "DESC"].includes(selectedSort) ? selectedSort : "DESC",
      product: query.has("productId")
        ? { key: "productId", value: query.get("productId") }
        : { key: "productName", value: normalizeFilter(query.get("productName") ?? "") },
      index: Number.isSafeInteger(pageNumber) ? pageNumber - 1 : 0
    };
  }

  function writeLocation(selectedRegion, index, mode) {
    const url = new URL(window.location.href);
    url.searchParams.set("region", selectedRegion);
    url.searchParams.set("sort", sortDirection);
    url.searchParams.delete("productId");
    url.searchParams.delete("productName");
    if (productFilter.value) url.searchParams.set(productFilter.key, productFilter.value);
    url.searchParams.set("page", String(index + 1));
    if (url.href !== window.location.href) window.history[mode + "State"](null, "", url.href);
  }

  async function load(targetIndex, { fresh = false, history = "push", toLast = false } = {}) {
    closeEntity();
    if (controller) controller.abort();
    controller = new AbortController();
    const requestController = controller;
    const requestSequence = ++sequence;
    const selectedRegion = region.value;
    const selectedSort = sortDirection;
    const selectedProduct = productFilter;
    const view = JSON.stringify([selectedRegion, selectedSort, selectedProduct]);
    const knownCursors = !fresh && cursorView === view ? cursors.slice() : [null];
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
    element("item-count").textContent = "Up to 25 orders per page";
    element("updated").textContent = "Updating…";
    showState("Loading orders", "Getting the latest orders for " + selectedRegion + ".", "↻");
    controls();
    try {
      const invalidProduct = selectedProduct.value !== "" && (selectedProduct.key === "productId"
        ? !canonicalId(selectedProduct.value)
        : [...selectedProduct.value].length > 200 || /[\p{Cc}\p{Cs}]/u.test(selectedProduct.value));
      productInput.setAttribute("aria-invalid", String(invalidProduct));
      if (invalidProduct) throw new Error("INVALID_PRODUCT");
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
        const query = new URLSearchParams({ region: selectedRegion, sort: selectedSort, limit: String(PAGE_SIZE) });
        if (selectedProduct.value) query.set(selectedProduct.key, selectedProduct.value);
        if (knownCursors[index] !== null) {
          query.set("afterCreatedAt", knownCursors[index].createdAt);
          query.set("afterOrderId", knownCursors[index].orderId);
        }
        const response = await fetch("/api/v1/orders?" + query, {
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
        // A cached cursor can become empty after order changes. Find the last real page.
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
      cursorView = view;
      pageIndex = index;
      nextAfter = data.nextAfter;
      writeLocation(selectedRegion, pageIndex, toLast ? history : "replace");
      lastRequested = false;
      hasPage = true;
      render(data.items);
      element("state").hidden = data.items.length > 0;
      element("page-label").textContent = "Page " + (pageIndex + 1);
      element("item-count").textContent = data.items.length + (data.items.length === 1 ? " order on this page" : " orders on this page");
      element("updated").textContent = "Updated " + new Date().toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" });
      if (data.items.length === 0) {
        showState("No orders found", "There are no orders on this page for " + selectedRegion + ". Refresh to check again.", "∅");
      } else {
        element("announcement").textContent = data.items.length + (data.items.length === 1 ? " order loaded for " : " orders loaded for ") + selectedRegion + ", page " + (pageIndex + 1) + ".";
      }
    } catch (error) {
      if (requestSequence !== sequence) return;
      element("updated").textContent = "Update unsuccessful";
      element("page-label").textContent = "Page " + (targetIndex + 1);
      const description = error.message === "INVALID_PRODUCT"
        ? selectedProduct.key === "productId"
          ? "Enter a canonical positive product ID from 1 to 9223372036854775807, or clear the filter."
          : "Enter a product name of up to 200 characters without control characters, or clear the filter."
        : timedOut
        ? "The request took too long. Refresh to try again."
        : error.message === "INVALID_RESPONSE"
          ? "The order service returned an unexpected response. Refresh to try again."
          : "The order service is unavailable right now. Refresh to try again.";
      showState("Couldn't load orders", description, "!");
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
    sortDirection = location.sort;
    updateSort();
    productFilter = location.product;
    productInput.value = productFilter.value;
    load(location.index, { history: "replace" });
  }

  region.addEventListener("change", () => load(0, { fresh: true }));
  function updateSort() {
    const newest = sortDirection === "DESC";
    element("created-heading").setAttribute("aria-sort", newest ? "descending" : "ascending");
    element("sort-direction").textContent = newest ? "↓" : "↑";
    sort.setAttribute("aria-label", "Created, " + (newest ? "newest first. Sort oldest first" : "oldest first. Sort newest first"));
  }
  sort.addEventListener("click", () => {
    sortDirection = sortDirection === "DESC" ? "ASC" : "DESC";
    updateSort();
    load(0, { fresh: true });
  });
  element("product-filter").addEventListener("submit", (event) => {
    event.preventDefault();
    const value = normalizeFilter(productInput.value);
    productFilter = { key: /^\d+$/.test(value) ? "productId" : "productName", value };
    productInput.value = value;
    load(0, { fresh: true });
  });
  element("clear-filter").addEventListener("click", () => {
    productFilter = { key: "productId", value: "" };
    productInput.value = "";
    load(0, { fresh: true });
  });
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
  document.addEventListener("click", (event) => {
    if (activeEntity && !activeEntity.wrapper.contains(event.target)) closeEntity();
  });
  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape" && activeEntity) { event.preventDefault(); closeEntity(true); }
  });
  window.addEventListener("resize", () => { if (activeEntity) positionEntity(activeEntity); });
  window.addEventListener("scroll", () => { if (activeEntity) positionEntity(activeEntity); }, true);
  window.addEventListener("popstate", restore);
  restore();
})();
