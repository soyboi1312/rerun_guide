// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "RerunGuideCore",
    platforms: [.tvOS(.v17), .macOS(.v14), .iOS(.v17), .macCatalyst(.v17)],
    products: [.library(name: "RerunGuideCore", targets: ["RerunGuideCore"])],
    targets: [
        .target(name: "RerunGuideCore"),
        .testTarget(name: "RerunGuideCoreTests", dependencies: ["RerunGuideCore"]),
    ]
)
