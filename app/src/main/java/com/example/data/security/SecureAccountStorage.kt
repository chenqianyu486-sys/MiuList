package com.example.data.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.example.data.source.zju.ZjuFailureKind
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 设备安全存储不可用。
 *
 * 与「没有凭据」是两回事：**没有凭据**是正常状态（用户没勾「记住」），
 * 而这个异常说的是「想保存，但这台机器现在没法安全地存」——
 * 典型场景是硬件密钥被系统作废（改锁屏密码、重置凭据、生物识别变更）。
 *
 * 之所以要把它变成一个**会被报出来的异常**而不是静默降级：
 * 静默降级换来的那把软件密钥只活在进程内存里，进程一死密文就再也解不开 ——
 * 于是界面显示「已记住密码」，下次启动却什么都取不出来。
 * 那是一种**看起来加密了、实际上随时会失效**的状态，比明文更让人放心（更糟）。
 */
class SecureStorageUnavailableException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * 安全凭据与缓存元数据存储。
 *
 * ## 安全保障
 * - 账号与密码使用硬件级 Android KeyStore (AES-256-GCM) 加密存储，密文与 IV 存入私有 SharedPreferences。
 * - 硬件主密钥保存在 Android TEE / KeyStore 安全硬件中，无法被提取导出。
 * - 凭据绝不明文落盘，也不进入日志、代码版本库、错误堆栈。
 *
 * ## 拿不到硬件密钥时**不降级**
 *
 * 早先这里是 `catch (_: Throwable)` → 换成一把进程内的软件 AES 密钥。
 * 那样做的问题不在于「用了软件密钥」，而在于**它无声无息**：
 * 密文照样落盘、界面照样显示「已记住密码」，而那把密钥随进程一起消失 ——
 * 于是凭据在某次重启后静默失效，用户只看到「又要重新登录」，
 * 完全看不出原因，也没有任何地方提示「这次其实没用上硬件加密」。
 *
 * 现在改成：**拿不到硬件密钥就是拿不到**，抛 [SecureStorageUnavailableException]，
 * 由 [com.example.data.sync.ScheduleSyncManager] 如实报给用户
 * （课表已经同步成功，只是凭据没存上，界面必须说得清是哪一种）。
 *
 * 测试环境没有 AndroidKeyStore provider，改为**显式注入**软件密钥
 * （[softwareKeyProvider]）—— 降级路径从「隐式兜底」变成「明写出来的选择」。
 *
 * 类与 [saveCredentials] 是 `open` 的，**唯一目的是留一个测试接缝**：
 * 「课表已经 commit 进 Room、随后保存凭据时炸了」这个时序决定了失败提示能不能说
 * 「本地原样保留」，而这个时序没法靠桩掉 ZJU 接口造出来 —— 桩一炸就发生在 commit 之前。
 * 不开这个口的话，那条保证只能靠读代码相信。
 */
