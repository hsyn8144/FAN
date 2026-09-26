package fan.superai

import android.app.Application
import fan.superai.data.Settings

class FanApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Settings.init(this)
        EngineHost.init(this)
    }
}
