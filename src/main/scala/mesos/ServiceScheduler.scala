package deploylib
package mesos

import _root_.mesos._
import edu.berkeley.cs.scads.comm._

import java.io.File
import net.lag.logging.Logger

import scala.collection.mutable.{Buffer, ListBuffer}
import scala.collection.JavaConversions._

object LocalExperimentScheduler {
  System.loadLibrary("mesos")

  def apply(name: String, mesosMaster: String = "1@" + java.net.InetAddress.getLocalHost.getHostAddress + ":5050") = new LocalExperimentScheduler(name, mesosMaster)
}

case class Experiment(var processes: Seq[JvmProcess])

abstract trait ExperimentScheduler {
  def scheduleExperiment(processes: Seq[JvmProcess]): Unit
}

class LocalExperimentScheduler protected (name: String, mesosMaster: String) extends Scheduler with ExperimentScheduler{
  val logger = Logger()
  var taskId = 0
  var driver = new MesosSchedulerDriver(this, mesosMaster)

  var outstandingExperiments = new java.util.concurrent.ConcurrentLinkedQueue[Experiment]
  var awaitingSiblings = List[JvmProcess]()

  val driverThread = new Thread("ExperimentScheduler Mesos Driver Thread") { override def run(): Unit = driver.run() }
  driverThread.start()

  def scheduleExperiment(processes: Seq[JvmProcess]): Unit = synchronized {
    outstandingExperiments.add(new Experiment(processes))
  }

  override def getFrameworkName(d: SchedulerDriver): String = "SCADS Service Framework: " + name
  override def getExecutorInfo(d: SchedulerDriver): ExecutorInfo = new ExecutorInfo("/root/mesos/frameworks/deploylib/java_executor", Array[Byte]())
  override def registered(d: SchedulerDriver, fid: String): Unit = logger.info("Registered SCADS Framework.  Fid: " + fid)

  override def resourceOffer(d: SchedulerDriver, oid: String, offers: java.util.List[SlaveOffer]) = awaitingSiblings.synchronized {
    val tasks = new java.util.LinkedList[TaskDescription]

    while(offers.size > 0 && outstandingExperiments.peek() != null) {
      val currentExperiment = outstandingExperiments.peek()
      val scheduleNow = currentExperiment.processes.take(offers.size)
      scheduleNow.take(offers.size).foreach(proc => {
        val offer = offers.remove(0)
        val taskParams = Map(List("mem", "cpus").map(k => k -> offer.getParams.get(k)):_*)
        val task = new TaskDescription(taskId, offer.getSlaveId, proc.mainclass, taskParams, proc.toBytes)
        logger.debug("Scheduling task %d: %s", taskId, proc)
        taskId += 1
        tasks.add(task)

        awaitingSiblings ::= proc
      })

      currentExperiment.processes = currentExperiment.processes.drop(scheduleNow.size)
      if(currentExperiment.processes.size == 0) {
        outstandingExperiments.poll()
        logger.info("Experiment Scheduled. Size: %d", awaitingSiblings.size)
        awaitingSiblings = List[JvmProcess]()
      }
      else {
        logger.info("Scheduled %d of %d processes", awaitingSiblings.size, awaitingSiblings.size + currentExperiment.processes.size)
      }
    }

    d.replyToOffer(oid, tasks, Map[String,String]())
  }

  override def statusUpdate(d: SchedulerDriver, status: TaskStatus): Unit = {
    if(status.getState == TaskState.TASK_FAILED || status.getState == TaskState.TASK_LOST) {
      logger.warning("Status Update for Task %d: %s", status.getTaskId, status.getState)
      logger.ifWarning(new String(status.getData))
    }
    else {
      logger.info("Status Update: " + status.getTaskId + " " + status.getState)
      logger.ifDebug(new String(status.getData))
    }
  }

  def stopDriver = driver.stop
}
