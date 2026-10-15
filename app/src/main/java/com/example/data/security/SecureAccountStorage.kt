package com.example.data.security

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.example.data.source.zju.ZjuFailureKind
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

enum class DataSourceMode {
    DEMO,
    REAL
}

/**
 * 安全凭据与缓存元数据存储。
 *
 * ## 安全保障
 * - 账号与密码使用硬件级 Android KeyStore (AES-256-GCM) 加密存储，密文与 IV 存入私有 SharedPreferences。
 * - 硬件主密钥保存在 Android TEE / KeyStore 安全硬件中，无法被提取导出。
 * - 凭据绝不明文落盘，也不进入日志、代码版本库、错误堆栈。
 * - 支持在无 AndroidKeyStore 环境（如 JVM 纯单测）下优雅降级至独立内存/软件 AES 密钥，保证测试可靠性。
 *
 * 类与 [saveCredentials] 是 `open` 的，**唯一目的是留一个测试接缝**：
 * 「课表已经 commit 进 Room、随后保存凭据时炸了」这个时序决定了失败提示能不能说
 * 「本地原样保留」，而这个时序没法靠桩掉 ZJU 接口造出来 —— 桩一炸就发生在 commit 之前。
 * 不开这个口的话，那条保证只能靠读代码相信。
 */
