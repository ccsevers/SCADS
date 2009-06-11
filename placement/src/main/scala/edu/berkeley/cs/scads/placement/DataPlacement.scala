package edu.berkeley.cs.scads

import edu.berkeley.cs.scads.thrift.ConflictPolicy
import edu.berkeley.cs.scads.thrift.ConflictPolicyType

trait DataPlacement {
	def assign(node: StorageNode, range: KeyRange)
	def copy(keyRange: KeyRange, src: StorageNode, dest: StorageNode)
	def move(keyRange: KeyRange, src: StorageNode, dest: StorageNode)
	def remove(keyRange: KeyRange, node: StorageNode)
}

@serializable
class SimpleDataPlacement(ns: String) extends SimpleKeySpace with ThriftConversions with DataPlacement {
	val nameSpace = ns
	val conflictPolicy = new ConflictPolicy()
	conflictPolicy.setType(ConflictPolicyType.CPT_GREATER)

	override def assign(node: StorageNode, range: KeyRange) {
		super.assign(node, range)
		node.getClient().set_responsibility_policy(nameSpace, range)
	}

	def copy(keyRange: KeyRange, src: StorageNode, dest: StorageNode) {
		val newDestRange = lookup(dest) + keyRange

		//Verify the src has our keyRange
		assert((lookup(src) & keyRange) == keyRange)

		//Tell the servers to copy the data
		src.getClient().copy_set(nameSpace, keyRange, dest.syncHost)

		//Change the assigment
		assign(dest, newDestRange)

		//Sync keys that might have changed
		src.getClient().sync_set(nameSpace, keyRange, dest.syncHost, conflictPolicy)
	}

	def move(keyRange: KeyRange, src: StorageNode, dest: StorageNode) {
		val newSrcRange = lookup(src) - keyRange
		val newDestRange = lookup(dest) + keyRange

		src.getClient().copy_set(nameSpace, keyRange, dest.syncHost)
		assign(dest, newDestRange)
		assign(src, newSrcRange)

		src.getClient().sync_set(nameSpace, keyRange, dest.syncHost, conflictPolicy)
		src.getClient().remove_set(nameSpace, keyRange)
	}

	def remove(keyRange: KeyRange, node: StorageNode) {
		val newRange = lookup(node) - keyRange

		assign(node, newRange)
		lookup(keyRange).foreach((n) => {
			node.getClient().sync_set(nameSpace, n._2, n._1.syncHost, conflictPolicy)
		})
		node.getClient().remove_set(nameSpace, keyRange)
	}
}
