package com.ltonetwork.network

import org.scalamock.scalatest.MockFactory
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.OneInstancePerTest
import scorex.crypto.signatures.Curve25519._

class CheckpointSpecification extends AnyFreeSpec with Matchers with MockFactory with OneInstancePerTest {

  private val maxRollback = 10

  "history points" in {
    val h = 100000

    Checkpoint.historyPoints(h, maxRollback, 3) shouldBe Seq(h - 10, h - 20, h - 40)

    Checkpoint.historyPoints(2, maxRollback, 3) shouldBe Seq()

    Checkpoint.historyPoints(1000, maxRollback, 3) shouldBe Seq(990, 980, 960)

    Checkpoint.historyPoints(h, maxRollback, 2) shouldBe Seq(h - 10, h - 20)

  }

  "serialization" in {
    def sig(b: Byte) = Array.fill[Byte](SignatureLength)(b)

    val c = Checkpoint(Seq(BlockCheckpoint(1, sig(1)), BlockCheckpoint(2, sig(2))), sig(3))

    val c2 = CheckpointSpec.deserializeData(CheckpointSpec.serializeData(c)).get

    c2.items should have size 2
    (c2.signature sameElements c.signature) shouldBe true
  }
}
