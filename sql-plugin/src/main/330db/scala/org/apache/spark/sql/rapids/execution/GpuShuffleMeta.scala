/*
 * Copyright (c) 2023, NVIDIA CORPORATION.
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

package org.apache.spark.sql.rapids.execution

import com.nvidia.spark.rapids.{DataFromReplacementRule, GpuExec, RapidsConf, RapidsMeta}

import org.apache.spark.rapids.shims.GpuShuffleExchangeExec
import org.apache.spark.sql.catalyst.plans.physical.SinglePartition
import org.apache.spark.sql.execution.exchange.{EXECUTOR_BROADCAST, ShuffleExchangeExec}
import org.apache.spark.sql.execution.joins.BroadcastHashJoinExec


class GpuShuffleMeta(
    shuffle: ShuffleExchangeExec,
    conf: RapidsConf,
    parent: Option[RapidsMeta[_, _, _]],
    rule: DataFromReplacementRule)
  extends GpuShuffleMetaBase(shuffle, conf, parent, rule) {

  override def tagPlanForGpu(): Unit = {
    super.tagPlanForGpu()

    shuffle.shuffleOrigin match {
      // Since we are handling a broadcast (this on executor-side), the similar
      // rules for BroadcastExchange apply for whether this should be replaced or not
      case EXECUTOR_BROADCAST =>
        // Copied from GpuBroadcastMeta
        // ensure the outputPartitioning is SinglePartition
        if (!shuffle.outputPartitioning.equals(SinglePartition)) {
          willNotWorkOnGpu("Executor-side broadcast can only be converted " + 
            "with output partitioning SinglePartition at this time")
        }

        def isSupported(rm: RapidsMeta[_, _, _]): Boolean = rm.wrapped match {
          case _: BroadcastHashJoinExec => true
          case _ => false
        }
        if (parent.isDefined) {
          if (!parent.exists(isSupported)) {
            willNotWorkOnGpu("executor broadcast only works on the GPU if being used " +
                "with a GPU version of BroadcastHashJoinExec")
          }
        }

      case _ =>
    }
  }

  override def convertToGpu(): GpuExec =
    GpuShuffleExchangeExec(
      childParts.head.convertToGpu(),
      childPlans.head.convertIfNeeded(),
      shuffle.shuffleOrigin
    )(shuffle.outputPartitioning)
}
