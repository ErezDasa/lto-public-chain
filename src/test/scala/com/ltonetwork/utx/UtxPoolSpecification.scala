package com.ltonetwork.utx

import com.typesafe.config.ConfigFactory
import com.ltonetwork.account.{Address, PrivateKeyAccount, PublicKeyAccount}
import com.ltonetwork.block.Block
import com.ltonetwork.features.BlockchainFeatures
import com.ltonetwork.history.StorageFactory
import com.ltonetwork.lagonaki.mocks.TestBlock
import com.ltonetwork.lang.v1.compiler.Terms.EXPR
import com.ltonetwork.lang.v1.compiler.{CompilerContext, CompilerV1}
import com.ltonetwork.mining._
import com.ltonetwork.settings._
import com.ltonetwork.state.diffs._
import com.ltonetwork.state.{ByteStr, EitherExt2, _}
import com.ltonetwork.transaction.ValidationError.SenderIsBlacklisted
import com.ltonetwork.transaction.genesis.GenesisTransaction
import com.ltonetwork.transaction.smart.SetScriptTransaction
import com.ltonetwork.transaction.smart.script.Script
import com.ltonetwork.transaction.smart.script.v1.ScriptV1
import com.ltonetwork.transaction.transfer.MassTransferTransaction.ParsedTransfer
import com.ltonetwork.transaction.transfer._
import com.ltonetwork.transaction.{FeeCalculator, Transaction}
import com.ltonetwork.utils.Time
import com.ltonetwork._
import org.scalacheck.Gen
import org.scalacheck.Gen._
import org.scalamock.scalatest.MockFactory
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import org.scalatest.matchers.should.Matchers
import org.scalatest.freespec.AnyFreeSpec

import scala.concurrent.duration._

