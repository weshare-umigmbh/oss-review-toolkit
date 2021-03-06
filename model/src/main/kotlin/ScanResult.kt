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

package com.here.ort.model

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode

import com.here.ort.spdx.LICENSE_FILE_MATCHERS
import com.here.ort.spdx.LICENSE_FILE_NAMES

import java.nio.file.Paths
import java.util.SortedSet

/**
 * The result of a single scan of a single package.
 */
data class ScanResult(
    /**
     * Provenance information about the scanned source code.
     */
    val provenance: Provenance,

    /**
     * Details about the used scanner.
     */
    val scanner: ScannerDetails,

    /**
     * A summary of the scan results.
     */
    val summary: ScanSummary,

    /**
     * The raw output of the scanner. Can be null if the raw result shall not be included. If the raw result is
     * empty it must not be null but [EMPTY_JSON_NODE].
     */
    @JsonAlias("rawResult")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val rawResult: JsonNode? = null
) {
    /**
     * Filter all detected licenses and copyrights from the [summary] which are underneath [path], and set the [path]
     * for [provenance]. Findings in files which are listed in [LICENSE_FILE_NAMES] are also kept.
     */
    fun filterPath(path: String): ScanResult {
        fun SortedSet<TextLocation>.filterPath() =
            filterTo(sortedSetOf()) { location ->
                location.path.startsWith("$path/") || LICENSE_FILE_MATCHERS.any { matcher ->
                    matcher.matches(Paths.get(location.path))
                }
            }

        val newProvenance = provenance.copy(
            vcsInfo = provenance.vcsInfo?.copy(path = path),
            originalVcsInfo = provenance.originalVcsInfo?.copy(path = path)
        )

        val findings = summary.licenseFindings.mapNotNull { finding ->
            val locations = finding.locations.filterPath()

            if (locations.isNotEmpty()) {
                val copyrights = finding.copyrights.mapNotNull { copyright ->
                    val copyrightLocations = copyright.locations.filterPath()

                    if (copyrightLocations.isNotEmpty()) {
                        CopyrightFinding(copyright.statement, copyrightLocations)
                    } else {
                        null
                    }
                }.toSortedSet()

                LicenseFinding(finding.license, locations, copyrights)
            } else {
                null
            }
        }.toSortedSet()

        val fileCount = findings.flatMap { finding -> finding.locations.map { it.path } }.size
        val summary = summary.copy(fileCount = fileCount, licenseFindings = findings)

        return ScanResult(newProvenance, scanner, summary)
    }
}
