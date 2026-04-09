/*
 * Browser-side runtime for the OpenFlights admin SQL console.
 *
 * The backend now returns structured JSON rather than pre-rendered HTML, so
 * this file owns the presentation layer for the SQL console:
 * - initialize the CodeMirror SQL editor
 * - send SQL + paging state to the admin endpoint
 * - render toolbar, pagination, page-size controls, and result tables
 * - escape database values before they are injected into the DOM
 * - manage the Hide/Show toggle for the rendered output block
 *
 * This split is intentional. It keeps SqlRepository focused on database IO and
 * lets the UI evolve without pushing HTML-building concerns back into Java.
 */
const sqlInput = document.getElementById("sql-input");
const executeButton = document.getElementById("execute-button");
const hideShowButton = document.getElementById("hide-show-button");
const sqlOutput = document.getElementById("sql-output");
const allowedPageSizes = [10, 20, 50, 100];

// We still keep the textarea in the HTML so the page has a simple fallback
// structure, but CodeMirror becomes the real editor when its assets are loaded.
const sqlEditor = typeof CodeMirror === "undefined"
    ? null
    : CodeMirror.fromTextArea(sqlInput, {
        mode: "text/x-pgsql",
        lineNumbers: true,
        lineWrapping: true
    });
let currentPageSize = 50;

function currentSql() {
    return sqlEditor === null ? sqlInput.value : sqlEditor.getValue();
}

function containsDataTable(result) {
    return result.type === "TABLE" && Array.isArray(result.rows) && result.rows.length > 0;
}

// Database values are rendered client-side now, so this escaping step is the
// safety boundary that prevents raw cell values from becoming executable HTML.
function escapeHtml(value) {
    return String(value)
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll("\"", "&quot;")
        .replaceAll("'", "&#39;");
}

function rowsSummary(result) {
    if (result.totalRows === 0) {
        return "Rows 0-0 of 0";
    }
    const from = (result.page - 1) * result.pageSize + 1;
    const to = Math.min(from + result.rows.length - 1, result.totalRows);
    return "Rows " + from + "-" + to + " of " + result.totalRows;
}

function visiblePages(page, totalPages) {
    const pages = [];
    const start = Math.max(1, page - 2);
    const end = Math.min(totalPages, page + 2);
    for (let current = start; current <= end; current += 1) {
        pages.push(current);
    }
    return pages;
}

function renderPageButton(label, page, disabled, current) {
    return "<button type=\"button\" class=\"sql-page-button"
        + (current ? " sql-page-button-current" : "")
        + "\" data-page=\"" + page + "\""
        + (current ? " aria-current=\"page\"" : "")
        + (disabled ? " disabled" : "")
        + ">" + escapeHtml(label) + "</button>";
}

function renderPageSizeButton(pageSize, currentPageSize) {
    return "<button type=\"button\" class=\"sql-page-size-button"
        + (pageSize === currentPageSize ? " sql-page-size-button-active" : "")
        + "\" data-page-size=\"" + pageSize + "\">" + pageSize + "</button>";
}

// Rebuild the same toolbar/table structure that used to be produced in Java,
// but now from the structured JSON contract returned by the backend.
function renderTableResult(result) {
    let html = "<div class=\"sql-result-toolbar\">"
        + "<div class=\"sql-page-summary\">" + escapeHtml(rowsSummary(result)) + "</div>"
        + "<div class=\"sql-pagination\">"
        + renderPageButton("Previous", Math.max(1, result.page - 1), result.page === 1, false);

    for (const pageNumber of visiblePages(result.page, result.totalPages)) {
        html += renderPageButton(String(pageNumber), pageNumber, false, pageNumber === result.page);
    }

    html += renderPageButton("Next", Math.min(result.totalPages, result.page + 1), result.page === result.totalPages, false)
        + "</div><div class=\"sql-page-size-controls\">"
        + "<span class=\"sql-page-size-label\">Page size:</span>";

    for (const pageSize of allowedPageSizes) {
        html += renderPageSizeButton(pageSize, result.pageSize);
    }

    html += "</div></div><table class=\"sql-result-table\"><thead><tr>";
    for (const column of result.columns) {
        html += "<th>" + escapeHtml(column) + "</th>";
    }
    html += "</tr></thead><tbody>";

    if (result.rows.length === 0) {
        html += "<tr><td class=\"sql-empty\" colspan=\"" + result.columns.length + "\">(no rows)</td></tr>";
    } else {
        for (const row of result.rows) {
            html += "<tr>";
            for (const value of row) {
                html += "<td>" + escapeHtml(value) + "</td>";
            }
            html += "</tr>";
        }
    }

    return html + "</tbody></table>";
}

function renderMessage(message, cssClass) {
    return "<div class=\"" + cssClass + "\">" + escapeHtml(message) + "</div>";
}

function renderSqlResult(result) {
    if (result.type === "TABLE") {
        return renderTableResult(result);
    }
    if (result.type === "ERROR") {
        return renderMessage(result.message ?? "Unknown error", "sql-result-error");
    }
    return renderMessage(result.message ?? "No output", "sql-result-message");
}

function showSqlOutput() {
    sqlOutput.hidden = false;
    hideShowButton.textContent = "Hide";
}

function hideSqlOutput() {
    sqlOutput.hidden = true;
    hideShowButton.textContent = "Show";
}

// Executes the current editor contents and refreshes the rendered output for
// the requested page window. The backend is responsible for database-backed
// paging, while this function is responsible for updating the visible UI state.
async function executeSql(page = 1, pageSize = currentPageSize) {
    currentPageSize = pageSize;
    sqlOutput.hidden = false;
    hideShowButton.hidden = true;
    hideShowButton.textContent = "Hide";
    sqlOutput.innerHTML = "<div class=\"sql-result-message\">Executing...</div>";

    try {
        const response = await fetch("/api/v1/openflights/admin/sql", {
            method: "POST",
            headers: {
                "Content-Type": "application/json"
            },
            body: JSON.stringify({
                sql: currentSql(),
                page: page,
                pageSize: pageSize
            })
        });

        const result = await response.json();
        const html = renderSqlResult(result);
        sqlOutput.innerHTML = html;
        if (containsDataTable(result)) {
            hideShowButton.hidden = false;
            showSqlOutput();
        } else {
            hideShowButton.hidden = true;
        }
    } catch (error) {
        const errorMessage = error instanceof Error ? error.message : "Request failed";
        sqlOutput.innerHTML = "<div class=\"sql-result-error\">" + errorMessage + "</div>";
        hideShowButton.hidden = true;
    }
}

executeButton.addEventListener("click", () => executeSql(1, currentPageSize));
hideShowButton.addEventListener("click", () => {
    if (sqlOutput.hidden) {
        showSqlOutput();
    } else {
        hideSqlOutput();
    }
});
sqlOutput.addEventListener("click", event => {
    const pageSizeButton = event.target.closest("[data-page-size]");
    if (pageSizeButton instanceof HTMLButtonElement && !pageSizeButton.disabled) {
        executeSql(1, Number(pageSizeButton.dataset.pageSize));
        return;
    }

    const pageButton = event.target.closest("[data-page]");
    if (pageButton instanceof HTMLButtonElement && !pageButton.disabled) {
        executeSql(Number(pageButton.dataset.page), currentPageSize);
    }
});
