plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":shared-core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    // vlcj/LibVLC removed (D-Desktop-14): a bundled-libvlc playback stall couldn't be tuned away,
    // and the hand-built Compose transport controls were rejected as ongoing maintenance burden.
    // Replaced by MpvPlayer.kt/MpvLibrary.kt, a hand-rolled JNA binding against libmpv, reusing
    // these same jna-jpms artifacts (still needed for WindowsCredentialsStore's Crypt32Util).
    implementation("net.java.dev.jna:jna-jpms:5.14.0")
    implementation("net.java.dev.jna:jna-platform-jpms:5.14.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.xerial:sqlite-jdbc:3.46.1.0")
    testImplementation("junit:junit:4.13.2")
}

sourceSets.named("main") {
    resources.srcDir("../app/src/main/res/drawable-nodpi")
}

compose.desktop {
    application {
        mainClass = "com.dev.tvivo.desktop.MainKt"
    }
}

tasks.withType<org.gradle.api.tasks.JavaExec>().configureEach {
    System.getProperty("tvivo.libmpv.dir")?.let { libMpvDirectory ->
        systemProperty("tvivo.libmpv.dir", libMpvDirectory)
    }
    System.getProperty("tvivo.debug.fixture")?.let { fixturePath ->
        systemProperty("tvivo.debug.fixture", fixturePath)
    }
    System.getProperty("tvivo.debug.verbose")?.let { flag ->
        systemProperty("tvivo.debug.verbose", flag)
    }
}
