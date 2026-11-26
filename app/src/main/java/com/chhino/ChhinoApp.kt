package com.chhino

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point for Chhino.
 * Initializes Hilt dependency injection graph for offline-first driver assistance.
 */
@HiltAndroidApp
class ChhinoApp : Application()
