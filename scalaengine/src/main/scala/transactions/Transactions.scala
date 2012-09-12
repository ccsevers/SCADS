package edu.berkeley.cs.scads.storage.transactions

import conflict.ConflictResolver
import edu.berkeley.cs.scads.storage._
import edu.berkeley.cs.scads.storage.client.index._
import edu.berkeley.cs.avro.marker._
import edu.berkeley.cs.scads.comm._

import mdcc._
import org.apache.avro._
import org.apache.avro.specific._
import org.apache.avro.generic._

import org.apache.zookeeper._
import java.util.concurrent.TimeUnit

trait TransactionI {
  def getDefaultMeta(key: Array[Byte]) : MDCCMetadata
  def keySchema: Schema
  def valueSchema: Schema
  def getConflictResolver: ConflictResolver
  def recordCache: MDCCClientServer
  def serversForKey(key: Array[Byte]): Seq[PartitionService]
}

// This works with SpecificNamespace
trait Transactions[K <: SpecificRecord, V <: SpecificRecord]
extends RangeKeyValueStore[K, V]
with TransactionsBase {

  implicit protected def valueManifest: Manifest[V]
  val icUtil = new FieldICUtil[V]
  def getFieldICList = icUtil.getFieldICList

  def putLogical(key: K, value: V): Unit = {
    putBytesLogical(keyToBytes(key), valueToBytes(value))
  }

  override def put(key: K, value: Option[V]): Unit = {
    putBytes(keyToBytes(key), value.map(v => valueToBytes(v)))
  }

  override def get(key: K): Option[V] = {
    getBytes(keyToBytes(key)).map(b =>
      // Don't return a record which was deleted
      if (b.length == 0)
        return None
      else
        return Some(bytesToValue(b))
    )
  }

  override def getRange(start: Option[K],
                        end: Option[K],
                        limit: Option[Int] = None,
                        offset: Option[Int] = None,
                        ascending: Boolean = true) = {
    // Filter out records which were deleted
    getKeys(start.map(prefix => fillOutKey(prefix, newKeyInstance _)(minVal)).map(keyToBytes), end.map(prefix => fillOutKey(prefix, newKeyInstance _)(maxVal)).map(keyToBytes), limit, offset, ascending).filter {
      case(k,v) => v.length > 0
    } map { 
      case (k,v) => bytesToBulk(k, v) 
    }
  }
}

// This works with PairNamespace.
trait PairTransactions[R <: AvroPair]
extends RecordStore[R]
with client.index.IndexManager[R]
with TransactionsBase {

  implicit protected def pairManifest: Manifest[R]
  val icUtil = new FieldICUtil[R]
  def getFieldICList = icUtil.getFieldICList

  // TODO: implement Pair specific functionality.
  // put(Pair) uses put(), which uses putBytes.
  // delete(Pair) uses put(), which uses putBytes.

  def putLogical(pairRec: R): Unit = {
    putBytesLogical(keyToBytes(pairRec.key), valueToBytes(pairRec.value))
  }

  override def getRecord(key: IndexedRecord): Option[R] = {
    val keyBytes = keyToBytes(key)

    getBytes(keyBytes).map(b =>
      // Don't return a record which was deleted
      if (b.length == 0)
        return None
      else
        return Some(bytesToBulk(keyBytes, b))
    )
  }

  override def getRange(start: Option[IndexedRecord],
                        end: Option[IndexedRecord],
                        limit: Option[Int] = None,
                        offset: Option[Int] = None,
                        ascending: Boolean = true) = {
    // Filter out records which were deleted
    getKeys(start.map(prefix => fillOutKey(prefix, newKeyInstance _)(minVal)).map(keyToBytes), end.map(prefix => fillOutKey(prefix, newKeyInstance _)(maxVal)).map(keyToBytes), limit, offset, ascending).filter {
      case(k,v) => v.length > 0
    } map {
      case (k,v) => bytesToBulk(k, v)
    }
  }

  private val protType2 = protocolType

  override def newIndexNamespace(name: String,
                                 cluster: ScadsCluster,
                                 root: ZooKeeperProxy#ZooKeeperNode,
                                 keySchema: Schema): IndexNamespace = {
    new IndexNamespace(name, cluster, root, keySchema) with IndexTransactions {
      val localProtType = protType2
      override lazy val protocolType = localProtType
    }
  }

}

