import net.fabricmc.loom.api.LoomGradleExtensionAPI

plugins {
    id("dev.kikugie.stonecutter")
    `java-library`
}

extra["minecraft_version"] = stonecutter.current.version
apply(from = rootProject.file("gradle/node.gradle.kts"))
// 26.1 dropped obfuscation: no mappings and no remapping from there on.
val unobfuscated = stonecutter.eval(stonecutter.current.version, ">=26.1")
apply(plugin = if (unobfuscated) "net.fabricmc.fabric-loom" else "net.fabricmc.fabric-loom-remap")

base {
    archivesName = "${rootProject.property("mod_id")}-common"
}

tasks.named<Test>("test") {
    inputs.dir(rootProject.file("examples/scenarios"))
        .withPropertyName("scenarioLibrary")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

dependencies {
    "minecraft"("com.mojang:minecraft:${stonecutter.current.version}")
    if (!unobfuscated) "mappings"(project.the<LoomGradleExtensionAPI>().officialMojangMappings())
    // Minecraft's classes carry Fabric Loader's @Environment annotations; without it javac warns on EnvType.
    compileOnly("net.fabricmc:fabric-loader:${property("fabric_loader_version")}")
    // the annotations of the client mixin; every loader ships Mixin at runtime
    compileOnly("net.fabricmc:sponge-mixin:0.17.5+mixin.0.8.7")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.2")
}
