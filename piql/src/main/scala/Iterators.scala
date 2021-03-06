package edu.berkeley.cs.scads
package piql
package exec

import plans._

import net.lag.logging.Logger
import org.apache.avro.util.Utf8
import org.apache.avro.generic._

import comm.ScadsFuture

import java.{util => ju}
import scala.collection.mutable.Queue
import scala.collection.mutable.ArrayBuffer

/**
 * The contex for a query plan to be executed in.
 * @param parameters - any runtime parameters for the query
 * @param state - the serialized state if resuming the query from a previous execution.
 */
case class Context(parameters: IndexedSeq[Any], state: Option[List[Any]])

abstract class QueryIterator extends Iterator[Tuple] {
  val name: String

  def open: Unit

  def close: Unit
}

/**
 * A query executor takes a physical query plan and produces and iterator tree that will execute the query with
 * a given strategy.
 */
trait QueryExecutor {
  protected val logger = Logger("edu.berkeley.cs.scads.piql.QueryExecutor")

  def apply(plan: QueryPlan, args: Any*): QueryIterator = apply(plan)(Context(args.toIndexedSeq, None))

  def apply(plan: QueryPlan)(implicit ctx: Context): QueryIterator

  final protected def bindValue(value: Value, currentTuple: Tuple)(implicit ctx: Context): Any = {
    logger.debug("BoundValue: %s %s %s", value, currentTuple, ctx)
    value match {
      case ConstantValue(v) => v
      case ParameterValue(o) => ctx.parameters(o)
      case AttributeValue(recPos, fieldPos) => currentTuple(recPos).get(fieldPos)
    }
  }

  final protected def bindKey(ns: TupleProvider, key: KeyGenerator, currentTuple: Tuple = null)(implicit ctx: Context): Key = {
    val boundKey = new GenericData.Record(ns.keySchema)

    key.map(bindValue(_, currentTuple)).zipWithIndex.foreach {
      case (value: Any, idx: Int) => boundKey.put(idx, value)
    }
    logger.debug("Bound Key: %s", boundKey)
    boundKey
  }

  final protected def bindLimit(limit: Limit)(implicit ctx: Context): Int = limit match {
    case FixedLimit(l) => l
    case ParameterLimit(lim, max) => {
      val limitValue = ctx.parameters(lim).asInstanceOf[Int]
      //if(limitValue > max)
      //  throw new RuntimeException("Limit out of range")
      limitValue
    }
  }

  final protected def evalPredicate(predicate: Predicate, tuple: Tuple)(implicit ctx: Context): Boolean = predicate match {
    case EqualityPredicate(v1, v2) => {
      compareAny(bindValue(v1, tuple), bindValue(v2, tuple)) == 0
    }
    case InPredicate(v1, v2) => {
      val valueList = bindValue(v2, tuple).asInstanceOf[Seq[Any]]
      val boundValue = bindValue(v1, tuple)
      valueList.foreach(v => if (compareAny(v, boundValue) == 0) return true)
      return false
    }
  }

  final protected def compareTuples(left: Tuple, right: Tuple, attributes: Seq[Value])(implicit ctx: Context): Int = {
    attributes.foreach(a => {
      val leftValue = bindValue(a, left)
      val rightValue = bindValue(a, right)
      val comparison = compareAny(leftValue, rightValue)
      if (comparison != 0)
        return comparison
    })
    return 0
  }

  final protected def compareAny(left: Any, right: Any): Int = (left, right) match {
    case (l: Integer, r: Integer) => l.intValue - r.intValue
    case (l: Utf8, r: Utf8) => l.toString compare r.toString
    case (l: String, r: Utf8) => l compare r.toString
    case (l: Utf8, r: String) => l.toString compare r
    case (true, true) => 0
    case (false, true) => -1
    case (true, false) => 1
  }
}

/**
 * The simple executor issues requests to the key/value store serially.
 */
class SimpleExecutor extends QueryExecutor {

  implicit def toOption[A](a: A): Option[A] = Option(a)

