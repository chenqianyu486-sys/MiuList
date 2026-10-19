package com.example.data.repository

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * 给 [android.content.ContentResolver] 喂真实图片字节的假 provider。
 *
 * 关键点：**每次 openInputStream 都要重新开一条新流**。
 * 导入流程会把同一个 Uri 打开三次（读尺寸 / 读 EXIF / 真正解码），
 * 复用一个流的话第二次就读到 EOF，后面全崩。
 */
class FakeImageProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.example.test.fakeimage"

        @Volatile
        @JvmStatic
        var file: File? = null
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val target = file ?: return null
        return ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "image/png"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
