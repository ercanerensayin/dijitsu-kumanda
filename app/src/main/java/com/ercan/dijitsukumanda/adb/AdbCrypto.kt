package com.ercan.dijitsukumanda.adb

import android.content.Context
import android.util.Base64
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/** ADB'nin RSA anahtar işlemleri: anahtar üretme/saklama, jeton imzalama, Android biçiminde açık anahtar. */
object AdbCrypto {
    private const val KEY_BITS = 2048
    private const val KEY_BYTES = KEY_BITS / 8

    // SHA-1 DigestInfo öneki (adbd, jetonu SHA-1 özeti gibi doğrular)
    private val SHA1_PREFIX = byteArrayOf(
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    )

    fun loadOrCreate(ctx: Context): KeyPair {
        val privFile = File(ctx.filesDir, "adbkey")
        val pubFile = File(ctx.filesDir, "adbkey.pub")
        if (privFile.exists() && pubFile.exists()) {
            try {
                val kf = KeyFactory.getInstance("RSA")
                val priv = kf.generatePrivate(PKCS8EncodedKeySpec(privFile.readBytes()))
                val pub = kf.generatePublic(X509EncodedKeySpec(pubFile.readBytes()))
                return KeyPair(pub, priv)
            } catch (_: Exception) {
            }
        }
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(KEY_BITS)
        val kp = gen.generateKeyPair()
        privFile.writeBytes(kp.private.encoded)
        pubFile.writeBytes(kp.public.encoded)
        return kp
    }

    /** PKCS#1 v1.5 (tip 1) dolgulu ham RSA imzası. */
    fun sign(kp: KeyPair, token: ByteArray): ByteArray {
        val priv = kp.private as RSAPrivateKey
        val em = ByteArray(KEY_BYTES)
        em[0] = 0x00
        em[1] = 0x01
        val tLen = SHA1_PREFIX.size + token.size
        val psEnd = KEY_BYTES - tLen - 1
        for (i in 2 until psEnd) em[i] = 0xFF.toByte()
        em[psEnd] = 0x00
        System.arraycopy(SHA1_PREFIX, 0, em, psEnd + 1, SHA1_PREFIX.size)
        System.arraycopy(token, 0, em, psEnd + 1 + SHA1_PREFIX.size, token.size)
        val s = BigInteger(1, em).modPow(priv.privateExponent, priv.modulus)
        return toFixed(s, KEY_BYTES)
    }

    /** adbd'nin beklediği biçim: base64(RSAPublicKey yapısı) + " kullanıcı@cihaz" + NUL */
    fun androidPublicKey(kp: KeyPair): ByteArray {
        val pub = kp.public as RSAPublicKey
        val n = pub.modulus
        val words = KEY_BYTES / 4
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = r32.subtract(n.mod(r32).modInverse(r32))
        val rr = BigInteger.ONE.shiftLeft(KEY_BITS * 2).mod(n)
        val bb = ByteBuffer.allocate(4 + 4 + KEY_BYTES + KEY_BYTES + 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(words)
        bb.putInt(n0inv.toInt())
        for (i in 0 until words) bb.putInt(n.shiftRight(32 * i).mod(r32).toInt())
        for (i in 0 until words) bb.putInt(rr.shiftRight(32 * i).mod(r32).toInt())
        bb.putInt(pub.publicExponent.toInt())
        val b64 = Base64.encodeToString(bb.array(), Base64.NO_WRAP)
        return ("$b64 ercan@DijitsuKumanda\u0000").toByteArray(Charsets.UTF_8)
    }

    private fun toFixed(v: BigInteger, len: Int): ByteArray {
        val raw = v.toByteArray()
        if (raw.size == len) return raw
        val out = ByteArray(len)
        if (raw.size > len) System.arraycopy(raw, raw.size - len, out, 0, len)
        else System.arraycopy(raw, 0, out, len - raw.size, raw.size)
        return out
    }
}
