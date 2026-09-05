import XCTest

final class UncivLaunchTests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    @MainActor
    func testLaunchesInstalledApplication() throws {
        let application = XCUIApplication(bundleIdentifier: "com.aishuati.unciv")

        application.launch()

        XCTAssertTrue(
            application.wait(for: .runningForeground, timeout: 30),
            "Expected the pre-installed app com.aishuati.unciv to reach the foreground"
        )
        application.terminate()
    }

    @MainActor
    func testStableIdentifierProbe() throws {
        let application = XCUIApplication(bundleIdentifier: "com.aishuati.unciv")

        application.launch()

        XCTAssertTrue(
            application.staticTexts["unciv.ui-test.ready"].waitForExistence(timeout: 30),
            "Expected the opt-in iOS UI test readiness marker"
        )
        XCTAssertTrue(
            application.staticTexts["unciv.ui-test.audio.passed"].waitForExistence(timeout: 30),
            "Expected MP3 playback and contained OGG music loading failure"
        )
        application.terminate()
    }
}
