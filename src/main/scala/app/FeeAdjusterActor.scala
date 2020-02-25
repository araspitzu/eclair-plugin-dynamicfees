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

package app

import akka.actor.{Actor, DiagnosticActorLogging}
import akka.util.Timeout
import app.FeeAdjusterActor.DynamicFeesBreakdown
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.channel.{CMD_UPDATE_RELAY_FEE, Commitments, DATA_NORMAL, NORMAL, RES_GETINFO}
import fr.acinq.eclair.channel.Register.Forward
import fr.acinq.eclair.payment.{ChannelPaymentRelayed, PaymentReceived, PaymentRelayed, PaymentSent, TrampolinePaymentRelayed}
import fr.acinq.eclair.wire.ChannelUpdate
import fr.acinq.eclair.{EclairImpl, Kit, MilliSatoshi, ShortChannelId}

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

class FeeAdjusterActor(kit: Kit, dynamicFees: DynamicFeesBreakdown) extends Actor with DiagnosticActorLogging {

  implicit val system = context.system
  implicit val ec = context.system.dispatcher
  //val log = system.log
  implicit val askTimeout: Timeout = Timeout(30 seconds)
  val eclair = new EclairImpl(kit)

  system.eventStream.subscribe(self, classOf[PaymentRelayed])

  override def receive: Receive = {
    case TrampolinePaymentRelayed(paymentHash, incoming, outgoing, _) =>
      log.debug(s"computing new dynamic fees for trampoline payment_hash=$paymentHash")
      val channels = incoming.map(_.channelId) ++ outgoing.map(_.channelId)
      Future.sequence(channels.map(getChannelData)).map(_.flatten).map { channelData =>
        updateFeesForChannels(channelData)
      }

    case ChannelPaymentRelayed(_, _, paymentHash, fromChannelId, toChannelId, _) =>
      log.debug(s"computing new dynamic fees for relayed payment_hash=$paymentHash")
      val channels = fromChannelId :: toChannelId :: Nil
      Future.sequence(channels.map(getChannelData)).map(_.flatten).map { channelData =>
        updateFeesForChannels(channelData)
      }
  }

  def getChannelData(channelId: ByteVector32): Future[Option[DATA_NORMAL]] = {
    eclair.channelInfo(Left(channelId)).map {
      case RES_GETINFO(_, _, state, data) if state == NORMAL => Some(data.asInstanceOf[DATA_NORMAL])
      case _ => None
    }
  }

  def updateFeesForChannels(channels: Seq[DATA_NORMAL]) = {
    channels.foreach { channel =>
      newFeeProportionalForChannel(channel.commitments, channel.channelUpdate) match {
        case None => log.debug(s"not updating fees for channelId=${channel.commitments.channelId}")
        case Some(feeProp) =>
          log.info(s"updating feeProportional for channelId=${channel.commitments.channelId} oldFee=${channel.channelUpdate.feeProportionalMillionths} newFee=$feeProp")
          kit.register ! Forward(channel.commitments.channelId , CMD_UPDATE_RELAY_FEE(kit.nodeParams.feeBase, feeProp))
      }
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
    } else if(toLocalPercentage > dynamicFees.saturated.threshold){
      // do saturated update
      dynamicFees.saturated.multiplier
    } else {
      // it's balanced, do not apply multiplier
      1D
    }

    val newFeeProportional = (kit.nodeParams.feeProportionalMillionth * multiplier).toLong
    log.debug(s"prevFeeProportional=${channelUpdate.feeProportionalMillionths} newFeeProportional=$newFeeProportional")

    if(channelUpdate.feeProportionalMillionths == newFeeProportional){
      None
    } else {
      Some(newFeeProportional)
    }
  }

}

object FeeAdjusterActor {

  case class DynamicFeeRow(threshold: Double, multiplier: Double)

  case class DynamicFeesBreakdown(
    depleted: DynamicFeeRow,
    saturated: DynamicFeeRow
  )

}
