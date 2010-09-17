package edu.berkeley.cs.scads.test

import scala.collection.JavaConversions._

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.Spec
import org.scalatest.matchers.{Matcher, MatchResult, ShouldMatchers}

import org.apache.avro.generic.{GenericData, IndexedRecord}

import edu.berkeley.cs.scads.storage.TestScalaEngine
import edu.berkeley.cs.scads.piql._

trait QueryResultMatchers {
  type Tuple = Array[GenericData.Record]
  type QueryResult = Seq[Tuple]

  class QueryResultMatcher[A <: IndexedRecord](right: Seq[Array[A]]) extends Matcher[QueryResult] {
    def apply(left: QueryResult): MatchResult = {
      left.zip(right).foreach {
        case (leftTuple, rightTuple) => {
          leftTuple.zip(rightTuple).foreach {
            case (leftRec, rightRec) => {
              leftRec.getSchema.getFields.foreach(field => {
                val rightVal = rightRec.get(field.pos)
                val leftVal = leftRec.get(field.pos)

                if(rightVal != leftVal) {

                  val string = "%s != %s".format(leftRec, rightRec)
                  return MatchResult(false, string, string)
                }
              })
            }
          }
        }
      }
      return MatchResult(true, "==", "==")
    }
  }

  def returnTuples[A <: IndexedRecord](right: Array[A]) = new QueryResultMatcher(List(right))
  def returnTuples(right: QueryResult) = new QueryResultMatcher(right)
}

@RunWith(classOf[JUnitRunner])
class ScadrSpec extends Spec with ShouldMatchers with QueryResultMatchers {
  val client = new ScadrClient(TestScalaEngine.getTestCluster)
  client.users ++= (1 to 10).map(i => (UserKey("User" + i), UserValue("UserHomeTown" + i)))

  describe("The SCADr client") {
    it("should have a findUser") {
      client.findUser("User1") should returnTuples(Array(UserKey("User1"), UserValue("UserHomeTown1")))
    }
  }
}