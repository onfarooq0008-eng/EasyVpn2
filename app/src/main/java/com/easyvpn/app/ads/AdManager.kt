package com.easyvpn.app.ads

import android.app.Activity
import android.content.Context
import android.widget.FrameLayout
import com.google.android.gms.ads.*
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback

/**
 * All ad unit IDs below are Google's official TEST IDs -- safe to ship in
 * debug builds, but you MUST swap in your real AdMob unit IDs before
 * publishing (never ship test ad IDs to production, and never click your
 * own live ads -- that gets accounts banned).
 *
 * Replace:
 *  - BANNER_AD_UNIT_ID
 *  - INTERSTITIAL_AD_UNIT_ID
 *  - and the APPLICATION_ID in AndroidManifest.xml
 * once you have your AdMob pub ID / ad units.
 */
object AdManager {

    private const val BANNER_AD_UNIT_ID = "ca-app-pub-3940256099942544/6300978111" // TEST
    private const val INTERSTITIAL_AD_UNIT_ID = "ca-app-pub-3940256099942544/1033173712" // TEST

    private var interstitialAd: InterstitialAd? = null
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        MobileAds.initialize(context) { initialized = true }
        preloadInterstitial(context)
    }

    /** Every screen shows a banner -- the whole app is free and ad-supported. */
    fun loadBanner(container: FrameLayout, activity: Activity) {
        container.removeAllViews()
        val adView = AdView(activity)
        adView.adUnitId = BANNER_AD_UNIT_ID
        adView.setAdSize(AdSize.BANNER)
        container.addView(adView)
        adView.loadAd(AdRequest.Builder().build())
    }

    fun preloadInterstitial(context: Context) {
        InterstitialAd.load(
            context, INTERSTITIAL_AD_UNIT_ID, AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitialAd = ad
                }
                override fun onAdFailedToLoad(error: LoadAdError) {
                    interstitialAd = null
                }
            }
        )
    }

    /** Show an interstitial right before connecting -- good UX + revenue spot for a free app. */
    fun maybeShowInterstitial(activity: Activity, onDismissed: () -> Unit) {
        val ad = interstitialAd
        if (ad == null) {
            onDismissed()
            return
        }
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                interstitialAd = null
                preloadInterstitial(activity)
                onDismissed()
            }
            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                interstitialAd = null
                onDismissed()
            }
        }
        ad.show(activity)
    }
}
