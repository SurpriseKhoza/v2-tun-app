package io.surprise.vpn.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Base64CodecTest {

    @Test
    fun encodesAndDecodesStandardVectors() {
        assertEquals("", Base64Codec.encodeStandard(ByteArray(0)))
        assertEquals("Zg==", Base64Codec.encodeStandard("f".toByteArray()))
        assertEquals("Zm8=", Base64Codec.encodeStandard("fo".toByteArray()))
        assertEquals("Zm9v", Base64Codec.encodeStandard("foo".toByteArray()))
        assertEquals("Zm9vYg==", Base64Codec.encodeStandard("foob".toByteArray()))
        assertEquals("Zm9vYmE=", Base64Codec.encodeStandard("fooba".toByteArray()))
        assertEquals("Zm9vYmFy", Base64Codec.encodeStandard("foobar".toByteArray()))
        assertEquals("foobar", Base64Codec.decodeToString("Zm9vYmFy"))
        assertEquals("foobar", Base64Codec.decodeToString("Zm9vYmFy===="))
    }

    @Test
    fun handlesUrlSafeAlphabet() {
        val bytes = byteArrayOf(0xFB.toByte(), 0xFF.toByte(), 0xBF.toByte())
        val encoded = Base64Codec.encodeUrlSafe(bytes)
        assertEquals("-_-_", encoded)
        assertEquals(bytes.toList(), Base64Codec.decode(encoded)?.toList())
    }

    @Test
    fun rejectsInvalidCharacters() {
        assertNull(Base64Codec.decode("not base64!"))
    }
}
