package com.swordfish.lemuroid.lib.storage.local

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.swordfish.lemuroid.common.kotlin.toStringCRC32
import net.sf.sevenzipjbinding.ArchiveFormat
import net.sf.sevenzipjbinding.ExtractAskMode
import net.sf.sevenzipjbinding.ExtractOperationResult
import net.sf.sevenzipjbinding.IArchiveExtractCallback
import net.sf.sevenzipjbinding.IInArchive
import net.sf.sevenzipjbinding.IInStream
import net.sf.sevenzipjbinding.ISeekableStream
import net.sf.sevenzipjbinding.ISequentialOutStream
import net.sf.sevenzipjbinding.PropID
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.SevenZipException
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class SevenZipEntryInfo(
    val index: Int,
    val rawPath: String,
    val fileName: String,
    val size: Long,
    val crcHex: String?,
)

class OpenedSevenZipArchive(
    val archive: IInArchive,
    private val inStream: IInStream,
    private val tempFile: File?,
) : Closeable {
    override fun close() {
        runCatching { archive.close() }
        runCatching { inStream.close() }
        runCatching { tempFile?.delete() }
    }
}

private class FileChannelInStream(
    private val autoCloseInputStream: ParcelFileDescriptor.AutoCloseInputStream,
    private val channel: FileChannel,
) : IInStream {
    @Synchronized
    override fun seek(
        offset: Long,
        seekOrigin: Int,
    ): Long {
        try {
            val target =
                when (seekOrigin) {
                    ISeekableStream.SEEK_SET -> offset
                    ISeekableStream.SEEK_CUR -> channel.position() + offset
                    ISeekableStream.SEEK_END -> channel.size() + offset
                    else -> throw SevenZipException("Unknown seek origin: $seekOrigin")
                }
            channel.position(target.coerceAtLeast(0L))
            return channel.position()
        } catch (e: IOException) {
            throw SevenZipException("Failed seeking 7z FileChannel", e)
        }
    }

    @Synchronized
    override fun read(data: ByteArray): Int {
        if (data.isEmpty()) return 0
        try {
            val read = channel.read(ByteBuffer.wrap(data))
            return if (read <= 0) 0 else read
        } catch (e: IOException) {
            throw SevenZipException("Failed reading 7z FileChannel", e)
        }
    }

    override fun close() {
        runCatching { channel.close() }
        autoCloseInputStream.close()
    }
}

object SevenZipUtils {
    @Synchronized
    private fun ensureInitialized() {
        if (SevenZip.isInitializedSuccessfully()) return
        try {
            SevenZip.initSevenZipFromPlatformJAR()
        } catch (_: Throwable) {
            System.loadLibrary("7-Zip-JBinding")
            SevenZip.initLoadedLibraries()
        }
    }

    fun openArchive(
        context: Context,
        uri: Uri,
    ): OpenedSevenZipArchive {
        ensureInitialized()
        val (inStream, tempFile) = openInStream(context, uri)
        val archive =
            try {
                try {
                    SevenZip.openInArchive(ArchiveFormat.SEVEN_ZIP, inStream)
                } catch (_: SevenZipException) {
                    inStream.seek(0L, ISeekableStream.SEEK_SET)
                    SevenZip.openInArchive(null, inStream)
                }
            } catch (t: Throwable) {
                runCatching { inStream.close() }
                runCatching { tempFile?.delete() }
                throw t
            }
        return OpenedSevenZipArchive(archive, inStream, tempFile)
    }

    private fun openInStream(
        context: Context,
        uri: Uri,
    ): Pair<IInStream, File?> {
        if (uri.scheme == "file") {
            val path = uri.path ?: throw IOException("Missing file path for URI: $uri")
            return RandomAccessFileInStream(RandomAccessFile(File(path), "r")) to null
        }

        val pfd = runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
        if (pfd != null) {
            val autoStream = ParcelFileDescriptor.AutoCloseInputStream(pfd)
            val channel = autoStream.channel
            val isSeekable =
                runCatching {
                    val total = channel.size()
                    channel.position(0L)
                    total > 0L
                }.getOrDefault(false)

            if (isSeekable) {
                return FileChannelInStream(autoStream, channel) to null
            } else {
                runCatching { autoStream.close() }
            }
        }

        val tempDir = File(context.cacheDir, "sevenz-stream-tmp").apply { mkdirs() }
        val tempFile = File.createTempFile("archive_", ".7z", tempDir)
        try {
            val input =
                context.contentResolver.openInputStream(uri)
                    ?: throw IOException("Cannot open input stream for 7z URI: $uri")
            input.use { src ->
                tempFile.outputStream().use { dst ->
                    src.copyTo(dst)
                }
            }
            return RandomAccessFileInStream(RandomAccessFile(tempFile, "r")) to tempFile
        } catch (t: Throwable) {
            tempFile.delete()
            throw t
        }
    }

