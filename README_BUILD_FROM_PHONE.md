# Build and install CybX OS from a phone (no Android Studio)

This project is source code, not an installable APK. The included GitHub Actions workflow can build a debug APK in GitHub's cloud runner.

## Build route
1. Extract this ZIP on your phone.
2. Create a new **private** repository on GitHub.
3. Upload the extracted project contents to the repository root (not the ZIP file itself). Keep the `.github/workflows/build-android-apk.yml` file and its folder structure.
4. Open the repository's **Actions** tab and select **Build CybX Android APK**. If the workflow has not run automatically, choose **Run workflow**.
5. When the run completes successfully, open the run, scroll to **Artifacts**, and download `CybX-OS-debug-APK`.
6. Extract the downloaded artifact ZIP and tap `app-debug.apk` to install it. Android may ask you to allow installs from the browser or file manager you used; only enable this for that source and turn it off afterward.

## Important
- This workflow has been added but has **not been executed here**. A successful build is not yet verified.
- A debug APK is for testing, not a signed production release.
- The app does not bundle PRoot or a Kali-derived rootfs. The Linux runtime will not be usable until compatible, trusted ARM64 PRoot and rootfs files are imported in Linux Setup.
- The app runs rootless and shares Android's host kernel; it does not replace Android or boot a custom CybX kernel.
