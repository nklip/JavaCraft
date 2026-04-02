// we wait till page is loaded
document.addEventListener("DOMContentLoaded", (event) => {
    console.log("Page loaded.");

    updatePageAfterOpponentFirstTurn();

});

function updatePageAfterOpponentFirstTurn() {
    let gameId = document.getElementById("gameId").value;
    let gameTurn = {
        player_turn: document.getElementById("playerTurnId").value,
        won: document.getElementById("wonId").value
    };

    if (shouldPollForOpponentTurn(gameTurn)) {
        updateMyBoard(gameId);
    }
}

function shouldPollForOpponentTurn(gameTurn) {
    let userId = document.getElementById("userId").value;

    // Poll only while we are waiting for the other player to make a move.
    //
    // This uses JavaScript's left-to-right short-circuit evaluation:
    // 1. `gameTurn`
    //    Makes sure we have a turn object at all. If it is null/undefined,
    //    the whole expression stops here and returns a falsy value.
    // 2. `gameTurn.player_turn`
    //    Requires an active player turn. If the backend has not provided one,
    //    there is nothing useful to poll for yet.
    // 3. `!gameTurn.won`
    //    Stops polling once the game is over. A finished game may still carry
    //    old turn information, so we explicitly check that nobody has won.
    // 4. `gameTurn.player_turn !== userId`
    //    Poll only when it is somebody else's turn. If it is our turn, the UI
    //    should wait for local interaction instead of refreshing in a loop.
    //
    // When all four checks pass, it means:
    // - the turn payload exists
    // - there is an active current player
    // - the match is still running
    // - the current player is not us
    //
    // In other words: we are waiting for the opponent, so auto-refresh makes sense.
    return Boolean(gameTurn && gameTurn.player_turn && !gameTurn.won && gameTurn.player_turn !== userId);
}

function addShot(event) {
    let userId = document.getElementById("userId").value;
    let playerTurnId = document.getElementById("playerTurnId").value;

    if (userId == playerTurnId) { // ignore mouse clicks while it's not your turn
        let aliveShips = document.getElementById("aliveShips").value;

        let imgId = event.id;

        document.getElementById(imgId).removeAttribute("onclick");
        document.getElementById(imgId).setAttribute("class", "shot");

        // get salvo
        let salvo = document.getElementById("salvo").value;
        salvo = salvo + imgId;

        // get salvoCount
        let salvoCount = parseInt(document.getElementById("salvoCount").value);
        // update salvoCount
        salvoCount = salvoCount + 1;
        // save salvoCount
        document.getElementById("salvoCount").value = salvoCount;

        if (salvoCount >= aliveShips) {
            // send required salvo to backend
            sendFireRequest(salvo);
            // reset salvoCount
            document.getElementById("salvoCount").value = 0;
        } else {
            // update salve
            salvo = salvo + ",";
        }
        // save salvo
        document.getElementById("salvo").value = salvo;
    }
}

function sendFireRequest(salvo) {
    let gameId = document.getElementById("gameId").value;

    let array = new Array();
    array = salvo.split(",");

    let fireRequest = {}
    fireRequest["salvo"] = array;

    let httpRequest = new XMLHttpRequest();

    httpRequest.onreadystatechange = function() {
        if (httpRequest.readyState == XMLHttpRequest.DONE) { // XMLHttpRequest.DONE == 4
            if (httpRequest.status == 200) {
                let fireResponse = JSON.parse(httpRequest.responseText);
                let game = fireResponse.game;
                let salvo = fireResponse.salvo;

                // update opponent board
                for (const [key, value] of Object.entries(salvo)) {
                    if (value === "hit" || value === "kill") { // from backend
                        document.getElementById(key).setAttribute("class", "sunk");
                    }
                }

                updateGameTurn(gameId, game);

                // drop salvo value to empty
                document.getElementById("salvo").value = "";
            } else {
                // see ajax-errors.js
                alert(window.xlAjax.extractAjaxErrorMessage(httpRequest, "Unable to fire at the opponent right now."));
                // very crude approach as we are trying to resolve it with page update
                window.location.href = "/gameId/" + gameId;
            }
        }
    };

    httpRequest.open("PUT", "/xl-spaceship/user/game/" + gameId + "/fire", true);
    httpRequest.setRequestHeader('Content-Type', 'application/json; charset=utf-8');
    httpRequest.send(JSON.stringify(fireRequest));
}

