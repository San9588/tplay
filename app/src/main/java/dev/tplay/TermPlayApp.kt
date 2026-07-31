package dev.tplay

import android.app.Application
import dev.tplay.core.AppContainer
import dev.tplay.player.PlayerConnection

class TermPlayApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        PlayerConnection.init(this)
    }
}
