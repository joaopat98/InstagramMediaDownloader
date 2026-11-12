# Instagram Media Downloader

Android app for saving Instagram photos and videos to `Downloads/InstagramMediaDownloader`.

- In Instagram, open a post, reel, or story, tap **Share**, and choose **Instagram Media Downloader** from the Android Sharesheet. The app starts looking for media immediately.
- Or open the downloader, paste an Instagram link, and tap **Download**.

The app contacts Instagram directly and downloads the image and video files it finds, including carousel items when Instagram exposes them. If Instagram returns no media, you can sign in on Instagram’s own page inside the app and retry automatically. The session stays on the device and is used for Instagram page requests. Expired stories, content your account cannot access, and links whose pages do not expose media files can still fail.

## Build

Open the project in Android Studio with JDK 17 and Android SDK 35. It runs on Android 10 or later.

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

The debug APK is at `app/build/outputs/apk/debug/app-debug.apk`.

## Development and releases

Work on `develop` or branch from it and open a pull request back to `develop`. GitHub Actions runs the unit tests and lint, builds a debug APK, and uploads it as a workflow artifact.

To release, merge `develop` into `main`. A successful build on `main` publishes an installable, signed APK under **Releases**. Release signing requires the repository secret `ANDROID_RELEASE_KEYSTORE` (a Base64-encoded PKCS12 keystore containing the `instagram-media-downloader` key) and `ANDROID_RELEASE_PASSWORD`. Use the same key for each release so Android can install upgrades.
