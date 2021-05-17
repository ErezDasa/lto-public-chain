package com.ltonetwork.state.diffs

import com.ltonetwork.account.PrivateKeyAccount
import com.ltonetwork.lagonaki.mocks.TestBlock.{create => block}
import com.ltonetwork.state.{ByteStr, EitherExt2}
import com.ltonetwork.transaction.anchor.AnchorTransactionV1
import com.ltonetwork.transaction.{DataTransaction, GenesisTransaction}
import com.ltonetwork.{NoShrink, TransactionGen, WithDB}
import org.scalacheck.Gen
import org.scalatest.prop.PropertyChecks
import org.scalatest.{Matchers, PropSpec}
class AnchorTransactionDiffTest extends PropSpec with PropertyChecks with Matchers with TransactionGen with NoShrink with WithDB {

  val baseSetup: Gen[(GenesisTransaction, PrivateKeyAccount, Long)] = for {
    master <- accountGen
    ts     <- positiveLongGen
    genesis: GenesisTransaction = GenesisTransaction.create(master, ENOUGH_AMT, ts).explicitGet()
  } yield (genesis, master, ts)

  def anchor(version: Byte, sender: PrivateKeyAccount, data: List[ByteStr], fee: Long, timestamp: Long): AnchorTransactionV1 =
    AnchorTransactionV1.selfSigned(version, sender, data, fee, timestamp).explicitGet()

  property("cannot overspend funds") {
    val setup = for {
      (genesis, master, ts) <- baseSetup
      value                 <- bytes64gen
      feeOverhead           <- Gen.choose[Long](1, ENOUGH_AMT)
      version               <- Gen.oneOf(DataTransaction.supportedVersions.toSeq)
      anchorTx = anchor(version, master, List(ByteStr(value)), ENOUGH_AMT + feeOverhead, ts + 10000)
    } yield (genesis, anchorTx)

    forAll(setup) {
      case (genesis, anchorTx) =>
        assertDiffEi(Seq(block(Seq(genesis))), block(Seq(anchorTx))) { blockDiffEi =>
          blockDiffEi should produce("negative lto balance")
        }
    }
  }
}
