package com.ltonetwork.utx

import cats._
import com.ltonetwork.account.Address
import com.ltonetwork.consensus.TransactionsOrdering
import com.ltonetwork.metrics.Instrumented
import com.ltonetwork.mining.MultiDimensionalMiningConstraint
import com.ltonetwork.settings.{FunctionalitySettings, UtxSettings}
import com.ltonetwork.state.diffs.TransactionDiffer
import com.ltonetwork.state.reader.CompositeBlockchain.composite
import com.ltonetwork.state.{Blockchain, ByteStr, Diff, Portfolio}
import com.ltonetwork.transaction.ValidationError.{GenericError, SenderIsBlacklisted}
import com.ltonetwork.transaction._
import com.ltonetwork.transaction.transfer._
import com.ltonetwork.utils.{ScorexLogging, Time}
import kamon.Kamon
import kamon.metric.instrument.{Time => KamonTime}
import monix.eval.Task
import monix.execution.Scheduler
import monix.execution.schedulers.SchedulerService

import java.util.concurrent.ConcurrentHashMap
import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.util.{Left, Right}

class UtxPoolImpl(time: Time, blockchain: Blockchain, feeCalculator: FeeCalculator, fs: FunctionalitySettings, utxSettings: UtxSettings)
    extends ScorexLogging
    with Instrumented
    with AutoCloseable
    with UtxPool {
  outer =>

  import com.ltonetwork.utx.UtxPoolImpl._

  private implicit val scheduler: SchedulerService = Scheduler.singleThread("utx-pool-cleanup")

  private val transactions          = new ConcurrentHashMap[ByteStr, Transaction]()
  private val pessimisticPortfolios = new PessimisticPortfolios

  private val removeInvalid = Task {
    val b                    = blockchain
    val transactionsToRemove = transactions.values.asScala.filter(t => b.containsTransaction(t.id()))
    removeAll(transactionsToRemove)
  }.delayExecution(utxSettings.cleanupInterval)

  private val cleanup = removeInvalid.flatMap(_ => removeInvalid).runAsyncLogErr

  override def close(): Unit = {
    cleanup.cancel()
    scheduler.shutdown()
  }

  private val utxPoolSizeStats    = Kamon.metrics.minMaxCounter("utx-pool-size", 500.millis)
  private val processingTimeStats = Kamon.metrics.histogram("utx-transaction-processing-time", KamonTime.Milliseconds)
  private val putRequestStats     = Kamon.metrics.counter("utx-pool-put-if-new")

  private def removeExpired(currentTs: Long): Unit = {
    def isExpired(tx: Transaction) = (currentTs - tx.timestamp).millis > utxSettings.maxTransactionAge

    transactions.values.asScala
      .filter(isExpired)
      .foreach { tx =>
        transactions.remove(tx.id())
        pessimisticPortfolios.remove(tx.id())
        utxPoolSizeStats.decrement()
      }
  }

  override def putIfNew(tx: Transaction): Either[ValidationError, (Boolean, Diff)] = putIfNew(blockchain, tx)

  private def checkNotBlacklisted(tx: Transaction): Either[ValidationError, Unit] = {
    if (utxSettings.blacklistSenderAddresses.isEmpty) {
      Right(())
    } else {
      val sender: Option[String] = tx match {
        case x: Transaction => Some(x.sender.address)
        case _              => None
      }

      sender match {
        case Some(addr) if utxSettings.blacklistSenderAddresses.contains(addr) =>
          val recipients = tx match {
            case tt: TransferTransaction      => Seq(tt.recipient)
            case mtt: MassTransferTransaction => mtt.transfers.map(_.address)
            case _                            => Seq()
          }
          val allowed =
            recipients.nonEmpty &&
              recipients.forall(r => utxSettings.allowBlacklistedTransferTo.contains(r.stringRepr))
          Either.cond(allowed, (), SenderIsBlacklisted(addr))
        case _ => Right(())
      }
    }
  }

  override def removeAll(txs: Traversable[Transaction]): Unit = {
    txs.view.map(_.id()).foreach { id =>
      Option(transactions.remove(id)).foreach(_ => utxPoolSizeStats.decrement())
      pessimisticPortfolios.remove(id)
    }

    removeExpired(time.correctedTime())
  }

  override def accountPortfolio(addr: Address): Portfolio = blockchain.portfolio(addr)

  override def portfolio(addr: Address): Portfolio =
    Monoid.combine(blockchain.portfolio(addr), pessimisticPortfolios.getAggregated(addr))

  override def all: Seq[Transaction] = transactions.values.asScala.toSeq.sorted(TransactionsOrdering.InUTXPool)

  override def size: Int = transactions.size

  override def transactionById(transactionId: ByteStr): Option[Transaction] = Option(transactions.get(transactionId))

  override def packUnconfirmed(rest: MultiDimensionalMiningConstraint, sortInBlock: Boolean): (Seq[Transaction], MultiDimensionalMiningConstraint) = {
    val currentTs = time.correctedTime()
    removeExpired(currentTs)
    val b      = blockchain
    val differ = TransactionDiffer(fs, blockchain.lastBlockTimestamp, currentTs, b.height) _
    val (invalidTxs, reversedValidTxs, _, finalConstraint, _) = transactions.values.asScala.toSeq
      .sorted(TransactionsOrdering.InUTXPool)
      .iterator
      .scanLeft((Seq.empty[ByteStr], Seq.empty[Transaction], Monoid[Diff].empty, rest, false)) {
        case ((invalid, valid, diff, currRest, isEmpty), tx) =>
          val updatedBlockchain = composite(b, diff, 0)
          val updatedRest       = currRest.put(updatedBlockchain, tx)
          if (updatedRest.isOverfilled) {
            (invalid, valid, diff, currRest, isEmpty)
          } else {
            differ(updatedBlockchain, tx) match {
              case Right(newDiff) =>
                (invalid, tx +: valid, Monoid.combine(diff, newDiff), updatedRest, currRest.isEmpty)
              case Left(_) =>
                (tx.id() +: invalid, valid, diff, currRest, isEmpty)
            }
          }
      }
      .takeWhile(!_._5)
      .reduce((_, s) => s)

    invalidTxs.foreach { itx =>
      transactions.remove(itx)
      pessimisticPortfolios.remove(itx)
    }
    val txs = if (sortInBlock) reversedValidTxs.sorted(TransactionsOrdering.InBlock) else reversedValidTxs.reverse
    (txs, finalConstraint)
  }

  override private[utx] def createBatchOps: UtxBatchOps = new BatchOpsImpl(blockchain)

  private class BatchOpsImpl(b: Blockchain) extends UtxBatchOps {
    override def putIfNew(tx: Transaction): Either[ValidationError, (Boolean, Diff)] = outer.putIfNew(b, tx)
  }

  private def putIfNew(b: Blockchain, tx: Transaction): Either[ValidationError, (Boolean, Diff)] = {
    putRequestStats.increment()
    measureSuccessful(
      processingTimeStats, {
        for {
          _    <- Either.cond(transactions.size < utxSettings.maxSize, (), GenericError("Transaction pool size limit is reached"))
          _    <- checkNotBlacklisted(tx)
          _    <- feeCalculator.enoughFee(tx, blockchain, fs)
          diff <- TransactionDiffer(fs, blockchain.lastBlockTimestamp, time.correctedTime(), blockchain.height)(b, tx)
        } yield {
          utxPoolSizeStats.increment()
          pessimisticPortfolios.add(tx.id(), diff)
          (Option(transactions.put(tx.id(), tx)).isEmpty, diff)
        }
      }
    )
  }
}

