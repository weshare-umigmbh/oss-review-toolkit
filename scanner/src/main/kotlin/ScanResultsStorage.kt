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

package com.here.ort.scanner

import ch.frankel.slf4k.*

import com.here.ort.model.AccessStatistics
import com.here.ort.model.Package
import com.here.ort.model.ScanResult
import com.here.ort.model.ScannerDetails
import com.here.ort.model.config.ArtifactoryStorageConfiguration
import com.here.ort.utils.log

interface ScanResultsStorage {
    companion object : ScanResultsStorage {
        var storage = object : ScanResultsStorage {
            override fun read(pkg: Package) = emptyList<ScanResult>()
            override fun read(pkg: Package, scannerDetails: ScannerDetails) = emptyList<ScanResult>()
            override fun add(scanResult: ScanResult) = false
        }
            private set

        val stats = AccessStatistics()

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

        override fun read(pkg: Package) =
            storage.read(pkg).also {
                stats.numReads.incrementAndGet()
                if (it.isNotEmpty()) {
                    stats.numHits.incrementAndGet()
                }
            }

        override fun read(pkg: Package, scannerDetails: ScannerDetails) =
            storage.read(pkg, scannerDetails).also {
                stats.numReads.incrementAndGet()
                if (it.isNotEmpty()) {
                    stats.numHits.incrementAndGet()
                }
            }

        override fun add(scanResult: ScanResult) = storage.add(scanResult)
    }

    /**
     * Read all [ScanResult]s for this [package][pkg] from the storage.
     */
    fun read(pkg: Package): List<ScanResult>

    /**
     * Read all [ScanResult]s for this [package][pkg] with matching [scannerDetails] from the storage.
     */
    fun read(pkg: Package, scannerDetails: ScannerDetails): List<ScanResult>

    /**
     * Add a [scanResult] to the storage.
     */
    fun add(scanResult: ScanResult): Boolean
}
