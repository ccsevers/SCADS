package edu.berkeley.cs
package scads
package piql
package modeling

import comm._
import storage._
import perf._
import deploylib.ec2._
import deploylib.mesos._
import piql.scadr._
import perf.scadr._
import avro.marker._
import avro.runtime._

object Experiments {
  implicit var zooKeeperRoot = ZooKeeperNode("zk://zoo.knowsql.org/").getOrCreate("home").getOrCreate(System.getenv("USER"))
  val cluster = new Cluster(zooKeeperRoot)
  val resultsCluster = new ScadsCluster(zooKeeperRoot.getOrCreate("results"))

  implicit def classSource = cluster.classSource
  implicit def serviceScheduler = cluster.serviceScheduler
  def traceRoot = zooKeeperRoot.getOrCreate("traceCollection")

  def watchServiceScheduler = cluster.firstMaster.watch("/root/serviceScheduler.log")
  def debug(pkg: String) = net.lag.logging.Logger(pkg).setLevel(java.util.logging.Level.FINEST)
  def expCluster(expId: Int) = 
    new ScadsCluster(
      ZooKeeperNode("zk://zoo.knowsql.org/home/marmbrus/scads/experimentCluster%010d".format(expId)))

  def scadrClient(expId: Int) = 
    new ScadrClient(expCluster(expId), new ParallelExecutor)

  lazy val testTpcwClient =
    new piql.tpcw.TpcwClient(new piql.tpcw.TpcwLoaderTask(4,2,10,1000,2).newTestCluster, new ParallelExecutor with DebugExecutor)

  lazy val scadsCluster = new ScadsCluster(traceRoot)
  lazy val scadrClient = new piql.scadr.ScadrClient(scadsCluster, new ParallelExecutor)
  lazy val testScadrClient = {
    val cluster = TestScalaEngine.newScadsCluster()
    val client = new piql.scadr.ScadrClient(cluster, new ParallelExecutor with DebugExecutor)
    val loader = new piql.scadr.ScadrLoader(1, 1)
    loader.getData(0).load(client)
    client
  }

  def clients = cluster.slaves.pflatMap(_.jps).filter(_.main equals "AvroTaskMain").pfilterNot(_.stack contains "ScalaEngineTask")
  def laggards = clients.pfilterNot(_.stack contains "awaitChild")
  def tagClients = clients.map(_.remoteMachine.asInstanceOf[EC2Instance]).foreach(_.tags += ("task", "client"))

  def killTask(id: Int): Unit = cluster.serviceScheduler !? KillTaskRequest(id)

  implicit def productSeqToExcel(lines: Seq[Product]) = new {
    import java.io._
    def toExcel: Unit = {
      val file = File.createTempFile("scadsOut", ".csv")
      val writer = new FileWriter(file)

      lines.map(_.productIterator.mkString(",") + "\n").foreach(writer.write)
      writer.close

      Runtime.getRuntime.exec(Array("/usr/bin/open", file.getCanonicalPath))
    }
  }

  object QueryRunner {
    val results = resultsCluster.getNamespace[Result]("queryRunnerResults")

    def downloadResults: Unit = {
      val outfile = AvroOutFile[Result]("QueryRunnerResults.avro")
      results.iterateOverRange(None, None).foreach(outfile.append)
      outfile.close
    }

    def allResults = AvroInFile[Result]("QueryRunnerResults.avro")

    def goodResults = allResults.filter(_.failedQueries < 200)
      .filterNot(_.iteration == 1)
      .filter(_.clientConfig.iterationLengthMin == 10)
      .filter(_.clientConfig.numClients == 50)

    def queryTypeQuantile(results: Seq[Result] = goodResults.toSeq, quantile: Double = 0.90) =
      results.groupBy(_.queryDesc).map {
        case (queryDesc, results) => (queryDesc, results.map(_.responseTimes)
          .reduceLeft(_ + _)
          .map(_.quantile(quantile)))
      }

    def thoughtstreamGraph =
      queryTypeQuantile().filter(_._1.queryName == "thoughtstream")

