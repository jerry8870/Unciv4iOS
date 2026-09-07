import XCTest

final class UncivGameplayDriverTests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    @MainActor
    func testActions() throws {
        let application = XCUIApplication(bundleIdentifier: "com.aishuati.unciv")
        application.activate()

        XCTAssertTrue(
            application.wait(for: .runningForeground, timeout: 30),
            "Expected the pre-installed app com.aishuati.unciv to reach the foreground"
        )
        print("UNCIV_UI_FRAME=\(application.frame) ORIENTATION=\(XCUIDevice.shared.orientation.rawValue)")

        let rawActions = try XCTUnwrap(
            ProcessInfo.processInfo.environment["UNCIV_UI_ACTIONS"],
            "Set UNCIV_UI_ACTIONS to a semicolon-separated list of coordinate actions"
        )

        for rawAction in rawActions.split(separator: ";") {
            try perform(String(rawAction), in: application)
        }

        let screenshot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        screenshot.name = "Unciv gameplay driver result"
        screenshot.lifetime = .keepAlways
        add(screenshot)
    }

    @MainActor
    func testPersistentActions() throws {
        let application = XCUIApplication(bundleIdentifier: "com.aishuati.unciv")
        application.activate()

        XCTAssertTrue(
            application.wait(for: .runningForeground, timeout: 30),
            "Expected the pre-installed app com.aishuati.unciv to reach the foreground"
        )

        let fileManager = FileManager.default
        let commandURL = fileManager.temporaryDirectory.appendingPathComponent("unciv-ui-commands.txt")
        let acknowledgementURL = fileManager.temporaryDirectory.appendingPathComponent("unciv-ui-acknowledgements.txt")
        try "".write(to: commandURL, atomically: true, encoding: .utf8)
        try "READY\n".write(to: acknowledgementURL, atomically: true, encoding: .utf8)
        print("UNCIV_UI_COMMAND_FILE=\(commandURL.path)")
        print("UNCIV_UI_ACK_FILE=\(acknowledgementURL.path)")

        let timeout = Double(ProcessInfo.processInfo.environment["UNCIV_UI_LOOP_TIMEOUT"] ?? "14400") ?? 14400
        let deadline = Date().addingTimeInterval(timeout)
        var consumedLineCount = 0
        var acknowledgements = ["READY"]

        while Date() < deadline {
            let contents = (try? String(contentsOf: commandURL, encoding: .utf8)) ?? ""
            let lines = contents.split(separator: "\n", omittingEmptySubsequences: true)

            while consumedLineCount < lines.count {
                let line = String(lines[consumedLineCount])
                consumedLineCount += 1

                if line == "quit" {
                    return
                }

                let command = line.split(separator: "|", maxSplits: 1).map(String.init)
                XCTAssertEqual(command.count, 2, "Expected command-id|action;action")
                guard command.count == 2 else { continue }

                for rawAction in command[1].split(separator: ";") {
                    try perform(String(rawAction), in: application)
                }

                acknowledgements.append(command[0])
                try (acknowledgements.joined(separator: "\n") + "\n")
                    .write(to: acknowledgementURL, atomically: true, encoding: .utf8)
            }

            RunLoop.current.run(until: Date().addingTimeInterval(0.1))
        }

        XCTFail("Persistent UI action loop timed out after \(timeout) seconds")
    }

    @MainActor
    private func perform(_ rawAction: String, in application: XCUIApplication) throws {
        let parts = rawAction.split(separator: ",").map(String.init)
        let action = try XCTUnwrap(parts.first, "Empty UI action")

        switch action {
        case "dump":
            XCTAssertEqual(parts.count, 1, "dump expects no arguments")
            print("UNCIV_UI_TREE=\(application.debugDescription)")
        case "portrait":
            XCTAssertEqual(parts.count, 1, "portrait expects no arguments")
            XCUIDevice.shared.orientation = .portrait
        case "landscapeLeft":
            XCTAssertEqual(parts.count, 1, "landscapeLeft expects no arguments")
            XCUIDevice.shared.orientation = .landscapeLeft
        case "landscapeRight":
            XCTAssertEqual(parts.count, 1, "landscapeRight expects no arguments")
            XCUIDevice.shared.orientation = .landscapeRight
        case "tap":
            XCTAssertEqual(parts.count, 3, "tap expects tap,x,y")
            coordinate(x: try number(parts[1]), y: try number(parts[2]), in: application).tap()
        case "typeText":
            XCTAssertGreaterThanOrEqual(parts.count, 2, "typeText expects typeText,text")
            application.typeText(parts.dropFirst().joined(separator: ","))
        case "doubleTap":
            XCTAssertEqual(parts.count, 3, "doubleTap expects doubleTap,x,y")
            coordinate(x: try number(parts[1]), y: try number(parts[2]), in: application).doubleTap()
        case "press":
            XCTAssertEqual(parts.count, 4, "press expects press,x,y,duration")
            coordinate(x: try number(parts[1]), y: try number(parts[2]), in: application)
                .press(forDuration: try number(parts[3]))
        case "drag":
            XCTAssertEqual(parts.count, 6, "drag expects drag,startX,startY,endX,endY,duration")
            let start = coordinate(x: try number(parts[1]), y: try number(parts[2]), in: application)
            let end = coordinate(x: try number(parts[3]), y: try number(parts[4]), in: application)
            start.press(forDuration: try number(parts[5]), thenDragTo: end)
        case "wait":
            XCTAssertEqual(parts.count, 2, "wait expects wait,duration")
            RunLoop.current.run(until: Date().addingTimeInterval(try number(parts[1])))
        default:
            XCTFail("Unsupported UI action: \(action)")
        }
    }

    private func coordinate(x: Double, y: Double, in application: XCUIApplication) -> XCUICoordinate {
        let origin = application.coordinate(withNormalizedOffset: .zero)
        return origin.withOffset(CGVector(
            dx: application.frame.width * x,
            dy: application.frame.height * y
        ))
    }

    private func number(_ rawValue: String) throws -> Double {
        try XCTUnwrap(Double(rawValue), "Expected a number, got \(rawValue)")
    }
}
