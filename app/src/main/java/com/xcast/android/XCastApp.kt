package com.xcast.android

import android.app.Application
import com.google.android.gms.cast.framework.CastContext
import com.google.android.material.color.DynamicColors
import java.util.concurrent.Executors

class XCastApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
        CastContext.getSharedInstance(this, Executors.newSingleThreadExecutor())
    }
}
