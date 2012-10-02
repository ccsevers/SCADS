package edu.berkeley.cs
package scads
package piql
package tpcw

import net.lag.logging.Logger

import opt._
import plans._
import comm._
import storage._
import storage.client.index._

import ch.ethz.systems.tpcw.populate.data.Utils

import java.util.UUID

class TpcwClient(val cluster: ScadsCluster, val executor: QueryExecutor) {
  protected val logger = Logger("edu.berkeley.cs.scads.piql.TpcwWorkflow")
  protected implicit val exec = executor

  /* Relations */
  val addresses = cluster.getNamespace[Address]("addresses")
  val authors = cluster.getNamespace[Author]("authors")
  val xacts = cluster.getNamespace[CcXact]("xacts")
  val countries = cluster.getNamespace[Country]("countries")
  val customers = cluster.getNamespace[Customer]("customers")
  val items = cluster.getNamespace[Item]("items")
  val orderLines = cluster.getNamespace[OrderLine]("orderLines")
  val orders = cluster.getNamespace[Order]("orders")
  val shoppingCartItems = cluster.getNamespace[ShoppingCartItem]("shoppingCartItems")

  /* Views */
  val orderCountStaging = cluster.getNamespace[OrderCountStaging]("orderCountStaging")
  val orderCount = cluster.getNamespace[OrderCount]("orderCount")

  /* View Maintenance Code */
  val fetchLineKeys = LocalTuples(0, "line", OrderLine.keySchema, OrderLine.schema)
    .join(items.as("item"))
    .where("item.I_ID".a === "line.OL_I_ID".a)
    .join(orders.as("order"))
    .where("order.O_ID".a === "line.OL_O_ID".a)
    .toPiql("fetchLineKeys")

  //Trigger to update the staging relation
  orderLines.addTriggers ::= { orderLines =>
    fetchLineKeys(orderLines.map(Vector(_))).foreach {joinedLine =>
      val line = joinedLine(0).asInstanceOf[OrderLine]
      val item = joinedLine(1).asInstanceOf[Item]
      val order = joinedLine(2).asInstanceOf[Order]
      calculateEpochs(order.O_DATE_Time).foreach { ep =>
        orderCountStaging.incrementField(OrderCountStaging(ep, item.I_SUBJECT, line.OL_I_ID ).key, "OC_COUNT", line.OL_QTY)
      }
    }
  }

  val kTopOrdersToList = 50

  object CurrentEpoch extends CalculatedValue {
    def getValue = getEpoch()
  }

  /**
   * Best Sellers web interaction
   * declare @last_o numeric(10)
   * select top 3333  O_ID into #temp from ORDERS
   * order by O_DATE desc
   * select @last_o = min(O_ID) from #temp
   * select top 50 I_ID,I_TITLE,A_FNAME,A_LNAME
   * from ITEM, AUTHOR ,ORDER_LINE
   * where OL_O_ID > @last_o AND
   * I_ID = OL_I_ID AND I_A_ID = A_ID AND I_SUBJECT = @CategoryID
   * group by I_ID,I_TITLE,A_FNAME,A_LNAME
   * order by SUM(OL_QTY) desc
   */
  val bestSellerWI = orderCount.as("count")
        .where("count.epoch".a === CurrentEpoch)
        .where("count.I_SUBJECT".a === (0.?))
        .dataLimit(kTopOrdersToList)
        .toPiql("bestSellerWI")

  val listTopOrdersQuery = orderCount.as("count")
      .where("count.epoch".a === (0.?))
      .where("count.I_SUBJECT".a === (1.?))
      .dataLimit(kTopOrdersToList)
      .select("count.I_ID".a, "count.OC_COUNT".a)
      .toPiql("listTopOrdersQuery")

  // Returns kTopOrdersToList orders in the (approximate) hour interval before ts.
  def topOrdersInPreviousHour(ts: Long, subject: String) = {
    listTopOrdersQuery(getEpoch(ts), subject).map(t =>
      (t.head.get(0).toString, t.head.get(1).asInstanceOf[Int]))
  }

