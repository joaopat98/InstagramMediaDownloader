# Souvenir

Android app for saving Instagram photos and videos to `Downloads/Souvenir`.

- In Instagram, open a public post or Reel, tap **Share**, and choose **Souvenir** from the Android Sharesheet. The app starts looking for media immediately.
- Or open the downloader, paste an Instagram link, and tap **Download**.

Souvenir makes signed-out requests only. It does not sign in to Instagram or use saved Instagram cookies. It can save public posts, Reels, and carousel items when Instagram exposes the media. Stories are not downloaded. If a link is unavailable or requires sign-in or verification, Souvenir offers **Open in Instagram** (or opens the link in a browser if Instagram is not installed).

## Build

Open the project in Android Studio with JDK 17 and Android SDK 35. It runs on Android 10 or later.

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

The debug APK is at `app/build/outputs/apk/debug/app-debug.apk`.

## Development and releases

Work on `develop` or branch from it and open a pull request back to `develop`. GitHub Actions runs the unit tests and lint, builds a debug APK, and uploads it as a workflow artifact.

To release, merge `develop` into `main`. A successful build on `main` publishes an installable, signed APK under **Releases**. Release signing requires the repository secret `ANDROID_RELEASE_KEYSTORE` (a Base64-encoded PKCS12 keystore containing the `instagram-media-downloader` key) and `ANDROID_RELEASE_PASSWORD`. Use the same key for each release so Android can install upgrades.
