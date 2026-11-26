package com.chhino.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chhino.action.AnalyzedOfferState
import com.chhino.action.DriverAssistController
import com.chhino.analytics.EarningsAnalytics
import com.chhino.analytics.RideStatisticsSummary
import com.chhino.capture.CaptureManager
import com.chhino.capture.CaptureState
import com.chhino.data.database.dao.HomeLocationDao
import com.chhino.data.database.dao.RideHistoryDao
import com.chhino.data.database.entities.HomeLocationEntity
import com.chhino.data.database.entities.RideHistoryEntity
import com.chhino.data.preferences.SettingsDataStore
import com.chhino.data.preferences.UserSettings
import com.chhino.diagnose.DiagnosticsManager
import com.chhino.diagnose.HealthCheck
import com.chhino.privacy.PrivacyController
import com.chhino.service.BubbleService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChhinoViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsDataStore: SettingsDataStore,
    private val captureManager: CaptureManager,
    private val driverAssistController: DriverAssistController,
    private val rideHistoryDao: RideHistoryDao,
    private val homeLocationDao: HomeLocationDao,
    private val earningsAnalytics: EarningsAnalytics,
    private val diagnosticsManager: DiagnosticsManager,
    private val privacyController: PrivacyController
) : ViewModel() {

    val settings: StateFlow<UserSettings> = settingsDataStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserSettings())

    val captureState: StateFlow<CaptureState> = captureManager.state

    val latestOffer: StateFlow<AnalyzedOfferState?> = driverAssistController.latestOffer

    val rideHistory: StateFlow<List<RideHistoryEntity>> = rideHistoryDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val homeLocation: StateFlow<HomeLocationEntity?> = homeLocationDao.observeHome()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val analyticsSummary: StateFlow<RideStatisticsSummary> = rideHistory
        .map { earningsAnalytics.summarize(it) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            RideStatisticsSummary(0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, emptyMap())
        )

    private val _diagnostics = MutableStateFlow<List<HealthCheck>>(emptyList())
    val diagnostics: StateFlow<List<HealthCheck>> = _diagnostics.asStateFlow()

    fun refreshDiagnostics() {
        _diagnostics.value = diagnosticsManager.runChecks(homeLocation.value != null)
    }

    fun completeOnboarding() {
        viewModelScope.launch { settingsDataStore.setOnboardingCompleted(true) }
    }

    fun toggleMonitoring(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setMonitoringEnabled(enabled)
            captureManager.setPaused(!enabled)
        }
    }

    fun simulateTestOffer() {
        val sample = com.chhino.capture.RideOfferText(
            platform = "Rapido",
            detectedAt = System.currentTimeMillis(),
            visibleTexts = listOf("Rapido Auto Order", "₹185", "1.2 km", "8.7 km", "22 min", "Connaught Place", "Noida Sector 18", "Accept"),
            normalizedTexts = listOf("Rapido Auto Order", "₹185", "1.2 km", "8.7 km", "22 min", "Connaught Place", "Noida Sector 18", "Accept"),
            sourceNodeMetadata = emptyList(),
            confidence = 0.95f
        )
        captureManager.onOfferCaptured(sample, 1L)
    }

    fun toggleHomePriority(enabled: Boolean) {
        viewModelScope.launch { settingsDataStore.setHomePriorityEnabled(enabled) }
    }

    fun toggleBubble(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setBubbleEnabled(enabled)
            val intent = Intent(context, BubbleService::class.java)
            if (enabled) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } else {
                context.stopService(intent)
            }
        }
    }

    fun saveHomeLocation(name: String, lat: Double, lon: Double, radiusKm: Double = 5.0) {
        viewModelScope.launch {
            homeLocationDao.saveHome(
                HomeLocationEntity(
                    id = "primary_home",
                    name = name,
                    latitude = lat,
                    longitude = lon,
                    radiusKm = radiusKm,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    fun saveInitialMinFare(minFare: Double) {
        viewModelScope.launch {
            val s = settings.value
            settingsDataStore.updateRules(minFare, s.maxPickupKmRule, s.minFarePerKmRule, s.fuelCostPerKm)
        }
    }

    fun updateRules(minFare: Double, maxPickupKm: Double, minFarePerKm: Double, fuelCostPerKm: Double) {
        viewModelScope.launch {
            settingsDataStore.updateRules(minFare, maxPickupKm, minFarePerKm, fuelCostPerKm)
        }
    }

    fun deleteRide(id: String) {
        viewModelScope.launch { rideHistoryDao.deleteById(id) }
    }

    fun clearAllHistory() {
        viewModelScope.launch { privacyController.clearAllRideHistory() }
    }

    fun clearHomeLocation() {
        viewModelScope.launch { privacyController.clearHomeLocation() }
    }
}
