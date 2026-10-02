// swift-tools-version:5.9
import PackageDescription

// WhatikCore: logica pura (rilevatori, ritaglio, pack, libreria), solo Foundation.
// WhatikMedia: codifica/decodifica WebP con libwebp e conversione degli sticker.
// Nessuna dipendenza da UIKit: si compila e si testa anche su Linux con `swift test`.
let package = Package(
    name: "WhatikCore",
    platforms: [.iOS(.v16), .macOS(.v12)],
    products: [
        .library(name: "WhatikCore", targets: ["WhatikCore"]),
        .library(name: "WhatikMedia", targets: ["WhatikMedia"]),
    ],
    dependencies: [
        .package(url: "https://github.com/SDWebImage/libwebp-Xcode", from: "1.5.0"),
    ],
    targets: [
        .target(name: "WhatikCore"),
        .target(
            name: "WhatikMedia",
            dependencies: ["WhatikCore", .product(name: "libwebp", package: "libwebp-Xcode")],
            linkerSettings: [.linkedLibrary("m", .when(platforms: [.linux]))]
        ),
        .testTarget(name: "WhatikCoreTests", dependencies: ["WhatikCore", "WhatikMedia"]),
    ]
)
