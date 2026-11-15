// Host/target OS + arch detection, aligned with desktop-app/build.gradle.kts so that
// standalone device-screencast builds and desktop-app aggregate packaging resolve the
// same target platform. Composite-build Gradle properties do not propagate across
// includeBuild boundaries by default, so this build probes the host itself and honors
// -Ptarget.arch when present (cross-arch packaging is driven by desktop-app's call).
val hostOs = System.getProperty("os.name").lowercase()
val hostArch =
    when (System.getProperty("os.arch").lowercase()) {
        "x86_64", "amd64" -> "x64"
        "aarch64", "arm64" -> "arm64"
        else -> error("Unsupported screencast host architecture: ${System.getProperty("os.arch")}")
    }
val targetArch = project.findProperty("target.arch")?.toString()?.lowercase() ?: hostArch
val targetOs =
    when {
        hostOs.contains("mac") -> "macos"
        hostOs.contains("win") -> "windows"
        hostOs.contains("linux") -> "linux"
        else -> error("Unsupported screencast host operating system: $hostOs")
    }

// bytedeco native classifier mapping. bytedeco's classifier strings are NOT the same shape
// as targetOs/targetArch: macOS is "macosx" (not "macos") and arm uses "arm64" (not "aarch64").
// An explicit map avoids the trap of string-concatenating "$targetOs-$targetArch", which
// would produce the non-existent "macos-arm64" and 404 on Maven Central.
val nativeClassifier =
    when ("$targetOs-$targetArch") {
        "linux-x64" -> "linux-x86_64"
        "windows-x64" -> "windows-x86_64"
        "macos-arm64" -> "macosx-arm64"
        "macos-x64" -> "macosx-x86_64"
        "linux-arm64" -> "linux-arm64"
        else -> error("No bytedeco native classifier for $targetOs-$targetArch; add it to the mapping.")
    }

dependencies {
    implementation(libs.aps.adb.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.jcodec)
    implementation(libs.jcodec.javase)
    // FFmpeg soft decoder (JavaCV). A per-target-platform native classifier keeps the
    // native distribution at ~100MB per target (instead of the multi-GB platform bundle)
    // while ensuring the host actually has loadable native libraries. Without the correct
    // classifier, org.bytedeco.ffmpeg.global.avutil fails to initialize (no native lib for
    // the running OS) and decoding degrades to the jcodec pure-Java fallback. javacv itself
    // carries no native code — natives come from the ffmpeg + javacpp platform jars below.
    implementation(libs.javacpp)
    implementation("org.bytedeco:javacpp:${libs.versions.javacpp.get()}:$nativeClassifier")
    implementation(libs.javacv)
    implementation(libs.bytedeco.ffmpeg)
    implementation("org.bytedeco:ffmpeg:${libs.versions.bytedeco.ffmpeg.get()}:$nativeClassifier")
}
