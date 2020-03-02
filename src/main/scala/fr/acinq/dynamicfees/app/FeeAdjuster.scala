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

package fr.acinq.dynamicfees.app

import akka.actor.{Actor, DiagnosticActorLogging}
import akka.util.Timeout
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.dynamicfees.app.FeeAdjuster.DynamicFeesBreakdown
import fr.acinq.eclair.channel._
import fr.acinq.eclair.payment.{ChannelPaymentRelayed, PaymentRelayed, TrampolinePaymentRelayed}
import fr.acinq.eclair.wire.ChannelUpdate
import fr.acinq.eclair.{EclairImpl, Kit, ShortChannelId}

import scala.concurrent.Future
import scala.concurrent.duration._

class FeeAdjuster(kit: Kit, dynamicFees: DynamicFeesBreakdown) extends Actor with DiagnosticActorLogging {

  implicit val system = context.system
  implicit val ec = context.system.dispatcher
  implicit val askTimeout: Timeout = Timeout(30 seconds)
  val eclair = new EclairImpl(kit)

  system.eventStream.subscribe(self, classOf[PaymentRelayed])

  override def receive: Receive = {
    case TrampolinePaymentRelayed(paymentHash, incoming, outgoing, _) =>
      log.debug(s"computing new dynamic fees for trampoline payment_hash=$paymentHash")
      val channels = incoming.map(_.channelId) ++ outgoing.map(_.channelId)
      updateFees(channels)

    case ChannelPaymentRelayed(_, _, paymentHash, fromChannelId, toChannelId, _) =>
      log.debug(s"computing new dynamic fees for relayed payment_hash=$paymentHash")
      val channels = fromChannelId :: toChannelId :: Nil
      updateFees(channels)
  }

  /**
    * Will update the relay fee of the given channels if their current balance falls into depleted/saturated
    * category
    */
  def updateFees(channels: Seq[ByteVector32]) = {
    Future.sequence(channels.map(getChannelData)).map(_.flatten).map { channelData =>
      channelData
        .filter(filterChannel)
        .foreach { channel =>
        newFeeProportionalForChannel(channel.commitments, channel.channelUpdate) match {
          case None =>
            log.debug(s"not updating fees for channelId=${channel.commitments.channelId}")
          case Some(feeProp) =>
            log.info(s"updating feeProportional for channelId=${channel.commitments.channelId} oldFee=${channel.channelUpdate.feeProportionalMillionths} newFee=$feeProp")
            eclair.updateRelayFee(Left(channel.commitments.channelId), kit.nodeParams.feeBase, feeProp)
        }
      }
    }
  }

  def filterChannel(channel: DATA_NORMAL): Boolean = {
    (dynamicFees.whitelist.isEmpty && dynamicFees.blacklist.isEmpty) ||
    (dynamicFees.whitelist.nonEmpty && dynamicFees.whitelist.contains(channel.shortChannelId)) ||
    (dynamicFees.blacklist.nonEmpty && !dynamicFees.blacklist.contains(channel.shortChannelId))
  }

  def getChannelData(channelId: ByteVector32): Future[Option[DATA_NORMAL]] = {
    eclair.channelInfo(Left(channelId)).map {
      case RES_GETINFO(_, _, state, data) if state == NORMAL => Some(data.asInstanceOf[DATA_NORMAL])
      case _ => None
    }
  }

  /**
    * Computes the updated fee proportional for this channel, the new value is returned only if it's necessary
    * to update.
    *
    * @return
    */
  def newFeeProportionalForChannel(commitments: Commitments, channelUpdate: ChannelUpdate): Option[Long] = {
    val toLocal = commitments.availableBalanceForSend
    val toRemote = commitments.availableBalanceForReceive
    val toLocalPercentage = toLocal.toLong.toDouble / (toLocal.toLong + toRemote.toLong)

    val multiplier = if (toLocalPercentage < dynamicFees.depleted.threshold) {
      // do depleted update
      dynamicFees.depleted.multiplier
    } else if (toLocalPercentage > dynamicFees.saturated.threshold) {
      // do saturated update
      dynamicFees.saturated.multiplier
    } else {
      // it's balanced, do not apply multiplier
      1D
    }

    val newFeeProportional = (kit.nodeParams.feeProportionalMillionth * multiplier).toLong
    log.debug(s"prevFeeProportional=${channelUpdate.feeProportionalMillionths} newFeeProportional=$newFeeProportional")

    if (channelUpdate.feeProportionalMillionths == newFeeProportional) {
      None
    } else {
      Some(newFeeProportional)
    }
  }

}

object FeeAdjuster {

  case class DynamicFeeRow(threshold: Double, multiplier: Double)

  case class DynamicFeesBreakdown(
    depleted: DynamicFeeRow,
    saturated: DynamicFeeRow,
    whitelist: List[ShortChannelId],
    blacklist: List[ShortChannelId]
  ) {
    require(!(whitelist.nonEmpty && blacklist.nonEmpty), "cannot use both whitelist and blacklist in dynamicfees plugin configuration")
  }

}
