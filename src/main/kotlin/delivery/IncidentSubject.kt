package delivery

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = IncidentSubjectSerializer::class)
sealed interface IncidentSubject {
    val key: String

    val ownerOnly: Boolean

    val title: String
        get() =
            when (this) {
                is BetaCommit -> "Beta delivery needs attention: ${sha.take(12)}"
                is AppStoreVersion -> "App Store release needs attention: $version"
                is ReviewSubmission -> "App Store release needs attention: review-submission-$id"
                is Hold,
                is Receipt,
                Audit,
                AppStore,
                Incidents,
                is Unknown -> "iOS delivery needs attention: $key"
            }

    data class BetaCommit(val sha: String) : IncidentSubject {
        override val ownerOnly = false

        override val key
            get() = "internal/$sha"
    }

    data class AppStoreVersion(val version: String) : IncidentSubject {
        override val ownerOnly = true

        override val key
            get() = "appstore/$version"
    }

    data class ReviewSubmission(val id: String) : IncidentSubject {
        override val ownerOnly = true

        override val key
            get() = "appstore/review-submission-$id"
    }

    data class Hold(val issue: String) : IncidentSubject {
        override val ownerOnly = true

        constructor(issue: Int) : this(issue.toString())

        override val key
            get() = "hold/$issue"
    }

    data class Receipt(val version: String, val number: String) : IncidentSubject {
        override val ownerOnly = true

        override val key
            get() = "receipt/$version/$number"
    }

    data object Audit : IncidentSubject {
        override val ownerOnly = true

        override val key = "audit"
    }

    data object AppStore : IncidentSubject {
        override val ownerOnly = true

        override val key = "appstore"
    }

    data object Incidents : IncidentSubject {
        override val ownerOnly = true

        override val key = "incidents"
    }

    // Preserve unrecognised historical markers instead of reinterpreting or dropping them.
    data class Unknown(override val key: String) : IncidentSubject {
        override val ownerOnly = false
    }

    companion object {
        // Decode legacy keys only at the issue-marker/serialization boundary. Domain decisions
        // use the sealed subject, never string prefixes.
        fun fromKey(key: String): IncidentSubject {
            val match = Regex("(internal|appstore|hold|receipt)/(.*)").matchEntire(key)
            if (match == null)
                return when (key) {
                    "audit" -> Audit
                    "appstore" -> AppStore
                    "incidents" -> Incidents
                    else -> Unknown(key)
                }
            val value = match.groupValues[2]
            return when (match.groupValues[1]) {
                "internal" -> BetaCommit(value)
                "appstore" -> {
                    val review = Regex("review-submission-(.*)").matchEntire(value)
                    if (review == null) AppStoreVersion(value)
                    else ReviewSubmission(review.groupValues[1])
                }
                "hold" -> Hold(value)
                "receipt" -> {
                    val parts = value.split('/')
                    if (parts.size == 2) Receipt(parts[0], parts[1]) else Unknown(key)
                }
                else -> Unknown(key)
            }
        }
    }
}

object IncidentSubjectSerializer : KSerializer<IncidentSubject> {
    override val descriptor =
        PrimitiveSerialDescriptor("delivery.IncidentSubject", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: IncidentSubject) =
        encoder.encodeString(value.key)

    override fun deserialize(decoder: Decoder): IncidentSubject =
        IncidentSubject.fromKey(decoder.decodeString())
}