class UtxPoolSpecification
    extends AnyFreeSpec
    with Matchers
    with MockFactory
    with ScalaCheckDrivenPropertyChecks
    with TransactionGen
    with NoShrink
    with WithDB {

  private val calculatorSettings = FeesSettings(
    Map[Byte, Seq[FeeSettings]](
      GenesisTransaction.typeId      -> Seq(FeeSettings("BASE", 0)),
      TransferTransaction.typeId     -> Seq(FeeSettings("BASE", 0)),
      MassTransferTransaction.typeId -> Seq(FeeSettings("BASE", 0), FeeSettings("VAR", 0)),
      SetScriptTransaction.typeId    -> Seq(FeeSettings("BASE", 0))
    ))
  import CommonValidation.{ScriptExtraFee => extraFee}

  private def mkBlockchain(senderAccount: Address, senderBalance: Long) = {
    val config          = ConfigFactory.load()
    val genesisSettings = TestHelpers.genesisSettings(Map(senderAccount -> senderBalance))
    val origSettings    = LtoSettings.fromConfig(config)
    val settings = origSettings.copy(
      blockchainSettings = BlockchainSettings(
        'T',
        FunctionalitySettings.TESTNET.copy(
          preActivatedFeatures = Map(
            BlockchainFeatures.SmartAccounts.id -> 0
          )),
        genesisSettings
      ),
      feesSettings = calculatorSettings,
      featuresSettings = origSettings.featuresSettings.copy(autoShutdownOnUnsupportedFeature = false)
    )

    val bcu = StorageFactory(settings, db, new TestTime())
    bcu.processBlock(Block.genesis(genesisSettings).explicitGet()).explicitGet()
    bcu
  }

  private def transfer(sender: PrivateKeyAccount, maxAmount: Long, time: Time) =
    (for {
      amount    <- chooseNum(1, (maxAmount * 0.9).toLong)
      recipient <- accountGen
      fee       <- chooseNum(extraFee, (maxAmount * 0.1).toLong)
    } yield TransferTransaction.signed(1, time.getTimestamp(), sender, fee, recipient, amount, Array.empty[Byte]).explicitGet())
      .label("transferTransaction")

  private def transferWithRecipient(sender: PrivateKeyAccount, recipient: PublicKeyAccount, maxAmount: Long, time: Time) =
    (for {
      amount <- chooseNum(1, (maxAmount * 0.9).toLong)
      fee    <- chooseNum(extraFee, (maxAmount * 0.1).toLong)
    } yield TransferTransaction.signed(1, time.getTimestamp(), sender, fee, recipient, amount, Array.empty[Byte]).explicitGet())
      .label("transferWithRecipient")

  private def massTransferWithRecipients(sender: PrivateKeyAccount, recipients: List[PublicKeyAccount], maxAmount: Long, time: Time) = {
    val amount    = maxAmount / (recipients.size + 1)
    val transfers = recipients.map(r => ParsedTransfer(r.toAddress, amount))
    val txs = for {
      version <- Gen.oneOf(MassTransferTransaction.supportedVersions.toSeq)
      fee = extraFee + amount * extraFee / 10
    } yield MassTransferTransaction.signed(version, time.getTimestamp(), sender, fee, transfers, Array.empty[Byte]).explicitGet()
    txs.label("massTransferWithRecipients")
  }

  private def mkCalculator(blockchain: Blockchain) = new FeeCalculator(calculatorSettings, blockchain)

  private val stateGen = for {
    sender        <- accountGen.label("sender")
    senderBalance <- positiveLongGen.label("senderBalance")
    if senderBalance > 100000L
  } yield {
    val bcu = mkBlockchain(sender, senderBalance)
    (sender, senderBalance, bcu)
  }

  private val twoOutOfManyValidPayments = (for {
    (sender, senderBalance, bcu) <- stateGen
    recipient                    <- accountGen
    n                            <- chooseNum(3, 10)
    fee                          <- chooseNum(extraFee, (senderBalance * 0.01).toLong)
    offset                       <- chooseNum(1000L, 2000L)
  } yield {
    val time = new TestTime()
    val utx =
      new UtxPoolImpl(
        time,
        bcu,
        mkCalculator(bcu),
        FunctionalitySettings.TESTNET,
        UtxSettings(10, 10.minutes, Set.empty, Set.empty, 5.minutes)
      )
    val amountPart = (senderBalance - fee) / 2 - fee
    val txs        = for (_ <- 1 to n) yield createLtoTransfer(sender, recipient, amountPart, fee, time.getTimestamp()).explicitGet()
    (utx, time, txs, (offset + 1000).millis)
  }).label("twoOutOfManyValidPayments")

  private val emptyUtxPool = stateGen
    .map {
      case (sender, _, bcu) =>
        val time = new TestTime()
        val utxPool =
          new UtxPoolImpl(
            time,
            bcu,
            mkCalculator(bcu),
            FunctionalitySettings.TESTNET,
            UtxSettings(10, 1.minute, Set.empty, Set.empty, 5.minutes)
          )
        (sender, bcu, utxPool)
    }
    .label("emptyUtxPool")

  private val withValidPayments = (for {
    (sender, senderBalance, bcu) <- stateGen
    recipient                    <- accountGen
    time = new TestTime()
    txs <- Gen.nonEmptyListOf(transferWithRecipient(sender, recipient, senderBalance / 10, time))
  } yield {
    val settings = UtxSettings(10, 1.minute, Set.empty, Set.empty, 5.minutes)
    val utxPool  = new UtxPoolImpl(time, bcu, mkCalculator(bcu), FunctionalitySettings.TESTNET, settings)
    txs.foreach(utxPool.putIfNew)
    (sender, bcu, utxPool, time, settings)
  }).label("withValidPayments")

  private val withBlacklisted = (for {
    (sender, senderBalance, bcu) <- stateGen
    recipient                    <- accountGen
    time = new TestTime()
    txs <- Gen.nonEmptyListOf(transferWithRecipient(sender, recipient, senderBalance / 10, time)) // @TODO: Random transactions
  } yield {
    val settings = UtxSettings(10, 1.minute, Set(sender.address), Set.empty, 5.minutes)
    val utxPool  = new UtxPoolImpl(time, bcu, mkCalculator(bcu), FunctionalitySettings.TESTNET, settings)
    (sender, utxPool, txs)
  }).label("withBlacklisted")

  private val withBlacklistedAndAllowedByRule = (for {
    (sender, senderBalance, bcu) <- stateGen
    recipient                    <- accountGen
    time = new TestTime()
    txs <- Gen.nonEmptyListOf(transferWithRecipient(sender, recipient, senderBalance / 10, time)) // @TODO: Random transactions
  } yield {
    val settings = UtxSettings(txs.length, 1.minute, Set(sender.address), Set(recipient.address), 5.minutes)
    val utxPool  = new UtxPoolImpl(time, bcu, mkCalculator(bcu), FunctionalitySettings.TESTNET, settings)
    (sender, utxPool, txs)
  }).label("withBlacklistedAndAllowedByRule")

  private def massTransferWithBlacklisted(allowRecipients: Boolean) =
    (for {
      (sender, senderBalance, bcu) <- stateGen
      addressGen = Gen.listOf(accountGen).filter(list => if (allowRecipients) list.nonEmpty else true)
      recipients <- addressGen
      time = new TestTime()
      txs <- Gen.nonEmptyListOf(massTransferWithRecipients(sender, recipients, senderBalance / 10, time))
    } yield {
      val whitelist: Set[String] = if (allowRecipients) recipients.map(_.address).toSet else Set.empty
      val settings               = UtxSettings(txs.length, 1.minute, Set(sender.address), whitelist, 5.minutes)
      val utxPool                = new UtxPoolImpl(time, bcu, mkCalculator(bcu), FunctionalitySettings.TESTNET, settings)
      (sender, utxPool, txs)
    }).label("massTransferWithBlacklisted")

  private def utxTest(utxSettings: UtxSettings = UtxSettings(20, 5.seconds, Set.empty, Set.empty, 5.minutes), txCount: Int = 10)(
      f: (Seq[TransferTransaction], UtxPool, TestTime) => Unit): Unit = forAll(stateGen, chooseNum(2, txCount).label("txCount")) {
    case ((sender, senderBalance, bcu), count) =>
      val time = new TestTime()

      forAll(listOfN(count, transfer(sender, senderBalance / 2, time))) { txs =>
        val utx = new UtxPoolImpl(time, bcu, mkCalculator(bcu), FunctionalitySettings.TESTNET, utxSettings)
        f(txs, utx, time)
      }
  }

  private val dualTxGen: Gen[(UtxPool, TestTime, Seq[Transaction], FiniteDuration, Seq[Transaction])] =
    for {
      (sender, senderBalance, bcu) <- stateGen
      ts = System.currentTimeMillis()
      count1 <- chooseNum(5, 10)
      tx1    <- listOfN(count1, transfer(sender, senderBalance / 2, new TestTime(ts)))
      offset <- chooseNum(5000L, 10000L)
      tx2    <- listOfN(count1, transfer(sender, senderBalance / 2, new TestTime(ts + offset + 1000)))
    } yield {
      val time = new TestTime()
      val utx = new UtxPoolImpl(
        time,
        bcu,
        mkCalculator(bcu),
        FunctionalitySettings.TESTNET,
        UtxSettings(10, offset.millis, Set.empty, Set.empty, 5.minutes)
      )
      (utx, time, tx1, (offset + 1000).millis, tx2)
    }

  private val expr: EXPR = {
    val code =
      """let x = 1
        |let y = 2
        |true""".stripMargin

    val compiler = new CompilerV1(CompilerContext.empty)
    compiler.compile(code, List.empty).explicitGet()
  }

  private val script: Script = ScriptV1(expr).explicitGet()

  private def preconditionsGen(lastBlockId: ByteStr, master: PrivateKeyAccount): Gen[Seq[Block]] =
    for {
      ts <- timestampGen
    } yield {
      val setScript = SetScriptTransaction.signed(1, ts + 1, master, 100000000, Some(script)).explicitGet()
      Seq(TestBlock.create(ts + 1, lastBlockId, Seq(setScript)))
    }

  private val withScriptedAccount: Gen[(PrivateKeyAccount, Long, UtxPoolImpl, Long)] = for {
    (sender, senderBalance, bcu) <- stateGen
    preconditions                <- preconditionsGen(bcu.lastBlockId.get, sender)
  } yield {
    val smartAccountsFs = TestFunctionalitySettings.Enabled.copy(preActivatedFeatures = Map(BlockchainFeatures.SmartAccounts.id -> 0))
    preconditions.foreach(b => bcu.processBlock(b).explicitGet())
    val utx = new UtxPoolImpl(
      new TestTime(preconditions.head.timestamp),
      bcu,
      mkCalculator(bcu),
      smartAccountsFs,
      UtxSettings(10, 1.day, Set.empty, Set.empty, 1.day)
    )

    (sender, senderBalance, utx, bcu.lastBlock.fold(0L)(_.timestamp))
  }

  private def transactionGen(sender: PrivateKeyAccount, ts: Long, fee: Long): Gen[TransferTransaction] = accountGen.map { recipient =>
    TransferTransaction.signed(2: Byte, ts, sender, fee, recipient, lto(1), Array.emptyByteArray).explicitGet()
  }

  private val notEnoughFeeTxWithScriptedAccount = for {
    (sender, _, utx, ts) <- withScriptedAccount
    fee = 100 * 1000 * 1000 - 1
    tx <- transactionGen(sender, ts + 1, fee)
  } yield (utx, tx)

  private val enoughFeeTxWithScriptedAccount = for {
    (sender, _, utx, ts) <- withScriptedAccount
    fee                  <- choose(100 * 1000 * 1000, 2 * 100 * 1000 * 1000)
    tx                   <- transactionGen(sender, ts + 1, fee)
  } yield (utx, tx)

  "UTX Pool" - {
    "does not add new transactions when full" in utxTest(UtxSettings(1, 5.seconds, Set.empty, Set.empty, 5.minutes)) { (txs, utx, _) =>
      utx.putIfNew(txs.head) shouldBe 'right
      all(txs.tail.map(t => utx.putIfNew(t))) should produce("pool size limit")
    }

    "does not broadcast the same transaction twice" in utxTest() { (txs, utx, _) =>
      utx.putIfNew(txs.head) shouldBe 'right
      utx.putIfNew(txs.head) shouldBe 'right
    }

    "evicts expired transactions when removeAll is called" in forAll(dualTxGen) {
      case (utx, time, txs1, offset, txs2) =>
        all(txs1.map(utx.putIfNew)) shouldBe 'right
        utx.all.size shouldEqual txs1.size

        time.advance(offset)
        utx.removeAll(Seq.empty)

        all(txs2.map(utx.putIfNew)) shouldBe 'right
        utx.all.size shouldEqual txs2.size
    }

    "packUnconfirmed result is limited by constraint" in forAll(dualTxGen) {
      case (utx, _, txs, _, _) =>
        all(txs.map(utx.putIfNew)) shouldBe 'right
        utx.all.size shouldEqual txs.size

        val maxNumber             = Math.max(utx.all.size / 2, 3)
        val rest                  = limitByNumber(maxNumber)
        val (packed, restUpdated) = utx.packUnconfirmed(rest, sortInBlock = false)

        packed.lengthCompare(maxNumber) should be <= 0
        if (maxNumber <= utx.all.size) restUpdated.isEmpty shouldBe true
    }

    "evicts expired transactions when packUnconfirmed is called" in forAll(dualTxGen) {
      case (utx, time, txs, offset, _) =>
        all(txs.map(utx.putIfNew)) shouldBe 'right
        utx.all.size shouldEqual txs.size

        time.advance(offset)

        val (packed, _) = utx.packUnconfirmed(limitByNumber(100), sortInBlock = false)
        packed shouldBe 'empty
        utx.all shouldBe 'empty
    }

    "evicts one of mutually invalid transactions when packUnconfirmed is called" in forAll(twoOutOfManyValidPayments) {
      case (utx, time, txs, offset) =>
        all(txs.map(utx.putIfNew)) shouldBe 'right
        utx.all.size shouldEqual txs.size

        time.advance(offset)

        val (packed, _) = utx.packUnconfirmed(limitByNumber(100), sortInBlock = false)
        packed.size shouldBe 2
        utx.all.size shouldBe 2
    }

    "portfolio" - {
      "returns a count of assets from the state if there is no transaction" in forAll(emptyUtxPool) {
        case (sender, state, utxPool) =>
          val basePortfolio = state.portfolio(sender)

          utxPool.size shouldBe 0
          val utxPortfolio = utxPool.portfolio(sender)

          basePortfolio shouldBe utxPortfolio
      }

      "taking into account unconfirmed transactions" in forAll(withValidPayments) {
        case (sender, state, utxPool, _, _) =>
          val basePortfolio = state.portfolio(sender)

          utxPool.size should be > 0
          val utxPortfolio = utxPool.portfolio(sender)

          utxPortfolio.balance should be <= basePortfolio.balance
          utxPortfolio.lease.out should be <= basePortfolio.lease.out
          // should not be changed
          utxPortfolio.lease.in shouldBe basePortfolio.lease.in

      }

      "is changed after transactions with these assets are removed" in forAll(withValidPayments) {
        case (sender, _, utxPool, time, settings) =>
          val utxPortfolioBefore = utxPool.portfolio(sender)
          val poolSizeBefore     = utxPool.size

          time.advance(settings.maxTransactionAge * 2)
          utxPool.packUnconfirmed(limitByNumber(100), sortInBlock = false)

          poolSizeBefore should be > utxPool.size
          val utxPortfolioAfter = utxPool.portfolio(sender)

          utxPortfolioAfter.balance should be >= utxPortfolioBefore.balance
          utxPortfolioAfter.lease.out should be >= utxPortfolioBefore.lease.out

      }
    }

    "blacklisting" ignore {
      "prevent a transfer transaction from specific addresses" in {
        val transferGen = Gen.oneOf(withBlacklisted, massTransferWithBlacklisted(allowRecipients = false))
        forAll(transferGen) {
          case (_, utxPool, txs) =>
            val r = txs.forall { tx =>
              utxPool.putIfNew(tx) match {
                case Left(SenderIsBlacklisted(_)) => true
                case _                            => false
              }
            }

            r shouldBe true
            utxPool.all.size shouldEqual 0
        }
      }

      "allow a transfer transaction from blacklisted address to specific addresses" in {
        val transferGen = Gen.oneOf(withBlacklistedAndAllowedByRule, massTransferWithBlacklisted(allowRecipients = true))
        forAll(transferGen) {
          case (_, utxPool, txs) =>
            all(txs.map { t =>
              utxPool.putIfNew(t)
            }) shouldBe 'right
            utxPool.all.size shouldEqual txs.size
        }
      }
    }

    // See NODE-702
    "smart accounts" - {
      "not enough fee" in {
        val (utx, tx) = notEnoughFeeTxWithScriptedAccount.sample.getOrElse(throw new IllegalStateException("NO SAMPLE"))
        utx.putIfNew(tx) should produce("InsufficientFee")
      }

      "enough fee" in {
        val (utx, tx) = enoughFeeTxWithScriptedAccount.sample.getOrElse(throw new IllegalStateException("NO SAMPLE"))
        utx.putIfNew(tx) shouldBe 'right
      }
    }
  }

  private def limitByNumber(n: Int): MultiDimensionalMiningConstraint = MultiDimensionalMiningConstraint(
    OneDimensionalMiningConstraint(n, TxEstimators.one),
    OneDimensionalMiningConstraint(n, TxEstimators.one)
  )

}
