/*
 * Copyright (c) 2026.  Certlocker (tm)
 * All rights reserved
 */

package io.certlocker.trust.gradle;

import io.certlocker.trustclient.client.TrustClient;
import io.certlocker.trustclient.client.TrustClientBuilder;
import io.certlocker.trustclient.service.TrustService;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.Property;
import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;

import javax.inject.Inject;
import java.time.Duration;

/**
 * One {@link TrustService} for the build. Closed when the build finishes.
 * <p>
 * The API key is a service parameter so the client can be created by Gradle.
 * It is not logged.
 *
 * @author dhudson
 * 23/10/2026 - 15:30
 */
public abstract class TrustClientService implements BuildService<TrustClientService.Params>, AutoCloseable {

    private static final Logger theLogger = Logging.getLogger(TrustClientService.class);

    /**
     * Connection values captured when {@code trust.service} is first used.
     */
    public interface Params extends BuildServiceParameters {

        /**
         * Trust base URL.
         *
         * @return URL
         */
        Property<String> getUrl();

        /**
         * API key sent as {@code x-api-token}.
         *
         * @return API key
         */
        Property<String> getApiKey();

        /**
         * Connect timeout in milliseconds. Empty when the client default should be used.
         *
         * @return timeout in milliseconds
         */
        Property<Long> getConnectTimeoutMillis();
    }

    private final TrustService trustService;

    /**
     * Opens the Trust client from the captured URL and API key.
     */
    @Inject
    public TrustClientService() {
        String url = getParameters().getUrl().get();
        TrustClientBuilder builder = TrustClient.builder()
                .url(url)
                .apiKey(getParameters().getApiKey().get());
        Long timeoutMillis = getParameters().getConnectTimeoutMillis().getOrNull();
        if (timeoutMillis != null) {
            builder.connectTimeout(Duration.ofMillis(timeoutMillis));
        }
        trustService = new TrustService(builder);
        theLogger.info("Trust client configured for {}", url);
    }

    /**
     * Returns the service used by the build.
     *
     * @return Trust service
     */
    public TrustService getTrustService() {
        return trustService;
    }

    /**
     * Closes the underlying HTTP client.
     */
    @Override
    public void close() {
        trustService.close();
    }
}
