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
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private val handler = Handler(Looper.getMainLooper())

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraImageUri: Uri? = null

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

        // Edge-to-edge moderno
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Usa o layout XML
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)
        progressBar = findViewById(R.id.progressBar)

        // Corrige o flash preto com fundo branco invisível até carregar
        webView.setBackgroundColor(Color.WHITE)
        webView.visibility = View.INVISIBLE
        progressBar.visibility = View.VISIBLE

        // Ícones escuros na barra de status/navegação
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
        webView.loadUrl("https://app.edukio.com/?app_version=2.2")

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
                // Cancela qualquer seleção anterior que ainda esteja pendente.
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
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)

                handler.postDelayed({
                    progressBar.visibility = View.GONE
                    webView.visibility = View.VISIBLE
                    checkAndRequestPermissions()
                }, 300)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                return if (url.contains("edukio.com") || url.contains("accounts.google.com") || url.contains("accounts.youtube.com")) {
                    false
                } else {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        view.context.startActivity(intent)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    true
                }
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

        // Se a página usar capture="camera", abre a câmera diretamente.
        if (fileChooserParams?.isCaptureEnabled == true && cameraIntent != null) {
            fileChooserLauncher.launch(cameraIntent)
            return
        }

        // Caso normal: abre o seletor de arquivos e oferece a câmera como opção extra.
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
}
