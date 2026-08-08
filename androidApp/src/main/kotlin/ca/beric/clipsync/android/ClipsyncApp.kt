package ca.beric.clipsync.android

import android.app.Application

class ClipsyncApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
    }
}
