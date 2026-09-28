package com.lichiai.terminal.backend.ssh

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.Session
import com.lichiai.terminal.core.TerminalResourceGovernor
import com.lichiai.terminal.model.SftpFileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Vector

/**
 * Robust SFTP Manager for remote file browsing and transfers.
 * Reuses the authenticated SSH session from SshBackend.
 */
class SftpManager(private val getSession: () -> Session?) {

    suspend fun listFiles(remoteDir: String): Result<List<SftpFileItem>> = withContext(Dispatchers.IO) {
        val session = getSession() ?: return@withContext Result.failure(IllegalStateException("SSH Session not connected"))
        var sftpChannel: ChannelSftp? = null
        try {
            sftpChannel = session.openChannel("sftp") as ChannelSftp
            sftpChannel.connect(TerminalResourceGovernor.CONNECT_TIMEOUT_MS)

            val vector: Vector<*> = sftpChannel.ls(remoteDir)
            val list = mutableListOf<SftpFileItem>()

            for (obj in vector) {
                if (obj is ChannelSftp.LsEntry) {
                    val name = obj.filename
                    if (name == "." || name == "..") continue
                    val attrs = obj.attrs
                    val isDir = attrs.isDir
                    val size = attrs.size
                    val permissions = attrs.permissionsString
                    val mtime = attrs.mTime.toLong() * 1000L
                    val fullPath = if (remoteDir.endsWith("/")) "$remoteDir$name" else "$remoteDir/$name"

                    list.add(
                        SftpFileItem(
                            name = name,
                            path = fullPath,
                            isDirectory = isDir,
                            sizeBytes = size,
                            permissions = permissions,
                            lastModifiedEpochMs = mtime
                        )
                    )
                }
            }
            Result.success(list.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })))
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            sftpChannel?.disconnect()
        }
    }

    suspend fun createDirectory(remotePath: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val session = getSession() ?: return@withContext Result.failure(IllegalStateException("SSH Session not connected"))
        var sftpChannel: ChannelSftp? = null
        try {
            sftpChannel = session.openChannel("sftp") as ChannelSftp
            sftpChannel.connect(TerminalResourceGovernor.CONNECT_TIMEOUT_MS)
            sftpChannel.mkdir(remotePath)
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            sftpChannel?.disconnect()
        }
    }

    suspend fun deleteFile(remotePath: String, isDirectory: Boolean): Result<Boolean> = withContext(Dispatchers.IO) {
        val session = getSession() ?: return@withContext Result.failure(IllegalStateException("SSH Session not connected"))
        var sftpChannel: ChannelSftp? = null
        try {
            sftpChannel = session.openChannel("sftp") as ChannelSftp
            sftpChannel.connect(TerminalResourceGovernor.CONNECT_TIMEOUT_MS)
            if (isDirectory) {
                sftpChannel.rmdir(remotePath)
            } else {
                sftpChannel.rm(remotePath)
            }
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            sftpChannel?.disconnect()
        }
    }

    suspend fun rename(oldPath: String, newPath: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val session = getSession() ?: return@withContext Result.failure(IllegalStateException("SSH Session not connected"))
        var sftpChannel: ChannelSftp? = null
        try {
            sftpChannel = session.openChannel("sftp") as ChannelSftp
            sftpChannel.connect(TerminalResourceGovernor.CONNECT_TIMEOUT_MS)
            sftpChannel.rename(oldPath, newPath)
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            sftpChannel?.disconnect()
        }
    }

    suspend fun downloadFile(remotePath: String, localTarget: File, onProgress: ((bytesTransferred: Long) -> Unit)? = null): Result<File> = withContext(Dispatchers.IO) {
        val session = getSession() ?: return@withContext Result.failure(IllegalStateException("SSH Session not connected"))
        var sftpChannel: ChannelSftp? = null
        try {
            sftpChannel = session.openChannel("sftp") as ChannelSftp
            sftpChannel.connect(TerminalResourceGovernor.CONNECT_TIMEOUT_MS)

            val attrs = sftpChannel.stat(remotePath)
            if (attrs.size > TerminalResourceGovernor.MAX_SFTP_DOWNLOAD_BYTES) {
                return@withContext Result.failure(IllegalStateException("File size exceeds maximum mobile limit of 100MB"))
            }

            FileOutputStream(localTarget).use { fos ->
                sftpChannel.get(remotePath, fos)
            }
            Result.success(localTarget)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            sftpChannel?.disconnect()
        }
    }

    suspend fun uploadFile(localSource: File, remotePath: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val session = getSession() ?: return@withContext Result.failure(IllegalStateException("SSH Session not connected"))
        var sftpChannel: ChannelSftp? = null
        try {
            sftpChannel = session.openChannel("sftp") as ChannelSftp
            sftpChannel.connect(TerminalResourceGovernor.CONNECT_TIMEOUT_MS)
            FileInputStream(localSource).use { fis ->
                sftpChannel.put(fis, remotePath)
            }
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            sftpChannel?.disconnect()
        }
    }
}
