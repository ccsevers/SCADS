package edu.berkeley.cs
package scads
package consistency
package tpcw

import edu.berkeley.cs.scads.piql.tpcw._
import edu.berkeley.cs.scads.piql.exec._
import edu.berkeley.cs.scads.storage.transactions._

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

case class MDCCResult(var clientConfig: MDCCTpcwWorkflowTask,
                      var loaderConfig: MDCCTpcwLoaderTask,
                      var clusterAddress: String,
                      var clientId: Int,
                      var iteration: Int,
                      var threadId: Int) extends AvroPair {
  var startTime: String = _
  var totalElaspedTime: Long = _ /* in ms */
  var times: Map[String, Histogram] = null
  var skips: Int = _
  var failures: Int = _
}

case class MDCCTpcwWorkflowTask(var numClients: Int,
                                var executorClass: String,
                                var startTime: String,
                                var numThreads: Int = 10,
                                var runLengthMin: Int = 5,
                                var clusterId: Int = 0,
                                var numClusters: Int = 1) extends AvroRecord with ReplicatedExperimentTask {

  var experimentAddress: String = _
  var clusterAddress: String = _
  var resultClusterAddress: String = _

  def run(): Unit = {

    val numGlobalClients = numClients * numClusters

    val clientId = coordination.registerAndAwait("clientStart", numGlobalClients, timeout=60*60*1000)

    logger.info("Waiting for cluster to be ready")
    val clusterConfig = clusterRoot.awaitChild("clusterReady")
    val loaderConfig = classOf[MDCCTpcwLoaderTask].newInstance.parse(clusterConfig.data)

    val results = resultCluster.getNamespace[MDCCResult]("tpcwMDCCResults")
    val executor = Class.forName(executorClass).newInstance.asInstanceOf[QueryExecutor]
    val tpcwClient = new MDCCTpcwClient(cluster, executor, loaderConfig.txProtocol)

    val loader = new TpcwLoader(
      numEBs = loaderConfig.numEBs,
      numItems = loaderConfig.numItems)

    logger.info("starting experiment at: " + startTime)

    val resultList = (1 to numThreads).pmap(threadId => {
      def getTime = System.nanoTime / 1000000
      val readHistogram = Histogram(1, 10000)
      val writeHistogram = Histogram(1, 10000)
      val histograms = new scala.collection.mutable.HashMap[String, Histogram]
      val runTime = runLengthMin * 60 * 1000L
      val iterationStartTime = getTime
      var endTime = iterationStartTime
      var skips = 0
      var failures = 0

      val workflow = new TpcwWorkflow(tpcwClient, loader)

      while(endTime - iterationStartTime < runTime) {
        val startTime = getTime
        try {
          val (wasExecuted, actionCommit, aName) = workflow.executeMDCCMix()
          val actionName = if (actionCommit) {
            aName + "-COMMIT"
          } else {
            aName + "-ABORT"
          }
          endTime = getTime
          val elapsedTime = endTime - startTime
          if (wasExecuted) { // we actually ran the query
            if (histograms.isDefinedAt(actionName)) {
              // Histogram for this action already exists.
              histograms.get(actionName).get += elapsedTime
            } else {
              // Create a histogram for this action.
              val newHist = Histogram(1, 10000)
              newHist += elapsedTime
              histograms.put(actionName, newHist)
            }
            if (actionName.contains("Write")) {
              writeHistogram += elapsedTime
            } else {
              readHistogram += elapsedTime
            }
          } else // we punted the query
            skips += 1
        } catch {
          case e => {
            logger.warning(e, "Execepting generating page")
            failures += 1
          }
        }
      }

      logger.info("Thread %d read tx stats 50th: %dms, 90th: %dms, 99th: %dms, avg: %fms, stddev: %fms",
                  threadId, readHistogram.quantile(0.50), readHistogram.quantile(0.90), readHistogram.quantile(0.99), readHistogram.average, readHistogram.stddev)
      logger.info("Thread %d write tx stats 50th: %dms, 90th: %dms, 99th: %dms, avg: %fms, stddev: %fms",
                  threadId, writeHistogram.quantile(0.50), writeHistogram.quantile(0.90), writeHistogram.quantile(0.99), writeHistogram.average, writeHistogram.stddev)
      val res = MDCCResult(this, loaderConfig, clusterRoot.canonicalAddress, clientId, 1, threadId)
      res.startTime = startTime
      res.totalElaspedTime = endTime - iterationStartTime
      res.times = histograms.toMap
      res.failures = failures
      res.skips = skips

      res
    })

    logger.info("******** aggregate results.")
    // Aggregate all the threads into 1 result.
    var res = resultList.head
    resultList.tail.foreach(r => {
      val histograms = new scala.collection.mutable.HashMap[String, Histogram]
      histograms ++= res.times
      r.times.foreach(x => {
        val (k, h) = x
        if (histograms.isDefinedAt(k)) {
          histograms.put(k, histograms.get(k).get + h)
        } else {
          histograms.put(k, h)
        }
      })

      res.times = histograms.toMap
      res.failures += r.failures
      res.skips += r.skips
    })

    logger.info("******** writing out aggregated results.")
    results ++= List(res)

    coordination.registerAndAwait("iteration1", numGlobalClients)

    if(clientId == 0) {
      ExperimentNotification.completions.publish("TPCW MDCC Complete", this.toJson)
      cluster.shutdown
    }
  }
}
