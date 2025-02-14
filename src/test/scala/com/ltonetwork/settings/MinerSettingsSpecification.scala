package com.ltonetwork.settings

import com.typesafe.config.ConfigFactory
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ArbitraryTypeReader._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration._

class MinerSettingsSpecification extends AnyFlatSpec with Matchers {
  "MinerSettings" should "read values" in {
    val config = ConfigFactory.parseString("""
        |lto {
        |  miner {
        |    enable: yes
        |    quorum: 1
        |    interval-after-last-block-then-generation-is-allowed: 1d
        |    micro-block-interval: 5s
        |    minimal-block-generation-offset: 500ms
        |    max-transactions-in-key-block: 300
        |    max-transactions-in-micro-block: 400
        |    min-micro-block-age: 3s
        |  }
        |}
      """.stripMargin).resolve()

    val settings = config.as[MinerSettings]("lto.miner")

    settings.enable should be(true)
    settings.quorum should be(1)
    settings.microBlockInterval should be(5.seconds)
    settings.minimalBlockGenerationOffset should be(500.millis)
    settings.maxTransactionsInKeyBlock should be(300)
    settings.maxTransactionsInMicroBlock should be(400)
    settings.minMicroBlockAge should be(3.seconds)
  }
}
