package ca.beric.clipsync.core

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import ca.beric.clipsync.db.ClipsyncDb
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClipRepositoryTest {

    private fun newRepo(cap: Int = 100): ClipRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ClipsyncDb.Schema.create(driver)
        return ClipRepository(ClipsyncDb(driver), cap = cap)
    }

    @Test
    fun recordThenLatestRoundTrips() = runTest {
        val repo = newRepo()
        assertTrue(repo.record("local", "hello", nowMs = 111))
        val latest = repo.latest()!!
        assertEquals("hello", latest.content)
        assertEquals("local", latest.deviceId)
        assertEquals("text", latest.kind)
        assertEquals(111, latest.createdAtMs)
    }

    @Test
    fun historyIsNewestFirst() = runTest {
        val repo = newRepo()
        repo.record("local", "one", 1)
        repo.record("local", "two", 2)
        val items = repo.observeHistory().first()
        assertEquals(listOf("two", "one"), items.map { it.content })
    }

    @Test
    fun consecutiveDuplicateIsSkipped() = runTest {
        val repo = newRepo()
        assertTrue(repo.record("local", "same", 1))
        assertFalse(repo.record("local", "same", 2))
        assertEquals(1, repo.observeHistory().first().size)
    }

    @Test
    fun capTrimsOldestBeyond100() = runTest {
        val repo = newRepo(cap = 100)
        repeat(105) { repo.record("local", "item-$it", it.toLong()) }
        val items = repo.observeHistory().first()
        assertEquals(100, items.size)
        assertEquals("item-104", items.first().content)
        assertEquals("item-5", items.last().content)
    }
}