open class SecureAccountStorage(
    context: Context,
    prefName: String = PREF_NAME,
    /**
     * 主密钥来源。默认走 Android KeyStore；单测用 [softwareKeyProvider] 显式注入。
     *
     * 抽成参数而不是在类里 `catch` 兜底，是为了让「生产用硬件密钥、测试用软件密钥」
     * 这件事**写在构造点上**，而不是藏在一条异常分支里。
     */
    private val masterKeyProvider: () -> SecretKey = { AndroidKeyStoreMasterKey.obtain() }
) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences(prefName, Context.MODE_PRIVATE)

    private val masterKey: SecretKey by lazy { masterKeyProvider() }

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
        if (!isRememberCredentials()) return false
        if (!prefs.contains(KEY_ENC_STUDENT_ID) || !prefs.contains(KEY_ENC_PASSWORD)) return false
        // 密文在、但**解不开**（设备密钥被系统作废、换了密钥、文件被改过）时，
        // 不算「已保存」。否则界面会说「已记住密码」、同步时却取不出来 ——
        // 两处自相矛盾，而用户看不出是哪一处在骗他。
        return getStudentId() != null && getPassword() != null
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

    // ── 局域网同步的凭据 ────────────────────────────────────────

    /**
     * 局域网同步密码（可选）。
     *
     * 它会**混进配对协商出的共享密钥**再派生密钥，所以只拿到公钥或抓到密文
     * 都不足以解密，还得知道这个密码。它必须能**还原**（配对时要拿它参与派生），
     * 所以不能只存哈希 —— 于是走同一套 Keystore AES-GCM 加密落盘。
     */
    @Synchronized
    fun saveSyncPasscode(passcode: String) {
        val editor = prefs.edit()
        if (passcode.isBlank()) {
            editor.remove(KEY_ENC_SYNC_PASSCODE)
        } else {
            editor.putString(KEY_ENC_SYNC_PASSCODE, encryptString(passcode))
        }
        editor.apply()
    }

    @Synchronized
    fun getSyncPasscode(): String? {
        val enc = prefs.getString(KEY_ENC_SYNC_PASSCODE, null) ?: return null
        return decryptString(enc)
    }

    @Synchronized
    fun clearSyncPasscode() {
        prefs.edit().remove(KEY_ENC_SYNC_PASSCODE).apply()
    }

    /**
     * 已配对设备的密钥（三把子密钥打包成一段 base64）。
     *
     * **它等价于配对 secret 本身** —— 拿到它就能冒充那台设备，
     * 所以和同步密码一样走加密落盘。
     */
    @Synchronized
    fun saveSyncDeviceKeys(deviceId: String, blob: String) {
        prefs.edit().putString(KEY_SYNC_DEVICE_PREFIX + deviceId, encryptString(blob)).apply()
    }

    @Synchronized
    fun getSyncDeviceKeys(deviceId: String): String? {
        val enc = prefs.getString(KEY_SYNC_DEVICE_PREFIX + deviceId, null) ?: return null
        return decryptString(enc)
    }

    @Synchronized
    fun allSyncDeviceKeys(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        prefs.all.forEach { (key, value) ->
            if (key.startsWith(KEY_SYNC_DEVICE_PREFIX) && value is String) {
                val deviceId = key.removePrefix(KEY_SYNC_DEVICE_PREFIX)
                decryptString(value)?.let { out[deviceId] = it }
            }
        }
        return out
    }

    @Synchronized
    fun removeSyncDeviceKeys(deviceId: String) {
        prefs.edit().remove(KEY_SYNC_DEVICE_PREFIX + deviceId).apply()
    }

    companion object {
        const val PREF_NAME = "miulist_secure_credentials"
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"

        private const val KEY_ENC_STUDENT_ID = "enc_student_id"
        private const val KEY_ENC_PASSWORD = "enc_password"
        /** 局域网同步密码（加密落盘）。 */
        private const val KEY_ENC_SYNC_PASSCODE = "enc_sync_passcode"
        /** 已配对设备的密钥（加密落盘），前缀 + deviceId。 */
        private const val KEY_SYNC_DEVICE_PREFIX = "sync_device_"
        private const val KEY_REMEMBER = "pref_remember_credentials"
        private const val KEY_MASKED_ID = "pref_masked_student_id"
        private const val KEY_LAST_SYNC_TIME = "pref_last_sync_timestamp"
        private const val KEY_LAST_SYNC_STATUS = "pref_last_sync_status"
        private const val KEY_LAST_SYNC_ATTEMPT_TIME = "pref_last_sync_attempt_time"
        private const val KEY_LAST_SYNC_FAILURE_KIND = "pref_last_sync_failure_kind"
        private const val KEY_LAST_TERM_YEAR = "pref_last_term_year"
        private const val KEY_LAST_TERM_CODE = "pref_last_term_code"

        /**
         * 单测用的软件密钥来源。
         *
         * **只有测试该用它。** 它生成的那把 AES 密钥只活在进程内存里，
         * 进程一死，密文就再也解不开 —— 也就是说它只能支撑「进程内」的场景。
         *
         * 之所以把它显式化（而不是在生产路径里偷偷兜底），就是要让
         * 「生产用硬件密钥」这件事在代码里一眼看得出来。
         */
        fun softwareKeyProvider(): () -> SecretKey {
            val lock = Any()
            var key: SecretKey? = null
            return {
                synchronized(lock) {
                    key ?: KeyGenerator.getInstance("AES").apply { init(256) }
                        .generateKey().also { key = it }
                }
            }
        }

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

/**
 * Android KeyStore 里的那把主密钥。
 *
 * 抽成独立对象是为了让「取密钥」这件事**只有一个失败形态**：
 * 拿不到就抛 [SecureStorageUnavailableException]，绝不换一把软件密钥蒙混过去。
 *
 * 三种失败各有各的话，因为用户能做的事不一样：
 * - provider 都没有 → 这台设备/环境根本不支持硬件密钥；
 * - 密钥被系统作废（`UnrecoverableKeyException`）→ 改过锁屏密码或重置过凭据，
 *   **重新登录一次就能恢复**，所以文案要指向「重新登录」而不是「设备坏了」；
 * - 其它异常（`ProviderException` 等）→ 设备安全存储本身出问题。
 */
internal object AndroidKeyStoreMasterKey {

    private const val ANDROID_KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "MiuListSecureAccountMasterKey"

    fun obtain(): SecretKey {
        val keyStore = try {
            KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER)
        } catch (e: Exception) {
            throw SecureStorageUnavailableException(
                "本机没有可用的硬件密钥库，无法安全保存账号密码",
                e
            )
        }

        return try {
            keyStore.load(null)
            if (!keyStore.containsAlias(KEY_ALIAS)) createKey(keyStore)
            (keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        } catch (e: UnrecoverableKeyException) {
            throw SecureStorageUnavailableException(
                "设备加密密钥已失效，请重新登录以重新绑定账号",
                e
            )
        } catch (e: Exception) {
            throw SecureStorageUnavailableException(
                "设备安全存储不可用，无法安全保存账号密码",
                e
            )
        }
    }

    private fun createKey(keyStore: KeyStore) {
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
}
