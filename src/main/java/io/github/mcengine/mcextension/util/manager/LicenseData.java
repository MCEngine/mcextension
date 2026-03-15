package io.github.mcengine.mcextension.util.manager;

/**
 * Immutable payload model used for batch license verification requests.
 *
 * @param extensionId extension identifier used as the result-map key
 * @param url license endpoint associated with the extension
 * @param token extension-provided license token sent to the endpoint
 */
public record LicenseData(String extensionId, String url, String token) {}
