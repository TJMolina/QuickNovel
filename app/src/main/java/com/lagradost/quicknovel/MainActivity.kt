package com.lagradost.quicknovel

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.IdRes
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import androidx.preference.PreferenceManager
import com.anggrayudi.storage.StorageFile
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.ResponseParser
import com.lagradost.nicehttp.ignoreAllSSLErrors
import com.lagradost.quicknovel.APIRepository.Companion.providersActive
import com.lagradost.quicknovel.BookDownloader2.openQuickStream
import com.lagradost.quicknovel.BookDownloader2Helper.checkWrite
import com.lagradost.quicknovel.BookDownloader2Helper.createQuickStream
import com.lagradost.quicknovel.BookDownloader2Helper.requestRW
import com.lagradost.quicknovel.CommonActivity.activity
import com.lagradost.quicknovel.CommonActivity.showToast
import com.lagradost.quicknovel.CommonActivity.updateLocale
import com.lagradost.quicknovel.DataStore.getKey
import com.lagradost.quicknovel.DataStore.getKeys
import com.lagradost.quicknovel.DataStore.toKotlinObject
import com.lagradost.quicknovel.NotificationHelper.requestNotifications
import com.lagradost.quicknovel.compose.ActionDialog
import com.lagradost.quicknovel.compose.CloudStreamTheme
import com.lagradost.quicknovel.compose.loadPrimaryColor
import com.lagradost.quicknovel.compose.loadThemeMode
import com.lagradost.quicknovel.databinding.ActivityMainBinding
import com.lagradost.quicknovel.mvvm.logError
import com.lagradost.quicknovel.mvvm.safe
import com.lagradost.quicknovel.network.CloudflareKiller
import com.lagradost.quicknovel.providers.RedditProvider
import com.lagradost.quicknovel.ui.common.ImmutableSearchResponse
import com.lagradost.quicknovel.ui.download.DownloadFragment
import com.lagradost.quicknovel.ui.result.ResultFragment
import com.lagradost.quicknovel.ui.result.ResultPageAction
import com.lagradost.quicknovel.ui.result.ResultViewModel2
import com.lagradost.quicknovel.ui.result.preview.BookmarkSelectionDialog
import com.lagradost.quicknovel.ui.result.preview.BottomPreviewDialog
import com.lagradost.quicknovel.util.Apis.Companion.apis
import com.lagradost.quicknovel.util.Apis.Companion.getApiSettings
import com.lagradost.quicknovel.util.Apis.Companion.printProviders
import com.lagradost.quicknovel.util.Coroutines
import com.lagradost.quicknovel.util.Coroutines.ioSafe
import com.lagradost.quicknovel.util.Coroutines.main
import com.lagradost.quicknovel.util.InAppUpdater.Companion.runAutoUpdate
import com.lagradost.quicknovel.util.ResultCached
import com.lagradost.quicknovel.util.UIHelper.colorFromAttribute
import com.lagradost.quicknovel.util.UIHelper.fixSystemBarsPadding
import com.lagradost.quicknovel.util.UIHelper.getResourceColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.reflect.KClass

