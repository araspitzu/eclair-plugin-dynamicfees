/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import TestConstants._
import akka.actor.{ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.dynamicfees.app.FeeAdjuster
import fr.acinq.dynamicfees.app.FeeAdjuster.{DynamicFeeRow, DynamicFeesBreakdown}
import fr.acinq.eclair.payment.ChannelPaymentRelayed
import fr.acinq.eclair._
import fr.acinq.eclair.{Kit, ShortChannelId}
import fr.acinq.eclair.LongToBtcAmount
import fr.acinq.eclair.channel.{CMD_GETINFO, CMD_UPDATE_RELAY_FEE, NORMAL, RES_GETINFO}
import fr.acinq.eclair.channel.Register.Forward
import org.scalatest.{Outcome, fixture}

import scala.concurrent.duration._

class FeeAdjusterSpec extends TestKit(ActorSystem("test")) with fixture.FunSuiteLike {

  case class FixtureParam(kit: Kit, register: TestProbe, channel1: ByteVector32, channel2: ByteVector32)

  override def withFixture(test: OneArgTest): Outcome = {
    val register = TestProbe()
    val (channel1, channel2) = (randomBytes32, randomBytes32)
    val kit = Kit(
      nodeParams = Alice.nodeParams,
      system = system,
      watcher = TestProbe().ref,
      paymentHandler = TestProbe().ref,
      register = register.ref,
      commandBuffer = TestProbe().ref,
      relayer = TestProbe().ref,
      router = TestProbe().ref,
      switchboard = TestProbe().ref,
      paymentInitiator = TestProbe().ref,
      server = TestProbe().ref,
      wallet = new TestWallet
    )
    withFixture(test.toNoArgTest(FixtureParam(kit, register, channel1, channel2)))
  }


  test("configuration should allow only one between blacklist and whitelist") { _ =>
    // both empty list is okay
    val dfb = DynamicFeesBreakdown(DynamicFeeRow(0.2, 0.2D), DynamicFeeRow(0.9, 2D), List.empty, List.empty)

    // one of the two non empty is okay
    dfb.copy(whitelist = List(ShortChannelId("1x2x3")))
    dfb.copy(blacklist = List(ShortChannelId("1x2x3")))

    // both non empty list NOT okay
    assertThrows[IllegalArgumentException](dfb.copy(whitelist = List(ShortChannelId("0x0x0")), blacklist = List(ShortChannelId("1x2x3"))))
  }

  test("relay a payment and adjust a the relay fee of a depleted channel") { f =>
    import f._

    val sender = TestProbe()
    // we'll consider depleted anything that has toLocal < 40% of the channel capacity
    val dfb = DynamicFeesBreakdown(DynamicFeeRow(0.4, 0.1), DynamicFeeRow(0.9, 2), List.empty, List.empty)
    val feeAdjuster = system.actorOf(Props(new FeeAdjuster(kit, dfb)))

    // let's relay a payment
    val cpr = ChannelPaymentRelayed(20 msat, 19 msat, ByteVector32.One, channel1, channel2)
    sender.send(feeAdjuster, cpr)

    // the FeeAdjuster actor will ask for channel state info
    register.expectMsg(Forward(channel1, CMD_GETINFO))
    register.reply(RES_GETINFO(
      randomKey.publicKey, channel1, NORMAL, // channel1 has a depleted balance
      makeChannelDataNormal(100000 msat, 10000000 msat, channel1, ShortChannelId("0x1x0"), Alice.nodeParams.feeProportionalMillionth)))

    register.expectMsg(Forward(channel2, CMD_GETINFO))
    register.reply(RES_GETINFO(
      randomKey.publicKey, channel2, NORMAL, // channel2 is balanced
      makeChannelDataNormal(100000 msat, 100000 msat, channel2, ShortChannelId("0x2x0"), Alice.nodeParams.feeProportionalMillionth)))

    val urf = register.expectMsgType[Forward[CMD_UPDATE_RELAY_FEE]]
    assert(urf.channelId == channel1)
    assert(urf.message.feeProportionalMillionths == (Alice.nodeParams.feeProportionalMillionth * dfb.depleted.multiplier).toLong)

    // channel2 shouldn't be updated
    register.expectNoMsg(max = 2 seconds)
  }

  test("relay a payment and adjust a the relay fee of a saturated channel") { f =>
    import f._

    val sender = TestProbe()
    // we'll consider saturated anything that has toLocal > 70% of the channel capacity
    val dfb = DynamicFeesBreakdown(DynamicFeeRow(0.4, 0.1), DynamicFeeRow(0.7, 2), List.empty, List.empty)
    val feeAdjuster = system.actorOf(Props(new FeeAdjuster(kit, dfb)))

    // let's relay a payment
    val cpr = ChannelPaymentRelayed(20 msat, 19 msat, ByteVector32.One, channel1, channel2)
    sender.send(feeAdjuster, cpr)

    // the FeeAdjuster actor will ask for channel state info
    register.expectMsg(Forward(channel1, CMD_GETINFO))
    register.reply(RES_GETINFO(
      randomKey.publicKey, channel1, NORMAL, // channel1 has a saturated balance
      makeChannelDataNormal(8000.sat.toMilliSatoshi , 2000.sat.toMilliSatoshi, channel1, ShortChannelId("0x1x0"), Alice.nodeParams.feeProportionalMillionth)))

    register.expectMsg(Forward(channel2, CMD_GETINFO))
    register.reply(RES_GETINFO(
      randomKey.publicKey, channel2, NORMAL, // channel2 is balanced
      makeChannelDataNormal(100000 msat, 100000 msat, channel2, ShortChannelId("0x2x0"), Alice.nodeParams.feeProportionalMillionth)))

    val urf = register.expectMsgType[Forward[CMD_UPDATE_RELAY_FEE]]
    assert(urf.channelId == channel1)
    assert(urf.message.feeProportionalMillionths == (Alice.nodeParams.feeProportionalMillionth * dfb.saturated.multiplier).toLong)

    // channel2 shouldn't be updated
    register.expectNoMsg(max = 2 seconds)
  }

  test("do not emit a new channel update if the fee is already updated") { f =>
    import f._

    val sender = TestProbe()
    // we'll consider saturated anything that has toLocal > 70% of the channel capacity
    val dfb = DynamicFeesBreakdown(DynamicFeeRow(0.4, 0.1), DynamicFeeRow(0.7, 2), List.empty, List.empty)
    val feeAdjuster = system.actorOf(Props(new FeeAdjuster(kit, dfb)))

    // let's relay a payment
    val cpr = ChannelPaymentRelayed(20 msat, 19 msat, ByteVector32.One, channel1, channel2)
    sender.send(feeAdjuster, cpr)

    // the FeeAdjuster actor will ask for channel state info
    register.expectMsg(Forward(channel1, CMD_GETINFO))
    register.reply(RES_GETINFO(
      randomKey.publicKey, channel1, NORMAL, // channel1 has a saturated balance but its fee is already updated correctly
      makeChannelDataNormal(8000.sat.toMilliSatoshi , 2000.sat.toMilliSatoshi, channel1, ShortChannelId("0x1x0"), (Alice.nodeParams.feeProportionalMillionth * dfb.saturated.multiplier).toLong)))

    register.expectMsg(Forward(channel2, CMD_GETINFO))
    register.reply(RES_GETINFO(
      randomKey.publicKey, channel2, NORMAL, // channel2 is balanced
      makeChannelDataNormal(100000 msat, 100000 msat, channel2, ShortChannelId("0x2x0"), Alice.nodeParams.feeProportionalMillionth)))

    // we expect zero CMD_UPDATE_RELAY_FEE requests
    register.expectNoMsg(max = 2 seconds)
  }

  test("ignore a new update for a channel if is blacklisted") { f =>
    import f._

    val sender = TestProbe()
    // we'll consider saturated anything that has toLocal > 70% of the channel capacity
    val dfb = DynamicFeesBreakdown(DynamicFeeRow(0.4, 0.1), DynamicFeeRow(0.7, 2), List.empty, List(ShortChannelId("0x1x0")))
    val feeAdjuster = system.actorOf(Props(new FeeAdjuster(kit, dfb)))

    // let's relay a payment
    val cpr = ChannelPaymentRelayed(20 msat, 19 msat, ByteVector32.One, channel1, channel2)
    sender.send(feeAdjuster, cpr)

    // the FeeAdjuster actor will ask for channel state info
    register.expectMsg(Forward(channel1, CMD_GETINFO))
    register.reply(RES_GETINFO(
      randomKey.publicKey, channel1, NORMAL, // channel1 has a saturated balance and an outdated proportional fee but it's blacklisted
      makeChannelDataNormal(8000.sat.toMilliSatoshi , 2000.sat.toMilliSatoshi, channel1, ShortChannelId("0x1x0"), Alice.nodeParams.feeProportionalMillionth)))

    register.expectMsg(Forward(channel2, CMD_GETINFO))
    register.reply(RES_GETINFO(
      randomKey.publicKey, channel2, NORMAL, // channel2 is balanced
      makeChannelDataNormal(100000 msat, 100000 msat, channel2, ShortChannelId("0x2x0"), Alice.nodeParams.feeProportionalMillionth)))

    // we expect zero CMD_UPDATE_RELAY_FEE requests
    register.expectNoMsg(max = 2 seconds)
  }

  test("if the whitelist is non empty update fees only for whitelisted channels") { f =>
    import f._

    val sender = TestProbe()
    // we'll consider saturated anything that has toLocal > 70% of the channel capacity
    val dfb = DynamicFeesBreakdown(DynamicFeeRow(0.4, 0.1), DynamicFeeRow(0.7, 2), List(ShortChannelId("0x3x0")), List.empty)
    val feeAdjuster = system.actorOf(Props(new FeeAdjuster(kit, dfb)))

    // let's relay a payment
    val cpr = ChannelPaymentRelayed(20 msat, 19 msat, ByteVector32.One, channel1, channel2)
    sender.send(feeAdjuster, cpr)

    // the FeeAdjuster actor will ask for channel state info
    register.expectMsg(Forward(channel1, CMD_GETINFO))
    register.reply(RES_GETINFO(
      randomKey.publicKey, channel1, NORMAL, // channel1 has a saturated balance and an outdated proportional fee but it's NOT whitelisted
      makeChannelDataNormal(8000.sat.toMilliSatoshi , 2000.sat.toMilliSatoshi, channel1, ShortChannelId("0x1x0"), Alice.nodeParams.feeProportionalMillionth)))

    register.expectMsg(Forward(channel2, CMD_GETINFO))
    register.reply(RES_GETINFO(
      randomKey.publicKey, channel2, NORMAL, // channel2 is balanced
      makeChannelDataNormal(100000 msat, 100000 msat, channel2, ShortChannelId("0x2x0"), Alice.nodeParams.feeProportionalMillionth)))

    // we expect zero CMD_UPDATE_RELAY_FEE requests
    register.expectNoMsg(max = 2 seconds)
  }

}