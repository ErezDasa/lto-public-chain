package com.ltonetwork.history

import com.ltonetwork.TransactionGen
import com.ltonetwork.state.EitherExt2
import com.ltonetwork.state.diffs._
import org.scalacheck.Gen
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import org.scalatest.matchers.should.Matchers
import org.scalatest.propspec.AnyPropSpec
import com.ltonetwork.transaction.genesis.GenesisTransaction
import com.ltonetwork.transaction.transfer._

class BlockchainUpdaterBlockOnlyTest
    extends AnyPropSpec
    with ScalaCheckDrivenPropertyChecks
    with DomainScenarioDrivenPropertyCheck
    with Matchers
    with TransactionGen {

  def preconditionsAndPayments(paymentsAmt: Int): Gen[(GenesisTransaction, Seq[TransferTransaction])] =
    for {
      master    <- accountGen
      recipient <- accountGen
      ts        <- positiveIntGen
      genesis: GenesisTransaction = GenesisTransaction.create(master, ENOUGH_AMT, ts).explicitGet()
      payments <- Gen.listOfN(paymentsAmt, ltoTransferGeneratorP(ts, master, recipient))
    } yield (genesis, payments)

  property("can apply valid blocks") {

    scenario(preconditionsAndPayments(1)) {
      case (domain, (genesis, payments)) =>
        val blocks = chainBlocks(Seq(Seq(genesis), Seq(payments.head)))
        all(blocks.map(block => domain.blockchainUpdater.processBlock(block))) shouldBe 'right
    }
  }

  property("can apply, rollback and reprocess valid blocks") {

    scenario(preconditionsAndPayments(2)) {
      case (domain, (genesis, payments)) =>
        val blocks = chainBlocks(Seq(Seq(genesis), Seq(payments(0)), Seq(payments(1))))
        domain.blockchainUpdater.processBlock(blocks.head) shouldBe 'right
        domain.blockchainUpdater.height shouldBe 1
        domain.blockchainUpdater.processBlock(blocks(1)) shouldBe 'right
        domain.blockchainUpdater.height shouldBe 2
        domain.blockchainUpdater.removeAfter(blocks.head.uniqueId) shouldBe 'right
        domain.blockchainUpdater.height shouldBe 1
        domain.blockchainUpdater.processBlock(blocks(1)) shouldBe 'right
        domain.blockchainUpdater.processBlock(blocks(2)) shouldBe 'right
    }
  }

  property("can't apply block with invalid signature") {

    scenario(preconditionsAndPayments(1)) {
      case (domain, (genesis, payment)) =>
        val blocks = chainBlocks(Seq(Seq(genesis), payment))
        domain.blockchainUpdater.processBlock(blocks.head) shouldBe 'right
        domain.blockchainUpdater.processBlock(spoilSignature(blocks.last)) should produce("InvalidSignature")
    }
  }

  property("can't apply block with invalid signature after rollback") {

    scenario(preconditionsAndPayments(1)) {
      case (domain, (genesis, payment)) =>
        val blocks = chainBlocks(Seq(Seq(genesis), payment))
        domain.blockchainUpdater.processBlock(blocks.head) shouldBe 'right
        domain.blockchainUpdater.processBlock(blocks(1)) shouldBe 'right
        domain.blockchainUpdater.removeAfter(blocks.head.uniqueId) shouldBe 'right
        domain.blockchainUpdater.processBlock(spoilSignature(blocks(1))) should produce("InvalidSignature")
    }
  }

  property("can process 11 blocks and then rollback to genesis") {

    scenario(preconditionsAndPayments(10)) {
      case (domain, (genesis, payments)) =>
        val blocks = chainBlocks(Seq(genesis) +: payments.map(Seq(_)))
        blocks.foreach { b =>
          domain.blockchainUpdater.processBlock(b) shouldBe 'right
        }
        domain.blockchainUpdater.removeAfter(blocks.head.uniqueId) shouldBe 'right
    }
  }
}