  def apply(plan: QueryPlan)(implicit ctx: Context): QueryIterator = plan match {
    case IndexLookup(namespace, key) => {
      new QueryIterator {
        val name = "SimpleIndexLookup"
        private val boundKey = bindKey(namespace, key)
        private var result: Option[Record] = None

        def open: Unit =
          result = namespace.provider.getRecord(boundKey)

        def close: Unit =
          result = None

        def hasNext = result.isDefined

        def next = {
          val tuple = ArrayBuffer(result.getOrElse(throw new ju.NoSuchElementException("Next on empty iterator")))
          result = None
          tuple
        }
      }
    }
    case IndexScan(namespace, keyPrefix, limit, ascending) => {
      new QueryIterator {
        val name = "SimpleIndexScan"
        private val boundKeyPrefix = bindKey(namespace, keyPrefix)
        private var result: Seq[Record] = Nil
        private var pos = 0
        private var offset = 0
        private var limitReached = false
        private val boundLimit = bindLimit(limit)

        @inline private def doFetch() {
          logger.debug("BoundKeyPrefix: %s", boundKeyPrefix)
          logger.debug("Fetch from namespace: " + namespace)
          logger.debug("Namespace provider: " + namespace.provider)
          result = namespace.provider.getRange(boundKeyPrefix, boundKeyPrefix, offset = offset, limit = boundLimit, ascending = ascending)
          logger.debug("IndexScan Prefetch Returned %s, with offset %d, limit %d", result, offset, boundLimit)
          offset += result.size
          pos = 0
          if (result.size < boundLimit)
            limitReached = true
        }

        def open: Unit = doFetch()

        def close: Unit =
          result = Nil

        def hasNext =
          if (pos < result.size) true
          else if (limitReached) false
          else {
            // need to fetch more from KV store to see if we really have more
            doFetch()
            hasNext
          }

        def next = {
          if (!hasNext)
            throw new ju.NoSuchElementException("Next on empty iterator")

          val tuple = ArrayBuffer(result(pos))
          pos += 1
          tuple
        }
      }
    }
    case IndexLookupJoin(namespace, key, child) => {
      new QueryIterator {
        val name = "SimpleIndexLookupJoin"
        private val childIterator = apply(child)
        private var nextTuple: Tuple = null

        def open = {
          childIterator.open; getNext
        }

        def close = childIterator.close

        def hasNext = (nextTuple != null)

        def next = {
          val ret = nextTuple
          getNext
          ret
        }

        private def getNext: Unit = {
          while (childIterator.hasNext) {
            val childTuple = childIterator.next
            val boundKey = bindKey(namespace, key, childTuple)
            val value = namespace.provider.getRecord(boundKey)

            if (value.isDefined) {
              nextTuple = childTuple ++ Array[Record](value.get) //TODO: Why does it return the boundKey???
              return
            }
          }
          nextTuple = null
        }
      }
    }
    case IndexMergeJoin(namespace, keyPrefix, sortFields, limit, ascending, child) => {
      // TODO: Unifty this iterator and ParallelIndexMergeJoin, since a lot of
      // code is repeated
      new QueryIterator {
        val name = "SimpleIndexMergeJoin"
        private val childIterator = apply(child)
        private val boundLimit = bindLimit(limit)

        /**(key, child tup, offset, limit reached?) */
        var tupleData: Array[(Record, Tuple, Int, Boolean)] = null

        var tupleBuffers: Array[IndexedSeq[Tuple]] = null

        var bufferPos: Array[Int] = null

        var nextTuple: Tuple = null

        def open: Unit = {
          childIterator.open

          val tupleDatum = childIterator.map(childValue => {
            val boundKeyPrefix = bindKey(namespace, keyPrefix, childValue)
            val records = namespace.provider.getRange(boundKeyPrefix, boundKeyPrefix, limit = boundLimit, ascending = ascending)
            logger.debug("IndexMergeJoin Prefetch Using Key %s: %s", boundKeyPrefix, records)

            val recIdxSeq = records.map(r => childValue :+ r).toIndexedSeq
            (boundKeyPrefix, childValue, records.size, records.size < boundLimit, recIdxSeq)
          }).toSeq

          tupleData = tupleDatum.map(x => (x._1, x._2, x._3, x._4)).toArray

          tupleBuffers = tupleDatum.map(_._5).toArray
          bufferPos = Array.fill(tupleBuffers.size)(0)

          getNext // load the first result
        }

        def close: Unit = childIterator.close

        def hasNext = (nextTuple != null)

        def next = {
          if (!hasNext)
            throw new ju.NoSuchElementException("Next on empty iterator")
          val ret = nextTuple
          getNext
          ret
        }

        private def fillBuffer(i: Int) {
          val (key, tup, offset, limitReached) = tupleData(i)
          assert(!limitReached)
          val records = namespace.provider.getRange(key, key, offset = offset, limit = boundLimit, ascending = ascending)
          logger.debug("IndexMergeJoin Prefetch Using Key %s: %s", key, records)
          tupleBuffers(i) ++= records.map(r => tup :+ r).toIndexedSeq
          tupleData(i) = ((key, tup, offset + records.size, records.size < boundLimit))
        }

        private def bufferLimitReached(i: Int) =
          tupleData(i)._4

        private def getNext: Unit = {

          // find the first available buffer
          var minIdx = -1
          var idx = 0
          while (minIdx == -1 && idx < tupleBuffers.size) {

            // if this buffer has already been scanned over but we can still fetch from KV store
            if (bufferPos(idx) == tupleBuffers(idx).size && !bufferLimitReached(idx)) {
              fillBuffer(idx) // do the fetch
            }

            // if there is a buffer with contents, then we've found the start
            if (bufferPos(idx) < tupleBuffers(idx).size) {
              minIdx = idx
            }
            idx += 1
          }

          if (minIdx == -1) nextTuple = null
          else {
            for (i <- ((minIdx + 1) to (tupleBuffers.size - 1))) {
              if (bufferPos(i) == tupleBuffers(i).size && !bufferLimitReached(i)) {
                fillBuffer(i)
              }

              if (bufferPos(i) < tupleBuffers(i).size) {
                if ((ascending && (compareTuples(tupleBuffers(i)(bufferPos(i)), tupleBuffers(minIdx)(bufferPos(minIdx)), sortFields) < 0)) ||
                  (!ascending && (compareTuples(tupleBuffers(i)(bufferPos(i)), tupleBuffers(minIdx)(bufferPos(minIdx)), sortFields) > 0))) {
                  minIdx = i
                }
              }
            }
            nextTuple = tupleBuffers(minIdx)(bufferPos(minIdx))
            bufferPos(minIdx) += 1
            // NO prefetching here if minIdx buffer becomes entirely scanned-
            // this is unlike ParallelIndexMergeJoin which does a prefetch for
            // this case
          }
        }
      }
    }

    case LocalSelection(predicate, child) => {
      new QueryIterator {
        val name = "Selection"
        private val childIterator = apply(child)
        private var nextTuple: Tuple = null

        def open = {
          childIterator.open; getNext
        }

        def close = childIterator.close

        def hasNext = (nextTuple != null)

        def next = {
          val ret = nextTuple
          getNext
          ret
        }

        private def getNext: Unit = {
          while (childIterator.hasNext) {
            val childValue = childIterator.next
            if (evalPredicate(predicate, childValue)) {
              nextTuple = childValue
              return
            }
          }
          nextTuple = null
        }
      }
    }
    case LocalStopAfter(k, child) => {
      new QueryIterator {
        val name = "StopAfter"
        private val childIterator = apply(child)
        private val limit = bindLimit(k)
        private var taken = 0

        def open = {
          taken = 0; childIterator.open
        }

        def close = {
          childIterator.close
        }

        def hasNext = childIterator.hasNext && (limit > taken)

        def next = {
          taken += 1; childIterator.next
        }
      }
    }
    case LocalIterator(ordinal, wrap) => {
      new QueryIterator {
        val name = "LocalIterator"
        private var delegate: Iterator[Tuple] = null

        def open: Unit = {
          delegate =
            if (wrap) //This is kinda gross... I'm sorry.
              ctx.parameters(ordinal).asInstanceOf[Seq[Any]].map(i => Vector(storage.StringRec(i.asInstanceOf[String]))).toIterator
            else
              ctx.parameters(ordinal).asInstanceOf[Seq[Tuple]].toIterator
        }

        def close: Unit = delegate = null

        def hasNext = delegate.hasNext

        def next = delegate.next
      }
    }
    case Union(chil1, child2, eqField) => {
      throw new RuntimeException("Not yet implemented")
    }
  }
}

