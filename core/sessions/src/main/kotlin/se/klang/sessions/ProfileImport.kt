package se.klang.sessions

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** The file cannot be loaded as a reference profile. Nothing was stored. */
class ProfileImportException(message: String) : Exception(message)

/**
 * A `klang.reference-profile` file, schema version 1 (docs/reference-profiles.md). One profile per
 * file. The file declares its own citation; the app stores it as [ProfileSource.Imported].
 */
@Serializable
data class ReferenceProfileFile(
    val schema: String = SCHEMA,
    val schemaVersion: Int = SCHEMA_VERSION,
    val profile: DeclaredProfile,
) {
    /** A profile as the file declares it: the source is always IMPORTED, so the file gives only a citation. */
    @Serializable
    data class DeclaredProfile(
        val id: String,
        val version: Int,
        val name: String,
        val roomUses: List<RoomUse> = emptyList(),
        val citation: Citation,
        val target: ReverbTarget,
        val bands: BandScope = BandScope.SpeechAverage,
        val conditions: String = "",
    )

    companion object {
        const val SCHEMA = "klang.reference-profile"
        const val SCHEMA_VERSION = 1
        const val MAX_BYTES = 64 * 1024
    }
}

/**
 * Strict reader for `klang.reference-profile` files: unknown fields, unknown schema or version,
 * missing citation text and out-of-range values are all rejected with a message, before anything
 * is stored.
 */
object ProfileImporter {
    private val json = Json { ignoreUnknownKeys = false; classDiscriminator = "type" }

    fun parse(bytes: ByteArray, fileName: String, importedAtEpochMs: Long): ReferenceProfile {
        if (bytes.size > ReferenceProfileFile.MAX_BYTES) {
            throw ProfileImportException("The file is larger than ${ReferenceProfileFile.MAX_BYTES / 1024} KB; a reference profile is a small JSON file.")
        }
        val text = bytes.toString(Charsets.UTF_8)
        val root = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (e: SerializationException) {
            null
        } ?: throw ProfileImportException("“$fileName” is not a JSON document.")
        val schema = (root["schema"] as? JsonPrimitive)?.contentOrNull
        if (schema != ReferenceProfileFile.SCHEMA) {
            throw ProfileImportException("Not a reference profile: schema is “${schema ?: "missing"}”, expected “${ReferenceProfileFile.SCHEMA}”.")
        }
        val version = (root["schemaVersion"] as? JsonPrimitive)?.intOrNull
        if (version != ReferenceProfileFile.SCHEMA_VERSION) {
            throw ProfileImportException(
                "Unsupported reference profile schema version ${version ?: "(missing)"}; this app reads version ${ReferenceProfileFile.SCHEMA_VERSION}.",
            )
        }
        val file = try {
            json.decodeFromJsonElement(ReferenceProfileFile.serializer(), root)
        } catch (e: SerializationException) {
            throw ProfileImportException("The file does not match schema version ${ReferenceProfileFile.SCHEMA_VERSION}: ${e.message?.lineSequence()?.firstOrNull()}")
        } catch (e: IllegalArgumentException) {
            throw ProfileImportException("The file does not match schema version ${ReferenceProfileFile.SCHEMA_VERSION}: ${e.message?.lineSequence()?.firstOrNull()}")
        }
        val d = file.profile
        if (BuiltInProfiles.isBuiltInId(d.id)) {
            throw ProfileImportException("The id “${d.id}” belongs to a built-in profile; an imported profile needs its own id.")
        }
        val profile = ReferenceProfile(
            id = d.id,
            version = d.version,
            name = d.name.trim(),
            roomUses = d.roomUses,
            source = ProfileSource.Imported(fileName.take(ProfileLimits.MAX_NAME_LENGTH), d.citation, importedAtEpochMs),
            target = d.target,
            bands = d.bands,
            conditions = d.conditions,
        )
        val problems = ProfileValidation.problems(profile)
        if (problems.isNotEmpty()) throw ProfileImportException("The profile is not valid: " + problems.joinToString("; ") + ".")
        return profile
    }

    /** The file a profile would be written as (for documentation, tests and sharing). */
    fun toFile(p: ReferenceProfile, citation: Citation): String = Json { prettyPrint = true; encodeDefaults = true; classDiscriminator = "type" }
        .encodeToString(
            ReferenceProfileFile.serializer(),
            ReferenceProfileFile(profile = ReferenceProfileFile.DeclaredProfile(p.id, p.version, p.name, p.roomUses, citation, p.target, p.bands, p.conditions)),
        )
}
