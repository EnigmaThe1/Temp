# digiKam Android port experiment

This folder is an intentionally small downstream compatibility layer used by the disposable `EnigmaThe1/Temp` build factory.

## Source

The workflow downloads the official digiKam 9.1.0 source tarball from KDE at build time. The upstream source is not copied into this repository.

## Strategy

- Pinned KDE Craft Qt 5.15.5 / KF5 Android arm64 toolchain image.
- arm64-v8a first.
- Keep digiKam's upstream database, metadata, thumbnail, face, search, map and image-processing core.
- Do **not** ship the desktop window unchanged on a phone.
- Add an Android-only adaptive UI shell: touch-sized controls, kinetic scrolling, collapsed sidebars/drawers, bottom navigation, mobile overflow access to the full command set, and portrait/landscape adaptation.
- Add only the Android APK/platform wrapper and mobile presentation layer; desktop behaviour remains unchanged.
- Export `main()` for Android.
- Disable desktop-only or currently unsupported pieces for the first bootable build: DBus, Qt WebEngine, MySQL/internal MySQL and the media player.
- SQLite remains the initial database backend.
- Build evidence and logs are uploaded even when compilation fails.

## Output

A successful run uploads the raw APK plus a disposable test-signed APK that can be sideloaded for validation. The signing key is generated during the CI run and discarded; it is not a release signing identity.

This is a porting/build branch, not canonical digiKam source.


## Mobile UI acceptance criteria

A build is not considered a finished Android port merely because an APK launches. Before release it must be usable by touch on a normal phone:

- no permanently exposed desktop menu bar or dense desktop toolbars;
- primary navigation reachable from a bottom bar;
- Albums, People and Search reachable with one tap;
- Map, Tags, Dates, Timeline, Similarity and the original advanced commands remain reachable from the mobile overflow;
- sidebars behave as temporary mobile drawers instead of permanently consuming the photo grid;
- controls and menu rows use touch-sized targets;
- item views use kinetic scrolling;
- portrait and landscape resize the active drawer appropriately;
- first-run/configuration dialogs inherit the mobile sizing layer.

Further passes will adapt image-viewer gestures, Android storage picking/permissions and any dialogs that still prove desktop-only during device testing.
