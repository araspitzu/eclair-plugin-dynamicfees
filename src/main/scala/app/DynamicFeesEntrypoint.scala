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

import akka.actor.Props
import app.FeeAdjusterActor.{DynamicFeeRow, DynamicFeesBreakdown}
import com.typesafe.config.Config
import fr.acinq.eclair.{Kit, Plugin, Setup}
import grizzled.slf4j.Logging

class DynamicFeesEntrypoint extends Plugin with Logging {

  logger.info("loading dynamicfees plugin")

  var conf: Config = null
  var dynamicFeesConfiguration: DynamicFeesBreakdown = null

  override def onSetup(setup: Setup): Unit = {
    conf = setup.config
    dynamicFeesConfiguration = DynamicFeesBreakdown(
      depleted = DynamicFeeRow(conf.getDouble("dynamicfees.depleted.threshold"), conf.getDouble("dynamicfees.depleted.multiplier")),
      saturated = DynamicFeeRow(conf.getDouble("dynamicfees.saturated.threshold"), conf.getDouble("dynamicfees.saturated.multiplier"))
    )
    logger.info(prettyPrint(dynamicFeesConfiguration))
  }

  override def onKit(kit: Kit): Unit = {
    kit.system.actorOf(Props(new FeeAdjusterActor(kit, dynamicFeesConfiguration)))
  }

  def prettyPrint(dfb: DynamicFeesBreakdown) =
    s"""
       |+----------------------------------------------------------------------+
       ||                 DYNAMIC FEES PLUGIN CONFIGURATION                    |
       ||                                                                      |
       ||                                                                      |
       ||      depleted channel: threshold = ${dfb.depleted.threshold} multiplier = ${dfb.depleted.multiplier}              |
       ||                                                                      |
       ||      saturated channel: threshold = ${dfb.saturated.threshold} multiplier = ${dfb.saturated.multiplier}             |
       ||                                                                      |
       ||                                                                      |
       |+----------------------------------------------------------------------+
     """.stripMargin

}