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

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.module.kotlin.treeToValue

import com.here.ort.utils.SortedSetComparator
import com.here.ort.utils.constructTreeSetType

import java.util.SortedSet
import java.util.TreeSet

@JsonDeserialize(using = CopyrightFindingDeserializer::class)
data class CopyrightFinding(
    val statement: String,
    val locations: SortedSet<TextLocation>
) : Comparable<CopyrightFinding> {
    companion object {
        val SORTED_SET_COMPARATOR = SortedSetComparator<CopyrightFinding>()
        val TREE_SET_TYPE by lazy { jsonMapper.typeFactory.constructTreeSetType(CopyrightFinding::class.java) }
    }

    override fun compareTo(other: CopyrightFinding) =
        compareValuesBy(
            this,
            other,
            compareBy(CopyrightFinding::statement)
                .thenBy(TextLocation.SORTED_SET_COMPARATOR, CopyrightFinding::locations)
        ) { it }
}

class CopyrightFindingDeserializer : StdDeserializer<CopyrightFinding>(CopyrightFinding::class.java) {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): CopyrightFinding {
        val node = p.codec.readTree<JsonNode>(p)
        return when {
            node.isTextual -> CopyrightFinding(node.textValue(), sortedSetOf())
            else -> {
                val statement = jsonMapper.treeToValue<String>(node["statement"])

                val locations = jsonMapper.readValue<TreeSet<TextLocation>>(
                    jsonMapper.treeAsTokens(node["locations"]),
                    TextLocation.TREE_SET_TYPE
                )

                CopyrightFinding(statement, locations)
            }
        }
    }
}
