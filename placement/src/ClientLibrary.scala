import SCADS.RecordSet
import SCADS.RangeSet
import SCADS.Record
import SCADS.NotResponsible
import SCADS.ClientLibrary

import scala.collection.mutable.HashMap
import scala.collection.mutable.HashSet

trait KeySpaceProvider {
	def getKeySpace(ns: String)
	def refreshKeySpace()
}

class NonCoveredRangeException extends Exception

abstract class ClientLibrary extends SCADS.ClientLibrary.Iface {
	def ns_map: Map[String,KeySpace]
	
	def add_namespace(ns: String): Boolean = {
		this.add_namespace(ns,null)
		true
	}
	def add_namespace(ns: String, ks: KeySpace): Boolean = {
		ns_map.update(ns,ks)
		true
	}
	
	def get(namespace: String, key: String): Record
	def get_set(namespace: String, keys: RecordSet): java.util.List[Record]
	def put(namespace: String, rec:Record): Boolean 
}

class ROWAClientLibrary extends ClientLibrary with KeySpaceProvider {
	var ns_map = new HashMap[String,SimpleKeySpace]
	var dp_map = new HashMap[String,DataPlacement]
	
	/**
	* Asks key space provider for latest keyspace for the specified namespace.
	* Updates the local copy's keyspace.
	*/
	override def getKeySpace(ns: String) = {
		val ks = dp_map(ns).keySpace
		ns_map.update(ns,ks)
	}
	
	/**
	* Asks key space provider for all known namespaces.
	* Updates all the keyspaces.
	*/
	override def refreshKeySpace() = {
		ns_map.foreach({ case(ns,ks) => {
			this.getKeySpace(ns)
		}})
	}

	/**
	* Read value from one node. Uses local map. 
	* Does update from KeySpaceProvider if local copy is out of date.
	*/
	override def get(namespace: String, key: String): Record = {
		val ns_keyspace = ns_map(namespace)
		try {
			val node = ns_keyspace.lookup(key).next // just get the first node
			val record = node.get(namespace,key)
			record
		} catch {
			case e:NotResponsible => {
				this.getKeySpace(namespace)
				val record = this.get(namespace,key) // recursion, will this work?
				record
			}
		}
	}
	
	/**
	* Read values from one node. Uses local map.
	* Does update from KeySpaceProvider if local copy is out of date.
	*/
	override def get_set(namespace: String, keys: RecordSet): java.util.List[Record] = {
		var records = new HashSet[Record]
		val ns_keyspace = ns_map(namespace)
		val target_range = new KeyRange(keys.range.start_key, keys.range.end_key)

		// determine which ranges to ask from which nodes
		// assumes no gaps in range, but someone should tell user if entire range isn't covered
		val query_nodes = this.get_set_queries(ns_keyspace.lookup(target_range),target_range)

		// now do the getting
		query_nodes.foreach( {case (node,keyrange)=> {
			val rset = new RecordSet(3,new RangeSet(keyrange.start,keyrange.end,0,0),null)
			try {
				val records_subset = node.get_set(namespace,rset)
				val iter = records_subset.iterator()
				while (iter.hasNext()) { records += iter.next() }
			} catch {
				case e:NotResponsible => {
					this.getKeySpace(namespace)
					val records_subset = node.get_set(namespace,rset)
					val iter = records_subset.iterator()
					while (iter.hasNext()) { records += iter.next() }
				}
			}
		}
		})
		// TODO: check for gaps in desired RecordSet, throw exception?
		
		java.util.Arrays.asList(records.toArray: _*) // shitty, but convert to java array
	}
	
	private def get_set_queries(nodes: Map[StorageNode, KeyRange], target_range: KeyRange): HashMap[StorageNode, KeyRange] = {
		var resultmap = new HashMap[StorageNode, KeyRange]
		
		var start = target_range.start
		val end = target_range.end
		
		while (start < end) {
			val node_tuple = this.find_node_at_start(nodes,start)
			resultmap += node_tuple._1 -> node_tuple._2
			start = node_tuple._2.end
		}
		resultmap
	}
	
	private def find_node_at_start(nodes: Map[StorageNode,KeyRange], start: String): (StorageNode,KeyRange) = {
		var potential_nodes = nodes.filter((entry) => entry._2.start <= start) // nodes that start at or before target start
		var chosen_node = potential_nodes.elements.next // init to first one?
		var end = chosen_node._2.end
		
		potential_nodes.foreach((entry) =>
			if (entry._2.end > end) {
				chosen_node = entry
				end = chosen_node._2.end
			}
		)
		val range_covered = new KeyRange(start,end)
		(chosen_node._1, range_covered)
	}
	
	/**
	* Write records to all responsible nodes.
	* Does update from KeySpaceProvider if local copy is out of date.
	*/
	override def put(namespace: String, rec:Record): Boolean = {
		val key = rec.getKey()
		val ns_keyspace = ns_map(namespace)
		val put_nodes = ns_keyspace.lookup(key)
		
		put_nodes.foreach({ case(node)=>{
			try {
				val success = node.put(namespace,rec)
				success
			} catch {
				case e:NotResponsible => {
					this.getKeySpace(namespace)
					val success = this.put(namespace,rec) // recursion may redo some work
					success
				}
			}
		}})
		true // TODO: make accurate
	}
}


class ClientLibraryServer(p: Int) extends ThriftServer {
	val port = p
	val processor = new SCADS.ClientLibrary.Processor(new ROWAClientLibrary)
}

