package com.grapie.pickitup

import android.content.ActivityNotFoundException
import android.content.Context
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * An account's marketplace site in a WebView, in that account's own profile: the login page (the
 * cookies it leaves behind are what its client uses) or, with [EXTRA_URL], any page such as the
 * order list. [EXTRA_ACCOUNT] names the account (none = the active one).
 */
class LoginActivity : ComponentActivity() {
    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_ACCOUNT = "account"
        private const val READ_TIMEOUT_MS = 3000L

        /** The site is open here: background fetches wait, two pages must not rotate one login pair. */
        @Volatile private var openCount = 0
        val isOpen get() = openCount > 0
        private val TITLE_SUFFIX = Regex("\\s*[-–—|]\\s*(OZON|Ozon|ozon\\.ru).*$")

        fun loginIntent(context: Context, account: Account): Intent =
            Intent(context, LoginActivity::class.java).putExtra(EXTRA_ACCOUNT, account.id)

        fun openPage(context: Context, account: Account, url: String) =
            context.startActivity(loginIntent(context, account).putExtra(EXTRA_URL, url))
    }

    private lateinit var web: WebView
    private lateinit var account: Account
    private val handler = Handler(Looper.getMainLooper())
    private var finishing = false
    private var counted = false
    /** Login pair when the page opened: a change by closing time means the page rotated it. */
    private var startPrint: String? = null

    private fun uncount() {
        if (counted) openCount--
        counted = false
    }
    private val host = mutableStateOf("ozon.ru")
    private val progress = mutableIntStateOf(0)
    private val pageTitle = mutableStateOf<String?>(null)
    /** null = page fine; else the main frame failed to load. */
    private val failure = mutableStateOf<Failure?>(null)
    private var loadFailed = false

    private enum class Kind { OFFLINE, NETWORK, OTHER }

    private data class Failure(val kind: Kind, val detail: String)

    /** Timeouts, QUIC/connection resets, DNS: the network or the site is flaky, not the page. */
    private val networkError = Regex("TIMED_OUT|QUIC|CONNECTION|NAME_NOT_RESOLVED|NETWORK|ADDRESS|PROXY|TUNNEL|EMPTY_RESPONSE|SSL")

    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private val network = object : ConnectivityManager.NetworkCallback() {
        // Back online after a failed load: retry by itself.
        override fun onAvailable(n: Network) {
            handler.post { if (failure.value != null) retry() }
        }
    }

    private fun isOnline() = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    private fun showHost(url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme == "http" || uri.scheme == "https") uri.host?.let { host.value = it.removePrefix("www.") }
    }

    /** Opens an app link (ozon://, intent://, tel: ...) outside the WebView; falls back to its web URL. */
    private fun openExternally(url: String) {
        val intent = runCatching {
            if (url.startsWith("intent:")) Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                component = null
                selector = null
            } else Intent(Intent.ACTION_VIEW, Uri.parse(url))
        }.getOrNull() ?: return
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            val fallback = intent.getStringExtra("browser_fallback_url")
            if (fallback != null) web.loadUrl(fallback)
            else Toast.makeText(this, account.provider.text(AppSettings.strings().noAppForLink), Toast.LENGTH_SHORT).show()
        }
    }

    /** The placeholder stays up while reloading (else WebView's own error page shows through). */
    private fun retry() {
        if (progress.intValue in 1..99) return
        progress.intValue = 1
        web.reload()
    }

    // Login mode closes itself once Ozon's cookies switch from guest to a real account
    // (__Secure-user-id != 0). Only on that transition, so opening it while already logged in
    // doesn't bounce straight back. A session Ozon ended on its side may keep the account id
    // cookie: then a new access token is the transition.
    private var wasGuest = false
    private var startToken: String? = null
    private val loginWatcher = object : Runnable {
        override fun run() {
            checkLoggedIn()
            if (!finishing) handler.postDelayed(this, 1000)
        }
    }

    // Enabled only while the page has history, so the last back goes to the system and gets
    // the predictive "back to the app" animation.
    private val webBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = web.goBack()
    }

    private val ozon get() = account.provider == Provider.OZON
    private val wb get() = account.provider == Provider.WB
    private val cdek get() = account.provider == Provider.CDEK
    /** When the in-flight localStorage read (WB) started, 0 = none. A read sent while the page
     *  navigates never calls back, so a stale one is given up after [READ_TIMEOUT_MS]. */
    private var readingSince = 0L

    private fun checkLoggedIn() {
        if (finishing) return
        // CDEK: the login ends with our own redirect (shouldOverrideUrlLoading), nothing to poll.
        if (cdek) return
        if (wb) {
            // Wildberries: the site keeps the token in localStorage once WB ID sends it back here.
            val now = System.currentTimeMillis()
            if (readingSince != 0L && now - readingSince < READ_TIMEOUT_MS) return
            readingSince = now
            WbClient.readToken(web) { t ->
                readingSince = 0L
                if (finishing || t == null || WbClient.expiry(t) <= System.currentTimeMillis()) return@readToken
                if (!wasGuest && t == startToken) return@readToken
                WbClient.saveToken(account, t)
                DebugLog.d("site", "WB login detected, closing")
                loggedIn()
            }
            return
        }
        if (!OzonClient.isLoggedIn(account)) return
        if (!wasGuest && OzonClient.accessToken(account) == startToken) return
        DebugLog.tokens("site", "login detected, closing", account)
        // A new login, not a lost rotation: the main screen refreshes before this page is gone.
        OzonClient.noteLogin(account)
        loggedIn()
    }

    private fun finishCdekLogin(url: Uri) {
        finishing = true
        DebugLog.d("site", "CDEK login redirect, exchanging the code")
        lifecycleScope.launch {
            if (CdekClient.finishLogin(account, url)) loggedIn()
            else {
                finishing = false
                Toast.makeText(this@LoginActivity, AppSettings.strings().cdekLoginFailed, Toast.LENGTH_LONG).show()
                web.loadUrl(CdekClient.loginUrl(account))
            }
        }
    }

    private fun loggedIn() {
        finishing = true
        WebProfiles.cookies(account.profile).flush()
        // Now, not in onDestroy: that runs only after the main screen has resumed.
        account.setLoggedIn(true)
        setResult(RESULT_OK, Intent().putExtra(EXTRA_ACCOUNT, account.id))
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        account = Accounts.byId(intent.getStringExtra(EXTRA_ACCOUNT)) ?: Accounts.active
        openCount++
        counted = true
        if (ozon) startPrint = OzonClient.currentPrint(account)
        web = WebView(this).also { WebProfiles.apply(it, account.profile) }
        // Without MATCH_PARENT the WebView is WRAP_CONTENT inside AndroidView and pages report a
        // 0 px tall viewport to CSS: Wildberries' login (html{height:100%;overflow:hidden}) was blank.
        web.layoutParams = android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        account.provider.client.configure(web, account)
        val pageUrl = intent.getStringExtra(EXTRA_URL)
        val loginMode = pageUrl == null
        web.webViewClient = object : WebViewClient() {
            // "Log in with the app" and similar buttons lead to ozon:// / intent:// links: hand them
            // to the Ozon app. Once the login is approved there, this page notices it by itself
            // and the cookie watcher closes the window.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                // CDEK: our PKCE login came back. The site must not load it (it would use the code up).
                if (loginMode && cdek && !finishing && CdekClient.isLoginRedirect(account, url)) {
                    finishCdekLogin(url)
                    return true
                }
                if (url.scheme == "http" || url.scheme == "https") return false
                openExternally(url.toString())
                return true
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): android.webkit.WebResourceResponse? {
                if (request.url.host == "www.ozon.ru" && (request.isForMainFrame || request.url.path.orEmpty().startsWith("/api/")))
                    DebugLog.d("site", "${request.method} ${DebugLog.url(request.url.toString())}${if (request.isForMainFrame) " (document)" else ""}")
                return null
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: android.webkit.WebResourceResponse) {
                if (request.url.host?.endsWith("ozon.ru") == true)
                    DebugLog.d("site", "HTTP ${response.statusCode} ${request.method} ${DebugLog.url(request.url.toString())}")
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                DebugLog.d("site", "page started ${DebugLog.url(url)}")
                loadFailed = false
                showHost(url)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame) return
                loadFailed = true
                val detail = error.description?.toString().orEmpty().removePrefix("net::")
                failure.value = Failure(
                    when {
                        !isOnline() -> Kind.OFFLINE
                        networkError.containsMatchIn(detail) -> Kind.NETWORK
                        else -> Kind.OTHER
                    },
                    detail,
                )
            }

            // The placeholder goes only once real content is drawn: onPageFinished also fires for
            // the old error page when a reload starts, which let WebView's own error page show.
            override fun onPageCommitVisible(view: WebView, url: String) {
                if (!loadFailed) failure.value = null
            }

            override fun onPageFinished(view: WebView, url: String) {
                // A rotated pair from this document load goes to disk at once (swipe from Recents kills us).
                WebProfiles.cookies(account.profile).flush()
                if (loginMode) checkLoggedIn()
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                webBack.isEnabled = view.canGoBack()
                showHost(url)
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.intValue = newProgress
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                // Ozon titles end with a " - OZON"-like suffix; a bare URL means "no title yet".
                pageTitle.value = title?.takeUnless { it.isBlank() || it.startsWith("http") || "ozon.ru/" in it }
                    ?.replace(TITLE_SUFFIX, "")?.trim()?.ifEmpty { null }
            }
        }
        DebugLog.d("site", "opened for account $account, ${if (loginMode) "login" else "page " + DebugLog.url(pageUrl)}")
        if (ozon) DebugLog.tokens("site", "tokens at open", account)
        if (loginMode) {
            wasGuest = !account.provider.client.isLoggedIn(account)
            startToken = if (ozon) OzonClient.accessToken(account) else if (wb) WbClient.token(account) else null
            handler.postDelayed(loginWatcher, 1000)
        }
        onBackPressedDispatcher.addCallback(this, webBack)
        runCatching { connectivity.registerDefaultNetworkCallback(network) }

        setContent {
            val s = AppSettings.strings(AppSettings.language.collectAsStateWithLifecycle().value)
            val dark = isAppDark(AppSettings.theme.collectAsStateWithLifecycle().value)
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.Transparent.toArgb())
                else SystemBarStyle.light(Color.Transparent.toArgb(), Color.Transparent.toArgb())
                enableEdgeToEdge(style, style)
            }
            PickItUpTheme {
                Page(if (loginMode) account.provider.text(s.loginTitle) else pageTitle.value ?: s.ordersTitle, if (loginMode) s.loginHint else null, s)
            }
        }

        if (savedInstanceState == null) web.loadUrl(pageUrl ?: account.provider.client.loginUrl(account)) else web.restoreState(savedInstanceState)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Page(title: String, hint: String?, s: Strings) {
        val closeLabel = s.close
        Scaffold(
            topBar = {
                Column {
                    TopAppBar(
                        navigationIcon = {
                            IconButton(onClick = ::finish) { Ic(R.drawable.ic_close, description = closeLabel) }
                        },
                        title = {
                            Column {
                                AnimatedContent(title, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "title") {
                                    Text(it, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Ic(R.drawable.ic_lock, Modifier.size(14.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.width(4.dp))
                                    Text(host.value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    )
                    val p = progress.intValue
                    AnimatedVisibility(p in 1..99, enter = fadeIn(), exit = fadeOut()) {
                        LinearProgressIndicator(progress = { p / 100f }, modifier = Modifier.fillMaxWidth())
                    }
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()).imePadding()) {
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    AndroidView(factory = { web }, modifier = Modifier.fillMaxSize())
                    // Our own page instead of WebView's grey "Web page not available".
                    androidx.compose.animation.AnimatedVisibility(failure.value != null, enter = fadeIn(), exit = fadeOut()) {
                        ErrorPlaceholder(failure.value ?: Failure(Kind.OFFLINE, ""), s)
                    }
                }
                if (hint != null) {
                    val shown = remember { androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true } }
                    AnimatedVisibility(shown, enter = slideInVertically(initialOffsetY = { it }) + fadeIn()) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                Modifier.navigationBarsPadding().padding(horizontal = 24.dp, vertical = 18.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(14.dp))
                                Text(hint, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                } else {
                    Spacer(Modifier.navigationBarsPadding())
                }
            }
        }
    }

    @Composable
    private fun ErrorPlaceholder(f: Failure, s: Strings) {
        val float = rememberInfiniteTransition(label = "float")
        val dy by float.animateFloat(-6f, 6f, infiniteRepeatable(tween(1800, easing = EaseInOutSine), RepeatMode.Reverse), label = "dy")
        val ring by float.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = EaseOutCubic)), label = "ring")
        val loading = progress.intValue in 1..99
        val primary = MaterialTheme.colorScheme.primary
        Column(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier.size(180.dp).drawBehind {
                    drawCircle(primary.copy(alpha = 0.18f * (1f - ring)), radius = size.minDimension / 2 * (0.6f + 0.4f * ring))
                },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(112.dp).graphicsLayer { translationY = dy.dp.toPx() }
                        .clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Ic(
                        if (f.kind == Kind.OTHER) R.drawable.ic_info else R.drawable.ic_cloud_off,
                        Modifier.size(52.dp), MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            val (title, text) = when (f.kind) {
                Kind.OFFLINE -> s.offlineTitle to s.offlineText
                Kind.NETWORK -> account.provider.text(s.networkErrorTitle) to s.networkErrorText
                Kind.OTHER -> s.pageErrorTitle to f.detail
            }
            Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text(
                text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (f.kind == Kind.NETWORK && f.detail.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(f.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            Spacer(Modifier.height(28.dp))
            // While reloading: a spinner in place of the button, the placeholder stays.
            Box(Modifier.height(56.dp), contentAlignment = Alignment.Center) {
                if (loading) CircularProgressIndicator(Modifier.size(32.dp))
                else PillButton(s.retry, R.drawable.ic_refresh, ::retry, Modifier.widthIn(min = 200.dp))
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    // Before the screen below resumes: the user may have logged in or out on any Ozon page.
    override fun onStart() {
        super.onStart()
        BaseActivity.uiStarted()
    }

    override fun onStop() {
        BaseActivity.uiStopped()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        DebugLog.d("ui", "LoginActivity shown")
    }

    override fun onPause() {
        super.onPause()
        DebugLog.d("ui", "LoginActivity " + if (isFinishing) "closed" else "hidden")
        // The process may be frozen or killed soon after: a pair the page rotated must be on disk.
        WebProfiles.cookies(account.profile).flush()
        if (ozon) {
            OzonClient.noteTokens(account)
            if (!finishing) account.setLoggedIn(OzonClient.isLoggedIn(account))
        } else if (wb && !finishing) {
            // Any WB page (orders) may have renewed the token: keep the newest valid one.
            WbClient.readToken(web) { t ->
                if (t != null && t != WbClient.token(account) && WbClient.expiry(t) > System.currentTimeMillis()) {
                    WbClient.saveToken(account, t)
                    account.setLoggedIn(true)
                }
            }
        }
        // The screen below may refresh as soon as it resumes (onDestroy comes later).
        if (isFinishing) uncount()
    }

    override fun onDestroy() {
        runCatching { connectivity.unregisterNetworkCallback(network) }
        handler.removeCallbacksAndMessages(null)
        uncount()
        if (ozon) {
            DebugLog.tokens("site", "closed", account)
            // Not destroyed at once: a request still in flight may bring a rotated login pair.
            OzonClient.retire(web, account, startPrint)
        } else {
            WebProfiles.cookies(account.profile).flush()
            web.destroy()
        }
        super.onDestroy()
    }
}
