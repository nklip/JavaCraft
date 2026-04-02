// This app loads plain browser scripts via <script> tags, so shared helpers need a global home.
// In the browser, 'window' is the global object for page scripts.
// window.xlAjax is a project-defined namespace on the browser's global window object, not a built-in API.
//
// The line below means "reuse the existing xlAjax object if one is already there, otherwise create an empty one".
// Keeping helpers under window.xlAjax avoids scattering standalone globals like extractAjaxErrorMessage
// across the page while still letting index.js and game.js reuse the same function.
window.xlAjax = window.xlAjax || {};

// adds a shared function ('extractAjaxErrorMessage') onto that object ('window.xlAjax')
window.xlAjax.extractAjaxErrorMessage = function(httpRequest, fallbackMessage) {
    const responseText = typeof httpRequest.responseText === "string"
        ? httpRequest.responseText.trim()
        : "";

    if (!responseText) {
        return fallbackMessage;
    }

    try {
        const jsonError = JSON.parse(responseText);
        return jsonError.error_message || jsonError.error || fallbackMessage;
    } catch (error) {
        return responseText.startsWith("<") ? fallbackMessage : responseText;
    }
};
