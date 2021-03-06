/**
 * BreadWallet
 *
 * Created by Drew Carlson <drew.carlson@breadwallet.com> on 8/12/19.
 * Copyright (c) 2019 breadwallet LLC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.breadwallet.ui

import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import com.bluelinelabs.conductor.ChangeHandlerFrameLayout
import com.bluelinelabs.conductor.Conductor
import com.bluelinelabs.conductor.Router
import com.bluelinelabs.conductor.RouterTransaction
import com.bluelinelabs.conductor.changehandler.FadeChangeHandler
import com.breadwallet.BuildConfig
import com.breadwallet.app.BreadApp
import com.breadwallet.legacy.presenter.activities.util.BRActivity
import com.breadwallet.logger.logDebug
import com.breadwallet.logger.logError
import com.breadwallet.tools.security.BRKeyStore
import com.breadwallet.tools.security.KeyStore
import com.breadwallet.tools.util.EventUtils
import com.breadwallet.tools.util.Utils
import com.breadwallet.ui.login.LoginController
import com.breadwallet.ui.migrate.MigrateController
import com.breadwallet.ui.navigation.NavigationEffect
import com.breadwallet.ui.navigation.OnCompleteAction
import com.breadwallet.ui.navigation.RouterNavigationEffectHandler
import com.breadwallet.ui.onboarding.IntroController
import com.breadwallet.ui.pin.InputPinController
import com.breadwallet.ui.recovery.RecoveryKey
import com.breadwallet.ui.recovery.RecoveryKeyController
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val LOCK_TIMEOUT = 180_000L // 3 minutes in milliseconds

// String extra containing a recovery phrase to bootstrap the recovery process. (debug only)
private const val EXTRA_RECOVER_PHRASE = "RECOVER_PHRASE"

/**
 * The main user entrypoint into the app.
 *
 * This activity serves as a Conductor router host and translates
 * platform events into Mobius events.
 */
@Suppress("TooManyFunctions")
class MainActivity : BRActivity() {

    companion object {
        const val EXTRA_DATA = "com.breadwallet.ui.MainActivity.EXTRA_DATA"
        const val EXTRA_PUSH_NOTIFICATION_CAMPAIGN_ID =
            "com.breadwallet.ui.MainActivity.EXTRA_PUSH_CAMPAIGN_ID"
    }

    private lateinit var router: Router
    // NOTE: Used only to centralize deep link navigation handling.
    private var routerNavHandler: RouterNavigationEffectHandler? = null

    private var walletLockJob: Job? = null

    private var launchedWithInvalidState = false
    private val isDeviceStateValid: Boolean
        get() = (application as BreadApp).isDeviceStateValid

    @Suppress("ComplexMethod", "LongMethod")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The view of this activity is nothing more than a Controller host with animation support
        setContentView(ChangeHandlerFrameLayout(this).also { view ->
            router = Conductor.attachRouter(this, view, savedInstanceState)
            routerNavHandler = RouterNavigationEffectHandler(router)
        })

        if (!isDeviceStateValid) {
            // In this case, isDeviceStateValid displays a dialog (activity)
            // for user resolution of the invalid state. We must check this
            // again in onResume to ensure we display the dialog if the state
            // is unchanged or recreate the activity.
            launchedWithInvalidState = true
            logError("Device state is invalid.")
            return
        }

        // Allow launching with a phrase to recover automatically
        if (BuildConfig.DEBUG && intent.hasExtra(EXTRA_RECOVER_PHRASE) && !BreadApp.hasWallet()) {
            val phrase = intent.getStringExtra(EXTRA_RECOVER_PHRASE)
            if (phrase.isNotBlank() && phrase.split(" ").size == RecoveryKey.M.RECOVERY_KEY_WORDS_COUNT) {
                val controller = RecoveryKeyController(RecoveryKey.Mode.RECOVER, phrase)
                router.setRoot(RouterTransaction.with(controller))
                return
            }
        }

