package com.riftos.app

import android.content.Context

/** Core package lifecycle revocation of all stored grants for a managed RAPP. */
internal object RiftCorePackageGrants {
    fun revokeAll(context: Context, id: String): Boolean {
        require(Regex("^[A-Za-z0-9][A-Za-z0-9._-]{1,63}$").matches(id)) {
            "RAPP grant identity invalid"
        }
        val prefs = context.applicationContext.getSharedPreferences(
            "rift-native", Context.MODE_PRIVATE
        )
        val key = "setting:permissions:$id"
        val existed = prefs.contains(key)
        if (existed) {
            require(prefs.edit().remove(key).commit()) {
                "Failed to persist RAPP grant revocation"
            }
        }
        return existed
    }
}
