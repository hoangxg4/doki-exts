import tasks.ReportGenerateTask

plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    `maven-publish`
}

group = "org.dokiteam"
version = "1.0"

ksp {
    arg("summaryOutputDir", "${project.projectDir}/.github")
}

// ✅ ĐÃ CẬP NHẬT: Sử dụng compilerOptions theo chuẩn mới
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        freeCompilerArgs.addAll(listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=kotlin.contracts.ExperimentalContracts",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=org.dokiteam.doki.parsers.InternalParsersApi",
        ))
    }
}

kotlin {
    jvmToolchain(8)
    explicitApi()
    sourceSets.main.get().kotlin.srcDirs("build/generated/ksp/main/kotlin")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
        }
    }
}

dependencies {
    api(libs.jsoup)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okio)
    implementation(libs.json)
    implementation(libs.androidx.collection)
    implementation(libs.nanohttpd)

    ksp(project(":doki-ksp"))

    testImplementation(libs.bundles.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.quickjs)
}

tasks.register<ReportGenerateTask>("generateTestsReport")
