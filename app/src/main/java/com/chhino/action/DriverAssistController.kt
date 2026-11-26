package com.chhino.action

import android.content.Context
import com.chhino.capture.CaptureEvent
import com.chhino.capture.CaptureManager
import com.chhino.data.database.dao.HomeLocationDao
import com.chhino.data.database.dao.RideHistoryDao
import com.chhino.data.database.entities.RideHistoryEntity
import com.chhino.data.preferences.SettingsDataStore
import com.chhino.data.preferences.UserSettings
import com.chhino.intelligence.home.DestinationGeocoder
import com.chhino.intelligence.home.HomeDistanceCalculator
import com.chhino.intelligence.home.HomeDistanceResult
import com.chhino.intelligence.home.HomeZone
import com.chhino.intelligence.profit.ExpenseProfile
import com.chhino.intelligence.profit.ProfitCalculator
import com.chhino.intelligence.scoring.RideScore
import com.chhino.intelligence.scoring.RideScoringEngine
import com.chhino.parse.ParseResult
import com.chhino.parse.RideOffer
import com.chhino.parse.RideParser
import com.chhino.rules.RulesEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class AnalyzedOfferState(
    val offer: RideOffer,
    val score: RideScore,
    val homeDistance: HomeDistanceResult,
    val netProfit: Double?,
    val pipelineLatencyMs: Long
)

@Singleton
class DriverAssistController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val captureManager: CaptureManager,
    private val parser: RideParser,
    private val profitCalculator: ProfitCalculator,
    private val scoringEngine: RideScoringEngine,
    private val notificationController: NotificationController,
    private val rideHistoryDao: RideHistoryDao,
    private val homeLocationDao: HomeLocationDao,
    private val settingsDataStore: SettingsDataStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _latestOffer = MutableStateFlow<AnalyzedOfferState?>(null)
    val latestOffer: StateFlow<AnalyzedOfferState?> = _latestOffer.asStateFlow()

    @Volatile private var cachedSettings = UserSettings()
    @Volatile private var cachedHomeZone: HomeZone? = null

    init {
        scope.launch {
            settingsDataStore.settingsFlow.collect { cachedSettings = it }
        }
        scope.launch {
            homeLocationDao.observeHome().collect { entity ->
                cachedHomeZone = entity?.let {
                    HomeZone(it.name, it.latitude, it.longitude, it.radiusKm)
                }
            }
        }
        scope.launch {
            captureManager.events.collect { event ->
                if (event is CaptureEvent.OfferTextCaptured && cachedSettings.monitoringEnabled) {
                    processCapturedOffer(event)
                }
            }
        }
    }

    private suspend fun processCapturedOffer(event: CaptureEvent.OfferTextCaptured) {
        val startNs = System.nanoTime()
        val parseResult = parser.parse(event.offerText)
        if (parseResult !is ParseResult.Success) return

        val offer = parseResult.offer
        val profile = ExpenseProfile(fuelCostPerKm = cachedSettings.fuelCostPerKm)
        val profit = profitCalculator.calculate(offer, profile)

        val fallbackRules = RulesEngine.rules.value
        val activeHome = cachedHomeZone ?: run {
            val lat = fallbackRules.homeLat.toDoubleOrNull()
            val lon = fallbackRules.homeLon.toDoubleOrNull()
            if (lat != null && lon != null) HomeZone(fallbackRules.homeName, lat, lon, fallbackRules.homeRadiusKm) else null
        }

        val homeDist = if (activeHome == null) {
            HomeDistanceResult(distanceKm = null, displayLabel = "HOME not set")
        } else {
            val destCoords = DestinationGeocoder.resolveCoordinates(context, offer.destinationText)
            if (destCoords == null) {
                HomeDistanceResult(distanceKm = null, displayLabel = "UNKNOWN (Offline/No match)")
            } else {
                val km = HomeDistanceCalculator.calculateHaversineKm(
                    activeHome.latitude,
                    activeHome.longitude,
                    destCoords.first,
                    destCoords.second
                )
                HomeDistanceCalculator.formatDistance(km)
            }
        }
        val score = scoringEngine.score(offer, profit, homeDist.distanceKm)
        val totalMs = (System.nanoTime() - startNs) / 1_000_000L + event.captureDurationMs

        val analyzed = AnalyzedOfferState(
            offer = offer,
            score = score,
            homeDistance = homeDist,
            netProfit = profit.estimatedNetProfit,
            pipelineLatencyMs = totalMs
        )
        _latestOffer.value = analyzed

        if (cachedSettings.notificationsEnabled) {
            notificationController.showOfferAlert(
                platform = offer.platform,
                fare = offer.fare,
                pickupKm = offer.pickupDistanceKm,
                rideKm = offer.rideDistanceKm,
                homeDistanceLabel = homeDist.displayLabel,
                score = score.totalScore,
                category = score.category.name,
                isHomePriority = cachedSettings.homePriorityEnabled
            )
        }

        // Persist asynchronously without blocking alert dispatch
        scope.launch(Dispatchers.IO) {
            rideHistoryDao.insert(
                RideHistoryEntity(
                    id = offer.id,
                    platform = offer.platform,
                    timestamp = offer.timestamp,
                    fare = offer.fare,
                    pickupText = offer.pickupText,
                    destinationText = offer.destinationText,
                    pickupDistanceKm = offer.pickupDistanceKm,
                    rideDistanceKm = offer.rideDistanceKm,
                    homeDistanceKm = homeDist.distanceKm,
                    distanceType = homeDist.distanceType,
                    score = score.totalScore,
                    category = score.category.name,
                    parserConfidence = offer.confidence,
                    estimatedExpenses = profit.totalEstimatedExpense,
                    estimatedProfit = profit.estimatedNetProfit,
                    isHomePriorityMatch = cachedSettings.homePriorityEnabled
                )
            )
        }
    }
}
