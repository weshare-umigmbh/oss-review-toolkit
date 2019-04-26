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

import kotlin.reflect.KType
import kotlin.reflect.full.*

sealed class VcsHost(val cloneUrl: String) {
    companion object {
        val ALL = VcsHost::class.sealedSubclasses.sortedByDescending {
            val priority = it.companionObject?.memberProperties?.find {
                it.name == "priority" && it.returnType.isSubtypeOf(Int::class.createType())
            }

            requireNotNull(priority) {
                "All sealed subclasses of ${this} have to define a comanion object with an integer 'priority' property."
            }

            priority.getter.call(it.companionObjectInstance) as Int
        }
        fun createFromCloneUrl(url: String): VcsHost {
            var cloneUrl = url.trimEnd('/')

            if (cloneUrl.startsWith(":pserver:") || cloneUrl.startsWith(":ext:")) {
                // Do not touch CVS URLs for now.
                return Generic(cloneUrl)
            }

            return Generic(cloneUrl)
        }

        fun create(url: String) {
            //VcsHost::class.sealedSubclasses.sortedBy { it.companionObjectInstance }
        }
    }

    //abstract val browseUrl: String?

    //open val priority: Int = 0

    //abstract fun isCloneUrl(): Boolean

    //abstract fun isBrowseUrl(): Boolean

    class Generic(cloneUrl: String) : VcsHost(cloneUrl) {
        //override val browseUrl = null
    }

    /*class Bitbucket : VcsHost() {

    }

    class GitHub : VcsHost() {
        override val browseUrl = "https://$host/$org/$project/tree/$revision/buildSrc"
    }

    class GitLab : VcsHost() {

    }*/
}
