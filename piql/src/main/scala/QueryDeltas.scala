package edu.berkeley.cs.scads.piql
package opt

import plans._

import net.lag.logging.Logger

class QueryDeltas(plan: LogicalPlan) {
  val logger = Logger()

  lazy val relations = getRelations(plan)

  protected def getRelations(plan: LogicalPlan): Seq[Relation] = plan match {
    case in: InnerNode => in.children.map(getRelations).reduceLeft(_ ++ _)
    case r: Relation => r :: Nil
  }

  lazy val deltaQueries =
    relations.map(r => {
      val delta = calcDelta(plan, r)
      val keyAttrs = relations.flatMap(_.keyAttributes)
      val viewAttrs = delta.equalityAttributes ++ delta.ordering
      logger.error("equality attrs: %s ordering attrs: %s", delta.equalityAttributes, delta.ordering)
      /* append remaining key fields to gurnatee uniqueness */
      val suffixAttrs = keyAttrs.filterNot(viewAttrs contains _)
      //TODO: Filter unified attributes
      val projAttrs = (viewAttrs ++ suffixAttrs).map(calcDelta(_, r))
      (r, Project(projAttrs, delta.plan))
    }).toMap

  case class SubPlan(plan: LogicalPlan, equalityAttributes: Seq[QualifiedAttributeValue], ordering: Seq[Value], ordered: Boolean)
  protected def calcDelta(plan: LogicalPlan, relation: TupleProvider): SubPlan = plan match {
    case Selection(EqualityPredicate(v1: ParameterValue, v2: QualifiedAttributeValue), child) =>
      val deltaChild = calcDelta(child, relation)
      deltaChild.copy(equalityAttributes=(v2 +: deltaChild.equalityAttributes))
    case Selection(EqualityPredicate(v1: QualifiedAttributeValue, v2: ParameterValue), child) =>
      val deltaChild = calcDelta(child, relation)
      deltaChild.copy(equalityAttributes=v1 +: deltaChild.equalityAttributes)
    case Selection(p, child) =>
      val deltaChild = calcDelta(child, relation)
      deltaChild.copy(plan=Selection(calcDelta(p, relation), deltaChild.plan))
    case Sort(attrs, asc, child)
      if(attrs.collect {case q: QualifiedAttributeValue => q}.map(_.relation) contains relation) =>
        calcDelta(child, relation).copy(ordering = attrs, ordered=false)
    case Sort(attrs, asc, child) =>
      val deltaChild = calcDelta(child, relation)
      deltaChild.copy(plan=Sort(attrs, asc, deltaChild.plan), ordering=attrs, ordered=true)
    case StopAfter(count, child) =>
      val deltaChild = calcDelta(child, relation)
      deltaChild.copy(plan= StopAfter(count, deltaChild.plan))
    case DataStopAfter(count, child) =>
      calcDelta(child, relation)
    case Join(left, right) =>
      val deltaLeft = calcDelta(left, relation)
      val deltaRight = calcDelta(right, relation)
      SubPlan(Join(deltaLeft.plan, deltaRight.plan), deltaLeft.equalityAttributes ++ deltaRight.equalityAttributes, Nil, false)
    case Paginate(cnt, c) =>
      val deltaChild = calcDelta(c, relation)
      if(deltaChild.ordered)
        deltaChild.copy(plan=Paginate(cnt, deltaChild.plan))
      else
        deltaChild
    case r: Relation if(r == relation) => SubPlan(DataStopAfter(FixedLimit(1), calcDelta(r)), Nil, Nil, false)
    case r: Relation => SubPlan(r, Nil, Nil, false)
  }

  protected def calcDelta(predicate: Predicate, relation: TupleProvider): Predicate = predicate match {
    case EqualityPredicate(v1, v2) => EqualityPredicate(calcDelta(v1, relation), calcDelta(v2, relation))
  }

  protected def calcDelta(value: Value, relation: TupleProvider): Value = value match {
    case QualifiedAttributeValue(r,f) if (r == relation) => QualifiedAttributeValue(calcDelta(r), f)
    case other => other
  }

  protected def calcDelta(relation: TupleProvider) = LocalTuples(0, "@" + relation.name, relation.keySchema, relation.schema)
}