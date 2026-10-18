dependencies {
    implementation(project(":perfetto-model"))
    api(libs.aps.platform.perfetto)
    testImplementation(libs.kotlinx.coroutines.core)
}
