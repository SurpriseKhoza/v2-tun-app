# Production release signing

V2-TUN release builds no longer use the debug keystore or hardcoded passwords. `app/build.gradle.kts` requires these environment variables whenever a release task is requested:

```text
V2TUN_RELEASE_KEYSTORE
V2TUN_RELEASE_STORE_PASSWORD
V2TUN_RELEASE_KEY_ALIAS
V2TUN_RELEASE_KEY_PASSWORD
```

For a local build, load the private credentials file supplied with the generated keystore and run:

```bash
set -a
source /path/to/V2-TUN-production-signing.env
set +a
./gradlew :app:assembleRelease
```

For GitHub Actions, configure these repository secrets:

```text
V2TUN_RELEASE_KEYSTORE_BASE64
V2TUN_RELEASE_STORE_PASSWORD
V2TUN_RELEASE_KEY_ALIAS
V2TUN_RELEASE_KEY_PASSWORD
```

`V2TUN_RELEASE_KEYSTORE_BASE64` must contain the base64-encoded contents of the production `.jks` file. The workflow decodes it into the runner, sets the four Gradle environment variables, builds the APK, and discards the runner copy afterward. Never commit the keystore, the credentials file, or the decoded runner file.

The generated production key is separate from the old `app/v2tun-debug.jks`. Keep multiple secure backups of the production keystore and credentials; losing them prevents updates to an already-published Android application.
