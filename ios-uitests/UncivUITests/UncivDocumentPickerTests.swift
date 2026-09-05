import XCTest

@MainActor
final class UncivDocumentPickerTests: XCTestCase {
    private let uncivBundleIdentifier = "com.aishuati.unciv"
    private let documentPickerBundleIdentifier = "com.apple.DocumentManagerUICore.Service"
    private let invalidFixtureName = "UncivUITests-invalid-save.txt"
    private let validFixtureName = "UncivUITests-valid-4.21.14.txt"
    private var hasObservedPickerProcess = false

    override func setUpWithError() throws {
        continueAfterFailure = false
        XCUIDevice.shared.orientation = .landscapeLeft
    }

    func testCustomLocationLoadAndSavePickerRecovery() {
        let application = XCUIApplication(bundleIdentifier: uncivBundleIdentifier)
        application.launch()

        XCTAssertTrue(
            application.wait(for: .runningForeground, timeout: 30),
            "Expected the pre-installed app \(uncivBundleIdentifier) to reach the foreground"
        )
        XCTAssertTrue(
            application.staticTexts["unciv.ui-test.ready"].waitForExistence(timeout: 30),
            "Expected an app built with -PiosUiTestBuild=true"
        )

        reachMainMenu(in: application)
        tap(x: 0.37, y: 0.63, in: application)
        XCTAssertTrue(
            screenMarker("LoadGameScreen", in: application).waitForExistence(timeout: 15),
            "Expected LoadGameScreen after tapping Load game"
        )

        openDocumentPicker(from: application)
        navigateToBrowseRoot(using: application)
        dismissDocumentPicker(using: application)
        XCTAssertTrue(
            hostMarker("visible", in: application).waitForExistence(timeout: 10),
            "Expected cancellation to reveal the Unciv host again"
        )
        XCTAssertTrue(
            screenMarker("LoadGameScreen", in: application).waitForExistence(timeout: 10),
            "Expected cancellation to leave the app on LoadGameScreen"
        )

        openDocumentPicker(from: application)
        selectFixture(named: invalidFixtureName, using: application)
        XCTAssertTrue(
            hostMarker("visible", in: application).waitForExistence(timeout: 15),
            "Expected an invalid custom file to dismiss the document picker"
        )
        XCTAssertTrue(
            screenMarker("LoadGameScreen", in: application).waitForExistence(timeout: 15),
            "Expected an invalid custom file to leave the app on LoadGameScreen"
        )

        openDocumentPicker(from: application)
        selectFixture(named: validFixtureName, using: application)
        XCTAssertTrue(
            hostMarker("visible", in: application).waitForExistence(timeout: 30),
            "Expected a valid custom file to dismiss the document picker"
        )
        XCTAssertTrue(
            screenMarker("WorldScreen", in: application).waitForExistence(timeout: 120),
            "Expected a valid custom save to finish loading into WorldScreen"
        )

        // A fresh profile can show several consecutive tutorial popups over the
        // hamburger menu. Try the normal route first, then dismiss one tutorial
        // step and retry until SaveGameScreen is reached.
        let saveGameScreen = screenMarker("SaveGameScreen", in: application)
        var reachedSaveGameScreen = false
        for _ in 0..<10 {
            tap(x: 0.08, y: 0.055, in: application)
            RunLoop.current.run(until: Date().addingTimeInterval(0.5))
            tap(x: 0.48, y: 0.38, in: application)
            if saveGameScreen.waitForExistence(timeout: 1) {
                reachedSaveGameScreen = true
                break
            }

            tap(x: 0.53, y: 0.55, in: application)
            RunLoop.current.run(until: Date().addingTimeInterval(0.5))
        }
        XCTAssertTrue(
            reachedSaveGameScreen,
            "Expected SaveGameScreen after opening Save game from WorldScreen"
        )

        // Reopening after cancellation proves that the asynchronous cancellation callback
        // restored the custom-location button from its disabled Saving... state.
        for attempt in 1...2 {
            openDocumentPicker(from: application, x: 0.55, y: 0.45)
            dismissExportDocumentPicker(using: application)
            XCTAssertTrue(
                hostMarker("visible", in: application).waitForExistence(timeout: 10),
                "Expected export cancellation \(attempt) to reveal the Unciv host again"
            )
            XCTAssertTrue(
                screenMarker("SaveGameScreen", in: application).waitForExistence(timeout: 10),
                "Expected export cancellation \(attempt) to leave the app on SaveGameScreen"
            )
            RunLoop.current.run(until: Date().addingTimeInterval(1))
        }
    }

    private func reachMainMenu(in application: XCUIApplication) {
        if screenMarker("MainMenuScreen", in: application).waitForExistence(timeout: 15) {
            return
        }

        let languagePicker = screenMarker("LanguagePickerScreen", in: application)
        XCTAssertTrue(
            languagePicker.waitForExistence(timeout: 15),
            "Expected either LanguagePickerScreen or MainMenuScreen after launch"
        )

        // English and Pick language on the initial landscape language screen.
        tap(x: 0.50, y: 0.15, in: application)
        tap(x: 0.88, y: 0.87, in: application)
        XCTAssertTrue(
            screenMarker("MainMenuScreen", in: application).waitForExistence(timeout: 30),
            "Expected the first-run language choice to reach MainMenuScreen"
        )
    }

