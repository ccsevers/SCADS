package edu.berkeley.cs
package scads
package piql
package mviews

import net.lag.logging.Logger
import org.apache.avro.generic._

import opt._
import plans._
import comm._
import storage._
import storage.client.index._
import deploylib._
import java.util.concurrent.TimeUnit

/* unified interface to tag store */
abstract class TagClient(val cluster: ScadsCluster,
                         implicit val executor: QueryExecutor,
                         val limit: Int = 20) {
  def selectTags(tag1: String, tag2: String): Seq[String]
  def fastSelectTags(tag1: String, tag2: String): Any
  def addTag(item: String, tag: String): Tuple2[Long,Long]
  def removeTag(item: String, tag: String): Tuple2[Long,Long]
  def initBulk(itemTagPairs: Seq[Tuple2[String,String]])
  def clear()

  val tags = cluster.getNamespace[Tag]("tags")

  /* begin random test stuff for querydeltas */
  val posts = cluster.getNamespace[Post]("posts")
  val subs = cluster.getNamespace[Subscription]("subscr")
  val unopt2 =
    posts.as("p")
      .join(subs.as("s"))
      .where("p.topicId".a === "s.topicId".a)
      .where("s.userId".a === (0.?))
      .limit(limit)
      .select("p.text".a, "p.topicId".a)

  val unopt =
    tags.as("t1")
        .where("t1.word".a === (0.?))
        .join(tags.as("t2"))
        .where("t2.word".a === (1.?))
        .where("t1.item".a === "t2.item".a)
        .limit(limit)
        .select("t1.item".a)

  val simple =
    tags.where("item".a === (0.?))
        .limit(limit)

  val threeTagsUnopt =
    tags.as("t1")
        .where("t1.word".a === (0.?))
        .join(tags.as("t2"))
        .where("t2.word".a === (1.?))
        .join(tags.as("t3"))
        .where("t3.word".a === (2.?))
        .where("t1.item".a === "t2.item".a)
        .where("t2.item".a === "t3.item".a)
        .limit(limit)
        .select("t1.item".a)

//  val threeTags = threeTagsUnopt.toPiqlWithView("threeTags")

  // select all tags combinations for this item
  val dq =
    tags.as("t2")
        .join(tags.as("t3"))
        .where("t2.item".a === (0.?))
        .where("t2.item".a === "t3.item".a)
        .limit(limit)
        .select("t3.word".a, "t2.word".a, "t3.item".a)

  // select all tag combinations for this rewritten variant
  // TODO fix bug in algorithm (in paper?)
  val dq2 =
    tags.as("t1")
        .join(tags.as("t3"))
        .where("t1.item".a === (0.?))
        .where("t3.item".a === (0.?))
        .limit(limit)
        .select("t3.word".a, "t1.word".a, "t1.item".a)
  /* end random test stuff */


  // materialized pairs of tags, including the duplicate pair
  val mTagPairs = cluster.getNamespace[MTagPair]("mTagPairs")

  /* suitable for String String records */
  def tagToBytes(tag: String): Array[Byte] = {
    tags.keyToBytes(new Tag(tag, "foo"))
  }

  /* suitable for String String String records */
  def tupleToBytes(k: String, l: String): Array[Byte] = {
    mTagPairs.keyToBytes(new MTagPair(k, l, "foo"))
  }

  def all() = {
    tags.iterateOverRange(None, None).toList
  }

  def count() = {
    tags.iterateOverRange(None, None).size
  }

  val selectItemQuery =
    tags.where("item".a === (0.?))
        .limit(limit)
        .toPiql("selectItemQuery")
  
  def selectItem(item: String): List[String] = {
    var tags = List[String]()
    for (arr <- selectItemQuery(item)) {
      arr.head match {
        case m =>
          tags ::= m.get(1).toString
      }
    }
    tags
  }
}

