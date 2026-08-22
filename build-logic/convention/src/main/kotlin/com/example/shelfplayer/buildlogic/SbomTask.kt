package com.example.shelfplayer.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * PRODUCT_SPEC 18 — the Software Bill of Materials, in CycloneDX 1.5 JSON.
 *
 * ### Why this exists rather than a plugin
 *
 * `org.cyclonedx.bom` would do this, and adding it costs a new plugin on the build classpath plus its
 * transitive tree inside `strict` dependency verification — for a document this build already holds all the
 * inputs for. Everything below is read from files the repository or the Gradle cache already contains, so
 * the task needs no network and produces the same bytes on any machine with a warm cache.
 *
 * ### Two sources, each for the one thing it is authoritative about
 *
 * **Scope comes from the resolved graph, not from `verification-metadata.xml`.** That file lists every
 * version Gradle ever resolved *metadata* for, which includes versions that lost conflict resolution —
 * `androidx.activity` appears at 1.5.1, 1.7.0, 1.8.2 and 1.10.1, and only the last is in the app. An SBOM
 * built from it would name four versions of one library as shipped. A supply-chain document that misstates
 * the supply chain is worse than none, so the component set is `releaseRuntimeClasspath`'s resolution
 * result, which is the graph after conflict resolution.
 *
 * **Integrity comes from `verification-metadata.xml`**, because that is where this project's pinned
 * SHA-256 values live and re-hashing the cache would only prove the cache agrees with itself. A component
 * whose checksum is absent there is emitted **without** a hash rather than with a computed one; under
 * `strict` verification that combination should be impossible, and inventing a hash would hide it if it
 * ever happened.
 *
 * **Licences come from each component's own POM**, copied verbatim from `<licenses>`, and are **omitted**
 * when the POM declares none. An omitted licence means the publisher did not state one — not that the
 * component is unlicensed, and not that anybody has audited it. `docs/release.md` says so, because a
 * licence field in an SBOM is read as a finding rather than as a quotation.
 */
@CacheableTask
abstract class SbomTask : DefaultTask() {

    /**
     * The resolution result of the configuration being described.
     *
     * Held as a `Property<ResolvedComponentResult>` — the shape Gradle documents for consuming a graph in
     * a task — so resolution happens at execution time and the configuration cache can serialise it.
     */
    @get:Internal
    abstract val rootComponent: Property<ResolvedComponentResult>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val verificationMetadata: RegularFileProperty

    /**
     * `GRADLE_USER_HOME`, used only to find POMs for their licence declarations.
     *
     * `@Internal` rather than an input: its *contents* are what matter and hashing the whole Gradle cache
     * to detect that would cost more than the task. The consequence is that a licence appearing in a POM
     * that was not cached on the previous run needs `--rerun-tasks` to be picked up, which is acceptable
     * for a field this task never invents.
     */
    @get:Internal
    abstract val gradleUserHome: DirectoryProperty

    @get:Input
    abstract val applicationId: Property<String>

    @get:Input
    abstract val versionName: Property<String>

    @get:Input
    abstract val projectLicense: Property<String>

    /**
     * Modules to leave out because they are not part of the shipped application.
     *
     * Empty today. It exists so that a future exclusion has to be named here, next to this comment,
     * rather than applied silently inside the traversal.
     */
    @get:Input
    abstract val excludedModules: SetProperty<String>

    /**
     * Whether a shipped component with no pinned checksum fails the build.
     *
     * Default `true`, and this is the task's one assertion rather than a preference. Under
     * `org.gradle.dependency.verification=strict` a binary that reaches the application without a pinned
     * SHA-256 cannot happen; if it ever does, that is a supply-chain finding, and a JSON property nobody
     * reads is the wrong place to put it. Components that publish **no** binary at all — a KMP parent, a
     * BOM — are not failures and never trip this.
     */
    @get:Input
    abstract val failOnUnpinned: Property<Boolean>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val excluded = excludedModules.get()
        val components = collectComponents(rootComponent.get())
            .filterNot { "${it.group}:${it.module}" in excluded }
            .sortedBy { "${it.group}:${it.module}:${it.version}" }

        val checksums = readChecksums(verificationMetadata.get().asFile)
        val cacheRoot = gradleUserHome.get().asFile.resolve(MODULE_CACHE)

        val body = buildString {
            append("{\n")
            append("""  "bomFormat": "CycloneDX",""").append('\n')
            append("""  "specVersion": "1.5",""").append('\n')
            append("""  "version": 1,""").append('\n')
            append("""  "metadata": {""").append('\n')
            append("""    "component": {""").append('\n')
            append("""      "type": "application",""").append('\n')
            append("""      "bom-ref": "${applicationId.get()}",""").append('\n')
            append("""      "name": "${applicationId.get()}",""").append('\n')
            append("""      "version": "${versionName.get()}",""").append('\n')
            append("""      "licenses": [{ "license": { "id": "${projectLicense.get()}" } }]""").append('\n')
            append("    }\n")
            append("  },\n")
            append("""  "components": [""").append('\n')
            components.forEachIndexed { index, id ->
                append(componentJson(id, checksums, cacheRoot))
                if (index != components.lastIndex) append(',')
                append('\n')
            }
            append("  ]\n")
            append("}\n")
        }

