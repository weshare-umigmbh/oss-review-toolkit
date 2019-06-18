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

import ch.frankel.slf4k.info
import com.fasterxml.jackson.module.kotlin.readValue

import com.here.ort.model.Identifier
import com.here.ort.model.Package
import com.here.ort.model.ScanResult
import com.here.ort.model.ScanResultContainer
import com.here.ort.model.ScannerDetails
import com.here.ort.model.jsonMapper
import com.here.ort.utils.log

import java.io.IOException
import java.sql.Connection
import java.util.SortedSet

class PostgresStorage(val connection: Connection, val schema: String) : ScanResultsStorage {
    val table = "scan_results" // TODO: make configurable

    /**
     * Initialize the database.
     */
    fun init() {
        if (!tableExists()) {
            println("Creating table...")
            if (!createTable()) {
                throw IOException("Could not create table.")
            }
            println("Created table.")
        }
    }

    private fun tableExists(): Boolean {
        val statement = connection.createStatement()
        val resultSet = statement.executeQuery("SELECT to_regclass('$schema.$table')")
        resultSet.next()
        return resultSet.getString(1) == table
    }

    private fun createTable(): Boolean {
        val query = """
            CREATE SEQUENCE $schema.${table}_id_seq;

            CREATE TABLE $schema.$table
            (
                id integer NOT NULL DEFAULT nextval('${table}_id_seq'::regclass),
                identifier text COLLATE pg_catalog."default" NOT NULL,
                scan_result jsonb NOT NULL,
                CONSTRAINT ${table}_pkey PRIMARY KEY (id)
            )
            WITH (
                OIDS = FALSE
            )
            TABLESPACE pg_default;

            CREATE INDEX identifier
                ON $schema.$table USING btree
                (identifier COLLATE pg_catalog."default")
                TABLESPACE pg_default;
        """.trimIndent()

        val statement = connection.createStatement()
        statement.execute(query)

        return tableExists()
    }

    override fun read(id: Identifier): ScanResultContainer {
        log.info { "Reading scan results for ${id.toCoordinates()} from storage." }

        val query = "SELECT scan_result FROM $schema.$table WHERE identifier = ?"

        val statement = connection.prepareStatement(query)
        statement.setString(1, id.toCoordinates())

        val resultSet = statement.executeQuery()

        val results = mutableListOf<ScanResult>()

        while (resultSet.next()) {
            val scanResult = jsonMapper.readValue<ScanResult>(resultSet.getString(1))
            results.add(scanResult)
        }

        log.info { "Found ${results.size} scan results for ${id.toCoordinates()}." }

        return ScanResultContainer(id, results)
    }

    override fun read(pkg: Package, scannerDetails: ScannerDetails): ScanResultContainer {
        val scanResults = read(pkg.id).results.toMutableList()

        // TODO: This filtering should be done in the database query for better performance.
        scanResults.retainAll { it.provenance.matches(pkg) }
        scanResults.retainAll { scannerDetails.isCompatible(it.scanner) }

        log.info { "Found ${scanResults.size} matching scan results for ${pkg.id.toCoordinates()}." }

        return ScanResultContainer(pkg.id, scanResults)
    }

    override fun add(id: Identifier, scanResult: ScanResult): Boolean {
        // Do not store empty scan results. It is likely that something went wrong when they were created, and if not,
        // it is cheap to re-create them.
        if (scanResult.summary.fileCount == 0) {
            log.info { "Not storing scan result for '${id.toCoordinates()}' because no files were scanned." }

            return false
        }

        // Do not store scan results without raw result. The raw result can be set to null for other usages, but in the
        // storage it must never be null.
        if (scanResult.rawResult == null) {
            log.info { "Not storing scan result for '${id.toCoordinates()}' because the raw result is null." }

            return false
        }

        // Do not store scan results without provenance information, because they cannot be assigned to the revision of
        // the package source code later.
        if (scanResult.provenance.sourceArtifact == null && scanResult.provenance.vcsInfo == null) {
            log.info {
                "Not storing scan result for '${id.toCoordinates()}' because no provenance information is available."
            }

            return false
        }

        log.info { "Storing scan result for ${id.toCoordinates()} in storage." }

        // TODO: Check if there is already a matching entry for this provenance and scanner details.
        val query = "INSERT INTO $schema.$table (identifier, scan_result) VALUES (?, to_json(?::json)::jsonb)"

        val statement = connection.prepareStatement(query)
        statement.setString(1, id.toCoordinates())
        statement.setString(2, jsonMapper.writeValueAsString(scanResult))
        statement.execute()

        return true
    }

    override fun listPackages(): SortedSet<Identifier> {
        val query = "SELECT identifier FROM $schema.$table"

        val statement = connection.createStatement()
        val resultSet = statement.executeQuery(query)

        val result = sortedSetOf<Identifier>()

        while (resultSet.next()) {
            val id = Identifier(resultSet.getString(1))
            result.add(id)
        }

        return result
    }
}
