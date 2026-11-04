package com.weatherapp.libre

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.core.graphics.ColorUtils
import com.weatherapp.theme.BoutiqueThemeEngine
import com.weatherapp.theme.ColorPalette
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.weatherapp.libre.lunar.LunarCalculator
import com.weatherapp.libre.lunar.getEmoji
import com.weatherapp.libre.lunar.getNameRes
import com.weatherapp.libre.solar.Observer as SolarObserver
import com.weatherapp.libre.solar.SolarLightingEngine
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class WidgetSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    /**
     * Test-only injection point for a [WeatherRepository] backed by a fake HTTP client.
     * When null (the production default), a fresh [WeatherRepository] is created as before.
     * The primary (Context, WorkerParameters) constructor is preserved so WorkManager
     * reflection and TestListenableWorkerBuilder continue to work unchanged; tests set
     * this property after building the worker.
     */
    var weatherRepositoryOverride: WeatherRepository? = null

    companion object {
        private const val WORK_NAME_PERIODIC = "widget_sync_periodic"
        private const val WORK_NAME_IMMEDIATE = "widget_immediate_sync"

        fun enqueuePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<WidgetSyncWorker>(60, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancelPeriodic(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_PERIODIC)
        }

        fun enqueueImmediate(context: Context) {
            val request = OneTimeWorkRequestBuilder<WidgetSyncWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_IMMEDIATE,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        fun notifyWidgetsInvalidated(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context) ?: return
            val widgetTypes = listOf(
                ComponentName(context, WeatherWidgetProvider4x2::class.java),
                ComponentName(context, WeatherWidgetProvider4x1::class.java),
                ComponentName(context, WeatherWidgetProvider2x1::class.java)
            )

            var hasActiveWidgets = false
            val activeWidgetIds = mutableListOf<Int>()

            for (component in widgetTypes) {
                val ids = appWidgetManager.getAppWidgetIds(component)
                if (ids.isNotEmpty()) {
                    hasActiveWidgets = true
                    activeWidgetIds.addAll(ids.toList())
                }
            }

            if (!hasActiveWidgets || activeWidgetIds.isEmpty()) return

            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                for (appWidgetId in activeWidgetIds.distinct()) {
                    rebuildAndUpdate(context, appWidgetId)
                }
            }
        }

        fun notifyWidgetsThemeChanged(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context) ?: return
            val widgetTypes = listOf(
                ComponentName(context, WeatherWidgetProvider4x2::class.java),
                ComponentName(context, WeatherWidgetProvider4x1::class.java),
                ComponentName(context, WeatherWidgetProvider2x1::class.java)
            )
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                for (component in widgetTypes) {
                    val ids = appWidgetManager.getAppWidgetIds(component)
                    for (appWidgetId in ids) {
                        rebuildAndUpdate(context, appWidgetId)
                    }
                }
            }
        }

        suspend fun rebuildAndUpdate(context: Context, appWidgetId: Int) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val widgetInfo = appWidgetManager.getAppWidgetInfo(appWidgetId) ?: return
            val layoutId = widgetInfo.initialLayout
            val providerComponent = widgetInfo.provider ?: run {
                Log.w("WidgetSyncWorker", "Cannot update widget $appWidgetId: provider component is null")
                return
            }

            val widgetPrefs = WidgetPreferences(context)
            val locationPreferences = LocationPreferences(context)
            val weatherRepository = WeatherRepository()

            val activeTab = widgetPrefs.getActiveTab(appWidgetId)
            val showNightVis = widgetPrefs.getShowNightVis(appWidgetId)
            val showSolunar = widgetPrefs.getShowSolunar(appWidgetId)

            val tabCount = locationPreferences.getTabCount()
            val safeActiveTab = activeTab.coerceIn(0, tabCount - 1)
            val location = locationPreferences.getTabLocation(safeActiveTab)
            val unitSystem = locationPreferences.getUnitSystem()

            val weatherResponse = weatherRepository.getCachedForecast(
                lat = location.latitude,
                lon = location.longitude,
                model = locationPreferences.getActiveModelForLocation(location),
                prefs = locationPreferences
            )

            val remoteViews = RemoteViews(context.packageName, layoutId)

            val palette = when (widgetPrefs.getWidgetThemeSource()) {
                WidgetPreferences.WIDGET_THEME_SOURCE_FOLLOW -> {
                    val savedMode = locationPreferences.getSavedThemeMode()
                    val effectiveMode = locationPreferences.resolveEffectiveTheme(savedMode, context)
                    BoutiqueThemeEngine.getThemePalette(effectiveMode)
                }
                WidgetPreferences.WIDGET_THEME_SOURCE_CUSTOM -> {
                    val customMode = widgetPrefs.getWidgetCustomThemeMode()
                    val modeToUse = if (customMode == WidgetPreferences.WIDGET_THEME_UNSET) {
                        locationPreferences.getSavedThemeMode()
                    } else {
                        customMode
                    }
                    val effectiveMode = locationPreferences.resolveEffectiveTheme(modeToUse, context)
                    BoutiqueThemeEngine.getThemePalette(effectiveMode)
                }
                else -> {
                    ColorPalette(
                        primary = Color.parseColor("#0284C7"),
                        surfaceCard = Color.parseColor("#1E293B"),
                        surfaceCardSecondary = Color.parseColor("#334155"),
                        backgroundLight = Color.parseColor("#1E293B"),
                        textPrimary = Color.parseColor("#FFFFFF"),
                        textSecondary = Color.parseColor("#94A3B8"),
                        tempHigh = Color.parseColor("#DC2626"),
                        tempHighBg = Color.parseColor("#FEE2E2"),
                        tempLow = Color.parseColor("#0284C7"),
                        tempLowBg = Color.parseColor("#E0F2FE"),
                        goldenHourPrimary = Color.parseColor("#D97706"),
                        blueHourPrimary = Color.parseColor("#4F46E5"),
                        dividerColor = Color.parseColor("#CBD5E1")
                    )
                }
            }

            val opacityPercent = widgetPrefs.getWidgetOpacity().coerceIn(1, 100)
            val alpha = ((opacityPercent * 255) / 100).coerceIn(0, 255)
            val rootBgColor = (palette.surfaceCard and 0x00FFFFFF) or (0xFF shl 24)
            remoteViews.setInt(R.id.iv_widget_bg, "setColorFilter", rootBgColor)
            remoteViews.setInt(R.id.iv_widget_bg, "setImageAlpha", alpha)

            if (layoutId == R.layout.widget_layout_4x2) {
                val featureBgColor = (palette.textSecondary and 0x00FFFFFF) or (0xFF shl 24)
                remoteViews.setInt(R.id.iv_feature_bg, "setColorFilter", featureBgColor)
            }

            remoteViews.setTextColor(R.id.tv_widget_temp, palette.textPrimary)
            remoteViews.setTextColor(R.id.tv_widget_condition, palette.textSecondary)
            remoteViews.setTextColor(R.id.tv_widget_high_low, palette.textSecondary)
            remoteViews.setTextColor(R.id.tv_widget_sunrise, palette.textSecondary)
            remoteViews.setTextColor(R.id.tv_widget_sunset, palette.textSecondary)
            remoteViews.setTextColor(R.id.tv_widget_natural_light_period, palette.primary)
            remoteViews.setTextColor(R.id.tv_widget_moon_phase, palette.primary)

            if (layoutId == R.layout.widget_layout_4x2 || layoutId == R.layout.widget_layout_4x1 || layoutId == R.layout.widget_layout_2x1) {
                remoteViews.setTextColor(R.id.tv_widget_moonrise, palette.textSecondary)
                remoteViews.setTextColor(R.id.tv_widget_moonset, palette.textSecondary)
            }

            if (layoutId == R.layout.widget_layout_2x1) {
                remoteViews.setTextColor(R.id.tv_widget_moon_stage, palette.textSecondary)
            }

            if (layoutId == R.layout.widget_layout_4x2) {
                remoteViews.setTextColor(R.id.tv_widget_lunar_timings, palette.textSecondary)
                remoteViews.setTextColor(R.id.tv_widget_dark_sky_period, palette.textSecondary)
                remoteViews.setTextColor(R.id.tv_widget_night_vis, palette.textSecondary)
                remoteViews.setTextColor(R.id.tv_widget_solunar, palette.textSecondary)
            }



            if (weatherResponse != null) {
                val response = weatherResponse

                val tempStr = response?.current?.temperature?.let {
                    UnitUtils.formatTemp(it, unitSystem)
                } ?: context.getString(R.string.position_unavailable)
                remoteViews.setTextViewText(R.id.tv_widget_temp, tempStr)

                val weatherCode = response?.current?.weatherCode ?: 0
                val condIconRes = WeatherCodeUtils.conditionIconRes(weatherCode)
                val condText = WeatherCodeUtils.getWeatherDescription(context, weatherCode)

                remoteViews.setImageViewResource(R.id.iv_widget_condition_icon, condIconRes)
                remoteViews.setTextViewText(R.id.tv_widget_condition, condText)

                val maxTemp = response?.daily?.temperatureMax?.firstOrNull()
                val minTemp = response?.daily?.temperatureMin?.firstOrNull()
                val highLowStr = if (maxTemp != null && minTemp != null) {
                    "H: ${UnitUtils.formatTemp(maxTemp, unitSystem)} / L: ${UnitUtils.formatTemp(minTemp, unitSystem)}"
                } else {
                    "--"
                }
                remoteViews.setTextViewText(R.id.tv_widget_high_low, highLowStr)

                // Moon Phase calculation
                val moonInfo = LunarCalculator.phaseAt(Instant.now())
                val moonEmoji = moonInfo.phaseCategory.getEmoji()
                val moonPct = (moonInfo.illuminationFraction * 100).roundToInt().coerceIn(0, 100)
                remoteViews.setTextViewText(R.id.tv_widget_moon_phase, "$moonEmoji $moonPct%")

                if (layoutId == R.layout.widget_layout_2x1) {
                    val phaseName = context.getString(moonInfo.phaseCategory.getNameRes())
                    remoteViews.setTextViewText(R.id.tv_widget_moon_stage, phaseName)
                    remoteViews.setViewVisibility(R.id.tv_widget_moon_stage, View.VISIBLE)
                }

                val zoneId = try {
                    ZoneId.of(response?.timezone ?: ZoneId.systemDefault().id)
                } catch (e: Exception) {
                    Log.e("WidgetSync", "EXCEPTION id=$appWidgetId", e)
                    ZoneId.systemDefault()
                }

                // Solar lighting calculations (Sunrise & Sunset)
                val solarObserver = SolarObserver(
                    latitudeDegrees = location.latitude,
                    longitudeDegrees = location.longitude,
                    elevationMeters = location.elevationMeters
                )
                val solarLighting = SolarLightingEngine.calculateSolarLightingPhases(
                    LocalDate.now(),
                    zoneId,
                    solarObserver
                )

                // Bind Discrete Sunrise & Sunset Rows (Arrow glyphs on all layouts)
                val sunriseStr = formatInstantTime(solarLighting.sunrise?.instantUtc, zoneId)
                val sunsetStr = formatInstantTime(solarLighting.sunset?.instantUtc, zoneId)

                if (solarLighting.sunrise?.instantUtc != null) {
                    remoteViews.setTextViewText(R.id.tv_widget_sunrise, "↑ $sunriseStr")
                    remoteViews.setViewVisibility(R.id.tv_widget_sunrise, View.VISIBLE)
                } else {
                    remoteViews.setViewVisibility(R.id.tv_widget_sunrise, View.GONE)
                }

                if (solarLighting.sunset?.instantUtc != null) {
                    remoteViews.setTextViewText(R.id.tv_widget_sunset, "↓ $sunsetStr")
                    remoteViews.setViewVisibility(R.id.tv_widget_sunset, View.VISIBLE)
                } else {
                    remoteViews.setViewVisibility(R.id.tv_widget_sunset, View.GONE)
                }

                // Compute Active Natural Light Period (2x1, 4x1, 4x2)
                if (layoutId == R.layout.widget_layout_4x2 || layoutId == R.layout.widget_layout_4x1 || layoutId == R.layout.widget_layout_2x1) {
                    val now = Instant.now()
                    val solarPhases = SolarLightingEngine.calculateChronologicalSolarPhases(
                        date = LocalDate.now(),
                        zoneId = zoneId,
                        observer = solarObserver,
                        nowInstant = now
                    )
                    val activePhase = solarPhases.find { phase ->
                        phase.isCurrentActivePhase || (phase.startInstant != null && phase.endInstant != null && !now.isBefore(phase.startInstant) && now.isBefore(phase.endInstant))
                    } ?: solarPhases.firstOrNull()

                    val activePhaseLabel = when (activePhase?.name?.lowercase()) {
                        "civil twilight" -> context.getString(R.string.widget_phase_civil_twilight)
                        "nautical twilight" -> context.getString(R.string.widget_phase_nautical_twilight)
                        "astronomical twilight" -> context.getString(R.string.widget_phase_astronomical_twilight)
                        else -> activePhase?.getDisplayName(context)
                    }
                    if (!activePhaseLabel.isNullOrEmpty()) {
                        remoteViews.setTextViewText(R.id.tv_widget_natural_light_period, activePhaseLabel)
                        remoteViews.setViewVisibility(R.id.tv_widget_natural_light_period, View.VISIBLE)
                    } else {
                        remoteViews.setViewVisibility(R.id.tv_widget_natural_light_period, View.GONE)
                    }
                }

                // Solunar calculations for 2x1, 4x1 & 4x2 (Moonrise & Moonset)
                if (layoutId == R.layout.widget_layout_4x2 || layoutId == R.layout.widget_layout_4x1 || layoutId == R.layout.widget_layout_2x1) {
                    val solunarSummaries = SolunarManager().calculateSolunarForecast(
                        dates = listOf(LocalDate.now()),
                        zoneId = zoneId,
                        location = location
                    )
                    val todaySolunar = solunarSummaries.firstOrNull()

                    val riseStr = formatInstantTime(todaySolunar?.moonrise, zoneId)
                    val setStr = formatInstantTime(todaySolunar?.moonset, zoneId)

                    if (todaySolunar?.moonrise != null) {
                        val moonriseText = if (layoutId == R.layout.widget_layout_2x1) {
                            "↑ $riseStr"
                        } else {
                            val riseLabel = context.getString(R.string.label_moonrise)
                            "$riseLabel: $riseStr"
                        }
                        remoteViews.setTextViewText(R.id.tv_widget_moonrise, moonriseText)
                        remoteViews.setViewVisibility(R.id.tv_widget_moonrise, View.VISIBLE)
                    } else {
                        remoteViews.setViewVisibility(R.id.tv_widget_moonrise, View.GONE)
                    }

                    if (todaySolunar?.moonset != null) {
                        val moonsetText = if (layoutId == R.layout.widget_layout_2x1) {
                            "↓ $setStr"
                        } else {
                            val setLabel = context.getString(R.string.label_moonset)
                            "$setLabel: $setStr"
                        }
                        remoteViews.setTextViewText(R.id.tv_widget_moonset, moonsetText)
                        remoteViews.setViewVisibility(R.id.tv_widget_moonset, View.VISIBLE)
                    } else {
                        remoteViews.setViewVisibility(R.id.tv_widget_moonset, View.GONE)
                    }

                    // 4x2 Feature Toggles & Lunar Timings Row
                    if (layoutId == R.layout.widget_layout_4x2) {
                        val transitStr = formatInstantTime(todaySolunar?.transit, zoneId)
                        val nadirStr = formatInstantTime(todaySolunar?.nadir, zoneId)
                        val transitLabel = context.getString(R.string.label_transit)
                        val nadirLabel = context.getString(R.string.label_nadir)

                        val timingsStr = "$transitLabel: $transitStr  •  $nadirLabel: $nadirStr"
                        remoteViews.setTextViewText(R.id.tv_widget_lunar_timings, timingsStr)

                    // Bind True Dark Sky Period Row
                    val darkSkySummaries = DarkSkyManager().calculateDarkSkyWindows(
                        dates = listOf(LocalDate.now()),
                        zoneId = zoneId,
                        location = location
                    )
                    val todayDarkSky = darkSkySummaries.firstOrNull()
                    val darkSkyWindows = todayDarkSky?.windows ?: emptyList()
                    val now = Instant.now()

                    val currentDarkSkyWindow = darkSkyWindows.find { w ->
                        !now.isBefore(w.startUtc) && now.isBefore(w.endUtc)
                    }
                    val upcomingDarkSkyWindow = if (currentDarkSkyWindow == null) {
                        darkSkyWindows.find { w -> w.startUtc.isAfter(now) }
                    } else null

                    val darkSkyLabel = context.getString(R.string.subtab_dark_sky_dropdown)
                    val activeDarkSkyWindow = currentDarkSkyWindow ?: upcomingDarkSkyWindow

                    if (activeDarkSkyWindow != null) {
                        val startStr = formatInstantTime(activeDarkSkyWindow.startUtc, zoneId)
                        val endStr = formatInstantTime(activeDarkSkyWindow.endUtc, zoneId)
                        val text = "$darkSkyLabel: $startStr - $endStr"
                        remoteViews.setTextViewText(R.id.tv_widget_dark_sky_period, text)
                    } else {
                        remoteViews.setTextViewText(R.id.tv_widget_dark_sky_period, context.getString(R.string.widget_no_dark_sky))
                    }
                    remoteViews.setViewVisibility(R.id.tv_widget_dark_sky_period, View.VISIBLE)

                    if (showNightVis) {
                        val visSummaries = NightVisibilityManager().calculateVisibilityPeriods(
                            dates = listOf(LocalDate.now()),
                            zoneId = zoneId,
                            location = location
                        )
                        val category = visSummaries.firstOrNull()?.periods?.firstOrNull()?.getLocalizedCategory(context)
                            ?: context.getString(R.string.rating_moderate)
                        remoteViews.setTextViewText(
                            R.id.tv_widget_night_vis,
                            "${context.getString(R.string.widget_night_vis_label)} $category"
                        )
                        remoteViews.setViewVisibility(R.id.tv_widget_night_vis, View.VISIBLE)
                    } else {
                        remoteViews.setViewVisibility(R.id.tv_widget_night_vis, View.GONE)
                    }

                    if (showSolunar) {
                        val rating = todaySolunar?.getLocalizedRatingLabel(context)
                            ?: context.getString(R.string.rating_moderate)
                        remoteViews.setTextViewText(
                            R.id.tv_widget_solunar,
                            "${context.getString(R.string.widget_solunar_label)} $rating"
                        )
                        remoteViews.setViewVisibility(R.id.tv_widget_solunar, View.VISIBLE)
                    } else {
                        remoteViews.setViewVisibility(R.id.tv_widget_solunar, View.GONE)
                    }

                    remoteViews.setViewVisibility(R.id.layout_widget_features, View.VISIBLE)
                }
            }
            } else {
                remoteViews.setTextViewText(R.id.tv_widget_temp, "--")
                remoteViews.setTextViewText(R.id.tv_widget_condition, context.getString(R.string.widget_no_data))
                remoteViews.setTextViewText(R.id.tv_widget_high_low, "--")
                remoteViews.setViewVisibility(R.id.tv_widget_sunrise, View.GONE)
                remoteViews.setViewVisibility(R.id.tv_widget_sunset, View.GONE)
                remoteViews.setViewVisibility(R.id.tv_widget_natural_light_period, View.GONE)
                if (layoutId == R.layout.widget_layout_2x1) {
                    remoteViews.setViewVisibility(R.id.tv_widget_moon_stage, View.GONE)
                }
                remoteViews.setViewVisibility(R.id.tv_widget_moonrise, View.GONE)
                remoteViews.setViewVisibility(R.id.tv_widget_moonset, View.GONE)
                remoteViews.setViewVisibility(R.id.tv_widget_dark_sky_period, View.GONE)
            }

            // Configure Tab Pills (for 4x2 and 4x1)
            if (layoutId == R.layout.widget_layout_4x2 || layoutId == R.layout.widget_layout_4x1) {
                val pillFrameIds = arrayOf(
                    R.id.btn_widget_tab_0,
                    R.id.btn_widget_tab_1,
                    R.id.btn_widget_tab_2,
                    R.id.btn_widget_tab_3
                )
                val pillBgIds = arrayOf(
                    R.id.iv_pill_bg_0,
                    R.id.iv_pill_bg_1,
                    R.id.iv_pill_bg_2,
                    R.id.iv_pill_bg_3
                )
                val pillTextIds = arrayOf(
                    R.id.tv_pill_text_0,
                    R.id.tv_pill_text_1,
                    R.id.tv_pill_text_2,
                    R.id.tv_pill_text_3
                )

                for (i in 0 until 4) {
                    if (i < tabCount) {
                        val tabLoc = locationPreferences.getTabLocation(i)
                        val isSelected = (i == safeActiveTab)

                        remoteViews.setTextViewText(pillTextIds[i], tabLoc.name)
                        remoteViews.setViewVisibility(pillFrameIds[i], View.VISIBLE)

                        val pillBgColor = if (isSelected) palette.primary else palette.surfaceCardSecondary
                        remoteViews.setInt(pillBgIds[i], "setColorFilter", pillBgColor)

                        val pillTextColor = if (isSelected) {
                            if (ColorUtils.calculateLuminance(palette.primary) > 0.5) Color.BLACK else Color.WHITE
                        } else {
                            palette.textSecondary
                        }
                        remoteViews.setTextColor(pillTextIds[i], pillTextColor)

                        val switchIntent = Intent().apply {
                            component = providerComponent
                            action = WeatherWidgetProvider.ACTION_WIDGET_SWITCH_TAB
                            data = Uri.parse("weatherwidget://tab/$appWidgetId/$i")
                            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                            putExtra(WeatherWidgetProvider.EXTRA_TAB_INDEX, i)
                        }

                        val switchPendingIntent = PendingIntent.getBroadcast(
                            context,
                            (appWidgetId * 100) + i,
                            switchIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        remoteViews.setOnClickPendingIntent(pillFrameIds[i], switchPendingIntent)
                    } else {
                        remoteViews.setViewVisibility(pillFrameIds[i], View.GONE)
                    }
                }
            }

            // Attach Deep-Linking PendingIntent to main body
            val deepLinkIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                data = Uri.parse("weatherwidget://$appWidgetId")
                putExtra(WeatherWidgetProvider.EXTRA_WIDGET_TAB_INDEX, safeActiveTab)
                putExtra(WeatherWidgetProvider.EXTRA_WIDGET_SUBTAB, "CURRENT")
            }
            val deepLinkPendingIntent = PendingIntent.getActivity(
                context,
                appWidgetId,
                deepLinkIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            remoteViews.setOnClickPendingIntent(R.id.widget_main_body, deepLinkPendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, remoteViews)
        }

        private fun formatInstantTime(instant: Instant?, zoneId: ZoneId): String {
            if (instant == null) return "——"
            val zdt = ZonedDateTime.ofInstant(instant, zoneId)
            val formatter = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
            return zdt.format(formatter)
        }
    }

    override suspend fun doWork(): Result {
        val context = applicationContext
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val locationPreferences = LocationPreferences(context)
        val widgetPrefs = WidgetPreferences(context)
        val weatherRepository = weatherRepositoryOverride ?: WeatherRepository()

        val widgetTypes = listOf(
            ComponentName(context, WeatherWidgetProvider4x2::class.java),
            ComponentName(context, WeatherWidgetProvider4x1::class.java),
            ComponentName(context, WeatherWidgetProvider2x1::class.java)
        )

        val allWidgetIds = mutableListOf<Int>()
        for (component in widgetTypes) {
            val widgetIds = appWidgetManager.getAppWidgetIds(component)
            allWidgetIds.addAll(widgetIds.toList())
        }

        val distinctWidgetIds = allWidgetIds.distinct()
        if (distinctWidgetIds.isEmpty()) {
            return Result.success()
        }

        // Collect unique locations assigned to active widgets
        val tabCount = locationPreferences.getTabCount()
        val locationsToFetch = mutableSetOf<Pair<LocationItem, ForecastModel>>()

        for (appWidgetId in distinctWidgetIds) {
            val activeTab = widgetPrefs.getActiveTab(appWidgetId)
            val safeActiveTab = activeTab.coerceIn(0, tabCount - 1)
            val location = locationPreferences.getTabLocation(safeActiveTab)
            val model = locationPreferences.getActiveModelForLocation(location)
            locationsToFetch.add(Pair(location, model))
        }

        // Fetch fresh weather data for each unique location
        for ((location, model) in locationsToFetch) {
            try {
                weatherRepository.getWeatherForecast(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    model = model,
                    forceRefresh = false,
                    isCacheEnabled = locationPreferences.isCacheTtlEnabled(),
                    ttlMinutes = locationPreferences.getCacheTtlMinutes(),
                    locationPreferences = locationPreferences
                )
            } catch (e: Exception) {
                Log.e("WidgetSyncWorker", "Failed to refresh weather for widget location: ${location.name}", e)
            }
        }

        // Redraw all active widgets from updated cache
        for (appWidgetId in distinctWidgetIds) {
            try {
                rebuildAndUpdate(context, appWidgetId)
            } catch (e: Exception) {
                Log.e("WidgetSyncWorker", "Failed to rebuild widget $appWidgetId", e)
            }
        }

        return Result.success()
    }
}