        // The app is launched, no screen to be restored
        if (!router.hasRootController()) {
            val rootController = when {
                !BreadApp.hasWallet() && BreadApp.isMigrationRequired -> MigrateController()
                BreadApp.hasWallet() -> {
                    if (BRKeyStore.getPinCode(this).isNotBlank()) {
                        val intentUrl = processIntentData(intent)
                        LoginController(intentUrl)
                    } else {
                        InputPinController(OnCompleteAction.GO_HOME)
                    }
                }
                else -> IntroController()
            }
            router.setRoot(
                RouterTransaction.with(rootController)
                    .popChangeHandler(FadeChangeHandler())
                    .pushChangeHandler(FadeChangeHandler())
            )
        }

        if (BuildConfig.DEBUG) {
            Utils.printPhoneSpecs(this)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        walletLockJob?.cancel()
        walletLockJob = null
    }

    override fun onResume() {
        super.onResume()
        // If we come back to the activity after launching with
        // an invalid device state, check the state again.
        // If the state is valid, recreate the activity otherwise
        // the resolution dialog will display again.
        if (launchedWithInvalidState) {
            if (isDeviceStateValid) {
                logDebug("Device state is valid, recreating activity.")
                recreate()
            } else {
                logError("Device state is invalid.")
            }
            return
        }
    }

    override fun onStart() {
        super.onStart()
        walletLockJob?.cancel()
        walletLockJob = null
    }

    override fun onStop() {
        super.onStop()
        walletLockJob = GlobalScope.launch(Main) {
            delay(LOCK_TIMEOUT)
            lockApp()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        router.onActivityResult(requestCode, resultCode, data)
        KeyStore.onActivityResult(requestCode, resultCode)
    }

    override fun onBackPressed() {
        // Defer to controller back-press control before exiting.
        if (!router.handleBack()) {
            super.onBackPressed()
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        return checkOverlayAndDispatchTouchEvent(ev)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent ?: return

        val data = processIntentData(intent) ?: ""
        if (data.isNotBlank() && BreadApp.hasWallet()) {
            val hasRoot = router.hasRootController()
            val isTopLogin = router.backstack.lastOrNull()?.controller() is LoginController
            val isAuthenticated = !isTopLogin && hasRoot
            routerNavHandler?.goToDeepLink(NavigationEffect.GoToDeepLink(data, isAuthenticated))
        }
    }

    /** Process the new intent and return the url to browse if available */
    private fun processIntentData(intent: Intent): String? {
        if (intent.hasExtra(EXTRA_PUSH_NOTIFICATION_CAMPAIGN_ID)) {
            val campaignId = intent.getStringExtra(EXTRA_PUSH_NOTIFICATION_CAMPAIGN_ID)
            val attributes =
                mapOf<String, String>(EventUtils.EVENT_ATTRIBUTE_CAMPAIGN_ID to campaignId)
            EventUtils.pushEvent(EventUtils.EVENT_MIXPANEL_APP_OPEN, attributes)
            EventUtils.pushEvent(EventUtils.EVENT_PUSH_NOTIFICATION_OPEN)
        }

        val data = intent.getStringExtra(EXTRA_DATA)
        return if (data.isNullOrBlank()) {
            intent.dataString
        } else data
    }

    private fun lockApp() {
        val hasPin = BRKeyStore.getPinCode(this).isNotEmpty()
        val controller = when {
            hasPin -> LoginController(showHome = false)
            else -> InputPinController(
                onComplete = OnCompleteAction.GO_HOME,
                skipWriteDown = true
            )
        }

        val transaction = RouterTransaction.with(controller)
            .popChangeHandler(FadeChangeHandler())
            .pushChangeHandler(FadeChangeHandler())

        when (router.backstack.lastOrNull()?.controller()) {
            is LoginController -> router.replaceTopController(transaction)
            else -> router.pushController(transaction)
        }
    }
}
