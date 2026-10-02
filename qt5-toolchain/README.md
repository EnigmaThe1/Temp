# Reconstructed KDE Qt5 Android toolchain

This build image recreates KDE's retired Qt5 Android CI environment for the
digiKam Android port.

Pinned compatibility baseline:

- Ubuntu 22.04
- Qt 5.15.5 LTS
- KDE Frameworks 5 host tooling
- Android SDK 33
- Android NDK 22.1.7171670
- Android arm64-v8a target
- Craft `qt5-lts` at `9ad3f38d...`
- Craft blueprints `qt5-lts` at `18c24e4b...`
- CraftMaster at `51e94ec2...`

The versions are based on KDE's final Qt5 Android CI image/configuration before
that infrastructure was removed. The generated image is build infrastructure,
not a modified digiKam distribution.
