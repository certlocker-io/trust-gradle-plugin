/*
 * Copyright (c) 2026.  Certlocker (tm)
 * All rights reserved
 */

package io.certlocker.trust.gradle;

import io.certlocker.trustclient.model.token.TokenType;
import io.certlocker.trustclient.oidc.DeviceOidcConfig;
import io.certlocker.trustclient.oidc.AzureDevOpsOidc;
import io.certlocker.trustclient.oidc.AzureIdentityToken;
import io.certlocker.trustclient.oidc.GcpIdentityToken;
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
 * Auth precedence:
 * <ol>
 *   <li>DEVICE OIDC — when {@code trust.deviceId} and {@code trust.devicePrivateKeyPath}
 *       are set (with {@code trust.oidcAudience}): mint Ed25519/{@code EdDSA} JWT → exchange</li>
 *   <li>Ambient GitHub Actions OIDC → Gateway exchange (when {@code ACTIONS_ID_TOKEN_*} present
 *       and {@code trust.oidcAudience} is set)</li>
 *   <li>Ambient GCP identity token → Gateway exchange (metadata / Cloud Run when
 *       {@code trust.oidcAudience} is set)</li>
 *   <li>Ambient Azure managed identity → Gateway exchange (when {@code trust.oidcAudience} is set)</li>
 *   <li>Ambient Azure DevOps Pipelines OIDC → Gateway exchange
 *       ({@code SYSTEM_OIDCREQUESTURI} + {@code SYSTEM_ACCESSTOKEN}; no Trust audience required)</li>
 *   <li>Break-glass {@code trust.apiToken} / {@value #API_TOKEN_ENV} (explicit only — not the CI happy path)</li>
 * </ol>
 * {@code trust.url} defaults to the {@value #URL_ENV} environment variable.
 * {@code trust.oidcAudience} defaults to {@value #OIDC_AUDIENCE_ENV}.
 * {@code trust.accountId} defaults to {@value #ACCOUNT_ID_ENV} (required for break-glass;
 * OIDC exchange supplies accountId when unset).
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
     * Environment variable used when {@code trust.apiToken} is not set (break-glass only).
     */
    public static final String API_TOKEN_ENV = "TRUST_API_TOKEN";

    /**
     * Per-account OIDC audience, e.g. {@code https://trust.certlocker.io/oidc/<accountId>}.
     */
    public static final String OIDC_AUDIENCE_ENV = "TRUST_OIDC_AUDIENCE";

    /**
     * Tenant account id for Gateway paths ({@code /v1/{resource}/{accountId}/…}).
     */
    public static final String ACCOUNT_ID_ENV = "TRUST_ACCOUNT_ID";

    /**
     * Enrolled DEVICE {@code device_id} claim.
     */
    public static final String DEVICE_ID_ENV = "TRUST_OIDC_DEVICE_ID";

    /**
     * Path to PKCS#8 Ed25519 private key PEM from ui-edge enroll download.
     */
    public static final String DEVICE_PRIVATE_KEY_ENV = "TRUST_OIDC_DEVICE_PRIVATE_KEY";

    /**
     * Optional JWT {@code kid} matching enrolled principal {@code keyId}.
     */
    public static final String DEVICE_KEY_ID_ENV = "TRUST_OIDC_DEVICE_KEY_ID";

    /**
     * Optional OIDC exchange mint type ({@code SECRET}, {@code CERTIFICATE}, {@code MCP}).
     * When unset, Gateway uses the principal's defaultTokenType.
     */
    public static final String OIDC_TOKEN_TYPE_ENV = "TRUST_OIDC_TOKEN_TYPE";

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

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        if (second != null && !second.isBlank()) {
            return second.trim();
        }
        return null;
    }

    /**
     * Returns the property value when it is non-blank.
     *
     * @param property            value to read
     * @param name                property name used in the failure message
     * @param environmentVariable environment variable named in the failure message
     * @return the property value
     * @throws GradleException if the value is missing or blank
     */
    private static String required(Property<String> property, String name, String environmentVariable) {
        String value = property.getOrNull();
        if (value == null || value.isBlank()) {
            throw new GradleException(name + " is required. Set " + name
                    + " or " + environmentVariable + " before using trust.service.");
        }
        return value;
    }

    /**
     * Trust base URL. An absolute {@code http} or {@code https} URL.
     *
     * @return URL property
     */
    public abstract Property<String> getUrl();

    /**
     * Break-glass API token sent as the {@code x-api-token} header.
     * Prefer ambient OIDC in CI; do not centre workflows on this property.
     *
     * @return API token property
     */
    public abstract Property<String> getApiToken();

    /**
     * Tenant account id for Gateway asset paths. Required for break-glass apiToken;
     * optional for OIDC (taken from the exchange response when unset).
     *
     * @return account id property
     */
    public abstract Property<String> getAccountId();

    /** Explicitly use pre-account Gateway routes during rollout. */
    public abstract Property<Boolean> getLegacyApi();

    /**
     * Per-account OIDC audience for GitHub Actions and DEVICE federation.
     *
     * @return OIDC audience property
     */
    public abstract Property<String> getOidcAudience();

    /**
     * Enrolled DEVICE {@code device_id}. Together with {@link #getDevicePrivateKeyPath()}
     * enables DEVICE OIDC mint + exchange.
     *
     * @return device id property
     */
    public abstract Property<String> getDeviceId();

    /**
     * Path to the PKCS#8 Ed25519 private key PEM downloaded at DEVICE enroll.
     * Never commit this file.
     *
     * @return private key path property
     */
    public abstract Property<String> getDevicePrivateKeyPath();

    /**
     * Optional JWT {@code kid}; must match enrolled principal {@code keyId} when that field is set.
     *
     * @return key id property
     */
    public abstract Property<String> getDeviceKeyId();

    /**
     * Optional DEVICE JWT lifetime in minutes (default 5).
     *
     * @return ttl minutes property
     */
    public abstract Property<Integer> getDeviceJwtTtlMinutes();

    /**
     * Optional OIDC exchange mint type: {@code SECRET}, {@code CERTIFICATE}, or {@code MCP}.
     * Must be allowed on the matched principal. When unset, Gateway uses the principal default.
     *
     * @return token type property
     */
    public abstract Property<String> getOidcTokenType();

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
        ResolvedAuth auth = resolveAuth(url);

        BuildServiceRegistry services = gradle.getSharedServices();
        String serviceName = "trustClient" + Integer.toHexString(System.identityHashCode(this));
        Provider<TrustClientService> provider = services.registerIfAbsent(
                serviceName,
                TrustClientService.class,
                spec -> {
                    spec.getParameters().getUrl().set(url);
                    spec.getParameters().getApiToken().set(auth.apiToken());
                    spec.getParameters().getAccountId().set(auth.accountId());
                    spec.getParameters().getLegacyApi().set(getLegacyApi().getOrElse(false));
                    if (getConnectTimeout().isPresent()) {
                        spec.getParameters().getConnectTimeoutMillis().set(getConnectTimeout().get().toMillis());
                    }
                });
        service = provider.get().getTrustService();
        return service;
    }

    /**
     * Resolves {@code x-api-token} and account id (DEVICE → GHA → GCP → Azure ambient → break-glass).
     */
    private ResolvedAuth resolveAuth(String url) {
        String audience = blankToNull(getOidcAudience().getOrNull());
        String deviceId = blankToNull(getDeviceId().getOrNull());
        String privateKeyPath = blankToNull(getDevicePrivateKeyPath().getOrNull());
        String configuredAccount = blankToNull(getAccountId().getOrNull());
        TokenType tokenType = parseOidcTokenType(blankToNull(getOidcTokenType().getOrNull()));

        if (deviceId != null && privateKeyPath != null) {
            if (audience == null) {
                throw new GradleException(
                        "trust.oidcAudience (or " + OIDC_AUDIENCE_ENV
                                + ") is required for DEVICE OIDC with trust.deviceId / trust.devicePrivateKeyPath.");
            }
            try {
                DeviceOidcConfig.DeviceOidcConfigBuilder builder = DeviceOidcConfig.builder()
                        .audience(audience)
                        .deviceId(deviceId)
                        .privateKeyPath(privateKeyPath)
                        .keyId(blankToNull(getDeviceKeyId().getOrNull()));
                if (getDeviceJwtTtlMinutes().isPresent()) {
                    builder.ttlMinutes(getDeviceJwtTtlMinutes().get());
                }
                OidcExchangeResult result = TrustOidcExchange.fromDeviceOidc(
                        URI.create(url.trim()), builder.build(), tokenType);
                String accountId = firstNonBlank(configuredAccount, result.accountId());
                if (accountId == null) {
                    throw new GradleException("DEVICE OIDC exchange response missing accountId");
                }
                return new ResolvedAuth(result.tokenId(), accountId);
            } catch (GradleException e) {
                throw e;
            } catch (Exception e) {
                throw new GradleException(
                        "Trust DEVICE OIDC exchange failed. Check trust.deviceId, trust.devicePrivateKeyPath, "
                                + "trust.oidcAudience / " + OIDC_AUDIENCE_ENV
                                + ", and that a DEVICE principal is enrolled. " + e.getMessage(), e);
            }
        }

        if (audience != null && GitHubActionsOidc.isAvailable()) {
            try {
                OidcExchangeResult result = TrustOidcExchange.fromGitHubActions(
                        URI.create(url.trim()), audience, tokenType);
                String accountId = firstNonBlank(configuredAccount, result.accountId());
                if (accountId == null) {
                    throw new GradleException("OIDC exchange response missing accountId");
                }
                return new ResolvedAuth(result.tokenId(), accountId);
            } catch (GradleException e) {
                throw e;
            } catch (Exception e) {
                throw new GradleException(
                        "Trust OIDC exchange failed. Check trust.oidcAudience / " + OIDC_AUDIENCE_ENV
                                + " and that an OIDC principal is seeded for this repository. "
                                + e.getMessage(), e);
            }
        }

        if (audience != null && GcpIdentityToken.isAvailable()) {
            try {
                OidcExchangeResult result = TrustOidcExchange.fromGcp(
                        URI.create(url.trim()), audience, tokenType);
                String accountId = firstNonBlank(configuredAccount, result.accountId());
                if (accountId == null) {
                    throw new GradleException("OIDC exchange response missing accountId");
                }
                return new ResolvedAuth(result.tokenId(), accountId);
            } catch (GradleException e) {
                throw e;
            } catch (Exception e) {
                throw new GradleException(
                        "Trust GCP OIDC exchange failed. Check trust.oidcAudience / " + OIDC_AUDIENCE_ENV
                                + " and that a GCP principal is bound to this service account. "
                                + e.getMessage(), e);
            }
        }

        if (audience != null && AzureIdentityToken.isAvailable()) {
            try {
                OidcExchangeResult result = TrustOidcExchange.fromAzure(
                        URI.create(url.trim()), audience, tokenType);
                String accountId = firstNonBlank(configuredAccount, result.accountId());
                if (accountId == null) {
                    throw new GradleException("OIDC exchange response missing accountId");
                }
                return new ResolvedAuth(result.tokenId(), accountId);
            } catch (GradleException e) {
                throw e;
            } catch (Exception e) {
                throw new GradleException(
                        "Trust Azure OIDC exchange failed. Check trust.oidcAudience / " + OIDC_AUDIENCE_ENV
                                + " and that an AZURE principal is bound to this managed identity. "
                                + e.getMessage(), e);
            }
        }

        if (AzureDevOpsOidc.isAvailable()) {
            try {
                OidcExchangeResult result = TrustOidcExchange.fromAzureDevOps(
                        URI.create(url.trim()), tokenType);
                String accountId = firstNonBlank(configuredAccount, result.accountId());
                if (accountId == null) {
                    throw new GradleException("OIDC exchange response missing accountId");
                }
                return new ResolvedAuth(result.tokenId(), accountId);
            } catch (GradleException e) {
                throw e;
            } catch (Exception e) {
                throw new GradleException(
                        "Trust Azure DevOps OIDC exchange failed. Ensure SYSTEM_OIDCREQUESTURI and "
                                + "SYSTEM_ACCESSTOKEN are set, and an AZURE_DEVOPS principal is bound to this "
                                + "pipeline subject (sc:// or p://). " + e.getMessage(), e);
            }
        }

        String apiToken = blankToNull(getApiToken().getOrNull());
        if (apiToken != null) {
            if (configuredAccount == null && !getLegacyApi().getOrElse(false)) {
                throw new GradleException(
                        "trust.accountId is required with trust.apiToken. Set trust.accountId or "
                                + ACCOUNT_ID_ENV + ".");
            }
            return new ResolvedAuth(apiToken, configuredAccount == null ? "" : configuredAccount);
        }

        throw new GradleException(
                "Trust credentials required. DEVICE: set trust.deviceId + trust.devicePrivateKeyPath "
                        + "(and trust.oidcAudience). CI: set trust.oidcAudience (or " + OIDC_AUDIENCE_ENV
                        + ") with workflow permissions id-token: write, or run on GCP/Azure with "
                        + "metadata / managed identity, or Azure DevOps with SYSTEM_OIDCREQUESTURI. "
                        + "Break-glass: set trust.apiToken + trust.accountId (or env "
                        + API_TOKEN_ENV + " / " + ACCOUNT_ID_ENV + ").");
    }

    private static TokenType parseOidcTokenType(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            TokenType type = TokenType.valueOf(raw.trim().toUpperCase());
            if (type != TokenType.SECRET && type != TokenType.CERTIFICATE && type != TokenType.MCP) {
                throw new GradleException(
                        "trust.oidcTokenType must be SECRET, CERTIFICATE, or MCP (got " + raw + ")");
            }
            return type;
        } catch (IllegalArgumentException e) {
            throw new GradleException(
                    "trust.oidcTokenType must be SECRET, CERTIFICATE, or MCP (got " + raw + ")");
        }
    }

    private record ResolvedAuth(String apiToken, String accountId) {
    }
}