    private func openDocumentPicker(
        from application: XCUIApplication,
        x: Double = 0.50,
        y: Double = 0.30
    ) {
        tap(x: x, y: y, in: application)

        XCTAssertTrue(
            hostMarker("covered", in: application).waitForExistence(timeout: 15),
            "Expected the UIDocumentPicker to cover the Unciv host"
        )

        let picker = XCUIApplication(bundleIdentifier: documentPickerBundleIdentifier)
        if !hasObservedPickerProcess {
            let deadline = Date().addingTimeInterval(15)
            while picker.state == .notRunning && Date() < deadline {
                RunLoop.current.run(until: Date().addingTimeInterval(0.1))
            }
            XCTAssertNotEqual(
                picker.state,
                .notRunning,
                "Expected the real UIDocumentPicker service process to be presented"
            )
            hasObservedPickerProcess = true
        }
        RunLoop.current.run(until: Date().addingTimeInterval(0.5))
    }

    private func dismissDocumentPicker(
        using eventSurface: XCUIApplication,
        fallback: CGVector = CGVector(dx: 0.87, dy: 0.10)
    ) {
        let closeLabels = ["Close", "Cancel", "关闭", "取消"]
        for label in closeLabels {
            let button = eventSurface.buttons[label]
            if button.exists && button.isHittable {
                button.tap()
                return
            }
        }

        // The landscape document browser presents a trailing close glyph. Its label
        // has changed between iOS releases, so retain a coordinate fallback.
        eventSurface.coordinate(withNormalizedOffset: fallback).tap()
    }

    private func dismissExportDocumentPicker(using eventSurface: XCUIApplication) {
        var navigatedBack = false
        for label in ["Back", "返回"] {
            let button = eventSurface.buttons[label]
            if button.exists && button.isHittable {
                button.tap()
                navigatedBack = true
                break
            }
        }
        if !navigatedBack {
            eventSurface.coordinate(withNormalizedOffset: CGVector(dx: 0.065, dy: 0.10)).tap()
        }
        RunLoop.current.run(until: Date().addingTimeInterval(0.5))
        if hostMarker("visible", in: eventSurface).waitForExistence(timeout: 1) {
            return
        }

        // The export browser has no close control while it is inside a provider.
        // Returning to Browse root replaces Back with the dismiss control.
        dismissDocumentPicker(
            using: eventSurface,
            fallback: CGVector(dx: 0.065, dy: 0.10)
        )
    }

    private func selectFixture(named filename: String, using eventSurface: XCUIApplication) {
        let displayedFilename = String(filename.dropLast(".txt".count))
        let fixtureLabels = [displayedFilename, filename]
        let localProviderLabels = [
            "On My iPhone", "On My iPad",
            "我的iPhone", "我的 iPhone", "我的iPad", "我的 iPad",
            "在我的 iPhone 上", "在我的 iPad 上",
        ]
        navigateToBrowseRoot(using: eventSurface)

        var localProvider = firstHittableElement(
            labeled: localProviderLabels,
            in: eventSurface
        )
        if localProvider == nil {
            // Expand Locations when Files restored the Browse root with that
            // section collapsed.
            if let locations = firstHittableElement(
                labeled: ["Locations", "位置"],
                in: eventSurface
            ) {
                locations.tap()
            } else {
                eventSurface.coordinate(withNormalizedOffset: CGVector(dx: 0.89, dy: 0.42)).tap()
            }
            RunLoop.current.run(until: Date().addingTimeInterval(0.5))
            localProvider = firstHittableElement(
                labeled: localProviderLabels,
                in: eventSurface
            )
        }
        if let localProvider {
            localProvider.tap()
        } else {
            // iOS 26.5 can hide provider rows from the target app's hierarchy.
            // The local provider is the second row in the simulator's Locations
            // section, independent of the displayed language.
            eventSurface.coordinate(withNormalizedOffset: CGVector(dx: 0.20, dy: 0.61)).tap()
        }
        RunLoop.current.run(until: Date().addingTimeInterval(0.8))

        if let fixture = firstHittableElement(labeled: fixtureLabels, in: eventSurface) {
            fixture.tap()
            return
        }
        XCTFail("Expected the checked-in fixture \(filename) in the local Files provider")
    }

    private func navigateToBrowseRoot(using eventSurface: XCUIApplication) {
        // Tapping the selected Browse tab returns to its root, while tapping it
        // from Recents first restores the last browsed folder. Two taps therefore
        // normalize both states to the Browse root.
        eventSurface.coordinate(withNormalizedOffset: CGVector(dx: 0.62, dy: 0.88)).tap()
        RunLoop.current.run(until: Date().addingTimeInterval(0.5))
        eventSurface.coordinate(withNormalizedOffset: CGVector(dx: 0.62, dy: 0.88)).tap()
        RunLoop.current.run(until: Date().addingTimeInterval(0.5))
    }

    private func firstHittableElement(
        labeled labels: [String],
        in application: XCUIApplication
    ) -> XCUIElement? {
        for label in labels {
            let predicate = NSPredicate(format: "label CONTAINS[c] %@", label)
            let element = application.descendants(matching: .any).matching(predicate).firstMatch
            if element.waitForExistence(timeout: 1), element.isHittable {
                return element
            }
        }
        return nil
    }

    private func screenMarker(_ screenName: String, in application: XCUIApplication) -> XCUIElement {
        application.staticTexts["unciv.ui-test.screen.\(screenName)"]
    }

    private func hostMarker(_ state: String, in application: XCUIApplication) -> XCUIElement {
        application.staticTexts["unciv.ui-test.host.\(state)"]
    }

    private func tap(x: Double, y: Double, in application: XCUIApplication) {
        application.coordinate(withNormalizedOffset: CGVector(dx: x, dy: y)).tap()
    }
}
