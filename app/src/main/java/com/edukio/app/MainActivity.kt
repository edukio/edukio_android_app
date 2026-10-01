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
import android.widget.FrameLayout
import android.widget.ProgressBar
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
        private const val START_URL = "https://app.edukio.com/?app_version=2.2"
        private const val EDUKIO_SITE = "https://app.edukio.com"
        private const val STARTUP_TIMEOUT_MS = 20_000L
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private val handler = Handler(Looper.getMainLooper())

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraImageUri: Uri? = null

    // Controle de saúde/recuperação do WebView.
    private var waitingForAppReady = true
    private var recoveryStage = 0
    private var recoveryInProgress = false
    private var mainFrameHasExternalFailure = false

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

        webView = findViewById(R.id.webview)
        progressBar = findViewById(R.id.progressBar)

        webView.setBackgroundColor(Color.WHITE)
        webView.visibility = View.INVISIBLE
        progressBar.visibility = View.VISIBLE

        WindowCompat.getInsetsController(window, window.decorView)?.apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        val container = findViewById<FrameLayout>(R.id.container)
        ViewCompat.setOnApplyWindowInsetsListener(container) { view, insets ->
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

                if (!waitingForAppReady || url.isNullOrBlank()) {
                    return
                }

                val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
                val host = uri.host.orEmpty()

                if (isGoogleAuthHost(host)) {
                    // Não considerar o tempo gasto pelo usuário na autenticação do Google.
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

                handler.postDelayed({
                    progressBar.visibility = View.GONE
                    webView.visibility = View.VISIBLE
                    checkAndRequestPermissions()
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

                if (request?.isForMainFrame == true && isConnectivityError(error?.errorCode)) {
                    // Falha de internet/DNS/SSL não deve apagar os dados do usuário.
                    mainFrameHasExternalFailure = true
                    cancelStartupWatchdog()
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
                    // Erro do servidor não deve causar logout/limpeza automática.
                    mainFrameHasExternalFailure = true
                    cancelStartupWatchdog()
                }
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

    private fun startHealthCheckedLoad() {
        waitingForAppReady = true
        recoveryStage = 0
        recoveryInProgress = false
        mainFrameHasExternalFailure = false
        webView.loadUrl(START_URL)
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
                // Primeira falha: apenas tenta novamente, preservando sessão/dados.
                recoveryStage = 1
                mainFrameHasExternalFailure = false
                webView.reload()
                scheduleStartupWatchdog()
            }

            1 -> {
                // Segunda falha: limpa os dados locais do Edukio e reinicia.
                recoveryStage = 2
                clearEdukioBrowsingDataAndReload()
            }

            else -> {
                // Evita ciclo infinito de limpeza/reload.
                waitingForAppReady = false
                cancelStartupWatchdog()
                Toast.makeText(
                    this,
                    "Não foi possível iniciar o Edukio. Verifique sua conexão e tente abrir o aplicativo novamente.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
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

            val recoveryUrl = "$START_URL&recovery=${System.currentTimeMillis()}"
            webView.loadUrl(recoveryUrl)
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)) {
            try {
                // Inclui cache de rede, cookies, armazenamento JavaScript
                // e service workers nas WebViews compatíveis.
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

        // Fallback para Android System WebView sem DELETE_BROWSING_DATA.
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
