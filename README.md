# USB Formatter

Root-free Android app that formats a USB drive (MBR + FAT32) over the USB Host API,
using [libaums](https://github.com/magnusja/libaums) for the Mass Storage / SCSI transport.

- FAT32: implemented (not yet verified on hardware; test with a spare drive first).
- exFAT: not implemented. NTFS / ext4: not possible without root.

## Build

Pushing to `main` runs GitHub Actions, which builds `app-debug.apk`.
Download it from the run's **Artifacts** section and install it on your phone.
Locally: `gradle assembleDebug` (Gradle 8.7, JDK 17, Android SDK 34).
