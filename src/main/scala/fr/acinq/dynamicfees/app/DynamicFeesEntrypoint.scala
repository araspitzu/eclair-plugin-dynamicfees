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

import akka.actor.Props
import com.typesafe.config.{Config, ConfigFactory}
import fr.acinq.dynamicfees.app.FeeAdjuster.{DynamicFeeRow, DynamicFeesBreakdown}
import fr.acinq.eclair.{Kit, Plugin, Setup, ShortChannelId}
import grizzled.slf4j.Logging

import scala.collection.JavaConverters._

class DynamicFeesEntrypoint extends Plugin with Logging {

  val fallbackConf = ConfigFactory.parseString(
    """
      dynamicfees.whitelist = []
      dynamicfees.blacklist = []
    """
  )
  var conf: Config = null
  var dynamicFeesConfiguration: DynamicFeesBreakdown = null

  logger.info("loading DynamicFees plugin")

  override def onSetup(setup: Setup): Unit = {
    conf = setup.config
    dynamicFeesConfiguration = DynamicFeesBreakdown(
      depleted = DynamicFeeRow(conf.getDouble("dynamicfees.depleted.threshold"), conf.getDouble("dynamicfees.depleted.multiplier")),
      saturated = DynamicFeeRow(conf.getDouble("dynamicfees.saturated.threshold"), conf.getDouble("dynamicfees.saturated.multiplier")),
      whitelist = conf.withFallback(fallbackConf).getStringList("dynamicfees.whitelist").asScala.toList.map(ShortChannelId.apply),
      blacklist = conf.withFallback(fallbackConf).getStringList("dynamicfees.blacklist").asScala.toList.map(ShortChannelId.apply)
    )
    logger.info(prettyPrint(dynamicFeesConfiguration))
  }

  def prettyPrint(dfb: DynamicFeesBreakdown) =
    s"""
       |+----------------------------------------------------------------------+
       |                 DYNAMIC FEES PLUGIN CONFIGURATION
       |
       |
       |      depleted channel: threshold = ${dfb.depleted.threshold} multiplier = ${dfb.depleted.multiplier}
       |
       |      saturated channel: threshold = ${dfb.saturated.threshold} multiplier = ${dfb.saturated.multiplier}
       |
       |      blacklisted: ${dfb.blacklist.size}       whitelisted: ${dfb.whitelist.size}
       |
       |
       |+----------------------------------------------------------------------+
     """.stripMargin

  override def onKit(kit: Kit): Unit = {
    kit.system.actorOf(Props(new FeeAdjuster(kit, dynamicFeesConfiguration)))
  }

}