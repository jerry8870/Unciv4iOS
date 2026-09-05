# Unciv iOS

This module ports the Unciv `4.21.14` compatibility baseline to iPhone and iPad
with MobiVM `2.3.26` and libGDX `1.14.2`. The universal application is simulator
first, full-screen, single-scene, and fixed to both landscape orientations. On
iPad it does not support portrait, Split View, Stage Manager resizing, multiple
windows, or Mac Catalyst. Its
bundle identifier is `com.aishuati.unciv`, display name is `Unciv4iOS`, bundle
build is `1`, and deployment target is iOS `15.0`.

The iOS 26.5 runtime warns that `UIRequiresFullScreen` will eventually be
ignored and that all orientations will eventually be required. This build
intentionally retains `UIRequiresFullScreen=true` for its fixed full-screen
landscape iPad scope. Supporting a future resizable-window model is a separate
migration and is not implied by this port.

The bundle identifier differs from earlier POC builds, so iOS treats this as a
separate application sandbox. Saves belonging to an installed
`com.unciv.app.poc` build are not migrated automatically.

The repository-owned [`ios-backend`](../ios-backend/README.md) module repackages
the pinned libGDX MetalANGLE backend as `1.14.2-unciv.1`. It replaces only
`IOSApplication*` and creates the window from `UIWindowScene`; it does not use
`mavenLocal()` or commit the upstream native frameworks.

## Build checks

An installed Xcode iOS SDK and an Apple-silicon simulator are required.

```bash
./gradlew --no-configuration-cache \
    :ios:check :ios-backend:check

./gradlew --no-configuration-cache :ios:verifyRoboVmApiCompatibility

./gradlew --no-configuration-cache :ios:robovmArchive \
    -PiosSkipSigning=true \
    -Probovm.arch=arm64 \
    -Probovm.archs=arm64
```

`:ios:verifyRoboVmApiCompatibility` checks the Java 8 class-file ceiling and
unsupported bytecode features in the `core`, `ios`, and repository-owned
`ios-backend` main outputs. It also resolves class, method, field, and access-mode
references in JDK and RoboVM platform namespaces against the pinned MobiVM
`2.3.26` platform jars, using the iOS runtime classpath for supporting types. It
does not audit bytecode inside other dependencies (including force-linked
dependencies), compile Android or Desktop, run AOT, or inspect test bytecode.
Force-link patterns are still checked for matches. The report is written to
`ios/build/default/reports/robovm-api-compatibility.txt`.

`:ios:check` runs the gate. `:ios:createIPA` and `:ios:robovmArchive` run it
before RoboVM AOT compilation. The check is static: reflection and other
dynamically selected classes, Objective-C selectors/native symbols, iOS
availability, and device behavior still require archive and runtime tests.

The iOS module builds independently using the checked-in configuration.
It uses default libGDX audio for MP3 and has no cloud sync, support-purchase or
platform password-store implementation. Unsupported OGG files are excluded from sound/music selection, and direct
OGG music loads fail through the existing music-controller error path.

## Optional runtime source sets

The public extension contract is `IOSRuntimeFeatures`. A separate runtime can
be compiled directly into RoboVM AOT using these optional Gradle parameters:

```text
-PiosExtensionSourceDir=/absolute/main       # contains kotlin/ and java/
-PiosExtensionTestSourceDir=/absolute/test   # contains kotlin/ and java/
-PiosMainClass=qualified.Launcher
-PiosRoboVmConfig=/absolute/robovm.xml
-PiosRoboVmProperties=/absolute/robovm.properties
```

Inputs stay in their original directory. Default output goes to
`ios/build/default`; extension output goes to `ios/build/extension`. Native
libraries and any extension-specific signing entitlements are supplied by the
external config. Release configuration and upload tooling are maintained outside
this repository.

## Simulator

Select the exact simulator and SDK instead of relying on MobiVM's best match:

```bash
./gradlew --no-configuration-cache :ios:launchIPadSimulator \
    -Probovm.arch=arm64 \
    -Probovm.device.name="iPad mini (A17 Pro)" \
    -Probovm.sdk.version=26.5 \
    -PiosSkipSigning=true
```

The UI-test-only Xcode project in [`ios-uitests`](../ios-uitests/README.md)
launches an already installed app by bundle identifier. Its blocking tests
verify foreground launch, the opt-in accessibility markers, and the native
custom-location document-picker flow. Build that instrumented app explicitly
with `-PiosUiTestBuild=true`; the default production source set and entry point
contain no UI-test classes or markers. Device launch, archive, and IPA tasks
reject the UI-test property so the instrumented variant cannot be packaged
accidentally. The tests passed on the fixed iPhone 13 mini simulator.

## Native custom save and load

`IOSSaverLoader` uses `UIDocumentPickerViewController` for external game files.
Loads use `NSFileCoordinator` and read the selected security-scoped URL off the
UIKit main thread. Saves export a temporary serialized game and remove it after
success or cancellation; the next launch also removes strictly named abandoned
export directories left by an interrupted picker.

## Platform capability boundary

`IOSGame` enables foreground asynchronous multiplayer through the API v1-only
`IOSMultiplayerV1Transport`, backed by an ephemeral `NSURLSession`. The default
server is `https://uncivserver.xyz`; custom servers must be public HTTPS URLs.
Game and preview downloads pin that validated source URL so later setting
changes cannot reroute an existing game's reads, writes, or authentication.
Server passwords use the existing settings storage
(`secureMultiplayerServerPasswords=false`).

Chat, API v2, Dropbox storage, background polling, push notifications, deep
links, HTTP/LAN servers, Mod management, and default music download stay
disabled on iOS. The main-menu Mods entry is hidden; existing local data is not
deleted. Foreground
downloads are cancelled on backgrounding; an in-flight turn PUT gets only a
finite UIKit background-task window and remains explicitly unconfirmed if that
window expires.

## Audio

MP3 uses the default `OALIOSAudio`. The ten existing OGG game assets remain
packaged, but the default audio backend has no OGG decoder. OGG is excluded from sound/music candidates; direct music loads are guarded
before invoking the native backend. Other loading failures remain caught.
No background music is installed implicitly, and default music download remains
disabled.

## Signing boundary

Phase one does not claim physical-device or App Store readiness. For a later
developer-device build, connect and unlock a registered device, enable
Developer Mode, and run:

```bash
./gradlew --no-configuration-cache :ios:launchIOSDevice \
    -PiosSkipSigning=false
```

If automatic selection is ambiguous, pass `-PiosProvisioningProfile` and the
hardware UDID with `-Probovm.device.udid`. Never treat a simulator or ad-hoc
archive result as device-signing evidence.

## Verification boundary

Migration acceptance is recorded by the release orchestrator. Earlier results
from builds with optional native runtime features do not establish default
build behavior. Simulator checks do not establish physical-device or live
multiplayer-server behavior.
