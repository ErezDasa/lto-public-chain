package com.ltonetwork

import java.io._

import com.google.common.primitives.Ints
import com.typesafe.config.ConfigFactory
import com.ltonetwork.account.{Address, AddressScheme}
import com.ltonetwork.block.Block
import com.ltonetwork.consensus.PoSSelector
import com.ltonetwork.db.openDB
import com.ltonetwork.history.{CheckpointServiceImpl, StorageFactory}
import com.ltonetwork.mining.MultiDimensionalMiningConstraint
import com.ltonetwork.settings.{LtoSettings, loadConfig}
import com.ltonetwork.state.ByteStr
import com.ltonetwork.state.appender.BlockAppender
import com.ltonetwork.transaction.Transaction
import com.ltonetwork.utils._
import com.ltonetwork.utx.UtxPool
import monix.execution.Scheduler
import org.slf4j.bridge.SLF4JBridgeHandler
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success, Try}

object Importer extends ScorexLogging {
  def main(args: Array[String]): Unit = {

    SLF4JBridgeHandler.removeHandlersForRootLogger()
    SLF4JBridgeHandler.install()

    val configFilename = Try(args(0)).toOption.getOrElse("lto-testnet.conf")
    val config         = loadConfig(ConfigFactory.parseFile(new File(configFilename)))
    val settings       = LtoSettings.fromConfig(config)
    AddressScheme.current = new AddressScheme {
      override val chainId: Byte = settings.blockchainSettings.addressSchemeCharacter.toByte
    }

    implicit val scheduler: Scheduler = Scheduler.singleThread("appender")
    val utxPoolStub = new UtxPool {
      override def putIfNew(tx: Transaction)                                                     = ???
      override def removeAll(txs: Traversable[Transaction]): Unit                                = {}
      override def accountPortfolio(addr: Address)                                               = ???
      override def portfolio(addr: Address)                                                      = ???
      override def all                                                                           = ???
      override def size                                                                          = ???
      override def transactionById(transactionId: ByteStr)                                       = ???
      override def packUnconfirmed(rest: MultiDimensionalMiningConstraint, sortInBlock: Boolean) = ???
      override def close(): Unit                                                                 = {}
    }

    Try(args(1)) match {
      case Success(filename) =>
        log.info(s"Loading file '$filename'")

        createInputStream(filename) match {
          case Success(inputStream) =>
            val db                = openDB(settings.dataDirectory)
            val blockchainUpdater = StorageFactory(settings, db, NTP)
            val pos               = new PoSSelector(blockchainUpdater, settings.blockchainSettings)
            val checkpoint        = new CheckpointServiceImpl(db, settings.checkpointsSettings)
            val extAppender       = BlockAppender(checkpoint, blockchainUpdater, NTP, utxPoolStub, pos, settings, scheduler) _
            checkGenesis(settings, blockchainUpdater)
            val bis          = new BufferedInputStream(inputStream)
            var quit         = false
            val lenBytes     = new Array[Byte](Ints.BYTES)
            val start        = System.currentTimeMillis()
            var counter      = 0
            var blocksToSkip = blockchainUpdater.height - 1

            println(s"Skipping $blocksToSkip blocks(s)")

            while (!quit) {
              val s1 = bis.read(lenBytes)
              if (s1 == Ints.BYTES) {
                val len    = Ints.fromByteArray(lenBytes)
                val buffer = new Array[Byte](len)
                val s2     = bis.read(buffer)
                if (s2 == len) {
                  if (blocksToSkip > 0) {
                    blocksToSkip -= 1
                  } else {
                    val block = Block.parseBytes(buffer).get
                    if (blockchainUpdater.lastBlockId.contains(block.reference)) {
                      Await.result(extAppender.apply(block).runAsync, Duration.Inf) match {
                        case Left(ve) =>
                          log.error(s"Error appending block: $ve")
                          quit = true
                        case _ =>
                          counter = counter + 1
                      }
                    }
                  }
                } else {
                  println(s"$s2 != expected $len")
                  quit = true
                }
              } else {
                println(s"Expecting to read ${Ints.BYTES} but got $s1 (${bis.available()})")
                quit = true
              }
            }
            bis.close()
            inputStream.close()
            val duration = System.currentTimeMillis() - start
            log.info(s"Imported $counter block(s) in ${humanReadableDuration(duration)}")
          case Failure(ex) => log.error(s"Failed to open file '$filename")
        }
      case Failure(ex) => log.error(s"Failed to get input filename from second parameter: $ex")
    }
  }

  def createInputStream(filename: String): Try[FileInputStream] =
    Try {
      new FileInputStream(filename)
    }

}
