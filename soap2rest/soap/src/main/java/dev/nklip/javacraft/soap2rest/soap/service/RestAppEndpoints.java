package dev.nklip.javacraft.soap2rest.soap.service;

/**
 * Centralized REST endpoint templates used by SOAP order services and test stubs.
 * This keeps SOAP-to-REST integration paths aligned with the rest-app controllers.
 */
public final class RestAppEndpoints {

    private RestAppEndpoints() {
    }

    public static String smart(String accountId) {
        return "/api/v1/smart/%s".formatted(accountId);
    }

    public static String smartAsync(String accountId) {
        return smart(accountId) + "?async=true";
    }

    public static String smartAsyncResult(String requestId) {
        return "/api/v1/smart/async/%s".formatted(requestId);
    }

    public static String smartLatest(String accountId) {
        return smart(accountId) + "/latest";
    }

    public static String electric(String accountId) {
        return smart(accountId) + "/electric";
    }

    public static String electricLatest(String accountId) {
        return electric(accountId) + "/latest";
    }

    public static String gas(String accountId) {
        return smart(accountId) + "/gas";
    }

    public static String gasLatest(String accountId) {
        return gas(accountId) + "/latest";
    }
}
