/*
 * Drives the Claude panel on the dashboard.
 *
 * Served as a static file rather than inlined in weather.html on purpose: Qute treats "{" as the
 * start of an expression, so JavaScript object literals inside a <script> block would be parsed
 * as template syntax and fail the build.
 */
(function () {
    'use strict';

    var toggle = document.getElementById('claude-toggle');
    var body = document.getElementById('claude-body');
    var form = document.getElementById('claude-form');
    var input = document.getElementById('claude-question');
    var submit = document.getElementById('claude-submit');
    var answer = document.getElementById('claude-answer');

    // The server renders the toggle disabled by default. The form only exists when an API key is
    // configured, so enable the control after the complete DOM is available.
    if (!toggle || !form) {
        return;
    }
    toggle.disabled = false;

    toggle.addEventListener('change', function () {
        body.hidden = !toggle.checked;
        if (toggle.checked) {
            input.focus();
        }
    });

    function render(status, text) {
        answer.hidden = false;
        answer.className = 'answer answer-' + status;
        answer.textContent = text;
    }

    form.addEventListener('submit', function (event) {
        event.preventDefault();

        var question = input.value.trim();
        if (question === '') {
            return;
        }

        submit.disabled = true;
        render('pending', 'Asking Claude…');

        fetch('/api/ask', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ question: question })
        }).then(function (response) {
            return response.json();
        }).then(function (payload) {
            render(payload.status || 'failed', payload.text || 'No answer was returned.');
        }).catch(function () {
            render('failed', 'Could not reach the server.');
        }).finally(function () {
            submit.disabled = false;
        });
    });
})();
