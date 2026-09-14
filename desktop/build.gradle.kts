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
    implementation("net.java.dev.jna:jna-jpms:5.14.0")
    implementation("net.java.dev.jna:jna-platform-jpms:5.14.0")
    implementation("uk.co.caprica:vlcj:4.8.3") {
        // Use our existing jna-jpms artifacts instead of vlcj's plain-jna transitive
        // dependency -- both provide com.sun.jna.*, and mixing them risks duplicate
        // class/module conflicts at runtime.
        exclude(group = "net.java.dev.jna")
    }
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.xerial:sqlite-jdbc:3.46.1.0")
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
    System.getProperty("tvivo.libvlc.dir")?.let { libVlcDirectory ->
        systemProperty("tvivo.libvlc.dir", libVlcDirectory)
        environment("VLC_PLUGIN_PATH", file("$libVlcDirectory/plugins").absolutePath)
    }
    System.getProperty("tvivo.debug.fixture")?.let { fixturePath ->
        systemProperty("tvivo.debug.fixture", fixturePath)
    }
    System.getProperty("tvivo.debug.verbose")?.let { flag ->
        systemProperty("tvivo.debug.verbose", flag)
    }
}