object UtxPoolImpl {

  private class PessimisticPortfolios {
    private type Portfolios = Map[Address, Portfolio]
    private val transactionPortfolios = new ConcurrentHashMap[ByteStr, Portfolios]()
    private val transactions          = new ConcurrentHashMap[Address, Set[ByteStr]]()

    def add(txId: ByteStr, txDiff: Diff): Unit = {
      val nonEmptyPessimisticPortfolios = txDiff.portfolios
        .map {
          case (addr, portfolio) => addr -> portfolio.pessimistic
        }
        .filterNot {
          case (_, portfolio) => portfolio.isEmpty
        }

      if (nonEmptyPessimisticPortfolios.nonEmpty &&
          Option(transactionPortfolios.put(txId, nonEmptyPessimisticPortfolios)).isEmpty) {
        nonEmptyPessimisticPortfolios.keys.foreach { address =>
          transactions.put(address, transactions.getOrDefault(address, Set.empty) + txId)
        }
      }
    }

    def getAggregated(accountAddr: Address): Portfolio = {
      val portfolios = for {
        txId <- transactions.getOrDefault(accountAddr, Set.empty).toSeq
        txPortfolios = transactionPortfolios.getOrDefault(txId, Map.empty[Address, Portfolio])
        txAccountPortfolio <- txPortfolios.get(accountAddr).toSeq
      } yield txAccountPortfolio

      Monoid.combineAll[Portfolio](portfolios)
    }

    def remove(txId: ByteStr): Unit = {
      if (Option(transactionPortfolios.remove(txId)).isDefined) {
        transactions.keySet().asScala.foreach { addr =>
          transactions.put(addr, transactions.getOrDefault(addr, Set.empty) - txId)
        }
      }
    }
  }

}
