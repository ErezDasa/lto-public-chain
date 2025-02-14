package com.ltonetwork.network

import java.net.InetSocketAddress

import com.ltonetwork.{TransactionGen, crypto}
import io.netty.buffer.Unpooled.wrappedBuffer
import io.netty.buffer.{ByteBuf, Unpooled}
import io.netty.channel.embedded.EmbeddedChannel
import org.scalacheck.Gen
import org.scalamock.scalatest.MockFactory
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import com.ltonetwork.network.message.{MessageSpec, Message => ScorexMessage}

import scala.concurrent.duration.DurationInt

class LegacyFrameCodecSpec extends AnyFreeSpec with Matchers with MockFactory with ScalaCheckDrivenPropertyChecks with TransactionGen {

  "should handle multiple messages" in forAll(Gen.nonEmptyListOf(transferV1Gen)) { origTxs =>
    val codec = new LegacyFrameCodec(PeerDatabase.NoOp, 3.minutes)

    val buff = Unpooled.buffer
    origTxs.foreach(write(buff, _, TransactionSpec))

    val ch = new EmbeddedChannel(codec)
    ch.writeInbound(buff)

    val decoded = (1 to origTxs.size).map { _ =>
      ch.readInbound[RawBytes]()
    }

    val decodedTxs = decoded.map { x =>
      TransactionSpec.deserializeData(x.data).get
    }

    decodedTxs shouldEqual origTxs
  }

  "should reject an already received transaction" in {
    val tx    = transferV1Gen.sample.getOrElse(throw new RuntimeException("Can't generate a sample transaction"))
    val codec = new LegacyFrameCodec(PeerDatabase.NoOp, 3.minutes)
    val ch    = new EmbeddedChannel(codec)

    val buff1 = Unpooled.buffer
    write(buff1, tx, TransactionSpec)
    ch.writeInbound(buff1)

    val buff2 = Unpooled.buffer
    write(buff2, tx, TransactionSpec)
    ch.writeInbound(buff2)

    ch.inboundMessages().size() shouldEqual 1
  }

  "should not reject an already received GetPeers" in {
    val msg   = KnownPeers(Seq(InetSocketAddress.createUnresolved("127.0.0.1", 80)))
    val codec = new LegacyFrameCodec(PeerDatabase.NoOp, 3.minutes)
    val ch    = new EmbeddedChannel(codec)

    val buff1 = Unpooled.buffer
    write(buff1, msg, PeersSpec)
    ch.writeInbound(buff1)

    val buff2 = Unpooled.buffer
    write(buff2, msg, PeersSpec)
    ch.writeInbound(buff2)

    ch.inboundMessages().size() shouldEqual 2
  }

  private def write[T <: AnyRef](buff: ByteBuf, msg: T, spec: MessageSpec[T]): Unit = {
    val bytes    = spec.serializeData(msg)
    val checkSum = wrappedBuffer(crypto.fastHash(bytes), 0, ScorexMessage.ChecksumLength)

    buff.writeInt(LegacyFrameCodec.Magic)
    buff.writeByte(spec.messageCode)
    buff.writeInt(bytes.length)
    buff.writeBytes(checkSum)
    buff.writeBytes(bytes)
  }

}
