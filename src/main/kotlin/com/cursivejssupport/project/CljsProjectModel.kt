package com.cursivejssupport.project

import com.cursivejssupport.index.JsSymbolIndex
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

enum class IndexPhase { NOT_STARTED, INDEXING, READY, FAILED }

data class IndexStatus(
    val phase: IndexPhase = IndexPhase.NOT_STARTED,
    val packageCount: Int = 0,
    val lastRefresh: Instant? = null,
    val warnings: List<String> = emptyList(),
)

data class IndexSnapshot(
    val generation: Long,
    val status: IndexStatus,
)

@Service(Service.Level.PROJECT)
class CljsProjectModel(private val project: Project) {
    private val profilesRef = AtomicReference<List<CljsBuildProfile>>(emptyList())
    private val snapshotRef = AtomicReference(IndexSnapshot(0, IndexStatus()))

    val profiles: List<CljsBuildProfile> get() = profilesRef.get()
    val indexSnapshot: IndexSnapshot get() = snapshotRef.get()

    fun refreshProfiles(): List<CljsBuildProfile> {
        val root = project.basePath?.let(::File) ?: return emptyList()
        val builtIns = listOf(
            ShadowCljsBuildProfileAdapter(),
            FigwheelMainBuildProfileAdapter(),
            CljsMainBuildProfileAdapter(),
        )
        val discovered = (builtIns + ADAPTER_EP.extensionList)
            .flatMap { runCatching { it.discover(root) }.getOrDefault(emptyList()) }
            .distinctBy { it.id }
            .sortedBy { it.displayName }
        profilesRef.set(discovered)
        return discovered
    }

    fun markIndexing() = updateStatus(IndexStatus(IndexPhase.INDEXING))

    fun markReady(packageCount: Int, warnings: List<String> = emptyList()) =
        updateStatus(IndexStatus(IndexPhase.READY, packageCount, Instant.now(), warnings))

    fun markFailed(message: String) =
        updateStatus(IndexStatus(IndexPhase.FAILED, lastRefresh = Instant.now(), warnings = listOf(message)))

    private fun updateStatus(status: IndexStatus) {
        val previous = snapshotRef.get()
        snapshotRef.set(IndexSnapshot(previous.generation + 1, status))
    }

    fun index(): JsSymbolIndex = JsSymbolIndex.getInstance(project)

    /**
     * Runtime targets that cover [file], i.e. the union of [CljsBuildProfile.target] for every
     * build profile whose `source-paths` contain the file. Returns an empty set when no profile
     * covers the file (unknown/mixed target) — callers should then show symbols from every
     * environment rather than filtering.
     */
    fun runtimeTargetsForFile(file: VirtualFile): Set<CljsRuntimeTarget> {
        val path = file.path
        val targets = mutableSetOf<CljsRuntimeTarget>()
        for (profile in profiles) {
            val workDir = profile.resolvedWorkingDirectory(File(project.basePath ?: return emptySet()))
            for (src in profile.sourcePaths) {
                val srcDir = File(src).let { if (it.isAbsolute) it else File(workDir, src) }.normalize()
                if (path.startsWith(srcDir.absolutePath + File.separator) || path == srcDir.absolutePath) {
                    targets.add(profile.target)
                    break
                }
            }
        }
        return targets
    }

    companion object {
        private val ADAPTER_EP = ExtensionPointName.create<CljsBuildProfileAdapter>("com.cursivejssupport.buildProfileAdapter")
        fun getInstance(project: Project): CljsProjectModel = project.service()
    }
}
