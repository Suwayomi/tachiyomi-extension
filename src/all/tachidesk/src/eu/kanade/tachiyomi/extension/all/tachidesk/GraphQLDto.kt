package eu.kanade.tachiyomi.extension.all.tachidesk

import kotlinx.serialization.Serializable

@Serializable
data class Error(val message: String)

@Serializable
data class Outer(val errors: List<Error>)
