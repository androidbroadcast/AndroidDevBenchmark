package org.thoughtcrime.securesms.util

import com.google.android.gms.wallet.WalletConstants
import org.signal.donations.GooglePayApi
import org.signal.donations.StripeApi
import org.thoughtcrime.securesms.BuildConfig

object Environment {
  const val IS_STAGING: Boolean = BuildConfig.BUILD_ENVIRONMENT_TYPE == "Staging"

  object Donations {
    val GOOGLE_PAY_CONFIGURATION = GooglePayApi.Configuration(
      walletEnvironment = if (IS_STAGING) WalletConstants.ENVIRONMENT_TEST else WalletConstants.ENVIRONMENT_PRODUCTION
    )

    val STRIPE_CONFIGURATION = StripeApi.Configuration(
      publishableKey = BuildConfig.STRIPE_PUBLISHABLE_KEY
    )
  }
}