class MainActivity : AppCompatActivity() {
    companion object {
        private var _mainActivity: WeakReference<MainActivity>? = null
        private var mainActivity
            get() = _mainActivity?.get()
            private set(value) {
                _mainActivity = WeakReference(value)
            }

        fun importEpub() {
            mainActivity?.openEpubPicker()
        }

        fun loadPreviewPage(searchResponse: SearchResponse) {
            mainActivity?.mainViewModel?.onAction(
                ResultPageAction.LoadResult(
                    response = ImmutableSearchResponse.from(searchResponse),
                )
            )
        }

        fun loadPreviewPage(searchResponse: ImmutableSearchResponse) {
            mainActivity?.mainViewModel?.onAction(ResultPageAction.LoadResult(response = searchResponse))
        }

        fun loadPreviewPage(card: DownloadFragment.DownloadDataLoaded) {
            //only called from AnyAdapter. this can be deleted.
            /*
            mainActivity?.mainViewModel?.onAction(
                ResultPageAction.LoadResult(response = ImmutableSearchResponse.from(card))
            )*/
        }

        fun loadPreviewPage(cached: ResultCached) {
            /* Not used anymore
                mainActivity?.mainViewModel?.onAction(
                    ResultPageAction.LoadResult(
                        response = ImmutableSearchResponse.from(cached),
                    )
                )

            */
        }

        const val EXTRA_URL = "extra_url"
        const val EXTRA_API_NAME = "extra_api_name"

        fun importEpubs() {
            mainActivity?.openEpubPickers()
        }

        var app = Requests(
            OkHttpClient()
                .newBuilder()
                .ignoreAllSSLErrors()
                .readTimeout(30L, TimeUnit.SECONDS)
                .build(),
            responseParser = object : ResponseParser {
                val mapper: ObjectMapper = jacksonObjectMapper().configure(
                    DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    false
                )

                override fun <T : Any> parse(text: String, kClass: KClass<T>): T {
                    return mapper.readValue(text, kClass.java)
                }

                override fun <T : Any> parseSafe(text: String, kClass: KClass<T>): T? {
                    return try {
                        mapper.readValue(text, kClass.java)
                    } catch (e: Exception) {
                        null
                    }
                }

                override fun writeValueAsString(obj: Any): String {
                    return mapper.writeValueAsString(obj)
                }
            }
        ).apply {
            defaultHeaders = mapOf("user-agent" to USER_AGENT)
        }

        val appWithInterceptor by lazy {
            Requests(
                baseClient = app.baseClient.newBuilder()
                    .addInterceptor(CloudflareKiller())
                    .build(),
                responseParser = app.responseParser
            ).apply {
                defaultHeaders = app.defaultHeaders
            }
        }

        // === API ===
        lateinit var navOptions: NavOptions

        fun loadResult(
            url: String,
            apiName: String,
            id: Int? = null,
        ) {
            (activity as? AppCompatActivity)?.loadResult(url, apiName, id)
        }

        fun loadResult(response: ImmutableSearchResponse) {
            (activity as? AppCompatActivity)?.loadResult(response)
        }

        fun Activity?.navigate(@IdRes navigation: Int, arguments: Bundle? = null) {
            try {
                if (this is FragmentActivity) {
                    val navHostFragment =
                        supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment?
                    navHostFragment?.navController?.navigate(navigation, arguments)
                }
            } catch (t: Throwable) {
                logError(t)
            }
        }

        fun FragmentActivity.loadResult(
            url: String,
            apiName: String,
            id: Int? = null,
        ) {
            runOnUiThread {
                this.navigate(
                    R.id.global_to_navigation_results,
                    ResultFragment.newInstance(
                        url = url,
                        apiName = apiName,
                        id = id
                    )
                )
            }
        }

        fun FragmentActivity.loadResult(response: ImmutableSearchResponse) {
            runOnUiThread {
                if (this is MainActivity) {
                    this.mainViewModel.onAction(
                        ResultPageAction.LoadResult(
                            response = response,
                            isPreview = false
                        )
                    )
                }
                this.navigate(R.id.global_to_navigation_results)
            }
        }

        fun Activity?.loadSearchResult(card: SearchResponse, startAction: Int = 0) {
            (this as AppCompatActivity?)?.loadResult(card.url, card.apiName, startAction)
        }

        fun AppCompatActivity.loadResultFromUrl(url: String?): Boolean {
            if (url == null) return false
            for (api in apis) {
                if (url.contains(api.mainUrl)) {
                    loadResult(url, api.name)
                    return true
                }
            }

            // kinda dirty ik
            val reddit = RedditProvider()
            RedditProvider.getName(url)?.let { name ->
                try {
                    Coroutines.main {
                        val uri = withContext(Dispatchers.IO) {
                            createQuickStream(
                                QuickStreamData(
                                    QuickStreamMetaData(
                                        "Not found",
                                        name,
                                        reddit.name,
                                    ),
                                    null,
                                    mutableListOf(ChapterData("Single Post", url, null, null))
                                )
                            )
                        }
                        openQuickStream(uri)
                    }
                } catch (e: Exception) {
                    logError(e)
                }
                return true
            }
            return false
        }
    }

    private fun NavDestination.matchDestination(@IdRes destId: Int): Boolean =
        hierarchy.any { it.id == destId }

