package com.weatherapp.libre

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import androidx.annotation.Keep
import java.util.Locale

@Keep
enum class ForecastModel(
    val idKey: String,
    val displayName: String,
    val openMeteoKey: String,
    val baseUrl: String,
    val sourceAttribution: String,
    val description: String,
    val isWestern: Boolean,
    val isEnsemble: Boolean = false
) {
    GEM(
        "GEM",
        "GEM (Environment Canada)",
        "gem_global",
        "https://api.open-meteo.com/v1/forecast",
        "Source: Environment Canada GEM Model",
        "Official Environment Canada GEM global forecast model",
        true
    ),
    GFS(
        "GFS",
        "GFS (NOAA, USA)",
        "gfs_seamless",
        "https://api.open-meteo.com/v1/forecast",
        "Source: NOAA GFS Model",
        "NOAA Global Forecast System",
        true
    ),
    ECMWF(
        "ECMWF",
        "ECMWF (European Centre)",
        "ecmwf_ifs025",
        "https://api.open-meteo.com/v1/forecast",
        "Source: ECMWF IFS Model",
        "European Centre for Medium-Range Weather Forecasts (25km)",
        true
    ),
    GEFS(
        "GEFS",
        "GEFS Ensemble (NOAA)",
        "gfs_seamless",
        "https://ensemble-api.open-meteo.com/v1/ensemble",
        "Source: NOAA GEFS Model",
        "NOAA Global Ensemble Forecast System (30 member ensemble)",
        true,
        isEnsemble = true
    ),
    CMA_GFS(
        "CMA_GFS",
        "CMA-GFS (China)",
        "cma_grapes_global",
        "https://api.open-meteo.com/v1/forecast",
        "Source: CMA-GFS (GRAPES_GFS) Model",
        "China Meteorological Administration Global Forecast System",
        false
    );

    fun getDisplayName(context: android.content.Context): String = when (this) {
        GEM -> context.getString(R.string.model_gem_title)
        GFS -> context.getString(R.string.model_gfs_short)
        ECMWF -> context.getString(R.string.model_ecmwf_title)
        GEFS -> context.getString(R.string.model_gefs_title)
        CMA_GFS -> context.getString(R.string.model_cma_title)
    }

    fun getSourceAttribution(context: android.content.Context): String = when (this) {
        GEM -> context.getString(R.string.msg_source_gem_model)
        GFS -> context.getString(R.string.model_gfs_title)
        ECMWF -> context.getString(R.string.model_ecmwf_title)
        GEFS -> context.getString(R.string.model_gefs_title)
        CMA_GFS -> context.getString(R.string.model_cma_title)
    }

    fun getDescription(context: android.content.Context): String = when (this) {
        GEM -> context.getString(R.string.model_gem_desc)
        GFS -> context.getString(R.string.model_gfs_title)
        ECMWF -> context.getString(R.string.model_ecmwf_desc)
        GEFS -> context.getString(R.string.model_gefs_desc)
        CMA_GFS -> context.getString(R.string.model_cma_desc)
    }

    companion object {
        fun fromKey(key: String?): ForecastModel {
            return values().find { it.idKey.equals(key, ignoreCase = true) || it.openMeteoKey.equals(key, ignoreCase = true) } ?: GEM
        }
    }
}

class LocationPreferences(val context: Context) {

