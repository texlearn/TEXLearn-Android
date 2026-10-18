# TEXLearn Android wrapper (first test build)

This project packages the existing live TEXLearn website in an Android WebView. It does not copy or rebuild the website and it does not modify the live GitHub Pages site.

- Start URL: `https://samrambakeralam.github.io/TEXLearn/`
- App label: `TEXLearn`
- Android package ID: `com.samrambakeralam.texlearn`
- Min Android: 6.0 (API 23)
- Build target: Android API 34

## Important testing note

This is a **test-build project**, not a verified release APK. The app enables JavaScript and DOM storage and keeps the TEXLearn site, Google Apps Script endpoints, and Razorpay checkout pages inside the same WebView where possible. UPI/deep links and other external schemes are handed to Android. Download links use Android's DownloadManager.

The Razorpay flow, Google sign-in if used, external links, PDF/book access, and payment return flow must be tested on a real Android device before distribution. If a payment or login step opens a domain not listed in `INTERNAL_HOSTS`, review the routing logic in `MainActivity.kt` before release. Do not use this test APK for public distribution until these tests pass.

## Build using GitHub Actions (no Android Studio required on your computer)

1. Create a new GitHub repository for the Android wrapper (for example, `TEXLearn-Android`).
2. Upload the **contents of this project folder** to the repository root. Do not upload the parent folder as an extra nested directory.
3. Open the repository's **Actions** tab and run **Build TEXLearn APK**. The workflow also runs on pushes to `main`.
4. When it completes, open the workflow run and download the `TEXLearn-debug-APK` artifact.
5. Extract the downloaded artifact ZIP to get `app-debug.apk`.
6. Install it on a test Android phone and check the flows listed above.

## Build locally with Android Studio

Open this folder in Android Studio. Ensure JDK 17 and Android SDK Platform 35 are installed. If Android Studio asks to create/download Gradle wrapper files, allow it to do so; then run the `assembleDebug` Gradle task. The APK will be under `app/build/outputs/apk/debug/`.

## Before public distribution

A debug APK is for testing. For a public APK, create a **release-signed APK** and securely retain its signing key for all future updates. Keep the package ID stable. Do not commit signing keys or passwords to GitHub.
