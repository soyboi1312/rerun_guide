// swift-tools-version:5.9
// Writes android/core/src/test/resources/parity-fixture.json from the Swift Core (see README › Android, Google TV and Fire TV).
import PackageDescription
let package = Package(
    name: "fixturegen",
    platforms: [.macOS(.v14)],
    dependencies: [.package(path: "../../Core")],
    targets: [.executableTarget(name: "fixturegen", dependencies: [.product(name: "RerunGuideCore", package: "Core")])]
)
