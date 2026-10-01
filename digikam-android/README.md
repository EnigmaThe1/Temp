# digiKam Android port experiment

This folder is an intentionally small downstream compatibility layer used by the disposable `EnigmaThe1/Temp` build factory.

## Source

The workflow downloads the official digiKam 9.1.0 source tarball from KDE at build time. The upstream source is not copied into this repository.

## Strategy

- KDE's official Qt 6.11 Android Craft container.
- arm64-v8a first.
- Keep the upstream Qt Widgets application and digiKam core.
- Add only the Android APK wrapper required by ECM/Qt.
- Export `main()` for Android.
- Disable desktop-only or currently unsupported pieces for the first bootable build: DBus, Qt WebEngine, MySQL/internal MySQL and the media player.
- SQLite remains the initial database backend.
- Build evidence and logs are uploaded even when compilation fails.

## Output

A successful run uploads the raw APK plus a disposable test-signed APK that can be sideloaded for validation. The signing key is generated during the CI run and discarded; it is not a release signing identity.

This is a porting/build branch, not canonical digiKam source.
