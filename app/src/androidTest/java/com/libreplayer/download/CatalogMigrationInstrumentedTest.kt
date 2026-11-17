package com.libreplayer.download

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.libreplayer.data.database.AppDatabase
import com.libreplayer.data.database.MIGRATION_1_2
import com.libreplayer.data.database.MIGRATION_2_3
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CatalogMigrationInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun versionTwoPreservesEveryLegacyRowAndProvenance() = migrate(2)
    @Test fun versionOneChainsBothMigrationsWithoutChangingReferences() = migrate(1)

    private fun migrate(version: Int) = runBlocking {
        val name = "catalog-migration-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        val schema = JSONObject(instrumentation.context.assets.open("com.libreplayer.data.database.AppDatabase/$version.json")
            .bufferedReader().use { it.readText() }).getJSONObject("database")
        val tables = mutableListOf<String>()
        val before = SQLiteDatabase.openOrCreateDatabase(path, null).use { sqlite ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                tables += table
                sqlite.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices")
                if (indices != null) {
                    for (j in 0 until indices.length()) sqlite.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) sqlite.execSQL(setup.getString(i))
            sqlite.execSQL("INSERT INTO songs VALUES ('media:42', 'MEDIA_STORE', 'content://media/external_primary/audio/media/42', 'Title', 'Artist', 'Album', 1000, 1, 1, 2020, 10, 11, 'song.mp3', 'Music/', 'audio/mpeg', NULL, 1, 'title', 'artist', 'album')")
            sqlite.execSQL("INSERT INTO albums VALUES ('album', 'Album', 'Artist', 1, 1000, NULL, 'album')")
            sqlite.execSQL("INSERT INTO artists VALUES ('artist', 'Artist', 1, 1000, NULL, 'artist')")
            sqlite.execSQL("INSERT INTO imported_roots VALUES ('content://test.provider/tree/a', 'Root', 10)")
            sqlite.execSQL("INSERT INTO playlists VALUES (17, 'Original', 10, 11)")
            sqlite.execSQL("INSERT INTO playlist_songs VALUES (17, 'media:42', 7, 12)")
            sqlite.execSQL("INSERT INTO playlist_songs VALUES (17, 'missing:retained', 9, 13)")
            sqlite.execSQL("INSERT INTO recently_played VALUES ('media:42', 14)")
            if (version == 2) {
                sqlite.execSQL("INSERT INTO library_sources VALUES ('source', 'MEDIA_STORE', 'media', 'external_primary', 3, 'version', 90, 100)")
                sqlite.execSQL("INSERT INTO song_sources VALUES ('source', 3, '42', 'media:42', 'content://media/external_primary/audio/media/42', 'physical-key', 1)")
                sqlite.execSQL("INSERT INTO legacy_song_protection VALUES ('missing:retained', 'unscoped')")
            }
            sqlite.version = version
            snapshot(tables) { sqlite.rawQuery(it, null) }
        }
        val database = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
        try {
            assertEquals(3, database.openHelper.writableDatabase.version)
            assertEquals(before, snapshot(tables) { database.openHelper.writableDatabase.query(it) })
            assertTrue(database.catalogDao().entries().isEmpty())
            assertTrue(database.catalogDao().downloads().isEmpty())
            assertEquals(listOf("media:42", "missing:retained"), database.playlistDao().getPlaylistEntriesNow(17).map { it.songId })
            assertTrue(database.songDao().getAllSongs().single().isFavorite)
            database.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    private fun snapshot(tables: List<String>, query: (String) -> android.database.Cursor) = tables.associateWith { table ->
        query("SELECT * FROM `$table` ORDER BY 1, 2").use { cursor ->
            buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) }) }
        }
    }
}