/**
 * The parallel executor issues all requests to the key/value store in parallel.
 * TODO: Should abstract out the common parts between the query iterators.
 */
class ParallelExecutor extends SimpleExecutor {

  override def apply(plan: QueryPlan)(implicit ctx: Context): QueryIterator = plan match {
    case IndexLookup(namespace, key) => {
      new QueryIterator {
        val name = "ParallelIndexLookup"
        private val boundKey = bindKey(namespace, key)
        private var ftch: Option[ScadsFuture[Option[Record]]] = None

        def open: Unit =
          ftch = Some(namespace.provider.asyncGetRecord(boundKey))

        def close: Unit =
          ftch = None

        def hasNext =
          ftch.flatMap(_.get).isDefined

        def next = {
          val tuple = ArrayBuffer(ftch.flatMap(_.get).getOrElse(throw new ju.NoSuchElementException("Empty iterator")))
          ftch = None
          tuple
        }
      }
    }
    case IndexScan(namespace, keyPrefix, limit, ascending) => {
      new QueryIterator {
        val name = "ParallelIndexScan"
        private val boundKeyPrefix = bindKey(namespace, keyPrefix)

        private var result: Seq[Record] = Nil
        private var ftch: ScadsFuture[Seq[Record]] = _

        private var pos = 0
        private var offset = 0
        private var limitReached = false
        private val boundLimit = bindLimit(limit) + 1 // should get rid of 2nd getRange
        private var ftchInvoked = false

        @inline private def doFetch() {
          logger.debug("BoundKeyPrefix: %s", boundKeyPrefix)
          ftch = namespace.provider.asyncGetRange(boundKeyPrefix, boundKeyPrefix, offset = offset, limit = boundLimit, ascending = ascending)
          ftchInvoked = false
        }

        @inline private def updateFuture() {
          result = ftch.get
          logger.debug("IndexScan Prefetch Returned %s, with offset %d, limit %d", result, offset, boundLimit)
          offset += result.size
          pos = 0
          if (result.size < boundLimit)
            limitReached = true
          ftchInvoked = true
        }

        def open: Unit = doFetch()

        def close: Unit = {
          result = Nil
          ftch = null
        }

        def hasNext =
          if (ftchInvoked) {
            // have we already blocked on ftch and stored the result in result?
            if (pos < result.size) true
            else if (limitReached) false
            else {
              // need to fetch more from KV store to see if we really have more
              doFetch()
              hasNext
            }
          } else {
            updateFuture()
            hasNext
          }

        def next = {
          if (!hasNext)
            throw new ju.NoSuchElementException("Next on empty iterator")

          val tuple = ArrayBuffer(result(pos))
          pos += 1
          tuple
        }
      }
    }
    case IndexLookupJoin(namespace, key, child) => {
      new QueryIterator {
        val name = "ParallelIndexLookupJoin"
        private val childIterator = apply(child)
        private var nextTuple: Tuple = null
        private val ftchs = new Queue[(Tuple, Record, ScadsFuture[Option[Record]])]
        private val windowSize = 10 // keep 10 outstanding ftchs at a time

        def open = {
          childIterator.open; fillFutures()
        }

        def close = childIterator.close

        def hasNext = (nextTuple != null) || ({
          var found = false
          while (!found && !ftchs.isEmpty) {
            val (childTuple, boundKey, ftch) = ftchs.dequeue()
            ftch.get match {
              case Some(recVal) =>
                nextTuple = childTuple ++ Array[Record](recVal)
                found = true // done
              case None => // keep going
            }
            if (ftchs.isEmpty && childIterator.hasNext) // need to get more records
              fillFutures()
          }
          found
        })

        def next = {
          if (!hasNext)
            throw new ju.NoSuchElementException("Next on empty iterator")
          val ret = nextTuple
          nextTuple = null
          fillFutures()
          ret
        }

        private def fillFutures() {
          while (childIterator.hasNext && ftchs.size < windowSize) {
            val childTuple = childIterator.next
            val boundKey = bindKey(namespace, key, childTuple)
            val valueFtch = namespace.provider.asyncGetRecord(boundKey)
            ftchs += ((childTuple, boundKey, valueFtch))
          }
        }
      }
    }
    case IndexMergeJoin(namespace, keyPrefix, sortFields, limit, ascending, child) => {
      new QueryIterator {
        val name = "ParallelIndexMergeJoin"
        private val childIterator = apply(child)
        private val boundLimit = bindLimit(limit)

        /**
         * (key, child tup, offset, limit reached?, outstanding ftch)
         */
        private var tupleData: Array[(Key, Tuple, Int, Boolean, ScadsFuture[Seq[Record]])] = null
        private var tupleBuffers: Array[IndexedSeq[Tuple]] = null
        private var bufferPos: Array[Int] = null
        private var nextTuple: Tuple = null

        def open: Unit = {
          childIterator.open

          tupleData = childIterator.map(childValue => {
            val boundKeyPrefix = bindKey(namespace, keyPrefix, childValue)
            val ftch = namespace.provider.asyncGetRange(boundKeyPrefix, boundKeyPrefix, limit = boundLimit, ascending = ascending)
            (boundKeyPrefix, childValue, 0, false, ftch)
          }).toArray

          tupleBuffers = Array.fill(tupleData.size)(IndexedSeq.empty)
          bufferPos = Array.fill(tupleBuffers.size)(0)
        }

        def close: Unit = childIterator.close

        def hasNext = (nextTuple != null) || ({

          // find the first available buffer
          var minIdx = -1
          var idx = 0
          while (minIdx == -1 && idx < tupleBuffers.size) {

            // if this buffer has already been scanned over but we can still fetch from KV store
            if (bufferPos(idx) == tupleBuffers(idx).size && !bufferLimitReached(idx)) {
              fillBuffer(idx) // do the fetch
            }

            // if there is a buffer with contents, then we've found the start
            if (bufferPos(idx) < tupleBuffers(idx).size) {
              minIdx = idx
            }
            idx += 1
          }

          if (minIdx == -1) false
          else {
            for (i <- ((minIdx + 1) to (tupleBuffers.size - 1))) {
              if (bufferPos(i) == tupleBuffers(i).size && !bufferLimitReached(i)) {
                fillBuffer(i)
              }

              if (bufferPos(i) < tupleBuffers(i).size) {
                if ((ascending && (compareTuples(tupleBuffers(i)(bufferPos(i)), tupleBuffers(minIdx)(bufferPos(minIdx)), sortFields) < 0)) ||
                  (!ascending && (compareTuples(tupleBuffers(i)(bufferPos(i)), tupleBuffers(minIdx)(bufferPos(minIdx)), sortFields) > 0))) {
                  minIdx = i
                }
              }
            }
            nextTuple = tupleBuffers(minIdx)(bufferPos(minIdx))
            bufferPos(minIdx) += 1

            // do another fetch if we reach the end of minIdx's buffer- this
            // is unlike SimpleIndexMergeJoin which does not do another fetch
            // here.
            if (bufferPos(minIdx) == tupleBuffers(minIdx).size && !bufferLimitReached(minIdx))
              fillBuffer(minIdx)

            true
          }
        })

        private def fillBuffer(i: Int) {

          val (key, tup, offset, limitReached, ftch) = tupleData(i)

          assert(!limitReached)
          assert(key != null && tup != null && ftch != null && offset != -1)

          val records = ftch.get
          logger.debug("IndexMergeJoin Prefetch Using Key %s: %s", key, records)

          // TODO: is it slow to ++= append to an IndexedSeq??
          tupleBuffers(i) ++= records.map(tup :+ _)

          if (records.size < boundLimit) {
            // end of records in KV store
            tupleData(i) = ((null, null, -1, true, null)) // sentinel values
          } else {
            // still can ask for more records
            //val newOffset = records.size + offset
            //val newFtch = namespace.provider.asyncGetRange(key, key, offset=newOffset, limit=boundLimit, ascending=ascending)
            //tupleData(i) = ((key, tup, newOffset, limitReached, newFtch))
          }
        }

        private def bufferLimitReached(i: Int) =
          tupleData(i)._4

        def next = {
          if (!hasNext)
            throw new ju.NoSuchElementException("Next on empty iterator")

          val ret = nextTuple
          nextTuple = null
          ret
        }

      }
    }

    case _ => super.apply(plan)
  }
}

