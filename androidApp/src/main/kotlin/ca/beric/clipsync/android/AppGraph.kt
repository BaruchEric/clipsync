package ca.beric.clipsync.android

import android.content.Context
import ca.beric.clipsync.core.ClipRepository
import ca.beric.clipsync.db.ClipsyncDb
import ca.beric.clipsync.db.DriverFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Process-wide singletons. Initialized from [ClipsyncApp] and defensively from services. */
object AppGraph {
    lateinit var repo: ClipRepository
        private set

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun init(context: Context) {
        if (::repo.isInitialized) return
        repo = ClipRepository(
            ClipsyncDb(DriverFactory(context.applicationContext).createDriver()),
            writeContext = Dispatchers.IO,
        )
    }
}