    init {
        com.weatherapp.libre.alerts.providers.DefaultEcccMemoStore.init(context)
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences("weather_location_prefs", Context.MODE_PRIVATE)

    companion object {
        private val LOCATION_LOCK = Any()

        val DEFAULT_MENU_ORDER = listOf(
            "CURRENT", "HOURLY", "SEVEN_DAY", "SEPARATOR", 
            "NATURAL_LIGHT", "LUNAR_CALENDAR", "NIGHT_VISIBILITY", "DARK_SKY", 
            "SEPARATOR", "SOLUNAR"
        )

        const val ALERT_CONSENT_UNSET = -1
        const val ALERT_CONSENT_OPT_OUT = 0
        const val ALERT_CONSENT_OPT_IN = 1

        private const val KEY_FIRST_LAUNCH = "is_first_launch"
        private const val KEY_STARTUP_MODE = "app_startup_mode"

        // Last Used Location Keys
        private const val KEY_LAST_NAME = "last_location_name"
        private const val KEY_LAST_ADMIN1 = "last_location_admin1"
        private const val KEY_LAST_COUNTRY = "last_location_country"
        private const val KEY_LAST_LAT = "last_location_lat"
        private const val KEY_LAST_LON = "last_location_lon"

        // Fixed Default Location Keys
        private const val KEY_DEFAULT_NAME = "default_location_name"
        private const val KEY_DEFAULT_ADMIN1 = "default_location_admin1"
        private const val KEY_DEFAULT_COUNTRY = "default_location_country"
        private const val KEY_DEFAULT_LAT = "default_location_lat"
        private const val KEY_DEFAULT_LON = "default_location_lon"

        private const val KEY_THEME = "app_theme_mode"
        private const val KEY_PENDING_REOPEN_THEME = "app_pending_reopen_theme_settings"
        private const val KEY_UNIT_SYSTEM = "app_unit_system"
        private const val KEY_DEFAULT_MODEL = "app_default_model"
        private const val KEY_AUTO_CMA_CHINA = "app_auto_cma_china"
        private const val KEY_ALERT_CONSENT_STATE = "app_alert_consent_state"
        private const val KEY_CACHE_TTL_ENABLED = "app_cache_ttl_enabled"
        private const val KEY_CACHE_TTL_MINUTES = "app_cache_ttl_minutes"

        // Maximum number of persistent forecast cache entries before the oldest are evicted
        private const val MAX_CACHE_ENTRIES = 50
        private const val KEY_ALERT_DISMISSED = "app_alert_dismissed"
        private const val KEY_SOLAR_EVENTS_FIRST_LAUNCH = "is_solar_events_first_launch"
        private const val KEY_RECENT_LOCATIONS_JSON = "app_recent_locations_json"

        private const val KEY_TAB_COUNT = "app_location_tab_count"
        private const val KEY_ACTIVE_TAB_INDEX = "app_active_tab_index"
        private const val KEY_TABS_STICKY = "app_location_tabs_sticky"
        private const val KEY_SHOW_GOLDEN_BLUE_HOUR_MAIN_PAGE = "app_show_golden_blue_hour_main_page"
        private const val KEY_SOLAR_ALARM_MINS = "app_solar_alarm_lead_mins"

        private const val KEY_VOLUME_SCROLL_ENABLED = "app_volume_scroll_enabled"
        private const val KEY_VOLUME_SCROLL_SPEED_INDEX = "app_volume_scroll_speed_index"

        private const val KEY_LAUNCH_COUNT = "app_launch_count"

        const val THEME_SYSTEM = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2
        const val THEME_CREMA = 3
        const val THEME_CHARCOAL = 4
        const val THEME_COBALT = 5
        const val THEME_JADE = 6
        const val THEME_AURORA = 7
        const val THEME_TERRACOTTA = 8
        const val THEME_FROST = 9
        @Deprecated("retained solely to migrate legacy installs")
        const val THEME_FIRE_ENGINE = 10
        const val THEME_ORANGE_GROVE = 11
        const val THEME_FRUTIGER_AERO = 12
        const val THEME_HARDY_HERON = 13

        const val STARTUP_LAST_USED = 0
        const val STARTUP_FIXED_DEFAULT = 1

        val DEFAULT_OTTAWA = LocationItem(
            id = 1000L,
            name = "Ottawa",
            admin1 = "Ontario",
            country = "Canada",
            latitude = 45.4215,
            longitude = -75.6972,
            elevationMeters = 70.0
        )

        val DEFAULT_EDMONTON = LocationItem(
            id = 1001L,
            name = "Edmonton",
            admin1 = "Alberta",
            country = "Canada",
            latitude = 53.5461,
            longitude = -113.4938,
            elevationMeters = 645.0
        )

        val DEFAULT_MONTREAL = LocationItem(
            id = 1002L,
            name = "Montreal",
            admin1 = "Quebec",
            country = "Canada",
            latitude = 45.5019,
            longitude = -73.5674,
            elevationMeters = 216.0
        )

        val DEFAULT_HALIFAX = LocationItem(
            id = 1003L,
            name = "Halifax",
            admin1 = "Nova Scotia",
            country = "Canada",
            latitude = 44.6488,
            longitude = -63.5752,
            elevationMeters = 60.0
        )
    }

    fun setPendingReopenThemeSettings(pending: Boolean) {
        prefs.edit().putBoolean(KEY_PENDING_REOPEN_THEME, pending).apply()
    }

    fun consumePendingReopenThemeSettings(): Boolean {
        val pending = prefs.getBoolean(KEY_PENDING_REOPEN_THEME, false)
        if (pending) {
            prefs.edit().remove(KEY_PENDING_REOPEN_THEME).apply()
        }
        return pending
    }

    fun isVolumeScrollEnabled(): Boolean {
        return prefs.getBoolean(KEY_VOLUME_SCROLL_ENABLED, true)
    }

    fun saveVolumeScrollEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_VOLUME_SCROLL_ENABLED, enabled).apply()
    }

    fun getVolumeScrollSpeedIndex(): Int {
        return prefs.getInt(KEY_VOLUME_SCROLL_SPEED_INDEX, 4).coerceIn(1, 7)
    }

    fun saveVolumeScrollSpeedIndex(index: Int) {
        prefs.edit().putInt(KEY_VOLUME_SCROLL_SPEED_INDEX, index.coerceIn(1, 7)).apply()
    }

    fun incrementAppLaunchCount(): Int {
        val count = prefs.getInt(KEY_LAUNCH_COUNT, 0) + 1
        prefs.edit().putInt(KEY_LAUNCH_COUNT, count).apply()
        return count
    }

    fun getAppLaunchCount(): Int {
        return prefs.getInt(KEY_LAUNCH_COUNT, 0)
    }

    fun resetAppLaunchCount() {
        prefs.edit().putInt(KEY_LAUNCH_COUNT, 0).apply()
    }

    fun getSolarAlarmLeadMinutes(): Int {
        return prefs.getInt(KEY_SOLAR_ALARM_MINS, 10).coerceIn(1, 120)
    }

    fun saveSolarAlarmLeadMinutes(mins: Int) {
        prefs.edit().putInt(KEY_SOLAR_ALARM_MINS, mins.coerceIn(1, 120)).apply()
    }

    fun isShowGoldenBlueHourMainPageEnabled(): Boolean {
        return prefs.getBoolean(KEY_SHOW_GOLDEN_BLUE_HOUR_MAIN_PAGE, false)
    }

    fun saveShowGoldenBlueHourMainPageEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_GOLDEN_BLUE_HOUR_MAIN_PAGE, enabled).apply()
    }

    fun isLocationTabsSticky(): Boolean {
        return prefs.getBoolean(KEY_TABS_STICKY, true)
    }

    fun saveLocationTabsSticky(sticky: Boolean) {
        prefs.edit().putBoolean(KEY_TABS_STICKY, sticky).apply()
    }

    fun getTabCount(): Int {
        return prefs.getInt(KEY_TAB_COUNT, 2).coerceIn(1, 4)
    }

    fun saveTabCount(count: Int) {
        prefs.edit().putInt(KEY_TAB_COUNT, count.coerceIn(1, 4)).apply()
    }

    fun getActiveTabIndex(): Int {
        val maxIndex = getTabCount() - 1
        return prefs.getInt(KEY_ACTIVE_TAB_INDEX, 0).coerceIn(0, maxIndex)
    }

    fun saveActiveTabIndex(index: Int) {
        val maxIndex = getTabCount() - 1
        prefs.edit().putInt(KEY_ACTIVE_TAB_INDEX, index.coerceIn(0, maxIndex)).apply()
    }

    fun getSavedInlineSubTab(): Int {
        return prefs.getInt("app_inline_sub_tab_index", 0).coerceIn(0, 6)
    }

    fun saveInlineSubTab(index: Int) {
        prefs.edit().putInt("app_inline_sub_tab_index", index.coerceIn(0, 6)).apply()
    }

    fun getSevenDayAsMenuItem(): Boolean {
        return prefs.getBoolean("app_seven_day_as_menu_item", false)
    }

    fun saveSevenDayAsMenuItem(enabled: Boolean) {
        prefs.edit().putBoolean("app_seven_day_as_menu_item", enabled).apply()
    }

    fun getMenuOrder(): List<String> {
        val str = prefs.getString("app_menu_order_json", null)
        if (str == null) return DEFAULT_MENU_ORDER

        val list = try {
            str.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        } catch (e: Exception) {
            return DEFAULT_MENU_ORDER
        }

        val containsAllTabs = InlineSubTab.entries.all { list.contains(it.name) }

        if (!containsAllTabs) {
            val updatedList = list.toMutableList()
            InlineSubTab.entries.forEach { subTab ->
                if (!updatedList.contains(subTab.name)) {
                    updatedList.add(subTab.name)
                }
            }
            saveMenuOrder(updatedList)
            return updatedList
        }

        return list
    }

    fun saveMenuOrder(order: List<String>) {
        val str = order.joinToString(",")
        prefs.edit().putString("app_menu_order_json", str).apply()
    }

    fun getMenuEnabledFlags(): Map<InlineSubTab, Boolean> {
        val defaultFlags = InlineSubTab.entries.associateWith { true }
        val str = prefs.getString("app_menu_enabled_json", null) ?: return defaultFlags
        return try {
            val map = defaultFlags.toMutableMap()
            val pairs = str.split(",")
            for (pair in pairs) {
                val parts = pair.split(":")
                if (parts.size == 2) {
                    val tabName = parts[0].trim()
                    val enabled = parts[1].trim().toBoolean()
                    try {
                        val tab = InlineSubTab.valueOf(tabName)
                        map[tab] = enabled
                    } catch (e: Exception) {
                        // Ignore
                    }
                }
            }
            map
        } catch (e: Exception) {
            defaultFlags
        }
    }

    fun saveMenuEnabledFlags(flags: Map<InlineSubTab, Boolean>) {
        val str = flags.entries.joinToString(",") { "${it.key.name}:${it.value}" }
        prefs.edit().putString("app_menu_enabled_json", str).apply()
    }

    fun getDefaultLaunchSubTab(): InlineSubTab {
        val name = prefs.getString("app_default_launch_sub_tab", InlineSubTab.CURRENT.name)
        return try {
            if (name != null) InlineSubTab.valueOf(name) else InlineSubTab.CURRENT
        } catch (e: Exception) {
            InlineSubTab.CURRENT
        }
    }

    fun saveDefaultLaunchSubTab(subTab: InlineSubTab) {
        prefs.edit().putString("app_default_launch_sub_tab", subTab.name).apply()
    }

    private fun SharedPreferences.Editor.putDouble(key: String, value: Double): SharedPreferences.Editor {
        return putLong(key, java.lang.Double.doubleToRawLongBits(value))
    }

    private fun SharedPreferences.getDouble(key: String, defaultValue: Double): Double {
        if (!contains(key)) return defaultValue
        return try {
            java.lang.Double.longBitsToDouble(getLong(key, 0L))
        } catch (e: Exception) {
            getFloat(key, defaultValue.toFloat()).toDouble()
        }
    }

    fun getTabLocation(tabIndex: Int): LocationItem {
        synchronized(LOCATION_LOCK) {
            val keyName = "tab_${tabIndex}_name"
            val name = prefs.getString(keyName, null)
            if (name != null) {
                val id = prefs.getLong("tab_${tabIndex}_id", (1001 + tabIndex).toLong())
                val admin1 = prefs.getString("tab_${tabIndex}_admin1", null)
                val country = prefs.getString("tab_${tabIndex}_country", null)
                val countryCode = prefs.getString("tab_${tabIndex}_country_code", null)
                val lat = prefs.getDouble("tab_${tabIndex}_lat", 0.0)
                val lon = prefs.getDouble("tab_${tabIndex}_lon", 0.0)
                val elev = prefs.getDouble("tab_${tabIndex}_elev", 0.0)
                return LocationItem(id = id, name = name, admin1 = admin1, country = country, countryCode = countryCode, latitude = lat, longitude = lon, elevationMeters = elev)
            }

            // Default initial fallbacks for tabs (persist on first read so tabs remain independent and stable)
            val defaultLoc = when (tabIndex) {
                0 -> getInitialLocation()
                1 -> LocationItem(id = 1004L, name = "Toronto", admin1 = "Ontario", country = "Canada", latitude = 43.6532, longitude = -79.3832, elevationMeters = 76.0)
                2 -> LocationItem(id = 1005L, name = "Saskatoon", admin1 = "Saskatchewan", country = "Canada", latitude = 52.1579, longitude = -106.6702, elevationMeters = 481.0)
                3 -> LocationItem(id = 1006L, name = "Vancouver", admin1 = "British Columbia", country = "Canada", latitude = 49.2827, longitude = -123.1207, elevationMeters = 0.0)
                else -> getInitialLocation()
            }

            saveTabLocation(tabIndex, defaultLoc)
            return defaultLoc
        }
    }

    fun saveTabLocation(tabIndex: Int, location: LocationItem) {
        synchronized(LOCATION_LOCK) {
            prefs.edit()
                .putLong("tab_${tabIndex}_id", location.id)
                .putString("tab_${tabIndex}_name", location.name)
                .putString("tab_${tabIndex}_admin1", location.admin1)
                .putString("tab_${tabIndex}_country", location.country)
                .putString("tab_${tabIndex}_country_code", location.countryCode)
                .putDouble("tab_${tabIndex}_lat", location.latitude)
                .putDouble("tab_${tabIndex}_lon", location.longitude)
                .putDouble("tab_${tabIndex}_elev", location.elevationMeters)
                .apply()
        }
    }

    fun isChinaLocation(location: LocationItem): Boolean {
        val country = location.country?.trim() ?: ""
        return country.equals("China", ignoreCase = true) ||
                country.equals("CN", ignoreCase = true) ||
                country.equals("PRC", ignoreCase = true) ||
                country.contains("China", ignoreCase = true)
    }

    fun getActiveModelForLocation(location: LocationItem): ForecastModel {
        if (isChinaLocation(location) && isAutoCmaChinaEnabled()) {
            return ForecastModel.CMA_GFS
        }
        return getSavedForecastModel()
    }

    fun getSavedForecastModel(): ForecastModel {
        val key = prefs.getString(KEY_DEFAULT_MODEL, ForecastModel.GEM.idKey)
        return ForecastModel.fromKey(key)
    }

    fun saveForecastModel(model: ForecastModel) {
        prefs.edit().putString(KEY_DEFAULT_MODEL, model.idKey).apply()
    }

    fun isAutoCmaChinaEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTO_CMA_CHINA, true)
    }

    fun saveAutoCmaChinaEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_CMA_CHINA, enabled).apply()
    }

    fun getAlertConsentState(): Int {
        return prefs.getInt(KEY_ALERT_CONSENT_STATE, ALERT_CONSENT_UNSET)
    }

    fun saveAlertConsentState(state: Int) {
        prefs.edit().putInt(KEY_ALERT_CONSENT_STATE, state).apply()
    }

    fun migrateLegacyConsentState() {
        if (prefs.contains(KEY_ALERT_CONSENT_STATE)) {
            return
        }

        val editor = prefs.edit()
        var migrated = false

        if (prefs.contains("app_weather_alerts_enabled")) {
            val legacyEnabled = prefs.getBoolean("app_weather_alerts_enabled", false)
            val newState = if (legacyEnabled) ALERT_CONSENT_OPT_IN else ALERT_CONSENT_OPT_OUT
            editor.putInt(KEY_ALERT_CONSENT_STATE, newState)
            migrated = true
        } else if (prefs.contains("app_alert_consent_version")) {
            editor.putInt(KEY_ALERT_CONSENT_STATE, ALERT_CONSENT_OPT_IN)
            migrated = true
        }

        if (migrated) {
            editor.remove("app_weather_alerts_enabled")
            editor.remove("app_alert_consent_version")
            editor.apply()
        }
    }

    fun isCacheTtlEnabled(): Boolean {
        return prefs.getBoolean(KEY_CACHE_TTL_ENABLED, true)
    }

    fun saveCacheTtlEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CACHE_TTL_ENABLED, enabled).apply()
    }

    fun getCacheTtlMinutes(): Int {
        return prefs.getInt(KEY_CACHE_TTL_MINUTES, 60)
    }

    fun saveCacheTtlMinutes(minutes: Int) {
        prefs.edit().putInt(KEY_CACHE_TTL_MINUTES, minutes).apply()
    }

    fun getPersistentCacheJson(cacheKey: String): String? {
        return prefs.getString("disk_cache_json_$cacheKey", null)
    }

    fun getPersistentCacheTime(cacheKey: String): Long {
        return prefs.getLong("disk_cache_time_$cacheKey", 0L)
    }

    fun getCachedPageHtml(pageKey: String): String? {
        return prefs.getString("web_page_html_$pageKey", null)
    }

    fun getCachedPageTime(pageKey: String): Long {
        return prefs.getLong("web_page_time_$pageKey", 0L)
    }

    fun saveCachedPageHtml(pageKey: String, html: String) {
        prefs.edit()
            .putString("web_page_html_$pageKey", html)
            .putLong("web_page_time_$pageKey", System.currentTimeMillis())
            .apply()
    }

    fun savePersistentCache(cacheKey: String, json: String, timestampMillis: Long) {
        synchronized(LOCATION_LOCK) {
            prefs.edit()
                .putString("disk_cache_json_$cacheKey", json)
                .putLong("disk_cache_time_$cacheKey", timestampMillis)
                .apply()

            enforceCacheCap()
        }
    }

    private fun enforceCacheCap() {
        val timeKeys = prefs.all.keys.filter { it.startsWith("disk_cache_time_") }
        if (timeKeys.size <= MAX_CACHE_ENTRIES) {
            return
        }

        val entries = timeKeys.map { key ->
            val cacheKey = key.removePrefix("disk_cache_time_")
            cacheKey to prefs.getLong("disk_cache_time_$cacheKey", 0L)
        }.sortedBy { it.second }

        val toEvict = entries.size - MAX_CACHE_ENTRIES
        val evictedKeys = entries.take(toEvict).map { it.first }

        val editor = prefs.edit()
        for (cacheKey in evictedKeys) {
            editor.remove("disk_cache_json_$cacheKey")
            editor.remove("disk_cache_time_$cacheKey")
        }
        editor.apply()
    }

    fun isFirstLaunchSolarEvents(): Boolean {
        return prefs.getBoolean(KEY_SOLAR_EVENTS_FIRST_LAUNCH, true)
    }

    fun setSolarEventsFirstLaunchCompleted() {
        prefs.edit().putBoolean(KEY_SOLAR_EVENTS_FIRST_LAUNCH, false).apply()
    }

    fun isFirstLaunch(): Boolean {
        return prefs.getBoolean(KEY_FIRST_LAUNCH, true)
    }

    fun setFirstLaunchCompleted() {
        prefs.edit().putBoolean(KEY_FIRST_LAUNCH, false).apply()
    }

    fun getStartupMode(): Int {
        return prefs.getInt(KEY_STARTUP_MODE, STARTUP_LAST_USED)
    }

    fun saveStartupMode(mode: Int) {
        prefs.edit().putInt(KEY_STARTUP_MODE, mode).apply()
    }

    /**
     * Gets up to 4 recent locations.
     * The very top-most location (index 0) is ALWAYS set to the default location.
     */
    fun getRecentLocations(): List<LocationItem> {
        val defaultLoc = getFixedDefaultLocation()
        val resultList = mutableListOf<LocationItem>()
        resultList.add(defaultLoc)

        val json = prefs.getString(KEY_RECENT_LOCATIONS_JSON, null)
        if (!json.isNullOrEmpty()) {
            try {
                val type = object : TypeToken<List<LocationItem>>() {}.type
                val list: List<LocationItem>? = Gson().fromJson(json, type)
                if (!list.isNullOrEmpty()) {
                    for (item in list) {
                        if (!item.displayName.equals(defaultLoc.displayName, ignoreCase = true) &&
                            resultList.none { it.displayName.equals(item.displayName, ignoreCase = true) }) {
                            resultList.add(item)
                            if (resultList.size >= 4) break
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("LocationPreferences", "Failed to parse recent locations JSON", e)
            }
        }

        if (resultList.size < 4) {
            val lastUsed = getLastUsedLocation()
            if (!lastUsed.displayName.equals(defaultLoc.displayName, ignoreCase = true) &&
                resultList.none { it.displayName.equals(lastUsed.displayName, ignoreCase = true) }) {
                resultList.add(lastUsed)
            }
        }

        return resultList.take(4)
    }

    fun saveRecentLocation(location: LocationItem) {
        synchronized(LOCATION_LOCK) {
            val currentList = getRecentLocations().toMutableList()
            currentList.removeAll { it.displayName.equals(location.displayName, ignoreCase = true) }
            currentList.add(0, location)
            val trimmed = currentList.take(4)
            try {
                val json = Gson().toJson(trimmed)
                prefs.edit().putString(KEY_RECENT_LOCATIONS_JSON, json).apply()
            } catch (e: Exception) {
                Log.w("LocationPreferences", "Failed to save recent locations JSON", e)
            }
        }
    }

    fun getInitialLocation(): LocationItem {
        return if (getStartupMode() == STARTUP_FIXED_DEFAULT) {
            getFixedDefaultLocation()
        } else {
            getLastUsedLocation()
        }
    }

    fun getLastUsedLocation(): LocationItem {
        val name = prefs.getString(KEY_LAST_NAME, null) ?: return DEFAULT_OTTAWA
        val admin1 = prefs.getString(KEY_LAST_ADMIN1, null)
        val country = prefs.getString(KEY_LAST_COUNTRY, null)
        val lat = prefs.getFloat(KEY_LAST_LAT, DEFAULT_OTTAWA.latitude.toFloat()).toDouble()
        val lon = prefs.getFloat(KEY_LAST_LON, DEFAULT_OTTAWA.longitude.toFloat()).toDouble()

        return LocationItem(
            name = name,
            admin1 = admin1,
            country = country,
            latitude = lat,
            longitude = lon
        )
    }

    fun saveLastUsedLocation(location: LocationItem) {
        synchronized(LOCATION_LOCK) {
            prefs.edit()
                .putString(KEY_LAST_NAME, location.name)
                .putString(KEY_LAST_ADMIN1, location.admin1)
                .putString(KEY_LAST_COUNTRY, location.country)
                .putFloat(KEY_LAST_LAT, location.latitude.toFloat())
                .putFloat(KEY_LAST_LON, location.longitude.toFloat())
                .apply()

            saveRecentLocation(location)
        }
    }

    fun getFixedDefaultLocation(): LocationItem {
        val name = prefs.getString(KEY_DEFAULT_NAME, null) ?: return getLastUsedLocation()
        val admin1 = prefs.getString(KEY_DEFAULT_ADMIN1, null)
        val country = prefs.getString(KEY_DEFAULT_COUNTRY, null)
        val lat = prefs.getFloat(KEY_DEFAULT_LAT, DEFAULT_OTTAWA.latitude.toFloat()).toDouble()
        val lon = prefs.getFloat(KEY_DEFAULT_LON, DEFAULT_OTTAWA.longitude.toFloat()).toDouble()

        return LocationItem(
            name = name,
            admin1 = admin1,
            country = country,
            latitude = lat,
            longitude = lon
        )
    }

    fun saveFixedDefaultLocation(location: LocationItem) {
        prefs.edit()
            .putString(KEY_DEFAULT_NAME, location.name)
            .putString(KEY_DEFAULT_ADMIN1, location.admin1)
            .putString(KEY_DEFAULT_COUNTRY, location.country)
            .putFloat(KEY_DEFAULT_LAT, location.latitude.toFloat())
            .putFloat(KEY_DEFAULT_LON, location.longitude.toFloat())
            .apply()
    }

    fun getSavedUnitSystem(): UnitSystem {
        val name = prefs.getString(KEY_UNIT_SYSTEM, UnitSystem.METRIC.name)
        return try {
            UnitSystem.valueOf(name ?: UnitSystem.METRIC.name)
        } catch (e: Exception) {
            UnitSystem.METRIC
        }
    }

    fun saveUnitSystem(unitSystem: UnitSystem) {
        prefs.edit().putString(KEY_UNIT_SYSTEM, unitSystem.name).apply()
    }

    private fun resolveThemeMode(rawMode: Int): Int {
        if (rawMode == THEME_FIRE_ENGINE) {
            saveThemeMode(THEME_DARK)
            return THEME_DARK
        }
        return rawMode
    }

    fun getSavedThemeMode(): Int {
        val raw = prefs.getInt(KEY_THEME, THEME_SYSTEM)
        return resolveThemeMode(raw)
    }

    fun getThemeMode(): Int = getSavedThemeMode()

    fun resolveEffectiveTheme(themeMode: Int, context: Context = this.context): Int {
        if (themeMode != THEME_SYSTEM) return themeMode
        val sysConfig = android.content.res.Resources.getSystem().configuration
        val nightMode = sysConfig.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return if (nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES) {
            THEME_DARK
        } else {
            THEME_LIGHT
        }
    }

    fun saveThemeMode(mode: Int) {
        prefs.edit().putInt(KEY_THEME, mode).apply()
    }

    fun getLunarReminderLeadTimeMinutes(): Int {
        return prefs.getInt("lunar_reminder_lead_time_minutes", 1440)
    }

    fun saveLunarReminderLeadTimeMinutes(minutes: Int) {
        prefs.edit().putInt("lunar_reminder_lead_time_minutes", minutes).apply()
    }

    fun getUnitSystem(): UnitSystem = getSavedUnitSystem()

    fun getDefaultModel(): String = getSavedForecastModel().openMeteoKey

    fun saveDefaultModel(key: String) {
        val model = ForecastModel.fromKey(key)
        saveForecastModel(model)
    }

    fun clearPersistentCache() {
        val editor = prefs.edit()
        val allKeys = prefs.all.keys
        for (k in allKeys) {
            if (k.startsWith("disk_cache_")) {
                editor.remove(k)
            }
        }
        editor.apply()
    }

    fun checkAndPerformVersionMigration(currentVersionCode: Int, repository: WeatherRepository) {
        val lastVersionCode = prefs.getInt("app_last_run_version_code", -1)
        if (lastVersionCode != -1 && currentVersionCode > lastVersionCode) {
            val lastVersionName = prefs.getString("app_last_run_version_name", "v$lastVersionCode") ?: "v$lastVersionCode"
            val currentVersionName = "v$currentVersionCode"

            clearSelectiveWipeData(repository)

            android.util.Log.i("MigrationManager", "Migration: Cleared transient cache for $lastVersionName -> $currentVersionName, preserved user preferences")
        }
        prefs.edit()
            .putInt("app_last_run_version_code", currentVersionCode)
            .putString("app_last_run_version_name", "v$currentVersionCode")
            .apply()
    }

    fun clearSelectiveWipeData(repository: WeatherRepository) {
        repository.clearMemoryAndDiskCache(this)
        val editor = prefs.edit()
        val allKeys = prefs.all.keys
        val keysToRemove = mutableListOf<String>()

        for (k in allKeys) {
            if (k.startsWith("disk_cache_") ||
                k.startsWith("web_page_") ||
                k.startsWith("app_active_alert_") ||
                (k.startsWith("app_alert_") && k != KEY_ALERT_CONSENT_STATE) ||
                k.startsWith("app_alert_dismissed_") ||
                k.startsWith("log_") ||
                k.startsWith("temp_") ||
                k == "pending_reopen_theme_settings" ||
                k == "last_fetch_timestamp" ||
                k == "last_weather_refresh_time"
            ) {
                keysToRemove.add(k)
            }
        }

        for (key in keysToRemove) {
            editor.remove(key)
        }
        editor.apply()
    }

    fun getVolumeScrollSpeedLabel(context: Context, index: Int): String {
        val resId = when (index) {
            1 -> R.string.volume_speed_1
            2 -> R.string.volume_speed_2
            3 -> R.string.volume_speed_3
            4 -> R.string.volume_speed_4
            5 -> R.string.volume_speed_5
            6 -> R.string.volume_speed_6
            7 -> R.string.volume_speed_7
            else -> R.string.volume_speed_4
        }
        return context.getString(resId)
    }



    fun clearTestAlertDismissals() {
        val editor = prefs.edit()
        val allKeys = prefs.all.keys
        for (k in allKeys) {
            if (k.startsWith("${KEY_ALERT_DISMISSED}_")) {
                editor.remove(k)
            }
        }
        editor.apply()
    }

    fun isAlertDismissed(alertId: String, location: LocationItem): Boolean {
        val coordKey = String.format(Locale.US, "%.4f_%.4f", location.latitude, location.longitude)
        return prefs.getBoolean("${KEY_ALERT_DISMISSED}_${coordKey}_$alertId", false)
    }

    fun dismissAlert(alertId: String, location: LocationItem) {
        val coordKey = String.format(Locale.US, "%.4f_%.4f", location.latitude, location.longitude)
        prefs.edit().putBoolean("${KEY_ALERT_DISMISSED}_${coordKey}_$alertId", true).apply()
    }

    fun getSavedLocation(): LocationItem = getInitialLocation()
    fun saveLocation(location: LocationItem) = saveLastUsedLocation(location)
}
