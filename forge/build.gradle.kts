import net.minecraftforge.gradle.ForgeGradleExtension
import net.minecraftforge.gradle.MinecraftExtensionForProject

plugins {
    id("dev.kikugie.stonecutter")
    `java-library`
}

val minecraftVersion = stonecutter.current.version
extra["minecraft_version"] = minecraftVersion
apply(from = rootProject.file("gradle/node.gradle.kts"))
// common's node for this Minecraft version; its sources are compiled into this jar
val commonNode = stonecutter.node.sibling("common")!!.project
evaluationDependsOn(commonNode.path)
apply(plugin = "net.minecraftforge.gradle")
val minecraft = the<MinecraftExtensionForProject>()
val fg = the<ForgeGradleExtension>()

base {
    archivesName = "${rootProject.property("mod_id")}-forge"
}

// 26.1 dropped obfuscation; before it, Forge maps to Mojang's official names.
if (stonecutter.eval(minecraftVersion, "<26.1")) minecraft.mappings("official", minecraftVersion)

minecraft.runs {
    // One directory per side: both sides write mcp/server.json.
    register("client") { workingDir.set(layout.projectDirectory.dir("run/client")) }
    register("server") { workingDir.set(layout.projectDirectory.dir("run/server")); args("--nogui") }
}

repositories {
    minecraft.mavenizer(this)
    maven(fg.forgeMaven)
    maven(fg.minecraftLibsMaven)
}

dependencies {
    "implementation"(minecraft.dependency("net.minecraftforge:forge:$minecraftVersion-${property("forge_version")}"))
}

// The last source dir of common's node is the copy Stonecutter processed for this version.
val commonSources = commonNode.the<SourceSetContainer>()["main"]
val commonGenerate = commonNode.tasks.matching { it.name == "stonecutterGenerate" }
tasks.named<JavaCompile>("compileJava") {
    dependsOn(commonGenerate)
    source(commonSources.java.srcDirs.last())
}

// Collected while configuring: reading the project from inside the task is deprecated (Gradle 10).
val templateValues = mapOf(
    "version" to version.toString(),
    "minecraft_version" to minecraftVersion,
    "forge_loader_range" to property("forge_loader_range"),
    // Forge wants a pack.mcmeta in every mod; 1.21.9 replaced pack_format with a min/max range.
    "pack_format_fields" to (property("pack_format") as String).let {
        if (stonecutter.eval(minecraftVersion, ">=1.21.9")) "\"min_format\": $it, \"max_format\": $it" else "\"pack_format\": $it"
    },
) + listOf("mod_id", "mod_name", "mod_description", "mod_author", "mod_license").associateWith { rootProject.property(it) }

tasks.named<ProcessResources>("processResources") {
    dependsOn(commonGenerate)
    from(commonSources.resources.srcDirs.last())
    inputs.properties(templateValues)
    filesMatching(listOf("META-INF/mods.toml", "pack.mcmeta")) { expand(templateValues) }
}