  val fetchItemDetails = LocalTuples(0, "ocs", OrderCountStaging.keySchema, OrderCountStaging.schema)
    .join(items)
    .where("ocs.I_ID".a === "items.I_ID".a)
    .join(authors)
    .where("items.I_A_ID".a === "authors.A_ID".a)
    .toPiql("fetchItemDetails")

  /**
   * Function to calculate the topK from the staging relation for the current epoch
   * to be called periodically (stepSize)
   */
  def updateOrderCount(epoch: Long = getEpoch(), subjects: Seq[String] = Seq("subject0"), k: Int = kTopOrdersToList): Unit = {
    subjects.foreach(subject => {
      val prefix = OrderCountStaging(epoch, subject, null).key
      orderCount ++= fetchItemDetails(orderCountStaging.topK(prefix, prefix, Seq("OC_COUNT"), k, false).map(Vector(_))).map(joinedOcs => {
        val ocs = joinedOcs(0).asInstanceOf[OrderCountStaging]
        val item = joinedOcs(1).asInstanceOf[Item]
        val author = joinedOcs(2).asInstanceOf[Author]

        val oc = OrderCount(ocs.epoch, ocs.I_SUBJECT, ocs.OC_COUNT, ocs.I_ID)
        oc.A_FNAME = author.A_FNAME
        oc.A_LNAME = author.A_LNAME
        oc.I_TITLE = item.I_TITLE
        oc
      })
    })
  }

  val namespaces = List(addresses, authors, xacts, countries, customers, items, orderLines, orders, shoppingCartItems, orderCountStaging, orderCount)

  val windowSize = 60 * 60 * 1000  // 1 hour in milliseconds
  val stepSize = 5 * 60 * 1000     // 5 minutes in milliseconds

  def getEpoch(timestamp: Long = System.currentTimeMillis()) = timestamp - timestamp % stepSize

  def calculateEpochs(timestamp: Long = System.currentTimeMillis(), windowSize: Long = windowSize): Seq[Long] = {
    val firstEpoch = getEpoch(timestamp)
    (firstEpoch until (firstEpoch + windowSize) by stepSize).toSeq
  }

  //TODO: Move to scadr cluster
  def workloadDistribution = {
    val partitions = namespaces.flatMap(_.serversForKeyRange(None, None))
    val workloads = partitions.flatMap(p => p.servers.map(s => (s.host, s !! GetWorkloadStats())))
                              .map {case (h, f) => (h, f())}
                              .map {case (h, GetWorkloadStatsResponse(w1, w2, _)) => (h, w1+w2); case (h, _) => throw new RuntimeException("Invalid response from: " + h)}
                              .groupBy(_._1).toSeq

    workloads.map {
      case (h, w) => (h, w.map(_._2).sum)
    }
  }

  // cardinality constraints
  // TODO: we need to place these in various queries
  val maxOrderLinesPerPage = 100
  val maxItemsPerCart = 5000

  /**
   * Home web interaction
   * 
   * Select DISTINCT C_FNAME,C_LNAME
   * from CUSTOMER
   * where C_UNAME=@C_UNAME
   */
  val homeWI = customers.where("C_UNAME".a === (0.?))
		                    .toPiql("homeWI")

  /**
   * New Products web interaction
   * 
   * select top 50 I_ID,I_TITLE,A_FNAME,A_LNAME
   * from ITEM , AUTHOR
   * where A_ID = I_A_ID AND
   *       I_SUBJECT LIKE @CategoryID
   * order by I_PUB_DATE desc,I_TITLE
   */
  val newProductWI =
    new OptimizedQuery(
      "newProductWI",
      IndexLookupJoin(
        authors,
        AttributeValue(1,2) :: Nil,
        IndexLookupJoin(
          items,
          AttributeValue(0,2) :: Nil,
          LocalStopAfter(
            ParameterLimit(1,50),
            IndexScan(
              items.getOrCreateIndex(TokenIndex("I_SUBJECT" :: Nil) :: AttributeIndex("I_PUB_DATE") :: Nil),
              (0.?) :: Nil,
              ParameterLimit(1, 50),
              false)))),
      executor
    )

