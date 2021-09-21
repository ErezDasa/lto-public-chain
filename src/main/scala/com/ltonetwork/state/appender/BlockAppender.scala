package com.ltonetwork.state.appender

import cats.data.EitherT
import com.ltonetwork.consensus.PoSSelector
import com.ltonetwork.mining.Miner
import com.ltonetwork.network._
import com.ltonetwork.settings.LtoSettings
import com.ltonetwork.state.Blockchain
import com.ltonetwork.utils.{ScorexLogging, Time}
import com.ltonetwork.utx.UtxPool
import io.netty.channel.Channel
import io.netty.channel.group.ChannelGroup
import monix.eval.Task
import monix.execution.Scheduler
import com.ltonetwork.block.Block
import com.ltonetwork.transaction.ValidationError.{BlockAppendError, InvalidSignature}
import com.ltonetwork.transaction.{BlockchainUpdater, CheckpointService, ValidationError}

import scala.util.Right

object BlockAppender extends ScorexLogging {

  def apply(checkpoint: CheckpointService,
            blockchainUpdater: BlockchainUpdater with Blockchain,
            time: Time,
            utxStorage: UtxPool,
            pos: PoSSelector,
            settings: LtoSettings,
            scheduler: Scheduler)(newBlock: Block): Task[Either[ValidationError, Option[BigInt]]] =
    Task {
      {
        if (blockchainUpdater.isLastBlockId(newBlock.reference)) {
          appendBlock(checkpoint, blockchainUpdater, utxStorage, pos, time, settings)(newBlock).map(_ => Some(blockchainUpdater.score))
        } else if (blockchainUpdater.contains(newBlock.uniqueId)) {
          Right(None)
        } else {
          Left(BlockAppendError("Block is not a child of the last block", newBlock))
        }
      }
    }.executeOn(scheduler)

  def apply(checkpoint: CheckpointService,
            blockchainUpdater: BlockchainUpdater with Blockchain,
            time: Time,
            utxStorage: UtxPool,
            pos: PoSSelector,
            settings: LtoSettings,
            allChannels: ChannelGroup,
            peerDatabase: PeerDatabase,
            miner: Miner,
            scheduler: Scheduler)(ch: Channel, newBlock: Block): Task[Unit] = {
    (for {
      _                <- EitherT(Task.now(newBlock.signaturesValid()))
      validApplication <- EitherT(apply(checkpoint, blockchainUpdater, time, utxStorage, pos, settings, scheduler)(newBlock))
    } yield validApplication).value.map {
      case Right(None) => // block already appended
      case Right(Some(_)) =>
        log.debug(s"${id(ch)} Appended $newBlock")
        if (newBlock.transactionData.isEmpty)
          allChannels.broadcast(BlockForged(newBlock), Some(ch))
        miner.scheduleMining()
      case Left(is: InvalidSignature) =>
        peerDatabase.blacklistAndClose(ch, s"Could not append $newBlock: $is")
      case Left(ve) =>
        log.debug(s"${id(ch)} Could not append $newBlock: $ve")
    }
  }
}
