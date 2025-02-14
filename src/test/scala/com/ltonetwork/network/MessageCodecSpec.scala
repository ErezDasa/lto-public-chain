package com.ltonetwork.network

import java.nio.charset.StandardCharsets

import com.ltonetwork.TransactionGen
import com.ltonetwork.transaction.transfer.TransferTransaction
import com.ltonetwork.transaction.Transaction
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.embedded.EmbeddedChannel
import org.scalamock.scalatest.MockFactory
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class MessageCodecSpec extends AnyFreeSpec with Matchers with MockFactory with ScalaCheckDrivenPropertyChecks with TransactionGen {

  "should block a sender of invalid messages" in {
    val codec = new SpiedMessageCodec
    val ch    = new EmbeddedChannel(codec)

    ch.writeInbound(RawBytes(TransactionSpec.messageCode, "foo".getBytes(StandardCharsets.UTF_8)))
    ch.readInbound[TransferTransaction]()

    codec.blockCalls shouldBe 1
  }

  "should not block a sender of valid messages" in forAll(randomTransactionGen) { origTx: Transaction =>
    val codec = new SpiedMessageCodec
    val ch    = new EmbeddedChannel(codec)

    ch.writeInbound(RawBytes.from(origTx))
    val decodedTx = ch.readInbound[Transaction]()

    decodedTx shouldBe origTx
    codec.blockCalls shouldBe 0
  }

  private class SpiedMessageCodec extends MessageCodec(PeerDatabase.NoOp) {
    var blockCalls = 0

    override def block(ctx: ChannelHandlerContext, e: Throwable): Unit = {
      blockCalls += 1
    }
  }

}
