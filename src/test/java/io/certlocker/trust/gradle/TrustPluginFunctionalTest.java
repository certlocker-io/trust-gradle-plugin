/*
 * Copyright (c) 2026.  Certlocker (tm)
 * All rights reserved
 */

package io.certlocker.trust.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Groovy build scripts can call {@code TrustService} through {@code trust.service}.
 *
 * @author dhudson
 * 23/10/2026 - 15:30
 */
public class TrustPluginFunctionalTest {

    private static final String SERVICE_CLASS = "io.certlocker.trustclient.service.TrustService";

    @TempDir
    Path projectDir;

    @Test
    public void groovyCanCallTrustServiceFromAProject() throws IOException {
        write(projectDir.resolve("settings.gradle"), "rootProject.name = 'trust-plugin-smoke'\n");
        write(projectDir.resolve("build.gradle"), """
                plugins {
                    id 'io.certlocker.trust'
                }

                trust {
                    url = 'https://trust.example.com'
                    apiKey = 'test-key'
                    connectTimeout = java.time.Duration.ofSeconds(3)
                }

                tasks.register('showService') {
                    doLast {
                        def service = trust.service
                        println "trustService=${service.class.name}"
                        println "hasFetchSecret=${!service.metaClass.respondsTo(service, 'fetchSecret', String).isEmpty()}"
                        println "hasGetSecretAsConfiguration=${!service.metaClass.respondsTo(service, 'getSecretAsConfiguration', String).isEmpty()}"
                        println "hasFetchCertificate=${!service.metaClass.respondsTo(service, 'fetchCertificate', String).isEmpty()}"
                        println "hasGetToken=${!service.metaClass.respondsTo(service, 'getToken', String).isEmpty()}"
                    }
                }
                """);

        BuildResult result = runner("showService").build();
        String output = result.getOutput();

        assertTrue(output.contains("trustService=" + SERVICE_CLASS), output);
        assertTrue(output.contains("hasFetchSecret=true"), output);
        assertTrue(result.getOutput().contains("hasGetSecretAsConfiguration=true"));
        assertTrue(result.getOutput().contains("hasFetchCertificate=true"));
        assertTrue(result.getOutput().contains("hasGetToken=true"));
    }

    @Test
    public void groovyCanCallTrustServiceFromSettings() throws IOException {
        write(projectDir.resolve("settings.gradle"), """
                plugins {
                    id 'io.certlocker.trust'
                }

                trust {
                    url = 'https://trust.example.com'
                    apiKey = 'test-key'
                }

                def service = trust.service
                gradle.ext.trustServiceClass = service.class.name
                rootProject.name = 'trust-settings-smoke'
                """);
        write(projectDir.resolve("build.gradle"), """
                tasks.register('showService') {
                    doLast {
                        println "trustService=${gradle.trustServiceClass}"
                    }
                }
                """);

        BuildResult result = runner("showService").build();
        assertTrue(result.getOutput().contains("trustService=" + SERVICE_CLASS));
    }

    @Test
    public void readsUrlAndApiKeyFromTheEnvironment() throws IOException {
        write(projectDir.resolve("settings.gradle"), "rootProject.name = 'trust-env-smoke'\n");
        write(projectDir.resolve("build.gradle"), """
                plugins {
                    id 'io.certlocker.trust'
                }

                tasks.register('showService') {
                    doLast {
                        println "trustService=${trust.service.class.name}"
                    }
                }
                """);

        BuildResult result = runner("showService")
                .withEnvironment(environment(Map.of(
                        TrustExtension.URL_ENV, "https://trust.example.com",
                        TrustExtension.API_KEY_ENV, "test-key")))
                .build();

        assertTrue(result.getOutput().contains("trustService=" + SERVICE_CLASS));
    }

    @Test
    public void failsWhenTheUrlIsMissing() throws IOException {
        write(projectDir.resolve("settings.gradle"), "rootProject.name = 'trust-missing-smoke'\n");
        write(projectDir.resolve("build.gradle"), """
                plugins {
                    id 'io.certlocker.trust'
                }

                tasks.register('showService') {
                    doLast {
                        trust.service.class.name
                    }
                }
                """);

        BuildResult result = runner("showService")
                .withEnvironment(environmentWithout(TrustExtension.URL_ENV, TrustExtension.API_KEY_ENV))
                .buildAndFail();

        assertTrue(result.getOutput().contains("trust.url is required"));
    }

    private GradleRunner runner(String... arguments) {
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments(arguments);
    }

    private static Map<String, String> environment(Map<String, String> overrides) {
        Map<String, String> env = new HashMap<>(System.getenv());
        env.putAll(overrides);
        return env;
    }

    private static Map<String, String> environmentWithout(String... names) {
        Map<String, String> env = new HashMap<>(System.getenv());
        for (String name : names) {
            env.remove(name);
        }
        return env;
    }

    private static void write(Path path, String contents) throws IOException {
        Files.writeString(path, contents);
    }
}
