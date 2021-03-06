package com.breadwallet.legacy.wallet.wallets.ethereum;

import android.content.Context;
import android.util.Log;

import com.breadwallet.BuildConfig;
import com.breadwallet.legacy.presenter.entities.CurrencyEntity;
import com.breadwallet.legacy.wallet.configs.WalletSettingsConfiguration;
import com.breadwallet.legacy.wallet.configs.WalletUiConfiguration;
import com.breadwallet.legacy.wallet.wallets.CryptoAddress;
import com.breadwallet.legacy.wallet.wallets.CryptoTransaction;
import com.breadwallet.legacy.wallet.wallets.WalletManagerHelper;
import com.breadwallet.legacy.wallet.wallets.bitcoin.WalletBitcoinManager;
import com.breadwallet.model.TokenItem;
import com.breadwallet.repository.RatesRepository;
import com.breadwallet.tools.manager.BRReportsManager;
import com.breadwallet.tools.manager.BRSharedPrefs;
import com.breadwallet.tools.util.BRConstants;
import com.breadwallet.tools.util.TokenUtil;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * BreadWallet
 * <p/>
 * Created by Mihail Gutan on <mihail@breadwallet.com> 4/13/18.
 * Copyright (c) 2018 breadwallet LLC
 * <p/>
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * <p/>
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * <p/>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
public class WalletTokenManager extends BaseEthereumWalletManager {

    private static final String TAG = WalletTokenManager.class.getSimpleName();

    private WalletEthManager mWalletEthManager;
    private static Map<String, String> mTokenIsos = new HashMap<>();
    private static Map<String, WalletTokenManager> mTokenWallets = new HashMap<>();
    private WalletUiConfiguration mUiConfig;

    private static final String DEFAULT_COLOR_LEFT = "#ff5193"; // tokenWallet.getToken().getColorLeft()
    private static final String DEFAULT_COLOR_RIGHT = "#f9a43a"; // tokenWallet.getToken().getColorRight();
    public static final String BRD_CONTRACT_ADDRESS = BuildConfig.BITCOIN_TESTNET ? "0x7108ca7c4718efa810457f228305c9c71390931a" : "0x558ec3152e2eb2174905cd19aea4e34a23de9ad6";
    public static final String BRD_CURRENCY_CODE = "BRD";
    public static final String TUSD_CONTRACT_ADDRESS = BuildConfig.BITCOIN_TESTNET ? "0x7108ca7c4718efa810457f228305c9c71390931a" : "0x8Dd5fbCE2F6a956c3022bA3663759011Dd51E73E";
    public static final String TUSD_CURRENCY_CODE = "TUSD";

    private WalletTokenManager(WalletEthManager walletEthManager) {
    }

    private synchronized static WalletTokenManager getTokenWallet(WalletEthManager walletEthManager) {
        return null;
    }

    //for testing only
    public static WalletTokenManager getBrdWallet(WalletEthManager walletEthManager) {
        return new WalletTokenManager(walletEthManager);
    }

    public synchronized static WalletTokenManager getTokenWalletByIso(Context context, String iso) {

        WalletEthManager walletEthManager = WalletEthManager.getInstance(context.getApplicationContext());

        long start = System.currentTimeMillis();
        if (mTokenIsos.size() <= 0) mapTokenIsos(context);

        String address = mTokenIsos.get(iso.toLowerCase());
        address = address == null ? null : address.toLowerCase();
        if (address != null) {
            if (mTokenWallets.containsKey(address)) {
                return mTokenWallets.get(address);
            }
            BRReportsManager.reportBug(new NullPointerException("Failed to getTokenWalletByIso: " + iso + ":" + address));
        }
        return null;
    }


    public static synchronized void mapTokenIsos(Context context) {
        for (TokenItem tokenItem : TokenUtil.getTokenItems(context)) {
            if (!mTokenIsos.containsKey(tokenItem.getSymbol().toLowerCase())) {
                mTokenIsos.put(tokenItem.getSymbol().toLowerCase(), tokenItem.getAddress().toLowerCase());
            }
        }
    }

    @Override
    public byte[] signAndPublishTransaction(CryptoTransaction tx, byte[] seed) {
        String hash = tx.getHash();
        return hash == null ? new byte[0] : hash.getBytes();
    }

    @Override
    public void watchTransactionForHash(CryptoTransaction tx, OnHashUpdated listener) {
        mWalletEthManager.watchTransactionForHash(tx, listener);
    }

    @Override
    public long getRelayCount(byte[] txHash) {
        return 3; // ready to go
    }

    @Override
    public double getSyncProgress(long startHeight) {
        return mWalletEthManager.getSyncProgress(startHeight);
    }

    @Override
    public double getConnectStatus() {
        return mWalletEthManager.getConnectStatus();
    }

    @Override
    public void connect(Context context) {
        //no need for Tokens
    }

    @Override
    public void disconnect(Context context) {
        //no need for Tokens
    }

    @Override
    public boolean useFixedNode(String node, int port) {
        //no need for tokens
        return false;
    }

    @Override
    public void rescan(Context context) {
        //no need for tokens
    }

    @Override
    public CryptoTransaction[] getTxs(Context context) {
        return new CryptoTransaction[0];
    }

    @Override
    public BigDecimal getTxFee(CryptoTransaction tx) {
        return BigDecimal.ZERO;
    }

    @Override
    public BigDecimal getEstimatedFee(BigDecimal amount, String address) {
        return BigDecimal.ZERO;
    }

