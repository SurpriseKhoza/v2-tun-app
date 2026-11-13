# V2-TUN source backup

This repository contains the V2-TUN Android source, native Go payload engine, Gradle project, research notes, UI references, and project assets needed to continue development.

The following are intentionally excluded from version control:

- IDE metadata and machine-specific files other than the explicitly included `local.properties`.
- Signing material other than the explicitly included `RELEASE_KEYSTORE`.
- APK/AAB files, `dist/`, Gradle caches, and `build/` directories, because they are generated artifacts.
- `V2-TUN-MTN-Profile.json`, because it contains supplied SSH credentials. Import it locally when needed rather than publishing it to a repository.

This private repository intentionally includes `local.properties` and `RELEASE_KEYSTORE` at the project root for the owner's convenience. Review repository access carefully because anyone with access can retrieve the signing key. Update `local.properties` if the Android SDK path differs, configure the production signing environment variables, install Android SDK/JDK/Go as required, and run `./gradlew assembleRelease`.
