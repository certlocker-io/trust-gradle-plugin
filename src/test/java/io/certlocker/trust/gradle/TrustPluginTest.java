/*
 * Copyright (c) 2026.  Certlocker (tm)
 * All rights reserved
 */

package io.certlocker.trust.gradle;

import io.certlocker.trustclient.service.TrustService;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author dhudson
 * 23/10/2026 - 15:30
 */
public class TrustPluginTest {

    @Test
    public void exposesOneTrustService() {
        TrustExtension trust = extension();
        trust.getUrl().set("https://trust.example.com");
        trust.getApiKey().set("test-key");
        trust.getConnectTimeout().set(Duration.ofSeconds(3));

        try (TrustService first = trust.getService()) {
            assertEquals(TrustService.class, first.getClass());
            assertSame(first, trust.getService());
        }
    }

    @Test
    public void requiresAUrl() {
        TrustExtension trust = extension();
        trust.getUrl().set("  ");
        trust.getApiKey().set("test-key");

        GradleException failure = assertThrows(GradleException.class, trust::getService);
        assertTrue(failure.getMessage().contains("trust.url is required"));
        assertTrue(failure.getMessage().contains(TrustExtension.URL_ENV));
    }

    @Test
    public void requiresAnApiKey() {
        TrustExtension trust = extension();
        trust.getUrl().set("https://trust.example.com");
        trust.getApiKey().set("  ");

        GradleException failure = assertThrows(GradleException.class, trust::getService);
        assertTrue(failure.getMessage().contains("trust.apiKey is required"));
        assertTrue(failure.getMessage().contains(TrustExtension.API_KEY_ENV));
    }

    @Test
    public void rejectsARelativeUrl() {
        TrustExtension trust = extension();
        trust.getUrl().set("trust.example.com");
        trust.getApiKey().set("test-key");

        RuntimeException failure = assertThrows(RuntimeException.class, trust::getService);
        assertTrue(causeChain(failure).contains("absolute http or https"), failure.toString());
    }

    private static String causeChain(Throwable failure) {
        StringBuilder chain = new StringBuilder();
        Throwable current = failure;
        while (current != null) {
            chain.append(current.getClass().getName()).append(": ").append(current.getMessage()).append('\n');
            current = current.getCause();
        }
        return chain.toString();
    }

    private static TrustExtension extension() {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply(TrustPlugin.class);
        return project.getExtensions().getByType(TrustExtension.class);
    }
}
