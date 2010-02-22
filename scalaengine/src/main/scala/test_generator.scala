import edu.berkeley.cs.scads.comm._
import edu.berkeley.cs.scads.storage._
import edu.berkeley.cs.scads.test._
import edu.berkeley.cs.scads.comm.Conversions._
import scala.actors._
import scala.actors.Actor._
import org.apache.log4j.Logger

object CreateDirectorData {
	val logger = Logger.getLogger("scads.datagen")
	implicit val proxy = new StorageActorProxy
	val namespace = "perfTest256"
	val key = new IntRec
	var request_count = 0
	var exception_count = 0
	
	def main(args: Array[String]): Unit = {
		
		val host = args(0)
		val minKey = args(1).toInt
		val maxKey = args(2).toInt
		(minKey until maxKey).foreach(currentKey=> {
			key.f1 = currentKey
			val pr = new PutRequest
			pr.namespace = namespace
			pr.key = key.toBytes
			pr.value = "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx".getBytes

			// create request actor
			val request = new ScadsWarmerActor(RemoteNode(host,9991),pr)
			request.start // actually send request
		})
		logger.info("Done creating requests")
		while ((request_count+exception_count) < (maxKey-minKey)) { logger.info("Still waiting for responses"); Thread.sleep(500) }
		if (exception_count == 0) logger.info("Done warming with no exceptions")
		else logger.warn("Warming had exceptions!")
	}
	class ScadsWarmerActor(dest:RemoteNode, scads_req:Object) extends Actor {
		var starttime:Long = -1L; var startNano:Long = -1L
		var endtime:Long = -1L; var latency:Double = -1.0
		def act = {
			starttime = System.currentTimeMillis
			startNano = System.nanoTime
			val req = new Message
			req.body = scads_req
			req.src = new java.lang.Long(MessageHandler.registerActor(this))
			makeRequest(req)
		}
		def makeRequest(req:Message)(implicit mgr: StorageActorProxy):Object = {
			// send the request
			mgr.sendMessage(dest, req) // go to only first node

			// wait for response
			reactWithin(10000) {
				case (RemoteNode(hostname, port), msg: Message) => msg.body match {
					case exp: ProcessingException => exception_count +=1
					case obj => {
						endtime = System.currentTimeMillis
						latency = (System.nanoTime-startNano)/1000000.0
						request_count += 1
						// log start_time, end_time, latency, hostname
						//request_info.put(starttime+","+endtime+","+latency+","+hostname+"\n")
					}
				}
				case TIMEOUT => exception_count +=1
				case msg => logger.warn("Unexpected message: " + msg)
			}
		}
	}
	
}

object PolicyRange {
	def createPartitions(startKey: Int, endKey: Int, numPartitions: Int):List[PolicyRange] = {
		val partitions = new scala.collection.mutable.ArrayStack[PolicyRange]()
		val numKeys = endKey - startKey + 1

		val last = (startKey to endKey by (numKeys/numPartitions)).toList.reduceLeft((s,e) => {
			partitions.push(PolicyRange(s,e))
			e
		})
		partitions.push(PolicyRange(last,(numKeys + startKey)))

		return partitions.toList.reverse
	}
}

case class PolicyRange(val minKey:Int, val maxKey:Int) {
	def contains(needle:Int) = (needle >= minKey && needle < maxKey)
}

class RequestLogger(queue:java.util.concurrent.BlockingQueue[String]) extends Runnable {
	var running = true
	val sleep_time = 30*1000
	var lastFlush = System.currentTimeMillis
	val logfile = new java.io.BufferedWriter(new java.io.FileWriter("/tmp/requestlogs.csv"))
	
	def run = {
		while (running) {
			if (System.currentTimeMillis > lastFlush+sleep_time) {
				// flush stats so far to file
				logfile.flush
				lastFlush = System.currentTimeMillis
			}
			logfile.write(queue.take)
		}
	}
	def stop = {
		running = false
		logfile.flush
		logfile.close
	}
}

