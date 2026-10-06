package com.edukio.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.view.View
import android.webkit.*
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebStorageCompat
import androidx.webkit.WebViewFeature
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        private const val START_URL = "https://app.edukio.com/?app_version=2.3"
        private const val EDUKIO_SITE = "https://app.edukio.com"
        private const val STARTUP_TIMEOUT_MS = 20_000L
        private const val LIGHT_SYSTEM_BAR_COLOR = "#FFFFFF"
        private const val DARK_SYSTEM_BAR_COLOR = "#0F172A"
    }

    private lateinit var rootContainer: FrameLayout
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var errorContainer: LinearLayout
    private lateinit var errorMessage: TextView
    private lateinit var retryButton: Button
    private val handler = Handler(Looper.getMainLooper())

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraImageUri: Uri? = null

    private var waitingForAppReady = true
    private var recoveryStage = 0
    private var recoveryInProgress = false
    private var mainFrameHasExternalFailure = false
    private var errorScreenVisible = false

    private val startupWatchdog = Runnable {
        if (waitingForAppReady && !recoveryInProgress && !mainFrameHasExternalFailure) {
            handleStartupTimeout()
        }
    }

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = filePathCallback ?: return@registerForActivityResult

            val uris = if (result.resultCode == RESULT_OK) {
                extractSelectedUris(result.data)
            } else {
                null
            }

            callback.onReceiveValue(uris)
            filePathCallback = null
            cameraImageUri = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        rootContainer = findViewById(R.id.container)
        webView = findViewById(R.id.webview)
        progressBar = findViewById(R.id.progressBar)
        errorContainer = findViewById(R.id.errorContainer)
        errorMessage = findViewById(R.id.errorMessage)
        retryButton = findViewById(R.id.btnRetry)

        webView.setBackgroundColor(Color.WHITE)
        webView.visibility = View.INVISIBLE
        progressBar.visibility = View.VISIBLE
        errorContainer.visibility = View.GONE

        retryButton.setOnClickListener {
            hideNativeErrorScreen()
            showLoadingScreen()
            startHealthCheckedLoad()
        }

        // Aplica imediatamente o tema já salvo pelo site para evitar flash
        // branco nas barras quando o usuário usa o tema escuro.
        applySystemBarTheme(readDarkThemeFromCookie())

        ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        setupWebView()
        startHealthCheckedLoad()

        onBackPressedDispatcher.addCallback(this, backPressedCallback)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val customUserAgent = String.format(
            "Mozilla/5.0 (Linux; Android %s; %s Build/%s) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
            Build.VERSION.RELEASE,
            Build.MODEL,
            Build.ID
        )
        webView.settings.userAgentString = customUserAgent

        webView.settings.javaScriptEnabled = true
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = true
        webView.settings.safeBrowsingEnabled = true

        webView.removeJavascriptInterface("searchBoxJavaBridge_")

        // Ponte mínima para o tema: o site apenas informa se o body está
        // com a classe theme-dark. Nenhum dado sensível é exposto.
        webView.addJavascriptInterface(ThemeBridge(), "EdukioThemeBridge")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                if (request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
                    request.grant(request.resources)
                } else {
                    super.onPermissionRequest(request)
                }
            }

            override fun onShowFileChooser(
                webView: WebView?,
                newFilePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = newFilePathCallback

                if (newFilePathCallback == null) {
                    filePathCallback = null
                    return false
                }

                return try {
                    launchFileChooser(fileChooserParams)
                    true
                } catch (e: Exception) {
                    e.printStackTrace()
                    filePathCallback?.onReceiveValue(null)
                    filePathCallback = null
                    cameraImageUri = null
                    false
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)

                if (!waitingForAppReady || url.isNullOrBlank()) return

                val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
                val host = uri.host.orEmpty()

                if (isGoogleAuthHost(host)) {
                    cancelStartupWatchdog()
                    return
                }

                if (isEdukioHost(host)) {
                    mainFrameHasExternalFailure = false
                    scheduleStartupWatchdog()
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)

                if (errorScreenVisible) return

                val host = runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault("")
                if (isEdukioHost(host)) {
                    installThemeObserver(view)
                    checkWebAppReady(view)
                    handler.postDelayed({ checkWebAppReady(view) }, 500)
                    handler.postDelayed({ checkWebAppReady(view) }, 1500)
                }

                handler.postDelayed({
                    if (!errorScreenVisible) {
                        progressBar.visibility = View.GONE
                        webView.visibility = View.VISIBLE
                        checkAndRequestPermissions()
                    }
                }, 300)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return handleNavigation(view, request.url)
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                return handleNavigation(view, Uri.parse(url))
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)

                if (request?.isForMainFrame == true) {
                    mainFrameHasExternalFailure = true
                    cancelStartupWatchdog()

                    val message = if (isConnectivityError(error?.errorCode)) {
                        "Não foi possível conectar ao Edukio. Verifique sua conexão com a internet e tente novamente."
                    } else {
                        "Não foi possível carregar o Edukio. Tente novamente em alguns instantes."
                    }

                    showNativeErrorScreen(message)
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)

                if (
                    request?.isForMainFrame == true &&
                    (errorResponse?.statusCode ?: 0) >= 500
                ) {
                    mainFrameHasExternalFailure = true
                    cancelStartupWatchdog()
                    showNativeErrorScreen(
                        "O Edukio está temporariamente indisponível. Tente novamente em alguns instantes."
                    )
                }
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: android.net.http.SslError?
            ) {
                handler?.cancel()
                mainFrameHasExternalFailure = true
                cancelStartupWatchdog()
                showNativeErrorScreen(
                    "Não foi possível estabelecer uma conexão segura com o Edukio. Tente novamente mais tarde."
                )
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?
            ): Boolean {
                cancelStartupWatchdog()
                view?.destroy()
                handler.post { recreate() }
                return true
            }
        }

        webView.setDownloadListener { url, agent, contentDisposition, mimeType, _ ->
            val request = DownloadManager.Request(Uri.parse(url))
            request.setMimeType(mimeType)
            val cookies = CookieManager.getInstance().getCookie(url)
            request.addRequestHeader("cookie", cookies)
            request.addRequestHeader("User-Agent", agent)
            request.setDescription("Baixando arquivo...")
            request.setTitle(URLUtil.guessFileName(url, contentDisposition, mimeType))
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                @Suppress("DEPRECATION")
                request.allowScanningByMediaScanner()
            }

            request.setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                URLUtil.guessFileName(url, contentDisposition, mimeType)
            )

            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
        }
    }

    private fun handleNavigation(view: WebView, uri: Uri): Boolean {
        if (uri.scheme == "edukio" && uri.host == "app-ready") {
            markAppReady()
            return true
        }

        val host = uri.host.orEmpty()

        return if (
            isEdukioHost(host) ||
            isGoogleAuthHost(host) ||
            host == "accounts.youtube.com"
        ) {
            false
        } else {
            try {
                val intent = Intent(Intent.ACTION_VIEW, uri)
                view.context.startActivity(intent)
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(
                    this,
                    "Não foi possível abrir este link.",
                    Toast.LENGTH_SHORT
                ).show()
            }
            true
        }
    }

    private fun isEdukioHost(host: String): Boolean {
        return host == "edukio.com" || host.endsWith(".edukio.com")
    }

    private fun isGoogleAuthHost(host: String): Boolean {
        return host == "accounts.google.com"
    }

    private inner class ThemeBridge {
        @JavascriptInterface
        fun setDarkMode(isDark: Boolean) {
            runOnUiThread {
                val currentHost = runCatching {
                    Uri.parse(webView.url.orEmpty()).host.orEmpty()
                }.getOrDefault("")

                // Aceita a alteração somente enquanto a página principal
                // carregada no WebView pertence ao Edukio.
                if (isEdukioHost(currentHost)) {
                    applySystemBarTheme(isDark)
                }
            }
        }
    }

    private fun installThemeObserver(view: WebView?) {
        if (view == null) return

        view.evaluateJavascript(
            """
            (function() {
                if (!document.body || !window.EdukioThemeBridge) return;

                function syncEdukioAndroidTheme() {
                    window.EdukioThemeBridge.setDarkMode(
                        document.body.classList.contains('theme-dark')
                    );
                }

                syncEdukioAndroidTheme();

                if (window.__edukioAndroidThemeObserver) {
                    window.__edukioAndroidThemeObserver.disconnect();
                }

                window.__edukioAndroidThemeObserver = new MutationObserver(function(mutations) {
                    for (var i = 0; i < mutations.length; i++) {
                        if (mutations[i].type === 'attributes' &&
                            mutations[i].attributeName === 'class') {
                            syncEdukioAndroidTheme();
                            break;
                        }
                    }
                });

                window.__edukioAndroidThemeObserver.observe(document.body, {
                    attributes: true,
                    attributeFilter: ['class']
                });
            })();
            """.trimIndent(),
            null
        )
    }

    private fun readDarkThemeFromCookie(): Boolean {
        val cookies = CookieManager.getInstance().getCookie(EDUKIO_SITE) ?: return false

        return cookies
            .split(";")
            .map { it.trim() }
            .any { it.equals("edukio_tema=dark", ignoreCase = true) }
    }

    private fun applySystemBarTheme(isDark: Boolean) {
        val barColor = Color.parseColor(
            if (isDark) DARK_SYSTEM_BAR_COLOR else LIGHT_SYSTEM_BAR_COLOR
        )

        // O container ocupa também as áreas edge-to-edge. Isso garante a
        // cor correta atrás das barras inclusive em versões recentes do Android.
        rootContainer.setBackgroundColor(barColor)

        @Suppress("DEPRECATION")
        window.statusBarColor = barColor

        @Suppress("DEPRECATION")
        window.navigationBarColor = barColor

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        WindowCompat.getInsetsController(window, window.decorView)?.apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }
    }

    private fun startHealthCheckedLoad() {
        waitingForAppReady = true
        recoveryStage = 0
        recoveryInProgress = false
        mainFrameHasExternalFailure = false
        errorScreenVisible = false
        webView.loadUrl(START_URL)
    }

    private fun checkWebAppReady(view: WebView?) {
        if (!waitingForAppReady || view == null || errorScreenVisible) return

        view.evaluateJavascript(
            "(window.edukioAppReady === true).toString()"
        ) { result ->
            if (result == "\\"true\\"" || result == "true") {
                markAppReady()
            }
        }
    }

    private fun markAppReady() {
        waitingForAppReady = false
        recoveryStage = 0
        recoveryInProgress = false
        mainFrameHasExternalFailure = false
        cancelStartupWatchdog()
    }

    private fun scheduleStartupWatchdog() {
        cancelStartupWatchdog()
        handler.postDelayed(startupWatchdog, STARTUP_TIMEOUT_MS)
    }

    private fun cancelStartupWatchdog() {
        handler.removeCallbacks(startupWatchdog)
    }

    private fun handleStartupTimeout() {
        when (recoveryStage) {
            0 -> {
                recoveryStage = 1
                mainFrameHasExternalFailure = false
                webView.reload()
                scheduleStartupWatchdog()
            }

            1 -> {
                recoveryStage = 2
                clearEdukioBrowsingDataAndReload()
            }

            else -> {
                waitingForAppReady = false
                cancelStartupWatchdog()
                showNativeErrorScreen(
                    "Não foi possível iniciar o Edukio. Verifique sua conexão e tente novamente."
                )
            }
        }
    }

    private fun showLoadingScreen() {
        errorScreenVisible = false
        errorContainer.visibility = View.GONE
        webView.visibility = View.INVISIBLE
        progressBar.visibility = View.VISIBLE
    }

    private fun showNativeErrorScreen(message: String) {
        runOnUiThread {
            errorScreenVisible = true
            progressBar.visibility = View.GONE
            webView.visibility = View.INVISIBLE
            errorMessage.text = message
            errorContainer.visibility = View.VISIBLE
        }
    }

    private fun hideNativeErrorScreen() {
        errorScreenVisible = false
        errorContainer.visibility = View.GONE
    }

    private fun clearEdukioBrowsingDataAndReload() {
        if (recoveryInProgress) return

        recoveryInProgress = true
        cancelStartupWatchdog()

        val reloadAfterCleanup = Runnable {
            webView.clearCache(true)
            webView.clearHistory()
            @Suppress("DEPRECATION")
            webView.clearFormData()

            recoveryInProgress = false
            waitingForAppReady = true
            mainFrameHasExternalFailure = false
            errorScreenVisible = false
            errorContainer.visibility = View.GONE
            progressBar.visibility = View.VISIBLE
            webView.visibility = View.INVISIBLE

            val recoveryUrl = "$START_URL&recovery=${System.currentTimeMillis()}"
            webView.loadUrl(recoveryUrl)
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)) {
            try {
                WebStorageCompat.deleteBrowsingDataForSite(
                    WebStorage.getInstance(),
                    EDUKIO_SITE,
                    reloadAfterCleanup
                )
                return
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
            reloadAfterCleanup.run()
        }
    }

    private fun isConnectivityError(errorCode: Int?): Boolean {
        return errorCode in setOf(
            ERROR_HOST_LOOKUP,
            ERROR_CONNECT,
            ERROR_TIMEOUT,
            ERROR_IO,
            ERROR_PROXY_AUTHENTICATION,
            ERROR_FAILED_SSL_HANDSHAKE
        )
    }

    private fun launchFileChooser(fileChooserParams: WebChromeClient.FileChooserParams?) {
        val fileIntent = try {
            fileChooserParams?.createIntent() ?: defaultFileIntent()
        } catch (e: Exception) {
            defaultFileIntent()
        }.apply {
            addCategory(Intent.CATEGORY_OPENABLE)

            if (fileChooserParams?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
        }

        val cameraIntent = if (acceptsImages(fileChooserParams) && hasCameraPermission()) {
            createCameraIntent()
        } else {
            null
        }

        if (fileChooserParams?.isCaptureEnabled == true && cameraIntent != null) {
            fileChooserLauncher.launch(cameraIntent)
            return
        }

        val chooserIntent = Intent(Intent.ACTION_CHOOSER).apply {
            putExtra(Intent.EXTRA_INTENT, fileIntent)
            putExtra(Intent.EXTRA_TITLE, "Selecionar arquivo")

            if (cameraIntent != null) {
                putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(cameraIntent))
            }
        }

        fileChooserLauncher.launch(chooserIntent)
    }

    private fun defaultFileIntent(): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
    }

    private fun acceptsImages(fileChooserParams: WebChromeClient.FileChooserParams?): Boolean {
        val acceptTypes = fileChooserParams?.acceptTypes
            ?.flatMap { it.split(",") }
            ?.map { it.trim().lowercase() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

        return acceptTypes.isEmpty() ||
            acceptTypes.any {
                it == "*/*" ||
                it.startsWith("image/") ||
                it in setOf(".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif")
            }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun createCameraIntent(): Intent? {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            return null
        }

        val photoFile = File.createTempFile(
            "edukio_camera_",
            ".jpg",
            cacheDir
        )

        val photoUri = FileProvider.getUriForFile(
            this,
            packageName + ".fileprovider",
            photoFile
        )

        cameraImageUri = photoUri

        return Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            clipData = ClipData.newRawUri("Edukio camera", photoUri)
        }
    }

    private fun extractSelectedUris(data: Intent?): Array<Uri>? {
        val clipData = data?.clipData
        if (clipData != null && clipData.itemCount > 0) {
            return Array(clipData.itemCount) { index ->
                clipData.getItemAt(index).uri
            }
        }

        data?.data?.let { selectedUri ->
            return arrayOf(selectedUri)
        }

        cameraImageUri?.let { capturedImage ->
            return arrayOf(capturedImage)
        }

        return null
    }

    private fun checkAndRequestPermissions() {
        val permissionsNeeded = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.CAMERA)
        }

        if (permissionsNeeded.isNotEmpty()) {
            requestPermissions(permissionsNeeded.toTypedArray(), 1)
        }
    }

    private val backPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (errorScreenVisible) {
                hideNativeErrorScreen()
                showLoadingScreen()
                startHealthCheckedLoad()
                return
            }

            if (webView.canGoBack()) {
                webView.goBack()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    override fun onDestroy() {
        cancelStartupWatchdog()
        super.onDestroy()
    }
}
