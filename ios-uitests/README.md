# Unciv iOS UI tests

This directory contains a **UI-test-only** Xcode project. It does not build or
embed Unciv. The smoke test controls an app that has already been installed on
the selected simulator and launches it with:

```swift
XCUIApplication(bundleIdentifier: "com.aishuati.unciv")
```

The readiness marker exists only in the explicit RoboVM UI-test build. Build
that variant with `-PiosUiTestBuild=true`; the default production source set
does not compile the test launcher or marker view controller.

The test target deploys to iPhone or iPad on iOS 15.0 or later. Keep simulator
state intact: these commands never erase or recreate a simulator.

## Prerequisites

Select the exact simulator once (the value below is the available iPad mini
(A17 Pro) simulator used for the iPad smoke test):

```sh
export UNCIV_SIMULATOR_UDID="84FBB833-2BA6-488C-8554-7A2A13E1BD32"
```

1. Build and install the explicit UI-test variant on the selected simulator:

   ```sh
   ./gradlew --no-configuration-cache :ios:launchIPadSimulator \
     -PiosUiTestBuild=true \
     -Probovm.device.name="iPad mini (A17 Pro)" \
     -Probovm.arch=arm64 \
     -Probovm.archs=arm64
   ```

2. Stop the attached launcher after the app reaches its first screen. Do not
   erase the simulator; the installed app is the XCTest target.
3. Verify that `com.aishuati.unciv` is installed:

   ```sh
   xcrun simctl get_app_container "$UNCIV_SIMULATOR_UDID" com.aishuati.unciv app
   ```

4. Before running XCTest, prove that the installed executable is the one just
   produced by RoboVM:

   ```sh
   ios-uitests/verify-installed-app.sh \
     "$UNCIV_SIMULATOR_UDID" \
     ios/build/default/robovm.tmp/UncivIOSPOC.app
   ```

   This comparison preserves the simulator's application data and fails if a
   stale UI-test App is installed.

## Build and run with a fixed simulator

Keep using the exact simulator UDID above; do not select a device by its display
name. Keep DerivedData outside this source directory:

```sh
export UNCIV_DERIVED_DATA_DIR="$(mktemp -d)/UncivUITests"

xcodebuild \
  -project ios-uitests/UncivUITests.xcodeproj \
  -scheme UncivUITests \
  -destination "platform=iOS Simulator,id=$UNCIV_SIMULATOR_UDID" \
  -derivedDataPath "$UNCIV_DERIVED_DATA_DIR" \
  build-for-testing

xcodebuild \
  -project ios-uitests/UncivUITests.xcodeproj \
  -scheme UncivUITests \
  -destination "platform=iOS Simulator,id=$UNCIV_SIMULATOR_UDID" \
  -derivedDataPath "$UNCIV_DERIVED_DATA_DIR" \
  test-without-building
```

The blocking assertions verify that the installed app reaches the foreground
and exposes the opt-in `unciv.ui-test.ready` accessibility marker. The same
test-only view controller exposes the current screen as
`unciv.ui-test.screen.<SimpleClassName>`, including `LoadGameScreen`,
`WorldScreen`, and `SaveGameScreen`. These markers are non-interactive and do
not exist in the production build.

## Custom-location load and save regression

Install the checked-in invalid and valid save fixtures into the simulator's
local Files provider, then run the document-picker regression:

```sh
ios-uitests/prepare-document-picker-fixtures.sh "$UNCIV_SIMULATOR_UDID"

xcodebuild \
  -project ios-uitests/UncivUITests.xcodeproj \
  -scheme UncivUITests \
  -destination "platform=iOS Simulator,id=$UNCIV_SIMULATOR_UDID" \
  -derivedDataPath "$UNCIV_DERIVED_DATA_DIR" \
  -only-testing:UncivUITests/UncivDocumentPickerTests/testCustomLocationLoadAndSavePickerRecovery \
  test-without-building
```

`prepare-document-picker-fixtures.sh` resolves
`group.com.apple.FileProvider.LocalStorage` dynamically; it does not depend on
another installed Unciv bundle or its data container. The one-session test:

1. handles both a fresh install's language picker and an existing English
   setting;
2. verifies that the real `com.apple.DocumentManagerUICore.Service` picker UI
   appears (`com.apple.DocumentManager.Service` is its process executable name);
3. cancels and opens the picker again;
4. chooses an invalid file and opens the picker again after the error;
5. chooses the valid 4.21.14 fixture and waits for the test-only `WorldScreen`
   marker; and
6. enters `SaveGameScreen`, opens and cancels the real export picker twice, and
   verifies that cancellation restores the custom-location button so it can be
   opened again.

The test dynamically selects the local provider using current English
`On My iPhone` / `On My iPad` or common Chinese iPhone / iPad accessibility
labels, and it selects each fixture by its unique file label rather than by a
grid position.
Provider-section coordinates are only a fallback for runtimes that omit those
system rows from the target app's accessibility snapshot. All branches
deliberately run in one XCTest session because repeated sessions can destabilize
the iOS 26.5 simulator's XCTest accessibility service.

## Long-running gameplay driver

Do not start a new XCTest process for every coordinate action. On the iOS 26.5
simulator, repeatedly creating accessibility automation sessions can crash the
target process inside `XCTAutomationSession` / `AccessibilitySupport` rather
than in Unciv code. Use one persistent test session instead:

```sh
xcodebuild \
  -project ios-uitests/UncivUITests.xcodeproj \
  -scheme UncivUITests \
  -destination "platform=iOS Simulator,id=$UNCIV_SIMULATOR_UDID" \
  -derivedDataPath "$UNCIV_DERIVED_DATA_DIR" \
  -only-testing:UncivUITests/UncivGameplayDriverTests/testPersistentActions \
  test-without-building
```

The test prints `UNCIV_UI_COMMAND_FILE` and `UNCIV_UI_ACK_FILE`. From another
shell, append one command per line using `command-id|action;action`. The
acknowledgement file receives the command ID only after every action completes:

```sh
printf '%s\n' 'overview|tap,0.91,0.055;wait,1;tap,0.91,0.055' \
  >> "$UNCIV_UI_COMMAND_FILE"
grep -x 'overview' "$UNCIV_UI_ACK_FILE"
```

Supported actions are `tap`, `typeText`, `doubleTap`, `press`, `drag`, `wait`,
`dump`, `portrait`, `landscapeLeft`, and `landscapeRight`. `typeText` sends the remaining
comma-separated text to the current first responder. Append `quit` on its own
line for a clean shutdown. The default persistent-session timeout is four
hours; override it with the `UNCIV_UI_LOOP_TIMEOUT` test environment variable.
