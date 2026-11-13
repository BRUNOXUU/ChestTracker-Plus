plugins {
    id("net.fabricmc.fabric-loom")
}

version = "1.1.0+26.3"
group = property("maven_group")!!

base {
    archivesName.set("chesttracker-scan")
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

repositories {
    mavenCentral()
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric-api_version")}")
}

tasks.withType<ProcessResources>().configureEach {
    filesMatching("fabric.mod.json") {
        expand(mapOf("version" to version))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
}
