# V2-TUN source backup

This repository contains the V2-TUN Android source, native Go payload engine, Gradle project, research notes, UI references, and project assets needed to continue development.

The following are intentionally excluded from version control:

- `local.properties` and IDE metadata, because they are machine-specific.
- `*.jks`, `*.keystore`, and other signing material, because private signing keys must not be stored in source control.
- APK/AAB files, `dist/`, Gradle caches, and `build/` directories, because they are generated artifacts.
- `V2-TUN-MTN-Profile.json`, because it contains supplied SSH credentials. Import it locally when needed rather than publishing it to a repository.

To build on another machine, install Android SDK/JDK/Go as required, copy the project, create a local `local.properties`, and run `./gradlew assembleRelease`.
