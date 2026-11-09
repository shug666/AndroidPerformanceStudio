dependencies {
    implementation(project(":winscope-device-capture"))
    implementation(libs.aps.adb.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    // The proxy server and static viewer host run on the JDK's built-in HTTP
    // server (com.sun.net.httpserver), matching the PerfettoUiServer pattern;
    // no additional HTTP framework dependency is introduced.
}
