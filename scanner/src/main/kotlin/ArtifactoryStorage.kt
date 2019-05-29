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

import com.here.ort.model.Package
import com.here.ort.model.RemoteArtifact
import com.here.ort.model.ScanResult
import com.here.ort.model.ScannerDetails
import com.here.ort.model.VcsInfo
import com.here.ort.model.readValue
import com.here.ort.model.yamlMapper
import com.here.ort.utils.OkHttpClientHelper
import com.here.ort.utils.fileSystemEncode
import com.here.ort.utils.log
import com.here.ort.utils.showStackTrace

import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.TimeUnit

import okhttp3.CacheControl
import okhttp3.Request

import okio.Okio

class ArtifactoryStorage(
    /**
     * The URL of the Artifactory server, e.g. "https://example.com/artifactory".
     */
    private val url: String,

    /**
     * The name of the Artifactory repository to use for storing scan results.
     */
    private val repository: String,

    /**
     * An Artifactory API token with read/write access to [repository].
     */
    private val apiToken: String
) : ScanResultsStorage {
    override fun read(pkg: Package): List<ScanResult> {
        val results = mutableListOf<ScanResult>()

        if (pkg.sourceArtifact.url.isNotBlank()) {
            results += read(storagePath(pkg.sourceArtifact))
        }

        if (pkg.vcsProcessed.url.isNotBlank()) {
            results += read(storagePath(pkg.vcsProcessed))
        }

        return results
    }

    private fun read(path: String): List<ScanResult> {
        val request = Request.Builder()
            .header("X-JFrog-Art-Api", apiToken)
            .cacheControl(CacheControl.Builder().maxAge(0, TimeUnit.SECONDS).build())
            .get()
            .url("$url/$repository/$path")
            .build()

        val tempFile = createTempFile("ort", "scan-results.yml")

        try {
            OkHttpClientHelper.execute(HTTP_CACHE_PATH, request).use { response ->
                if (response.code() == HttpURLConnection.HTTP_OK) {
                    response.body()?.let { body ->
                        Okio.buffer(Okio.sink(tempFile)).use { it.writeAll(body.source()) }
                    }

                    if (response.cacheResponse() != null) {
                        log.info { "Retrieved $path from local storage." }
                    } else {
                        log.info { "Downloaded $path from Artifactory storage." }
                    }

                    return tempFile.readValue()
                } else {
                    log.info {
                        "Could not get $path from Artifactory storage: ${response.code()} - " +
                                response.message()
                    }
                }
            }
        } catch (e: IOException) {
            e.showStackTrace()

            log.warn { "Could not get $path from Artifactory storage: ${e.message}" }
        }

        return emptyList()
    }

    override fun read(pkg: Package, scannerDetails: ScannerDetails): List<ScanResult> {
        val scanResults = read(pkg).toMutableList()

        if (scanResults.isEmpty()) return scanResults

        // Only keep scan results whose provenance information matches the package information.
        scanResults.retainAll { it.provenance.matches(pkg) }
        if (scanResults.isEmpty()) {
            log.info {
                "No stored scan results found for ${pkg.id.toCoordinates()}. The following entries with non-matching " +
                        "provenance have been ignored: ${scanResults.map { it.provenance }}"
            }
            return scanResults
        }

        // Only keep scan results from compatible scanners.
        scanResults.retainAll { scannerDetails.isCompatible(it.scanner) }
        if (scanResults.isEmpty()) {
            log.info {
                "No stored scan results found for $scannerDetails. The following entries with incompatible scanners " +
                        "have been ignored: ${scanResults.map { it.scanner }}"
            }
            return scanResults
        }

        log.info {
            "Found ${scanResults.size} stored scan result(s) for ${pkg.id.toCoordinates()} that are compatible with " +
                    "$scannerDetails."
        }

        // TODO: Remove this code again once we migrated our scan result storage to contain the new "namespaced" license
        //  names for ScanCode.

        return patchScanCodeLicenseRefs(scanResults)
    }

    internal fun patchScanCodeLicenseRefs(scanResults: List<ScanResult>) =
        scanResults.map { result ->
            if (result.scanner.name == "ScanCode") {
                val findings = result.summary.licenseFindings.map { finding ->
                    if (finding.license.startsWith("LicenseRef-") &&
                        !finding.license.startsWith("LicenseRef-scancode-")
                    ) {
                        val suffix = finding.license.removePrefix("LicenseRef-")
                        val license = "LicenseRef-scancode-$suffix"
                        log.info { "Patched license name '${finding.license}' to '$license'." }
                        finding.copy(license = license)
                    } else {
                        finding
                    }
                }

                result.copy(summary = result.summary.copy(licenseFindings = findings.toSortedSet()))
            } else {
                result
            }
        }

    override fun add(scanResult: ScanResult): Boolean {
        // Do not store empty scan results. It is likely that something went wrong when they were created, and if not,
        // it is cheap to re-create them.
        if (scanResult.summary.fileCount == 0) {
            log.info {
                "Not storing scan result for '${scanResult.provenance.description}' because no files were scanned."
            }

            return false
        }

        // Do not store scan results without raw result. The raw result can be set to null for other usages, but in the
        // storage it must never be null.
        if (scanResult.rawResult == null) {
            log.info {
                "Not storing scan result for '${scanResult.provenance.description}' because the raw result is null."
            }

            return false
        }

        // Do not store scan results without provenance information, because they cannot be assigned to the revision of
        // the package source code later.
        if (scanResult.provenance.sourceArtifact == null && scanResult.provenance.originalVcsInfo == null) {
            log.info {
                "Not storing scan result for '${scanResult.provenance.description}' because no provenance " +
                        "information is available."
            }

            return false
        }

        val pkg = Package.EMPTY.copy(
            sourceArtifact = scanResult.provenance.sourceArtifact ?: RemoteArtifact.EMPTY,
            vcs = scanResult.provenance.originalVcsInfo ?: VcsInfo.EMPTY,
            vcsProcessed = scanResult.provenance.originalVcsInfo ?: VcsInfo.EMPTY
        )

        val scanResults = read(pkg) + scanResult

        val tempFile = createTempFile("ort", "scan-results.yml")
        yamlMapper.writeValue(tempFile, scanResults)

        val storagePath = if (scanResult.provenance.sourceArtifact != null) {
            storagePath(scanResult.provenance.sourceArtifact!!)
        } else {
            storagePath(scanResult.provenance.originalVcsInfo!!)
        }

        log.info {
            "Writing scan results for '${scanResult.provenance.description}' to Artifactory storage: $storagePath"
        }

        val request = Request.Builder()
            .header("X-JFrog-Art-Api", apiToken)
            .put(OkHttpClientHelper.createRequestBody(tempFile))
            .url("$url/$repository/$storagePath")
            .build()

        try {
            return OkHttpClientHelper.execute(HTTP_CACHE_PATH, request).use { response ->
                (response.code() == HttpURLConnection.HTTP_CREATED).also {
                    log.info {
                        if (it) {
                            "Uploaded $storagePath to Artifactory storage."
                        } else {
                            "Could not upload $storagePath to Artifactory storage: ${response.code()} - " +
                                    response.message()
                        }
                    }
                }
            }
        } catch (e: IOException) {
            e.showStackTrace()

            log.warn { "Could not upload $storagePath to Artifactory storage: ${e.message}" }

            return false
        }
    }

    private fun storagePath(sourceArtifact: RemoteArtifact) =
        "scan-results/source-artifacts/${sourceArtifact.url.fileSystemEncode()}/scan-results.yml"

    private fun storagePath(vcsInfo: VcsInfo) =
        "scan-results/repositories/${vcsInfo.url.fileSystemEncode()}/scan-results.yml"
}