    def quantileCsv(queryName: String) = {
      val quantiles = queryTypeQuantile().filter(_._1.queryName == queryName)

      quantiles.map(i => {
        var line: List[String] = Nil
        line = i._1.queryName :: line

        (1 to i._1.parameters.length).foreach(j =>
          line = i._1.parameters(j - 1).toString :: line
        )

        line = i._2.get.toString :: line
        println(line.reverse.mkString(","))
      })
    }

    def thoughtstreamQuantileCsv = {
		  println("queryName,numSubscriptions,numPerPage,latency")
		  quantileCsv("thoughtstream")
		}
		
		def myThoughtsQuantileCsv = {
		  println("queryName,numPerPage,latency")
		  quantileCsv("myThoughts")
		}

		def usersFollowedByQuantileCsv = {
		  println("queryName,numSubscriptions,latency")
		  quantileCsv("usersFollowedBy")
		}
		
    def defaultScadr = ScadrLoaderTask(numServers=10,
				       numLoaders=10,
				       followingCardinality=500,
				       replicationFactor=2,
				       usersPerServer=20000,
				       thoughtsPerUser=100
				     )

    def testScadrCluster = ScadrLoaderTask(numServers=10,
				  numLoaders=10,
				  followingCardinality=100,
				  replicationFactor=2,
				  usersPerServer=100,
				  thoughtsPerUser=100
				 )
    val defaultRunner = 
      QueryRunnerTask(10,
		      "edu.berkeley.cs.scads.piql.modeling.ScadrQueryProvider",
		      iterations=30,
		      iterationLengthMin=10,
		      threads=2,
		      traceIterators=false,
		      traceMessages=false,
		      traceQueries=false)

    def testReplicationEffect: Unit = {
      val rep = defaultScadr.copy(replicationFactor=2).newCluster
      val noRep = defaultScadr.newCluster
      benchmarkScadr(rep)
      benchmarkScadr(noRep)
    }

    def testGenericRunner: Unit = {
      val cluster = GenericRelationLoaderTask(4,2,2,10,1,500).newTestCluster
      val runner = QueryRunnerTask(1,
		      "edu.berkeley.cs.scads.piql.modeling.GenericQueryProvider",
		      iterations=10,
		      iterationLengthMin=1,
		      threads=2,
		      traceIterators=false,
		      traceMessages=false,
		      traceQueries=false)
      runner.clusterAddress = cluster.root.canonicalAddress
      runner.experimentAddress = cluster.root.canonicalAddress
      runner.resultClusterAddress = cluster.root.canonicalAddress
      while(cluster.getAvailableServers.size < 1) Thread.sleep(100)
      val thread = new Thread(runner)
      thread.start
    }

    def genericCluster = 
      GenericRelationLoaderTask(
	      numServers=10,
	      numLoaders=10,
	      replicationFactor=2,
	      tuplesPerServer=10000,
	      dataSize=10,
	      maxCardinality=500)
    
    def defaultGenericRunner =
       QueryRunnerTask(numClients=10,
		      "edu.berkeley.cs.scads.piql.modeling.GenericQueryProvider",
		      iterations=30,
		      iterationLengthMin=10,
		      threads=2,
		      traceIterators=false,
		      traceMessages=false,
		      traceQueries=false)

    def scadrGenericBenchmark = {
      val clust30 = genericCluster.copy(dataSize=30).newCluster
      val clust40 = genericCluster.copy(dataSize=40).newCluster
      val scadrCluster = defaultScadr.newCluster

      genericBenchmark(clust30)
      genericBenchmark(clust40)
      benchmarkScadr(scadrCluster)
    }

    val tpcwCluster = tpcw.TpcwLoaderTask(10, 10, numEBs=100, numItems=10000, 2)
    val tpcwRunner =
      QueryRunnerTask(numClients=10,
        "edu.berkeley.cs.scads.piql.modeling.TpcwQueryProvider",
        iterations=30,
        iterationLengthMin=10,
        threads=2,
        traceIterators=false,
        traceMessages=false,
        traceQueries=false)

    val defaultTpcwRunner =
      QueryRunnerTask(10,
		      "edu.berkeley.cs.scads.piql.modeling.TpcwQueryProvider",
		      iterations=5*6,
		      iterationLengthMin=10,
		      threads=20,
		      traceIterators=false,
		      traceMessages=false,
		      traceQueries=false)

