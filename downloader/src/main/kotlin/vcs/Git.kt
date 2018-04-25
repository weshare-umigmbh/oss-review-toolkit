/*
 * Copyright (c) 2017-2018 HERE Europe B.V.
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

package com.here.ort.downloader.vcs

import ch.frankel.slf4k.*

import com.here.ort.downloader.DownloadException
import com.here.ort.downloader.VersionControlSystem
import com.here.ort.model.Package
import com.here.ort.model.VcsInfo
import com.here.ort.utils.OS
import com.here.ort.utils.log
import com.here.ort.utils.ProcessCapture
import com.here.ort.utils.getCommandVersion
import com.here.ort.utils.safeMkdirs
import com.here.ort.utils.showStackTrace

import java.io.File
import java.io.IOException
import java.util.regex.Pattern

abstract class GitBase : VersionControlSystem() {
    override val commandName = "git"
    override val movingRevisionNames = listOf("HEAD", "master")

    override fun getVersion(): String {
        val versionRegex = Pattern.compile("[Gg]it [Vv]ersion (?<version>[\\d.a-z-]+)(\\s.+)?")

        return getCommandVersion("git") {
            versionRegex.matcher(it.lineSequence().first()).let {
                if (it.matches()) {
                    it.group("version")
                } else {
                    ""
                }
            }
        }
    }

    open inner class GitWorkingTree(workingDir: File) : WorkingTree(workingDir) {
        override fun isValid(): Boolean {
            if (!workingDir.isDirectory) {
                return false
            }

            // Do not use runGitCommand() here as we do not require the command to succeed.
            val isInsideWorkTree = ProcessCapture(workingDir, "git", "rev-parse", "--is-inside-work-tree")
            return isInsideWorkTree.isSuccess() && isInsideWorkTree.stdout().trimEnd().toBoolean()
        }

        override fun isShallow(): Boolean {
            val dotGitDir = run(workingDir, "rev-parse", "--absolute-git-dir").stdout().trimEnd()
            return File(dotGitDir, "shallow").isFile
        }

        override fun getRemoteUrl() =
                run(workingDir, "remote", "get-url", "origin").stdout().trimEnd()

        override fun getRevision() =
                run(workingDir, "rev-parse", "HEAD").stdout().trimEnd()

        override fun getRootPath() =
                run(workingDir, "rev-parse", "--show-toplevel").stdout().trimEnd('\n', '/')

        override fun listRemoteTags(): List<String> {
            val tags = run(workingDir, "ls-remote", "--refs", "origin", "refs/tags/*").stdout().trimEnd()
            return tags.lines().map {
                it.split('\t').last().removePrefix("refs/tags/")
            }
        }
    }

    override fun getWorkingTree(vcsDirectory: File): WorkingTree = GitWorkingTree(vcsDirectory)
}

object Git : GitBase() {
    // TODO: Make this configurable.
    private const val HISTORY_DEPTH = 50

    override val aliases = listOf("git")

    override fun isApplicableUrl(vcsUrl: String) = ProcessCapture("git", "ls-remote", vcsUrl).isSuccess()

    override fun initWorkingTree(targetDir: File, vcs: VcsInfo): WorkingTree {
        // Do not use "git clone" to have more control over what is being fetched.
        run(targetDir, "init")
        run(targetDir, "remote", "add", "origin", vcs.url)

        if (OS.isWindows) {
            run(targetDir, "config", "core.longpaths", "true")
        }

        if (vcs.path.isNotBlank()) {
            log.info { "Configuring Git to do sparse checkout of path '${vcs.path}'." }
            run(targetDir, "config", "core.sparseCheckout", "true")
            val gitInfoDir = File(targetDir, ".git/info").apply { safeMkdirs() }
            File(gitInfoDir, "sparse-checkout").writeText(vcs.path + "\nLICENSE*\nLICENCE*")
        }

        return getWorkingTree(targetDir)
    }

    override fun updateWorkingTree(workingTree: WorkingTree, revision: String, recursive: Boolean) {
        // To safe network bandwidth, first try to only fetch exactly the revision we want. Skip this optimization for
        // SSH URLs to GitHub as GitHub does not have "allowReachableSHA1InWant" (nor "allowAnySHA1InWant") enabled and
        // the SSH transport invokes "git-upload-pack" without the "--stateless-rpc" option, causing different
        // reachability rules to kick in. Over HTTPS, the ref advertisement and the want/have negotiation happen over
        // two separate connections so the later actually does a reachability check instead of relying on the advertised
        // refs.
        if (!workingTree.getRemoteUrl().startsWith("ssh://git@github.com/")) {
            try {
                log.info { "Trying to fetch only revision '$revision' with depth limited to $HISTORY_DEPTH." }
                run(workingTree.workingDir, "fetch", "--depth", HISTORY_DEPTH.toString(), "origin", revision)

                // The documentation for git-fetch states that "By default, any tag that points into the histories being
                // fetched is also fetched", but that is not true for shallow fetches of a tag; then the tag itself is
                // not fetched. So create it manually afterwards.
                if (revision in workingTree.listRemoteTags()) {
                    run(workingTree.workingDir, "tag", revision, "FETCH_HEAD")
                }

                run(workingTree.workingDir, "checkout", revision)
            } catch (e: IOException) {
                e.showStackTrace()

                log.warn {
                    "Could not fetch only revision '$revision': ${e.message}\n" +
                            "Falling back to fetching all refs."
                }
            }
        }

        // Fall back to fetching all refs with limited depth of history.
        try {
            log.info { "Trying to fetch all refs with depth limited to $HISTORY_DEPTH." }
            run(workingTree.workingDir, "fetch", "--depth", HISTORY_DEPTH.toString(), "--tags", "origin")
            run(workingTree.workingDir, "checkout", revision)
        } catch (e: IOException) {
            e.showStackTrace()

            log.warn {
                "Could not fetch with only a depth of $HISTORY_DEPTH: ${e.message}\n" +
                        "Falling back to fetching everything."
            }
        }

        // Fall back to fetching everything.
        log.info { "Trying to fetch everything including tags." }

        if (workingTree.isShallow()) {
            run(workingTree.workingDir, "fetch", "--unshallow", "--tags", "origin")
        } else {
            run(workingTree.workingDir, "fetch", "--tags", "origin")
        }

        run(workingTree.workingDir, "checkout", revision)
    }
}
