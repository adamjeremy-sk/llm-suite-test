package com.weatherapp.libre

import com.weatherapp.theme.BoutiqueThemeEngine
import com.weatherapp.theme.ColorPalette
import com.weatherapp.libre.BuildConfig
import android.app.Dialog
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedCallback
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.util.TypedValue
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import android.view.KeyEvent
import androidx.core.view.GravityCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.weatherapp.libre.alerts.AlertNotificationHelper
import com.weatherapp.libre.databinding.ActivityMainBinding
import com.weatherapp.libre.databinding.DialogAboutSettingsBinding
import com.weatherapp.libre.databinding.DialogAlertsSettingsBinding
import com.weatherapp.libre.databinding.DialogBehaviourScreenBinding
import com.weatherapp.libre.databinding.DialogCacheSettingsBinding
import com.weatherapp.libre.databinding.DialogInfoScreenBinding
import com.weatherapp.libre.databinding.DialogLicensesSettingsBinding
import com.weatherapp.libre.databinding.DialogMenuOrganizationScreenBinding
import com.weatherapp.libre.databinding.DialogModelsSettingsBinding
import com.weatherapp.libre.databinding.DialogPrivacySettingsBinding
import com.weatherapp.libre.databinding.DialogSystemScreenBinding
import com.weatherapp.libre.databinding.DialogTabsSettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class SettingsDialogManager(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val locationPreferences: LocationPreferences
) {

    private val menuStack = mutableListOf<Dialog>()
    private var wasPopped = false

    fun trackDialog(dialog: Dialog) {
        SystemBarContrastHelper.applyDialogEdgeToEdge(dialog)
        menuStack.add(dialog)
        dialog.setOnDismissListener {
            menuStack.remove(dialog)
            if (menuStack.isEmpty() && wasPopped) {
                openDrawer()
            }
        }
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) {
                    popTopMenu()
                }
                true
            } else {
                false
            }
        }
    }

    fun popTopMenu() {
        if (menuStack.isNotEmpty()) {
            wasPopped = true
            menuStack.last().dismiss()
            wasPopped = false
        }
    }

    fun getMenuStackDepth() = menuStack.size

    fun cleanup() {
        menuStack.toList().forEach { dialog ->
            if (dialog.isShowing) {
                try {
                    dialog.dismiss()
                } catch (e: Exception) {
                    // Window may already be detached
                }
            }
        }
        menuStack.clear()
    }

    fun openDrawer() {
        (activity as? MainActivity)?.binding?.drawerLayout?.openDrawer(GravityCompat.START)
    }

    private fun getAaaTitleColor(bgColor: Int): Int {
        val r = Color.red(bgColor) / 255.0
        val g = Color.green(bgColor) / 255.0
        val b = Color.blue(bgColor) / 255.0

        val rs = if (r <= 0.03928) r / 12.92 else Math.pow((r + 0.055) / 1.055, 2.4)
        val gs = if (g <= 0.03928) g / 12.92 else Math.pow((g + 0.055) / 1.055, 2.4)
        val bs = if (b <= 0.03928) b / 12.92 else Math.pow((b + 0.055) / 1.055, 2.4)

        val luminance = 0.2126 * rs + 0.7152 * gs + 0.0722 * bs
        return if (luminance > 0.4) Color.BLACK else Color.WHITE
    }

    fun showWebViewSettingsPage(title: String, url: String) {
        if (activity.isFinishing || activity.isDestroyed) return

        val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar)
        val palette = BoutiqueThemeEngine.getThemePalette(locationPreferences.getSavedThemeMode())
        val density = activity.resources.displayMetrics.density

        val headerBg = palette.surfaceCard
        val headerTitleColor = getAaaTitleColor(headerBg)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(palette.backgroundLight)
        }

        // Top Header Bar
        val cardHeader = MaterialCardView(activity).apply {
            radius = 0f
            strokeWidth = 0
            setCardBackgroundColor(ColorStateList.valueOf(headerBg))
            cardElevation = 2 * density
        }

        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (14 * density).toInt())
        }

        val btnBack = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_back)
            contentDescription = "Back"
            val typedValue = TypedValue()
            activity.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, typedValue, true)
            setBackgroundResource(typedValue.resourceId)
            setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
            setColorFilter(headerTitleColor)
            setOnClickListener { dialog.dismiss() }
        }

        val tvTitle = TextView(activity).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(headerTitleColor)
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
            lp.setMargins((16 * density).toInt(), 0, 0, 0)
            layoutParams = lp
        }

        headerRow.addView(btnBack)
        headerRow.addView(tvTitle)
        cardHeader.addView(headerRow)
        root.addView(cardHeader)

        // Progress Bar
        val progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.VISIBLE
        }
        root.addView(progressBar)

        // Fullscreen In-App WebView with Pull-to-Refresh
        val swipeRefresh = androidx.swiperefreshlayout.widget.SwipeRefreshLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val webView = WebView(activity).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    progressBar.visibility = View.GONE
                    swipeRefresh.isRefreshing = false
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
                    val host = request.url.host?.lowercase(Locale.ROOT) ?: return false
                    if (host == "weatherapplibre.ca" || host == "www.weatherapplibre.ca") {
                        return false
                    }
                    try {
                        activity.startActivity(Intent(Intent.ACTION_VIEW, request.url))
                    } catch (e: Exception) {
                        Toast.makeText(activity, activity.getString(R.string.toast_could_not_open_browser), Toast.LENGTH_SHORT).show()
                    }
                    return true
                }
            }
        }

        swipeRefresh.addView(webView)
        root.addView(swipeRefresh)

        fun fetchPageContent(forceRefresh: Boolean = false) {
            val pageKey = url.substringAfterLast("/")
            val cachedHtml = locationPreferences.getCachedPageHtml(pageKey)
            val cachedTime = locationPreferences.getCachedPageTime(pageKey)
            val sevenDaysMs = 7 * 24 * 60 * 60 * 1000L
            val isCacheValid = !forceRefresh && cachedHtml != null && (System.currentTimeMillis() - cachedTime) < sevenDaysMs

            if (isCacheValid) {
                progressBar.visibility = View.GONE
                swipeRefresh.isRefreshing = false
                webView.loadDataWithBaseURL(url, cachedHtml, "text/html", "UTF-8", null)
            } else {
                if (!forceRefresh) progressBar.visibility = View.VISIBLE
                activity.lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val client = OkHttpClient()
                        val request = Request.Builder().url(url).build()
                        val html = suspendCancellableCoroutine<String> { continuation ->
                            val call = client.newCall(request)
                            continuation.invokeOnCancellation {
                                try {
                                    call.cancel()
                                } catch (e: Exception) {
                                    // Ignore cancellation exception during call.cancel()
                                }
                            }
                            call.enqueue(object : Callback {
                                override fun onResponse(call: Call, response: Response) {
                                    response.use { resp ->
                                        val body = resp.body?.string()
                                        if (!resp.isSuccessful || body.isNullOrEmpty()) {
                                            if (continuation.isActive) {
                                                continuation.resumeWithException(java.io.IOException("HTTP ${resp.code}"))
                                            }
                                        } else {
                                            if (continuation.isActive) {
                                                continuation.resume(body)
                                            }
                                        }
                                    }
                                }

                                override fun onFailure(call: Call, e: java.io.IOException) {
                                    if (continuation.isActive) {
                                        continuation.resumeWithException(e)
                                    }
                                }
                            })
                        }
                        locationPreferences.saveCachedPageHtml(pageKey, html)
                        withContext(Dispatchers.Main) {
                            if (!activity.isFinishing && !activity.isDestroyed) {
                                progressBar.visibility = View.GONE
                                swipeRefresh.isRefreshing = false
                                webView.loadDataWithBaseURL(url, html, "text/html", "UTF-8", null)
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            if (!activity.isFinishing && !activity.isDestroyed) {
                                progressBar.visibility = View.GONE
                                swipeRefresh.isRefreshing = false
                                if (cachedHtml != null) {
                                    webView.loadDataWithBaseURL(url, cachedHtml, "text/html", "UTF-8", null)
                                } else {
                                    webView.loadUrl(url)
                                }
                            }
                        }
                    }
                }
            }
        }

        swipeRefresh.setOnRefreshListener {
            fetchPageContent(forceRefresh = true)
        }

        fetchPageContent(forceRefresh = false)

        dialog.setContentView(root)
        trackDialog(dialog)

        if (!activity.isFinishing && !activity.isDestroyed) {
            dialog.show()
        }
    }

    fun showLocationTabsSettingsScreen() {
        if (activity.isFinishing || activity.isDestroyed) return

        val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar)
        val dialogBinding = DialogTabsSettingsBinding.inflate(activity.layoutInflater)
        dialog.setContentView(dialogBinding.root)

        val palette = BoutiqueThemeEngine.getThemePalette(locationPreferences.getSavedThemeMode())
        val headerBg = palette.surfaceCard
        val headerTitleColor = getAaaTitleColor(headerBg)

        dialogBinding.root.setBackgroundColor(palette.backgroundLight)
        dialogBinding.cardHeader.setCardBackgroundColor(ColorStateList.valueOf(headerBg))
        dialogBinding.btnBack.setColorFilter(headerTitleColor)
        val headerRow = dialogBinding.cardHeader.getChildAt(0) as? LinearLayout
        (headerRow?.getChildAt(1) as? TextView)?.setTextColor(headerTitleColor)
        dialogBinding.cardHeader.strokeColor = palette.dividerColor

        dialogBinding.cardStickyTabsSwitch.setCardBackgroundColor(ColorStateList.valueOf(palette.surfaceCard))
        dialogBinding.cardStickyTabsSwitch.strokeColor = palette.dividerColor
        dialogBinding.tvStickyTitle.setTextColor(palette.textPrimary)
        dialogBinding.tvStickyDesc.setTextColor(palette.textSecondary)
        dialogBinding.tvTabsCountHeader.setTextColor(palette.textPrimary)

        dialogBinding.btnBack.setOnClickListener { dialog.dismiss() }

        val isSticky = locationPreferences.isLocationTabsSticky()
        dialogBinding.switchStickyTabs.isChecked = isSticky
        dialogBinding.switchStickyTabs.setOnCheckedChangeListener { _, checked ->
            locationPreferences.saveLocationTabsSticky(checked)
            if (activity is MainActivity) {
                activity.updateStickyTabsPosition()
            }
            val msg = if (checked) activity.getString(R.string.toast_sticky_tabs_pinned) else activity.getString(R.string.toast_sticky_tabs_scroll)
            Toast.makeText(activity.applicationContext, msg, Toast.LENGTH_SHORT).show()
        }

        val currentCount = locationPreferences.getTabCount()
        dialogBinding.rgTabOptions.removeAllViews()

        val density = activity.resources.displayMetrics.density
        val tabOptions = listOf(
            1 to (activity.getString(R.string.option_1_tab_title) to activity.getString(R.string.option_1_tab_desc)),
            2 to (activity.getString(R.string.option_2_tabs_title) to activity.getString(R.string.option_2_tabs_desc)),
            3 to (activity.getString(R.string.option_3_tabs_title) to activity.getString(R.string.option_3_tabs_desc)),
            4 to (activity.getString(R.string.option_4_tabs_title) to activity.getString(R.string.option_4_tabs_desc))
        )

        for ((count, info) in tabOptions) {
            val card = MaterialCardView(activity).apply {
                radius = 12 * density
                strokeWidth = (1 * density).toInt()
                strokeColor = palette.dividerColor
                setCardBackgroundColor(palette.surfaceCard)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(0, 0, 0, (10 * density).toInt())
                layoutParams = lp
            }

            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (14 * density).toInt())
            }

            val rb = RadioButton(activity).apply {
                isChecked = (currentCount == count)
                isClickable = false
                isFocusable = false
            }

            val col = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
                lp.setMargins((12 * density).toInt(), 0, 0, 0)
                layoutParams = lp
            }

            val titleTv = TextView(activity).apply {
                text = info.first
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(palette.textPrimary)
            }

            val descTv = TextView(activity).apply {
                text = info.second
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(palette.textSecondary)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(0, (2 * density).toInt(), 0, 0)
                layoutParams = lp
            }

            col.addView(titleTv)
            col.addView(descTv)
            row.addView(rb)
            row.addView(col)
            card.addView(row)

            card.setOnClickListener {
                locationPreferences.saveTabCount(count)
                WidgetSyncWorker.notifyWidgetsInvalidated(activity.applicationContext)
                if (activity is MainActivity) {
                    activity.setupLocationTabs()
                }
                Toast.makeText(activity.applicationContext, activity.getString(R.string.toast_tab_count_updated, count), Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                showLocationTabsSettingsScreen()
            }

            dialogBinding.rgTabOptions.addView(card)
        }

        trackDialog(dialog)
        if (!activity.isFinishing && !activity.isDestroyed) {
            dialog.show()
        }
    }

    fun showModelsSettingsScreen(weatherDisplayManager: WeatherDisplayManager) {
        if (activity.isFinishing || activity.isDestroyed) return

        val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar)
        val dialogBinding = DialogModelsSettingsBinding.inflate(activity.layoutInflater)
        dialog.setContentView(dialogBinding.root)

        val palette = BoutiqueThemeEngine.getThemePalette(locationPreferences.getSavedThemeMode())
        val density = activity.resources.displayMetrics.density
        val headerBg = palette.surfaceCard
        val headerTitleColor = getAaaTitleColor(headerBg)

        dialogBinding.root.setBackgroundColor(palette.backgroundLight)
        dialogBinding.cardHeader.setCardBackgroundColor(ColorStateList.valueOf(headerBg))
        dialogBinding.btnBack.setColorFilter(headerTitleColor)
        val headerRow = dialogBinding.cardHeader.getChildAt(0) as? LinearLayout
        (headerRow?.getChildAt(1) as? TextView)?.setTextColor(headerTitleColor)
        dialogBinding.cardHeader.strokeColor = palette.dividerColor

        dialogBinding.btnBack.setOnClickListener { dialog.dismiss() }

        dialogBinding.tvWesternHeader.setTextColor(palette.textPrimary)
        dialogBinding.tvChineseHeader.setTextColor(palette.textPrimary)
        dialogBinding.tvAutoChinaHeader.setTextColor(palette.textPrimary)
        dialogBinding.tvAutoChinaTitle.setTextColor(palette.textPrimary)
        dialogBinding.tvAutoChinaDesc.setTextColor(palette.textSecondary)

        dialogBinding.cardAutoChina.setCardBackgroundColor(ColorStateList.valueOf(palette.surfaceCard))
        dialogBinding.cardAutoChina.strokeColor = palette.dividerColor

        val currentModel = locationPreferences.getSavedForecastModel()

        // Populate Western Models
        dialogBinding.rgWesternModels.removeAllViews()
        val westernModels = ForecastModel.entries.filter { it.isWestern }
        for (model in westernModels) {
            val card = MaterialCardView(activity).apply {
                radius = 12 * density
                strokeWidth = (1 * density).toInt()
                strokeColor = palette.dividerColor
                setCardBackgroundColor(palette.surfaceCard)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(0, 0, 0, (10 * density).toInt())
                layoutParams = lp
            }

            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (14 * density).toInt())
            }

            val rb = RadioButton(activity).apply {
                isChecked = (currentModel == model)
                isClickable = false
                isFocusable = false
            }

            val col = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
                lp.setMargins((12 * density).toInt(), 0, 0, 0)
                layoutParams = lp
            }

            val titleTv = TextView(activity).apply {
                text = model.getDisplayName(activity)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(palette.textPrimary)
            }

            val descTv = TextView(activity).apply {
                text = model.getDescription(activity)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(palette.textSecondary)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(0, (2 * density).toInt(), 0, 0)
                layoutParams = lp
            }

            col.addView(titleTv)
            col.addView(descTv)
            row.addView(rb)
            row.addView(col)
            card.addView(row)

            card.setOnClickListener {
                locationPreferences.saveForecastModel(model)
                weatherDisplayManager.loadWeather(forceRefresh = true)
                Toast.makeText(activity.applicationContext, activity.getString(R.string.toast_model_set, model.getDisplayName(activity)), Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                showModelsSettingsScreen(weatherDisplayManager)
            }

            dialogBinding.rgWesternModels.addView(card)
        }

        // Populate Chinese Models
        dialogBinding.rgChineseModels.removeAllViews()
        val chineseModels = ForecastModel.entries.filter { !it.isWestern }
        for (model in chineseModels) {
            val card = MaterialCardView(activity).apply {
                radius = 12 * density
                strokeWidth = (1 * density).toInt()
                strokeColor = palette.dividerColor
                setCardBackgroundColor(palette.surfaceCard)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(0, 0, 0, (10 * density).toInt())
                layoutParams = lp
            }

            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (14 * density).toInt())
            }

            val rb = RadioButton(activity).apply {
                isChecked = (currentModel == model)
                isClickable = false
                isFocusable = false
            }

            val col = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
                lp.setMargins((12 * density).toInt(), 0, 0, 0)
                layoutParams = lp
            }

            val titleTv = TextView(activity).apply {
                text = model.getDisplayName(activity)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(palette.textPrimary)
            }

            val descTv = TextView(activity).apply {
                text = model.getDescription(activity)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(palette.textSecondary)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(0, (2 * density).toInt(), 0, 0)
                layoutParams = lp
            }

            col.addView(titleTv)
            col.addView(descTv)
            row.addView(rb)
            row.addView(col)
            card.addView(row)

            card.setOnClickListener {
                locationPreferences.saveForecastModel(model)
                weatherDisplayManager.loadWeather(forceRefresh = true)
                Toast.makeText(activity.applicationContext, activity.getString(R.string.toast_model_set, model.getDisplayName(activity)), Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                showModelsSettingsScreen(weatherDisplayManager)
            }

            dialogBinding.rgChineseModels.addView(card)
        }

        val isAutoChina = locationPreferences.isAutoCmaChinaEnabled()
        dialogBinding.switchAutoChina.isChecked = isAutoChina
        dialogBinding.switchAutoChina.setOnCheckedChangeListener { _, checked ->
            locationPreferences.saveAutoCmaChinaEnabled(checked)
            weatherDisplayManager.loadWeather(forceRefresh = true)
        }

        trackDialog(dialog)
        if (!activity.isFinishing && !activity.isDestroyed) {
            dialog.show()
        }
    }

    fun showWeatherAlertsSettingsScreen() {
        if (activity.isFinishing || activity.isDestroyed) return

        val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar)
        val dialogBinding = DialogAlertsSettingsBinding.inflate(activity.layoutInflater)
        dialog.setContentView(dialogBinding.root)

        val palette = BoutiqueThemeEngine.getThemePalette(locationPreferences.getSavedThemeMode())
        val headerBg = palette.surfaceCard
        val headerTitleColor = getAaaTitleColor(headerBg)

        dialogBinding.root.setBackgroundColor(palette.backgroundLight)
        dialogBinding.cardHeader.setCardBackgroundColor(ColorStateList.valueOf(headerBg))
        dialogBinding.btnBack.setColorFilter(headerTitleColor)
        val headerRow = dialogBinding.cardHeader.getChildAt(0) as? LinearLayout
        (headerRow?.getChildAt(1) as? TextView)?.setTextColor(headerTitleColor)
        dialogBinding.cardHeader.strokeColor = palette.dividerColor

        dialogBinding.btnBack.setOnClickListener { dialog.dismiss() }

        dialogBinding.tvHeaderTitle.setTextColor(headerTitleColor)
        dialogBinding.tvAlertsHeaderDesc.setTextColor(palette.textSecondary)

        dialogBinding.cardAlertsSwitch.setCardBackgroundColor(ColorStateList.valueOf(palette.surfaceCard))
        dialogBinding.cardAlertsSwitch.strokeColor = palette.dividerColor
        dialogBinding.tvAlertsSwitchTitle.setTextColor(palette.textPrimary)
        dialogBinding.tvAlertsSwitchDesc.setTextColor(palette.textSecondary)

        dialogBinding.cardSolarLeadTime.setCardBackgroundColor(ColorStateList.valueOf(palette.surfaceCard))
        dialogBinding.cardSolarLeadTime.strokeColor = palette.dividerColor
        dialogBinding.tvSolarLeadTitle.setTextColor(palette.textPrimary)
        dialogBinding.tvSolarLeadDesc.setTextColor(palette.textSecondary)

        dialogBinding.cardLunarLeadTime.setCardBackgroundColor(ColorStateList.valueOf(palette.surfaceCard))
        dialogBinding.cardLunarLeadTime.strokeColor = palette.dividerColor
        dialogBinding.tvLunarLeadTitle.setTextColor(palette.textPrimary)
        dialogBinding.tvLunarLeadDesc.setTextColor(palette.textSecondary)

        dialogBinding.cardTestAlert.setCardBackgroundColor(ColorStateList.valueOf(palette.surfaceCard))
        dialogBinding.cardTestAlert.strokeColor = palette.dividerColor
        dialogBinding.tvTestAlertTitle.setTextColor(palette.textPrimary)
        dialogBinding.tvTestAlertDesc.setTextColor(palette.textSecondary)

        dialogBinding.etSolarLeadMins.setTextColor(palette.textPrimary)
        dialogBinding.etSolarLeadMins.backgroundTintList = ColorStateList.valueOf(palette.surfaceCardSecondary)

        dialogBinding.etLunarLeadMins.setTextColor(palette.textPrimary)
        dialogBinding.etLunarLeadMins.backgroundTintList = ColorStateList.valueOf(palette.surfaceCardSecondary)
        dialogBinding.etLunarLeadMins.setText(locationPreferences.getLunarReminderLeadTimeMinutes().toString())

        dialogBinding.etLunarLeadMins.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val mins = s?.toString()?.toIntOrNull()
                if (mins != null && mins >= 0) {
                    locationPreferences.saveLunarReminderLeadTimeMinutes(mins)
                }
            }
        })

        if (BuildConfig.DEBUG) {
            // Test notification system harness
            dialogBinding.cardTestAlert.alpha = 1.0f
            dialogBinding.cardTestAlert.isEnabled = true
            dialogBinding.btnSendTestAlert.isEnabled = true
            dialogBinding.btnSendTestAlert.text = activity.getString(R.string.btn_send_test_alert)
            dialogBinding.btnSendTestAlert.setOnClickListener { v ->
                val popup = PopupMenu(activity, v)
                val ecccFixtures = com.weatherapp.libre.alerts.GlobalAlertTestHarness.EcccTestFixture.entries
                val globalFixtures = com.weatherapp.libre.alerts.GlobalAlertTestHarness.GlobalTestFixture.entries

                for (i in ecccFixtures.indices) {
                    popup.menu.add(0, i, i, ecccFixtures[i].displayName)
                }
                val dividerItem = popup.menu.add(1, 999, 999, "──────────────")
                dividerItem.isEnabled = false

                for (i in globalFixtures.indices) {
                    val itemId = ecccFixtures.size + i
                    popup.menu.add(1, itemId, itemId, globalFixtures[i].displayName)
                }

                popup.setOnMenuItemClickListener { menuItem ->
                    val itemId = menuItem.itemId
                    val currentLoc = if (activity is MainActivity) activity.weatherDisplayManager.currentLocation else locationPreferences.getSavedLocation()
                    val tz = if (activity is MainActivity) activity.weatherDisplayManager.locationTimezone else null

                    val savedTabs = (0 until locationPreferences.getTabCount()).map { locationPreferences.getTabLocation(it) }

                    if (itemId < ecccFixtures.size) {
                        val selectedFixture = ecccFixtures[itemId]
                        locationPreferences.clearPersistentCache()

                        val eligibleEcccTabs = savedTabs.filter { tab ->
                            tab.countryCode.equals("CA", ignoreCase = true) || tab.country.equals("Canada", ignoreCase = true)
                        }

                        activity.lifecycleScope.launch {
                            val injectedList = com.weatherapp.libre.alerts.GlobalAlertTestHarness.injectEcccTestFixture(
                                context = activity.applicationContext,
                                fixture = selectedFixture,
                                locations = eligibleEcccTabs,
                                timezoneStr = tz
                            )

                            if (injectedList.isNotEmpty()) {
                                if (activity is MainActivity) {
                                    activity.alertBannerManager.updateAlertBanner(currentLoc)
                                }
                                Toast.makeText(activity.applicationContext, activity.getString(R.string.toast_test_alert_injected, selectedFixture.displayName), Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(activity.applicationContext, activity.getString(R.string.toast_test_alert_failed, selectedFixture.displayName), Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else if (itemId >= ecccFixtures.size && itemId < ecccFixtures.size + globalFixtures.size) {
                        val globalIdx = itemId - ecccFixtures.size
                        val selectedFixture = globalFixtures[globalIdx]
                        locationPreferences.clearPersistentCache()

                        val eligibleGlobalTabs = savedTabs.filter { tab ->
                            val countryCode = tab.countryCode?.uppercase(Locale.US) ?: ""
                            com.weatherapp.libre.alerts.routing.AlertProviderRouter.METEOALARM_COUNTRY_CODES.contains(countryCode)
                        }

                        activity.lifecycleScope.launch {
                            val injectedList = com.weatherapp.libre.alerts.GlobalAlertTestHarness.injectGlobalTestFixture(
                                context = activity.applicationContext,
                                fixture = selectedFixture,
                                locations = eligibleGlobalTabs,
                                timezoneStr = tz
                            )

                            if (injectedList.isNotEmpty()) {
                                if (activity is MainActivity) {
                                    activity.alertBannerManager.updateAlertBanner(currentLoc)
                                }
                                Toast.makeText(activity.applicationContext, activity.getString(R.string.toast_test_alert_injected, selectedFixture.displayName), Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(activity.applicationContext, activity.getString(R.string.toast_test_alert_failed, selectedFixture.displayName), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    true
                }
                popup.show()
            }
        } else {
            dialogBinding.cardTestAlert.visibility = View.GONE
        }

        val thumbStates = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        val thumbColors = intArrayOf(palette.brandPrimary, palette.dividerColor)
        dialogBinding.switchAlerts.thumbTintList = ColorStateList(thumbStates, thumbColors)

        val trackStates = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        val trackColors = intArrayOf(
            ColorUtils.setAlphaComponent(palette.brandPrimary, 128),
            ColorUtils.setAlphaComponent(palette.dividerColor, 128)
        )
        dialogBinding.switchAlerts.trackTintList = ColorStateList(trackStates, trackColors)

        var isBindingSwitchState = true
        val isAlertsOn = locationPreferences.getAlertConsentState() == LocationPreferences.ALERT_CONSENT_OPT_IN
        dialogBinding.switchAlerts.isChecked = isAlertsOn
        isBindingSwitchState = false

        dialogBinding.switchAlerts.setOnCheckedChangeListener { _, isChecked ->
            if (isBindingSwitchState) return@setOnCheckedChangeListener

            if (isChecked) {
                locationPreferences.saveAlertConsentState(LocationPreferences.ALERT_CONSENT_OPT_IN)
                AlertNotificationHelper.createNotificationChannels(activity)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (ContextCompat.checkSelfPermission(activity, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        activity.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
                    }
                }
                com.weatherapp.libre.alerts.workers.AlertSyncWorker.schedule(activity.applicationContext)
            } else {
                locationPreferences.saveAlertConsentState(LocationPreferences.ALERT_CONSENT_OPT_OUT)
                com.weatherapp.libre.alerts.workers.AlertSyncWorker.cancel(activity.applicationContext)
            }
        }

        dialogBinding.etSolarLeadMins.setText(locationPreferences.getSolarAlarmLeadMinutes().toString())

        trackDialog(dialog)
        if (!activity.isFinishing && !activity.isDestroyed) {
            dialog.show()
        }
    }

    fun showCacheSettingsScreen() {
        if (activity.isFinishing || activity.isDestroyed) return

        var isSubScreenActive = false
        val dialogBinding = DialogCacheSettingsBinding.inflate(activity.layoutInflater)
        val cacheSettingsView = dialogBinding.root

        val dialog = ComponentDialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar)
        val backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isSubScreenActive) {
                    isSubScreenActive = false
                    dialog.setContentView(cacheSettingsView)
                } else {
                    dialog.dismiss()
                }
            }
        }
        dialog.onBackPressedDispatcher.addCallback(dialog, backCallback)

        dialog.window?.let { window ->
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        }
        dialog.setContentView(cacheSettingsView)

        val palette = BoutiqueThemeEngine.getThemePalette(locationPreferences.getSavedThemeMode())
        val headerBg = palette.surfaceCard
        val headerTitleColor = getAaaTitleColor(headerBg)

        dialogBinding.root.setBackgroundColor(palette.backgroundLight)
        dialogBinding.cardHeader.setCardBackgroundColor(ColorStateList.valueOf(headerBg))
        dialogBinding.btnBack.setColorFilter(headerTitleColor)
        val headerRow = dialogBinding.cardHeader.getChildAt(0) as? LinearLayout
        (headerRow?.getChildAt(1) as? TextView)?.setTextColor(headerTitleColor)
        dialogBinding.cardHeader.strokeColor = palette.dividerColor

        dialogBinding.btnBack.setOnClickListener { dialog.dismiss() }

        dialogBinding.tvHeaderTitle.setTextColor(headerTitleColor)
        dialogBinding.tvCacheHeaderDesc.setTextColor(palette.textSecondary)

        dialogBinding.cardCacheSwitch.setCardBackgroundColor(ColorStateList.valueOf(palette.surfaceCard))
        dialogBinding.cardCacheSwitch.strokeColor = palette.dividerColor
        dialogBinding.tvCacheSwitchTitle.setTextColor(palette.textPrimary)
        dialogBinding.tvCacheSwitchDesc.setTextColor(palette.textSecondary)

        val alertLogCard = dialogBinding.root.findViewById<com.google.android.material.card.MaterialCardView>(com.weatherapp.libre.R.id.cardAlertLog)
        val tvAlertLogTitle = dialogBinding.root.findViewById<TextView>(com.weatherapp.libre.R.id.tvAlertLogTitle)
        val tvAlertLogDesc = dialogBinding.root.findViewById<TextView>(com.weatherapp.libre.R.id.tvAlertLogDesc)
        
        alertLogCard?.setCardBackgroundColor(ColorStateList.valueOf(palette.surfaceCard))
        alertLogCard?.strokeColor = palette.dividerColor
        tvAlertLogTitle?.setTextColor(palette.textPrimary)
        tvAlertLogDesc?.setTextColor(palette.textSecondary)
        alertLogCard?.setOnClickListener {
            isSubScreenActive = true
            val alertLogView = createAlertLogView(dialog) {
                dialog.onBackPressedDispatcher.onBackPressed()
            }
            dialog.setContentView(alertLogView)
        }

        val cachedDataCard = dialogBinding.root.findViewById<com.google.android.material.card.MaterialCardView>(com.weatherapp.libre.R.id.cardCachedData)
        val tvCachedDataTitle = dialogBinding.root.findViewById<TextView>(com.weatherapp.libre.R.id.tvCachedDataTitle)
        val tvCachedDataDesc = dialogBinding.root.findViewById<TextView>(com.weatherapp.libre.R.id.tvCachedDataDesc)

        cachedDataCard?.setCardBackgroundColor(ColorStateList.valueOf(palette.surfaceCard))
        cachedDataCard?.strokeColor = palette.dividerColor
        tvCachedDataTitle?.setTextColor(palette.textPrimary)
        tvCachedDataDesc?.setTextColor(palette.textSecondary)
        cachedDataCard?.setOnClickListener {
            isSubScreenActive = true
            val inspectorView = createCachedDataInspectorView(dialog) {
                dialog.onBackPressedDispatcher.onBackPressed()
            }
            dialog.setContentView(inspectorView)
        }

        dialogBinding.tvTtlHeader.setTextColor(palette.textPrimary)
        dialogBinding.tvTtlReasoningHeader.setTextColor(palette.textPrimary)
        dialogBinding.tvTtlReasoningDesc.setTextColor(palette.textSecondary)
        dialogBinding.tvRateLimitingHeader.setTextColor(palette.tempHigh)
        dialogBinding.tvRateLimitingDesc.setTextColor(palette.textSecondary)

        dialogBinding.cardTtlReasoning.setCardBackgroundColor(ColorStateList.valueOf(palette.surfaceCardSecondary))
        dialogBinding.cardRateLimiting.setCardBackgroundColor(ColorStateList.valueOf(palette.surfaceCardSecondary))

        val isCacheOn = locationPreferences.isCacheTtlEnabled()
        dialogBinding.switchCacheEnabled.isChecked = isCacheOn
        dialogBinding.switchCacheEnabled.setOnCheckedChangeListener { _, isChecked ->
            locationPreferences.saveCacheTtlEnabled(isChecked)
        }

        val currentTtl = locationPreferences.getCacheTtlMinutes()
        dialogBinding.layoutTtlOptions.removeAllViews()

        val density = activity.resources.displayMetrics.density
        val ttlOptions = listOf(
            30 to (activity.getString(R.string.ttl_30_min_title) to activity.getString(R.string.ttl_30_min_desc)),
            60 to (activity.getString(R.string.ttl_60_min_title) to activity.getString(R.string.ttl_60_min_desc)),
            120 to (activity.getString(R.string.ttl_120_min_title) to activity.getString(R.string.ttl_120_min_desc)),
            240 to (activity.getString(R.string.ttl_240_min_title) to activity.getString(R.string.ttl_240_min_desc)),
            360 to (activity.getString(R.string.ttl_360_min_title) to activity.getString(R.string.ttl_360_min_desc))
        )

        for ((mins, info) in ttlOptions) {
            val card = MaterialCardView(activity).apply {
                radius = 12 * density
                strokeWidth = (1 * density).toInt()
                strokeColor = palette.dividerColor
                setCardBackgroundColor(palette.surfaceCard)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(0, 0, 0, (10 * density).toInt())
                layoutParams = lp
            }

            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (14 * density).toInt())
            }

            val rb = RadioButton(activity).apply {
                isChecked = (currentTtl == mins)
                isClickable = false
                isFocusable = false
            }

            val col = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
                lp.setMargins((12 * density).toInt(), 0, 0, 0)
                layoutParams = lp
            }

            val titleTv = TextView(activity).apply {
                text = info.first
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(palette.textPrimary)
            }

            val descTv = TextView(activity).apply {
                text = info.second
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(palette.textSecondary)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(0, (2 * density).toInt(), 0, 0)
                layoutParams = lp
            }

            col.addView(titleTv)
            col.addView(descTv)
            row.addView(rb)
            row.addView(col)
            card.addView(row)

            card.setOnClickListener {
                locationPreferences.saveCacheTtlMinutes(mins)
                Toast.makeText(activity.applicationContext, activity.getString(R.string.toast_cache_ttl_set, info.first), Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                showCacheSettingsScreen()
            }

            dialogBinding.layoutTtlOptions.addView(card)
        }

        trackDialog(dialog)
        if (!activity.isFinishing && !activity.isDestroyed) {
            dialog.show()
        }
    }

    fun showCachedDataInspectorScreen() {
        if (activity.isFinishing || activity.isDestroyed) return

        val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar)
        dialog.window?.let { window ->
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        }
        val view = createCachedDataInspectorView(dialog) { dialog.dismiss() }
        dialog.setContentView(view)
        trackDialog(dialog)
        dialog.show()
    }

    private fun createCachedDataInspectorView(dialog: Dialog, onBack: () -> Unit): View {
        val palette = BoutiqueThemeEngine.getThemePalette(locationPreferences.getSavedThemeMode())
        val density = activity.resources.displayMetrics.density

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.backgroundLight)
        }

        // Header Card
        val cardHeader = MaterialCardView(activity).apply {
            radius = 0f
            strokeWidth = 0
            setCardBackgroundColor(ColorStateList.valueOf(palette.surfaceCard))
        }
        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (14 * density).toInt())
        }
        val btnBack = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_back)
            setBackgroundResource(android.R.color.transparent)
            setColorFilter(palette.textPrimary)
        }
        btnBack.setOnClickListener { onBack() }

        val tvTitle = TextView(activity).apply {
            text = activity.getString(R.string.title_cached_data_inspector)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(palette.textPrimary)
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
            lp.setMargins((16 * density).toInt(), 0, 0, 0)
            layoutParams = lp
        }

        headerRow.addView(btnBack)
        headerRow.addView(tvTitle)
        cardHeader.addView(headerRow)
        root.addView(cardHeader)

        // Content ScrollView
        val scrollView = ScrollView(activity).apply {
            isFillViewport = true
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f)
            layoutParams = lp
        }

        val entriesLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt())
        }

        val entries = CacheInspector.inspectAll(activity)

        if (entries.isEmpty()) {
            val tvEmpty = TextView(activity).apply {
                text = activity.getString(R.string.msg_no_cached_files_found)
                setTextColor(palette.textSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            }
            entriesLayout.addView(tvEmpty)
        } else {
            for (entry in entries) {
                val card = MaterialCardView(activity).apply {
                    radius = 8 * density
                    strokeWidth = (1 * density).toInt()
                    strokeColor = palette.dividerColor
                    setCardBackgroundColor(palette.surfaceCard)
                    val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    lp.setMargins(0, 0, 0, (12 * density).toInt())
                    layoutParams = lp
                }

                val col = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding((14 * density).toInt(), (12 * density).toInt(), (14 * density).toInt(), (12 * density).toInt())
                }

                val rowHeader = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }

                val sourceBadge = TextView(activity).apply {
                    text = entry.source.uppercase(Locale.getDefault())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    setPadding((6 * density).toInt(), (2 * density).toInt(), (6 * density).toInt(), (2 * density).toInt())
                    val bg = android.graphics.drawable.GradientDrawable()
                    bg.setColor(palette.brandPrimary)
                    bg.cornerRadius = 4 * density
                    background = bg
                }

                val keyTv = TextView(activity).apply {
                    text = entry.key
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(palette.textPrimary)
                    val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
                    lp.setMargins((8 * density).toInt(), 0, 0, 0)
                    layoutParams = lp
                }

                val sizeTv = TextView(activity).apply {
                    text = activity.getString(R.string.cache_bytes_format, entry.sizeBytes)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(palette.textSecondary)
                }

                rowHeader.addView(sourceBadge)
                rowHeader.addView(keyTv)
                rowHeader.addView(sizeTv)

                val snippetTv = TextView(activity).apply {
                    text = entry.snippet
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(palette.textSecondary)
                    val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    lp.setMargins(0, (6 * density).toInt(), 0, 0)
                    layoutParams = lp
                }

                col.addView(rowHeader)
                col.addView(snippetTv)
                card.addView(col)

                card.isClickable = true
                card.isFocusable = true
                card.setOnClickListener {
                    showFullContentViewerDialog(entry)
                }

                entriesLayout.addView(card)
            }
        }

        scrollView.addView(entriesLayout)
        root.addView(scrollView)

        // Clear All Cache Button at bottom
        val btnClearAll = com.google.android.material.button.MaterialButton(activity).apply {
            text = activity.getString(R.string.btn_clear_all_cache)
            setBackgroundColor(palette.tempHigh)
            setTextColor(Color.WHITE)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (16 * density).toInt())
            layoutParams = lp
            setOnClickListener {
                CacheInspector.clearWeatherCache(activity, locationPreferences)
                Toast.makeText(activity, activity.getString(R.string.toast_cache_cleared), Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        root.addView(btnClearAll)

        return root
    }

    private fun showFullContentViewerDialog(entry: CacheEntry) {
        if (activity.isFinishing || activity.isDestroyed) return

        val palette = BoutiqueThemeEngine.getThemePalette(locationPreferences.getSavedThemeMode())
        val density = activity.resources.displayMetrics.density

        val detailDialog = Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar)
        detailDialog.window?.let { window ->
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.backgroundLight)
        }

        // Header Card
        val cardHeader = MaterialCardView(activity).apply {
            radius = 0f
            strokeWidth = 0
            setCardBackgroundColor(ColorStateList.valueOf(palette.surfaceCard))
        }

        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (14 * density).toInt())
        }

        val btnBack = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_back)
            setBackgroundResource(android.R.color.transparent)
            setColorFilter(palette.textPrimary)
        }
        btnBack.setOnClickListener { detailDialog.dismiss() }

        val tvTitle = TextView(activity).apply {
            text = entry.key
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(palette.textPrimary)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
            lp.setMargins((12 * density).toInt(), 0, (12 * density).toInt(), 0)
            layoutParams = lp
        }

        val tvSize = TextView(activity).apply {
            text = activity.getString(R.string.cache_bytes_format, entry.sizeBytes)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(palette.textSecondary)
        }

        headerRow.addView(btnBack)
        headerRow.addView(tvTitle)
        headerRow.addView(tvSize)
        cardHeader.addView(headerRow)
        root.addView(cardHeader)

        // WebView for full content display
        val webView = android.webkit.WebView(activity).apply {
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f)
            layoutParams = lp

            settings.apply {
                javaScriptEnabled = false
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
            }
        }

        fun Int.toHexColor(): String = String.format("#%06X", 0xFFFFFF and this)

        val fullPayload = CacheInspector.readFullContent(activity, entry)
        val escapedPayload = fullPayload
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

        val bgColorHex = palette.backgroundLight.toHexColor()
        val textColorHex = palette.textPrimary.toHexColor()
        val accentColorHex = palette.brandPrimary.toHexColor()

        val htmlContent = """
            <!DOCTYPE html>
            <html>
            <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <style>
                body {
                    background-color: $bgColorHex;
                    color: $textColorHex;
                    margin: 0;
                    padding: 16px;
                    font-family: monospace;
                    font-size: 13px;
                    line-height: 1.4;
                    word-wrap: break-word;
                    white-space: pre-wrap;
                }
                pre {
                    margin: 0;
                    padding: 0;
                    font-family: monospace;
                    white-space: pre-wrap;
                    word-wrap: break-word;
                    overflow-wrap: break-word;
                    color: $textColorHex;
                    border-left: 3px solid $accentColorHex;
                    padding-left: 10px;
                }
            </style>
            </head>
            <body>
            <pre>$escapedPayload</pre>
            </body>
            </html>
        """.trimIndent()

        webView.loadDataWithBaseURL(null, htmlContent, "text/html", "UTF-8", null)
        root.addView(webView)

        detailDialog.setContentView(root)
        trackDialog(detailDialog)
        if (!activity.isFinishing && !activity.isDestroyed) {
            detailDialog.show()
        }
    }

    fun showAlertLogScreen() {
        if (activity.isFinishing || activity.isDestroyed) return

        val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar)
        dialog.window?.let { window ->
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        }
        val view = createAlertLogView(dialog) { dialog.dismiss() }
        dialog.setContentView(view)
        trackDialog(dialog)
        if (!activity.isFinishing && !activity.isDestroyed) {
            dialog.show()
        }
    }

    private fun createAlertLogView(dialog: Dialog, onBack: () -> Unit): View {
        val view = activity.layoutInflater.inflate(com.weatherapp.libre.R.layout.dialog_alert_log, null)

        val palette = BoutiqueThemeEngine.getThemePalette(locationPreferences.getSavedThemeMode())
        val headerBg = palette.surfaceCard
        val headerTitleColor = getAaaTitleColor(headerBg)

        view.setBackgroundColor(palette.backgroundLight)
        val cardHeader = view.findViewById<com.google.android.material.card.MaterialCardView>(com.weatherapp.libre.R.id.cardHeader)
        cardHeader.setCardBackgroundColor(ColorStateList.valueOf(headerBg))
        
        val btnBack = view.findViewById<ImageButton>(com.weatherapp.libre.R.id.btnBack)
        btnBack.setColorFilter(headerTitleColor)
        
        val tvHeaderTitle = view.findViewById<TextView>(com.weatherapp.libre.R.id.tvHeaderTitle)
        tvHeaderTitle.setTextColor(headerTitleColor)
        
        cardHeader.strokeColor = palette.dividerColor

        btnBack.setOnClickListener { onBack() }

        val btnSort = view.findViewById<android.widget.Button>(com.weatherapp.libre.R.id.btnSort)
        val btnFilter = view.findViewById<android.widget.Button>(com.weatherapp.libre.R.id.btnFilter)
        val btnClear = view.findViewById<android.widget.Button>(com.weatherapp.libre.R.id.btnClear)
        val layoutEntries = view.findViewById<LinearLayout>(com.weatherapp.libre.R.id.layoutAlertLogEntries)

        // Palette wiring
        btnSort.setTextColor(palette.textPrimary)
        btnFilter.setTextColor(palette.textPrimary)
        btnClear.setTextColor(palette.textPrimary)
        
        btnSort.backgroundTintList = ColorStateList.valueOf(palette.surfaceCardSecondary)
        btnFilter.backgroundTintList = ColorStateList.valueOf(palette.surfaceCardSecondary)
        btnClear.backgroundTintList = ColorStateList.valueOf(palette.surfaceCardSecondary)

        var sortMode = "newest"
        var filterMode = "show_all"
        var entries = com.weatherapp.libre.alerts.AlertLogRepository.getAllEntries(activity)

        fun renderEntries() {
            layoutEntries.removeAllViews()
            
            var displayList = entries.toList()
            if (filterMode == "hide_tests") {
                displayList = displayList.filter { !it.isTest }
            }
            
            displayList = when (sortMode) {
                "severity" -> {
                    val severityRank = mapOf("extreme" to 4, "severe" to 3, "moderate" to 2, "minor" to 1)
                    displayList.sortedByDescending { severityRank[it.severity.lowercase()] ?: 0 }
                }
                "oldest" -> {
                    displayList.sortedBy { it.timestampUtc }
                }
                else -> {
                    displayList.sortedByDescending { it.timestampUtc }
                }
            }

            val density = activity.resources.displayMetrics.density
            val formatter = java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d • h:mm a", java.util.Locale.getDefault())
            val zoneId = java.time.ZoneId.systemDefault()

            for (entry in displayList) {
                val card = com.google.android.material.card.MaterialCardView(activity).apply {
                    radius = 8 * density
                    strokeWidth = 0
                    setCardBackgroundColor(ColorStateList.valueOf(palette.surfaceCard))
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    lp.setMargins(0, 0, 0, (12 * density).toInt())
                    layoutParams = lp
                }

                val rowCol = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (14 * density).toInt())
                }
                
                val row1 = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val locTv = TextView(activity).apply {
                    text = entry.locationName
                    setTextColor(palette.textPrimary)
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
                    layoutParams = lp
                }
                row1.addView(locTv)
                
                if (entry.isTest) {
                    val testBadge = TextView(activity).apply {
                        text = activity.getString(com.weatherapp.libre.R.string.alert_log_test_badge)
                        setTextColor(Color.WHITE)
                        setBackgroundColor(palette.tempHigh)
                        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10f)
                        setPadding((4 * density).toInt(), (2 * density).toInt(), (4 * density).toInt(), (2 * density).toInt())
                        val bg = android.graphics.drawable.GradientDrawable()
                        bg.setColor(palette.tempHigh)
                        bg.cornerRadius = 4 * density
                        background = bg
                    }
                    row1.addView(testBadge)
                }

                val eventTv = TextView(activity).apply {
                    text = entry.alertEvent
                    setTextColor(palette.textPrimary)
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                    val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    lp.setMargins(0, (4 * density).toInt(), 0, 0)
                    layoutParams = lp
                }

                val row3 = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    lp.setMargins(0, (4 * density).toInt(), 0, 0)
                    layoutParams = lp
                }
                val timeStr = java.time.Instant.ofEpochMilli(entry.timestampUtc).atZone(zoneId).format(formatter)
                val timeTv = TextView(activity).apply {
                    text = timeStr
                    setTextColor(palette.textSecondary)
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                    val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
                    layoutParams = lp
                }
                val sevTv = TextView(activity).apply {
                    text = entry.severity
                    setTextColor(palette.textSecondary)
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
                row3.addView(timeTv)
                row3.addView(sevTv)

                rowCol.addView(row1)
                rowCol.addView(eventTv)
                rowCol.addView(row3)
                card.addView(rowCol)
                layoutEntries.addView(card)
            }
        }

        btnSort.setOnClickListener { view ->
            val popup = android.widget.PopupMenu(activity, view)
            popup.menu.add(0, 1, 0, activity.getString(com.weatherapp.libre.R.string.alert_log_sort_newest))
            popup.menu.add(0, 2, 0, activity.getString(com.weatherapp.libre.R.string.alert_log_sort_oldest))
            popup.menu.add(0, 3, 0, activity.getString(com.weatherapp.libre.R.string.alert_log_sort_severity))
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> {
                        sortMode = "newest"
                        btnSort.text = activity.getString(com.weatherapp.libre.R.string.alert_log_sort_newest)
                    }
                    2 -> {
                        sortMode = "oldest"
                        btnSort.text = activity.getString(com.weatherapp.libre.R.string.alert_log_sort_oldest)
                    }
                    3 -> {
                        sortMode = "severity"
                        btnSort.text = activity.getString(com.weatherapp.libre.R.string.alert_log_sort_severity)
                    }
                }
                renderEntries()
                true
            }
            popup.show()
        }

        btnFilter.setOnClickListener { view ->
            val popup = android.widget.PopupMenu(activity, view)
            popup.menu.add(0, 1, 0, activity.getString(com.weatherapp.libre.R.string.alert_log_filter_show_all))
            popup.menu.add(0, 2, 0, activity.getString(com.weatherapp.libre.R.string.alert_log_filter_hide_tests))
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> {
                        filterMode = "show_all"
                        btnFilter.text = activity.getString(com.weatherapp.libre.R.string.alert_log_filter_show_all)
                    }
                    2 -> {
                        filterMode = "hide_tests"
                        btnFilter.text = activity.getString(com.weatherapp.libre.R.string.alert_log_filter_hide_tests)
                    }
                }
                renderEntries()
                true
            }
            popup.show()
        }

        btnClear.setOnClickListener {
            val dialog = android.app.AlertDialog.Builder(activity)
                .setTitle(activity.getString(com.weatherapp.libre.R.string.alert_log_clear))
                .setMessage(activity.getString(com.weatherapp.libre.R.string.alert_log_clear_confirm))
                .setPositiveButton(com.weatherapp.libre.R.string.btn_ok) { _, _ ->
                    activity.lifecycleScope.launch {
                        com.weatherapp.libre.alerts.AlertLogRepository.clearAll(activity)
                        entries = emptyList()
                        Toast.makeText(activity, activity.getString(com.weatherapp.libre.R.string.alert_log_cleared), Toast.LENGTH_SHORT).show()
                        renderEntries()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
            dialog.show()
        }

        renderEntries()
        return view
    }

    fun showLicensesSettingsScreen() {
        showWebViewSettingsPage(activity.getString(R.string.title_licenses_sources), UrlConfig.URL_LICENSES)
    }

    fun showPrivacySettingsScreen() {
        showWebViewSettingsPage(activity.getString(R.string.title_privacy_policy), UrlConfig.URL_PRIVACY)
    }

    fun showAboutSettingsScreen() {
        showWebViewSettingsPage(activity.getString(R.string.about_page_title, BuildConfig.VERSION_NAME), UrlConfig.URL_ABOUT)
    }

    fun showDonateSettingsScreen() {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UrlConfig.URL_KOFI)))
        } catch (e: Exception) {
            Toast.makeText(activity, activity.getString(R.string.toast_could_not_open_browser), Toast.LENGTH_SHORT).show()
        }
    }

    fun showRoadmapSettingsScreen() {
        showWebViewSettingsPage(activity.getString(R.string.title_roadmap), UrlConfig.URL_ROADMAP)
    }

    fun showChangelogSettingsScreen() {
        showWebViewSettingsPage(activity.getString(R.string.title_changelog), UrlConfig.URL_CHANGELOG)
    }

    fun showUserManualSettingsScreen() {
        showWebViewSettingsPage(activity.getString(R.string.title_user_manual), UrlConfig.URL_USER_MANUAL)
    }

    fun showLunarAlmanacSettingsScreen() {
        showWebViewSettingsPage(activity.getString(R.string.title_lunar_almanac), UrlConfig.URL_LUNAR_ALMANAC)
    }

    fun showDefaultLocationSettingsScreen(locationDialogManager: LocationDialogManager) {
        locationDialogManager.showRecentLocationsDialog(isSettingDefaultLocation = true) {
            Toast.makeText(activity.applicationContext, activity.getString(R.string.toast_default_location_updated), Toast.LENGTH_SHORT).show()
        }
    }

    fun showSystemSettingsScreen(weatherDisplayManager: WeatherDisplayManager, locationDialogManager: LocationDialogManager) {
        if (activity.isFinishing || activity.isDestroyed) return

        val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar)
        val dialogBinding = DialogSystemScreenBinding.inflate(activity.layoutInflater)
        dialog.setContentView(dialogBinding.root)

        val palette = BoutiqueThemeEngine.getThemePalette(locationPreferences.getSavedThemeMode())
        val headerBg = palette.surfaceCard
        val headerTitleColor = getAaaTitleColor(headerBg)

        dialogBinding.root.setBackgroundColor(palette.backgroundLight)
        dialogBinding.cardHeader.setCardBackgroundColor(ColorStateList.valueOf(headerBg))
        dialogBinding.btnBack.setColorFilter(headerTitleColor)
        val headerRow = dialogBinding.cardHeader.getChildAt(0) as? LinearLayout
        (headerRow?.getChildAt(1) as? TextView)?.setTextColor(headerTitleColor)
        dialogBinding.cardHeader.strokeColor = palette.dividerColor

        dialogBinding.btnBack.setOnClickListener { dialog.dismiss() }

        dialogBinding.tvHeaderTitle.setTextColor(headerTitleColor)
        dialogBinding.tvHeaderDesc.setTextColor(palette.textSecondary)

        val cards = listOf(
            dialogBinding.cardBehaviour to (dialogBinding.tvBehaviourTitle to dialogBinding.tvBehaviourDesc),
            dialogBinding.cardDataCache to (dialogBinding.tvDataCacheTitle to dialogBinding.tvDataCacheDesc),
            dialogBinding.cardLocationTabs to (dialogBinding.tvLocationTabsTitle to dialogBinding.tvLocationTabsDesc),
            dialogBinding.cardOrganizeMenu to (dialogBinding.tvOrganizeMenuTitle to dialogBinding.tvOrganizeMenuDesc),
            dialogBinding.cardNotifications to (dialogBinding.tvNotificationsTitle to dialogBinding.tvNotificationsDesc),
            dialogBinding.cardWeatherModels to (dialogBinding.tvWeatherModelsTitle to dialogBinding.tvWeatherModelsDesc),
            dialogBinding.cardWidgets to (dialogBinding.tvWidgetsTitle to dialogBinding.tvWidgetsDesc)
        )

        for ((card, textPair) in cards) {
            card.setCardBackgroundColor(ColorStateList.valueOf(palette.surfaceCard))
            card.strokeColor = palette.dividerColor
            textPair.first.setTextColor(palette.textPrimary)
            textPair.second.setTextColor(palette.textSecondary)
        }

        dialogBinding.cardBehaviour.setOnClickListener { showBehaviourSettingsScreen() }
        dialogBinding.cardOrganizeMenu.setOnClickListener { showMenuOrganizationScreen() }
        dialogBinding.cardDataCache.setOnClickListener { showCacheSettingsScreen() }
        dialogBinding.cardLocationTabs.setOnClickListener { showLocationTabsSettingsScreen() }
        dialogBinding.cardNotifications.setOnClickListener { showWeatherAlertsSettingsScreen() }
        dialogBinding.cardWeatherModels.setOnClickListener { showModelsSettingsScreen(weatherDisplayManager) }
        dialogBinding.cardWidgets.setOnClickListener { showWidgetsSettingsScreen() }

        trackDialog(dialog)
        dialog.show()
    }

    fun showWidgetsSettingsScreen() {
        if (activity.isFinishing || activity.isDestroyed) return

        val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar)
        val dialogBinding = com.weatherapp.libre.databinding.DialogWidgetsSettingsBinding.inflate(activity.layoutInflater)
        dialog.setContentView(dialogBinding.root)

        val palette = BoutiqueThemeEngine.getThemePalette(locationPreferences.getSavedThemeMode())
        val headerBg = palette.surfaceCard
        val headerTitleColor = getAaaTitleColor(headerBg)

        dialogBinding.root.setBackgroundColor(palette.backgroundLight)
        dialogBinding.cardHeader.setCardBackgroundColor(ColorStateList.valueOf(headerBg))
        dialogBinding.btnBack.setColorFilter(headerTitleColor)
        val headerRow = dialogBinding.cardHeader.getChildAt(0) as? LinearLayout
        (headerRow?.getChildAt(1) as? TextView)?.setTextColor(headerTitleColor)
        dialogBinding.cardHeader.strokeColor = palette.dividerColor

        dialogBinding.tvWidgetThemeTitle.setTextColor(palette.textPrimary)
        dialogBinding.tvWidgetThemeSystemTitle.setTextColor(palette.textPrimary)
        dialogBinding.tvWidgetThemeFollowTitle.setTextColor(palette.textPrimary)
        dialogBinding.tvWidgetThemeCustomTitle.setTextColor(palette.textPrimary)

        dialogBinding.cardWidgetThemeSystem.setCardBackgroundColor(ColorStateList.valueOf(palette.surfaceCard))
        dialogBinding.cardWidgetThemeSystem.strokeColor = palette.dividerColor
        dialogBinding.cardWidgetThemeFollow.setCardBackgroundColor(ColorStateList.valueOf(palette.surfaceCard))
        dialogBinding.cardWidgetThemeFollow.strokeColor = palette.dividerColor
        dialogBinding.cardWidgetThemeCustom.setCardBackgroundColor(ColorStateList.valueOf(palette.surfaceCard))
        dialogBinding.cardWidgetThemeCustom.strokeColor = palette.dividerColor
        dialogBinding.cardWidgetOpacity.strokeColor = palette.dividerColor

        dialogBinding.radioWidgetThemeSystem.buttonTintList = ColorStateList.valueOf(palette.primary)
        dialogBinding.radioWidgetThemeFollow.buttonTintList = ColorStateList.valueOf(palette.primary)
        dialogBinding.radioWidgetThemeCustom.buttonTintList = ColorStateList.valueOf(palette.primary)

        dialogBinding.tvWidgetThemeSystemDesc.setTextColor(palette.textSecondary)
        dialogBinding.tvWidgetThemeFollowDesc.setTextColor(palette.textSecondary)
        dialogBinding.tvWidgetThemeCustomDesc.setTextColor(palette.textSecondary)
        dialogBinding.ivWidgetThemeChevron.setColorFilter(palette.textSecondary)

        dialogBinding.btnBack.setOnClickListener { dialog.dismiss() }

        val widgetPrefs = WidgetPreferences(activity)

        val themeModeNames = mapOf(
            LocationPreferences.THEME_LIGHT to R.string.theme_light_title,
            LocationPreferences.THEME_DARK to R.string.theme_dark_title,
            LocationPreferences.THEME_CREMA to R.string.theme_crema_title,
            LocationPreferences.THEME_CHARCOAL to R.string.theme_charcoal_title,
            LocationPreferences.THEME_COBALT to R.string.theme_cobalt_title,
            LocationPreferences.THEME_JADE to R.string.theme_jade_title,
            LocationPreferences.THEME_AURORA to R.string.theme_aurora_title,
            LocationPreferences.THEME_TERRACOTTA to R.string.theme_terracotta_title,
            LocationPreferences.THEME_FROST to R.string.theme_frost_title,
            LocationPreferences.THEME_ORANGE_GROVE to R.string.theme_orange_grove_title,
            LocationPreferences.THEME_FRUTIGER_AERO to R.string.theme_frutiger_aero_title,
            LocationPreferences.THEME_HARDY_HERON to R.string.theme_hardy_heron_title
        )

        fun resolveWidgetPalette(): ColorPalette {
            return when (widgetPrefs.getWidgetThemeSource()) {
                WidgetPreferences.WIDGET_THEME_SOURCE_FOLLOW -> {
                    val savedMode = locationPreferences.getSavedThemeMode()
                    val effectiveMode = locationPreferences.resolveEffectiveTheme(savedMode, activity)
                    BoutiqueThemeEngine.getThemePalette(effectiveMode)
                }
                WidgetPreferences.WIDGET_THEME_SOURCE_CUSTOM -> {
                    val customMode = widgetPrefs.getWidgetCustomThemeMode()
                    val modeToUse = if (customMode == WidgetPreferences.WIDGET_THEME_UNSET) {
                        locationPreferences.getSavedThemeMode()
                    } else {
                        customMode
                    }
                    val effectiveMode = locationPreferences.resolveEffectiveTheme(modeToUse, activity)
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
        }

        fun refreshOpacityPreview(opacityPercent: Int = dialogBinding.sliderWidgetOpacity.value.toInt()) {
            val alpha = ((opacityPercent.coerceIn(1, 100) * 255) / 100).coerceIn(0, 255)
            val palette = resolveWidgetPalette()
            val previewColor = (palette.surfaceCard and 0x00FFFFFF) or (alpha shl 24)
            dialogBinding.layoutOpacityPreview.backgroundTintMode = android.graphics.PorterDuff.Mode.SRC_IN
            dialogBinding.layoutOpacityPreview.backgroundTintList = ColorStateList.valueOf(previewColor)

            dialogBinding.tvWidgetOpacityTitle.setTextColor(palette.textPrimary)
            dialogBinding.tvWidgetOpacityDesc.setTextColor(palette.textSecondary)
            dialogBinding.tvWidgetOpacityValue.setTextColor(palette.textPrimary)

            dialogBinding.sliderWidgetOpacity.thumbTintList = ColorStateList.valueOf(palette.textPrimary)
            dialogBinding.sliderWidgetOpacity.trackActiveTintList = ColorStateList.valueOf(palette.textPrimary)
            dialogBinding.sliderWidgetOpacity.trackInactiveTintList = ColorStateList.valueOf((palette.textPrimary and 0x00FFFFFF) or (0x66 shl 24))
        }

        fun refreshThemeCells() {
            val source = widgetPrefs.getWidgetThemeSource()
            dialogBinding.radioWidgetThemeSystem.isChecked = (source == WidgetPreferences.WIDGET_THEME_SOURCE_SYSTEM)
            dialogBinding.radioWidgetThemeFollow.isChecked = (source == WidgetPreferences.WIDGET_THEME_SOURCE_FOLLOW)
            dialogBinding.radioWidgetThemeCustom.isChecked = (source == WidgetPreferences.WIDGET_THEME_SOURCE_CUSTOM)

            val customMode = widgetPrefs.getWidgetCustomThemeMode()
            if (customMode == WidgetPreferences.WIDGET_THEME_UNSET) {
                dialogBinding.tvWidgetThemeCustomDesc.text = activity.getString(R.string.widget_theme_not_set)
            } else {
                val themeResId = themeModeNames[customMode] ?: R.string.theme_dark_title
                val themeName = activity.getString(themeResId)
                dialogBinding.tvWidgetThemeCustomDesc.text = activity.getString(R.string.widget_theme_currently, themeName)
            }

            refreshOpacityPreview()
        }

        refreshThemeCells()

        dialogBinding.cardWidgetThemeSystem.setOnClickListener {
            widgetPrefs.saveWidgetThemeSource(WidgetPreferences.WIDGET_THEME_SOURCE_SYSTEM)
            refreshThemeCells()
            WidgetSyncWorker.notifyWidgetsThemeChanged(activity)
            WidgetSyncWorker.enqueueImmediate(activity)
        }

        dialogBinding.cardWidgetThemeFollow.setOnClickListener {
            widgetPrefs.saveWidgetThemeSource(WidgetPreferences.WIDGET_THEME_SOURCE_FOLLOW)
            refreshThemeCells()
            WidgetSyncWorker.notifyWidgetsThemeChanged(activity)
            WidgetSyncWorker.enqueueImmediate(activity)
        }

        dialogBinding.cardWidgetThemeCustom.setOnClickListener {
            widgetPrefs.saveWidgetThemeSource(WidgetPreferences.WIDGET_THEME_SOURCE_CUSTOM)
            refreshThemeCells()
            WidgetSyncWorker.notifyWidgetsThemeChanged(activity)
            WidgetSyncWorker.enqueueImmediate(activity)
            showWidgetCustomThemePickerScreen(widgetPrefs) {
                refreshThemeCells()
                WidgetSyncWorker.notifyWidgetsThemeChanged(activity)
                WidgetSyncWorker.enqueueImmediate(activity)
            }
        }

        val darkModesList = listOf(
            LocationPreferences.THEME_DARK,
            LocationPreferences.THEME_AURORA,
            LocationPreferences.THEME_CHARCOAL,
            LocationPreferences.THEME_COBALT,
            LocationPreferences.THEME_HARDY_HERON,
            LocationPreferences.THEME_JADE
        )
        val savedThemeMode = locationPreferences.getSavedThemeMode()
        val isDarkTheme = if (savedThemeMode == LocationPreferences.THEME_SYSTEM) {
            (activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        } else {
            savedThemeMode in darkModesList
        }

        val opacityCardBgColor = if (isDarkTheme) Color.WHITE else Color.parseColor("#1E293B")
        dialogBinding.cardWidgetOpacity.setCardBackgroundColor(ColorStateList.valueOf(opacityCardBgColor))

        val currentOpacity = widgetPrefs.getWidgetOpacity()
        dialogBinding.sliderWidgetOpacity.value = currentOpacity.toFloat()
        dialogBinding.tvWidgetOpacityValue.text = activity.getString(R.string.widget_opacity_format, currentOpacity)
        refreshOpacityPreview(currentOpacity)

        dialogBinding.sliderWidgetOpacity.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val opacity = value.toInt().coerceIn(1, 100)
                dialogBinding.tvWidgetOpacityValue.text = activity.getString(R.string.widget_opacity_format, opacity)
                refreshOpacityPreview(opacity)
            }
        }

        dialogBinding.sliderWidgetOpacity.addOnSliderTouchListener(object : com.google.android.material.slider.Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {}

            override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                val opacity = slider.value.toInt().coerceIn(1, 100)
                refreshOpacityPreview(opacity)
                widgetPrefs.saveWidgetOpacity(opacity)
                WidgetSyncWorker.notifyWidgetsThemeChanged(activity)
                WidgetSyncWorker.enqueueImmediate(activity)
            }
        })

        trackDialog(dialog)
        dialog.show()
    }

    fun showWidgetCustomThemePickerScreen(widgetPrefs: WidgetPreferences, onThemeSelected: () -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return

        val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar)
        val dialogBinding = com.weatherapp.libre.databinding.DialogWidgetThemePickerBinding.inflate(activity.layoutInflater)
        dialog.setContentView(dialogBinding.root)

        val palette = BoutiqueThemeEngine.getThemePalette(locationPreferences.getSavedThemeMode())
        val headerBg = palette.surfaceCard
        val headerTitleColor = getAaaTitleColor(headerBg)

        dialogBinding.root.setBackgroundColor(palette.backgroundLight)
        dialogBinding.cardHeader.setCardBackgroundColor(ColorStateList.valueOf(headerBg))
        dialogBinding.btnBack.setColorFilter(headerTitleColor)
        dialogBinding.tvHeaderTitle.setTextColor(headerTitleColor)
        dialogBinding.cardHeader.strokeColor = palette.dividerColor

        dialogBinding.tvLightThemesHeader.setTextColor(palette.textPrimary)
        dialogBinding.tvDarkThemesHeader.setTextColor(palette.textPrimary)

        dialogBinding.btnBack.setOnClickListener { dialog.dismiss() }

        val currentCustomMode = widgetPrefs.getWidgetCustomThemeMode()

        val lightModes = listOf(
            LocationPreferences.THEME_LIGHT to (R.string.theme_light_title to R.string.theme_light_desc),
            LocationPreferences.THEME_CREMA to (R.string.theme_crema_title to R.string.theme_crema_desc),
            LocationPreferences.THEME_FROST to (R.string.theme_frost_title to R.string.theme_frost_desc),
            LocationPreferences.THEME_FRUTIGER_AERO to (R.string.theme_frutiger_aero_title to R.string.theme_frutiger_aero_desc),
            LocationPreferences.THEME_ORANGE_GROVE to (R.string.theme_orange_grove_title to R.string.theme_orange_grove_desc),
            LocationPreferences.THEME_TERRACOTTA to (R.string.theme_terracotta_title to R.string.theme_terracotta_desc)
        ).sortedBy { activity.getString(it.second.first) }

        val darkModes = listOf(
            LocationPreferences.THEME_DARK to (R.string.theme_dark_title to R.string.theme_dark_desc),
            LocationPreferences.THEME_AURORA to (R.string.theme_aurora_title to R.string.theme_aurora_desc),
            LocationPreferences.THEME_CHARCOAL to (R.string.theme_charcoal_title to R.string.theme_charcoal_desc),
            LocationPreferences.THEME_COBALT to (R.string.theme_cobalt_title to R.string.theme_cobalt_desc),
            LocationPreferences.THEME_HARDY_HERON to (R.string.theme_hardy_heron_title to R.string.theme_hardy_heron_desc),
            LocationPreferences.THEME_JADE to (R.string.theme_jade_title to R.string.theme_jade_desc)
        ).sortedBy { activity.getString(it.second.first) }

        val radioButtons = mutableMapOf<Int, RadioButton>()

        fun populateThemeGroup(
            container: LinearLayout,
            modes: List<Pair<Int, Pair<Int, Int>>>
        ) {
            container.removeAllViews()
            val density = activity.resources.displayMetrics.density

            for ((mode, info) in modes) {
                val card = MaterialCardView(activity).apply {
                    radius = 12 * density
                    strokeWidth = (1 * density).toInt()
                    strokeColor = palette.dividerColor
                    setCardBackgroundColor(palette.surfaceCard)
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    lp.setMargins(0, 0, 0, (12 * density).toInt())
                    layoutParams = lp
                }

                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (14 * density).toInt())
                }

                val radioButton = RadioButton(activity).apply {
                    isChecked = (currentCustomMode == mode)
                    isClickable = false
                    isFocusable = false
                }
                radioButtons[mode] = radioButton

                val textLayout = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
                    lp.setMargins((12 * density).toInt(), 0, 0, 0)
                    layoutParams = lp
                }

                val titleTv = TextView(activity).apply {
                    text = activity.getString(info.first)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(palette.textPrimary)
                }

                val descTv = TextView(activity).apply {
                    text = activity.getString(info.second)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTextColor(palette.textSecondary)
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    lp.setMargins(0, (2 * density).toInt(), 0, 0)
                    layoutParams = lp
                }

                textLayout.addView(titleTv)
                textLayout.addView(descTv)
                row.addView(radioButton)
                row.addView(textLayout)
                card.addView(row)

                card.setOnClickListener {
                    radioButtons.values.forEach { rb -> rb.isChecked = false }
                    radioButton.isChecked = true
                    widgetPrefs.saveWidgetCustomThemeMode(mode)
                    onThemeSelected()
                    dialog.dismiss()
                }

                container.addView(card)
            }
        }

        populateThemeGroup(dialogBinding.layoutLightThemes, lightModes)
        populateThemeGroup(dialogBinding.layoutDarkThemes, darkModes)

        trackDialog(dialog)
        dialog.show()
    }

    fun showBehaviourSettingsScreen() {
        if (activity.isFinishing || activity.isDestroyed) return

        val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar)
        val dialogBinding = DialogBehaviourScreenBinding.inflate(activity.layoutInflater)
        dialog.setContentView(dialogBinding.root)

        val palette = BoutiqueThemeEngine.getThemePalette(locationPreferences.getSavedThemeMode())
        val headerBg = palette.surfaceCard
        val headerTitleColor = getAaaTitleColor(headerBg)

        dialogBinding.root.setBackgroundColor(palette.backgroundLight)
        dialogBinding.cardHeader.setCardBackgroundColor(ColorStateList.valueOf(headerBg))
        dialogBinding.btnBack.setColorFilter(headerTitleColor)
        val headerRow = dialogBinding.cardHeader.getChildAt(0) as? LinearLayout
        (headerRow?.getChildAt(1) as? TextView)?.setTextColor(headerTitleColor)
        dialogBinding.cardHeader.strokeColor = palette.dividerColor

        dialogBinding.btnBack.setOnClickListener { dialog.dismiss() }

        dialogBinding.tvHeaderTitle.setTextColor(headerTitleColor)
        dialogBinding.tvHeaderDesc.setTextColor(palette.textSecondary)

        // Volume Key Date Cycling Card
        dialogBinding.cardVolumeScrollSettings.setCardBackgroundColor(ColorStateList.valueOf(palette.surfaceCard))
        dialogBinding.cardVolumeScrollSettings.strokeColor = palette.dividerColor
        dialogBinding.tvVolumeScrollTitle.setTextColor(palette.textPrimary)
        dialogBinding.tvVolumeScrollDesc.setTextColor(palette.textSecondary)

        val isVolumeScrollOn = locationPreferences.isVolumeScrollEnabled()
        dialogBinding.switchVolumeScroll.isChecked = isVolumeScrollOn
        dialogBinding.layoutVolumeSpeedContainer.visibility = if (isVolumeScrollOn) View.VISIBLE else View.GONE

        dialogBinding.switchVolumeScroll.setOnCheckedChangeListener { _, isChecked ->
            locationPreferences.saveVolumeScrollEnabled(isChecked)
            dialogBinding.layoutVolumeSpeedContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        val currentSpeedIndex = locationPreferences.getVolumeScrollSpeedIndex().coerceIn(1, 7)
        dialogBinding.seekBarVolumeSpeed.max = 6
        dialogBinding.seekBarVolumeSpeed.progress = currentSpeedIndex - 1
        dialogBinding.tvVolumeSpeedLabel.text = activity.getString(R.string.label_cycling_speed, locationPreferences.getVolumeScrollSpeedLabel(activity, currentSpeedIndex))
        dialogBinding.tvVolumeSpeedLabel.setTextColor(palette.textPrimary)

        dialogBinding.seekBarVolumeSpeed.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val speedIndex = (progress + 1).coerceIn(1, 7)
                dialogBinding.tvVolumeSpeedLabel.text = activity.getString(R.string.label_cycling_speed, locationPreferences.getVolumeScrollSpeedLabel(activity, speedIndex))
                if (fromUser) {
                    locationPreferences.saveVolumeScrollSpeedIndex(speedIndex)
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        // Units Card
        dialogBinding.cardUnitsSelector.setCardBackgroundColor(ColorStateList.valueOf(palette.surfaceCard))
        dialogBinding.cardUnitsSelector.strokeColor = palette.dividerColor
        dialogBinding.tvUnitsTitle.setTextColor(palette.textPrimary)

        val isUsUnits = (locationPreferences.getUnitSystem() == UnitSystem.US_CUSTOMARY)
        dialogBinding.btnUnitsChoice.text = if (isUsUnits) activity.getString(R.string.units_us_short) else activity.getString(R.string.units_metric_short)
        dialogBinding.tvUnitsDesc.text = if (isUsUnits) activity.getString(R.string.units_us_long) else activity.getString(R.string.units_metric_long)

        dialogBinding.btnUnitsChoice.setOnClickListener {
            val newUnits = if (locationPreferences.getUnitSystem() == UnitSystem.METRIC) UnitSystem.US_CUSTOMARY else UnitSystem.METRIC
            locationPreferences.saveUnitSystem(newUnits)
            dialogBinding.btnUnitsChoice.text = if (newUnits == UnitSystem.US_CUSTOMARY) activity.getString(R.string.units_us_short) else activity.getString(R.string.units_metric_short)
            dialogBinding.tvUnitsDesc.text = if (newUnits == UnitSystem.US_CUSTOMARY) activity.getString(R.string.units_us_long) else activity.getString(R.string.units_metric_long)

            // Clear weather cache and reload weather for active tab immediately
            if (activity is MainActivity) {
                activity.repository.clearMemoryAndDiskCache(locationPreferences)
                activity.weatherDisplayManager.loadWeather(forceRefresh = true)
            }

            Toast.makeText(activity.applicationContext, activity.getString(R.string.toast_units_updated), Toast.LENGTH_SHORT).show()
        }

        trackDialog(dialog)
        dialog.show()
    }

    private fun checkActiveTabValidity() {
        if (activity is MainActivity) {
            val activeTab = activity.astronomyManager.activeInlineSubTab
            val showSevenDay = locationPreferences.getSevenDayAsMenuItem()
            val enabledFlags = locationPreferences.getMenuEnabledFlags()
            val isTabEnabled = enabledFlags[activeTab] ?: true
            val isSevenDayInvalid = (activeTab == InlineSubTab.SEVEN_DAY && !showSevenDay)

            if (!isTabEnabled || isSevenDayInvalid) {
                val defaultTab = locationPreferences.getDefaultLaunchSubTab()
                activity.astronomyManager.switchInlineSubTab(
                    defaultTab,
                    activity.weatherDisplayManager.currentLocation,
                    activity.weatherDisplayManager.locationTimezone
                )
            }
        }
    }

    fun showMenuOrganizationScreen() {
        if (activity.isFinishing || activity.isDestroyed) return

        val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar)
        val dialogBinding = DialogMenuOrganizationScreenBinding.inflate(activity.layoutInflater)
        dialog.setContentView(dialogBinding.root)

        val palette = BoutiqueThemeEngine.getThemePalette(locationPreferences.getSavedThemeMode())
        val headerBg = palette.surfaceCard
        val headerTitleColor = getAaaTitleColor(headerBg)

        dialogBinding.root.setBackgroundColor(palette.backgroundLight)
        dialogBinding.cardHeader.setCardBackgroundColor(ColorStateList.valueOf(headerBg))
        dialogBinding.btnBack.setColorFilter(headerTitleColor)
        val headerRow = dialogBinding.cardHeader.getChildAt(0) as? LinearLayout
        (headerRow?.getChildAt(1) as? TextView)?.setTextColor(headerTitleColor)
        dialogBinding.cardHeader.strokeColor = palette.dividerColor

        dialogBinding.btnBack.setOnClickListener { dialog.dismiss() }
        dialogBinding.tvHeaderTitle.setTextColor(headerTitleColor)
        dialogBinding.tvHeaderDesc.setTextColor(palette.textSecondary)

        // Palette theming for cards
        dialogBinding.cardSevenDayPlacement.setCardBackgroundColor(ColorStateList.valueOf(palette.surfaceCard))
        dialogBinding.cardSevenDayPlacement.strokeColor = palette.dividerColor
        dialogBinding.tvSevenDayPlacementTitle.setTextColor(palette.textPrimary)
        dialogBinding.tvSevenDayPlacementDesc.setTextColor(palette.textSecondary)

        dialogBinding.cardSeparatorStepper.setCardBackgroundColor(ColorStateList.valueOf(palette.surfaceCard))
        dialogBinding.cardSeparatorStepper.strokeColor = palette.dividerColor
        dialogBinding.tvNumSeparatorsTitle.setTextColor(palette.textPrimary)
        dialogBinding.tvSeparatorCount.setTextColor(palette.textPrimary)
        dialogBinding.btnMinusSeparators.setColorFilter(palette.brandPrimary)
        dialogBinding.btnPlusSeparators.setColorFilter(palette.brandPrimary)

        // Saved state
        val orgItems = locationPreferences.getMenuOrder().toMutableList()
        val showSevenDay = locationPreferences.getSevenDayAsMenuItem()

        val enabledFlags = locationPreferences.getMenuEnabledFlags().toMutableMap()
        var defaultLaunchSubTab = locationPreferences.getDefaultLaunchSubTab()

        // Adapter - create first so it can be updated
        lateinit var adapter: MenuOrganizationAdapter

        // 7-Day Placement Toggle Card
        dialogBinding.switchSevenDayPlacement.isChecked = showSevenDay
        dialogBinding.tvSevenDayPlacementDesc.text = activity.getString(
            if (showSevenDay) R.string.desc_seven_day_placement_menu else R.string.desc_seven_day_placement_inline
        )

        dialogBinding.switchSevenDayPlacement.setOnCheckedChangeListener { _, isChecked: Boolean ->
            locationPreferences.saveSevenDayAsMenuItem(isChecked)
            dialogBinding.tvSevenDayPlacementDesc.text = activity.getString(
                if (isChecked) R.string.desc_seven_day_placement_menu else R.string.desc_seven_day_placement_inline
            )
            val flags = locationPreferences.getMenuEnabledFlags().toMutableMap()
            flags[InlineSubTab.SEVEN_DAY] = isChecked
            locationPreferences.saveMenuEnabledFlags(flags)

            adapter.updateSevenDayPlacement(isChecked)
            checkActiveTabValidity()
            if (activity is MainActivity) {
                activity.astronomyManager.updateSevenDayForecastVisibility()
            }
        }

        // Stepper
        val initialSepCount = orgItems.count { it == "SEPARATOR" }
        dialogBinding.tvSeparatorCount.text = initialSepCount.toString()

        adapter = MenuOrganizationAdapter(
            context = activity,
            items = orgItems,
            enabledFlags = enabledFlags,
            defaultSubTab = defaultLaunchSubTab,
            palette = palette,
            showSevenDayInMenu = showSevenDay,
            onItemReordered = {
                locationPreferences.saveMenuEnabledFlags(enabledFlags)
                locationPreferences.saveMenuOrder(orgItems)
            },
            onDefaultChanged = { newDefault ->
                defaultLaunchSubTab = newDefault
                locationPreferences.saveDefaultLaunchSubTab(newDefault)
                locationPreferences.saveMenuEnabledFlags(enabledFlags)
            },
            onToggleDisabledActiveTab = {
                checkActiveTabValidity()
            }
        )

        dialogBinding.btnMinusSeparators.setOnClickListener {
            val currentOrder = locationPreferences.getMenuOrder().toMutableList()
            val count = currentOrder.count { it == "SEPARATOR" }
            if (count > 0) {
                currentOrder.removeAt(currentOrder.lastIndexOf("SEPARATOR"))
                locationPreferences.saveMenuOrder(currentOrder)
                adapter.updateItems(locationPreferences.getMenuOrder())
                dialogBinding.tvSeparatorCount.text = locationPreferences.getMenuOrder().count { it == "SEPARATOR" }.toString()
            }
        }

        dialogBinding.btnPlusSeparators.setOnClickListener {
            val currentOrder = locationPreferences.getMenuOrder().toMutableList()
            val count = currentOrder.count { it == "SEPARATOR" }
            if (count < 6) {
                currentOrder.add("SEPARATOR")
                locationPreferences.saveMenuOrder(currentOrder)
                adapter.updateItems(locationPreferences.getMenuOrder())
                dialogBinding.tvSeparatorCount.text = locationPreferences.getMenuOrder().count { it == "SEPARATOR" }.toString()
            }
        }

        dialogBinding.rvMenuOrgItems.layoutManager = LinearLayoutManager(activity)
        dialogBinding.rvMenuOrgItems.adapter = adapter
        dialogBinding.rvMenuOrgItems.isNestedScrollingEnabled = false
        dialogBinding.rvMenuOrgItems.setHasFixedSize(false)

        // Reset to Default button
        dialogBinding.btnResetDefaults.setOnClickListener {
            locationPreferences.saveMenuOrder(LocationPreferences.DEFAULT_MENU_ORDER)
            locationPreferences.saveMenuEnabledFlags(InlineSubTab.entries.associateWith { true })
            locationPreferences.saveDefaultLaunchSubTab(InlineSubTab.CURRENT)
            locationPreferences.saveSevenDayAsMenuItem(false)

            checkActiveTabValidity()
            if (activity is MainActivity) {
                activity.astronomyManager.updateSevenDayForecastVisibility()
            }

            Toast.makeText(activity.applicationContext, activity.getString(R.string.toast_reset_default_settings), Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            showMenuOrganizationScreen()
        }

        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPos = viewHolder.adapterPosition
                val toPos = target.adapterPosition
                if (fromPos != RecyclerView.NO_POSITION && toPos != RecyclerView.NO_POSITION) {
                    adapter.moveItem(fromPos, toPos)
                }
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
        })

        touchHelper.attachToRecyclerView(dialogBinding.rvMenuOrgItems)

        trackDialog(dialog)
        dialog.show()
    }

    fun showInfoSettingsScreen() {
        if (activity.isFinishing || activity.isDestroyed) return

        val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar)
        val dialogBinding = DialogInfoScreenBinding.inflate(activity.layoutInflater)
        dialog.setContentView(dialogBinding.root)

        val palette = BoutiqueThemeEngine.getThemePalette(locationPreferences.getSavedThemeMode())
        val headerBg = palette.surfaceCard
        val headerTitleColor = getAaaTitleColor(headerBg)

        dialogBinding.root.setBackgroundColor(palette.backgroundLight)
        dialogBinding.cardHeader.setCardBackgroundColor(ColorStateList.valueOf(headerBg))
        dialogBinding.btnBack.setColorFilter(headerTitleColor)
        val headerRow = dialogBinding.cardHeader.getChildAt(0) as? LinearLayout
        (headerRow?.getChildAt(1) as? TextView)?.setTextColor(headerTitleColor)
        dialogBinding.cardHeader.strokeColor = palette.dividerColor

        dialogBinding.btnBack.setOnClickListener { dialog.dismiss() }

        dialogBinding.tvHeaderTitle.setTextColor(headerTitleColor)
        dialogBinding.tvHeaderDesc.setTextColor(palette.textSecondary)

        val rawCards = listOf(
            Triple(dialogBinding.cardAbout, dialogBinding.tvAboutTitle, dialogBinding.tvAboutDesc),
            Triple(dialogBinding.cardLicenses, dialogBinding.tvLicensesTitle, dialogBinding.tvLicensesDesc),
            Triple(dialogBinding.cardLunarAlmanac, dialogBinding.tvLunarAlmanacTitle, dialogBinding.tvLunarAlmanacDesc),
            Triple(dialogBinding.cardPrivacy, dialogBinding.tvPrivacyTitle, dialogBinding.tvPrivacyDesc),
            Triple(dialogBinding.cardRoadmap, dialogBinding.tvRoadmapTitle, dialogBinding.tvRoadmapDesc),
            Triple(dialogBinding.cardUserManual, dialogBinding.tvUserManualTitle, dialogBinding.tvUserManualDesc)
        )

        val container = dialogBinding.cardAbout.parent as? LinearLayout
        val sortedCards = rawCards.sortedBy { it.second.text.toString() }
        if (container != null) {
            for (item in sortedCards) {
                container.removeView(item.first)
                container.addView(item.first)
            }
        }

        for ((card, titleTv, descTv) in sortedCards) {
            card.setCardBackgroundColor(ColorStateList.valueOf(palette.surfaceCard))
            card.strokeColor = palette.dividerColor
            titleTv.setTextColor(palette.textPrimary)
            descTv.setTextColor(palette.textSecondary)
        }

        dialogBinding.cardAbout.setOnClickListener { showAboutSettingsScreen() }
        dialogBinding.cardLicenses.setOnClickListener { showLicensesSettingsScreen() }
        dialogBinding.cardLunarAlmanac.setOnClickListener { showLunarAlmanacSettingsScreen() }
        dialogBinding.cardPrivacy.setOnClickListener { showPrivacySettingsScreen() }
        dialogBinding.cardRoadmap.setOnClickListener { showRoadmapSettingsScreen() }
        dialogBinding.cardUserManual.setOnClickListener { showUserManualSettingsScreen() }

        trackDialog(dialog)
        dialog.show()
    }

    fun shareProject() {
        val message = activity.getString(R.string.share_message_body, PLAY_STORE_URL, WEBSITE_URL)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, message)
        }
        val chooser = Intent.createChooser(sendIntent, activity.getString(R.string.share_chooser_title))
        activity.startActivity(chooser)
    }

    companion object {
        const val PLAY_STORE_URL = "https://play.google.com/store/apps/details?id=com.weatherapp.libre"
        const val WEBSITE_URL = "https://www.weatherapplibre.ca"
    }
}