function updateMyBoard(gameId) {
    setTimeout(() => {
        fetchGameStatus(gameId);
    }, 50); // update my board after 50 ms delay
}

function fetchGameStatus(gameId) {
    let httpRequest = new XMLHttpRequest();
    httpRequest.onreadystatechange = function() {
        if (httpRequest.readyState == XMLHttpRequest.DONE) { // XMLHttpRequest.DONE == 4
            if (httpRequest.status == 200) {
                let gameStatus = JSON.parse(httpRequest.responseText);

                let myBoard = document.getElementById('myBoard');
                myBoard.innerHTML = gameStatus.board;

                updateGameTurn(gameId, gameStatus.game);

                updateAliveShips(gameStatus.aliveShips);
            } else {
                // see ajax-errors.js
                alert(window.xlAjax.extractAjaxErrorMessage(httpRequest, "Unable to refresh the game right now."));
                // very crude approach as we are trying to resolve it with page update
                window.location.href = "/gameId/" + gameId;
            }
        }
    };

    httpRequest.open("GET", "/xl-spaceship/user/game/" + gameId + "/status");
    httpRequest.setRequestHeader('Content-Type', 'application/json; charset=utf-8');
    httpRequest.send();
}

function updateAliveShips(aliveShips) {
    // Keep the UI in sync even when the backend reports 0 ships left.
    // A plain truthy check would skip 0 and leave the previous count on screen.
    if (aliveShips !== null && aliveShips !== undefined) {
        document.getElementById("aliveShips").value = String(aliveShips);
    }
}

function updateGameTurn(gameId, gameTurn) {
    //  ╭─ nullish ──────╮ ╭─ not nullish ─────────────────────────────────╮
    // ┌───────────┬──────┬───────┬───┬────┬─────┬──────┬───┬─────────┬─────┐
    // │ undefined │ null │ false │ 0 │ "" │ ... │ true │ 1 │ "hello" │ ... │
    // └───────────┴──────┴───────┴───┴────┴─────┴──────┴───┴─────────┴─────┘
    //  ╰─ falsy ───────────────────────────────╯ ╰─ truthy ───────────────╯
    if (gameTurn.player_turn) { // check is not nullish
        document.getElementById("playerTurnId").value = gameTurn.player_turn;
    }

    if (gameTurn.won) { // check is not nullish
        document.getElementById("wonId").value = gameTurn.won;
    }

    checkGameOver();

    if (shouldPollForOpponentTurn(gameTurn)) {
        updateMyBoard(gameId);
    }
}

function checkGameOver() {
    let userId = document.getElementById("userId").value;
    let opponentId = document.getElementById("opponentId").value;
    let wonId = document.getElementById("wonId").value;

    if (userId === wonId) {
        alert("You won!");
        proposeRematch();
    } else if (opponentId === wonId) {
        alert("You lost!");
        proposeRematch();
    }
}

function proposeRematch() {
    if (confirm("Would you like a rematch?")) {
        createRematch();
    } else {
        // load index page
        window.location = "/";
    }
}

function createRematch() {
    let gameId = document.getElementById("gameId").value;

    let httpRequest = new XMLHttpRequest();

    httpRequest.onreadystatechange = function() {
        if (httpRequest.readyState == XMLHttpRequest.DONE) { // XMLHttpRequest.DONE == 4
            if (httpRequest.status == 200) {
                let id = JSON.parse(httpRequest.responseText).game_id;
                window.location.href = "/gameId/" + id;
            } else {
                // see ajax-errors.js
                alert(window.xlAjax.extractAjaxErrorMessage(httpRequest, "Unable to start a rematch right now."));
            }
        }
    };

    httpRequest.open("POST", "/xl-spaceship/user/game/" + gameId + "/rematch", true);
    httpRequest.send();
}
