<img align="right" alt="Certlocker" src="https://certlocker.io/logo-white-32.png" width="32" height="32">

# Trust Gradle Plugin
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)

This plugin exposes [`TrustService`](https://github.com/certlocker-io/trust-java-client) to a Gradle build so Groovy can fetch secrets, certificates and tokens from a running [Trust](https://trust.certlocker.io) application.

The current use case is a Google Artifact Registry service-account file for the `trust` build. The plugin is not specific to that secret. Any secret `TrustService` can read is available.

Requires Java 21 and Gradle 9.

The plugin depends on `io.certlocker:trust-client:1.0.4`. Publish that client to Maven Central before publishing this plugin. For local development before then, install the client with `./gradlew publishToMavenLocal` in its repository and use `-PuseMavenLocal=true` when building this plugin.


## Build configuration

**Plugin**

```groovy
plugins {
    id 'io.certlocker.trust' version '1.0.2'
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

Auth is resolved the first time `trust.service` is used. Prefer OIDC; use `apiToken` only as break-glass.

### Auth order

1. **Device OIDC** — `trust.deviceId` + `trust.devicePrivateKeyPath` (+ `trust.oidcAudience`)
2. **GitHub Actions** — ambient `ACTIONS_ID_TOKEN_*` + `trust.oidcAudience`
3. **GCP** — ambient metadata / Cloud Run identity token + `trust.oidcAudience`
4. **Azure (Entra managed identity)** — ambient IMDS + `trust.oidcAudience`
5. **Azure DevOps Pipelines** — ambient `SYSTEM_OIDCREQUESTURI` + `SYSTEM_ACCESSTOKEN` (no Trust audience required)
6. **Break-glass** — `trust.apiToken` + `trust.accountId`

Environment conventions (set when the Gradle property is unset):

| Property | Env |
|----------|-----|
| `trust.url` | `TRUST_URL` |
| `trust.oidcAudience` | `TRUST_OIDC_AUDIENCE` |
| `trust.oidcTokenType` | `TRUST_OIDC_TOKEN_TYPE` |
| `trust.deviceId` | `TRUST_OIDC_DEVICE_ID` |
| `trust.devicePrivateKeyPath` | `TRUST_OIDC_DEVICE_PRIVATE_KEY` |
| `trust.deviceKeyId` | `TRUST_OIDC_DEVICE_KEY_ID` |
| `trust.accountId` | `TRUST_ACCOUNT_ID` |
| `trust.apiToken` | `TRUST_API_TOKEN` |

`trust.accountId` is optional for OIDC (taken from the exchange response when unset) and **required** with break-glass `apiToken`. When set, it overrides the exchange `accountId`.

### `oidcTokenType` — SECRET / CERTIFICATE / MCP

Optional on every OIDC path. Must be allowed on the matched principal. When unset, Gateway uses the principal `defaultTokenType`.

```groovy
trust {
    url = System.getenv('TRUST_URL') ?: 'https://trust.certlocker.io'
    oidcAudience = System.getenv('TRUST_OIDC_AUDIENCE')
    oidcTokenType = 'SECRET' // or CERTIFICATE, or MCP
}
```

```bash
export TRUST_OIDC_TOKEN_TYPE=CERTIFICATE
```

### Device OIDC (enrolled machine)

Mint Ed25519 / `EdDSA` JWT from the enroll private key, exchange at Gateway, then fetch. Never commit the PEM.

```groovy
trust {
    url = System.getenv('TRUST_URL') ?: 'https://trust.certlocker.io'
    oidcAudience = System.getenv('TRUST_OIDC_AUDIENCE') // https://trust.certlocker.io/oidc/<accountId>
    deviceId = System.getenv('TRUST_OIDC_DEVICE_ID')
    devicePrivateKeyPath = System.getenv('TRUST_OIDC_DEVICE_PRIVATE_KEY') // PKCS#8 PEM path
    deviceKeyId = System.getenv('TRUST_OIDC_DEVICE_KEY_ID')               // optional kid
    // deviceJwtTtlMinutes = 5  // optional; default 5
    // oidcTokenType = 'SECRET'
    // accountId = System.getenv('TRUST_ACCOUNT_ID')  // optional override
}
```

```bash
export TRUST_URL=https://trust.certlocker.io
export TRUST_OIDC_AUDIENCE=https://trust.certlocker.io/oidc/<accountId>
export TRUST_OIDC_DEVICE_ID=<enrolled-device-id>
export TRUST_OIDC_DEVICE_PRIVATE_KEY=$HOME/.trust/device.pem
# optional:
# export TRUST_OIDC_DEVICE_KEY_ID=<enrolled-key-id>
# export TRUST_OIDC_TOKEN_TYPE=SECRET
# export TRUST_ACCOUNT_ID=<accountId>
```

### Ambient GitHub Actions OIDC

Workflow needs `permissions: id-token: write`. Do not set `apiToken`.

```yaml
# .github/workflows/build.yml
permissions:
  id-token: write
  contents: read
env:
  TRUST_URL: https://trust.certlocker.io
  TRUST_OIDC_AUDIENCE: https://trust.certlocker.io/oidc/<accountId>
  # TRUST_OIDC_TOKEN_TYPE: SECRET
```

```groovy
trust {
    url = System.getenv('TRUST_URL') ?: 'https://trust.certlocker.io'
    oidcAudience = System.getenv('TRUST_OIDC_AUDIENCE')
    // oidcTokenType = 'SECRET'
}
def secret = trust.service.fetchSecret('SECRET_ID')
```

### Ambient GCP OIDC

Runs on GCE / GKE Workload Identity / Cloud Run when the metadata server can mint an identity token for the Trust audience. Bind a GCP principal to the service-account email in Trust.

```bash
export TRUST_URL=https://trust.certlocker.io
export TRUST_OIDC_AUDIENCE=https://trust.certlocker.io/oidc/<accountId>
# export TRUST_OIDC_TOKEN_TYPE=SECRET
```

```groovy
trust {
    url = System.getenv('TRUST_URL') ?: 'https://trust.certlocker.io'
    oidcAudience = System.getenv('TRUST_OIDC_AUDIENCE')
}
```

### Ambient Azure (Entra managed identity)

Runs where Azure Instance Metadata can mint a token for the Trust audience. Bind an AZURE principal (object id / app id / subject) in Trust.

```bash
export TRUST_URL=https://trust.certlocker.io
export TRUST_OIDC_AUDIENCE=https://trust.certlocker.io/oidc/<accountId>
# export TRUST_OIDC_TOKEN_TYPE=SECRET
```

```groovy
trust {
    url = System.getenv('TRUST_URL') ?: 'https://trust.certlocker.io'
    oidcAudience = System.getenv('TRUST_OIDC_AUDIENCE')
}
```

### Ambient Azure DevOps Pipelines OIDC

Needs `SYSTEM_OIDCREQUESTURI` and `SYSTEM_ACCESSTOKEN` (ADO sets these for OIDC-enabled service connections / pipelines). No Trust `oidcAudience` required. Bind an `AZURE_DEVOPS` principal to the pipeline subject (`sc://…` or `p://…`).

```yaml
# azure-pipelines.yml (illustrative)
variables:
  TRUST_URL: https://trust.certlocker.io
  # TRUST_OIDC_TOKEN_TYPE: SECRET
```

```groovy
trust {
    url = System.getenv('TRUST_URL') ?: 'https://trust.certlocker.io'
    // oidcAudience not required for Azure DevOps
}
```

### Break-glass apiToken

Explicit only — not the federated CI design. Both token and account id are required.

```groovy
trust {
    url = System.getenv('TRUST_URL') ?: 'https://trust.certlocker.io'
    apiToken = System.getenv('TRUST_API_TOKEN')
    accountId = System.getenv('TRUST_ACCOUNT_ID')
}
```

```bash
export TRUST_URL=https://trust.certlocker.io
export TRUST_API_TOKEN=<x-api-token>
export TRUST_ACCOUNT_ID=<accountId>
```

`trust.service` is a `io.certlocker.trustclient.service.TrustService`. Groovy calls the client methods directly:

- `fetchSecret`, `getSecret`, `getSecretAsPem`, `getSecretAsConfiguration`, `getSecretAsPrivateKey`, `getSecretAsPassword`, `getSecretAsCredentials`
- `getCertificate`, `fetchCertificate`, `fetchCertificateBundle`
- `getToken`

### Asset tokens

An asset token is an `x-api-token` created for one secret or one certificate and assigned that asset. Trust already knows which asset the token can read, so the call omits the id.

```groovy
// SECRET token assigned to one secret, such as the GAR service account
def serviceAccount = trust.service.fetchSecret()
def configuration = trust.service.getSecretAsConfiguration()

// CERTIFICATE token assigned to one certificate
def certificate = trust.service.fetchCertificate()
def bundle = trust.service.fetchCertificateBundle()
```

The same no-argument form exists for `getSecret`, `getSecretAsPem`, `getSecretAsPrivateKey`, `getSecretAsPassword`, `getSecretAsCredentials`, and `getCertificate`.

Pass an id when the token is not assigned to a single asset:

```groovy
def secret = trust.service.fetchSecret('SECRET_ID')
def certificate = trust.service.fetchCertificate('CERTIFICATE_ID')
```

The client is created the first time `trust.service` is used, and closed when the build finishes. Applying the plugin does not call Trust. A fetch runs when the `TrustService` method is called. In `settings.gradle` that is during settings evaluation, on every build. `trust.connectTimeout` overrides the client default of 10 seconds.

Leave the configuration cache disabled. The API token is a build-service parameter, and with the cache enabled Gradle can store that token and replay an earlier fetch.

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
    id 'io.certlocker.trust' version '1.0.2'
}

trust {
    url = 'https://trust.certlocker.io'
    apiToken = System.getenv('TRUST_API_TOKEN')
    accountId = System.getenv('TRUST_ACCOUNT_ID')
}

// No id: this apiToken is a SECRET token assigned to the service-account secret.
def serviceAccount = trust.service.fetchSecret()
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