  /**
   * Product Detail web interaction
   * 
   * SELECT DISTINCT * FROM ITEM,AUTHOR
   * WHERE AUTHOR.A_ID = ITEM.I_A_ID AND ITEM.I_ID = @BookID
   */
  val productDetailWI =
      items.where("I_ID".a === (0.?))
			     .join(authors)
			     .where("A_ID".a === "I_A_ID".a)
			     .toPiql("productDetailWI")
  

  /**
   * Search Result web interaction (by Author)
   * 
   * SELECT top 50 I_TITLE,I_ID,A_FNAME, A_LNAME
   * FROM ITEM,AUTHOR
   * WHERE I_A_ID = A_ID
   * AND ( A_LNAME LIKE '% ' + @Author + '%' OR A_LNAME LIKE @Author + '%' )
   * order by I_TITLE
  */

  val searchByAuthorWI =
    new OptimizedQuery(
      "searchByAuthorWI",
      LocalStopAfter(
        ParameterLimit(1,50),
        IndexMergeJoin(
          items.getOrCreateIndex(AttributeIndex("I_A_ID") :: AttributeIndex("I_TITLE") :: Nil),
          AttributeValue(0,1) :: Nil,
          AttributeValue(2,1) :: Nil,
          ParameterLimit(1,50),
          true,
          IndexLookupJoin(
            authors,
            AttributeValue(0,1) :: Nil,
            LocalStopAfter(
              FixedLimit(50),
                IndexScan(
                  authors.getOrCreateIndex(TokenIndex("A_FNAME" :: "A_LNAME" :: Nil) :: Nil),
                  (0.?) :: Nil,
                  FixedLimit(50),
                  true
              ))))),
      executor)

  /**
   * Search Result web interaction (by Title)
   * 
   * SELECT TOP 50 I_TITLE, I_ID, A_FNAME, A_LNAME
   * FROM ITEM, AUTHOR
   * WHERE I_A_ID = A_ID
   * AND ( I_TITLE LIKE '% ' + @Title + '%' OR I_TITLE LIKE @Title + '%' )
   * order by I_TITLE
   */

  val searchByTitleWI =
    new OptimizedQuery(
      "searchByTitleWI",
      IndexLookupJoin(
        authors,
        AttributeValue(0, 2) :: Nil,
        LocalStopAfter(
          ParameterLimit(1,50),
          IndexScan(
            items.getOrCreateIndex(TokenIndex("I_TITLE" :: Nil) :: AttributeIndex("I_TITLE") :: AttributeIndex("I_A_ID") :: Nil),
            (0.?) :: Nil,
            ParameterLimit(1, 50),
            true))),
      executor
    )
 

  /**
   * Search Result web interaction (by Subject)
   * 
   * SELECT TOP 50 I_TITLE,I_ID, A_FNAME, A_LNAME
   * FROM ITEM, AUTHOR
   * WHERE I_A_ID = A_ID AND I_SUBJECT LIKE @CategoryID
   * order by I_TITLE
   */
  val searchBySubjectWI = newProductWI

