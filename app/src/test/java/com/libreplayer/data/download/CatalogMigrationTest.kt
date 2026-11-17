package com.libreplayer.data.download

import androidx.sqlite.db.SupportSQLiteDatabase
import com.libreplayer.data.database.MIGRATION_2_3
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test

class CatalogMigrationTest {
    @Test fun migrationIsStrictlyAdditiveAndHasRoomCompatibleIndicesAndForeignKeys() {
        val database = mockk<SupportSQLiteDatabase>()
        val statements = mutableListOf<String>()
        every { database.execSQL(any()) } answers { statements += firstArg<String>() }
        MIGRATION_2_3.migrate(database)
        assertEquals(6, statements.size)
        assertTrue(statements.all { it.startsWith("CREATE TABLE IF NOT EXISTS") || it.startsWith("CREATE INDEX IF NOT EXISTS") })
        assertTrue(statements.any { it.contains("index_catalog_tracks_assetKey") })
        assertTrue(statements.any { it.contains("index_catalog_playlist_entries_trackId") })
        val entries = statements.single { it.startsWith("CREATE TABLE IF NOT EXISTS `catalog_playlist_entries`") }
        assertTrue(entries.contains("REFERENCES `playlists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE"))
        assertTrue(entries.contains("REFERENCES `catalog_tracks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE"))
    }
}