open class SecureAccountStorage(
    context: Context,
    prefName: String = PREF_NAME
) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences(prefName, Context.MODE_PRIVATE)

    private val masterKey: SecretKey by lazy { getOrCreateMasterKey() }

    @Synchronized
    open fun saveCredentials(studentId: String, password: String, remember: Boolean) {
        val editor = prefs.edit()
        val maskedId = maskStudentId(studentId)
        editor.putString(KEY_MASKED_ID, maskedId)
        editor.putBoolean(KEY_REMEMBER, remember)

        if (remember && studentId.isNotBlank() && password.isNotBlank()) {
            val encId = encryptString(studentId)
            val encPass = encryptString(password)
            editor.putString(KEY_ENC_STUDENT_ID, encId)
            editor.putString(KEY_ENC_PASSWORD, encPass)
        } else {
            editor.remove(KEY_ENC_STUDENT_ID)
            editor.remove(KEY_ENC_PASSWORD)
        }
        editor.apply()
    }

    @Synchronized
    fun saveMaskedStudentIdOnly(studentId: String) {
        prefs.edit()
            .putString(KEY_MASKED_ID, maskStudentId(studentId))
            .remove(KEY_ENC_STUDENT_ID)
            .remove(KEY_ENC_PASSWORD)
            .putBoolean(KEY_REMEMBER, false)
            .apply()
    }

    @Synchronized
    fun getStudentId(): String? {
        val enc = prefs.getString(KEY_ENC_STUDENT_ID, null) ?: return null
        return decryptString(enc)
    }

    @Synchronized
    fun getPassword(): String? {
        val enc = prefs.getString(KEY_ENC_PASSWORD, null) ?: return null
        return decryptString(enc)
    }

    fun isRememberCredentials(): Boolean = prefs.getBoolean(KEY_REMEMBER, false)

    fun hasSavedCredentials(): Boolean {
        return isRememberCredentials() &&
            prefs.contains(KEY_ENC_STUDENT_ID) &&
            prefs.contains(KEY_ENC_PASSWORD)
    }

    fun getMaskedStudentId(): String? = prefs.getString(KEY_MASKED_ID, null)

    @Synchronized
    fun clearCredentials() {
        prefs.edit()
            .remove(KEY_ENC_STUDENT_ID)
            .remove(KEY_ENC_PASSWORD)
            .remove(KEY_MASKED_ID)
            .putBoolean(KEY_REMEMBER, false)
            .apply()
    }

    fun setDataSourceMode(mode: DataSourceMode) {
        prefs.edit().putString(KEY_DATA_MODE, mode.name).apply()
    }

    fun getDataSourceMode(): DataSourceMode {
        val raw = prefs.getString(KEY_DATA_MODE, null)
        return runCatching { DataSourceMode.valueOf(raw!!) }.getOrDefault(DataSourceMode.DEMO)
    }

    fun setLastSyncTimestamp(timestamp: Long) {
        prefs.edit().putLong(KEY_LAST_SYNC_TIME, timestamp).apply()
    }

    fun getLastSyncTimestamp(): Long? {
        val v = prefs.getLong(KEY_LAST_SYNC_TIME, 0L)
        return if (v > 0L) v else null
    }

    /**
     * 同步成功：记成功时间，并**清掉上一次失败的痕迹**。
     *
     * 清掉是有意为之。「上次失败是网络问题」只在**下一次还没成功**时才有意义；
     * 一旦成功过，再把旧的失败类别挂在界面上就是过期信息。
     */
    fun markSyncSuccess(at: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putLong(KEY_LAST_SYNC_TIME, at)
            .putLong(KEY_LAST_SYNC_ATTEMPT_TIME, at)
            .remove(KEY_LAST_SYNC_FAILURE_KIND)
            .putString(KEY_LAST_SYNC_STATUS, "同步成功")
            .apply()
    }

    /**
     * 同步失败：**不动** [getLastSyncTimestamp]。
     *
     * 这是有意的 —— 上次成功是什么时候，与上次有没有失败是两件事。
     * 把失败的时间写进「上次同步」会让用户以为课表已经被刷新过了。
     * 所以另记 [getLastSyncAttemptTime] 与 [getLastSyncFailureKind]。
     */
    fun markSyncFailure(
        kind: ZjuFailureKind,
        at: Long = System.currentTimeMillis()
    ) {
        prefs.edit()
            .putLong(KEY_LAST_SYNC_ATTEMPT_TIME, at)
            .putString(KEY_LAST_SYNC_FAILURE_KIND, kind.name)
            .putString(KEY_LAST_SYNC_STATUS, "同步失败（${kind.name}）")
            .apply()
    }

    /** 上一次同步**尝试**（不论成败）的时间。 */
    fun getLastSyncAttemptTime(): Long? {
        val v = prefs.getLong(KEY_LAST_SYNC_ATTEMPT_TIME, 0L)
        return if (v > 0L) v else null
    }

    /** 上一次失败的类别。成功过一次之后返回 null —— 那条信息已经过期了。 */
    fun getLastSyncFailureKind(): ZjuFailureKind? {
        val raw = prefs.getString(KEY_LAST_SYNC_FAILURE_KIND, null) ?: return null
        return runCatching { ZjuFailureKind.valueOf(raw) }.getOrNull()
    }

    fun setLastSyncStatus(status: String) {
        prefs.edit().putString(KEY_LAST_SYNC_STATUS, status).apply()
    }

    fun getLastSyncStatus(): String? = prefs.getString(KEY_LAST_SYNC_STATUS, null)

    fun saveLastTerm(year: String, term: Int) {
        prefs.edit()
            .putString(KEY_LAST_TERM_YEAR, year)
            .putInt(KEY_LAST_TERM_CODE, term)
            .apply()
    }

    fun getLastTerm(): Pair<String, Int>? {
        val year = prefs.getString(KEY_LAST_TERM_YEAR, null) ?: return null
        if (!prefs.contains(KEY_LAST_TERM_CODE)) return null
        val code = prefs.getInt(KEY_LAST_TERM_CODE, 0)
        return year to code
    }

    private fun encryptString(plain: String): String {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey)
        val iv = cipher.iv // 12 bytes
        val cipherText = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(1 + iv.size + cipherText.size)
        combined[0] = iv.size.toByte()
        System.arraycopy(iv, 0, combined, 1, iv.size)
        System.arraycopy(cipherText, 0, combined, 1 + iv.size, cipherText.size)
        return Base64.getEncoder().encodeToString(combined)
    }

    private fun decryptString(encryptedBase64: String): String? {
        return runCatching {
            val combined = Base64.getDecoder().decode(encryptedBase64)
            val ivSize = combined[0].toInt() and 0xFF
            val iv = ByteArray(ivSize)
            System.arraycopy(combined, 1, iv, 0, ivSize)
            val cipherTextSize = combined.size - 1 - ivSize
            val cipherText = ByteArray(cipherTextSize)
            System.arraycopy(combined, 1 + ivSize, cipherText, 0, cipherTextSize)

            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, masterKey, spec)
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun getOrCreateMasterKey(): SecretKey {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER)
            keyStore.load(null)
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                val keyGen = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    ANDROID_KEYSTORE_PROVIDER
                )
                val spec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                keyGen.init(spec)
                keyGen.generateKey()
            }
            (keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        } catch (_: Throwable) {
            // JVM 环境 / 单元测试 fallback：使用软件生成密钥
            getOrCreateFallbackJvmKey()
        }
    }

    private fun getOrCreateFallbackJvmKey(): SecretKey {
        synchronized(fallbackLock) {
            fallbackJvmKey?.let { return it }
            val gen = KeyGenerator.getInstance("AES")
            gen.init(256)
            val key = gen.generateKey()
            fallbackJvmKey = key
            return key
        }
    }

    companion object {
        const val PREF_NAME = "miulist_secure_credentials"
        private const val ANDROID_KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "MiuListSecureAccountMasterKey"
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"

        private const val KEY_ENC_STUDENT_ID = "enc_student_id"
        private const val KEY_ENC_PASSWORD = "enc_password"
        private const val KEY_REMEMBER = "pref_remember_credentials"
        private const val KEY_MASKED_ID = "pref_masked_student_id"
        private const val KEY_DATA_MODE = "pref_data_source_mode"
        private const val KEY_LAST_SYNC_TIME = "pref_last_sync_timestamp"
        private const val KEY_LAST_SYNC_STATUS = "pref_last_sync_status"
        private const val KEY_LAST_SYNC_ATTEMPT_TIME = "pref_last_sync_attempt_time"
        private const val KEY_LAST_SYNC_FAILURE_KIND = "pref_last_sync_failure_kind"
        private const val KEY_LAST_TERM_YEAR = "pref_last_term_year"
        private const val KEY_LAST_TERM_CODE = "pref_last_term_code"

        private val fallbackLock = Any()
        @Volatile
        private var fallbackJvmKey: SecretKey? = null

        fun maskStudentId(studentId: String): String {
            val trimmed = studentId.trim()
            if (trimmed.length <= 4) return trimmed
            return if (trimmed.length <= 7) {
                "${trimmed.take(2)}***${trimmed.takeLast(2)}"
            } else {
                "${trimmed.take(4)}****${trimmed.takeLast(2)}"
            }
        }
    }
}
