/*
 * Copyright (C) 2017-2018 HERE Europe B.V.
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

import com.beust.jcommander.JCommander
import com.beust.jcommander.Parameter
import com.beust.jcommander.Parameters
import com.here.ort.CommandWithHelp
import com.here.ort.model.config.RepositoryConfiguration
import com.here.ort.model.mapper
import com.here.ort.model.readValue
import com.here.ort.utils.PARAMETER_ORDER_MANDATORY
import java.io.File

@Parameters(commandNames = ["format-config"], commandDescription = "Format config.")
object FormatConfigCommand : CommandWithHelp() {
    @Parameter(description = "The target OrtResult file.",
        names = ["--target-file", "-t"],
        required = true,
        order = PARAMETER_ORDER_MANDATORY)
    private lateinit var targetFile: File

    override fun runCommand(jc: JCommander): Int {
        val config: RepositoryConfiguration = targetFile.readValue()
        targetFile.mapper().writerWithDefaultPrettyPrinter().writeValue(targetFile, config)
        return 0
    }
}
