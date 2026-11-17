package com.libreplayer.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `catalog_tracks` (`id` TEXT NOT NULL, `assetKey` TEXT NOT NULL, `metadata` TEXT NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_catalog_tracks_assetKey` ON `catalog_tracks` (`assetKey`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `catalog_playlist_entries` (`playlistId` INTEGER NOT NULL, `trackId` TEXT NOT NULL, `position` INTEGER NOT NULL, `addedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`playlistId`, `trackId`), FOREIGN KEY(`playlistId`) REFERENCES `playlists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`trackId`) REFERENCES `catalog_tracks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_catalog_playlist_entries_playlistId` ON `catalog_playlist_entries` (`playlistId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_catalog_playlist_entries_trackId` ON `catalog_playlist_entries` (`trackId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `downloads` (`assetKey` TEXT NOT NULL, `state` TEXT NOT NULL, `attemptId` TEXT NOT NULL, `attempts` INTEGER NOT NULL, `bytesDownloaded` INTEGER NOT NULL, `totalBytes` INTEGER, `error` TEXT, `evidence` TEXT, `md5` TEXT, `durationMs` INTEGER NOT NULL, `mimeType` TEXT, PRIMARY KEY(`assetKey`))")
    }
}
