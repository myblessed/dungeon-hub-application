import dev.kordex.gradle.plugins.kordex.DataCollection

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.10"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.10"

    //TODO fix errors
    //id("io.gitlab.arturbosch.detekt") version "1.23.6"

    id("dev.kordex.gradle.kordex") version "1.9.0"
    id("dev.kordex.gradle.i18n") version "1.1.1"
}

group = "net.dungeon-hub"
version = "1.0.0"
description = "dungeon-hub-application"

repositories {
    maven {
        url = uri("https://repo.hypixel.net/repository/Hypixel/")
        name = "Hypixel Repository"
    }

    mavenLocal()
}

kordEx {
    kordExVersion = "2.5.0-SNAPSHOT"
    kordVersion = "0.19.0-SNAPSHOT"
    jvmTarget = 25

    bot {
        // See https://docs.kordex.dev/data-collection.html
        dataCollection(DataCollection.Extra)

        mainClass = "net.dungeonhub.application.connection.DiscordConnection"
    }
}

i18n {
    bundle("dhub.strings", "net.dungeonhub.i18n")
}

//TODO fix errors
/*detekt {
    buildUponDefaultConfig = true

    config.from(rootProject.files("detekt.yml"))
}*/

dependencies {
    //TODO fix errors
    //detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:1.23.6")

    //Lombok, might remove at some time
    implementation("org.projectlombok:lombok:1.18.28")

    //Internal API
    implementation(libs.dungeon.hub.api.client)

    //Hypixel API
    implementation(libs.hypixel.wrapper)

    //Functionality
    implementation("net.dungeon-hub:transcripts-kord:0.2.2")
    implementation("com.google.zxing:javase:3.5.2")
    implementation("com.google.guava:guava:33.0.0-jre") // TODO use Koin and plug their dependency injection in the class loader instead of the current custom logic that uses guava
    implementation("org.mnode.ical4j:ical4j:4.1.1")

    //HTTP Client
    implementation("com.squareup.okhttp3:okhttp:4.10.0")
    implementation("io.ktor:ktor-client-java:3.0.0")

    //Logging
    implementation("ch.qos.logback:logback-core:1.5.6")
    implementation("ch.qos.logback:logback-classic:1.5.6")

    //Annotations
    annotationProcessor("org.projectlombok:lombok:1.18.28")

    //Testing
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.withType<Javadoc> {
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
}