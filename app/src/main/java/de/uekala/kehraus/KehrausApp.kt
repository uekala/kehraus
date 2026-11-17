package de.uekala.kehraus

import android.app.Application
import de.uekala.kehraus.bg.CleanWorker

class KehrausApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CleanWorker.sync(this)
    }
}
