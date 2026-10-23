/*
 * Copyright (c) 2026.  Certlocker (tm)
 * All rights reserved
 */

package io.certlocker.trust.gradle;

import io.certlocker.trustclient.service.TrustService;
import org.gradle.api.GradleException;
import org.gradle.api.invocation.Gradle;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.services.BuildServiceRegistry;

import javax.inject.Inject;
import java.time.Duration;

/**
 * Connection settings for a Trust application, and access to its {@link TrustService}.
 * <p>
 * {@code trust.url} defaults to the {@value #URL_ENV} environment variable.
 * {@code trust.apiKey} defaults to the {@value #API_KEY_ENV} environment variable.
 * The API key is sent as the {@code x-api-token} header. Do not commit it.
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
     * Environment variable used when {@code trust.apiKey} is not set.
     */
    public static final String API_KEY_ENV = "TRUST_API_KEY";

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
     * API key sent as the {@code x-api-token} header.
     *
     * @return API key property
     */
    public abstract Property<String> getApiKey();

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
     * @throws GradleException if the URL or API key is missing
     */
    public synchronized TrustService getService() {
        if (service != null) {
            return service;
        }

        String url = required(getUrl(), "trust.url", URL_ENV);
        String apiKey = required(getApiKey(), "trust.apiKey", API_KEY_ENV);

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

    private static String required(Property<String> property, String name, String environmentVariable) {
        String value = property.getOrNull();
        if (value == null || value.isBlank()) {
            throw new GradleException(name + " is required. Set " + name
                    + " or " + environmentVariable + " before using trust.service.");
        }
        return value;
    }
}
