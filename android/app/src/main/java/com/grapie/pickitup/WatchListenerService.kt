package com.grapie.pickitup

import io.rebble.pebblekit2.client.BasePebbleListenerService
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.ReceiveResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import java.util.UUID

/** Bound by the Pebble (Core Devices) app; the watchapp asks for the barcode when opened. */
class WatchListenerService : BasePebbleListenerService() {
    override suspend fun onMessageReceived(
        watchappUUID: UUID,
        data: PebbleDictionary,
        watch: WatchIdentifier,
    ): ReceiveResult {
        if (watchappUUID != WatchKeys.APP_UUID) return ReceiveResult.Ack
        val request = data[WatchKeys.REQUEST] ?: return ReceiveResult.Ack
        val kind = (request.value as? Number)?.toInt() ?: WatchKeys.REQUEST_CACHED
        val s = AppSettings.strings()
        BarcodeStore.log(if (kind == WatchKeys.REQUEST_REFRESH) s.logWatchAskedRefresh else s.logWatchAsked)
        BarcodeSync.onWatchRequest(this, kind)
        return ReceiveResult.Ack
    }
}
