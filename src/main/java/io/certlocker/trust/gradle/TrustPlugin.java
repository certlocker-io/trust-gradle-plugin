/*
 * Copyright (c) 2026.  Certlocker (tm)
 * All rights reserved
 */

package io.certlocker.trust.gradle;

import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.initialization.Settings;
import org.gradle.api.invocation.Gradle;
import org.gradle.api.plugins.ExtensionAware;
import org.gradle.api.provider.ProviderFactory;

/**
 * Exposes a {@link TrustExtension} to Groovy builds.
 * <p>
 * Apply {@code io.certlocker.trust} in a project build script or in {@code settings.gradle}.
 * The extension is not connected to Trust until {@code trust.service} is used.
 *
 * @author dhudson
 * 23/10/2026 - 15:30
 */
public class TrustPlugin implements Plugin<Object> {

    /**
     * Adds the {@code trust} extension to a project or to settings.
     *
     * @param target the project or settings object
     */
    @Override
    public void apply(Object target) {
        switch (target) {
            case Project project -> register(project, project.getGradle(), project.getProviders());
            case Settings settings -> register(settings, settings.getGradle(), settings.getProviders());
            default -> throw new GradleException(
                    "The Trust plugin can only be applied to a project or to settings.");
        }
    }

    /**
     * Creates the {@code trust} extension and applies environment-variable conventions.
     *
     * @param target project or settings that receives the extension
     * @param gradle build that owns the shared Trust client
     * @param providers provider factory used to read environment variables
     */
    private static void register(ExtensionAware target, Gradle gradle, ProviderFactory providers) {
        TrustExtension extension = target.getExtensions().create(
                TrustExtension.NAME, TrustExtension.class, gradle);
        extension.getUrl().convention(providers.environmentVariable(TrustExtension.URL_ENV));
        extension.getApiToken().convention(providers.environmentVariable(TrustExtension.API_TOKEN_ENV));
        extension.getAccountId().convention(providers.environmentVariable(TrustExtension.ACCOUNT_ID_ENV));
        extension.getOidcAudience().convention(providers.environmentVariable(TrustExtension.OIDC_AUDIENCE_ENV));
        extension.getDeviceId().convention(providers.environmentVariable(TrustExtension.DEVICE_ID_ENV));
        extension.getDevicePrivateKeyPath().convention(
                providers.environmentVariable(TrustExtension.DEVICE_PRIVATE_KEY_ENV));
        extension.getDeviceKeyId().convention(providers.environmentVariable(TrustExtension.DEVICE_KEY_ID_ENV));
        extension.getOidcTokenType().convention(providers.environmentVariable(TrustExtension.OIDC_TOKEN_TYPE_ENV));
    }
}
