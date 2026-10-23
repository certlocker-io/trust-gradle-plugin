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

    private static void register(ExtensionAware target, Gradle gradle, ProviderFactory providers) {
        TrustExtension extension = target.getExtensions().create(
                TrustExtension.NAME, TrustExtension.class, gradle);
        extension.getUrl().convention(providers.environmentVariable(TrustExtension.URL_ENV));
        extension.getApiKey().convention(providers.environmentVariable(TrustExtension.API_KEY_ENV));
    }
}