    fun listEntries(archive: IInArchive): List<SevenZipEntryInfo> {
        val count = archive.numberOfItems
        val result = ArrayList<SevenZipEntryInfo>(count)
        for (i in 0 until count) {
            val isFolder = archive.getProperty(i, PropID.IS_FOLDER) as? Boolean ?: false
            if (isFolder) continue
            val rawPath = (archive.getStringProperty(i, PropID.PATH) ?: "").replace('\\', '/')
            val fileName = rawPath.substringAfterLast('/')
            if (fileName.isBlank() || fileName == "." || fileName == "..") continue
            val size = (archive.getProperty(i, PropID.SIZE) as? Number)?.toLong() ?: -1L
            val crcInt = (archive.getProperty(i, PropID.CRC) as? Number)?.toInt()
            val crcHex =
                if (crcInt != null && crcInt != 0) {
                    (crcInt.toLong() and 0xFFFFFFFFL).toStringCRC32()
                } else {
                    null
                }
            result.add(SevenZipEntryInfo(i, rawPath, fileName, size, crcHex))
        }
        return result
    }

    fun extractEntryPrefix(
        archive: IInArchive,
        itemIndex: Int,
        maxBytes: Int,
    ): ByteArray {
        val out = ByteArrayOutputStream(maxBytes.coerceAtMost(262_144))
        var limitReached = false
        var opResult: ExtractOperationResult? = null

        val callback =
            object : IArchiveExtractCallback {
                override fun setTotal(total: Long) {}

                override fun setCompleted(complete: Long) {}

                override fun prepareOperation(extractAskMode: ExtractAskMode) {}

                override fun getStream(
                    index: Int,
                    extractAskMode: ExtractAskMode,
                ): ISequentialOutStream? {
                    if (index != itemIndex || extractAskMode != ExtractAskMode.EXTRACT) return null
                    return ISequentialOutStream { data ->
                        val remaining = maxBytes - out.size()
                        if (remaining > 0) {
                            val toWrite = minOf(remaining, data.size)
                            out.write(data, 0, toWrite)
                        }
                        if (out.size() >= maxBytes) {
                            limitReached = true
                            throw SevenZipException("Prefix limit reached")
                        }
                        data.size
                    }
                }

                override fun setOperationResult(extractOperationResult: ExtractOperationResult) {
                    opResult = extractOperationResult
                }
            }

        try {
            archive.extract(intArrayOf(itemIndex), false, callback)
        } catch (e: SevenZipException) {
            if (!limitReached) throw e
        }

        if (!limitReached && opResult != null && opResult != ExtractOperationResult.OK) {
            throw SevenZipException("7z prefix extract failed: $opResult")
        }
        return out.toByteArray()
    }

