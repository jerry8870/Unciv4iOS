# Unciv MetalANGLE UIScene backend

This module repackages the published libGDX MetalANGLE RoboVM backend
`com.badlogicgames.gdx:gdx-backend-robovm-metalangle:1.14.2` as
`1.14.2-unciv.1`. It preserves every upstream JAR entry except
`IOSApplication*.class`, which is rebuilt from the pinned upstream source with
the minimum changes required for an iOS 15+ `UIWindowScene` lifecycle.

The upstream binary is resolved from Maven Central at build time and must match
SHA-256
`1ab70904b754e17e7b20f5a00d693ed85dc32b439f74d32d04acc0a3ebc94ce4`.
The copied `IOSApplication.java` baseline came from the matching 1.14.2 source
JAR, SHA-256
`986359b6f8a68987e00e9b1c0c790c2b1ca39fb4aa75e1842f86130815b8edf2`.
Native MetalANGLE frameworks remain in that dependency and are not committed to
this repository.

## Build and verify

From the repository root:

```shell
./gradlew -p ios-backend clean check
```

The output is
`ios-backend/build/libs/gdx-backend-robovm-metalangle-1.14.2-unciv.1.jar`.
`check` verifies the upstream hash, confirms that all non-`IOSApplication`
entries are retained, and confirms that the replacement delegate classes are
present.

## Parent build integration

Include this directory as project `:ios-backend`, replace the iOS module's
published MetalANGLE backend dependency with `implementation(project(":ios-backend"))`,
and keep the existing iOS native classifier dependency. No `mavenLocal()`
publication is needed.

The app Info.plist must declare exactly one window application scene and disable
multiple scenes:

```xml
<key>UIApplicationSceneManifest</key>
<dict>
    <key>UIApplicationSupportsMultipleScenes</key>
    <false/>
    <key>UISceneConfigurations</key>
    <dict>
        <key>UIWindowSceneSessionRoleApplication</key>
        <array>
            <dict>
                <key>UISceneConfigurationName</key>
                <string>Default Configuration</string>
                <key>UISceneDelegateClassName</key>
                <string>UncivSceneDelegate</string>
            </dict>
        </array>
    </dict>
</dict>
```

`IOSApplication.Delegate` also returns the same scene configuration
programmatically, so the Objective-C delegate name and Java annotation stay in
one implementation.

## Compatibility boundary

- Minimum supported deployment target: iOS 15.0.
- Compiled against RoboVM UIKit bindings 2.3.26.
- Existing launchers may continue extending `IOSApplication.Delegate` without
  source changes.
- The backend intentionally supports one window scene. A later scene connection
  reuses the existing window and graphics instance instead of initializing
  libGDX twice.
- Scene disconnect does not dispose libGDX; process termination retains the
  upstream disposal behavior.

The replacement source is derived from libGDX 1.14.2 and remains under the
upstream Apache License 2.0.
