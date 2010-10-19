package edu.berkeley.cs
package scads
package perf

import comm._
import piql._
import storage._
import avro.runtime._
import avro.marker._

import deploylib._
import deploylib.mesos._

case class CardinalityResultKey(var clientConfig: CardinalityLoadClient, var clientId: Int, var iteration: Int, var threadId: Int) extends AvroRecord
case class CardinalityResultValue(var times: Histogram, var failures: Int) extends AvroRecord

object ScadrCardinalityTest extends Experiment {
  val results = resultCluster.getNamespace[CardinalityResultKey, CardinalityResultValue]("scadrCardinality")

  def clear = results.getRange(None, None).foreach(r => results.put(r._1, None))

  def printResults: Unit = {
    val runs = results.getRange(None, None).groupBy(k => (k._1.clientConfig, k._1.iteration)).filterNot(_._1._2 == 1).values
    runs.foreach(run => {
      val totalRequests = run.map(_._2.times.buckets.sum).sum
      val aggregrateHistogram = run.map(_._2.times).reduceLeft(_ + _)
      val cumulativeHistogram = aggregrateHistogram.buckets.scanLeft(0L)(_ + _).drop(1)
      val quantile50ResponseTime = cumulativeHistogram.findIndexOf(_ >= totalRequests * 0.50) * aggregrateHistogram.bucketSize
      val quantile99ResponseTime = cumulativeHistogram.findIndexOf(_ >= totalRequests * 0.99) * aggregrateHistogram.bucketSize
      val quantile999ResponseTime = cumulativeHistogram.findIndexOf(_ >= totalRequests * 0.999) * aggregrateHistogram.bucketSize
      val failures = run.map(_._2.failures).sum

      println(List(run.head._1.clientConfig.followingCardinality, failures, run.head._1.clientConfig.executorClass, totalRequests, quantile50ResponseTime, quantile99ResponseTime, quantile999ResponseTime).mkString("\t"))
    })
  }

  def run(followingCardinality: Int, executorClass: String = "edu.berkeley.cs.scads.piql.SimpleExecutor", clusterSize: Int = 10)(implicit classpath: Seq[ClassSource], scheduler: ExperimentScheduler): ZooKeeperProxy#ZooKeeperNode = {
    val expRoot = newExperimentRoot

    scheduler.scheduleExperiment(
      serverJvmProcess(expRoot.canonicalAddress) * clusterSize ++
      clientJvmProcess(
        CardinalityLoadClient(
          expRoot.canonicalAddress,
          clusterSize,
          clusterSize,
          followingCardinality,
          executorClass
        )
      ) * clusterSize
    )

    expRoot
  }
}

case class CardinalityLoadClient(var clusterAddress: String, var numServers: Int, var numClients: Int, var followingCardinality: Int, var executorClass: String, var iterations: Int = 5, var threads: Int = 1, var runLengthMin: Int = 5 ) extends AvroRecord with Runnable with Experiment {

  def run() = {
    val results = resultCluster.getNamespace[CardinalityResultKey, CardinalityResultValue]("scadrCardinality")
    val clusterRoot = ZooKeeperNode(clusterAddress)
    val coordination = clusterRoot.getOrCreate("coordination")
    val cluster = new ScadsCluster(clusterRoot)
    var executor = Class.forName(executorClass).newInstance.asInstanceOf[QueryExecutor]
    val scadrClient = new ScadrClient(cluster, executor)
    val loader = new ScadrLoader(scadrClient,
      replicationFactor = 1,
      numClients = numClients,
      numUsers = numServers * 10000,
      numThoughtsPerUser = 100,
      numSubscriptionsPerUser = followingCardinality,
      numTagsPerThought = 5)

    val clientId = coordination.registerAndAwait("clientStart", numClients)
    if(clientId == 0) {
      logger.info("Awaiting scads cluster startup")
      cluster.blockUntilReady(numServers)
      loader.createNamespaces
      scadrClient.users.setReadWriteQuorum(0.33, 0.67)
      scadrClient.thoughts.setReadWriteQuorum(0.33, 0.67)
      scadrClient.subscriptions.setReadWriteQuorum(0.33, 0.67)
      scadrClient.tags.setReadWriteQuorum(0.33, 0.67)
      scadrClient.idxUsersTarget.setReadWriteQuorum(0.33, 0.67)
    }

    coordination.registerAndAwait("startBulkLoad", numClients)
    logger.info("Begining bulk loading of data")
    loader.getData(clientId).load()
    logger.info("Bulk loading complete")
    coordination.registerAndAwait("loadingComplete", numClients)

    for(iteration <- (1 to iterations)) {
      logger.info("Begining iteration %d", iteration)

      results ++= (1 to threads).pmap(threadId => {
        def getTime = System.nanoTime / 1000000
        val histogram = Histogram(1, 5000)
        val runTime = runLengthMin * 60 * 1000L
        val iterationStartTime = getTime
        var endTime = iterationStartTime
        var failures = 0

        while(endTime - iterationStartTime < runTime) {
          val startTime = getTime
          try {
            scadrClient.thoughtstream(loader.randomUser, scadrClient.maxResultsPerPage)
            endTime = getTime
            val elapsedTime = endTime - startTime
            histogram.add(endTime - startTime)
          }
          catch {
            case e => {
              logger.warning(e, "Query Failed")
              failures += 1
              Thread.sleep(100)
            }
          }
        }

        logger.info("Thread %d complete", threadId)
        (CardinalityResultKey(this, clientId, iteration, threadId), CardinalityResultValue(histogram, failures))
      })

      coordination.registerAndAwait("iteration" + iteration, numClients)
    }

    if(clientId == 0)
      cluster.shutdown

    System.exit(0)
  }
}
