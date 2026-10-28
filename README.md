# AFTRHRS Android

Native Android counterpart to AFTRHRS for iOS. The app talks directly to `https://app.aftrhrs.co.za/api/v1`, uses Jetpack Compose, and contains no WebView or wrapped web application.

## Build

Use Android Studio or run `./gradlew bundleRelease`. Release signing is read from the untracked `release-signing.properties`; see `release-signing.properties.example`.

Production push delivery requires a Firebase Android app for package `za.co.aftrhrs` and an untracked `app/google-services.json`.

The Play upload certificate is committed at `keystore/aftrhrs-upload-cert.pem`; its SHA-256 fingerprint is `98:56:32:86:68:C4:6A:A3:97:36:75:81:D7:0E:B2:21:CD:88:E0:27:F7:59:6D:47:0B:04:1E:BF:56:74:7E:5C`. The private keystore and passwords are deliberately excluded from Git and must be backed up securely.

## Included

- Native sign-in and adult-only registration
- Three-stage live-camera identity verification gate
- Discovery grid, filters, likes, profiles, blocking and reporting
- Direct and group chats, media, view-once media, private galleries and location messages
- Profile, privacy, subscription, linked-partner and location controls
- Android biometric/device-credential app lock
- Notification channels and deep-link routing
