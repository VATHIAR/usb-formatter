# USB Formatter

Root-free Android app that formats a USB drive (MBR + FAT32 or exFAT) over the USB Host API,
using [libaums](https://github.com/magnusja/libaums) for the Mass Storage / SCSI transport.

- FAT32 and exFAT are implemented. Neither has been verified on hardware yet; test with a spare drive first.
- NTFS and ext4 are not offered (they need root).

## Build

Pushing to `main` runs GitHub Actions, which builds `app-debug.apk`.
Download it from the run's **Artifacts** section and install it on your phone.
Locally: `gradle assembleDebug` (Gradle 8.7, JDK 17, Android SDK 34).
