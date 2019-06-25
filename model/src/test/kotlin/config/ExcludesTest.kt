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

package com.here.ort.model.config

import com.here.ort.model.AnalyzerResult
import com.here.ort.model.AnalyzerRun
import com.here.ort.model.Environment
import com.here.ort.model.Identifier
import com.here.ort.model.OrtResult
import com.here.ort.model.PackageReference
import com.here.ort.model.Project
import com.here.ort.model.Repository
import com.here.ort.model.Scope
import io.kotlintest.TestCase

import io.kotlintest.matchers.beEmpty
import io.kotlintest.matchers.collections.contain
import io.kotlintest.matchers.collections.containExactly
import io.kotlintest.matchers.collections.containExactlyInAnyOrder
import io.kotlintest.matchers.collections.containOnlyNulls
import io.kotlintest.matchers.containAll
import io.kotlintest.matchers.haveSize
import io.kotlintest.should
import io.kotlintest.shouldBe
import io.kotlintest.specs.WordSpec

class ExcludesTest : WordSpec() {

    private lateinit var ortResult: OrtResult

    override fun beforeTest(testCase: TestCase) {
        ortResult = OrtResult(
            repository = Repository.EMPTY,
            analyzer = AnalyzerRun(
                environment = Environment(),
                config = AnalyzerConfiguration(ignoreToolVersions = false, allowDynamicVersions = false),
                result = AnalyzerResult.EMPTY
            )
        )
    }

    private fun setExcludes(excludes: Excludes) {
        val config = ortResult.repository.config.copy(excludes = excludes)
        ortResult = ortResult.replaceConfig(config)
    }

    private fun setProjects(vararg projects: Project) {
        val analyzerResult = ortResult.analyzer!!.result.copy(projects = projects.toSortedSet())
        ortResult = ortResult.copy(analyzer = ortResult.analyzer!!.copy(result = analyzerResult))
    }