    fun extractEntryToFile(
        archive: IInArchive,
        itemIndex: Int,
        targetFile: File,
    ) {
        targetFile.parentFile?.mkdirs()
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.part")
        tempFile.delete()

        var stream: BufferedOutputStream? = null
        var opResult: ExtractOperationResult? = null
        var finishedTarget = false

        val callback =
            object : IArchiveExtractCallback {
                override fun setTotal(total: Long) {}

                override fun setCompleted(complete: Long) {}

                override fun prepareOperation(extractAskMode: ExtractAskMode) {}

                override fun getStream(
                    index: Int,
                    extractAskMode: ExtractAskMode,
                ): ISequentialOutStream? {
                    if (index != itemIndex || extractAskMode != ExtractAskMode.EXTRACT) return null
                    val bos = BufferedOutputStream(FileOutputStream(tempFile), 64 * 1024)
                    stream = bos
                    return ISequentialOutStream { data ->
                        try {
                            bos.write(data)
                            data.size
                        } catch (e: IOException) {
                            throw SevenZipException("Failed writing extracted 7z entry", e)
                        }
                    }
                }

                override fun setOperationResult(extractOperationResult: ExtractOperationResult) {
                    opResult = extractOperationResult
                    runCatching { stream?.flush() }
                    runCatching { stream?.close() }
                    stream = null
                    finishedTarget = true
                }
            }

        try {
            archive.extract(intArrayOf(itemIndex), false, callback)
            if (!finishedTarget || opResult != ExtractOperationResult.OK || !tempFile.exists()) {
                throw IOException("7z extraction failed with status: $opResult")
            }
            if (targetFile.exists()) {
                targetFile.delete()
            }
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }
        } catch (t: Throwable) {
            runCatching { stream?.close() }
            tempFile.delete()
            throw t
        }
    }

    fun extractAllToDirectory(
        archive: IInArchive,
        targetDir: File,
    ) {
        targetDir.mkdirs()
        val canonicalRoot = targetDir.canonicalPath + File.separator
        val count = archive.numberOfItems
        var currentStream: BufferedOutputStream? = null
        var lastError: ExtractOperationResult? = null

        val callback =
            object : IArchiveExtractCallback {
                override fun setTotal(total: Long) {}

                override fun setCompleted(complete: Long) {}

                override fun prepareOperation(extractAskMode: ExtractAskMode) {}

                override fun getStream(
                    index: Int,
                    extractAskMode: ExtractAskMode,
                ): ISequentialOutStream? {
                    if (extractAskMode != ExtractAskMode.EXTRACT || index < 0 || index >= count) return null
                    val isFolder = archive.getProperty(index, PropID.IS_FOLDER) as? Boolean ?: false
                    if (isFolder) return null

                    val rawPath = (archive.getStringProperty(index, PropID.PATH) ?: "").replace('\\', '/')
                    val sanitized = rawPath.split('/').filter { it.isNotEmpty() && it != "." && it != ".." }.joinToString("/")
                    if (sanitized.isEmpty()) return null

                    val destFile = File(targetDir, sanitized)
                    if (!destFile.canonicalPath.startsWith(canonicalRoot)) return null
                    destFile.parentFile?.mkdirs()

                    runCatching { currentStream?.close() }
                    val bos = BufferedOutputStream(FileOutputStream(destFile), 64 * 1024)
                    currentStream = bos
                    return ISequentialOutStream { data ->
                        try {
                            bos.write(data)
                            data.size
                        } catch (e: IOException) {
                            throw SevenZipException("Failed extracting 7z item $sanitized", e)
                        }
                    }
                }

                override fun setOperationResult(extractOperationResult: ExtractOperationResult) {
                    runCatching { currentStream?.flush() }
                    runCatching { currentStream?.close() }
                    currentStream = null
                    if (extractOperationResult != ExtractOperationResult.OK) {
                        lastError = extractOperationResult
                    }
                }
            }

        try {
            archive.extract(null, false, callback)
            if (lastError != null) {
                throw IOException("7z directory extraction failed: $lastError")
            }
        } catch (t: Throwable) {
            runCatching { currentStream?.close() }
            throw t
        }
    }

    fun repackAllToZip(
        archive: IInArchive,
        targetZip: File,
    ) {
        targetZip.parentFile?.mkdirs()
        val tempZip = File(targetZip.parentFile, "${targetZip.name}.part")
        tempZip.delete()

        val zos = ZipOutputStream(BufferedOutputStream(FileOutputStream(tempZip), 64 * 1024))
        var entryOpen = false
        var lastError: ExtractOperationResult? = null
        val seenNames = HashSet<String>()

        val callback =
            object : IArchiveExtractCallback {
                override fun setTotal(total: Long) {}

                override fun setCompleted(complete: Long) {}

                override fun prepareOperation(extractAskMode: ExtractAskMode) {}

                override fun getStream(
                    index: Int,
                    extractAskMode: ExtractAskMode,
                ): ISequentialOutStream? {
                    if (extractAskMode != ExtractAskMode.EXTRACT) return null
                    val isFolder = archive.getProperty(index, PropID.IS_FOLDER) as? Boolean ?: false
                    if (isFolder) return null

                    val rawPath = (archive.getStringProperty(index, PropID.PATH) ?: "").replace('\\', '/')
                    val entryName = rawPath.split('/').filter { it.isNotEmpty() && it != "." && it != ".." }.joinToString("/")
                    if (entryName.isEmpty() || !seenNames.add(entryName)) return null

                    try {
                        if (entryOpen) {
                            zos.closeEntry()
                            entryOpen = false
                        }
                        zos.putNextEntry(ZipEntry(entryName))
                        entryOpen = true
                    } catch (e: IOException) {
                        throw SevenZipException("Failed creating ZIP entry $entryName", e)
                    }

                    return ISequentialOutStream { data ->
                        try {
                            zos.write(data)
                            data.size
                        } catch (e: IOException) {
                            throw SevenZipException("Failed writing ZIP entry $entryName", e)
                        }
                    }
                }

                override fun setOperationResult(extractOperationResult: ExtractOperationResult) {
                    if (entryOpen) {
                        runCatching { zos.closeEntry() }
                        entryOpen = false
                    }
                    if (extractOperationResult != ExtractOperationResult.OK) {
                        lastError = extractOperationResult
                    }
                }
            }

        try {
            archive.extract(null, false, callback)
            if (entryOpen) {
                zos.closeEntry()
                entryOpen = false
            }
            zos.close()
            if (lastError != null) {
                throw IOException("7z repack failed: $lastError")
            }
            if (targetZip.exists()) {
                targetZip.delete()
            }
            if (!tempZip.renameTo(targetZip)) {
                tempZip.copyTo(targetZip, overwrite = true)
                tempZip.delete()
            }
        } catch (t: Throwable) {
            runCatching { zos.close() }
            tempZip.delete()
            throw t
        }
    }
}