object RequestRunner {
	def main(args: Array[String]): Unit = {
		val numthreads = args(0).toInt
		val numSeconds = args(1).toInt

		val mapping = fileToMapping(args(2))
		val queue = new java.util.concurrent.ArrayBlockingQueue[String](100)
		val requestlogger = new RequestLogger(queue)

		val gens = (0 until numthreads).map(t=>new RequestGenerator(mapping,queue))
		val threads = gens.map(g => new Thread(g))
		val startTime = System.currentTimeMillis

		// start!
		(new Thread(requestlogger)).start
		threads.foreach(t=> t.start)

		while (true) {
			if (System.currentTimeMillis >= startTime+(numSeconds*1000)) {
				requestlogger.stop
				gens.foreach(g=> {g.stop; println(g.total_request_gen_time+","+g.request_count+","+g.exception_count)})
			}
			Thread.sleep(1*1000)
		}
	}
	def fileToMapping(filename:String):Map[PolicyRange,RemoteNode] = {
		null //TODO
	}
}

class RequestGenerator(mapping: Map[PolicyRange,RemoteNode], request_info:java.util.concurrent.BlockingQueue[String]) extends Runnable {
	val logger = Logger.getLogger("scads.requestgen")
	
	implicit val proxy = new StorageActorProxy
	var running = true
	val rnd = new java.util.Random
	
	var startr = System.nanoTime
	var request_gen_time = System.nanoTime
	var before_send = System.nanoTime
	
	var request_count = 0
	var exception_count = 0
	var total_request_gen_time:Long = 0L // nanosec
	
	// this stuff should be replaced with real request generator
	val key = new IntRec
	val namespace = "perfTest256"
	val ranges = Array[PolicyRange](mapping.toList.map(e=>e._1):_*)
	var minKey = ranges.foldLeft(0)((out,entry)=>{if(entry.minKey<out) entry.minKey else out})
	var maxKey = ranges.foldLeft(0)((out,entry)=>{if(entry.maxKey>out) entry.maxKey else out})
	var wait_time = 5 // ms before sending another request
	
	class ScadsRequestActor(dest:List[RemoteNode], scads_req:Object,log:Boolean) extends Actor {
		var starttime:Long = -1L; var startNano:Long = -1L
		var endtime:Long = -1L; var latency:Double = -1.0
		def act = {
			starttime = System.currentTimeMillis
			startNano = System.nanoTime
			val req = new Message
			req.body = scads_req // get or put request
			req.src = new java.lang.Long(MessageHandler.registerActor(this))
			makeRequest(req)
		}
		def makeRequest(req:Message)(implicit mgr: StorageActorProxy):Object = {
			// send the request
			mgr.sendMessage(dest(0), req) // go to only first node

			// wait for response
			reactWithin(10000) {
				case (RemoteNode(hostname, port), msg: Message) => msg.body match {
					case exp: ProcessingException => exception_count +=1
					case obj => {
						endtime = System.currentTimeMillis
						latency = (System.nanoTime-startNano)/1000000.0
						request_count += 1
						// log start_time, end_time, latency, hostname
						if (log) request_info.put(starttime+","+endtime+","+latency+","+hostname+"\n")
					}
				}
				case TIMEOUT => exception_count +=1
				case msg => logger.warn("Unexpected message: " + msg)
			}
		}
	}
	
	def run = {
		while (running) {
			startr = System.nanoTime
			val request = generateRequest
			request_gen_time = System.nanoTime
			total_request_gen_time += (request_gen_time-startr)
			request.start // actually send request
			Thread.sleep(wait_time)
		}
	}
	def stop = { running = false }
	private def generateRequest():ScadsRequestActor = {
		// pick a key
		key.f1 = rnd.nextInt(maxKey-minKey) + minKey
		// locate correct node for key
		val nodes = locate(key.f1)
		// pick and create type of request
		val gr = new GetRequest
		gr.namespace = namespace
		gr.key = key.toBytes
		// create request actor
		new ScadsRequestActor(nodes,gr,(rnd.nextDouble<0.02))
		
	}
	private def locate(needle:Int):List[RemoteNode] = {
		mapping.filter(e=>e._1.contains(needle)).toList.map(n=>n._2)//(0)._2 // pick first one, and get node
	}
}