    def tpcwBenchmark = {
      val cluster = tpcwCluster.newCluster
      defaultTpcwRunner.schedule(cluster, resultsCluster)
    }

    def testTpcwRunner: Unit = {
      val cluster = tpcw.TpcwLoaderTask(2, 2, 10, 1000, 2).newTestCluster
      val runner = QueryRunnerTask(1,
		      "edu.berkeley.cs.scads.piql.modeling.TpcwQueryProvider",
		      iterations=10,
		      iterationLengthMin=1,
		      threads=2,
		      traceIterators=false,
		      traceMessages=false,
		      traceQueries=false)
      runner.clusterAddress = cluster.root.canonicalAddress
      runner.experimentAddress = cluster.root.canonicalAddress
      runner.resultClusterAddress = cluster.root.canonicalAddress
      while(cluster.getAvailableServers.size < 1) Thread.sleep(100)
      val thread = new Thread(runner)
      thread.start
    }

    def genericBenchmark(cluster: ScadsCluster = genericCluster.newCluster) =
      defaultGenericRunner.schedule(cluster, resultsCluster)

    def benchmarkScadr(cluster: ScadsCluster = defaultScadr.newCluster) =
      defaultRunner.schedule(cluster,resultsCluster)
  }

  object TpcwScaleExperiment {
    import piql.tpcw._
    import scale._

    val results = resultsCluster.getNamespace[piql.tpcw.scale.Result]("tpcwScaleResults")

    val test =
      TpcwWorkflowTask(
        numClients=1,
        executorClass="edu.berkeley.cs.scads.piql.ParallelExecutor"
      ).testLocally(TpcwLoaderTask(2,2,10, 1000).newTestCluster)
  }

  object ScadrScaleExperiment {
    import perf.scadr._
    import scale._

    val results = resultsCluster.getNamespace[perf.scadr.scale.Result]("scadrScaleResults")

    def makeScaleGraphPoint(size: Int) = { 
      ScadrScaleTask(size,
		      "edu.berkeley.cs.scads.piql.ParallelExecutor",
		      0.01,
		      threads=10)
	.schedule(ScadrLoaderTask(size, size, 10).newCluster,
		  resultsCluster)
    }

    def dataSizeResults =
      new ScatterPlot(
        results.getRange(None, None)
          .filter(_.iteration != 1)
          .filter(_.clientConfig.threads == 20)
          .groupBy(e => (e.loaderConfig.usersPerServer, e.clusterAddress)).toSeq
          .map {
          case ((users,_), results) =>
            (users, results.map(_.times)
              .reduceLeft(_ + _)
              .quantile(.99))
        }.toSeq,
        title = "Users/Server vs. Response Time",
        xaxis = "Users per server",
        yaxis = "99th Percentile Response Time",
        xunit = "users/server",
        yunit = "milliseconds")

    def threadCountResults =
      new ScatterPlot(results.iterateOverRange(None, None)
			     .filter(_.iteration != 1).toSeq
			     .groupBy(_.clientConfig.threads)
			     .map {
			       case (threads, results) => 
				 (threads, results.map(_.times)
           .reduceLeft(_ + _)
           .quantile(.99))
      }.toSeq,
        title = "ThreadCount vs. Responsetime",
        xaxis = "Number of threads per application server",
        yaxis = "99th Percentile ResponseTime",
        xunit = "threads",
        yunit = "milliseconds")

    def runDataSizes(sizes: Seq[Int] = (2 to 20 by 2).map(_ * 10000)) = {
      sizes.map(numUsers =>
        ScadrScaleTask(10,
          "edu.berkeley.cs.scads.piql.ParallelExecutor",
          0.01,
          iterations = 2,
          runLengthMin = 2,
          threads = 20)
          .schedule(ScadrLoaderTask(10, 10, usersPerServer=numUsers, replicationFactor=1, followingCardinality=10).newCluster,
          resultsCluster))
    }