    init {
        val id = Identifier("type", "namespace", "name", "version")

        val projectId1 = id.copy(name = "project1")
        val projectId2 = id.copy(name = "project2")
        val projectId3 = id.copy(name = "project3")

        val project1 = Project.EMPTY.copy(id = projectId1, definitionFilePath = "path1")
        val project2 = Project.EMPTY.copy(id = projectId2, definitionFilePath = "path2")
        val project3 = Project.EMPTY.copy(id = projectId3, definitionFilePath = "path3")

        val projectExclude1 = ProjectExclude("path1", ProjectExcludeReason.BUILD_TOOL_OF, "")
        val projectExclude2 = ProjectExclude("path2", ProjectExcludeReason.BUILD_TOOL_OF, "")
        val projectExclude3 = ProjectExclude("path3", ProjectExcludeReason.BUILD_TOOL_OF, "")
        val projectExclude4 = ProjectExclude(".*", ProjectExcludeReason.BUILD_TOOL_OF, "")


        val pathExclude1 = PathExclude("path1", PathExcludeReason.BUILD_TOOL_OF, "")
        val pathExclude2 = PathExclude("path2", PathExcludeReason.BUILD_TOOL_OF, "")
        val pathExclude3 = PathExclude("**.ext", PathExcludeReason.BUILD_TOOL_OF, "")
        val pathExclude4 = PathExclude("**/file.ext", PathExcludeReason.BUILD_TOOL_OF, "")

        val scope1 = Scope("scope1", sortedSetOf(PackageReference(id)))
        val scope2 = Scope("scope2", sortedSetOf(PackageReference(id)))

        val scopeExclude1 = ScopeExclude("scope1", ScopeExcludeReason.PROVIDED_BY, "")
        val scopeExclude2 = ScopeExclude("scope2", ScopeExcludeReason.PROVIDED_BY, "")

        val projectExcludeWithScopes1 = ProjectExclude("path1", scopes = listOf(scopeExclude1))
        val projectExcludeWithScopes2 = ProjectExclude("path2", scopes = listOf(scopeExclude2))

        "findPathExcludes" should {
            "find the correct path excludes for a path" {
                val excludes = Excludes(paths = listOf(pathExclude1, pathExclude2, pathExclude3, pathExclude4))

                excludes.findPathExcludes("") should beEmpty()
                excludes.findPathExcludes("path1") should containExactly(pathExclude1)
                excludes.findPathExcludes("path2") should containExactly(pathExclude2)
                excludes.findPathExcludes("test.ext") should containExactly(pathExclude3)
                excludes.findPathExcludes("directory/test.ext") should containExactly(pathExclude3)
                excludes.findPathExcludes("directory/file.ext") should containExactly(pathExclude3, pathExclude4)
            }

            "find the correct path excludes for a project" {
                val excludes = Excludes(paths = listOf(pathExclude1, pathExclude2, pathExclude3, pathExclude4))

                setProjects(project1, project2, project3)

                excludes.findPathExcludes(project1, ortResult) should containExactly(pathExclude1)
                excludes.findPathExcludes(project2, ortResult) should containExactly(pathExclude2)
                excludes.findPathExcludes(project3, ortResult) should beEmpty()
            }
        }

        "findProjectExcludes" should {
            "return empty list if there is no matching project exclude" {
                val excludes = Excludes(projects = listOf(projectExclude1, projectExclude3))

                excludes.findProjectExcludes(project2, OrtResult.EMPTY) should beEmpty()
            }

            "find the correct project excludes" {
                val excludes = Excludes(projects = listOf(projectExclude1, projectExclude2, projectExclude4))

                excludes.findProjectExcludes(project2, OrtResult.EMPTY) should
                        containExactlyInAnyOrder(projectExclude2, projectExclude4)
            }
        }

        "findScopeExcludes" should {
            "return an empty list if there are no matching scope excludes" {
                val excludes = Excludes(
                    projects = listOf(projectExcludeWithScopes2),
                    scopes = listOf(scopeExclude2)
                )

                excludes.findScopeExcludes(scope1, project1, OrtResult.EMPTY) should beEmpty()
            }

            "find the correct global scope excludes" {
                val excludes = Excludes(
                    scopes = listOf(scopeExclude1, scopeExclude2)
                )

                val scopeExcludes = excludes.findScopeExcludes(scope1, project1, OrtResult.EMPTY)

                scopeExcludes should haveSize(1)
                scopeExcludes should contain(scopeExclude1)
            }

            "find the correct project specific scope excludes" {
                val excludes = Excludes(
                    projects = listOf(projectExcludeWithScopes1, projectExcludeWithScopes2)
                )

                val scopeExcludes = excludes.findScopeExcludes(scope1, project1, OrtResult.EMPTY)

                scopeExcludes should haveSize(1)
                scopeExcludes should contain(scopeExclude1)
            }
        }

        "isPackageExcluded" should {
            "return true if the package does not appear in the analyzer result" {
                ortResult.isPackageExcluded(id) shouldBe true
            }

            "return true if all occurrences of the package are excluded" {
                setExcludes(
                    Excludes(
                        projects = listOf(projectExclude1),
                        scopes = listOf(scopeExclude2)
                    )
                )

                setProjects(
                    project1.copy(scopes = sortedSetOf(scope1)),
                    project2.copy(scopes = sortedSetOf(scope2))
                )

                ortResult.isPackageExcluded(id) shouldBe true
            }

            "return false if not all occurrences of the package are excluded" {
                setExcludes(
                    Excludes(
                        projects = listOf(projectExclude1),
                        scopes = listOf(scopeExclude2)
                    )
                )

                setProjects(
                    project1.copy(scopes = sortedSetOf(scope1)),
                    project2.copy(scopes = sortedSetOf(scope1, scope2))
                )

                ortResult.isPackageExcluded(id) shouldBe false
            }

            "return false if no occurrences of the package are excluded" {
                setProjects(
                    project1.copy(scopes = sortedSetOf(scope1)),
                    project2.copy(scopes = sortedSetOf(scope2))
                )

                ortResult.isPackageExcluded(id) shouldBe false
            }
        }

        "isPathExcluded" should {
            "return true if any path exclude matches a file" {
                val excludes = Excludes(paths = listOf(pathExclude1, pathExclude2))

                excludes.isPathExcluded("path1") shouldBe true
                excludes.isPathExcluded("path2") shouldBe true
            }

            "return false if no path exclude matches a file" {
                val excludes = Excludes(paths = listOf(pathExclude1, pathExclude2))

                excludes.isPathExcluded("") shouldBe false
                excludes.isPathExcluded("path1/file") shouldBe false
                excludes.isPathExcluded("path3") shouldBe false
            }

            "return false if no path exclude is defined" {
                val excludes = Excludes()

                excludes.isPathExcluded("path") shouldBe false
            }
        }

        "isProjectExcluded" should {
            "return true if the definition file path is matched by a project exclude without scope excludes" {
                setExcludes(Excludes(projects = listOf(projectExclude1)))
                setProjects(project1)

                ortResult.isProjectExcluded(project1) shouldBe true
            }

            "return false if the definition file path is matched by a project exclude with scope excludes" {
                setExcludes(Excludes(projects = listOf(projectExcludeWithScopes1)))
                setProjects(project1)

                ortResult.isProjectExcluded(project1) shouldBe false
            }

            "return true if the definition file path is matched by a path exclude" {
                setExcludes(Excludes(paths = listOf(pathExclude1)))
                setProjects(project1)

                ortResult.isProjectExcluded(project1) shouldBe true
            }


            "return false if nothing is excluded" {
                setProjects(project1)

                ortResult.isProjectExcluded(project1) shouldBe false
            }
        }

        "isScopeExcluded" should {
            "return true if the scope is excluded in the project exclude" {
                val excludes = Excludes(projects = listOf(projectExcludeWithScopes1))

                excludes.isScopeExcluded(scope1, project1, OrtResult.EMPTY) shouldBe true
            }

            "return true if the scope is excluded globally" {
                val excludes = Excludes(scopes = listOf(scopeExclude1))

                excludes.isScopeExcluded(scope1, project1, OrtResult.EMPTY) shouldBe true
            }

            "return true if the scope is excluded using a regex" {
                val excludes = Excludes(scopes = listOf(scopeExclude1.copy(name = Regex("sc.*"))))

                excludes.isScopeExcluded(scope1, project1, OrtResult.EMPTY) shouldBe true
            }

            "return false if the scope is not excluded" {
                val excludes = Excludes()

                excludes.isScopeExcluded(scope1, project1, OrtResult.EMPTY) shouldBe false
            }
        }

        "projectExcludesById" should {
            "return null values for projects without a project exclude" {
                val excludes = Excludes()

                val excludesById = excludes.projectExcludesById(setOf(project1, project2, project3), OrtResult.EMPTY)

                excludesById.keys should haveSize(3)
                excludesById.keys should containAll(projectId1, projectId2, projectId3)
                excludesById.values should containOnlyNulls()
            }

            "return the correct mapping of ids to project excludes" {
                val excludes = Excludes(
                    projects = listOf(projectExclude1, projectExclude2, projectExclude3)
                )

                val excludesById = excludes.projectExcludesById(setOf(project1, project2, project3), OrtResult.EMPTY)

                excludesById.keys should haveSize(3)
                excludesById[projectId1] shouldBe projectExclude1
                excludesById[projectId2] shouldBe projectExclude2
                excludesById[projectId3] shouldBe projectExclude3
            }

            "only return mappings for requested projects" {
                val excludes = Excludes(
                    projects = listOf(projectExclude1, projectExclude2, projectExclude3)
                )

                val excludesById = excludes.projectExcludesById(setOf(project1, project2), OrtResult.EMPTY)

                excludesById.keys should haveSize(2)
                excludesById[projectId1] shouldBe projectExclude1
                excludesById[projectId2] shouldBe projectExclude2
            }
        }

        "scopeExcludesByName" should {
            "return empty lists for scopes without a scope exclude" {
                val excludes = Excludes()

                val excludesByName = excludes.scopeExcludesByName(project1, listOf(scope1, scope2), OrtResult.EMPTY)

                excludesByName.keys should haveSize(2)
                excludesByName.keys should containAll(scope1.name, scope2.name)
                excludesByName.getValue(scope1.name) should beEmpty()
                excludesByName.getValue(scope2.name) should beEmpty()
            }

            "return the correct mapping of scope names to scope excludes" {
                val excludes = Excludes(
                    projects = listOf(projectExcludeWithScopes1, projectExcludeWithScopes2, projectExclude3),
                    scopes = listOf(scopeExclude2)
                )

                val excludesByName = excludes.scopeExcludesByName(project1, setOf(scope1, scope2), OrtResult.EMPTY)

                excludesByName.keys should haveSize(2)
                excludesByName.keys should containAll(scope1.name, scope2.name)
                excludesByName.getValue(scope1.name).let {
                    it should haveSize(1)
                    it should contain(scopeExclude1)
                }
                excludesByName.getValue(scope2.name).let {
                    it should haveSize(1)
                    it should contain(scopeExclude2)
                }
            }

            "only return mappings for requested scopes" {
                val excludes = Excludes(
                    projects = listOf(projectExcludeWithScopes1, projectExcludeWithScopes2)
                )

                val excludesByName = excludes.scopeExcludesByName(project1, listOf(scope1), OrtResult.EMPTY)

                excludesByName.keys should haveSize(1)
                excludesByName.keys should contain(scope1.name)
                excludesByName.getValue(scope1.name).let {
                    it should haveSize(1)
                    it should contain(scopeExclude1)
                }
            }
        }
    }
}
