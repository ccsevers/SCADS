package edu.berkeley.cs.scads.storage.transactions

import edu.berkeley.cs.scads.storage._

import org.apache.avro.generic.IndexedRecord
import org.apache.avro.Schema
import edu.berkeley.cs.avro.marker.{AvroRecord, AvroPair}

// This class parses annotations and creates field integrity constraint lists.
// This works for both AvroPair and AvroRecord.
class FieldICUtil[V <: IndexedRecord](implicit valueManifest: Manifest[V]) {

  private val valueSchema = valueManifest.erasure.asInstanceOf[Class[V]].newInstance match {
    case p: AvroPair => p.value.getSchema
    case r: AvroRecord => r.getSchema
  }
  private val fields = valueManifest.erasure.asInstanceOf[Class[V]].getDeclaredFields

  def getFieldICList: FieldICList = {
    val rlist = fields.map(f => {
      val annotations = f.getDeclaredAnnotations

      if (annotations.length > 0) {
        val field = valueSchema.getField(f.getName)
        if (field != null) {
          val fieldPos = field.pos()

          val restrictions = annotations.map(a => {
            a match {
              case x: JavaFieldAnnotations.JavaFieldGT => FieldRestrictionGT(x.value)
              case x: JavaFieldAnnotations.JavaFieldGE => FieldRestrictionGE(x.value)
              case x: JavaFieldAnnotations.JavaFieldLT => FieldRestrictionLT(x.value)
              case x: JavaFieldAnnotations.JavaFieldLE => FieldRestrictionLE(x.value)
            }
          }).foldLeft[(Option[FieldRestriction], Option[FieldRestriction])]((None, None))(foldRestrictions _)

          FieldIC(fieldPos, restrictions._1, restrictions._2)
        } else {
          // The field does not exist.  This may happen if annotations are on
          // AvroPair records.  ICs on keys are ignored.
          null
        }
      } else {
        // No annotations on the field.
        null
      }
    }).filter(_ != null)
    FieldICList(rlist)
  }

  // r is the new restriction, and l is the (optionally) existing restriction.
  // r is a lower restriction (GT or GE).
  private def getLowerRestriction(r: FieldRestriction,
                                  l: Option[FieldRestriction]) = {
    val newRestriction = (r, l.getOrElse(r)) match {
      case (FieldRestrictionGT(x), FieldRestrictionGT(y)) =>
        FieldRestrictionGT(math.max(x, y))
      case (FieldRestrictionGT(x), FieldRestrictionGE(y)) =>
        if (x >= y) {
          FieldRestrictionGT(x)
        } else {
          FieldRestrictionGE(y)
        }
      case (FieldRestrictionGE(x), FieldRestrictionGT(y)) =>
        if (y >= x) {
          FieldRestrictionGT(y)
        } else {
          FieldRestrictionGE(x)
        }
      case (FieldRestrictionGE(x), FieldRestrictionGE(y)) =>
        FieldRestrictionGE(math.max(x, y))
      case (x, _) => x
    }
    Some(newRestriction)
  }

  // r is the new restriction, and u is the (optionally) existing restriction.
  // r is an upper restriction (LT or LE).
  private def getUpperRestriction(r: FieldRestriction,
                                  u: Option[FieldRestriction]) = {
    val newRestriction = (r, u.getOrElse(r)) match {
      case (FieldRestrictionLT(x), FieldRestrictionLT(y)) =>
        FieldRestrictionLT(math.min(x, y))
      case (FieldRestrictionLT(x), FieldRestrictionLE(y)) =>
        if (x <= y) {
          FieldRestrictionLT(x)
        } else {
          FieldRestrictionLE(y)
        }
      case (FieldRestrictionLE(x), FieldRestrictionLT(y)) =>
        if (y <= x) {
          FieldRestrictionLT(y)
        } else {
          FieldRestrictionLE(x)
        }
      case (FieldRestrictionLE(x), FieldRestrictionLE(y)) =>
        FieldRestrictionLE(math.min(x, y))
      case (x, _) => x
    }
    Some(newRestriction)
  }

  private def foldRestrictions(t: (Option[FieldRestriction],
                                   Option[FieldRestriction]),
                               r: FieldRestriction) = {
    (t, r) match {
      case ((l, u), x:FieldRestrictionGT) => (getLowerRestriction(x, l), u)
      case ((l, u), x:FieldRestrictionGE) => (getLowerRestriction(x, l), u)
      case ((l, u), x:FieldRestrictionLT) => (l, getUpperRestriction(x, u))
      case ((l, u), x:FieldRestrictionLE) => (l, getUpperRestriction(x, u))
    }
  }
}