    def runThreads(numThreads: Seq[Int] = (10 to 100 by 10)) = {
      numThreads.map(numThreads =>
        ScadrScaleTask(10,
          "edu.berkeley.cs.scads.piql.ParallelExecutor",
          0.01,
          iterations = 2,
          runLengthMin = 2,
          threads = numThreads)
          .schedule(ScadrLoaderTask(10, 10, replicationFactor=2, followingCardinality=10).newCluster,
          resultsCluster))
    }

    def scaleResults = {
      results.iterateOverRange(None, None)
        .filter(_.loaderConfig.replicationFactor == 2)
        .filter(_.loaderConfig.usersPerServer == 60000)
        .filter(_.clientConfig.iterations == 4)
        .filter(_.clientConfig.threads == 10)
        .filter(_.clientConfig.runLengthMin == 5)
        .filter(_.iteration != 1).toSeq
        .groupBy(r => (r.clientConfig.clusterAddress, r.iteration))
        .map {
        case ((exp, iter), results) =>
          val aggHist = results.map(_.times).reduceLeft(_ + _)
          val loaderConfig = results.head.loaderConfig
          val clientConfig = results.head.clientConfig
          (loaderConfig.numServers, aggHist.totalRequests, aggHist.quantile(0.99), clientConfig.numClients, clientConfig.executorClass, iter, aggHist.quantile(0.90))
      }.toSeq
    }

    def runScaleTest(numServers: Int, executor: String) = {
      val cluster = ScadrLoaderTask(numServers, numServers/2, replicationFactor=2, followingCardinality=10, usersPerServer = 60000).newCluster

      ScadrScaleTask(
        numServers/2,
        executor,
        0.01,
        iterations = 4,
        runLengthMin = 5,
        threads = 10
      ).schedule(cluster, resultsCluster)
    }

    def sweetSpotResults = {
      results.iterateOverRange(None, None)
        .filter(_.loaderConfig.numServers == 10)
        .filter(_.loaderConfig.usersPerServer == 60000)
        .filter(_.clientConfig.iterations == 3).toSeq
        .groupBy(r => (r.clientConfig.experimentAddress, r.iteration)).toSeq
        .map {
        case (resultId, perThreadData) => {
          val aggregateTimes = perThreadData.map(_.times).reduceLeft(_ + _)
          val clientConfig = perThreadData.head.clientConfig
          val loaderConfig = perThreadData.head.loaderConfig
          (clientConfig.numClients, clientConfig.threads, loaderConfig.replicationFactor, aggregateTimes.totalRequests.toInt, aggregateTimes.quantile(0.90), aggregateTimes.quantile(0.99), aggregateTimes.stddev)
        }
      }
    }

    def sweetSpotGraph =
      new ScatterPlot(
        results.iterateOverRange(None, None)
          .filter(_.loaderConfig.numServers == 10)
          .filter(_.loaderConfig.usersPerServer == 60000)
          .filter(_.clientConfig.iterations == 3).toSeq
          .groupBy(r => (r.clientConfig.experimentAddress, r.iteration)).toSeq
          .map {
          case (resultId, perThreadData) => {
            val aggregateTimes = perThreadData.map(_.times).reduceLeft(_ + _)
            (aggregateTimes.totalRequests.toInt, aggregateTimes.quantile(0.99))
          }
        },
        title = "TotalReuqests vs. 99thth percentile responsetime",
        xaxis = "total Scadr Requests ",
        yaxis = "99th Percentile ResponseTime",
        xunit = "requests",
        yunit = "milliseconds")


    def findSweetSpot: Unit = {
      val storageNodes = 10
      val usersPerServer = 60000

      List(1, 5, 10).foreach(numClients => {
        List(10, 20, 30).foreach(numThreads => {
          List(1, 2).foreach(replication => {
            ScadrScaleTask(
              numClients,
              "edu.berkeley.cs.scads.piql.ParallelExecutor",
              0.01,
              iterations = 3,
              runLengthMin = 3,
              threads = numThreads)
              .schedule(
              ScadrLoaderTask(
                numServers = 10,
                numLoaders = 10,
                usersPerServer = usersPerServer,
                replicationFactor = replication,
                followingCardinality = 10).newCluster,
              resultsCluster)
          })
        })
      })
    }
  }
}