    @Override
    public BigDecimal getFeeForTransactionSize(BigDecimal size) {
        return null;
    }

    @Override
    public String getTxAddress(CryptoTransaction tx) {
        return mWalletEthManager.getTxAddress(tx);
    }

    @Override
    public BigDecimal getMaxOutputAmount(Context context) {
        return mWalletEthManager.getMaxOutputAmount(context);
    }

    @Override
    public BigDecimal getMinOutputAmount(Context context) {
        return BigDecimal.ZERO;
    }

    @Override
    public BigDecimal getTransactionAmount(CryptoTransaction tx) {
        return mWalletEthManager.getTransactionAmount(tx);
    }

    @Override
    public BigDecimal getMinOutputAmountPossible() {
        return mWalletEthManager.getMinOutputAmountPossible();
    }

    @Override
    public void updateFee(Context context) {
        //no need
    }

    @Override
    public boolean containsAddress(String address) {
        return mWalletEthManager.containsAddress(address);
    }

    @Override
    public boolean addressIsUsed(String address) {
        return mWalletEthManager.addressIsUsed(address);
    }

    @Override
    public boolean generateWallet(Context context) {
        return false;
    }

    @Override
    public String getSymbol(Context context) {
        return "";
    }

    @Override
    public String getCurrencyCode() {
        return "";
    }

    @Override
    public String getScheme() {
        return null;
    }

    @Override
    public String getName() {
        return "";
    }

    @Override
    public String getDenominator() {
        return "";
    }

    @Override
    public CryptoAddress getReceiveAddress(Context context) {
        return mWalletEthManager.getReceiveAddress(context);
    }

    @Override
    public CryptoTransaction createTransaction(BigDecimal amount, String address) {
        return null;
    }

    @Override
    public String decorateAddress(String addr) {
        return addr;
    }

    @Override
    public String undecorateAddress(String addr) {
        return addr;
    }

    @Override
    public int getMaxDecimalPlaces(Context context) {
        return WalletManagerHelper.MAX_DECIMAL_PLACES;
    }

    @Override
    public BigDecimal getBalance() {
        return BigDecimal.ZERO;
    }

    @Override
    public BigDecimal getTotalSent(Context context) {
        return mWalletEthManager.getTotalSent(context);
    }

    @Override
    public void wipeData(Context context) {
        //Not needed for Tokens
    }

    @Override
    public void syncStarted() {
        //Not needed for Tokens
    }

    @Override
    public void syncStopped(String error) {
        //Not needed for Tokens
    }

    @Override
    public boolean networkIsReachable() {
        return mWalletEthManager.networkIsReachable();
    }

    @Override
    public BigDecimal getMaxAmount(Context context) {
        return mWalletEthManager.getMaxAmount(context);
    }

    @Override
    public WalletUiConfiguration getUiConfiguration() {
        return mUiConfig;
    }

    @Override
    public WalletSettingsConfiguration getSettingsConfiguration() {
        //no settings for tokens, so empty
        return new WalletSettingsConfiguration();
    }

    @Override
    public BigDecimal getFiatExchangeRate(Context context) {
        BigDecimal fiatData = RatesRepository.getInstance(context)
                .getFiatForCrypto(BigDecimal.ONE, getCurrencyCode(), BRSharedPrefs.getPreferredFiatIso(context));
        if (fiatData == null) {
            return BigDecimal.ZERO;
        }
        return fiatData; //fiat, e.g. dollars
    }

    @Override
    public BigDecimal getFiatBalance(Context context) {
        if (context == null) {
            return null;
        }
        return getFiatForSmallestCrypto(context, getBalance(), null);
    }

    @Override
    public BigDecimal getFiatForSmallestCrypto(Context context, BigDecimal
            amount, CurrencyEntity ent) {
        return null;
    }

    @Override
    public BigDecimal getCryptoForFiat(Context context, BigDecimal fiatAmount) {
        if (fiatAmount == null || fiatAmount.compareTo(BigDecimal.ZERO) == 0) {
            return fiatAmount;
        }
        String iso = BRSharedPrefs.getPreferredFiatIso(context);
        return getTokensForFiat(context, fiatAmount, iso);
    }

    @Override
    public BigDecimal getCryptoForSmallestCrypto(Context context, BigDecimal amount) {
        return BigDecimal.ZERO;
    }

    @Override
    public BigDecimal getSmallestCryptoForCrypto(Context context, BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) == 0) {
            return amount;
        }
        return amount.multiply(new BigDecimal(getDenominator())).stripTrailingZeros();
    }

    @Override
    public BigDecimal getSmallestCryptoForFiat(Context context, BigDecimal amount) {
        BigDecimal convertedCryptoAmount = getCryptoForFiat(context, amount);
        //Round the amount up for situations when the decimals of a token is smaller than the precision we're using.
        if (convertedCryptoAmount != null) {
            convertedCryptoAmount = convertedCryptoAmount.setScale(getMaxDecimalPlaces(context), BRConstants.ROUNDING_MODE);
        }
        return getSmallestCryptoForCrypto(context, convertedCryptoAmount);
    }

    //pass in a fiat amount and return the specified amount in tokens
    //Token rates are in BTC (thus this math)
    private BigDecimal getTokensForFiat(Context context, BigDecimal fiatAmount, String
            code) {
        return null;
    }

    @Override
    public Object getWallet() {
        return null;
    }

    @Override
    protected WalletEthManager getEthereumWallet() {
        return mWalletEthManager;
    }
}
