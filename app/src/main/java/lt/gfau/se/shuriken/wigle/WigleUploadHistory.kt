package lt.gfau.se.shuriken.wigle

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class UploadedFileKey(val name: String, val size: Long)

/** Size is the original CSV byte count, not the gzip attachment size. */
class WigleUploadHistory(context: Context, databaseName: String = "wigle-uploads.db") :
    SQLiteOpenHelper(context, databaseName, null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE uploads (name TEXT NOT NULL, size INTEGER NOT NULL, " +
            "accepted_at INTEGER NOT NULL, PRIMARY KEY(name, size))")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun read(): Set<UploadedFileKey> = readableDatabase.query("uploads", arrayOf("name", "size"),
        null, null, null, null, null).use { cursor ->
        buildSet {
            while (cursor.moveToNext()) add(UploadedFileKey(cursor.getString(0), cursor.getLong(1)))
        }
    }

    fun record(key: UploadedFileKey) {
        check(writableDatabase.insertWithOnConflict("uploads", null, ContentValues().apply {
            put("name", key.name)
            put("size", key.size)
            put("accepted_at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "Could not record upload history." }
    }
}
