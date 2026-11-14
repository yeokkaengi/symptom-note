package ai.symptomnote

import ai.symptomnote.service.AutoCaptureEngine
import ai.symptomnote.vehicle.VehicleSignals
import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        VehicleSignals.start(this)
        AutoCaptureEngine.start(this)
    }
}
