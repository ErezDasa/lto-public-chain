package com.ltonetwork.history

import com.ltonetwork._
import com.ltonetwork.state._
import com.ltonetwork.state.diffs.ENOUGH_AMT
import org.scalacheck.Gen
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import org.scalatest.matchers.should.Matchers
import org.scalatest.propspec.AnyPropSpec
import com.ltonetwork.account.PrivateKeyAccount
import com.ltonetwork.block.{Block, MicroBlock, SignerData}
import com.ltonetwork.consensus.nxt.NxtLikeConsensusBlockData
import com.ltonetwork.lagonaki.mocks.TestBlock
import com.ltonetwork.transaction.ValidationError.GenericError
import com.ltonetwork.transaction.genesis.GenesisTransaction
import com.ltonetwork.transaction.transfer._
import com.ltonetwork.transaction.Transaction

class BlockchainUpdaterLiquidBlockTest
    extends AnyPropSpec
    with ScalaCheckDrivenPropertyChecks
    with DomainScenarioDrivenPropertyCheck
    with Matchers
    with TransactionGen {

  private val preconditionsAndPayments: Gen[(Block, Block, Seq[MicroBlock])] = for {
    richAccount        <- accountGen
    totalTxNumber      <- Gen.chooseNum(Block.MaxTransactionsPerBlockVer3 + 1, Block.MaxTransactionsPerBlockVer3 + 100)
    txNumberInKeyBlock <- Gen.chooseNum(0, Block.MaxTransactionsPerBlockVer3)
    ts                 <- timestampGen
    allTxs             <- Gen.listOfN(totalTxNumber, validTransferGen(richAccount, ts))
  } yield {
    val (keyBlockTxs, microTxs) = allTxs.splitAt(txNumberInKeyBlock)
    val txNumberInMicros        = totalTxNumber - txNumberInKeyBlock

    val prevBlock = unsafeBlock(
      reference = randomSig,
      txs = Seq(GenesisTransaction.create(richAccount, ENOUGH_AMT, 0).explicitGet()),
      signer = TestBlock.defaultSigner,
      version = 3,
      timestamp = ts
    )

    val (keyBlock, microBlocks) = unsafeChainBaseAndMicro(
      totalRefTo = prevBlock.signerData.signature,
      base = keyBlockTxs,
      micros = microTxs.grouped(math.max(1, txNumberInMicros / 5)).toSeq,
      signer = TestBlock.defaultSigner,
      version = 3,
      timestamp = ts
    )

    (prevBlock, keyBlock, microBlocks)
  }

  // Ignored because the test hangs until the process is out of memory
  ignore("liquid block can't be overfilled") {
    withDomain(MicroblocksActivatedAt0LtoSettings) { d =>
      val (prevBlock, keyBlock, microBlocks) = preconditionsAndPayments.sample.get

      val blocksApplied = for {
        _ <- d.blockchainUpdater.processBlock(prevBlock)
        _ <- d.blockchainUpdater.processBlock(keyBlock)
      } yield ()

      val r = microBlocks.foldLeft(blocksApplied) {
        case (Right(_), curr) => d.blockchainUpdater.processMicroBlock(curr)
        case (x, _)           => x
      }

      withClue("All microblocks should not be processed") {
        r match {
          case Left(e: GenericError) => e.err should include("Limit of txs was reached")
          case x =>
            val txNumberByMicroBlock = microBlocks.map(_.transactionData.size)
            fail(
              s"Unexpected result: $x. keyblock txs: ${keyBlock.transactionCount}, " +
                s"microblock txs: ${txNumberByMicroBlock.mkString(", ")} (total: ${txNumberByMicroBlock.sum}), " +
                s"total txs: ${keyBlock.transactionCount + txNumberByMicroBlock.sum}")
        }
      }
    }
  }

  private def validTransferGen(from: PrivateKeyAccount, timestamp: Long): Gen[Transaction] =
    for {
      amount    <- smallFeeGen
      fee       <- smallFeeGen
      recipient <- accountGen
    } yield TransferTransaction.signed(1, timestamp, from, fee, recipient, amount, Array.empty).explicitGet()

  private def unsafeChainBaseAndMicro(totalRefTo: ByteStr,
                                      base: Seq[Transaction],
                                      micros: Seq[Seq[Transaction]],
                                      signer: PrivateKeyAccount,
                                      version: Byte,
                                      timestamp: Long): (Block, Seq[MicroBlock]) = {
    val block = unsafeBlock(totalRefTo, base, signer, version, timestamp)
    val microBlocks = micros
      .foldLeft((block, Seq.empty[MicroBlock])) {
        case ((lastTotal, allMicros), txs) =>
          val (newTotal, micro) = unsafeMicro(totalRefTo, lastTotal, txs, signer, version, timestamp)
          (newTotal, allMicros :+ micro)
      }
      ._2
    (block, microBlocks)
  }

  private def unsafeMicro(totalRefTo: ByteStr,
                          prevTotal: Block,
                          txs: Seq[Transaction],
                          signer: PrivateKeyAccount,
                          version: Byte,
                          ts: Long): (Block, MicroBlock) = {
    val newTotalBlock = unsafeBlock(totalRefTo, prevTotal.transactionData ++ txs, signer, version, ts)
    val unsigned      = new MicroBlock(version, signer, txs, prevTotal.uniqueId, newTotalBlock.uniqueId, ByteStr.empty)
    val signature     = crypto.sign(signer, unsigned.bytes())
    val signed        = unsigned.copy(signature = ByteStr(signature))
    (newTotalBlock, signed)
  }

  private def unsafeBlock(reference: ByteStr,
                          txs: Seq[Transaction],
                          signer: PrivateKeyAccount,
                          version: Byte,
                          timestamp: Long,
                          bTarget: Long = DefaultBaseTarget): Block = {
    val unsigned = Block(
      version = version,
      timestamp = timestamp,
      reference = reference,
      consensusData = NxtLikeConsensusBlockData(
        baseTarget = bTarget,
        generationSignature = generationSignature
      ),
      transactionData = txs,
      signerData = SignerData(
        generator = signer,
        signature = ByteStr.empty
      ),
      featureVotes = Set.empty
    )

    unsigned.copy(signerData = SignerData(signer, ByteStr(crypto.sign(signer, unsigned.bytes()))))
  }

}
