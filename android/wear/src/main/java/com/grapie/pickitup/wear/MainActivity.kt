package com.grapie.pickitup.wear

import android.os.Bundle
import android.text.format.DateFormat
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Date
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

private val OzonBlue = Color(0xFF4D8DFF)

/**
 * The pickup code on the wrist: one page per account (swipe), the barcode on white in the middle
 * band, at full brightness while open. Codes come from the phone app over the Data Layer and are
 * kept, so they show without the phone too.
 */
class MainActivity : ComponentActivity(), DataClient.OnDataChangedListener {
    private val scope = MainScope()
    private val updating = MutableStateFlow(false)
    private var updatingJob: Job? = null
    private val demo = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Codes.init(this)
        // Scanners read a bright screen better; the watch dims back when the app closes.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = 1f }
        setContent {
            MaterialTheme {
                val accounts by Codes.accounts.collectAsState()
                val busy by updating.collectAsState()
                val online by Codes.phoneConnected.collectAsState()
                val shown = if (demo.value) Codes.demo(System.currentTimeMillis()) else accounts
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    if (shown.isNullOrEmpty()) Empty { demo.value = true }
                    else Pages(shown, busy, online || demo.value) { index -> refresh(index) }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Wearable.getDataClient(this).addListener(this)
        scope.launch {
            Codes.load(this@MainActivity)
            // Like the Pebble watchapp on open: the phone re-fetches whatever is stale.
            Codes.request(this@MainActivity, Codes.REQUEST_CACHED, 0)
        }
    }

    override fun onPause() {
        Wearable.getDataClient(this).removeListener(this)
        super.onPause()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onDataChanged(events: DataEventBuffer) {
        events.forEach { e ->
            if (e.type == DataEvent.TYPE_CHANGED && e.dataItem.uri.path == Codes.PATH_CODES) {
                Codes.fromPhone(DataMapItem.fromDataItem(e.dataItem).dataMap)
                updating.value = false
            }
        }
    }

    private fun refresh(index: Int) {
        if (demo.value) return
        updatingJob?.cancel()
        updating.value = true
        updatingJob = scope.launch {
            if (!Codes.request(this@MainActivity, Codes.REQUEST_REFRESH, index)) {
                updating.value = false
                return@launch
            }
            delay(90_000)  // the phone answers by updating the data item; give up after this
            updating.value = false
        }
    }
}

@Composable
private fun Empty(onDemo: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.no_phone_data), style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.no_phone_data_hint), style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFB0B0B0), textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.demo), style = MaterialTheme.typography.labelMedium, color = OzonBlue,
            modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onDemo).padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun Pages(accounts: List<WearAccount>, busy: Boolean, online: Boolean, onRefresh: (Int) -> Unit) {
    val pager = rememberPagerState { accounts.size }
    Box(Modifier.fillMaxSize()) {
        HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
            AccountPage(accounts[page], busy, online) { onRefresh(page) }
        }
        if (accounts.size > 1) Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            repeat(accounts.size) { i ->
                Box(Modifier.size(6.dp).clip(CircleShape).background(if (i == pager.currentPage) Color.White else Color(0xFF555555)))
            }
        }
    }
}

@Composable
private fun AccountPage(a: WearAccount, busy: Boolean, online: Boolean, onRefresh: () -> Unit) {
    val round = LocalConfiguration.current.isScreenRound
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val h = maxHeight
        Text(
            a.label.replace("Ozon", "OZON"), style = MaterialTheme.typography.labelMedium, color = OzonBlue,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = h * 0.17f, start = 40.dp, end = 40.dp),
        )
        when {
            !a.loggedIn -> Message(stringResource(R.string.log_in))
            a.code.isEmpty() -> Message(stringResource(R.string.no_code))
            else -> {
                val qr by Codes.qr.collectAsState()
                if (qr) Qr(a.code)
                else {
                    Barcode(a.code, round)
                    Text(
                        a.visible, style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace,
                        color = Color.White, maxLines = 1,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = h * 0.64f),
                    )
                }
                // Tap on the code: Code 128 <-> QR (swipes still go to the pager).
                Box(
                    Modifier.align(Alignment.TopCenter).padding(top = h * 0.27f)
                        .fillMaxWidth(0.8f).height(h * 0.45f)
                        .clickable(interactionSource = null, indication = null) { Codes.toggleQr() },
                )
            }
        }
        val footer = when {
            busy -> stringResource(R.string.updating)
            !online && a.updated > 0 -> stringResource(R.string.phone_offline, updatedText(a.updated))
            a.updated > 0 -> updatedText(a.updated)
            else -> stringResource(R.string.refresh)
        }
        // Tapping the footer asks the phone for a fresh code of this account.
        Text(
            footer, style = MaterialTheme.typography.labelSmall, maxLines = 1,
            color = if (online || busy) Color(0xFFB0B0B0) else Color(0xFFFFB74D),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = h * 0.75f)
                .clip(RoundedCornerShape(50)).clickable(enabled = !busy, onClick = onRefresh)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize().padding(horizontal = 30.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
    }
}

