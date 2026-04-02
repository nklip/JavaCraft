const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

function createXmlHttpRequestFactory(responses) {
    return class MockXMLHttpRequest {
        static DONE = 4;

        open(method, url) {
            this.method = method;
            this.url = url;
        }

        setRequestHeader() {}

        send(body) {
            this.requestBody = body;

            const response = responses.shift();
            if (!response) {
                throw new Error("No mock HTTP response configured");
            }

            this.readyState = MockXMLHttpRequest.DONE;
            this.status = response.status;
            this.responseText = response.responseText;

            if (typeof this.onreadystatechange === "function") {
                this.onreadystatechange();
            }
        }
    };
}

function loadIndexScript({responses = [], alerts = [], formElement = {addEventListener() {}}} = {}) {
    const ajaxErrorsScriptPath = path.join(
        __dirname,
        "..",
        "..",
        "main",
        "resources",
        "static",
        "js",
        "ajax-errors.js"
    );
    const indexScriptPath = path.join(
        __dirname,
        "..",
        "..",
        "main",
        "resources",
        "static",
        "js",
        "index.js"
    );
    const ajaxErrorsScript = fs.readFileSync(ajaxErrorsScriptPath, "utf8");
    const script = fs.readFileSync(indexScriptPath, "utf8");

    let domContentLoadedHandler = null;
    const context = {
        console: {log() {}},
        document: {
            getElementById() {
                return formElement;
            }
        },
        window: {
            location: {},
            addEventListener(eventName, handler) {
                if (eventName === "DOMContentLoaded") {
                    domContentLoadedHandler = handler;
                }
            }
        },
        XMLHttpRequest: createXmlHttpRequestFactory(responses),
        alert(message) {
            alerts.push(message);
        },
        FormData
    };

    vm.createContext(context);
    vm.runInContext(ajaxErrorsScript, context);
    vm.runInContext(script, context);
    context.triggerDOMContentLoaded = function() {
        if (typeof domContentLoadedHandler !== "function") {
            throw new Error("DOMContentLoaded handler was not registered");
        }
        domContentLoadedHandler({});
    };
    return context;
}

test("sendAjaxRequest falls back to a generic alert when the server returns HTML", () => {
    const alerts = [];
    const context = loadIndexScript({
        responses: [
            {
                status: 503,
                responseText: "<html><body>Service Unavailable</body></html>"
            }
        ],
        alerts
    });

    assert.doesNotThrow(() => {
        context.sendAjaxRequest("{\"hostname\":\"127.0.0.1\",\"port\":\"8056\"}");
    });
    assert.deepEqual(alerts, ["Unable to create a new game right now."]);
});

test("DOMContentLoaded does not crash when the new game form is missing", () => {
    const context = loadIndexScript({
        formElement: null
    });

    assert.doesNotThrow(() => {
        context.triggerDOMContentLoaded();
    });
});
