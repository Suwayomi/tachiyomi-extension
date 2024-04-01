/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package eu.kanade.tachiyomi.extension.all.tachidesk

import com.apollographql.apollo3.api.Adapter
import com.apollographql.apollo3.api.CustomScalarAdapters
import com.apollographql.apollo3.api.json.JsonReader
import com.apollographql.apollo3.api.json.JsonWriter

object LongStringScalar : Adapter<Long> {
    override fun fromJson(
        reader: JsonReader,
        customScalarAdapters: CustomScalarAdapters,
    ): Long = reader.nextString()!!.toLong()

    override fun toJson(
        writer: JsonWriter,
        customScalarAdapters: CustomScalarAdapters,
        value: Long,
    ) {
        writer.value(value.toString())
    }
}
