/**
 * BreadWallet
 *
 * Created by Drew Carlson <drew.carlson@breadwallet.com> on 8/13/19.
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
package com.breadwallet.ui.recovery

import android.content.Context
import android.security.keystore.UserNotAuthenticatedException
import com.breadwallet.app.BreadApp
import com.breadwallet.breadbox.BreadBox
import com.breadwallet.crypto.Account
import com.breadwallet.crypto.Key
import com.breadwallet.logger.logError
import com.breadwallet.logger.logInfo
import com.breadwallet.tools.manager.BRSharedPrefs
import com.breadwallet.tools.security.BRKeyStore
import com.breadwallet.tools.security.setPinCode
import com.breadwallet.tools.util.BRConstants
import com.breadwallet.tools.util.Bip39Reader
import com.breadwallet.ui.recovery.RecoveryKey.E
import com.breadwallet.ui.recovery.RecoveryKey.F
import com.platform.entities.WalletInfoData
import com.platform.interfaces.AccountMetaDataProvider
import com.spotify.mobius.Connection
import com.spotify.mobius.functions.Consumer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import java.text.Normalizer
import java.util.Date
import java.util.Locale

private const val POLL_ATTEMPTS_MAX = 15
private const val POLL_TIMEOUT_MS = 1000L

@UseExperimental(ExperimentalCoroutinesApi::class, FlowPreview::class)
class RecoveryKeyHandler(
    private val output: Consumer<E>,
    private val breadBox: BreadBox,
    private val metaDataProvider: AccountMetaDataProvider,
    val goToUnlink: () -> Unit,
    val goToErrorDialog: () -> Unit,
    val errorShake: () -> Unit
) : Connection<F>, CoroutineScope {

    override val coroutineContext = SupervisorJob() + Dispatchers.Default

    override fun accept(effect: F) {
        when (effect) {
            F.ErrorShake -> launch(Dispatchers.Main) { errorShake() }
            F.GoToPhraseError -> goToErrorDialog()
            is F.ResetPin -> resetPin(effect)
            is F.Unlink -> unlink(effect)
            is F.RecoverWallet -> recoverWallet(effect)
            is F.ValidateWord -> validateWord(effect)
            is F.ValidatePhrase -> validatePhrase(effect)
        }
    }

    override fun dispose() {
        coroutineContext.cancel()
    }

    private fun validateWord(effect: F.ValidateWord) {
        val isValid = Bip39Reader.isWordValid(BreadApp.getBreadContext(), effect.word)
        output.accept(E.OnWordValidated(effect.index, !isValid))
    }

    private fun validatePhrase(effect: F.ValidatePhrase) {
        val errors = MutableList(RecoveryKey.M.RECOVERY_KEY_WORDS_COUNT) { false }
        effect.phrase.forEachIndexed { index, word ->
            errors[index] = !Bip39Reader.isWordValid(BreadApp.getBreadContext(), word)
        }
        output.accept(E.OnPhraseValidated(errors))
    }

    private fun resetPin(effect: F.ResetPin) {
        val context = BreadApp.getBreadContext()
        val phrase = normalizePhrase(effect.phrase)

        val storedPhrase = try {
            BRKeyStore.getPhrase(context, BRConstants.SHOW_PHRASE_REQUEST_CODE)
        } catch (e: UserNotAuthenticatedException) {
            logInfo("User not authenticated, attempting authentication before restoring wallet.", e)
            return
        } catch (e: Exception) {
            logError("Error storing phrase", e)
            // TODO: KeyStore read error
            output.accept(E.OnPhraseInvalid)
            return
        }

        output.accept(
            when {
                phrase.toByteArray().contentEquals(storedPhrase) -> {
                    setPinCode(context, "")
                    E.OnPinCleared
                }
                else -> E.OnPhraseInvalid
            }
        )
    }

    private fun unlink(effect: F.Unlink) {
        val context = BreadApp.getBreadContext()
        val phrase = normalizePhrase(effect.phrase)

        val storedPhrase = try {
            BRKeyStore.getPhrase(context, BRConstants.SHOW_PHRASE_REQUEST_CODE)
        } catch (e: UserNotAuthenticatedException) {
            logInfo("User not authenticated, attempting authentication before restoring wallet.", e)
            return
        } catch (e: Exception) {
            logError("Error storing phrase", e)
            // TODO: KeyStore read error
            output.accept(E.OnPhraseInvalid)
            return
        }

        if (phrase.toByteArray().contentEquals(storedPhrase)) {
            goToUnlink()
        } else {
            output.accept(E.OnPhraseInvalid)
        }
    }

    private fun recoverWallet(effect: F.RecoverWallet) {
        val context = BreadApp.getBreadContext()

        val phraseBytes = normalizePhrase(effect.phrase).toByteArray()

        val words = findWordsForPhrase(phraseBytes)
        if (words == null) {
            logInfo("Phrase validation failed.")
            output.accept(E.OnPhraseInvalid)
            return
        }

        val storePhraseSuccess = try {
            BRKeyStore.putPhrase(
                phraseBytes,
                context,
                BRConstants.PUT_PHRASE_RECOVERY_WALLET_REQUEST_CODE
            )
        } catch (e: UserNotAuthenticatedException) {
            logInfo("User not authenticated, attempting auth before restoring wallet.", e)
            return
        }

        if (!storePhraseSuccess) {
            logError("Failed to store phrase.")
            output.accept(E.OnPhraseInvalid) // TODO: Define phrase write error
            return
        }

        BRSharedPrefs.putPhraseWroteDown(check = true)

        val apiKey = Key.createForBIP32ApiAuth(phraseBytes, words).apply {
            if (!isPresent) {
                logError("Failed to create api auth key from phrase.")
                output.accept(E.OnPhraseInvalid) // TODO: Define unexpected error dialog
                return
            }
        }.get()

        // Must be set up before wallet creation date can be retrieved
        setupApiKey(apiKey, context)

        BreadApp.applicationScope.launch {
            metaDataProvider.recoverAll(true).first()
        }

        launch(Dispatchers.Main) {
            val creationDate = getWalletCreationDate()

            val uids = BRSharedPrefs.getDeviceId()
            val account = Account.createFromPhrase(phraseBytes, creationDate, uids)

            try {
                setupWallet(account, creationDate, context)
            } catch (e: Exception) {
                logError("Error setting up wallet", e)
                // TODO: Define generic initialization error with retry
                output.accept(E.OnPhraseInvalid)
                return@launch
            }

            try {
                breadBox.open(account)
            } catch (e: IllegalStateException) {
                logError("Error opening BreadBox", e)
                // TODO: Define initialization error
                output.accept(E.OnPhraseInvalid)
                return@launch
            }

            (context.applicationContext as BreadApp).startWithInitializedWallet(breadBox, false)

            output.accept(E.OnRecoveryComplete)
        }
    }

    /** Stores [apiKey]. */
    private fun setupApiKey(apiKey: Key, context: Context) {
        BRKeyStore.putAuthKey(apiKey.encodeAsPrivate(), context)
    }

    /** Stores [account] and [creationDate] in [BRKeyStore]. */
    private fun setupWallet(
        account: Account,
        creationDate: Date,
        context: Context
    ) {
        BRKeyStore.putAccount(account, context)
        BRKeyStore.putWalletCreationTime(creationDate.time, context)
    }

    /**
     * Returns the list of words for the language resulting in
     * a successful [Account.validatePhrase] call or null if
     * the phrase is invalid.
     */
    private fun findWordsForPhrase(phraseBytes: ByteArray): List<String>? {
        val context = BreadApp.getBreadContext()
        val allLocales = Locale.getAvailableLocales().asSequence()

        return (sequenceOf(Locale.getDefault()) + allLocales)
            .map(Locale::getLanguage)
            .map { it to Bip39Reader.getBip39Words(context, it) }
            .firstOrNull { (language, words) ->
                Account.validatePhrase(phraseBytes, words)
                    .also { matched ->
                        if (matched) {
                            BRSharedPrefs.recoveryKeyLanguage = language
                            Key.setDefaultWordList(words)
                        }
                    }
            }?.second
    }

    /** Returns the [Date] of [WalletInfoData.creationDate]. */
    private suspend fun getWalletCreationDate(): Date =
        metaDataProvider.walletInfo()
            .onStart {
                // Poll for wallet-info metadata
                // This is a work-around to avoid blocking until recoverAll(migrate)
                // recovers *all* metadata
                for (i in 1..POLL_ATTEMPTS_MAX) {
                    metaDataProvider.getWalletInfoUnsafe()
                        ?.let { emit(it) }
                    delay(POLL_TIMEOUT_MS)
                }
            }
            .first()
            .creationDate
            .run(::Date)

    private fun normalizePhrase(phrase: List<String>) =
        Normalizer.normalize(
            phrase.joinToString(" ")
                .replace("　", " ")
                .replace("\n", " ")
                .trim()
                .replace(" +".toRegex(), " "), Normalizer.Form.NFKD
        )
}
