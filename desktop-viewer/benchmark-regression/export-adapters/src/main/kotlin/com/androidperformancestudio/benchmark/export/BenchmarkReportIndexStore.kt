package com.androidperformancestudio.benchmark.export

import com.androidperformancestudio.benchmark.model.BenchmarkReportEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/**
 * Append-only index of exported benchmark reports.
 *
 * The Report Summary column answers "what did I generate, where is it, can I open it again" without
 * turning different report bodies into one shared model (implementation plan §16.2). Deleting an
 * index entry never deletes the user's file, so the index stays a catalogue.
 */
public class BenchmarkReportIndexStore(
    private val file: Path,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private val json = Json { prettyPrint = true }

    public fun load(): List<BenchmarkReportEntry> {
        if (!Files.exists(file)) return emptyList()
        return runCatching {
            json.parseToJsonElement(Files.readString(file)).jsonArray.mapNotNull { element ->
                val entry = element.jsonObject
                val path = entry["path"]?.jsonPrimitive?.content ?: return@mapNotNull null
                BenchmarkReportEntry(
                    id = entry["id"]?.jsonPrimitive?.content ?: path,
                    title = entry["title"]?.jsonPrimitive?.content ?: path,
                    format = entry["format"]?.jsonPrimitive?.content ?: "JSON",
                    createdAt =
                        entry["createdAt"]?.jsonPrimitive?.content?.let { runCatching { Instant.parse(it) }.getOrNull() }
                            ?: Instant.EPOCH,
                    path = path,
                    sizeBytes = entry["sizeBytes"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                )
            }
        }.getOrDefault(emptyList()).sortedByDescending(BenchmarkReportEntry::createdAt)
    }

    /** Records an artifact that already exists on disk; the size comes from the file itself. */
    public fun record(
        path: Path,
        title: String,
        format: String,
    ): List<BenchmarkReportEntry> {
        val absolute = path.toAbsolutePath().normalize()
        val size = runCatching { Files.size(absolute) }.getOrDefault(0L)
        return record(
            BenchmarkReportEntry(
                id = UUID.randomUUID().toString(),
                title = title,
                format = format,
                createdAt = Instant.now(),
                path = absolute.toString(),
                sizeBytes = size,
            ),
        )
    }

    public fun record(entry: BenchmarkReportEntry): List<BenchmarkReportEntry> {
        val entries = (listOf(entry) + load().filterNot { it.path == entry.path }).take(maxEntries)
        file.toAbsolutePath().parent?.let(Files::createDirectories)
        Files.writeString(
            file,
            json.encodeToString(
                buildJsonArray {
                    entries.forEach { item ->
                        add(
                            buildJsonObject {
                                put("id", item.id)
                                put("title", item.title)
                                put("format", item.format)
                                put("createdAt", item.createdAt.toString())
                                put("path", item.path)
                                put("sizeBytes", item.sizeBytes)
                            },
                        )
                    }
                },
            ),
        )
        return entries
    }

    private companion object {
        const val DEFAULT_MAX_ENTRIES = 50
    }
}
