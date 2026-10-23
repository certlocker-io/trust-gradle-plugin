<img align="right" alt="Certlocker" src="https://certlocker.io/logo-white-32.png" width="32" height="32">

# Trust Gradle Plugin
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)

This plugin exposes [`TrustService`](https://github.com/certlocker-io/trust-java-client) to a Gradle build so Groovy can fetch secrets, certificates and tokens from a running [Trust](https://trust.certlocker.io) application.

The current use case is a Google Artifact Registry service-account file for the `trust` build. The plugin is not specific to that secret. Any secret `TrustService` can read is available.

Requires Java 21 and Gradle 9.

The plugin depends on `io.certlocker:trust-client:1.0.1`. Publish that client to Maven Central before publishing this plugin. For local development before then, install the client with `./gradlew publishToMavenLocal` in its repository and use `-PuseMavenLocal=true` when building this plugin.

## Build configuration

**Plugin**

```groovy
plugins {
    id 'io.certlocker.trust' version '1.0.0'
}
```

Until the plugin is published, resolve it from Maven Local:

```groovy
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
    }
}
```

Publish this project first with `./gradlew publishToMavenLocal -PuseMavenLocal=true`.

## Getting started

The API key is a Trust token. Take it from the environment. Do not commit it, and do not print secret values into the build log.

`trust.url` defaults to `TRUST_URL`. `trust.apiKey` defaults to `TRUST_API_KEY`. Either can be set in the build.

```groovy
plugins {
    id 'io.certlocker.trust' version '1.0.0'
}

trust {
    url = 'https://trust.certlocker.io'
    apiKey = System.getenv('TRUST_API_KEY')
}

def secret = trust.service.fetchSecret('SECRET_ID')
```

`trust.service` is a `io.certlocker.trustclient.service.TrustService`. Groovy calls the client methods directly:

- `fetchSecret`, `getSecret`, `getSecretAsPem`, `getSecretAsConfiguration`, `getSecretAsPrivateKey`, `getSecretAsPassword`, `getSecretAsCredentials`
- `getCertificate`, `fetchCertificate`, `fetchCertificateBundle`
- `getToken`

The client is created the first time `trust.service` is used, and closed when the build finishes. Applying the plugin does not call Trust. A fetch runs when the `TrustService` method is called. In `settings.gradle` that is during settings evaluation, on every build. `trust.connectTimeout` overrides the client default of 10 seconds.

Leave the configuration cache disabled. The API key is a build-service parameter, and with the cache enabled Gradle can store that key and replay an earlier fetch.

## Artifact Registry service account

Fetch the service-account JSON during settings evaluation and use it as the Maven password. `_json_key` expects the raw JSON. If the secret is already base64, use username `_json_key_base64` instead.

`pluginManagement` cannot use this plugin. That block is resolved in order to load the plugin. `buildscript` repositories are also separate from `dependencyResolutionManagement`. The settings script can pass the JSON to the root build through `gradle.ext` when the buildscript classpath needs the same credential. Do not log that value.

```groovy
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
    }
}

plugins {
    id 'io.certlocker.trust' version '1.0.0'
}

trust {
    url = 'https://trust.certlocker.io'
    apiKey = System.getenv('TRUST_API_KEY')
}

def serviceAccount = trust.service.fetchSecret('SECRET_ID')
gradle.ext.garServiceAccount = serviceAccount

dependencyResolutionManagement {
    repositories {
        maven {
            name = 'certlockerGar'
            url = 'https://REGION-maven.pkg.dev/PROJECT/REPO'
            content {
                includeGroup 'certlocker.io'
            }
            credentials {
                username = '_json_key'
                password = serviceAccount
            }
            authentication {
                basic(BasicAuthentication)
            }
        }
        mavenCentral()
    }
}
```

Keep the content filter. An unfiltered Artifact Registry repository is probed for every public artifact.

`buildscript` repositories do not use `dependencyResolutionManagement`. Read the same in-memory JSON from the Gradle object. Do not print it.

```groovy
buildscript {
    def serviceAccount = gradle.garServiceAccount
    repositories {
        maven {
            name = 'certlockerGar'
            url = 'https://REGION-maven.pkg.dev/PROJECT/REPO'
            content {
                includeGroup 'certlocker.io'
            }
            credentials {
                username = '_json_key'
                password = serviceAccount
            }
            authentication {
                basic(BasicAuthentication)
            }
        }
        mavenCentral()
    }
}
```

The plugin does not write the service account to a file. `_json_key` takes the JSON string. Use `_json_key_base64` when the secret is already base64.

The same plugin id works in a project build script when the secret is needed after settings evaluation. Each application has its own client. A subproject can use the root client with `rootProject.trust.service` when the plugin is applied to the root project.

## Build

```bash
./gradlew test -PuseMavenLocal=true
./gradlew publishToMavenLocal -PuseMavenLocal=true
```

`test` does not call a live Trust application.

## License

Licensed under GNU General Public License v3.0. See the [LICENSE](LICENSE) file for details.

## Publishing

See [the publishing guide](docs/publishing.md) for Plugin Portal account setup and automated GitHub releases.