@Composable
private fun updatedText(t: Long): String {
    val time = DateFormat.getTimeFormat(androidx.compose.ui.platform.LocalContext.current).format(Date(t))
    fun day(ms: Long) = Calendar.getInstance().apply { timeInMillis = ms }.let { it.get(Calendar.YEAR) * 400 + it.get(Calendar.DAY_OF_YEAR) }
    val now = System.currentTimeMillis()
    return when (day(t)) {
        day(now) -> stringResource(R.string.updated_today, time)
        day(now - 86_400_000L) -> stringResource(R.string.updated_yesterday, time)
        else -> DateFormat.format("d MMM", t).toString()
    }
}

/**
 * The same code as a QR (level M) in a centred square between the header and the footer, whole
 * pixels per module, a 2-module white margin.
 */
@Composable
private fun Qr(code: String) {
    val matrix = remember(code) {
        runCatching { com.google.zxing.qrcode.encoder.Encoder.encode(code, com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M).matrix }.getOrNull()
    } ?: return
    val n = matrix.width
    Canvas(Modifier.fillMaxSize()) {
        val top = floor(size.height * 0.27f)
        val side = floor(size.height * 0.45f)
        val quiet = 2
        val scale = maxOf(1, floor(side / (n + 2 * quiet)).toInt())
        val box = ((n + 2 * quiet) * scale).toFloat()
        val left = floor(size.width / 2 - box / 2)
        val y0 = floor(top + (side - box) / 2)
        drawRoundRect(Color.White, Offset(left, y0), Size(box, box), cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f))
        for (y in 0 until n) for (x in 0 until n) {
            if (matrix.get(x, y).toInt() == 1) drawRect(
                Color.Black, Offset(left + (x + quiet) * scale, y0 + (y + quiet) * scale), Size(scale.toFloat(), scale.toFloat()),
            )
        }
    }
}

/**
 * Code 128 in the middle band, on white with quiet zones. Modules are a whole number of pixels
 * (fractional widths don't decode, see CLAUDE.md); on a round screen the code fits the chord at
 * the band's edge.
 */
@Composable
private fun Barcode(code: String, round: Boolean) {
    val widths = remember(code) { Code128.encode(code) } ?: return
    val modules = widths.sum()
    Canvas(Modifier.fillMaxSize()) {
        val top = floor(size.height * 0.36f)
        val bottom = floor(size.height * 0.61f)
        val cx = size.width / 2
        val cy = size.height / 2
        val r = minOf(size.width, size.height) / 2
        val dy = maxOf(abs(top - cy), abs(bottom - cy))
        val chord = if (round) 2 * sqrt(r * r - dy * dy) else size.width
        val usable = chord - 12  // a little air to the edge of the glass
        val quiet = 10  // modules each side; scanners want ~10
        val scale = maxOf(1, floor(usable / (modules + 2 * quiet)).toInt())
        val boxW = (modules + 2 * quiet) * scale
        val left = floor(cx - boxW / 2f)
        drawRoundRect(
            Color.White, Offset(left, top), Size(boxW.toFloat(), bottom - top),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f),
        )
        var x = left + quiet * scale
        val pad = (bottom - top) * 0.1f
        widths.forEachIndexed { i, w ->
            val px = (w * scale).toFloat()
            if (i % 2 == 0) drawRect(Color.Black, Offset(x, top + pad), Size(px, bottom - top - 2 * pad))
            x += px
        }
    }
}
