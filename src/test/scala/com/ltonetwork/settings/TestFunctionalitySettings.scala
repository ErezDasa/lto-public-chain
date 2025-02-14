package com.ltonetwork.settings

import com.ltonetwork.features.BlockchainFeatures

object TestFunctionalitySettings {
  val Enabled = FunctionalitySettings(
    featureCheckBlocksPeriod = 10000,
    blocksForFeatureActivation = 9000,
    preActivatedFeatures = Map(
      BlockchainFeatures.SmartAccounts.id          -> 0,
      BlockchainFeatures.AssociationTransaction.id -> 0,
      BlockchainFeatures.SponsorshipTransaction.id -> 0,
      BlockchainFeatures.Cobalt.id                 -> 0,
      BlockchainFeatures.CobaltAlloy.id            -> 0
    ),
    doubleFeaturesPeriodsAfterHeight = Int.MaxValue
  )
  val Disabled = Enabled.copy(preActivatedFeatures = Map.empty)

  val Stub: FunctionalitySettings = Enabled.copy(featureCheckBlocksPeriod = 100, blocksForFeatureActivation = 90)

  val EmptyFeaturesSettings: FeaturesSettings =
    FeaturesSettings(autoShutdownOnUnsupportedFeature = false, List.empty)
}
