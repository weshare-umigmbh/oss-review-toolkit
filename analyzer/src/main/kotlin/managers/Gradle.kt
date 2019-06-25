/*
 * Copyright (C) 2017-2019 HERE Europe B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

package com.here.ort.analyzer.managers

import Dependency
import DependencyTreeModel

import ch.frankel.slf4k.*

import com.here.ort.analyzer.AbstractPackageManagerFactory
import com.here.ort.analyzer.MavenSupport
import com.here.ort.analyzer.PackageManager
import com.here.ort.analyzer.identifier
import com.here.ort.downloader.VersionControlSystem
import com.here.ort.model.Identifier
import com.here.ort.model.OrtIssue
import com.here.ort.model.Package
import com.here.ort.model.PackageLinkage
import com.here.ort.model.PackageReference
import com.here.ort.model.Project
import com.here.ort.model.ProjectAnalyzerResult
import com.here.ort.model.Scope
import com.here.ort.model.Severity
import com.here.ort.model.VcsInfo
import com.here.ort.model.config.AnalyzerConfiguration
import com.here.ort.model.config.RepositoryConfiguration
import com.here.ort.utils.collectMessagesAsString
import com.here.ort.utils.getUserHomeDirectory
import com.here.ort.utils.log
import com.here.ort.utils.showStackTrace
import com.here.ort.utils.temporaryProperties

import java.io.File
import java.util.Properties

import org.apache.maven.project.ProjectBuildingException

import org.eclipse.aether.artifact.Artifact
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.repository.WorkspaceReader
import org.eclipse.aether.repository.WorkspaceRepository

import org.gradle.tooling.GradleConnector

/**
 * The [Gradle](https://gradle.org/) package manager for Java.
 */
