import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.8.0"
    id("com.github.johnrengelman.shadow") version "7.0.0"
}

group = "cn.chahuyun"
version = "0.1.0-SNAPSHOT"

repositories {
    findProperty("overflowRepository")?.toString()?.takeIf(String::isNotBlank)?.let { maven(it) }
    mavenCentral()
    maven("https://central.sonatype.com/repository/maven-snapshots/")
}

val overflowProviderApiVersion =
    findProperty("overflowProviderApiVersion")?.toString()
        ?: "0.1.0-gateway-SNAPSHOT"
val coroutinesVersion = "1.7.3"
val serializationVersion = "1.5.1"

dependencies {
    compileOnly("cn.chahuyun:overflow-provider-api:$overflowProviderApiVersion")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "1.8"
}

tasks.shadowJar {
    archiveClassifier.set("")
    mergeServiceFiles()
    dependencies {
        exclude(dependency("cn.chahuyun:overflow-provider-api:.*"))
        exclude(dependency("org.jetbrains.kotlin:.*:.*"))
        exclude(dependency("org.jetbrains.kotlinx:kotlinx-coroutines-.*:.*"))
        exclude(dependency("org.jetbrains.kotlinx:kotlinx-serialization-.*:.*"))
        exclude(dependency("org.slf4j:slf4j-api:.*"))
    }
}

tasks.build {
    dependsOn("verifyProviderArtifact")
}

tasks.register("verifyProviderArtifact") {
    dependsOn(tasks.shadowJar)
    doLast {
        val jar = tasks.shadowJar.get().archiveFile.get().asFile
        val entries = zipTree(jar)
        check(entries.matching {
            include("cn/chahuyun/overflow/provider/qqofficial/QqOfficialPlatformProvider.class")
        }.files.isNotEmpty()) { "Provider implementation is missing from $jar" }
        check(entries.matching {
            include("META-INF/services/top.mrxiaom.overflow.provider.PlatformProvider")
        }.files.isNotEmpty()) { "Provider SPI registration is missing from $jar" }
        check(entries.matching {
            include("META-INF/overflow/providers/official-qq.json")
        }.files.isNotEmpty()) { "Provider configuration template is missing from $jar" }
        check(entries.matching {
            include("top/mrxiaom/overflow/provider/PlatformProvider.class")
            include("kotlin/**")
            include("kotlinx/coroutines/**")
            include("kotlinx/serialization/**")
            include("org/slf4j/**")
        }.files.isEmpty()) { "Provider artifact bundles classes supplied by the Gateway" }
    }
}
