package org.example.statemind.core

import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class VersionEntry(
    val id: String,
    val type: Type,
    val url: String,
    val releaseTime: OffsetDateTime?,
    val sha1: String,
    val size: Long
) {

    enum class Type {
        RELEASE,
        SNAPSHOT,
        OLD_BETA,
        OLD_ALPHA,
        UNKNOWN
    }

    val releaseText: String
        get() = releaseTime
            ?.atZoneSameInstant(ZoneId.systemDefault())
            ?.format(TIME_FORMAT)
            ?: "—"

    companion object {

        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")

        fun typeOf(raw: String?): Type = when (raw?.lowercase()) {
            "release" -> Type.RELEASE
            "snapshot" -> Type.SNAPSHOT
            "old_beta" -> Type.OLD_BETA
            "old_alpha" -> Type.OLD_ALPHA
            else -> Type.UNKNOWN
        }
    }
}
