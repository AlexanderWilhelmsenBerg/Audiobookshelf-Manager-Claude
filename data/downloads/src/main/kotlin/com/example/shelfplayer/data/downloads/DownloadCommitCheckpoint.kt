package com.example.shelfplayer.data.downloads

import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.download.DownloadState
import com.example.shelfplayer.core.model.download.OfflineFile
import com.example.shelfplayer.core.model.getOrNull
import com.example.shelfplayer.core.model.resultOf
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest

/**
 * DL-001 / DL-002. Write proof before renaming a verified part. A final file can then survive a
 * process restart even when Room has yet to mark it complete. Only the manifest publishes it.
 */
internal object DownloadCommitCheckpoint {
    suspend fun prepare(part: File, before: OfflineFile, completed: OfflineFile): AppResult<Unit> = resultOf(onError = {
        AppError.Storage("The download could not be prepared for offline use.", temporarilyUnavailable = true)
    }) {
        val canonicalPart = part.canonicalPath
        val hash = digest(part)
        val target = checkpointFor(part)
        val temporary = File(part.parentFile, target.name + TEMP_SUFFIX)
        try {
            FileOutputStream(temporary).use { stream ->
                val output = DataOutputStream(stream)
                output.writeInt(VERSION)
                output.writeUTF(canonicalPart)
                output.writeUTF(before.remoteFileId)
                output.writeNullableText(before.eTag)
                output.writeNullableText(before.lastModified)
                output.writeLong(completed.downloadedBytes)
                output.writeLong(completed.expectedBytes ?: UNKNOWN_LENGTH)
                output.writeNullableText(completed.mimeType)
                output.writeNullableText(completed.eTag)
                output.writeNullableText(completed.lastModified)
                output.write(hash)
                output.flush()
                stream.fd.sync()
            }
            replace(temporary, target)
        } finally {
            temporary.delete()
        }
    }

    suspend fun recover(part: File, destination: File, manifestFile: OfflineFile): OfflineFile? = resultOf {
        val checkpoint = read(part) ?: return null
        if (!checkpoint.matches(part, destination, manifestFile)) return null
        if (!MessageDigest.isEqual(digest(destination), checkpoint.sha256)) return null
        manifestFile.copy(
            uri = destination.toURI().toString(),
            state = DownloadState.Complete,
            expectedBytes = checkpoint.expected,
            downloadedBytes = checkpoint.bytes,
            mimeType = checkpoint.mimeType,
            eTag = checkpoint.validator,
            lastModified = checkpoint.lastModified,
        )
    }.getOrNull()

    fun clear(part: File) {
        checkpointFor(part).delete()
    }

    private fun read(part: File): Receipt? {
        val file = checkpointFor(part)
        if (!file.isFile || file.length() !in 1..MAX_CHECKPOINT_BYTES) return null
        return DataInputStream(file.inputStream().buffered()).use { input ->
            if (input.readInt() != VERSION) return null
            val path = input.readUTF()
            val remoteId = input.readUTF()
            val previousValidator = input.readNullableText()
            val previousModified = input.readNullableText()
            val bytes = input.readLong()
            val expected = input.readLong().takeUnless { it == UNKNOWN_LENGTH }
            val mimeType = input.readNullableText()
            val validator = input.readNullableText()
            val lastModified = input.readNullableText()
            val sha = ByteArray(SHA256_BYTES).also(input::readFully)
            if (input.read() != -1 || bytes <= 0) return null
            Receipt(
                path,
                remoteId,
                previousValidator,
                previousModified,
                bytes,
                expected,
                mimeType,
                validator,
                lastModified,
                sha,
            )
        }
    }

    private fun replace(temporary: File, target: File) {
        try {
            Files.move(temporary.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), target.toPath(), REPLACE_EXISTING)
        }
    }

    private suspend fun digest(file: File): ByteArray {
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        file.inputStream().use { input ->
            var count = input.read(buffer)
            while (count != -1) {
                currentCoroutineContext().ensureActive()
                hash.update(buffer, 0, count)
                count = input.read(buffer)
            }
        }
        return hash.digest()
    }

    private fun checkpointFor(part: File) = File(part.parentFile, part.name + CHECKPOINT_SUFFIX)

    private fun DataInputStream.readNullableText(): String? = if (readBoolean()) readUTF() else null

    private fun DataOutputStream.writeNullableText(text: String?) {
        writeBoolean(text != null)
        if (text != null) writeUTF(text)
    }

    private data class Receipt(
        val partPath: String,
        val remoteFileId: String,
        val previousValidator: String?,
        val previousModified: String?,
        val bytes: Long,
        val expected: Long?,
        val mimeType: String?,
        val validator: String?,
        val lastModified: String?,
        val sha256: ByteArray,
    ) {
        fun matches(part: File, destination: File, file: OfflineFile): Boolean {
            val parent = part.parentFile ?: return false
            val safePath = File(parent.canonicalFile, destination.name)
            val identityMatches = partPath == part.canonicalPath && remoteFileId == file.remoteFileId
            val validatorsMatch = previousValidator == file.eTag && previousModified == file.lastModified
            val isCommitted = !part.exists() && destination.canonicalFile == safePath && destination.isFile
            return identityMatches && validatorsMatch && isCommitted && destination.length() == bytes
        }
    }

    private const val UNKNOWN_LENGTH = -1L
    private const val SHA256_BYTES = 32
    private const val VERSION = 1
    private const val MAX_CHECKPOINT_BYTES = 512 * 1024L
    private const val CHECKPOINT_SUFFIX = ".verified"
    private const val TEMP_SUFFIX = ".new"
}
