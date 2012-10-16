package edu.berkeley.cs
package scads
package piql
package tpcw
package scale

import comm._
import perf._
import storage._
import avro.runtime._
import avro.marker._

import deploylib._
import deploylib.mesos._
import deploylib.ec2._

import scala.util.Random
import scala.collection.{ mutable => mu }

case class Result(var expId: String,
                  var clientConfig: TpcwWorkflowTask,
                  var loaderConfig: TpcwLoaderTask,
                  var clusterAddress: String,
                  var clientId: Int,
                  var iteration: Int,
                  var threadId: Int) extends AvroPair {
  var totalElaspedTime: Long = _ /* in ms */
  var times: Seq[Histogram] = null
  var skips: Int = _
  var failures: Int = _
}

case class TpcwWorkflowTask(var numClients: Int,
                            var executorClass: String,
                            var numThreads: Int = 10,
                            var iterations: Int = 3,
                            var runLengthMin: Int = 5) extends AvroRecord with ReplicatedExperimentTask {

  var experimentAddress: String = _
  var clusterAddress: String = _
  var resultClusterAddress: String = _
  var expId: String = _

  def run(): Unit = {

    val clientId = coordination.registerAndAwait("clientStart", numClients, timeout=60*60*1000)

    logger.info("Waiting for cluster to be ready")
    val clusterConfig = clusterRoot.awaitChild("clusterReady")
    val loaderConfig = classOf[TpcwLoaderTask].newInstance.parse(clusterConfig.data)

    val results = resultCluster.getNamespace[Result]("tpcwScaleResults")
    val executor = Class.forName(executorClass).newInstance.asInstanceOf[QueryExecutor]
    val tpcwClient = new TpcwClient(cluster, executor)

    /* Turn on caching for relations commonly used in view delta queries */
    tpcwClient.orders.cacheActive = true
    tpcwClient.items.cacheActive = true

    val loader = new TpcwLoader(
      numEBs = loaderConfig.numEBs,
      numItems = loaderConfig.numItems)

    for(iteration <- (1 to iterations)) {
      logger.info("Begining iteration %d", iteration)
      results ++= (1 to numThreads).pmap(threadId => {
        def getTime = System.nanoTime / 1000000
        val histograms = new scala.collection.mutable.HashMap[ActionType.ActionType, Histogram]

        val runTime = runLengthMin * 60 * 1000L
        val iterationStartTime = getTime
        var endTime = iterationStartTime
        var skips = 0
        var failures = 0

        val workflow = new TpcwWorkflow(tpcwClient, loader)

        while(endTime - iterationStartTime < runTime) {
          val startTime = getTime
          try {
            val actionExecuted = workflow.executeMix()
            endTime = getTime
            val elapsedTime = endTime - startTime
            actionExecuted match {
              case Some(action) =>
                histograms.getOrElseUpdate(action, Histogram(1, 5000)) += elapsedTime
              case None => skips += 1

            }
          } catch {
            case e => {
              logger.warning(e, "Execepting generating page")
              failures += 1
            }
          }
        }

        histograms.foreach {
          case (action, histogram) =>
            logger.info("%s Thread %d 50th: %dms, 90th: %dms, 99th: %dms, avg: %fms, stddev: %fms",
            action, threadId, histogram.quantile(0.50), histogram.quantile(0.90), histogram.quantile(0.99), histogram.average, histogram.stddev)
        }
        val res = Result(this, loaderConfig, clusterRoot.canonicalAddress, clientId, iteration, threadId)
        res.totalElaspedTime =  endTime - iterationStartTime

        res.times = histograms.map {
          case (action, histogram) =>
            histogram.name = action.toString
            histogram
        }.toSeq
        res.failures = failures
        res.skips = skips

        res
      })

      coordination.registerAndAwait("iteration" + iteration, numClients)
    }

    if(clientId == 0) {
      ExperimentNotification.completions.publish("TPCW Scale Complete", this.toJson)
      cluster.shutdown
    }
  }
}
