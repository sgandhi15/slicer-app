package com.example.slicer.storage

import android.content.Context
import android.content.Intent
import android.net.Uri

internal interface SourceUriPermissionStore {
    fun persistedReadUris(): Set<Uri>

    fun persistedPermissionCount(): Int

    fun persistReadPermission(uri: Uri): Boolean

    fun releaseReadPermission(uri: Uri): Boolean
}

internal class AndroidSourceUriPermissionStore(
    private val context: Context
) : SourceUriPermissionStore {
    override fun persistedReadUris(): Set<Uri> {
        return context.contentResolver.persistedUriPermissions
            .filter { permission -> permission.isReadPermission }
            .mapTo(mutableSetOf()) { permission -> permission.uri }
    }

    override fun persistedPermissionCount(): Int {
        return context.contentResolver.persistedUriPermissions.size
    }

    override fun persistReadPermission(uri: Uri): Boolean {
        if (uri in persistedReadUris()) return true

        return try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            uri in persistedReadUris()
        } catch (_: Exception) {
            false
        }
    }

    override fun releaseReadPermission(uri: Uri): Boolean {
        return try {
            context.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            true
        } catch (_: Exception) {
            false
        }
    }
}
