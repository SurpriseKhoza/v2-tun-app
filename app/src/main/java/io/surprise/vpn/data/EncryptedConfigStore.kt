package io.surprise.vpn.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Local envelope encryption for provider exports. The AES key never leaves the
 * Android Keystore; AES-GCM authenticates both the ciphertext and the envelope.
 */
object EncryptedConfigStore {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "v2tun-provider-config-v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12
    private const val VERSION = 1

    fun encrypt(plainText: String, associatedData: String = "V2-TUN"): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv = ByteArray(IV_BYTES).also { java.security.SecureRandom().nextBytes(it) }
        cipher.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(associatedData.toByteArray(StandardCharsets.UTF_8))
        val ciphertext = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
        return JSONObject().apply {
            put("format", "v2tun-encrypted-config")
            put("version", VERSION)
            put("aad", associatedData)
            put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            put("data", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
        }.toString()
    }

    fun decrypt(envelope: String): String {
        val json = JSONObject(envelope)
        require(json.optString("format") == "v2tun-encrypted-config") { "Unsupported encrypted config format" }
        require(json.optInt("version") == VERSION) { "Unsupported encrypted config version" }
        val aad = json.optString("aad", "V2-TUN")
        val iv = Base64.decode(json.getString("iv"), Base64.DEFAULT)
        require(iv.size == IV_BYTES) { "Invalid encrypted config IV" }
        val data = Base64.decode(json.getString("data"), Base64.DEFAULT)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aad.toByteArray(StandardCharsets.UTF_8))
        return String(cipher.doFinal(data), StandardCharsets.UTF_8)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }
}