        val target = outputFile.get().asFile
        target.parentFile?.mkdirs()
        target.writeText(body)

        val withHash = components.count { binaryOf(it, checksums) != null }
        logger.lifecycle(
            "SBOM: ${components.size} components, $withHash with a pinned binary -> ${target.absolutePath}",
        )

        // Written first, then asserted, so a failing build still leaves the document that shows why.
        val unpinned = components.filter { id ->
            binaryOf(id, checksums) == null && checksums["${id.group}:${id.module}:${id.version}"] == null
        }
        if (unpinned.isNotEmpty() && failOnUnpinned.get()) {
            throw GradleException(
                buildString {
                    append("${unpinned.size} component(s) on the release runtime classpath have no pinned ")
                    append("checksum, which strict dependency verification should make impossible:")
                    unpinned.forEach { append("\n  - ${it.group}:${it.module}:${it.version}") }
                    append("\n\nSee ${target.absolutePath} and docs/release.md.")
                },
            )
        }
    }

    private fun componentJson(
        id: ModuleComponentIdentifier,
        checksums: Map<String, List<Pair<String, String>>>,
        cacheRoot: File,
    ): String {
        val purl = "pkg:maven/${id.group}/${id.module}@${id.version}"
        return buildString {
            append("    {\n")
            append("""      "type": "library",""").append('\n')
            append("""      "bom-ref": "$purl",""").append('\n')
            append("""      "group": "${id.group}",""").append('\n')
            append("""      "name": "${id.module}",""").append('\n')
            append("""      "version": "${id.version}",""").append('\n')
            append("""      "purl": "$purl"""")
            licensesJson(id, cacheRoot)?.let { append(",\n").append(it) }
            hashesJson(id, checksums)?.let { append(",\n").append(it) }
            binaryAbsenceReason(id, checksums)?.let { append(",\n").append(it) }
            append('\n')
            append("    }")
        }
    }

    /**
     * The pinned SHA-256 of the component's shipped binary, or `null` when it publishes none.
     *
     * Selected by extension from what the metadata actually lists, in a deliberate order:
     *
     * - an `.aar` is the Android binary, and there is at most one;
     * - otherwise a `.jar` that is not Kotlin Multiplatform *metadata* (`-metadata-<version>.jar`) and not
     *   sources or javadoc — those are published alongside a binary and are not it;
     * - otherwise nothing, which is the honest answer for a component that ships no binary of its own.
     *
     * That last case is common and benign rather than a gap: a KMP or relocation parent such as
     * `androidx.compose.animation:animation` exists to redirect to `animation-android`, and a BOM
     * publishes only a POM. [binaryAbsenceReason] records which of those it is, so a reader can tell "no
     * binary published" from "not pinned" — a distinction a bare missing field would hide, and the one
     * thing an SBOM must not be vague about.
     */
    private fun hashesJson(
        id: ModuleComponentIdentifier,
        checksums: Map<String, List<Pair<String, String>>>,
    ): String? {
        val sha = binaryOf(id, checksums)?.second ?: return null
        return """      "hashes": [{ "alg": "SHA-256", "content": "$sha" }]"""
    }

    private fun binaryOf(
        id: ModuleComponentIdentifier,
        checksums: Map<String, List<Pair<String, String>>>,
    ): Pair<String, String>? {
        val artifacts = checksums["${id.group}:${id.module}:${id.version}"] ?: return null
        artifacts.firstOrNull { it.first.endsWith(".aar") }?.let { return it }
        return artifacts.firstOrNull { (name, _) ->
            name.endsWith(".jar") &&
                !name.endsWith("-metadata-${id.version}.jar") &&
                !name.endsWith("-sources.jar") &&
                !name.endsWith("-javadoc.jar")
        }
    }

    /**
     * Why a component carries no hash, as a CycloneDX property.
     *
     * Present only when there is no binary, so a component with a hash carries no explanation it does not
     * need. The two values are different findings: `no-binary-published` is a fact about the publisher,
     * and `not-pinned` would be a fact about this repository that `strict` verification should make
     * impossible — so if it ever appears in a generated SBOM, it is a supply-chain finding rather than a
     * formatting quirk.
     */
    private fun binaryAbsenceReason(
        id: ModuleComponentIdentifier,
        checksums: Map<String, List<Pair<String, String>>>,
    ): String? {
        if (binaryOf(id, checksums) != null) return null
        val known = checksums["${id.group}:${id.module}:${id.version}"]
        val reason = if (known == null) "not-pinned" else "no-binary-published"
        val listed = known?.joinToString(" ") { it.first }.orEmpty()
        return """      "properties": [
        { "name": "shelfplayer:hash-absent", "value": "$reason" },
        { "name": "shelfplayer:pinned-artifacts", "value": "${escape(listed)}" }
      ]"""
    }

    /**
     * The component's own licence declaration, or `null` when its POM states none.
     *
     * SPDX identifiers are not attempted. The POM carries free text — "The Apache Software License,
     * Version 2.0" — and mapping that to `Apache-2.0` is a judgement this task is not entitled to make on
     * a publisher's behalf, so the name is quoted into CycloneDX's `license.name` field, which exists for
     * exactly this case.
     */
    private fun licensesJson(id: ModuleComponentIdentifier, cacheRoot: File): String? {
        val pom = findPom(id, cacheRoot) ?: return null
        val names = LICENSE_NAME.findAll(pom.readText())
            .map { it.groupValues[1].trim() }
            .filter(String::isNotEmpty)
            .map(::escape)
            .distinct()
            .toList()
        if (names.isEmpty()) return null
        val entries = names.joinToString(", ") { """{ "license": { "name": "$it" } }""" }
        return """      "licenses": [$entries]"""
    }

    /**
     * Locates a cached POM.
     *
     * The `modules-2/files-2.1/<group>/<name>/<version>/<sha1>/<file>` layout is not public API. It is
     * used anyway, for two reasons: it has been stable across many Gradle releases, and every failure mode
     * is the same benign one — no POM found means no licence field, which is already a documented outcome.
     * `ArtifactResolutionQuery` is the supported alternative and would reach the network on a cold cache,
     * which would make this task's output depend on connectivity.
     */
    private fun findPom(id: ModuleComponentIdentifier, cacheRoot: File): File? {
        val versionDir = cacheRoot.resolve(id.group).resolve(id.module).resolve(id.version)
        if (!versionDir.isDirectory) return null
        val wanted = "${id.module}-${id.version}.pom"
        return versionDir.listFiles()
            ?.asSequence()
            ?.filter(File::isDirectory)
            ?.map { it.resolve(wanted) }
            ?.firstOrNull(File::isFile)
    }

    /** Every external module in the graph, after conflict resolution, without duplicates. */
    private fun collectComponents(root: ResolvedComponentResult): List<ModuleComponentIdentifier> {
        val found = LinkedHashMap<String, ModuleComponentIdentifier>()
        val seen = mutableSetOf<ResolvedComponentResult>()

        fun walk(component: ResolvedComponentResult) {
            if (!seen.add(component)) return
            (component.id as? ModuleComponentIdentifier)?.let { id ->
                found.putIfAbsent("${id.group}:${id.module}:${id.version}", id)
            }
            component.dependencies
                .filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>()
                .forEach { walk(it.selected) }
        }

        walk(root)
        return found.values.toList()
    }

    /**
     * Component coordinate to its pinned artefacts, as (file name, SHA-256) pairs.
     *
     * The artefact *list* is kept rather than flattened onto constructed file names, because the names
     * cannot be constructed: AndroidX publishes its Android binary as `animation-release.aar`, not
     * `animation-1.8.3.aar`. A first version of this task built the expected name and found a hash for 96
     * of 175 components, which read as "this project does not pin most of its dependencies" when in fact
     * it pins all of them and the lookup was wrong.
     *
     * Parsed with regular expressions rather than an XML reader, which is normally the wrong choice and is
     * defensible for exactly one reason: this file is machine-generated by Gradle in a fixed shape, it is
     * in this repository rather than arriving from anywhere, and the alternative is a build-classpath XML
     * dependency for one file. A shape change breaks the extraction loudly — the map comes back empty and
     * every component reports no pinned artefact — rather than silently producing wrong values.
     */
    private fun readChecksums(file: File): Map<String, List<Pair<String, String>>> {
        val text = file.readText()
        val result = mutableMapOf<String, MutableList<Pair<String, String>>>()
        COMPONENT_BLOCK.findAll(text).forEach { block ->
            val stem = "${block.groupValues[1]}:${block.groupValues[2]}:${block.groupValues[3]}"
            ARTIFACT_SHA.findAll(block.groupValues[4]).forEach { artifact ->
                result.getOrPut(stem) { mutableListOf() }
                    .add(artifact.groupValues[1] to artifact.groupValues[2])
            }
        }
        return result
    }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    private companion object {
        const val MODULE_CACHE = "caches/modules-2/files-2.1"

        val COMPONENT_BLOCK =
            Regex("""<component group="([^"]+)" name="([^"]+)" version="([^"]+)">(.*?)</component>""", RegexOption.DOT_MATCHES_ALL)
        val ARTIFACT_SHA =
            Regex("""<artifact name="([^"]+)">\s*<sha256 value="([0-9a-f]+)""", RegexOption.DOT_MATCHES_ALL)
        val LICENSE_NAME = Regex("""<license>.*?<name>(.*?)</name>""", RegexOption.DOT_MATCHES_ALL)
    }
}
