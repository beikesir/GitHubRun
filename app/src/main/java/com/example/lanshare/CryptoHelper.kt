package com.example.lanshare

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 端到端加密：PBKDF2 派生密钥 + AES-GCM 加密
 *
 * 与浏览器扩展 crypto.js 严格对齐，保证跨端互通：
 *   · salt      = SHA256("lanshare:" + 频道号)
 *   · 派生算法  = PBKDF2WithHmacSHA256，100000 次迭代，256 位
 *   · 加密算法  = AES/GCM/NoPadding，12 字节 IV，128 位认证标签
 *
 * 中继只看到 nonce 与密文，无法解密正文。
 */
object CryptoHelper {

    private const val ITERATIONS = 100000
    private const val KEY_BITS = 256
    private const val SALT_PREFIX = "lanshare:"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    // 频道+口令 -> 派生密钥（避免每条消息重复派生，PBKDF2 很耗时）
    private val keyCache = HashMap<String, SecretKeySpec>()

    private fun saltFor(channel: String): ByteArray =
        MessageDigest.getInstance("SHA-256")
            .digest((SALT_PREFIX + channel).toByteArray(Charsets.UTF_8))

    @Synchronized
    private fun keyFor(channel: String, password: String): SecretKeySpec {
        val cacheKey = "$channel|$password"
        keyCache[cacheKey]?.let { return it }

        val spec = PBEKeySpec(password.toCharArray(), saltFor(channel), ITERATIONS, KEY_BITS)
        val derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        val key = SecretKeySpec(derived, "AES")
        keyCache[cacheKey] = key
        return key
    }

    /** 口令变更时清除该频道的密钥缓存 */
    @Synchronized
    fun clearCache(channel: String? = null) {
        if (channel == null) {
            keyCache.clear()
            return
        }
        // 不用 keys.removeIf：它是 Java 8 默认方法（平台类型 + @RequiresApi(24)），
        // 这里先快照再删除，语义等价且无兼容性风险
        val prefix = "$channel|"
        keyCache.keys.filter { it.startsWith(prefix) }.forEach { keyCache.remove(it) }
    }

    /**
     * 加密内层 JSON 字符串
     * @return Pair(nonceBase64, cipherBase64)，失败返回 null
     */
    fun encrypt(channel: String, password: String, plainJson: String): Pair<String, String>? =
        try {
            val key = keyFor(channel, password)
            val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            }
            val cipherText = cipher.doFinal(plainJson.toByteArray(Charsets.UTF_8))
            Pair(
                Base64.encodeToString(iv, Base64.NO_WRAP),
                Base64.encodeToString(cipherText, Base64.NO_WRAP)
            )
        } catch (e: Exception) {
            null
        }

    /**
     * 解密，还原内层 JSON 字符串
     * @return 明文；口令不匹配或数据损坏时返回 null（GCM 认证失败）
     */
    fun decrypt(channel: String, password: String, nonceB64: String, dataB64: String): String? =
        try {
            val key = keyFor(channel, password)
            val iv = Base64.decode(nonceB64, Base64.NO_WRAP)
            val cipherText = Base64.decode(dataB64, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            }
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (e: Exception) {
            // GCM 标签校验失败 = 口令错误 / 密文被篡改，不猜测内容
            null
        }
}