/**
 * The lazy executor retrieves tuples from the key/value store on demand, one at a time.
 */
class LazyExecutor extends SimpleExecutor {

  override def apply(plan: QueryPlan)(implicit ctx: Context): QueryIterator = plan match {
    case IndexLookup(namespace, key) => {
      new QueryIterator {
        val name = "LazyIndexLookup"

        private val boundKey = bindKey(namespace, key)

        private var accessed = false
        private lazy val result = namespace.provider.getRecord(boundKey)

        def open {}

        def close {
          accessed = true
        }

        def hasNext = accessed match {
          case false => result.isDefined
          case true => false
        }

        def next = {
          if (!hasNext)
            throw new ju.NoSuchElementException("Next on empty iterator")
          accessed = true
          ArrayBuffer(result.get)
        }
      }
    }
    case IndexScan(namespace, keyPrefix, _, ascending) => {
      // we don't care about limit here
      new QueryIterator {
        val name = "LazyIndexScan"

        private val boundKeyPrefix = bindKey(namespace, keyPrefix)

        private var result: Record = null

        private var offset = 0
        private var limitReached = false

        @inline private def doFetch() {
          logger.debug("BoundKeyPrefix: %s", boundKeyPrefix)
          val res = namespace.provider.getRange(boundKeyPrefix, boundKeyPrefix, offset = offset, limit = 1, ascending = ascending)
          logger.debug("IndexScan Fetch Returned %s, with offset %d, limit %d", res, offset, 1)
          offset += res.size
          if (res.isEmpty) {
            limitReached = true
            result = null
          } else {
            result = res.head
          }
          assert(limitReached || (result ne null))
        }

        def open {}

        def close {
          result = null
          limitReached = true
        }

        def hasNext =
          if (result ne null) true
          else if (!limitReached) {
            doFetch()
            hasNext
          } else false

        def next = {
          if (!hasNext)
            throw new ju.NoSuchElementException("Next on empty iterator")
          val ret = ArrayBuffer(result)
          result = null
          ret
        }
      }
    }
    case IndexLookupJoin(namespace, key, child) => {
      new QueryIterator {
        val name = "LazyIndexLookupJoin"

        private val childIterator = apply(child)
        private var nextTuple: Tuple = null

        private var needsInit = true

        def open {}

        def close {
          if (needsInit) needsInit = false
          else childIterator.close
        }

        def hasNext = {
          if (needsInit) {
            needsInit = false
            getNext
          }
          (nextTuple != null)
        }

        def next = {
          val ret = nextTuple
          getNext
          ret
        }

        private def getNext: Unit = {
          while (childIterator.hasNext) {
            val childTuple = childIterator.next
            val boundKey = bindKey(namespace, key, childTuple)
            val value = namespace.provider.getRecord(boundKey)

            if (value.isDefined) {
              nextTuple = childTuple ++ Array[Record](value.get)
              return
            }
          }
          nextTuple = null
        }
      }
    }
    case IndexMergeJoin(namespace, keyPrefix, sortFields, _, ascending, child) => {
      new QueryIterator {
        val name = "LazyIndexMergeJoin"

        private val childIterator = apply(child)

        /**(key, child tup, offset, limit reached?) */
        private var tupleData: Array[(Record, Tuple, Int, Boolean)] = null

        private var tupleBuffers: Array[IndexedSeq[Tuple]] = null

        private var bufferPos: Array[Int] = null

        private var nextTuple: Tuple = null

        private var needsInit = true

        private def init() {
          childIterator.open

          val tupleDatum = childIterator.map(childValue => {
            val boundKeyPrefix = bindKey(namespace, keyPrefix, childValue)
            val records = namespace.provider.getRange(boundKeyPrefix, boundKeyPrefix, limit = 1, ascending = ascending)
            logger.debug("IndexMergeJoin Prefetch Using Key %s: %s", boundKeyPrefix, records)

            val recIdxSeq = records.map(childValue :+ _).toIndexedSeq
            (boundKeyPrefix, childValue, records.size, records.size < 1, recIdxSeq)
          }).toSeq

          tupleData = tupleDatum.map(x => (x._1, x._2, x._3, x._4)).toArray

          tupleBuffers = tupleDatum.map(_._5).toArray
          bufferPos = Array.fill(tupleBuffers.size)(0)

          getNext // load the first result
        }

        def open {}

        def close {
          childIterator.close
          needsInit = false
        }

        def hasNext = {
          if (needsInit) {
            init()
            needsInit = false
          }
          (nextTuple != null)
        }

        def next = {
          if (!hasNext)
            throw new ju.NoSuchElementException("Next on empty iterator")
          val ret = nextTuple
          getNext
          ret
        }

        private def fillBuffer(i: Int) {
          val (key, tup, offset, limitReached) = tupleData(i)
          assert(!limitReached)
          val records = namespace.provider.getRange(key, key, offset = offset, limit = 1, ascending = ascending)
          logger.debug("IndexMergeJoin Fetch Using Key %s: %s", key, records)
          tupleBuffers(i) ++= records.map(tup :+ _).toIndexedSeq
          tupleData(i) = ((key, tup, offset + records.size, records.size < 1))
        }

        private def bufferLimitReached(i: Int) =
          tupleData(i)._4

        private def getNext: Unit = {

          // find the first available buffer
          var minIdx = -1
          var idx = 0
          while (minIdx == -1 && idx < tupleBuffers.size) {

            // if this buffer has already been scanned over but we can still fetch from KV store
            if (bufferPos(idx) == tupleBuffers(idx).size && !bufferLimitReached(idx)) {
              fillBuffer(idx) // do the fetch
            }

            // if there is a buffer with contents, then we've found the start
            if (bufferPos(idx) < tupleBuffers(idx).size) {
              minIdx = idx
            }
            idx += 1
          }

          if (minIdx == -1) nextTuple = null
          else {
            for (i <- ((minIdx + 1) to (tupleBuffers.size - 1))) {
              if (bufferPos(i) == tupleBuffers(i).size && !bufferLimitReached(i)) {
                fillBuffer(i)
              }

              if (bufferPos(i) < tupleBuffers(i).size) {
                if ((ascending && (compareTuples(tupleBuffers(i)(bufferPos(i)), tupleBuffers(minIdx)(bufferPos(minIdx)), sortFields) < 0)) ||
                  (!ascending && (compareTuples(tupleBuffers(i)(bufferPos(i)), tupleBuffers(minIdx)(bufferPos(minIdx)), sortFields) > 0))) {
                  minIdx = i
                }
              }
            }
            nextTuple = tupleBuffers(minIdx)(bufferPos(minIdx))
            bufferPos(minIdx) += 1
            // NO prefetching here if minIdx buffer becomes entirely scanned-
            // this is unlike ParallelIndexMergeJoin which does a prefetch for
            // this case
          }
        }
      }
    }
    case _ => super.apply(plan)
  }

}
