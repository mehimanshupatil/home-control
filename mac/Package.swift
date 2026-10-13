// swift-tools-version:5.10
import PackageDescription

let package = Package(
    name: "HomeBar",
    platforms: [.macOS(.v14)],
    targets: [
        .executableTarget(name: "HomeBar", path: "Sources/HomeBar")
    ]
)
