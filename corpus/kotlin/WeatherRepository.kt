package com.weatherapp.libre

import android.util.Log
import com.google.gson.Gson
import com.weatherapp.libre.solar.Observer
import com.weatherapp.libre.solar.SolarLightingEngine
import com.weatherapp.libre.solar.SolarRiseSetSolver
import kotlin.math.round
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class CachedWeatherEntry(
    val response: WeatherResponse,
    val timestampMillis: Long
)

class WeatherRepository(
    client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) {

    private val gson = Gson()
    private val client = client

    private val forecastCache = ConcurrentHashMap<String, CachedWeatherEntry>()

    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val inFlightRequests = ConcurrentHashMap<String, Deferred<String>>()

    fun clearMemoryAndDiskCache(locationPreferences: LocationPreferences?) {
        forecastCache.clear()
        locationPreferences?.clearPersistentCache()
    }

    fun hasValidCache(
        latitude: Double,
        longitude: Double,
        model: ForecastModel,
        ttlMinutes: Int,
        locationPreferences: LocationPreferences?
    ): Boolean {
        val modelKey = model.openMeteoKey
        val cacheKey = String.format(Locale.US, "cache_v2_%.4f_%.4f_%s", latitude, longitude, modelKey)
        val now = System.currentTimeMillis()
        val ttlMillis = ttlMinutes.toLong() * 60 * 1000L

        val cached = forecastCache[cacheKey]
        if (cached != null && (now - cached.timestampMillis) < ttlMillis) {
            return true
        }

        if (locationPreferences != null) {
            val diskTime = locationPreferences.getPersistentCacheTime(cacheKey)
            if (diskTime > 0L && (now - diskTime) < ttlMillis) {
                val diskJson = locationPreferences.getPersistentCacheJson(cacheKey)
                if (!diskJson.isNullOrEmpty()) {
                    try {
                        val response = gson.fromJson(diskJson, WeatherResponse::class.java)
                        if (response != null) {
                            forecastCache[cacheKey] = CachedWeatherEntry(response, diskTime)
                            return true
                        }
                    } catch (e: Exception) {
                        // Disk JSON parse error fallback
                    }
                }
            }
        }
        return false
    }

    private suspend fun executeCancellableRequest(request: Request): String {
        val url = request.url.toString()
        val deferred = inFlightRequests.computeIfAbsent(url) {
            repositoryScope.async {
                try {
                    suspendCancellableCoroutine { continuation ->
                        val call = client.newCall(request)
                        // No invokeOnCancellation hook: the shared call must finish even if a caller cancels.
                        call.enqueue(object : Callback {
                            override fun onResponse(call: Call, response: Response) {
                                response.use { resp ->
                                    if (!resp.isSuccessful) {
                                        if (continuation.isActive) {
                                            continuation.resumeWithException(IOException("HTTP Error: ${resp.code}"))
                                        }
                                    } else {
                                        val bodyString = resp.body?.string() ?: ""
                                        if (continuation.isActive) {
                                            continuation.resume(bodyString)
                                        }
                                    }
                                }
                            }

                            override fun onFailure(call: Call, e: IOException) {
                                if (continuation.isActive) {
                                    continuation.resumeWithException(e)
                                }
                            }
                        })
                    }
                } finally {
                    inFlightRequests.remove(url)
                }
            }
        }
        return deferred.await()
    }

    /**
     * Cache-only read method that NEVER touches the network and ignores TTL.
     * Checks in-memory forecastCache first, then falls back to persistent cache JSON.
     */
    fun getCachedForecast(
        lat: Double,
        lon: Double,
        model: ForecastModel,
        prefs: LocationPreferences
    ): WeatherResponse? {
        val modelKey = model.openMeteoKey
        val cacheKey = String.format(Locale.US, "cache_v2_%.4f_%.4f_%s", lat, lon, modelKey)

        val memoryCached = forecastCache[cacheKey]?.response
        if (memoryCached != null) {
            return memoryCached
        }

        val diskJson = prefs.getPersistentCacheJson(cacheKey)
        if (!diskJson.isNullOrEmpty()) {
            try {
                val response = gson.fromJson(diskJson, WeatherResponse::class.java)
                if (response != null) {
                    val diskTime = prefs.getPersistentCacheTime(cacheKey)
                    forecastCache[cacheKey] = CachedWeatherEntry(
                        response,
                        if (diskTime > 0L) diskTime else System.currentTimeMillis()
                    )
                    return response
                }
            } catch (e: Exception) {
                // Disk JSON parse error fallback
            }
        }

        return null
    }

    /**
     * Fetches weather forecast from Open-Meteo API.
     * Serves from memory or persistent disk cache if enabled and fetched less than ttlMinutes ago.
     */
    suspend fun getWeatherForecast(
        latitude: Double,
        longitude: Double,
        model: ForecastModel = ForecastModel.GEM,
        forceRefresh: Boolean = false,
        isCacheEnabled: Boolean = true,
        ttlMinutes: Int = 60,
        locationPreferences: LocationPreferences? = null
    ): Result<WeatherResponse> = withContext(Dispatchers.IO) {
        val modelKey = model.openMeteoKey
        val cacheKey = String.format(Locale.US, "cache_v2_%.4f_%.4f_%s", latitude, longitude, modelKey)
        val now = System.currentTimeMillis()
        val ttlMillis = ttlMinutes.toLong() * 60 * 1000L

        if (!forceRefresh && isCacheEnabled) {
            val cached = forecastCache[cacheKey]
            if (cached != null && (now - cached.timestampMillis) < ttlMillis) {
                return@withContext Result.success(cached.response)
            }

            if (locationPreferences != null) {
                val diskTime = locationPreferences.getPersistentCacheTime(cacheKey)
                if (diskTime > 0L && (now - diskTime) < ttlMillis) {
                    val diskJson = locationPreferences.getPersistentCacheJson(cacheKey)
                    if (!diskJson.isNullOrEmpty()) {
                        try {
                            val response = gson.fromJson(diskJson, WeatherResponse::class.java)
                            if (response != null) {
                                forecastCache[cacheKey] = CachedWeatherEntry(response, diskTime)
                                return@withContext Result.success(response)
                            }
                        } catch (e: Exception) {
                            Log.w("WeatherRepository", "Failed to parse persistent cache JSON for key $cacheKey", e)
                        }
                    }
                }
            }
        }

        try {
            coroutineScope {
                coroutineContext.ensureActive()
                val forecastDeferred = async { fetchForecastData(latitude, longitude, model) }
                val aqiDeferred = async { fetchAirQualityData(latitude, longitude) }

                val forecastResult = forecastDeferred.await()
                coroutineContext.ensureActive()

                if (forecastResult.isFailure) {
                    return@coroutineScope forecastResult
                }

                val weatherResponse = forecastResult.getOrThrow()

                val extraResponse = if (isBestMatchNeeded(weatherResponse)) {
                    fetchBestMatchMetrics(latitude, longitude).getOrNull()
                } else null

                val aqiResult = aqiDeferred.await().getOrNull()
                coroutineContext.ensureActive()

                weatherResponse.fetchedAtEpochMillis = now
                weatherResponse.sourceModelName = model.idKey

                if (weatherResponse.current != null && extraResponse?.current != null) {
                    val curr = weatherResponse.current
                    val extraCurr = extraResponse.current

                    if (aqiResult != null) curr.airQualityIndex = aqiResult
                    if (curr.temperature == null) curr.temperature = extraCurr.temperature
                    if (curr.relativeHumidity == null) curr.relativeHumidity = extraCurr.relativeHumidity
                    if (curr.apparentTemperature == null) curr.apparentTemperature = extraCurr.apparentTemperature
                    if (curr.weatherCode == null) curr.weatherCode = extraCurr.weatherCode
                    if (curr.windSpeed == null) curr.windSpeed = extraCurr.windSpeed
                    if (curr.windDirection == null) curr.windDirection = extraCurr.windDirection
                    if (curr.windGusts == null) curr.windGusts = extraCurr.windGusts
                    if (curr.uvIndex == null) curr.uvIndex = extraCurr.uvIndex
                    if (curr.visibilityMeters == null) curr.visibilityMeters = extraCurr.visibilityMeters
                    if (curr.cloudCoverPct == null) curr.cloudCoverPct = extraCurr.cloudCoverPct
                }

                if (weatherResponse.daily != null && extraResponse?.daily != null) {
                    val d = weatherResponse.daily
                    val exD = extraResponse.daily

                    val maxCount = (exD.time?.size ?: 8).coerceAtLeast(d.time?.size ?: 0)
                    val mergedTimes = MutableList<String>(maxCount) { i -> d.time?.getOrNull(i) ?: exD.time?.getOrNull(i) ?: "" }
                    val mergedCodes = MutableList<Int>(maxCount) { i -> d.weatherCode?.getOrNull(i) ?: exD.weatherCode?.getOrNull(i) ?: 0 }
                    val mergedMax = MutableList<Double>(maxCount) { i -> d.temperatureMax?.getOrNull(i) ?: exD.temperatureMax?.getOrNull(i) ?: 0.0 }
                    val mergedMin = MutableList<Double>(maxCount) { i -> d.temperatureMin?.getOrNull(i) ?: exD.temperatureMin?.getOrNull(i) ?: 0.0 }
                    val mergedUv = MutableList<Double>(maxCount) { i -> d.uvIndexMax?.getOrNull(i) ?: exD.uvIndexMax?.getOrNull(i) ?: 0.0 }
                    val mergedWind = MutableList<Double>(maxCount) { i -> d.windSpeedMax?.getOrNull(i) ?: exD.windSpeedMax?.getOrNull(i) ?: 0.0 }
                    val mergedGusts = MutableList<Double?>(maxCount) { i -> d.windGustsMax?.getOrNull(i) ?: exD.windGustsMax?.getOrNull(i) }
                    val mergedWindDirs = MutableList<Double?>(maxCount) { i -> d.windDirectionDominant?.getOrNull(i) ?: exD.windDirectionDominant?.getOrNull(i) }
                    val mergedSunrise = MutableList<String>(maxCount) { i -> d.sunrise?.getOrNull(i) ?: exD.sunrise?.getOrNull(i) ?: "" }
                    val mergedSunset = MutableList<String>(maxCount) { i -> d.sunset?.getOrNull(i) ?: exD.sunset?.getOrNull(i) ?: "" }
                    val mergedPrecipSum = MutableList<Double?>(maxCount) { i -> d.precipitationSum?.getOrNull(i) ?: exD.precipitationSum?.getOrNull(i) }
                    val mergedRainSum = MutableList<Double?>(maxCount) { i -> d.rainSum?.getOrNull(i) ?: exD.rainSum?.getOrNull(i) }
                    val mergedSnowfallSum = MutableList<Double?>(maxCount) { i -> d.snowfallSum?.getOrNull(i) ?: exD.snowfallSum?.getOrNull(i) }
                    val mergedPrecipProb = MutableList<Int?>(maxCount) { i -> d.precipitationProbabilityMax?.getOrNull(i) ?: exD.precipitationProbabilityMax?.getOrNull(i) }

                    d.time = mergedTimes
                    d.weatherCode = mergedCodes
                    d.temperatureMax = mergedMax
                    d.temperatureMin = mergedMin
                    d.uvIndexMax = mergedUv
                    d.windSpeedMax = mergedWind
                    d.windGustsMax = mergedGusts
                    d.windDirectionDominant = mergedWindDirs
                    d.sunrise = mergedSunrise
                    d.sunset = mergedSunset
                    d.precipitationSum = mergedPrecipSum
                    d.rainSum = mergedRainSum
                    d.snowfallSum = mergedSnowfallSum
                    d.precipitationProbabilityMax = mergedPrecipProb
                }

                if (weatherResponse.hourly == null && extraResponse?.hourly != null) {
                    weatherResponse.hourly = extraResponse.hourly
                }

                forecastCache[cacheKey] = CachedWeatherEntry(weatherResponse, now)
                if (locationPreferences != null) {
                    try {
                        val json = gson.toJson(weatherResponse)
                        locationPreferences.savePersistentCache(cacheKey, json, now)
                    } catch (e: Exception) {
                        Log.w("WeatherRepository", "Failed to write persistent cache JSON for key $cacheKey", e)
                    }
                }

                Result.success(weatherResponse)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    private suspend fun fetchForecastData(latitude: Double, longitude: Double, model: ForecastModel): Result<WeatherResponse> {
        return try {
            val baseUrl = model.baseUrl
            val modelApiString = model.openMeteoKey

            val url = "$baseUrl?" +
                    "latitude=$latitude" +
                    "&longitude=$longitude" +
                    "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,is_day,wind_speed_10m,wind_direction_10m,wind_gusts_10m,visibility,uv_index,cloud_cover" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,uv_index_max,wind_speed_10m_max,wind_gusts_10m_max,wind_direction_10m_dominant,precipitation_sum,rain_sum,snowfall_sum,precipitation_probability_max" +
                    "&hourly=temperature_2m,apparent_temperature,precipitation_probability,precipitation,weather_code,wind_speed_10m,wind_gusts_10m,wind_direction_10m,relative_humidity_2m,dew_point_2m,cloud_cover,visibility,uv_index" +
                    "&forecast_days=8" +
                    "&models=$modelApiString" +
                    "&timeformat=iso8601" +
                    "&timezone=auto"

            android.util.Log.d("WeatherAPI_Routing", "Requesting Model: ${model.displayName} | Endpoint: $baseUrl | URL: $url")

            val request = Request.Builder().url(url).get().build()
            val bodyString = executeCancellableRequest(request)

            val weatherResponse = gson.fromJson(bodyString, WeatherResponse::class.java)
            Result.success(weatherResponse)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    private fun isBestMatchNeeded(response: WeatherResponse): Boolean {
        val daily = response.daily ?: return true
        if (daily.time.isNullOrEmpty() ||
            daily.weatherCode.isNullOrEmpty() ||
            daily.temperatureMax.isNullOrEmpty() ||
            daily.temperatureMin.isNullOrEmpty()
        ) {
            return true
        }
        if (response.hourly == null) {
            return true
        }
        val current = response.current
        if (current == null || current.temperature == null || current.weatherCode == null) {
            return true
        }
        return false
    }

    private suspend fun fetchBestMatchMetrics(latitude: Double, longitude: Double): Result<WeatherResponse> {
        return try {
            val url = "https://api.open-meteo.com/v1/forecast?" +
                    "latitude=$latitude" +
                    "&longitude=$longitude" +
                    "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,is_day,wind_speed_10m,wind_direction_10m,wind_gusts_10m,visibility,uv_index,cloud_cover" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,uv_index_max,wind_speed_10m_max,wind_gusts_10m_max,wind_direction_10m_dominant,precipitation_sum,rain_sum,snowfall_sum,precipitation_probability_max" +
                    "&hourly=temperature_2m,apparent_temperature,precipitation_probability,precipitation,weather_code,wind_speed_10m,wind_gusts_10m,wind_direction_10m,relative_humidity_2m,dew_point_2m,cloud_cover,visibility,uv_index" +
                    "&forecast_days=8" +
                    "&timeformat=iso8601" +
                    "&timezone=auto"

            val request = Request.Builder().url(url).get().build()
            val bodyString = executeCancellableRequest(request)
            val weatherResponse = gson.fromJson(bodyString, WeatherResponse::class.java)
            Result.success(weatherResponse)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    private suspend fun fetchAirQualityData(latitude: Double, longitude: Double): Result<Int?> {
        return try {
            val url = "https://air-quality-api.open-meteo.com/v1/air-quality?" +
                    "latitude=$latitude&longitude=$longitude&current=us_aqi"

            val request = Request.Builder().url(url).get().build()
            val bodyString = executeCancellableRequest(request)
            val aqiResponse = gson.fromJson(bodyString, AirQualityResponse::class.java)
            Result.success(aqiResponse.current?.usAqi)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    /**
     * Searches location using Open-Meteo Geocoding API.
     */
    suspend fun searchLocations(query: String): Result<List<LocationItem>> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext Result.success(emptyList())

        try {
            val encodedQuery = java.net.URLEncoder.encode(query.trim(), "UTF-8")
            val url = "https://geocoding-api.open-meteo.com/v1/search?name=$encodedQuery&count=10&language=en&format=json"

            val request = Request.Builder().url(url).get().build()

            val bodyString = executeCancellableRequest(request)
            if (bodyString.isEmpty()) {
                return@withContext Result.failure(IOException("Empty response body"))
            }

            val geocodingResponse = gson.fromJson(bodyString, GeocodingResponse::class.java)
            val results = geocodingResponse.results?.map {
                LocationItem(
                    id = it.id,
                    name = it.name,
                    admin1 = it.admin1,
                    country = it.country,
                    countryCode = it.countryCode,
                    latitude = it.latitude,
                    longitude = it.longitude,
                    elevationMeters = it.elevation ?: 0.0
                )
            } ?: emptyList()

            Result.success(results)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    fun calculateSolarSummary(
        latitude: Double,
        longitude: Double,
        elevationMeters: Double,
        date: LocalDate,
        timezoneStr: String?
    ): SolarDaySummary {
        val zoneId = try {
            if (!timezoneStr.isNullOrBlank()) ZoneId.of(timezoneStr) else ZoneId.systemDefault()
        } catch (e: Exception) {
            ZoneId.systemDefault()
        }

        val observer = Observer(
            latitudeDegrees = latitude,
            longitudeDegrees = longitude,
            elevationMeters = elevationMeters
        )

        val lighting = SolarLightingEngine.calculateSolarLightingPhases(date, zoneId, observer)

        val srInstant = lighting.sunrise?.instantUtc
        val ssInstant = lighting.sunset?.instantUtc
        val srAz = lighting.sunrise?.azimuthDegrees?.let { round(it).toInt() }
        val ssAz = lighting.sunset?.azimuthDegrees?.let { round(it).toInt() }

        val durationMins = if (srInstant != null && ssInstant != null) {
            ChronoUnit.MINUTES.between(srInstant, ssInstant).coerceAtLeast(0L)
        } else null

        return SolarDaySummary(
            sunrise = srInstant,
            sunset = ssInstant,
            sunriseAzimuthDegrees = srAz,
            sunsetAzimuthDegrees = ssAz,
            morningGoldenHourStart = lighting.morningGoldenHour?.startInstantUtc,
            morningGoldenHourEnd = lighting.morningGoldenHour?.endInstantUtc,
            eveningGoldenHourStart = lighting.eveningGoldenHour?.startInstantUtc,
            eveningGoldenHourEnd = lighting.eveningGoldenHour?.endInstantUtc,
            morningBlueHourStart = lighting.morningBlueHour?.startInstantUtc,
            morningBlueHourEnd = lighting.morningBlueHour?.endInstantUtc,
            eveningBlueHourStart = lighting.eveningBlueHour?.startInstantUtc,
            eveningBlueHourEnd = lighting.eveningBlueHour?.endInstantUtc,
            solarNoonElevationDegrees = lighting.solarNoon?.apparentAltitudeDegrees?.let { round(it).toInt() },
            daylightDurationMinutes = durationMins
        )
    }

    fun processTodayForecast(response: WeatherResponse, latitude: Double, longitude: Double): DayForecast? {
        val daily = response.daily ?: return null
        val times = daily.time ?: return null
        if (times.isEmpty()) return null

        val localZone = try {
            ZoneId.of(response.timezone ?: "UTC")
        } catch (e: Exception) {
            ZoneId.of("UTC")
        }

        val nowLocal = LocalDate.now(localZone)
        var todayIdx = -1

        for (i in times.indices) {
            try {
                val parsedDate = LocalDate.parse(times[i])
                if (parsedDate.isEqual(nowLocal)) {
                    todayIdx = i
                    break
                }
            } catch (e: Exception) {
                // Ignore date parse
            }
        }

        if (todayIdx == -1) todayIdx = 0

        val dateStr = times[todayIdx]
        val parsedTodayDate = try {
            LocalDate.parse(dateStr)
        } catch (e: Exception) {
            nowLocal
        }

        val formattedDate = try {
            val formatter = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())
            parsedTodayDate.format(formatter)
        } catch (e: Exception) {
            dateStr
        }

        val weatherCode = daily.weatherCode?.getOrNull(todayIdx) ?: 0
        val tempMax = daily.temperatureMax?.getOrNull(todayIdx) ?: 0.0
        val tempMin = daily.temperatureMin?.getOrNull(todayIdx) ?: 0.0
        val uvIndex = daily.uvIndexMax?.getOrNull(todayIdx) ?: 0.0
        val windSpeed = daily.windSpeedMax?.getOrNull(todayIdx) ?: 0.0
        val windGusts = daily.windGustsMax?.getOrNull(todayIdx)
        val windDir = daily.windDirectionDominant?.getOrNull(todayIdx)
        val precipSum = daily.precipitationSum?.getOrNull(todayIdx)
        val rainSum = daily.rainSum?.getOrNull(todayIdx)
        val snowfallSum = daily.snowfallSum?.getOrNull(todayIdx)
        val precipProbMax = daily.precipitationProbabilityMax?.getOrNull(todayIdx)

        val weatherDescription = WeatherCodeUtils.getWeatherDescription(weatherCode)
        val current = response.current

        val elevation = response.elevation ?: 0.0
        val solarSummary = calculateSolarSummary(latitude, longitude, elevation, parsedTodayDate, response.timezone)

        return DayForecast(
            dateString = dateStr,
            dayOfWeek = "Today",
            formattedDate = formattedDate,
            weatherCode = weatherCode,
            weatherDescription = weatherDescription,
            iconEmoji = "",
            tempMax = tempMax,
            tempMin = tempMin,
            windSpeedMaxKmH = windSpeed,
            windGustsMaxKmH = windGusts,
            windDirectionDominant = windDir,
            uvIndexMax = uvIndex,
            humidityPct = current?.relativeHumidity?.toInt() ?: 0,
            visibilityMeters = current?.visibilityMeters ?: 10000.0,
            cloudCoverPct = current?.cloudCoverPct?.toInt() ?: 0,
            airQualityIndex = current?.airQualityIndex,
            solarSummary = solarSummary,
            precipitationSumMm = precipSum,
            rainSumMm = rainSum,
            snowfallSumMm = snowfallSum,
            precipitationProbabilityMax = precipProbMax
        )
    }

    fun processDailyForecast(response: WeatherResponse, latitude: Double, longitude: Double): List<DayForecast> {
        val daily = response.daily ?: return emptyList()
        val times = daily.time ?: return emptyList()
        if (times.isEmpty()) return emptyList()

        val localZone = try {
            ZoneId.of(response.timezone ?: "UTC")
        } catch (e: Exception) {
            ZoneId.of("UTC")
        }

        val nowLocal = LocalDate.now(localZone)
        val resultList = mutableListOf<DayForecast>()

        for (i in times.indices) {
            val dateStr = times[i]
            val parsedDate = try {
                LocalDate.parse(dateStr)
            } catch (e: Exception) {
                null
            }

            if (parsedDate != null && parsedDate.isBefore(nowLocal)) {
                continue
            }

            val dayOfWeekStr = if (parsedDate != null) {
                val diffDays = ChronoUnit.DAYS.between(nowLocal, parsedDate)
                when (diffDays) {
                    0L -> "Today"
                    1L -> "Tomorrow"
                    else -> parsedDate.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault())
                }
            } else {
                "Day ${i + 1}"
            }

            val formattedDate = if (parsedDate != null) {
                val formatter = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())
                parsedDate.format(formatter)
            } else {
                dateStr
            }

            val weatherCode = daily.weatherCode?.getOrNull(i) ?: 0
            val tempMax = daily.temperatureMax?.getOrNull(i) ?: 0.0
            val tempMin = daily.temperatureMin?.getOrNull(i) ?: 0.0
            val uvIndex = daily.uvIndexMax?.getOrNull(i) ?: 0.0
            val windSpeed = daily.windSpeedMax?.getOrNull(i) ?: 0.0
            val windGusts = daily.windGustsMax?.getOrNull(i)
            val windDir = daily.windDirectionDominant?.getOrNull(i)
            val precipSum = daily.precipitationSum?.getOrNull(i)
            val rainSum = daily.rainSum?.getOrNull(i)
            val snowfallSum = daily.snowfallSum?.getOrNull(i)
            val precipProbMax = daily.precipitationProbabilityMax?.getOrNull(i)

            val weatherDescription = WeatherCodeUtils.getWeatherDescription(weatherCode)

            val dayDate = parsedDate ?: nowLocal
            val elevation = response.elevation ?: 0.0
            val solarSummary = calculateSolarSummary(latitude, longitude, elevation, dayDate, response.timezone)

            resultList.add(
                DayForecast(
                    dateString = dateStr,
                    dayOfWeek = dayOfWeekStr,
                    formattedDate = formattedDate,
                    weatherCode = weatherCode,
                    weatherDescription = weatherDescription,
                    iconEmoji = "",
                    tempMax = tempMax,
                    tempMin = tempMin,
                    windSpeedMaxKmH = windSpeed,
                    windGustsMaxKmH = windGusts,
                    windDirectionDominant = windDir,
                    uvIndexMax = uvIndex,
                    humidityPct = response.current?.relativeHumidity?.toInt() ?: 0,
                    visibilityMeters = response.current?.visibilityMeters ?: 10000.0,
                    cloudCoverPct = response.current?.cloudCoverPct?.toInt() ?: 0,
                    airQualityIndex = response.current?.airQualityIndex,
                    solarSummary = solarSummary,
                    precipitationSumMm = precipSum,
                    rainSumMm = rainSum,
                    snowfallSumMm = snowfallSum,
                    precipitationProbabilityMax = precipProbMax
                )
            )
        }

        return resultList
    }
}
