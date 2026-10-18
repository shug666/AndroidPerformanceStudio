package com.androidperformancestudio.gpu.toolchain

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

public class AgiExecutablePreferenceStore(
    private val preferenceFile: Path,
) {
    public fun load(): Path? =
        runCatching {
            Files.readString(preferenceFile).takeIf(String::isNotBlank)?.let(Path::of)
        }.getOrNull()

    public fun save(executable: Path) {
        val destination = preferenceFile.toAbsolutePath().normalize()
        val parent = requireNotNull(destination.parent)
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, "${destination.fileName}.", ".tmp")
        try {
            Files.writeString(temporary, executable.toAbsolutePath().normalize().toString())
            try {
                Files.move(temporary, destination, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, destination, REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
