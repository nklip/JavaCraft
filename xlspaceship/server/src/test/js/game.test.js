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
            MockXMLHttpRequest.lastRequest = {
                method,
                url
            };
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

function loadGameScript({elements = {}, responses = [], alerts = []} = {}) {
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
    const gameScriptPath = path.join(
        __dirname,
        "..",
        "..",
        "main",
        "resources",
        "static",
        "js",
        "game.js"
    );
    const ajaxErrorsScript = fs.readFileSync(ajaxErrorsScriptPath, "utf8");
    const script = fs.readFileSync(gameScriptPath, "utf8");

    const context = {
        console: {log() {}},
        document: {
            addEventListener() {},
            getElementById(id) {
                if (id in elements) {
                    return elements[id];
                }
                throw new Error(`Unexpected element lookup: ${id}`);
            }
        },
        XMLHttpRequest: createXmlHttpRequestFactory(responses),
        setTimeout() {},
        alert(message) {
            alerts.push(message);
        },
        window: {location: {}}
    };

    vm.createContext(context);
    vm.runInContext(ajaxErrorsScript, context);
    vm.runInContext(script, context);
    return context;
}

test("updateAliveShips writes zero instead of skipping it", () => {
    const aliveShipsElement = {value: "5"};
    const context = loadGameScript({
        elements: {aliveShips: aliveShipsElement}
    });

    context.updateAliveShips(0);

    assert.equal(aliveShipsElement.value, "0");
});

test("updateAliveShips ignores nullish values", () => {
    const aliveShipsElement = {value: "5"};
    const context = loadGameScript({
        elements: {aliveShips: aliveShipsElement}
    });

    context.updateAliveShips(null);
    assert.equal(aliveShipsElement.value, "5");

    context.updateAliveShips(undefined);
    assert.equal(aliveShipsElement.value, "5");
});

test("sendFireRequest falls back to a generic alert when the server returns HTML", () => {
    const alerts = [];
    const context = loadGameScript({
        elements: {
            gameId: {value: "match-1"}
        },
        responses: [
            {
                status: 502,
                responseText: "<html><body>Bad Gateway</body></html>"
            }
        ],
        alerts
    });

    assert.doesNotThrow(() => {
        context.sendFireRequest("1x1");
    });
    assert.deepEqual(alerts, ["Unable to fire at the opponent right now."]);
    assert.equal(context.window.location.href, "/gameId/match-1");
});

test("createRematch falls back to a generic alert when the server returns an empty body", () => {
    const alerts = [];
    const context = loadGameScript({
        elements: {
            gameId: {value: "match-2"}
        },
        responses: [
            {
                status: 500,
                responseText: ""
            }
        ],
        alerts
    });

    assert.doesNotThrow(() => {
        context.createRematch();
    });
    assert.deepEqual(alerts, ["Unable to start a rematch right now."]);
});

test("createRematch uses POST for rematch creation", () => {
    const context = loadGameScript({
        elements: {
            gameId: {value: "match-2"}
        },
        responses: [
            {
                status: 200,
                responseText: JSON.stringify({
                    game_id: "match-3"
                })
            }
        ]
    });

    context.createRematch();

    assert.equal(context.window.location.href, "/gameId/match-3");
    assert.equal(context.XMLHttpRequest.lastRequest.method, "POST");
    assert.equal(context.XMLHttpRequest.lastRequest.url, "/xl-spaceship/user/game/match-2/rematch");
});

test("updateGameTurn keeps polling while a remote human opponent is playing", () => {
    const elements = {
        userId: {value: "nikilipa"},
        opponentId: {value: "alice"},
        playerTurnId: {value: ""},
        wonId: {value: ""}
    };
    const context = loadGameScript({elements});
    let polledGameId = null;

    context.updateMyBoard = (gameId) => {
        polledGameId = gameId;
    };

    context.updateGameTurn("match-7", {player_turn: "alice"});

    assert.equal(elements.playerTurnId.value, "alice");
    assert.equal(polledGameId, "match-7");
});

test("updateGameTurn stops polling when it becomes the local player's turn", () => {
    const elements = {
        userId: {value: "nikilipa"},
        opponentId: {value: "alice"},
        playerTurnId: {value: ""},
        wonId: {value: ""}
    };
    const context = loadGameScript({elements});
    let pollCount = 0;

    context.updateMyBoard = () => {
        pollCount += 1;
    };

    context.updateGameTurn("match-8", {player_turn: "nikilipa"});

    assert.equal(elements.playerTurnId.value, "nikilipa");
    assert.equal(pollCount, 0);
});

test("sendFireRequest starts polling when the next turn belongs to a remote human opponent", () => {
    const elements = {
        gameId: {value: "match-9"},
        userId: {value: "nikilipa"},
        opponentId: {value: "alice"},
        playerTurnId: {value: "nikilipa"},
        wonId: {value: ""},
        salvo: {value: "1x1"}
    };
    const context = loadGameScript({
        elements,
        responses: [
            {
                status: 200,
                responseText: JSON.stringify({
                    salvo: {},
                    game: {
                        player_turn: "alice"
                    }
                })
            }
        ]
    });
    let polledGameId = null;

    context.updateMyBoard = (gameId) => {
        polledGameId = gameId;
    };

    context.sendFireRequest("1x1");

    assert.equal(elements.salvo.value, "");
    assert.equal(elements.playerTurnId.value, "alice");
    assert.equal(polledGameId, "match-9");
});
