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
        storage = SecureAccountStorage(context, prefName = "test_secure_account_prefs")
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
    fun `data source mode persists across calls`() {
        assertEquals(DataSourceMode.DEMO, storage.getDataSourceMode())

        storage.setDataSourceMode(DataSourceMode.REAL)
        assertEquals(DataSourceMode.REAL, storage.getDataSourceMode())

        storage.setDataSourceMode(DataSourceMode.DEMO)
        assertEquals(DataSourceMode.DEMO, storage.getDataSourceMode())
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
}
