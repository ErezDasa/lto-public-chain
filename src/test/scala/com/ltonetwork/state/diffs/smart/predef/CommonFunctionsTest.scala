package com.ltonetwork.state.diffs.smart.predef

import com.ltonetwork.state._
import com.ltonetwork.state.diffs._
import com.ltonetwork.{NoShrink, TransactionGen, account}
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import org.scalatest.Assertions
import org.scalatest.matchers.should.Matchers
import org.scalatest.propspec.AnyPropSpec
import com.ltonetwork.account.Address
import org.scalacheck.Gen

class CommonFunctionsTest extends AnyPropSpec with ScalaCheckDrivenPropertyChecks with Matchers with TransactionGen with NoShrink {

  property("Some/None/extract/isDefined") {
    val some3 = "if true then 3 else unit"
    val none  = "if false then 3 else unit"
    runScript[Any](some3) shouldBe Right(3L)
    runScript[Any](none) shouldBe Right(())
    runScript[Boolean](s"isDefined($some3)") shouldBe Right(true)
    runScript[Boolean](s"isDefined($none)") shouldBe Right(false)
    runScript[Long](s"extract($some3)") shouldBe Right(3L)
    runScript[Long](s"extract($none)") should produce("called on unit value")
  }

  property("size()") {
    val arr = Array(1: Byte, 2: Byte, 3: Byte)
    runScript[Long]("size(base58'')".stripMargin) shouldBe Right(0L)
    runScript[Long](s"size(base58'${ByteStr(arr).base58}')".stripMargin) shouldBe Right(3L)
  }

  property("getTransfer should extract MassTransfer transfers") {
    import scodec.bits.ByteVector
    forAll(massTransferGen.retryUntil(tg => tg.transfers.size > 0 && tg.transfers.map(_.address).forall(_.isInstanceOf[Address]))) {
      case (massTransfer) =>
        val resultAmount = runScript[Long](
          """
            |match tx {
            | case mttx : MassTransferTransaction  =>  mttx.transfers[0].amount
            | case other => throw()
            | }
            |""".stripMargin,
          massTransfer
        )
        resultAmount shouldBe Right(massTransfer.transfers(0).amount)
        val resultAddress = runScript[ByteVector](
          """
                                                      |match tx {
                                                      | case mttx : MassTransferTransaction  =>
                                                      |        mttx.transfers[0].recipient.bytes
                                                      | case other => throw()
                                                      | }
                                                      |""".stripMargin,
          massTransfer
        )
        resultAddress shouldBe Right(ByteVector(massTransfer.transfers(0).address.bytes.arr))
        val resultLen = runScript[Long](
          """
                                           |match tx {
                                           | case mttx : MassTransferTransaction  =>  size(mttx.transfers)
                                           | case other => throw()
                                           | }
                                           |""".stripMargin,
          massTransfer
        )
        resultLen shouldBe Right(massTransfer.transfers.size.toLong)
    }
  }

  property("+ should check overflow") {
    runScript[Long]("2 + 3") shouldBe Right(5L)
    runScript[Long](s"1 + ${Long.MaxValue}") should produce("long overflow")
  }

  property("general shadowing verification") {
    forAll(Gen.oneOf(transferV1Gen, transferV2Gen, massTransferGen(10))) {
      case (transfer) =>
        val result = runScript[Boolean](
          s"""
            |match tx {
            | case tx : TransferTransaction  => tx.id == base58'${transfer.id().base58}'
            | case tx : MassTransferTransaction => tx.timestamp == ${transfer.timestamp}
            | case other => throw()
            | }
            |""".stripMargin,
          transfer
        )
        result shouldBe Right(true)
    }
  }

  property("negative shadowing verification") {
    forAll(Gen.oneOf(transferV2Gen, massTransferGen(10))) {
      case (transfer) =>
        try {
          runScript[Boolean](
            s"""
               |let t = 100
               |match tx {
               | case t: TransferTransaction  => t.id == base58'${transfer.id().base58}'
               | case t: IssueTransaction => t.fee == ${transfer.fee}
               | case t: MassTransferTransaction => t.timestamp == ${transfer.timestamp}
               | case other => throw()
               | }
               |""".stripMargin,
            transfer
          )
        } catch {
          case ex: MatchError =>
            Assertions.assert(ex.getMessage().contains("Compilation failed: Value 't' already defined in the scope"))
          case _: Throwable => Assertions.fail("Some unexpected error")
        }
    }
  }

  property("shadowing of empty ref") {
    try {
      runScript[Boolean](
        s"""
               |match p {
               | case tx: TransferTransaction  => true
               | case other => throw()
               | }
               |""".stripMargin
      )
    } catch {
      case ex: MatchError => Assertions.assert(ex.getMessage().contains("Compilation failed: A definition of 'p' is not found"))
      case _: Throwable   => Assertions.fail("Some unexpected error")
    }
  }

  property("shadowing of inner pattern matching") {
    forAll(transferV2Gen) {
      case transfer =>
        val result =
          runScript[Boolean](
            s"""
               |match tx {
               | case tx: TransferTransaction => {
               |  match tx {
               |    case tx: TransferTransaction  => tx.id == base58'${transfer.id().base58}'
               |  }
               |  }
               | case other => throw()
               |}
               |""".stripMargin,
            transfer
          )
        result shouldBe Right(true)
    }
  }

  property("shadowing of external variable") {
    //TODO: script can be simplified after NODE-837 fix
    try {
      runScript[Boolean](
        s"""
           |match {
           |  let aaa = 1
           |  tx
           |} {
           |     case tx: TransferTransaction  => true
           |     case other => throw()
           | }
           |""".stripMargin
      )

    } catch {
      case ex: MatchError => Assertions.assert(ex.getMessage().contains("Compilation failed: Value 'tx' already defined in the scope"))
      case _: Throwable   => Assertions.fail("Some unexpected error")
    }
  }

  property("data constructors") {
    forAll(transferV2Gen, longEntryGen(dataAsciiKeyGen)) { (t, entry) =>
      val transferResult = runScript[Boolean](
        s"""
           |match tx {
           |  case tx: TransferTransaction =>
           |    let goodEq = tx.recipient == Address(base58'${t.recipient.asInstanceOf[account.Address].address}')
           |    let badAddressEq = tx.recipient == Address(base58'Mbembangwana')
           |    let badAddressNe = tx.recipient != Address(base58'3AfZaKieM5')
           |    goodEq && !badAddressEq && badAddressNe
           |  case _ => throw()
           |}
           |""".stripMargin,
        t
      )
      transferResult shouldBe Right(true)
    }
  }

  property("data constructors bad syntax") {
    val realAddr = "3My3KZgFQ3CrVHgz6vGRt8687sH4oAA1qp8"
    val cases = Seq(
      (s"""Address(\"$realAddr\")""", "Compilation failed: Non-matching types"),
      ("Address(base58'GzumLunBoK', 4)", "Function 'Address' requires 1 arguments, but 2 are provided"),
      ("Address()", "Function 'Address' requires 1 arguments, but 0 are provided"),
      (s"Addr(base58'$realAddr')", "Can't find a function 'Addr'")
    )
    for ((clause, err) <- cases) {
      try {
        runScript[Unit](
          s"""
             |match tx {
             |  case tx: TransferTransaction =>
             |    let dza = $clause
             |    throw()
             |  case _ => throw()
             |}
             |""".stripMargin
        )
      } catch {
        case ex: MatchError => Assertions.assert(ex.getMessage().contains(err))
        case e: Throwable   => Assertions.fail("Unexpected error", e)
      }
    }
  }
}
