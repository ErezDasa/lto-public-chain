package com.ltonetwork.transaction

import com.ltonetwork.history._
import com.ltonetwork.state.diffs._
import com.ltonetwork.state.{Diff, EitherExt2, NgState}
import com.ltonetwork.transaction.genesis.GenesisTransaction
import com.ltonetwork.{NoShrink, TransactionGen}
import org.scalacheck.Gen
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import org.scalatest.matchers.should.Matchers
import org.scalatest.propspec.AnyPropSpec
import com.ltonetwork.transaction.transfer._

class NgStateTest extends AnyPropSpec with ScalaCheckDrivenPropertyChecks with Matchers with TransactionGen with NoShrink {

  def preconditionsAndPayments(amt: Int): Gen[(GenesisTransaction, Seq[TransferTransaction])] =
    for {
      master    <- accountGen
      recipient <- accountGen
      ts        <- positiveIntGen
      genesis: GenesisTransaction = GenesisTransaction.create(master, ENOUGH_AMT, ts).explicitGet()
      payments: Seq[TransferTransaction] <- Gen.listOfN(amt, ltoTransferGeneratorP(master, recipient))
    } yield (genesis, payments)

  property("can forge correctly signed blocks") {
    forAll(preconditionsAndPayments(10)) {
      case (genesis, payments) =>
        val (block, microBlocks) = chainBaseAndMicro(randomSig, genesis, payments.map(t => Seq(t)))

        val ng = new NgState(block, Diff.empty, 0L, Set.empty)
        microBlocks.foreach(m => ng.append(m, Diff.empty, 0L, 0L))

        ng.totalDiffOf(microBlocks.last.totalResBlockSig)
        microBlocks.foreach { m =>
          ng.totalDiffOf(m.totalResBlockSig).get match {
            case (forged, _, _, _) => forged.signaturesValid() shouldBe 'right
            case _                 => ???
          }
        }
        Seq(microBlocks(4)).map(x => ng.totalDiffOf(x.totalResBlockSig))
    }
  }

  property("can resolve best liquid block") {
    forAll(preconditionsAndPayments(5)) {
      case (genesis, payments) =>
        val (block, microBlocks) = chainBaseAndMicro(randomSig, genesis, payments.map(t => Seq(t)))

        val ng = new NgState(block, Diff.empty, 0L, Set.empty)
        microBlocks.foreach(m => ng.append(m, Diff.empty, 0L, 0L))

        ng.bestLiquidBlock.uniqueId shouldBe microBlocks.last.totalResBlockSig

        new NgState(block, Diff.empty, 0L, Set.empty).bestLiquidBlock.uniqueId shouldBe block.uniqueId
    }
  }

  property("can resolve best last block") {
    forAll(preconditionsAndPayments(5)) {
      case (genesis, payments) =>
        val (block, microBlocks) = chainBaseAndMicro(randomSig, genesis, payments.map(t => Seq(t)))

        val ng = new NgState(block, Diff.empty, 0L, Set.empty)

        microBlocks.foldLeft(1000) {
          case (thisTime, m) =>
            ng.append(m, Diff.empty, 0L, thisTime)
            thisTime + 50
        }

        ng.bestLastBlockInfo(0).blockId shouldBe block.uniqueId
        ng.bestLastBlockInfo(1001).blockId shouldBe microBlocks.head.totalResBlockSig
        ng.bestLastBlockInfo(1051).blockId shouldBe microBlocks.tail.head.totalResBlockSig
        ng.bestLastBlockInfo(2000).blockId shouldBe microBlocks.last.totalResBlockSig

        new NgState(block, Diff.empty, 0L, Set.empty).bestLiquidBlock.uniqueId shouldBe block.uniqueId
    }
  }

  property("calculates carry fee correctly") {
    forAll(preconditionsAndPayments(5)) {
      case (genesis, payments) =>
        val (block, microBlocks) = chainBaseAndMicro(randomSig, genesis, payments.map(t => Seq(t)))

        val ng = new NgState(block, Diff.empty, 0L, Set.empty)
        microBlocks.foreach(m => ng.append(m, Diff.empty, 1L, 0L))

        ng.totalDiffOf(block.uniqueId).map(_._3) shouldBe Some(0L)
        microBlocks.zipWithIndex.foreach {
          case (m, i) =>
            val u = ng.totalDiffOf(m.totalResBlockSig).map(_._3)
            u shouldBe Some(i + 1)
        }
        ng.carryFee shouldBe microBlocks.size
    }
  }
}