/* uses join for tag intersection query */
class NaiveTagClient(clus: ScadsCluster, exec: QueryExecutor)
      extends TagClient(clus, exec) {

  protected val logger = Logger("edu.berkeley.cs.scads.piql.mviews.NaiveTagClient")

  val twoTagsPiqlWithView =
    tags.as("t1")
        .where("t1.word".a === (0.?))
        .join(tags.as("t2"))
        .where("t2.word".a === (1.?))
        .where("t1.item".a === "t2.item".a)
        .limit(limit)
        .select("t1.item".a)
        .toPiqlWithView("twoTags")

  val twoTagsPiqlNoView =
    tags.as("t1")
        .where("t1.word".a === (0.?))
        .dataLimit(1024) // arbitrary false promise
        .join(tags.as("t2"))
        .where("t2.word".a === (1.?))
        .where("t1.item".a === "t2.item".a)
        .limit(limit)
        .select("t1.item".a)
        .toPiql("twoTagsNoView")

  def selectTags(tag1: String, tag2: String) = {
    twoTagsPiqlWithView(tag1, tag2).map(
      arr => arr.head match {
        case m => m.get(0).toString
      })
  }

  def fastSelectTags(tag1: String, tag2: String) = {
    twoTagsPiqlWithView(tag1, tag2)
  }

  def addTag(item: String, tag: String) = {
    val start = System.nanoTime / 1000
    tags.put(new Tag(tag, item))
    (System.nanoTime / 1000 - start, -2)
  }

  def removeTag(item: String, tag: String) = {
    val start = System.nanoTime / 1000
    tags.put(new Tag(tag, item), None)
    (System.nanoTime / 1000 - start, -1)
  }

  def initBulk(itemTagPairs: Seq[Tuple2[String,String]]) = {
    tags ++= itemTagPairs.map(t => new Tag(t._2, t._1))
  }

  def clear() = {
    tags.delete()
    tags.open()
  }
}

/* uses materialized view for tag intersection query */
class MTagClient(clus: ScadsCluster, exec: QueryExecutor)
      extends TagClient(clus, exec) {

  protected val logger = Logger("edu.berkeley.cs.scads.piql.mviews.MTagClient")

  val selectTagPairQuery =
    mTagPairs.where("tag1".a === (0.?))
               .where("tag2".a === (1.?))
               .limit(limit)
               .toPiql("selectTagPairQuery")

  def selectTags(tag1: String, tag2: String) = {
    selectTagPairQuery(tag1, tag2).map(
      arr => arr.head match {
        case m: MTagPair =>
          m.item
      })
  }

  def fastSelectTags(tag1: String, tag2: String) = {
    selectTagPairQuery(tag1, tag2)
  }

  def addTag(item: String, word: String) = {
    val start = System.nanoTime / 1000
    tags.put(new Tag(word, item))
    val dt1 = System.nanoTime / 1000 - start
    val assoc = selectItem(item)
    var mpairs = List[MTagPair]()
    // includes the duplicate pair
    for (a <- assoc) {
      mpairs ::= new MTagPair(a, word, item)
      mpairs ::= new MTagPair(word, a, item)
    }
    mTagPairs ++= mpairs;
//    var futures = List[ScadsFuture[Unit]]()
//    for (p <- mpairs) {
//      futures ::= mTagPairs.asyncPut(p.key, Some(p.value))
//    }
//    futures.map(_.get(5000, TimeUnit.MILLISECONDS).getOrElse(assert(false)))
    if ((System.nanoTime / 1000 - start) / 1000 > 1000) {
      var acc = "SLOW client side put time: "
      acc += (System.nanoTime / 1000 - start) / 1000
      logger.info(acc)
    }
    (dt1, System.nanoTime / 1000 - start)
  }

  def removeTag(item: String, word: String) = {
    val start = System.nanoTime / 1000
    tags.put(new Tag(word, item), None)
    val dt1 = System.nanoTime / 1000 - start
    var toDelete = List[MTagPair]()
    for (a <- selectItem(item)) {
      toDelete ::= new MTagPair(a, word, item)
      toDelete ::= new MTagPair(word, a, item)
    }
    toDelete ::= new MTagPair(word, word, item) // the duplicate pair
    mTagPairs --= toDelete;
//    var futures = List[ScadsFuture[Unit]]()
//    for (p <- toDelete) {
//      futures ::= mTagPairs.asyncPut(p.key, None)
//    }
//    futures.map(_.get(5000, TimeUnit.MILLISECONDS).getOrElse(assert(false)))
    (dt1, System.nanoTime / 1000 - start)
  }

  def initBulk(itemTagPairs: Seq[Tuple2[String,String]]) = {
    var allTags = List[Tag]()
    var allTagPairs = List[MTagPair]()
    itemTagPairs.groupBy(_._1).foreach {
      t =>
        val item = t._1
        var tags = t._2.map(_._2).sorted

        // queue the normal put
        tags.foreach(t => allTags ::= new Tag(t, item))

        // materialize all ordered pairs, including the duplicate pair
        for (x <- tags) {
          for (y <- tags) {
            allTagPairs ::= new MTagPair(x, y, item)
          }
        }
    }
    logger.info("Tag list size: " + allTags.length)
    logger.info("Materialized view size: " + allTagPairs.length)
    tags ++= allTags
    mTagPairs ++= allTagPairs
  }

  def clear() = {
    tags.delete()
    mTagPairs.delete()
    tags.open()
    mTagPairs.open()
  }
}
