/*
 * Copyright (c) 2026.  Certlocker (tm)
 * All rights reserved
 */

package io.certlocker.trust.gradle;

import io.certlocker.trustclient.oidc.GitHubActionsOidc;
import io.certlocker.trustclient.oidc.OidcExchangeResult;
import io.certlocker.trustclient.oidc.TrustOidcExchange;
import io.certlocker.trustclient.service.TrustService;
import org.gradle.api.GradleException;
import org.gradle.api.invocation.Gradle;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.services.BuildServiceRegistry;

import javax.inject.Inject;
import java.net.URI;
import java.time.Duration;

/**
 * Connection settings for a Trust application, and access to its {@link TrustService}.
 * <p>
 * Auth precedence (federated design):
 * <ol>
 *   <li>Ambient GitHub Actions OIDC → Gateway exchange (when {@code ACTIONS_ID_TOKEN_*} present
 *       and {@code trust.oidcAudience} is set)</li>
 *   <li>Break-glass {@code trust.apiKey} / {@value #API_KEY_ENV} (explicit only — not the CI happy path)</li>
 * </ol>
 * {@code trust.url} defaults to the {@value #URL_ENV} environment variable.
 * {@code trust.oidcAudience} defaults to {@value #OIDC_AUDIENCE_ENV}.
 * <p>
 * The client is created the first time {@link #getService()} is called and is closed
 * at the end of the build. Nothing is fetched until a {@link TrustService} method is called.
 *
 * @author dhudson
 * 23/10/2026 - 15:30
 */
public abstract class TrustExtension {

    /**
     * Extension name. Groovy uses {@code trust { } }.
     */
    public static final String NAME = "trust";

    /**
     * Environment variable used when {@code trust.url} is not set.
     */
    public static final String URL_ENV = "TRUST_URL";

    /**
     * Environment variable used when {@code trust.apiKey} is not set (break-glass only).
     */
    public static final String API_KEY_ENV = "TRUST_API_KEY";

    /**
     * Per-account OIDC audience, e.g. {@code https://trust.certlocker.io/oidc/<accountId>}.
     */
    public static final String OIDC_AUDIENCE_ENV = "TRUST_OIDC_AUDIENCE";

    private final Gradle gradle;
    private TrustService service;

    /**
     * Creates the extension. Gradle supplies the build instance.
     *
     * @param gradle build that owns the shared Trust client
     */
    @Inject
    public TrustExtension(Gradle gradle) {
        this.gradle = gradle;
    }

    /**
     * Trust base URL. An absolute {@code http} or {@code https} URL.
     *
     * @return URL property
     */
    public abstract Property<String> getUrl();

    /**
     * Break-glass API key sent as the {@code x-api-token} header.
     * Prefer ambient OIDC in CI; do not centre workflows on this property.
     *
     * @return API key property
     */
    public abstract Property<String> getApiKey();

    /**
     * Per-account OIDC audience for GitHub Actions (and later GCP) federation.
     *
     * @return OIDC audience property
     */
    public abstract Property<String> getOidcAudience();

    /**
     * HTTP connect timeout. When unset, the client uses its default of 10 seconds.
     *
     * @return connect timeout property
     */
    public abstract Property<Duration> getConnectTimeout();

    /**
     * Returns the {@link TrustService} for this build.
     * <p>
     * Groovy calls it as {@code trust.service}. The same instance is returned for
     * the rest of the build. The instance is loaded by the plugin class loader;
     * Groovy can call its methods without a compile-time dependency on the client.
     *
     * @return Trust service
     * @throws GradleException if credentials cannot be resolved
     */
    public synchronized TrustService getService() {
        if (service != null) {
            return service;
        }

        String url = required(getUrl(), "trust.url", URL_ENV);
        String apiKey = resolveCredential(url);

        BuildServiceRegistry services = gradle.getSharedServices();
        String serviceName = "trustClient" + Integer.toHexString(System.identityHashCode(this));
        Provider<TrustClientService> provider = services.registerIfAbsent(
                serviceName,
                TrustClientService.class,
                spec -> {
                    spec.getParameters().getUrl().set(url);
                    spec.getParameters().getApiKey().set(apiKey);
                    if (getConnectTimeout().isPresent()) {
                        spec.getParameters().getConnectTimeoutMillis().set(getConnectTimeout().get().toMillis());
                    }
                });
        service = provider.get().getTrustService();
        return service;
    }

    /**
     * 1) Ambient GitHub OIDC exchange when available + audience set.
     * 2) Break-glass apiKey.
     */
    private String resolveCredential(String url) {
        String audience = getOidcAudience().getOrNull();
        if (audience != null && !audience.isBlank() && GitHubActionsOidc.isAvailable()) {
            try {
                OidcExchangeResult result = TrustOidcExchange.fromGitHubActions(URI.create(url.trim()), audience.trim());
                return result.tokenId();
            } catch (Exception e) {
                throw new GradleException(
                        "Trust OIDC exchange failed. Check trust.oidcAudience / " + OIDC_AUDIENCE_ENV
                                + " and that an OIDC principal is seeded for this repository. "
                                + e.getMessage(), e);
            }
        }

        String apiKey = getApiKey().getOrNull();
        if (apiKey != null && !apiKey.isBlank()) {
            return apiKey;
        }

        throw new GradleException(
                "Trust credentials required. In CI set trust.oidcAudience (or " + OIDC_AUDIENCE_ENV
                        + ") with workflow permissions id-token: write. "
                        + "Break-glass: set trust.apiKey or " + API_KEY_ENV + ".");
    }

    private static String required(Property<String> property, String name, String environmentVariable) {
        String value = property.getOrNull();
        if (value == null || value.isBlank()) {
            throw new GradleException(name + " is required. Set " + name
                    + " or " + environmentVariable + " before using trust.service.");
        }
        return value;
    }
}
