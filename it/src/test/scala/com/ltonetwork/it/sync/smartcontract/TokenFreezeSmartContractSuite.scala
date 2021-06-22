package com.ltonetwork.it.sync.smartcontract

import com.ltonetwork.account.PrivateKeyAccount
import com.ltonetwork.it.api.SyncHttpApi._
import com.ltonetwork.it.sync._
import com.ltonetwork.it.transactions.BaseTransactionSuite
import com.ltonetwork.state._
import com.ltonetwork.transaction.smart.SetScriptTransaction
import com.ltonetwork.transaction.smart.script.ScriptCompiler
import com.ltonetwork.transaction.transfer._
import org.scalatest.CancelAfterFailure
import play.api.libs.json.{JsNumber, Json}

/*
Scenario:
1. Alice's and swapBC1's balances initialisation
2. Create and setup smart contract for swapBC1
3. Alice funds swapBC1t
4. Alice can't take money from swapBC1
5.1 Bob takes funds because he knows secret hash and 5.2 after rollback wait height and Alice takes funds back
 */

class TokenFreezeSmartContractSuite extends BaseTransactionSuite with CancelAfterFailure {

  private val contract = PrivateKeyAccount("xxx".getBytes())
  private val other    = PrivateKeyAccount("xxx".getBytes())

  test("setup contract account with lto") {
    val tx =
      TransferTransaction
        .selfSigned(
          version = 1,
          sender = sender.privateKey,
          recipient = contract,
          amount = 500000000,
          timestamp = System.currentTimeMillis(),
          fee = minFee,
          attachment = Array.emptyByteArray
        )
        .explicitGet()

    val transferId = sender
      .signedBroadcast(tx.json() + ("type" -> JsNumber(TransferTransaction.typeId.toInt)))
      .id
    nodes.waitForHeightAriseAndTxPresent(transferId)
  }

  test("set contract to contract account") {
    val scriptText =
      """
        |
        | height > 10
        |
        |
        """.stripMargin

    val script = ScriptCompiler(scriptText).explicitGet()
    val setScriptTransaction = SetScriptTransaction
      .selfSigned(1, System.currentTimeMillis(), contract, 100000000, Some(script._1))
      .explicitGet()

    val setScriptId = sender
      .signedBroadcast(setScriptTransaction.json() + ("type" -> JsNumber(SetScriptTransaction.typeId.toInt)))
      .id

    nodes.waitForHeightAriseAndTxPresent(setScriptId)

    val acc0ScriptInfo = sender.addressScriptInfo(contract.address)

    acc0ScriptInfo.script.isEmpty shouldBe false
    acc0ScriptInfo.scriptText.isEmpty shouldBe false
    acc0ScriptInfo.script.get.startsWith("base64:") shouldBe true

    val json = Json.parse(sender.get(s"/transactions/info/$setScriptId").getResponseBody)
    (json \ "script").as[String].startsWith("base64:") shouldBe true
  }

  test("step3: can't transfer early") {
    val tx =
      TransferTransaction
        .selfSigned(
          version = 2,
          sender = contract,
          recipient = other,
          amount = 200000000,
          timestamp = System.currentTimeMillis(),
          fee = minFee,
          attachment = Array.emptyByteArray
        )
        .explicitGet()

    assertBadRequestAndMessage(sender
                                 .signedBroadcast(tx.json()),
                               "")

  }

  test("step4: can transfer when height is ok") {
    nodes.waitForHeight(11)
  }
  test("step5: now ok") {
    val tx =
      TransferTransaction
        .selfSigned(
          version = 2,
          sender = contract,
          recipient = other,
          amount = 200000000,
          timestamp = System.currentTimeMillis(),
          fee = minFee + 300000,
          attachment = Array.emptyByteArray
        )
        .explicitGet()
    val transferId = sender
      .signedBroadcast(tx.json() + ("type" -> JsNumber(TransferTransaction.typeId.toInt)))
      .id
    nodes.waitForHeightAriseAndTxPresent(transferId)

  }

}
