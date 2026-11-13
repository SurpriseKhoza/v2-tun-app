# Production release signing

V2-TUN release builds no longer use the debug keystore or hardcoded passwords. `app/build.gradle.kts` requires these environment variables whenever a release task is requested:

```text
V2TUN_RELEASE_KEYSTORE
V2TUN_RELEASE_STORE_PASSWORD
V2TUN_RELEASE_KEY_ALIAS
V2TUN_RELEASE_KEY_PASSWORD
```

For a local build, point the keystore variable at the committed `RELEASE_KEYSTORE` and export the three private values in the shell:

```bash
export V2TUN_RELEASE_KEYSTORE="$PWD/RELEASE_KEYSTORE"
export V2TUN_RELEASE_STORE_PASSWORD="<store-password>"
export V2TUN_RELEASE_KEY_ALIAS="v2tun_release"
export V2TUN_RELEASE_KEY_PASSWORD="<key-password>"
./gradlew :app:assembleRelease
```

For GitHub Actions, configure these repository secrets:

```text
V2TUN_RELEASE_STORE_PASSWORD
V2TUN_RELEASE_KEY_ALIAS
V2TUN_RELEASE_KEY_PASSWORD
```

The workflow checks out the committed private `RELEASE_KEYSTORE` file, sets `V2TUN_RELEASE_KEYSTORE` to its path, injects the three GitHub secrets, and builds the APK. The keystore is intentionally present because this is a private repository; restrict repository access carefully.

If the committed keystore or any of the three signing secrets is unavailable, GitHub Actions automatically builds and uploads a signed debug APK instead of failing. A direct local `assembleRelease` still requires the production signing variables; use `assembleDebug` locally when testing without them.

The generated production key is separate from the old `app/v2tun-debug.jks`. Keep multiple secure backups of the production keystore and credentials; losing them prevents updates to an already-published Android application.
