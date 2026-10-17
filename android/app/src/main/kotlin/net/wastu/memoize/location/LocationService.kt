package net.wastu.memoize.location

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import net.wastu.memoize.data.NoteLocation
import java.util.Locale
import kotlin.coroutines.resume

object LocationService {
    fun hasPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(context: Context): NoteLocation? = withContext(Dispatchers.IO) {
        if (!hasPermission(context)) return@withContext null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return@withContext null

        val location: Location? = try {
            val providers = listOfNotNull(
                LocationManager.GPS_PROVIDER.takeIf { manager.isProviderEnabled(it) },
                LocationManager.NETWORK_PROVIDER.takeIf { manager.isProviderEnabled(it) },
                LocationManager.PASSIVE_PROVIDER.takeIf { manager.isProviderEnabled(it) },
            )
            var best: Location? = null
            for (p in providers) {
                val loc = manager.getLastKnownLocation(p)
                if (loc != null && (best == null || loc.time > best.time)) {
                    best = loc
                }
            }
            best
        } catch (_: Exception) {
            null
        }

        if (location == null) return@withContext null
        val lat = location.latitude
        val lng = location.longitude
        val label = resolvePlaceName(context, lat, lng)
        NoteLocation(label, lat, lng)
    }

    suspend fun resolvePlaceName(context: Context, latitude: Double, longitude: Double): String = withContext(Dispatchers.IO) {
        try {
            if (Geocoder.isPresent()) {
                val geocoder = Geocoder(context, Locale.getDefault())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val address = suspendCancellableCoroutine<String?> { cont ->
                        geocoder.getFromLocation(latitude, longitude, 1) { addresses ->
                            val first = addresses.firstOrNull()
                            val name = first?.let { formatAddress(it) }
                            cont.resume(name)
                        }
                    }
                    if (!address.isNullOrBlank()) return@withContext address
                } else {
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocation(latitude, longitude, 1)
                    val first = addresses?.firstOrNull()
                    if (first != null) {
                        val name = formatAddress(first)
                        if (name.isNotBlank()) return@withContext name
                    }
                }
            }
        } catch (_: Exception) {}
        "%.4f, %.4f".format(Locale.US, latitude, longitude)
    }

    private fun formatAddress(address: Address): String {
        val feature = address.featureName
        val locality = address.locality ?: address.subAdminArea ?: address.adminArea
        val country = address.countryName
        return when {
            feature != null && locality != null && feature != locality -> "$feature, $locality"
            locality != null && country != null -> "$locality, $country"
            locality != null -> locality
            address.maxAddressLineIndex >= 0 -> address.getAddressLine(0) ?: ""
            else -> ""
        }
    }
}
