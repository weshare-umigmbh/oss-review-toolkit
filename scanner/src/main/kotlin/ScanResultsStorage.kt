/*
 * Copyright (C) 2017-2019 HERE Europe B.V.
 * Copyright (C) 2019 Bosch Software Innovations GmbH
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

package com.here.ort.scanner

import ch.frankel.slf4k.*

import com.here.ort.model.AccessStatistics
import com.here.ort.model.Identifier
import com.here.ort.model.Package
import com.here.ort.model.ScanResult
import com.here.ort.model.ScanResultContainer
import com.here.ort.model.ScannerDetails
import com.here.ort.model.config.ArtifactoryStorageConfiguration
import com.here.ort.scanner.storages.ArtifactoryStorage
import com.here.ort.scanner.storages.NoStorage
import com.here.ort.utils.log

import java.util.SortedSet

/**
 * The interface that storage backends for scan results need to implement.
 */
interface ScanResultsStorage {
    /**
     * A companion object that wraps the real storage to use with access statistics.
     */
    companion object : ScanResultsStorage {
        /**
         * The scan result storage in use. Needs to be set via the corresponding configure function.
         */
        var storage: ScanResultsStorage = NoStorage()
            private set

        /**
         * The access statistics for the scan result storage wrapper.
         */
        val stats = AccessStatistics()

        /**
         * Configure an [ArtifactoryStorage] as the current storage backend.
         */
        fun configure(config: ArtifactoryStorageConfiguration) {
            require(config.url.isNotBlank()) {
                "URL for Artifactory storage is missing."
            }

            require(config.repository.isNotBlank()) {
                "Repository for Artifactory storage is missing."
            }

            require(config.apiToken.isNotBlank()) {
                "API token for Artifactory storage is missing."
            }

            storage = ArtifactoryStorage(config.url, config.repository, config.apiToken)

            log.info { "Using Artifactory storage at ${config.url}." }
        }

        override fun read(id: Identifier) =
            storage.read(id).also {
                stats.numReads.incrementAndGet()
                if (it.results.isNotEmpty()) {
                    stats.numHits.incrementAndGet()
                }
            }

        override fun read(pkg: Package, scannerDetails: ScannerDetails) =
            storage.read(pkg, scannerDetails).also {
                stats.numReads.incrementAndGet()
                if (it.results.isNotEmpty()) {
                    stats.numHits.incrementAndGet()
                }
            }

        override fun add(id: Identifier, scanResult: ScanResult) = storage.add(id, scanResult)

        override fun listPackages() = storage.listPackages()
    }

    /**
     * Read all [ScanResult]s for this [id] from the storage.
     *
     * @param id The [Identifier] of the scanned [Package].
     *
     * @return The [ScanResultContainer] for this [id].
     */
    fun read(id: Identifier): ScanResultContainer

    /**
     * Read the [ScanResult]s matching the [id][Package.id] of [pkg] and the [scannerDetails] from the storage.
     * [ScannerDetails.isCompatible] is used to check if the results are compatible with the provided [scannerDetails].
     * Also [Package.sourceArtifact], [Package.vcs], and [Package.vcsProcessed] are used to check if the scan result
     * matches the expected source code location. This is important to find the correct results when different revisions
     * of a package using the same version name are used (e.g. multiple scans of 1.0-SNAPSHOT during development).
     *
     * @param pkg The [Package] to look up results for.
     * @param scannerDetails Details about the scanner that was used to scan the [Package].
     *
     * @return The [ScanResultContainer] matching the [id][Package.id] of [pkg] and the [scannerDetails].
     */
    fun read(pkg: Package, scannerDetails: ScannerDetails): ScanResultContainer

    /**
     * Add a [ScanResult] to the [ScanResultContainer] for this [id] and write it to the storage.
     *
     * @param id The [Identifier] of the scanned [Package].
     * @param scanResult The [ScanResult]. The [ScanResult.rawResult] must not be null.
     *
     * @return If the [ScanResult] could be written to the storage.
     */
    fun add(id: Identifier, scanResult: ScanResult): Boolean

    /**
     * List the [Identifier]s of all stored packages.
     */
    fun listPackages(): SortedSet<Identifier>
}
