package dev.local.ourahealthbridge.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.local.ourahealthbridge.protocol.RawRingEvent

data class HistoryStoreStats(
    val eventCount: Long,
    val nextCursor: UInt,
)

/** App-private, lossless raw-event storage with transactional cursor advancement. */
class HistoryStore(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, SCHEMA_VERSION) {
    override fun onConfigure(database: SQLiteDatabase) {
        database.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE sync_state (
                singleton_id INTEGER PRIMARY KEY CHECK (singleton_id = 1),
                next_cursor INTEGER NOT NULL,
                last_sync_unix INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE TABLE raw_event (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                tag INTEGER NOT NULL,
                ring_timestamp INTEGER NOT NULL,
                body BLOB NOT NULL,
                captured_unix INTEGER NOT NULL,
                UNIQUE(tag, ring_timestamp, body)
            )
            """.trimIndent(),
        )
        database.execSQL("CREATE INDEX raw_event_time_tag ON raw_event(ring_timestamp, tag)")
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("No history database migration exists from $oldVersion to $newVersion")
    }

    fun cursor(): UInt = readableDatabase.rawQuery(
        "SELECT next_cursor FROM sync_state WHERE singleton_id = 1",
        null,
    ).use { cursor ->
        if (cursor.moveToFirst()) cursor.getLong(0).toUInt() else 0u
    }

    /** Inserts a complete batch and advances its cursor atomically. */
    fun commitBatch(events: List<RawRingEvent>, nextCursor: UInt): Int {
        require(events.isNotEmpty()) { "A cursor must not advance for an empty batch" }
        val database = writableDatabase
        var inserted = 0
        database.beginTransaction()
        try {
            val capturedUnix = System.currentTimeMillis() / 1_000L
            events.forEach { event ->
                val values = ContentValues().apply {
                    put("tag", event.tag)
                    put("ring_timestamp", event.ringTimestampDeciseconds.toLong())
                    put("body", event.body)
                    put("captured_unix", capturedUnix)
                }
                if (database.insertWithOnConflict(
                        "raw_event",
                        null,
                        values,
                        SQLiteDatabase.CONFLICT_IGNORE,
                    ) != -1L
                ) {
                    inserted++
                }
            }
            val state = ContentValues().apply {
                put("singleton_id", 1)
                put("next_cursor", nextCursor.toLong())
                put("last_sync_unix", capturedUnix)
            }
            database.insertWithOnConflict("sync_state", null, state, SQLiteDatabase.CONFLICT_REPLACE)
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        return inserted
    }

    fun stats(): HistoryStoreStats {
        val count = readableDatabase.rawQuery("SELECT COUNT(*) FROM raw_event", null).use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }
        return HistoryStoreStats(count, cursor())
    }

    /** Loads raw events for in-process re-decoding; callers must not log or export bodies. */
    fun loadRawEvents(): List<RawRingEvent> = readableDatabase.rawQuery(
        "SELECT tag, ring_timestamp, body FROM raw_event ORDER BY ring_timestamp, id",
        null,
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    RawRingEvent(
                        tag = cursor.getInt(0),
                        ringTimestampDeciseconds = cursor.getLong(1).toUInt(),
                        body = cursor.getBlob(2),
                    ),
                )
            }
        }
    }

    /** Loads only selected event families for private, in-process diagnostics. */
    fun loadRawEventsForTags(tags: Set<Int>): List<RawRingEvent> {
        if (tags.isEmpty()) return emptyList()
        val orderedTags = tags.sorted()
        val placeholders = orderedTags.joinToString(",") { "?" }
        return readableDatabase.rawQuery(
            "SELECT tag, ring_timestamp, body FROM raw_event " +
                "WHERE tag IN ($placeholders) ORDER BY ring_timestamp, id",
            orderedTags.map(Int::toString).toTypedArray(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        RawRingEvent(
                            tag = cursor.getInt(0),
                            ringTimestampDeciseconds = cursor.getLong(1).toUInt(),
                            body = cursor.getBlob(2),
                        ),
                    )
                }
            }
        }
    }

    /** Counts selected event families without loading or exposing their bodies. */
    fun countRawEventsByTags(tags: Set<Int>): Map<Int, Long> {
        if (tags.isEmpty()) return emptyMap()
        val orderedTags = tags.sorted()
        val placeholders = orderedTags.joinToString(",") { "?" }
        return readableDatabase.rawQuery(
            "SELECT tag, COUNT(*) FROM raw_event WHERE tag IN ($placeholders) GROUP BY tag",
            orderedTags.map(Int::toString).toTypedArray(),
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) put(cursor.getInt(0), cursor.getLong(1))
            }
        }
    }

    private companion object {
        const val DATABASE_NAME = "ring-history-v1.db"
        const val SCHEMA_VERSION = 1
    }
}
