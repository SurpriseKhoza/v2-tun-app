package io.surprise.vpn.util

import java.io.ByteArrayOutputStream

/**
 * Small, dependency-free Base64 implementation. java.util.Base64 is API 26+,
 * while V2-TUN supports Android 7.0 (API 24).
 */
object Base64Codec {

    private const val STANDARD = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encodeStandard(bytes: ByteArray): String = encode(bytes, STANDARD, padding = true)

    fun encodeUrlSafe(bytes: ByteArray): String {
        return encode(bytes, STANDARD.replace('+', '-').replace('/', '_'), padding = false)
    }

    fun decode(input: String): ByteArray? {
        val clean = input.trim()
            .replace('-', '+')
            .replace('_', '/')
            .filterNot { it.isWhitespace() }
            .trimEnd('=')
        if (clean.isEmpty()) return ByteArray(0)
        if (clean.length % 4 == 1) return null
        val output = ByteArrayOutputStream((clean.length * 3) / 4)
        var buffer = 0
        var bits = 0
        for (character in clean) {
            val value = STANDARD.indexOf(character)
            if (value < 0) return null
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                output.write((buffer shr bits) and 0xFF)
            }
        }
        return output.toByteArray()
    }

    fun decodeToString(input: String): String? =
        decode(input)?.toString(Charsets.UTF_8)

    private fun encode(bytes: ByteArray, alphabet: String, padding: Boolean): String {
        val output = StringBuilder(((bytes.size + 2) / 3) * 4)
        var index = 0
        while (index < bytes.size) {
            val b0 = bytes[index].toInt() and 0xFF
            val b1 = if (index + 1 < bytes.size) bytes[index + 1].toInt() and 0xFF else 0
            val b2 = if (index + 2 < bytes.size) bytes[index + 2].toInt() and 0xFF else 0
            output.append(alphabet[b0 shr 2])
            output.append(alphabet[((b0 and 0x03) shl 4) or (b1 shr 4)])
            if (index + 1 < bytes.size) {
                output.append(alphabet[((b1 and 0x0F) shl 2) or (b2 shr 6)])
            } else if (padding) {
                output.append('=')
            }
            if (index + 2 < bytes.size) {
                output.append(alphabet[b2 and 0x3F])
            } else if (padding) {
                output.append('=')
            }
            index += 3
        }
        return output.toString()
    }
}
