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

package com.here.ort.commands

import ch.frankel.slf4k.*

import com.beust.jcommander.IStringConverter
import com.beust.jcommander.JCommander
import com.beust.jcommander.Parameter
import com.beust.jcommander.ParameterException
import com.beust.jcommander.Parameters

import com.here.ort.CommandWithHelp
import com.here.ort.model.OutputFormat
import com.here.ort.model.config.ScannerConfiguration
import com.here.ort.model.mapper
import com.here.ort.model.readValue
import com.here.ort.scanner.LocalScanner
import com.here.ort.scanner.ScanResultsStorage
import com.here.ort.scanner.Scanner
import com.here.ort.scanner.ScannerFactory
import com.here.ort.scanner.scanners.ScanCode
import com.here.ort.utils.PARAMETER_ORDER_MANDATORY
import com.here.ort.utils.PARAMETER_ORDER_OPTIONAL
import com.here.ort.utils.expandTilde
import com.here.ort.utils.log

import java.io.File

@Parameters(commandNames = ["scan"], commandDescription = "Run existing copyright / license scanners.")
object ScannerCommand : CommandWithHelp() {
    private class ScannerConverter : IStringConverter<ScannerFactory> {
        override fun convert(scannerName: String): ScannerFactory {
            // TODO: Consider allowing to enable multiple scanners (and potentially running them in parallel).
            return Scanner.ALL.find { it.scannerName.equals(scannerName, true) }
                ?: throw ParameterException("Scanner '$scannerName' is not one of ${Scanner.ALL}.")
        }
    }

    @Parameter(
        description = "An ORT result file with an analyzer result to use. Source code will be downloaded " +
                "automatically if needed. This parameter and '--input-path' are mutually exclusive.",
        names = ["--ort-file", "-a"],
        order = PARAMETER_ORDER_OPTIONAL
    )
    private var ortFile: File? = null

    @Parameter(
        description = "An input directory or file to scan. This parameter and '--ort-file' are mutually " +
                "exclusive.",
        names = ["--input-path", "-i"],
        order = PARAMETER_ORDER_OPTIONAL
    )
    private var inputPath: File? = null

    @Parameter(
        description = "The list of scopes whose packages shall be scanned. Works only with the '--ort-file' " +
                "parameter. If empty, all scopes are scanned.",
        names = ["--scopes"],
        order = PARAMETER_ORDER_OPTIONAL
    )
    private var scopesToScan = listOf<String>()

    @Parameter(
        description = "The directory to write the scan results as ORT result file(s) to, in the specified " +
                "output format(s).",
        names = ["--output-dir", "-o"],
        required = true,
        order = PARAMETER_ORDER_MANDATORY
    )
    @Suppress("LateinitUsage")
    private lateinit var outputDir: File

    @Parameter(
        description = "The output directory for downloaded source code. Defaults to <output-dir>/downloads.",
        names = ["--download-dir"],
        order = PARAMETER_ORDER_OPTIONAL
    )
    private var downloadDir: File? = null

    @Parameter(
        description = "The scanner to use.",
        names = ["--scanner", "-s"],
        converter = ScannerConverter::class,
        order = PARAMETER_ORDER_OPTIONAL
    )
    private var scannerFactory: ScannerFactory = ScanCode.Factory()

    @Parameter(
        description = "The path to a configuration file.",
        names = ["--config", "-c"],
        order = PARAMETER_ORDER_OPTIONAL
    )
    private var configFile: File? = null

    @Parameter(
        description = "The list of output formats to be used for the ORT result file(s).",
        names = ["--output-formats", "-f"],
        order = PARAMETER_ORDER_OPTIONAL
    )
    private var outputFormats = listOf(OutputFormat.YAML)

    override fun runCommand(jc: JCommander): Int {
        require((ortFile == null) != (inputPath == null)) {
            "Either '--ort-file' or '--input-path' must be specified."
        }

        val absoluteOutputDir = outputDir.expandTilde().normalize()
        val absoluteNativeOutputDir = absoluteOutputDir.resolve("native-scan-results")

        val outputFiles = outputFormats.distinct().map { format ->
            File(absoluteOutputDir, "scan-result.${format.fileExtension}")
        }

        val existingOutputFiles = outputFiles.filter { it.exists() }
        if (existingOutputFiles.isNotEmpty()) {
            log.error { "None of the output files $existingOutputFiles must exist yet." }
            return 2
        }

        if (absoluteNativeOutputDir.exists() && absoluteNativeOutputDir.list().isNotEmpty()) {
            log.error { "The directory '$absoluteNativeOutputDir' must not contain any files yet." }
            return 2
        }

        val absoluteDownloadDir = downloadDir?.expandTilde()
        require(absoluteDownloadDir?.exists() != true) {
            "The download directory '$absoluteDownloadDir' must not exist yet."
        }

        val config = configFile?.expandTilde()?.let {
            require(it.isFile) {
                "The provided configuration file '$it' is not actually a file."
            }

            it.readValue<ScannerConfiguration>()
        } ?: ScannerConfiguration()

        ScanResultsStorage.configure(config)
//        config.artifactoryStorage?.let {
//            ScanResultsStorage.configure(it)
//        }

        val scanner = scannerFactory.create(config)

        println("Using scanner '${scanner.scannerName}'.")

        val ortResult = ortFile?.expandTilde()?.let {
            scanner.scanOrtResult(
                it, absoluteNativeOutputDir,
                absoluteDownloadDir ?: absoluteOutputDir.resolve("downloads"), scopesToScan.toSet()
            )
        } ?: run {
            require(scanner is LocalScanner) {
                "To scan local files the chosen scanner must be a local scanner."
            }

            val absoluteInputPath = inputPath!!.expandTilde().normalize()
            scanner.scanInputPath(absoluteInputPath, absoluteNativeOutputDir)
        }

        outputFiles.forEach { file ->
            println("Writing scan result to '$file'.")
            file.mapper().writerWithDefaultPrettyPrinter().writeValue(file, ortResult)
        }

        return 0
    }
}
