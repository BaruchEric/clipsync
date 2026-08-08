package ca.beric.clipsync.core

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import ca.beric.clipsync.db.ClipsyncDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

class ClipRepository(
    private val db: ClipsyncDb,
    private val cap: Int = 100,
    private val writeContext: CoroutineContext = Dispatchers.Default,
) {
    private val queries get() = db.historyQueries

    /** Returns false when [text] equals the latest entry (poll echo / duplicate). */
    suspend fun record(deviceId: String, text: String, nowMs: Long): Boolean =
        withContext(writeContext) {
            if (latest()?.content == text) return@withContext false
            queries.transaction {
                queries.insert(deviceId, "text", text, nowMs)
                queries.trimToCap(cap.toLong())
            }
            true
        }

    fun observeHistory(): Flow<List<ClipEntry>> =
        queries.selectAll(::ClipEntry).asFlow().mapToList(writeContext)

    fun latest(): ClipEntry? = queries.latest(::ClipEntry).executeAsOneOrNull()
}
