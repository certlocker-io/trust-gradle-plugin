# Publishing the Gradle plugin

This repository publishes plugin ID `io.certlocker.trust` to the Gradle Plugin Portal. Publish the Java dependency `io.certlocker:trust-client:1.0.1` to Maven Central first; see [the client publishing guide](https://github.com/certlocker-io/trust-java-client/blob/main/docs/publishing.md).

## One-time account setup

1. Create or use a company-controlled [Gradle Plugin Portal account](https://plugins.gradle.org/user/login), and link the appropriate GitHub identity where requested.
2. Generate the API key and secret in the account's API Keys tab.
3. Add `GRADLE_PUBLISH_KEY` and `GRADLE_PUBLISH_SECRET` to [repository Actions secrets](https://github.com/certlocker-io/trust-gradle-plugin/settings/secrets/actions). Do not use a Trust service API key here.
4. Check that the account can publish `io.certlocker.trust`. The first submission goes through Portal approval; be ready to demonstrate ownership of CertLocker's domain/repository if reviewers request it. Follow the [Portal publishing instructions](https://plugins.gradle.org/docs/publish-plugin).

Portal signing is optional; the library's mandatory Maven Central signing is configured in its own repository. Both publications retain the GPL v3 license from their LICENSE files.

## Release

1. Merge the publishing changes into `main` and make sure Build passes.
2. Publish the required `trustClientVersion` from the client repository and wait until its POM is downloadable from Maven Central.
3. Set `trustPluginVersion` to a new stable version, merge into `main`, and publish a GitHub Release with the matching `vMAJOR.MINOR.PATCH` tag. The first plugin version is `v1.0.0`.
4. **Publish release** validates the version and release source, checks the client POM on Central, builds/tests using Central dependencies, and calls `publishPlugins` with the Portal credentials.
5. Monitor Portal approval for the first release. A successful upload can still need human review before users can resolve the plugin.

Ordinary pushes and pull requests only build. Draft and prerelease releases do not publish. After a failed submission, inspect Portal state before retrying: use Actions → Publish release → Run workflow with the existing tag only if that version has not already been uploaded. Published versions must not be reused.

Once approved, users need Java 21, Gradle 9, and:

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

Keep configuration cache disabled, as described in the README. No Maven Local setup or repository login is needed for public consumers after both artifacts are published.

## Verification before the first client release

The Build workflow bootstraps the unpublished client from a pinned Git commit, installs it unsigned to Maven Local, and opts into `-PuseMavenLocal=true` for tests. This requires no publisher credentials and does not run the client's tests or access live Trust services. After `trust-client:1.0.1` is on Central, remove those bootstrap steps and the property from Build so CI uses Central too. Releases always use Central.

Locally, install the client with `./gradlew publishToMavenLocal` in its repository, then run here:

```bash
./gradlew build generatePomFileForPluginMavenPublication -PuseMavenLocal=true
```

The TestKit functional tests verify use from both project and settings scripts without contacting a live Trust service.
