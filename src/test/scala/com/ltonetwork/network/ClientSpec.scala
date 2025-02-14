package com.ltonetwork.network

import java.util.concurrent.ConcurrentHashMap

import com.ltonetwork.{TransactionGen, Version}
import io.netty.buffer.{ByteBuf, Unpooled}
import io.netty.channel.Channel
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.channel.group.ChannelGroup
import org.scalamock.scalatest.MockFactory
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.DurationInt
import scala.util.Random

class ClientSpec extends AnyFreeSpec with Matchers with MockFactory with TransactionGen {

  private val clientHandshake = new Handshake(
    applicationName = "ltoI",
    applicationVersion = Version.VersionTuple,
    nodeName = "test",
    nodeNonce = Random.nextInt(),
    declaredAddress = None
  )

  private val serverHandshake = clientHandshake.copy(nodeNonce = Random.nextInt())

  "should send only a local handshake on connection" in {
    val channel = createEmbeddedChannel(mock[ChannelGroup])

    val sentClientHandshakeBuff = channel.readOutbound[ByteBuf]()
    Handshake.decode(sentClientHandshakeBuff) shouldBe clientHandshake
    channel.outboundMessages() shouldBe empty
  }

  "should add a server's channel to all channels after the handshake only" in {
    var channelWasAdded = false
    val allChannels     = mock[ChannelGroup]
    (allChannels.add _).expects(*).onCall { _: Channel =>
      channelWasAdded = true
      true
    }

    val channel = createEmbeddedChannel(allChannels)

    // skip the client's handshake
    channel.readOutbound[ByteBuf]()
    channelWasAdded shouldBe false

    val replyServerHandshakeBuff = Unpooled.buffer()
    serverHandshake.encode(replyServerHandshakeBuff)
    channel.writeInbound(replyServerHandshakeBuff)
    channelWasAdded shouldBe true
  }

  private def createEmbeddedChannel(allChannels: ChannelGroup) = new EmbeddedChannel(
    new HandshakeDecoder(PeerDatabase.NoOp),
    new HandshakeTimeoutHandler(1.minute),
    new HandshakeHandler.Client(
      handshake = clientHandshake,
      establishedConnections = new ConcurrentHashMap(),
      peerConnections = new ConcurrentHashMap(),
      peerDatabase = PeerDatabase.NoOp,
      allChannels = allChannels
    )
  )

}
