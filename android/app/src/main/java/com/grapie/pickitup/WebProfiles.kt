package com.grapie.pickitup

import android.webkit.CookieManager
import android.webkit.ServiceWorkerController
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * WebView profiles: each [Account] has its own cookie jar and site storage, so several logins at
 * one marketplace live side by side. The account from before multi-account uses the default
 * profile, which is the plain CookieManager / WebStorage everything used until then.
 * Every touch of these loads Chromium: only call them where a WebView runs anyway.
 */
object WebProfiles {
    const val DEFAULT = Profile.DEFAULT_PROFILE_NAME

    /** Named profiles need a WebView with MULTI_PROFILE; without it only the default one exists. */
    fun supported() = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    private fun profile(name: String): Profile? =
        if (name != DEFAULT && supported()) ProfileStore.getInstance().getOrCreateProfile(name) else null

    fun cookies(name: String): CookieManager = profile(name)?.cookieManager ?: CookieManager.getInstance()

    fun storage(name: String): WebStorage = profile(name)?.webStorage ?: WebStorage.getInstance()

    fun serviceWorkers(name: String): ServiceWorkerController =
        profile(name)?.serviceWorkerController ?: ServiceWorkerController.getInstance()

    /** Right after creating a WebView, before it loads anything. */
    fun apply(web: WebView, name: String) {
        if (name != DEFAULT && supported()) WebViewCompat.setProfile(web, name)
    }
}