  /**
   * Order Display web interaction
   *
   * FirstQuery:
   * select C_ID from CUSTOMER where C_UNAME=@C_UNAME and C_PASSWD=@C_PASSWD
   * DECLARE @O_ID numeric(10) select @O_ID = max(O_ID)from ORDERS where
   * O_C_ID=@C_ID
   * SELECT
   *   C_FNAME,C_LNAME,C_EMAIL,C_PHONE,
   *   O_ID,O_DATE,O_SUBTOTAL,O_TAX,O_TOTAL,O_SHIP_TYPE,O_SHIP_DATE,
   *   O_BILL_ADDR,O_SHIP_ADDR,O_CC_TYPE,O_STATUS,
   *   ADDR_STREET1,ADDR_STREET2,ADDR_CITY,ADDR_STATE,ADDR_ZIP,CO_NAME
   * FROM CUSTOMER,ADDRESS,COUNTRY,ORDERS
   * where
   *   O_ID=@O_ID and
   *   C_ID=@C_ID and
   *   O_BILL_ADDR=ADDR_ID AND
   *   ADDR_CO_ID=CO_ID
   * Select ADDR_STREET1,ADDR_STREET2,ADDR_CITY,
   * ADDR_STATE,ADDR_ZIP,CO_NAME from ADDRESS,COUNTRYwhere
   * ADDR_ID=@A_ID and ADDR_CO_ID=CO_ID
   *
   * SecondQuery:
   * select OL_I_ID,I_TITLE,I_PUBLISHER,
   * I_COST,OL_QTY,OL_DISCOUNT,OL_COMMENTSfrom ORDER_LINE,ITEM where
   * OL_I_ID=I_ID and OL_O_ID=@O_I
   */
  val orderDisplayGetCustomer =
    customers.where("C_UNAME".a === (0.?))
	     .toPiql("orderDisplayGetCustomer")

  val orderDisplayGetLastOrder =
    orders
      .where("O_C_UNAME".a === (0.?))
      .sort("O_DATE_Time".a :: Nil, false)
      .limit(1)
      .join(addresses as "a1")
      .where("a1.ADDR_ID".a === "O_BILL_ADDR_ID".a)
      .join(countries as "c1")
      .where("a1.ADDR_CO_ID".a === "c1.CO_ID".a)
      .join(addresses as "a2")
      .where("a2.ADDR_ID".a === "O_SHIP_ADDR_ID".a)
      .join(countries as "c2")
      .where("a2.ADDR_CO_ID".a === "c2.CO_ID".a)
      .toPiql("orderDisplayGetLastOrder")

  val orderDisplayGetOrderLines =
    orderLines.where("OL_O_ID".a === (0.?))
      .limit((1.?), maxOrderLinesPerPage)
      .join(items)
      .where("OL_I_ID".a === "I_ID".a)
      .toPiql("orderDisplayGetOrderLines")


  def orderDisplayWI(c_uname: String, c_passwd: String, numOrderLinesPerPage: Int) = {
    val cust = orderDisplayGetCustomer(c_uname).head.head.asInstanceOf[Customer]
    //assert(cust.C_PASSWD == c_passwd, "Passwords don't match")

    val lastOrderDetails = orderDisplayGetLastOrder(c_uname).headOption
    val lastOrder = lastOrderDetails.map(_.apply(1).asInstanceOf[Order])
    val lines = lastOrder.map(o => orderDisplayGetOrderLines(o.O_ID, numOrderLinesPerPage))

    (cust, lastOrder, lines)
  }

  /**
   * Shopping Cart Interations
   *
   * Add to cart
   * Insert into SHOPPING_CART values(@Session,0,@BookID,1, @Title,@SRP,@COST,@Backing,GetDate())
   *
   * Refresh Display
   * Update SHOPPING_CART set SC_QTY=@QTY where SC_ID=@UserID and SC_I_ID=@BookID
   * Delete from SHOPPING_CART where SC_ID=@UserID and SC_I_ID=@BookID
   */

  val retrieveShoppingCart =
    shoppingCartItems.where("SCL_C_UNAME".a === (0.?))
                     .limit(1000)
                     .join(items)
                     .where("SCL_I_ID".a === "I_ID".a)
                     .toPiql("retrieveShoppingCart")

  /**
   * This is a very simplified shopping cart WI.
   * Given a c_uname, items will be added to the cart, or the
   * quantity will be updated. this is not really conformant to the TPC-W
   * benchmark spec */

   def shoppingCartWI(c_uname: String, newItems: Seq[(String, Int)]) = {
    val cart = retrieveShoppingCart(c_uname).map(_.head.asInstanceOf[ShoppingCartItem])
    val currentItems = cart.map(c => (c.SCL_I_ID, c.SCL_QTY))

    val updatedItems = (currentItems ++ newItems).groupBy(_._1).map {
      case (id, qtys) => (id, qtys.map(_._2).sum)
    }

    updatedItems.foreach(i => {
      val scl = new ShoppingCartItem(c_uname, i._1)
      if(i._2 > 0) {
        scl.SCL_QTY = i._2
        shoppingCartItems.put(scl)
      }
      else {
        shoppingCartItems.delete(scl)
      }
    })
  }