    private fun onNavDestinationSelected(item: MenuItem, navController: NavController): Boolean {
        val builder = NavOptions.Builder().setLaunchSingleTop(true).setRestoreState(true)
            .setEnterAnim(R.anim.enter_anim)
            .setExitAnim(R.anim.exit_anim)
            .setPopEnterAnim(R.anim.pop_enter)
            .setPopExitAnim(R.anim.pop_exit)
        if (item.order and Menu.CATEGORY_SECONDARY == 0) {
            builder.setPopUpTo(
                navController.graph.findStartDestination().id,
                inclusive = false,
                saveState = true
            )
        }
        val options = builder.build()
        return try {
            navController.navigate(item.itemId, null, options)
            navController.currentDestination?.matchDestination(item.itemId) == true
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    private val mainViewModel: ResultViewModel2 by viewModels()
    fun importUri(uri: Uri) = safe {
        val ctx: Context = this

        safe {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }

        val file = StorageFile.from(ctx, uri)
        val fileName = file?.name
        val mimeType = file?.mimeType //ctx.contentResolver.getType(uri)
        println("Loaded epub file. Selected URI path: $uri - Name: $fileName with type $mimeType")

        ioSafe {
            try {
                if (mimeType == "application/pdf" || fileName?.endsWith(".pdf") == true) {
                    BookDownloader2.downloadPDFWorkThread(uri, ctx)
                } else {
                    BookDownloader2.downloadWorkThread(uri, ctx)
                }
            } catch (t: Throwable) {
                logError(t)
                showToast(t.message)
            }
        }
    }
    //imports area -------------------------------
    private val epubPathPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            // It lies, it can be null if file manager quits.
            if (uri == null) return@registerForActivityResult
            importUri(uri)
        }

    private val epubPathsPicker =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isEmpty()) return@registerForActivityResult
            uris.forEach { uri ->
                importUri(uri)
            }
        }

    private fun openEpubPicker() {
        safe {
            epubPathPicker.launch(
                arrayOf(
                    //"text/plain",
                    //"text/str",
                    //"application/octet-stream",
                    "application/pdf",
                    "application/epub+zip",
                )
            )
        }
    }
    private fun openEpubPickers() {
        safe {
            epubPathsPicker.launch(
                arrayOf(
                    //"text/plain",
                    //"text/str",
                    //"application/octet-stream",
                    "application/pdf",
                    "application/epub+zip",
                )
            )
        }
    }
    /* // MOON READER WONT RETURN THE DURATION, BUT THIS CAN BE USED FOR SOME USER FEEDBACK IN THE FUTURE??? SEE @moonreader
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
    }*/

    override fun onResume() {
        super.onResume()
        activity = this
        mainActivity = this
    }

    /*override fun onBackPressed() {
        if (backPressed()) return
        super.onBackPressed()
    }*/

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        //this is from New Chapters notification
        val urlExtra = intent.getStringExtra(EXTRA_URL)
        val apiNameExtra = intent.getStringExtra(EXTRA_API_NAME)
        if (urlExtra != null && apiNameExtra != null) {
            loadResult(urlExtra, apiNameExtra)
            return
        }

        if (intent.action == Intent.ACTION_SEND) {
            val extraText = try { // I don't trust android
                intent.getStringExtra(Intent.EXTRA_TEXT)
            } catch (e: Exception) {
                null
            }
            val cd = intent.clipData
            val item = if (cd != null && cd.itemCount > 0) cd.getItemAt(0) else null
            val url = item?.text?.toString()

            // idk what I am doing, just hope any of these work
            if (item?.uri != null && loadResultFromUrl(item.uri?.toString())) {
                return
            }
            if (url != null && loadResultFromUrl(url)) {
                return
            }
            if (extraText != null && loadResultFromUrl(extraText)) {
                return
            }
        }
        val data: String? = intent.data?.toString()
        loadResultFromUrl(data)
    }

    override fun onNewIntent(intent: Intent) {
        handleIntent(intent)
        super.onNewIntent(intent)
    }


    private fun updateNavBar(destination: NavDestination) {
        //this.hideKeyboard()

        val isNavVisible = listOf(
            R.id.navigation_mainpage,
            R.id.navigation_homepage,
            R.id.navigation_history,
            R.id.navigation_download,
            R.id.navigation_search,
            R.id.navigation_settings,
        ).contains(destination.id)

        binding?.apply {
            navView.isVisible = isNavVisible
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateLocale() // android fucks me by chaining lang when rotating the phone
    }

    var binding: ActivityMainBinding? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        mainActivity = this

        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)

        val settingsManager = PreferenceManager.getDefaultSharedPreferences(this)
        CommonActivity.loadThemes(this)
        CommonActivity.init(this)

        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding!!.root)

        findViewById<ComposeView>(R.id.main_compose_view).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val state by mainViewModel.state.collectAsState()
                val context = LocalContext.current
                CloudStreamTheme(
                    mode = context.loadThemeMode(),
                    primaryColor = context.loadPrimaryColor(),
                ) {
                    if (state.dialogState?.isPreviewOpen == true) {
                        BottomPreviewDialog(
                            response = state.response,
                            isLoading = state.loadingResponse,
                            bookmarks = state.bookmarks,
                            currentBookmarkId = state.currentBookmark,
                            showMoreInfo = state.showMoreInfo,
                            onDismiss = { mainViewModel.onAction(ResultPageAction.DismissDialog) },
                            onAction = { mainViewModel.onAction(it) }
                        )
                    }

                    if (state.dialogState?.isDeleteConfirmationOpen == true) {
                        val title = state.deleteTarget?.name ?: state.response?.name
                        if (title != null) {
                            ActionDialog(
                                title = stringResource(R.string.delete),
                                text = stringResource(R.string.permanently_delete_format).format(title),
                                confirmText = stringResource(R.string.delete),
                                dismissText = stringResource(R.string.cancel),
                                dismiss = { mainViewModel.onAction(ResultPageAction.DismissDeleteConfirmation) },
                                confirm = {
                                    mainViewModel.onAction(ResultPageAction.DeleteNovel(0)) // ID handled by VM
                                }
                            )
                        }
                    }

                    if (state.dialogState?.isBookmarkSelectionOpen == true) {
                        BookmarkSelectionDialog(
                            bookmarks = state.bookmarks,
                            currentBookmarkId = state.currentBookmark,
                            onDismiss = { mainViewModel.onAction(ResultPageAction.DismissDialog) },
                            onAction = { mainViewModel.onAction(it) }
                        )
                    }
                }
            }
        }

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController

        navController.addOnDestinationChangedListener { _: NavController, navDestination: NavDestination, bundle: Bundle? ->
            // Intercept search and add a query
            updateNavBar(navDestination)
        }

        val navView: BottomNavigationView = findViewById(R.id.nav_view)

        val rippleColor = ColorStateList.valueOf(getResourceColor(R.attr.colorPrimary, 0.1f))
        navView.itemRippleColor = rippleColor
        navView.itemActiveIndicatorColor = rippleColor

        fixSystemBarsPadding(
            navView, heightResId = R.dimen.nav_view_height,
            padTop = false,
            overlayCutout = false
        )

        navView.setOnItemSelectedListener { item ->
            onNavDestinationSelected(
                item,
                navController = navController
            )
        }

        //val navController = findNavController(R.id.nav_host_fragment)

        //window.navigationBarColor = colorFromAttribute(R.attr.darkBackground)
        navOptions = NavOptions.Builder()
            .setLaunchSingleTop(true)
            .setEnterAnim(R.anim.nav_enter_anim)
            .setExitAnim(R.anim.nav_exit_anim)
            .setPopEnterAnim(R.anim.nav_pop_enter)
            .setPopExitAnim(R.anim.nav_pop_exit)
            .setPopUpTo(navController.graph.startDestinationId, false)
            .build()

        //navView.itemRippleColor =
        //    ColorStateList.valueOf(getResourceColor(R.attr.colorPrimary, 0.1f))

        val apiNames = getApiSettings()
        providersActive = apiNames
        val edit = settingsManager.edit()
        edit.putStringSet(getString(R.string.search_providers_list_key), providersActive)
        edit.apply()

        thread { // IDK, WARMUP OR SMTH, THIS WILL JUST REDUCE THE INITIAL LOADING TIME FOR DOWNLOADS, NO REAL USAGE, SEE @WARMUP
            val keys = getKeys(DOWNLOAD_FOLDER)
            for (k in keys) {
                getKey<DownloadFragment.DownloadData>(k)
            }
        }

        ioSafe {
            runAutoUpdate()
        }

        handleIntent(intent)

        if (!checkWrite()) {
            requestRW()
        }
        // Note that android can normally not request 2 permissions at once
        // But storage permissions are not required for android 13, but notifications are
        requestNotifications()

        printProviders()

        //loadResult("https://www.novelpassion.com/novel/battle-frenzy")
        //loadResult("https://www.royalroad.com/fiction/40182/only-villains-do-that", MainActivity.activeAPI.name)
        thread {
            test()
        }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    window?.navigationBarColor = colorFromAttribute(R.attr.primaryGrayBackground)
                    updateLocale()

                    // If we don't disable we end up in a loop with default behavior calling
                    // this callback as well, so we disable it, run default behavior,
                    // then re-enable this callback so it can be used for next back press.
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        )
    }

    fun test() {
        // val response = app.get("https://ranobes.net/up/a-bored-lich/936969-1.html")
        // println(response.text)
    }
}