package com.riftos.app

import java.util.UUID

/**
 * Initial pairing placeholder.
 * Production should store hashed tokens in RiftSecretStore.
 */
class RiftPairingManager {
    fun createPairingToken(): String = UUID.randomUUID().toString()

    fun revokeToken(token: String) {
        // TODO: remove token from secure storage
    }
}