class Gradle(
    name: String,
    analyzerRoot: File,
    analyzerConfig: AnalyzerConfiguration,
    repoConfig: RepositoryConfiguration
) : PackageManager(name, analyzerRoot, analyzerConfig, repoConfig) {
    class Factory : AbstractPackageManagerFactory<Gradle>("Gradle") {
        // Gradle prefers Groovy ".gradle" files over Kotlin ".gradle.kts" files, but "build" files have to come before
        // "settings" files as we should consider "settings" files only if the same directory does not also contain a
        // "build" file.
        override val globsForDefinitionFiles = listOf(
            "build.gradle", "build.gradle.kts",
            "settings.gradle", "settings.gradle.kts"
        )

        override fun create(
            analyzerRoot: File,
            analyzerConfig: AnalyzerConfiguration,
            repoConfig: RepositoryConfiguration
        ) = Gradle(managerName, analyzerRoot, analyzerConfig, repoConfig)
    }

    /**
     * A workspace reader that is backed by the local Gradle artifact cache.
     */
    private class GradleCacheReader : WorkspaceReader {
        private val workspaceRepository = WorkspaceRepository("gradleCache")
        private val gradleCacheRoot = getUserHomeDirectory().resolve(".gradle/caches/modules-2/files-2.1")

        override fun findArtifact(artifact: Artifact): File? {
            val artifactRootDir = File(
                gradleCacheRoot,
                "${artifact.groupId}/${artifact.artifactId}/${artifact.version}"
            )

            val artifactFile = artifactRootDir.walkTopDown().find {
                val classifier = if (artifact.classifier.isNullOrBlank()) "" else "${artifact.classifier}-"
                it.isFile && it.name == "${artifact.artifactId}-$classifier${artifact.version}.${artifact.extension}"
            }

            log.debug {
                "Gradle cache result for '${artifact.identifier()}:${artifact.classifier}:${artifact.extension}': " +
                        artifactFile?.invariantSeparatorsPath
            }

            return artifactFile
        }

        override fun findVersions(artifact: Artifact) =
            // Do not resolve versions of already locally available artifacts. This also ensures version resolution
            // was done by Gradle.
            if (findArtifact(artifact)?.isFile == true) listOf(artifact.version) else emptyList()

        override fun getRepository() = workspaceRepository
    }

    private val maven = MavenSupport(GradleCacheReader())

    override fun resolveDependencies(definitionFile: File): ProjectAnalyzerResult? {
        val gradleSystemProperties = mutableListOf<Pair<String, String>>()

        // Usually, the Gradle wrapper handles applying system properties from Gradle properties. But as we directly use
        // the tooling API, we need to manually load Gradle properties and apply any system properties. Limit the lookup
        // to the current user's Gradle properties file for now.
        val gradlePropertiesFile = getUserHomeDirectory().resolve(".gradle/gradle.properties")
        if (gradlePropertiesFile.isFile) {
            gradlePropertiesFile.inputStream().use {
                Properties().apply { load(it) }.mapNotNullTo(gradleSystemProperties) { (key, value) ->
                    val systemPropKey = (key as String).removePrefix("systemProp.")
                    (systemPropKey to (value as String)).takeIf { systemPropKey != key }
                }
            }
        }

        val gradleConnection = GradleConnector
            .newConnector()
            .forProjectDirectory(definitionFile.parentFile)
            .connect()

        return temporaryProperties(*gradleSystemProperties.toTypedArray()) {
            gradleConnection.use { connection ->
                val initScriptFile = File.createTempFile("init", ".gradle")
                initScriptFile.writeBytes(javaClass.getResource("/scripts/init.gradle").readBytes())

                val dependencyTreeModel = connection
                    .model(DependencyTreeModel::class.java)
                    .withArguments("--init-script", initScriptFile.path)
                    .get()

                if (!initScriptFile.delete()) {
                    log.warn { "Init script file '$initScriptFile' could not be deleted." }
                }

                val repositories = dependencyTreeModel.repositories.map {
                    // TODO: Also handle authentication and snapshot policy.
                    RemoteRepository.Builder(it, "default", it).build()
                }

                log.debug {
                    val projectName = dependencyTreeModel.name
                    "The Gradle project '$projectName' uses the following Maven repositories: $repositories"
                }

                val packages = mutableMapOf<String, Package>()
                val scopes = dependencyTreeModel.configurations.map { configuration ->
                    val dependencies = configuration.dependencies.map { dependency ->
                        parseDependency(dependency, packages, repositories)
                    }

                    Scope(configuration.name, dependencies.toSortedSet())
                }

                val project = Project(
                    id = Identifier(
                        type = managerName,
                        namespace = dependencyTreeModel.group,
                        name = dependencyTreeModel.name,
                        version = dependencyTreeModel.version
                    ),
                    definitionFilePath = VersionControlSystem.getPathInfo(definitionFile).path,
                    declaredLicenses = sortedSetOf(),
                    vcs = VcsInfo.EMPTY,
                    vcsProcessed = processProjectVcs(definitionFile.parentFile),
                    homepageUrl = "",
                    scopes = scopes.toSortedSet()
                )

                val issues = mutableListOf<OrtIssue>()

                dependencyTreeModel.errors.mapTo(issues) {
                    OrtIssue(source = managerName, message = it, severity = Severity.ERROR)
                }

                dependencyTreeModel.warnings.mapTo(issues) {
                    OrtIssue(source = managerName, message = it, severity = Severity.WARNING)
                }

                ProjectAnalyzerResult(
                    project,
                    packages.values.map { it.toCuratedPackage() }.toSortedSet(),
                    issues
                )
            }
        }
    }

    private fun parseDependency(
        dependency: Dependency, packages: MutableMap<String, Package>,
        repositories: List<RemoteRepository>
    ): PackageReference {
        val issues = mutableListOf<OrtIssue>()
        val versionX: String? = dependency.version
        val version: String = versionX ?: ""
        if (versionX == null) {
            log.debug { "dep with version = null '$dependency' groupId:'${dependency.groupId}' artifactId:'${dependency.artifactId}' classifier:'${dependency.classifier}' extension:'${dependency.extension}' pomFile:'${dependency.pomFile}' localPath:'${dependency.localPath}'" }
        }

        dependency.error?.let { issues += OrtIssue(source = managerName, message = it, severity = Severity.ERROR) }
        dependency.warning?.let { issues += OrtIssue(source = managerName, message = it, severity = Severity.WARNING) }

        // Only look for a package if there was no error resolving the dependency and it is no project dependency.
        if (dependency.error == null && dependency.localPath == null) {
            val identifier = "${dependency.groupId}:${dependency.artifactId}:${version}"

            packages.getOrPut(identifier) {
                try {
                    val artifact = DefaultArtifact(
                        dependency.groupId, dependency.artifactId, dependency.classifier,
                        dependency.extension, version
                    )

                    maven.parsePackage(artifact, repositories)
                } catch (e: ProjectBuildingException) {
                    e.showStackTrace()

                    log.error {
                        "Could not get package information for dependency '$identifier': ${e.message}"
                    }

                    issues += OrtIssue(source = managerName, message = e.collectMessagesAsString())

                    Package.EMPTY.copy(
                        id = Identifier(
                            type = "Maven",
                            namespace = dependency.groupId,
                            name = dependency.artifactId,
                            version = version
                        )
                    )
                }
            }
        }

        val transitiveDependencies = dependency.dependencies.map { parseDependency(it, packages, repositories) }

        return if (dependency.localPath != null) {
            val id = Identifier(managerName, dependency.groupId, dependency.artifactId, version)
            PackageReference(id, PackageLinkage.PROJECT_DYNAMIC, transitiveDependencies.toSortedSet(), issues)
        } else {
            val type = dependency.pomFile?.let { "Maven" } ?: "Unknown"
            val id = Identifier(type, dependency.groupId, dependency.artifactId, version)
            PackageReference(id, dependencies = transitiveDependencies.toSortedSet(), errors = issues)
        }
    }
}