  /**
   * Buy Request web interaction (Existing Customer)
   *
   * select C_ID from CUSTOMER where C_UNAME=@C_UNAME and
   * C_PASSWD=@C_PASSWD

   * SELECT C_UNAME,C_PASSWD,C_FNAME,C_LNAME,C_PHONE,
   * C_EMAIL,C_BIRTHDATE,C_DATA1,C_DATA2,ADDR_STREET1,
   * ADDR_STREET2,ADDR_CITY,ADDR_STATE,ADDR_ZIP,CO_NAME
   * FROM CUSTOMER,ADDRESS,COUNTRY
   * where C_ADDR_ID=ADDR_ID and ADDR_CO_ID=CO_ID and C_ID = @C_ID
   */

  val buyRequestExistingCustomerWI =
    customers.where("C_UNAME".a === (0.?))
             .join(addresses)
             .where("C_ADDR_ID".a === "ADDR_ID".a)
             .join(countries)
             .where("ADDR_CO_ID".a === "CO_ID".a)
             .toPiql("buyRequestExistingCustomerWI")

  /**
   * Buy Confirm Web Interation
   * DECLARE @CO_ID numeric(4)
   * DECLARE @ADDR_ID numeric(10)
   *
   * Select @CO_ID = CO_ID from COUNTRY where CO_NAME=@CO_NAME
   *
   * SELECT ADDR_ID
   * FROM ADDRESS
   * WHERE
   * ADDR_STREET1=@ADDR_STREET1 and
   * ADDR_STREET2=@ADDR_STREET2 and
   * ADDR_CITY=@ADDR_CITY and
   * ADDR_STATE=@ADDR_STATE and
   * ADDR_ZIP=@ADDR_ZIP and
   * ADDR_CO_ID=@CO_ID
   *
   * Select @CO_ID = CO_ID
   * from COUNTRY
   * where CO_NAME=@CO_NAME
   *
   * Insert into ADDRESS values(@ADDR_STREET1,
   * @ADDR_STREET2,@ADDR_CITY,@ADDR_STATE,@ADDR_ZIP,
   * @CO_ID)
   *
   * select @ADDR_ID = @@identity
   *
   * select C_ID,C_DISCOUNT,C_ADDR_ID
   * from CUSTOMER
   * where C_UNAME=@UserID
   *
   * DECLARE @O_ID numeric(9)
   *
   * Insert into ORDERS values (@O_C_ID,getdate(),@O_SUBTOTAL,
   * @O_TAX,@O_TOTAL,@O_SHIP_TYPE,NULL,@O_BILL_ADDR,
   * @O_SHIP_ADDR,@O_CC_TYPE,@O_CC_NUM,@O_CC_NAME,
   * @O_CC_EXP,'Pending')
   *
   * select @O_ID = @@identity
   *
   * Insert ORDER_LINE (OL_O_ID,OL_I_ID,OL_QTY,
   * OL_DISCOUNT,OL_COMMENTS)
   *
   * Select @O_ID,SC_I_ID,SC_QTY,1,'comment' from SHOPPING_CART
   *
   * update ITEM     set I_STOCK = I_STOCK - SCL_I_QTY + case when (I_STOCK -
   * SCL_I_QTY < 10) then 21 else 0 end
   * from SHOPPING_BASKET
   * where SC_SHOPPING_ID=@Session and SC_HOST=@SC_HOST and SCL_I_ID =
   * I_ID
   *
   * Delete from SHOPPING_CART where SC_ID=@Session
   *
   * Insert into CC_XACTS
   * values(@O_ID,@O_CC_TYPE,@O_CC_EXP,@O_CC_AUTH,@O_TOTAL,getdate(),@CO_ID)
   */

