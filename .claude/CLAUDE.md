# Kodelab — working notes for Claude

## Always build after a feature change

Whenever you add or change a feature, **build the app before calling the work
done**. A change that compiles only in your head is not finished work, and
Compose code in particular fails at the Kotlin level for things that read fine
in a diff.

```bash
ANDROID_HOME=~/Library/Android/sdk ./gradlew :app:assembleDebug
```

Install it on the attached device when the change is something you can see:

```bash
~/Library/Android/sdk/platform-tools/adb -s <serial> install -r \
  app/build/outputs/apk/debug/app-debug.apk
```

Report the build result honestly — if it failed, say so with the error rather
than describing the change as complete.

## Android tooling is not on PATH

Use full paths: `~/Library/Android/sdk/platform-tools/adb`,
`~/Library/Android/sdk/build-tools/35.0.0/{apksigner,aapt2}`. Pass `-s <serial>`
to `adb`, since a physical device and an emulator may both be attached.

## targetSdk stays 28 — do not bump it

From API 29 the platform forbids `execve()` / `mmap(PROT_EXEC)` on files in app
storage (W^X), which breaks proot and its ptrace loader — i.e. the entire Linux
terminal. `compileSdk` is 35; only `targetSdk` is pinned. The
`ExpiredTargetSdkVersion` lint is disabled in `app/build.gradle.kts` for this
reason, because it is fatal by default and would fail every release build.

## Release builds are signed from an untracked keystore

`keystore/` and `keystore.properties` are gitignored and hold the release
signing key. `app/build.gradle.kts` reads them if present; a clone without them
still builds every variant, and only `assembleRelease` comes out unsigned. The
release build is minified (R8 + resource shrinking), so smoke-test it on a
device before publishing — `proguard-rules.pro` carries the rules that keep
kotlinx.serialization and the WebView JS bridge alive.

Publish a build for download with:

```bash
gh release create v<version> app/build/outputs/apk/release/app-release.apk
```

## Don't commit or push unless asked

Leave finished work in the tree. Commit and push only when the request says to.
