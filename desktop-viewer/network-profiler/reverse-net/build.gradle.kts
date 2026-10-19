// Vendored official gnirehtet.apk (see third_party/gnirehtet/README.md) is consumed
// directly from third_party/ at session start; no resource packaging needed here.
dependencies {
    implementation(libs.aps.adb.core)
    implementation(libs.kotlinx.coroutines.core)
}

tasks.withType<Test>().configureEach {
    if (project.gradle.startParameter.taskNames.none { it.lowercase().contains("realdevice") }) {
        useJUnitPlatform {
            excludeTags("real-device")
        }
    }
}
tasks.register("realDeviceTest", Test::class) {
    group = "verification"
    description = "Runs end-to-end smoke tests against a real device."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("real-device") }
}
