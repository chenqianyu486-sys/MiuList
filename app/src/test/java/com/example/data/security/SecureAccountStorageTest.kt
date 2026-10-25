package com.example.data.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecureAccountStorageTest {

    private lateinit var context: Context
    private lateinit var storage: SecureAccountStorage

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // 单测环境没有 AndroidKeyStore provider（探针实测：
        // KeyStore.getInstance("AndroidKeyStore") → KeyStoreException: not found），
        // 所以这里**显式**注入软件密钥，而不是靠生产路径里的隐式兜底。
        storage = SecureAccountStorage(
            context,
            prefName = "test_secure_account_prefs",
            masterKeyProvider = SecureAccountStorage.softwareKeyProvider()
        )
        storage.clearCredentials()
    }

    @Test
    fun `save credentials encrypts and retrieves decrypted strings correctly`() {
        assertFalse(storage.hasSavedCredentials())
        assertNull(storage.getStudentId())
        assertNull(storage.getPassword())

        storage.saveCredentials("3190101234", "test_secret_pass_123", remember = true)

        assertTrue(storage.hasSavedCredentials())
        assertTrue(storage.isRememberCredentials())
        assertEquals("3190101234", storage.getStudentId())
        assertEquals("test_secret_pass_123", storage.getPassword())
        assertEquals("3190****34", storage.getMaskedStudentId())
    }

    @Test
    fun `save credentials with remember false does not store encrypted credentials`() {
        storage.saveCredentials("3190101234", "temp_pass", remember = false)

        assertFalse(storage.hasSavedCredentials())
        assertFalse(storage.isRememberCredentials())
        assertNull(storage.getStudentId())
        assertNull(storage.getPassword())
        assertEquals("3190****34", storage.getMaskedStudentId())
    }

    @Test
    fun `clearCredentials wipes encrypted items and masked id`() {
        storage.saveCredentials("3190101234", "password", remember = true)
        assertTrue(storage.hasSavedCredentials())

        storage.clearCredentials()

        assertFalse(storage.hasSavedCredentials())
        assertNull(storage.getStudentId())
        assertNull(storage.getPassword())
        assertNull(storage.getMaskedStudentId())
    }

    @Test
    fun `last sync timestamp and term persist correctly`() {
        assertNull(storage.getLastSyncTimestamp())
        assertNull(storage.getLastTerm())

        val now = 1790000000000L
        storage.setLastSyncTimestamp(now)
        storage.saveLastTerm("2026-2027", 0)

        assertEquals(now, storage.getLastSyncTimestamp())
        assertEquals("2026-2027" to 0, storage.getLastTerm())
    }

    @Test
    fun `masking student id preserves safety and privacy`() {
        assertEquals("3190****34", SecureAccountStorage.maskStudentId("3190101234"))
        assertEquals("12***67", SecureAccountStorage.maskStudentId("1234567"))
        assertEquals("1234", SecureAccountStorage.maskStudentId("1234"))
    }

    /**
     * 拿不到硬件密钥时**必须报错**，不能静默换一把软件密钥。
     *
     * 静默降级换来的那把密钥只活在进程内存里：密文照样落盘、界面照样显示
     * 「已记住密码」，而进程一死就再也解不开 —— 用户看到的只是「又要重新登录」，
     * 没有任何地方说明「这次其实没用上硬件加密」。
     */
    @Test
    fun `save credentials throws when the hardware key is unavailable`() {
        val broken = SecureAccountStorage(
            context,
            prefName = "test_broken_key_prefs",
            masterKeyProvider = {
                throw SecureStorageUnavailableException("设备加密密钥已失效，请重新登录以重新绑定账号")
            }
        )
        broken.clearCredentials()

        val error = runCatching {
            broken.saveCredentials("3190101234", "secret", remember = true)
        }.exceptionOrNull()

        assertTrue(error is SecureStorageUnavailableException)
        // 写失败时**不能留下半截状态**：加密没成功，明文标记也就不该落盘。
        assertFalse(broken.hasSavedCredentials())
        assertNull(broken.getStudentId())
    }

    /**
     * 密文在、但解不开时**不算「已保存」**。
     *
     * 这一条钉的是「界面说的」和「同步时拿到的」必须一致：
     * 设备密钥被系统作废（改锁屏密码 / 重置凭据）之后，旧密文就成了垃圾，
     * 而 prefs 里那两行键还在 —— 只看「键在不在」会说「已记住密码」，
     * 实际同步时什么都取不出来。
     */
    @Test
    fun `credentials that cannot be decrypted are not reported as saved`() {
        storage.saveCredentials("3190101234", "password", remember = true)
        assertTrue(storage.hasSavedCredentials())

        // 直接把密文换成垃圾（等价于密钥被换掉/被作废）。
        context.getSharedPreferences("test_secure_account_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("enc_student_id", "not-a-valid-ciphertext")
            .putString("enc_password", "not-a-valid-ciphertext")
            .apply()

        assertFalse(storage.hasSavedCredentials())
        assertNull(storage.getStudentId())
        assertNull(storage.getPassword())
    }
}