  //TODO: Async put?
  def buyConfirmWI(c_uname: String,
                   cc_type: String,
                   cc_number: Int,
                   cc_name: String,
                   cc_expiry: Long,
                   shipping: String): String = {
    val customer = homeWI(c_uname).head.head.asInstanceOf[Customer]
    val cart = retrieveShoppingCart(c_uname).map(sl => (sl(0).asInstanceOf[ShoppingCartItem], sl(1).asInstanceOf[Item]))

    // calculate costs
    val sc_sub_total = cart.map {
      case (scl, itm) => itm.I_COST * scl.SCL_QTY
    }.sum * (1.0 - customer.C_DISCOUNT)

    val sc_tax = sc_sub_total * 0.0825
    val sc_ship_cost = 3.0 + (1.0 * cart.size.toDouble)
    val sc_total = sc_sub_total + sc_tax + sc_ship_cost

    def newUUID =
      UUID.randomUUID.toString

    // make order
    val order = Order(newUUID)
    order.O_C_UNAME = c_uname
    order.O_DATE_Time = System.currentTimeMillis
    order.O_SUB_TOTAL = sc_sub_total
    order.O_TAX = sc_tax
    order.O_TOTAL = sc_total
    order.O_SHIP_TYPE = shipping
    order.O_SHIP_DATE = System.currentTimeMillis + (scala.util.Random.nextInt(7) + 1).toLong * 86400L // [1..7] days later
    order.O_BILL_ADDR_ID = customer.C_ADDR_ID
    order.O_SHIP_ADDR_ID = customer.C_ADDR_ID
    order.O_STATUS = "PENDING"
    val orderPut = orders.asyncPut(order.key, order.value)

    // make order lines
    val orderLinePuts = cart.zipWithIndex.map {
      case ((scl, itm), idx) =>
        val ol = new OrderLine(order.O_ID, idx + 1)
        ol.OL_I_ID = itm.I_ID
        ol.OL_QTY = scl.SCL_QTY
        ol.OL_DISCOUNT = customer.C_DISCOUNT
        ol.OL_COMMENT = Utils.getRandomAString(20, 100)
        orderLines.asyncPut(ol.key, ol.value)
    }

    // make item stocks updates
    val stockUpdatePuts = cart.map {
      case (scl, itm) =>
      if (itm.I_STOCK - scl.SCL_QTY >= 10)
        itm.I_STOCK = itm.I_STOCK - scl.SCL_QTY
      else
        itm.I_STOCK = scala.math.min(0, (itm.I_STOCK - scl.SCL_QTY) + 21) // ... uhh, what happens if this goes negative??? that's why i put the min condition there (it's not given in the spec)

      items.asyncPut(itm.key, itm.value)
    }

    // credit card (PGE) auth stuff ignored...

    // make cc txn
    val ccXact = CcXact(order.O_ID)
    ccXact.CX_TYPE = cc_type
    ccXact.CX_NUM = cc_number
    ccXact.CX_NAME = cc_name
    ccXact.CX_EXPIRY = cc_expiry
    ccXact.CX_AUTH_ID = Utils.getRandomAString(15)
    ccXact.CX_XACT_AMT = sc_total
    ccXact.CX_XACT_DATE = System.currentTimeMillis
    ccXact.CX_CO_ID = Utils.getRandomInt(1, 92)

    // clear shopping cart. unfortunately BulkPut does not support deletion,
    // so we do this inefficiently by looping. if our numbers are not good we
    // could try to make this more efficient, but since max cart size is 100,
    // this shouldn't be THAT bad
    val clearCartPuts = cart map { case (scl, _) =>
      shoppingCartItems.asyncPut(scl.key, None)
    }

    /* Block until puts complete */
    (orderLinePuts ++ stockUpdatePuts ++ clearCartPuts :+ orderPut).foreach(_())

    logger.debug("finished buy confirmation of %d items", cart.size)

    order.O_ID
  }

  val adminRequestWI = productDetailWI
}
