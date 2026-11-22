# Production release signing

Release builds are signed the same way as CipherTun-VPN. Nothing secret is stored in the repository.

## GitHub secrets

Add these two repository secrets (Settings → Secrets and variables → Actions):

| Secret | Content |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | The release keystore, base64-encoded on a single line |
| `LOCAL_PROPERTIES` | A properties file, base64-encoded on a single line |

The decoded `LOCAL_PROPERTIES` contains:

```properties
KEYSTORE_PASS=<store password>
ALIAS_NAME=<key alias>
ALIAS_PASS=<key password>
```

## How the build uses them

- `.github/workflows/android-ci.yml` decodes `RELEASE_KEYSTORE_BASE64` into `app/release.keystore`, then runs `:app:assembleRelease` with `LOCAL_PROPERTIES` in the environment, and deletes the keystore afterwards.
- `app/build.gradle.kts` reads `KEYSTORE_PASS`, `ALIAS_NAME` and `ALIAS_PASS` from the base64 `LOCAL_PROPERTIES` environment variable, or from the root `local.properties` file for local builds.
- If either secret is missing (for example on pull requests from forks) CI builds a debug APK instead. A direct `assembleRelease` fails with a clear message when signing is not configured.
- Pushing a tag such as `v1.0.1` attaches the signed APK to a GitHub release. Debug builds are never published.

## Local release build

Put the keystore at `app/release.keystore` and add the three properties to `local.properties`, then run:

```bash
./gradlew :app:assembleRelease
```

`*.keystore` is git-ignored. Never commit the keystore or the passwords.

## Creating a new keystore

```bash
PASS=$(openssl rand -hex 16)
keytool -genkeypair -keystore release.keystore -storetype PKCS12 -alias ciphertun \
  -keyalg RSA -keysize 4096 -validity 10000 -storepass "$PASS" -keypass "$PASS" \
  -dname "CN=CipherTun, O=CipherTun, C=ZA"
base64 -w0 release.keystore            # -> RELEASE_KEYSTORE_BASE64
printf 'KEYSTORE_PASS=%s\nALIAS_NAME=ciphertun\nALIAS_PASS=%s\n' "$PASS" "$PASS" | base64 -w0   # -> LOCAL_PROPERTIES
```

PKCS12 keystores use one password for both the store and the key, so `KEYSTORE_PASS` and `ALIAS_PASS` must match.

Keep several secure backups of the keystore and its passwords. Losing them prevents updates to an already-installed app.