// This works with IndexNamespace.
trait IndexTransactions
extends RecordStore[IndexedRecord]
with TransactionsBase {

  def getFieldICList = FieldICList(List())

  // TODO: implement Pair specific functionality.
  // put(Pair) uses put(), which uses putBytes.
  // delete(Pair) uses put(), which uses putBytes.

  override def getRecord(key: IndexedRecord): Option[IndexedRecord] = {
    val keyBytes = keyToBytes(key)

    getBytes(keyBytes).map(b =>
      // Don't return a record which was deleted
      if (b.length == 0)
        return None
      else
        return Some(bytesToBulk(keyBytes, b))
    )
  }

  override def getRange(start: Option[IndexedRecord],
                        end: Option[IndexedRecord],
                        limit: Option[Int] = None,
                        offset: Option[Int] = None,
                        ascending: Boolean = true) = {
    // Filter out records which were deleted
    getKeys(start.map(prefix => fillOutKey(prefix, newKeyInstance _)(minVal)).map(keyToBytes), end.map(prefix => fillOutKey(prefix, newKeyInstance _)(maxVal)).map(keyToBytes), limit, offset, ascending).filter {
      case(k,v) => v.length > 0
    } map {
      case (k,v) => bytesToBulk(k, v)
    }
  }
}

// This is the base trait with all the shared functionality between different
// types of namespaces.
trait TransactionsBase
extends QuorumRangeProtocol
with KeyRoutable
with ZooKeeperGlobalMetadata
with TransactionRecordMetadata
with TransactionI {

  def getFieldICList: FieldICList

  lazy val defaultMeta = MDCCMetaDefault.getDefault(nsRoot)
  lazy val conflictResolver = new ConflictResolver(valueSchema, getFieldICList)
  lazy val recordCache = new MDCCClientServer(this)

  def getConflictResolver = conflictResolver

  def getDefaultMeta(key: Array[Byte]) = defaultMeta.defaultMetaData(key)

  // TODO: Don't know why implicit manifest did not work.
  lazy val protocolType: NSTxProtocol = NSTxProtocolNone()

  override def getTxProtocolType():String = protocolType match {
    case NSTxProtocolNone() => ""
    case NSTxProtocol2pc() => "2PC"
    case NSTxProtocolMDCC() => "MDCC"
  }

  override val timeoutZooKeeper = 2 * 60 * 1000
  override val timeoutCreatePartition = 2 * 60 * 1000

  override def initRootAdditional(node: ZooKeeperProxy#ZooKeeperNode): Unit = {
    // Write the integrity constraints to zookeeper.
    val writer = new AvroSpecificReaderWriter[FieldICList](None)
    val icBytes = writer.serialize(getFieldICList)
    root.getOrCreate(name).createChild("valueICs", icBytes,
                                       CreateMode.PERSISTENT)
  }

  // Add the namespace protocol to the map.
  def addNSProtocol = {
    ThreadLocalStorage.protocolMap.value match {
      case Some(protocolMap) => {
        protocolMap.addNamespaceProtocol(name, protocolType)
      }
      case _ =>
    }
  }

  override def putBytes(key: Array[Byte], value: Option[Array[Byte]]): Unit = {
    val servers = serversForKey(key)
    ThreadLocalStorage.updateList.value match {
      case None => {
        // TODO: what does it mean to do puts outside of a tx?
        //       for now, just writes to all servers
        val putRequest = PutRequest(key,
                                    Some(MDCCRecordUtil.toBytes(value, getDefaultMeta(key))))
        val responses = servers.map(_ !! putRequest)
        responses.blockFor(servers.length, 5000, TimeUnit.MILLISECONDS)
      }
      case Some(updateList) => {
        updateList.appendValueUpdateInfo(this, servers, key, value)
      }
    }
    addNSProtocol
  }

  def putBytesLogical(key: Array[Byte],
                      value: Array[Byte]): Unit = {
    val servers = serversForKey(key)
    ThreadLocalStorage.updateList.value match {
      case None => {
        // TODO: what does it mean to do puts outside of a tx?
        //       for now, do nothing...
        throw new RuntimeException("")
      }
      case Some(updateList) => {
        updateList.appendLogicalUpdate(this, servers, key, Some(value))
      }
    }
    addNSProtocol
  }

  override def asyncPutBytes(key: Array[Byte], value: Option[Array[Byte]]): ScadsFuture[Unit] = {
    val servers = serversForKey(key)
    addNSProtocol
    ThreadLocalStorage.updateList.value match {
      case None => {
        // TODO: what does it mean to do puts outside of a tx?
        //       for now, just writes to all servers
        val putRequest = PutRequest(key,
                                    Some(MDCCRecordUtil.toBytes(value, getDefaultMeta(key))))
        val responses = servers.map(_ !! putRequest)
        new ComputationFuture[Unit] {
          def compute(timeoutHint: Long, unit: TimeUnit) = {
            responses.blockFor(servers.length)
          }
          def cancelComputation = sys.error("NOT IMPLEMENTED")
        }
      }
      case Some(updateList) => {
        updateList.appendValueUpdateInfo(this, servers, key, value)
        // This future does nothing, since the protocol will work later.
        new ComputationFuture[Unit] {
          def compute(timeoutHint: Long, unit: TimeUnit) = {}
          def cancelComputation = sys.error("NOT IMPLEMENTED")
        }
      }
    }
  }

  private def getReadQuorumSize(numServers: Int): Int = {
    ThreadLocalStorage.readConsistency.value match {
      case ReadLocal() => 1
      case ReadCustom(n) => scala.math.min(n, numServers)
      case ReadConsistent() => scala.math.ceil(numServers * 0.501).toInt
      case ReadAll() => numServers
    }
  }

  override def getBytes(key: Array[Byte]): Option[Array[Byte]] = {
    val servers = serversForKey(key)
    val getRequest = GetRequest(key)
    val responses = servers.map(_ !! getRequest)
    val handler = new GetHandlerTmp(key, responses)
    val record = handler.vote(getReadQuorumSize(servers.length))
    if (handler.failed) {
      None
    } else {
      record match {
        case None => None
        case Some(bytes) => {
          val mdccRec = MDCCRecordUtil.fromBytes(bytes)
          ThreadLocalStorage.txReadList.value.map(readList =>
            readList.addRecord(key, mdccRec, bytes))
          mdccRec.value
        }
      }
    }
  }

  override def processRangeRecord(key: Array[Byte], value: Array[Byte]) = {
    val mdccRec = MDCCRecordUtil.fromBytes(value)
    ThreadLocalStorage.txReadList.value.map(readList =>
      readList.addRecord(key, mdccRec, value))
    val realValue = mdccRec.value match {
      // Deleted records have zero length byte arrays
      case None => new Array[Byte](0)
      case Some(v) => v
    }
    (key, realValue)
  }

  // putBulkBytes calls createMetadata, so the records will have the
  // default metadata.

  // TODO: does get range need to collect ALL metatdata as well?
  //       could potentially be a very large list, and complicates code.

  // This is just modified from the quorum protocol...
  class GetHandlerTmp(val key: Array[Byte], val futures: Seq[MessageFuture[StorageMessage]], val timeout: Long = 5000) {
    private val responses = new java.util.concurrent.LinkedBlockingQueue[MessageFuture[StorageMessage]]
    futures.foreach(_.forward(responses))
    var winnerValue: Option[Array[Byte]] = None
    private var ctr = 0
    val timeoutCounter = new TimeoutCounter(timeout)
    var failed = false

    def vote(quorum: Int): Option[Array[Byte]] = {
      (1 to quorum).foreach(_ => compareNext())
      return winnerValue
    }

    private def compareNext(): Unit = {
      ctr += 1
      val future = responses.poll(timeoutCounter.remaining, TimeUnit.MILLISECONDS)
      if (future == null) {
        failed = true
        return
      }
      future() match {
        case GetResponse(v) => {
          val cmp = optCompareMetadataTmp(winnerValue, v)
          if (cmp > 0) {
          } else if (cmp < 0) {
            winnerValue = v
          }else {
          }
        }
        case m => throw new RuntimeException("Unknown message " + m)
      }
    }
  }

  // This is just modified from the quorum protocol...
  protected def optCompareMetadataTmp(optLhs: Option[Array[Byte]], optRhs: Option[Array[Byte]]): Int = (optLhs, optRhs) match {
    case (None, None) => 0
    case (None, Some(_)) => -1
    case (Some(_), None) => 1
    case (Some(lhs), Some(rhs)) => compareMetadata(lhs, rhs)
  }

}

trait TransactionRecordMetadata extends SimpleRecordMetadata with TransactionI {
  override def createMetadata(key: Array[Byte], rec: Array[Byte]): Array[Byte] = {
    MDCCRecordUtil.toBytes(Some(rec), getDefaultMeta(key))
  }

  override def compareMetadata(lhs: Array[Byte], rhs: Array[Byte]): Int = {
    MDCCRecordUtil.fromBytes(lhs).metadata.currentVersion.compare(MDCCRecordUtil.fromBytes(rhs).metadata.currentVersion)
  }

  override def extractMetadataAndRecordFromValue(value: Array[Byte]): (Array[Byte], Array[Byte]) = {
    // TODO: This doesn't really make sense and nothing uses this so far...
    (new Array[Byte](0), new Array[Byte](0))
  }

  override def extractRecordFromValue(value: Array[Byte]): Array[Byte] = {
    val txRec = MDCCRecordUtil.fromBytes(value)
    txRec.value match {
      // Deleted records have zero length byte arrays
      case None => new Array[Byte](0)
      case Some(v) => v
    }
  }
}
