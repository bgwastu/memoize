// swift-tools-version: 6.2
import PackageDescription

let package = Package(
    name: "Memoize",
    platforms: [.macOS("26.0")],
    products: [.executable(name: "Memoize", targets: ["Memoize"])],
    dependencies: [
        .package(url: "https://github.com/groue/GRDB.swift.git", exact: "7.11.1"),
        .package(url: "https://github.com/sindresorhus/KeyboardShortcuts.git", exact: "3.1.0"),
        .package(url: "https://github.com/swiftlang/swift-markdown.git", exact: "0.9.0"),
        .package(url: "https://github.com/sparkle-project/Sparkle", exact: "2.10.0"),
    ],
    targets: [
        .target(name: "MemoizeCore", dependencies: [
            .product(name: "GRDB", package: "GRDB.swift"),
            .product(name: "Markdown", package: "swift-markdown"),
        ], resources: [.process("Resources")]),
        .executableTarget(name: "Memoize", dependencies: ["MemoizeCore", "KeyboardShortcuts", "Sparkle"],
                          linkerSettings: [.unsafeFlags(["-Xlinker", "-rpath", "-Xlinker", "@executable_path/../Frameworks"])]),
        .testTarget(name: "MemoizeCoreTests", dependencies: ["MemoizeCore"], resources:[.copy("Fixtures")]),
    ]
)
