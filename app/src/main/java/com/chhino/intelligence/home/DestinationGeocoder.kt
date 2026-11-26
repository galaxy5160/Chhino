package com.chhino.intelligence.home

import android.content.Context
import android.location.Geocoder
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

object DestinationGeocoder {
    private val cache = ConcurrentHashMap<String, Pair<Double, Double>>()

    suspend fun resolveCoordinates(
        context: Context,
        destinationText: String?
    ): Pair<Double, Double>? {
        val query = destinationText?.trim()?.takeIf { it.length >= 3 } ?: return null
        cache[query.lowercase()]?.let { return it }

        if (!Geocoder.isPresent()) return null

        return withTimeoutOrNull(1500L) {
            withContext(Dispatchers.IO) {
                try {
                    val geocoder = Geocoder(context, Locale.getDefault())
                    val coords = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        suspendCancellableCoroutine<Pair<Double, Double>?> { cont ->
                            geocoder.getFromLocationName(query, 1) { list ->
                                val first = list.firstOrNull()
                                cont.resume(first?.let { it.latitude to it.longitude })
                            }
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        val list = geocoder.getFromLocationName(query, 1)
                        val first = list?.firstOrNull()
                        first?.let { it.latitude to it.longitude }
                    }
                    if (coords != null) {
                        cache[query.lowercase()] = coords
                    }
                    coords
                } catch (_: Exception) {
                    null
                }
            }
        }
    }
}
