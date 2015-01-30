package org.squbs.cluster

import java.util

import akka.actor._
import akka.util.ByteString
import com.typesafe.scalalogging.LazyLogging
import org.squbs.cluster.JMX._

/**
 * Created by zhuwang on 1/26/15.
 */
private[cluster] sealed trait ZkClusterState

private[cluster] case object ZkClusterUninitialized extends ZkClusterState
private[cluster] case object ZkClusterActiveAsLeader extends ZkClusterState
private[cluster] case object ZkClusterActiveAsFollower extends ZkClusterState

private[cluster] case class ZkPartitionData(partitionKey: ByteString, 
                                            members: Set[Address] = Set.empty, 
                                            expectedSize: Int,
                                            props: Array[Byte] = Array.empty)

private[cluster] case class ZkClusterData(leader: Option[Address],
                                          members: Set[Address],
                                          partitions: Map[ByteString, ZkPartitionData])

/**
 * The main Actor of ZkCluster
 */
class ZkClusterActor extends FSM[ZkClusterState, ZkClusterData] with Stash with LazyLogging {

  private[this] val zkCluster = ZkCluster(context.system)
  import zkCluster._

  private[this] implicit val segLogic = segmentationLogic
  import segLogic._
  
  private[this] implicit val log = logger
  private[cluster] var whenZkClientUpdated = Seq.empty[ActorRef]
  private[cluster] var whenPartitionUpdated = Set.empty[ActorRef]

  //begin the process of electing a leader
  private val zkMembershipMonitor = 
    context.actorOf(Props[ZkMembershipMonitor].withDispatcher("pinned-dispatcher"), "zkMembership")
  //begin the process of partitioning management
  private val zkPartitionsManager = context.actorOf(Props[ZkPartitionsManager], "zkPartitions")

  private[this] val mandatory:StateFunction = {

    case Event(updatedEvent @ ZkClientUpdated(updated), _) =>
      zkMembershipMonitor ! updatedEvent
      zkPartitionsManager ! updatedEvent
      whenZkClientUpdated.foreach(_ ! updatedEvent)
      stay

    case Event(ZkMonitorClient, _) =>
      whenZkClientUpdated = whenZkClientUpdated :+ sender()
      stay

    case Event(ZkQueryMembership, zkClusterData) =>
      sender() ! ZkMembership(zkClusterData.members)
      stay

    case Event(ZkMonitorPartition, _) =>
      log.info("[follower/leader] monitor partitioning from:{}", sender().path)
      whenPartitionUpdated += sender
      stay

    case Event(ZkStopMonitorPartition, _) =>
      log.info("[follower/leader] stop monitor partitioning from:{}", sender().path)
      whenPartitionUpdated -= sender
      stay

    case Event(ZkListPartitions(member), zkClusterData) =>
      sender() ! ZkPartitions(zkClusterData.partitions.collect{
        case (partitionKey, ZkPartitionData(_, members, _, _)) if members.contains(member) => partitionKey
      }.toSeq)
      stay
  }
  
  // reflect the current memory snapshot in the MXBean
  class MembersInfoBean extends MembersInfoMXBean {
    import scala.collection.JavaConversions._
    override def getLeader: String = stateData.leader.map(_.toString).toString
    override def getMembers: util.List[String] = stateData.members.map(_.toString).toList
  }

  class PartitionsInfoBean extends PartitionsInfoMXBean {
    import scala.collection.JavaConversions._
    override def getPartitions: util.List[PartitionInfo] = stateData.partitions map {
      case (key, data) => PartitionInfo(key, partitionZkPath(key), data.members.mkString(","))
    } toList
  }
  //the reason we put startWith into #preStart is to allow postRestart to trigger new FSM actor when recover from error
  override def preStart = {
    register(new MembersInfoBean, prefix + membersInfoName)
    register(new PartitionsInfoBean, prefix + partitionsInfoName)
    startWith(ZkClusterUninitialized, ZkClusterData(None, Set.empty[Address], ZkPartitionsManager.loadPartitions()))
  }
  
  override def postStop = {
    unregister(prefix + membersInfoName)
    unregister(prefix + partitionsInfoName)
  }

  when(ZkClusterUninitialized)(mandatory orElse {

    case Event(ZkLeaderElected(Some(address)), zkClusterData) =>
      log.info("[uninitialized] leader elected:{} and my zk address:{}", address, zkAddress)
      if(address.hostPort == zkAddress.hostPort) {
        val rebalanced = rebalance(zkClusterData.partitions, zkClusterData.partitions, zkClusterData.members)
        goto(ZkClusterActiveAsLeader) using zkClusterData.copy(leader = Some(address), partitions = rebalanced)
      } else
        goto(ZkClusterActiveAsFollower) using zkClusterData.copy(leader = Some(address))

    case Event(ZkMembersChanged(members), zkClusterData) =>
      log.info("[uninitialized] membership updated:{}", members)
      stay using zkClusterData.copy(members = members)

    case Event(_, _) =>
      stash
      stay
  })

  when(ZkClusterActiveAsFollower)(mandatory orElse {

    case Event(ZkLeaderElected(Some(address)), zkClusterData) =>
      if(address.hostPort == zkAddress.hostPort) {
        // in case of leader dies before follower get the whole picture of partitions
        // the follower get elected need to read from Zookeeper
        val partitions = ZkPartitionsManager.loadPartitions()
        val rebalanced = rebalance(zkClusterData.partitions, partitions, zkClusterData.members)
        goto(ZkClusterActiveAsLeader) using zkClusterData.copy(leader = Some(address), partitions = rebalanced)
      } else
        goto(ZkClusterActiveAsFollower) using zkClusterData.copy(leader = Some(address))

    case Event(ZkQueryLeadership, zkClusterData) =>
      log.info("[follower] leadership query answered:{} to:{}", zkClusterData.leader, sender().path)
      zkClusterData.leader.foreach(address => sender() ! ZkLeadership(address))
      stay

    case Event(ZkMembersChanged(members), zkClusterData) =>
      log.info("[follower] membership updated:{}", members)
      stay using zkClusterData.copy(members = members)
      
    case Event(origin @ ZkQueryPartition(partitionKey, notification, sizeOpt, props, members), zkClusterData) =>
      log.info("[follower] partition query: {} with expected size {}, current snapshot: {}",
        keyToPath(partitionKey),
        sizeOpt,
        zkClusterData.partitions.map{case (k, v) => keyToPath(k) -> v}
      )
      // There are couple cases regarding the current memory snapshot and the sizeOpt
      // 1. partition not available => ask leader
      // 3. partition available && sizeOpt empty => reply the parition
      // 4. partition available && partition.members.size != min(clusterSize, expectedSize) => ask leader
      // 5. partition available && partition.members.size == min(clusterSize, expectedSize, sizeOpt.getOrElse(expectedSize))
      //  1) sizeOpt.get == expectedSize => reply the partition
      //  2) sizeOpt.get != expectedSize => ask leader
      zkClusterData.partitions.get(partitionKey) match {
        case Some(data @ ZkPartitionData(_, servants, originalExpectedSize, _))
          if sizeOpt.getOrElse(originalExpectedSize) == originalExpectedSize
            && servants.size == Math.min(zkClusterData.members.size, sizeOpt.getOrElse(originalExpectedSize)) =>
          log.info("[follower] answer the partition query using snapshot {}", data)
          sender ! ZkPartition(partitionKey, servants, partitionZkPath(partitionKey), notification)
          stay
        case None if sizeOpt.isEmpty =>
          log.info("[follower] paritions {} does not exist for now", keyToPath(partitionKey))
          sender ! ZkPartitionNotFound(partitionKey)
          stay
        case _ =>
          log.info("[follower] local snapshot {} wasn't available yet or probably a resize to {}. Forward to {}",
            zkClusterData.partitions.map{case (k, v) => keyToPath(k) -> v},
            sizeOpt,
            zkClusterData.leader
          )
          zkClusterData.leader.foreach(address => {
            context.actorSelection(self.path.toStringWithAddress(address)) forward origin
          })
          stay
      }

    case Event(ZkPartitionsChanged(_, changes), zkClusterData) => 
      // Only apply the changes that reachs the expected size or the total member size
      val (partitionsToRemove, updates) = changes partition {
        case (key, change) => change.members.isEmpty && change.expectedSize == 0
      }

      log.info("[follower] got the partitions to update {} and partitions to remove {} from partition manager",
        updates.map{case (key, members) => keyToPath(key) -> members},
        partitionsToRemove.map{case (key, members) => keyToPath(key)}
      )
      if (changes.nonEmpty) {
        notifyPartitionDiffs(zkClusterData.partitions, changes)("follower")
        // For the partitions without any members, we remove them from the memory map
        stay using zkClusterData.copy(partitions = zkClusterData.partitions ++ updates -- partitionsToRemove.keys)
      } else stay

    case Event(remove:ZkRemovePartition, zkClusterData) =>
      zkClusterData.leader.foreach(address => {
        context.actorSelection(self.path.toStringWithAddress(address)) forward remove
      })
      stay
  })

  when(ZkClusterActiveAsLeader)(mandatory orElse {

    case Event(ZkLeaderElected(Some(address)), zkClusterData) =>
      if (address.hostPort == zkAddress.hostPort)
        stay
      else
        goto(ZkClusterActiveAsFollower) using zkClusterData.copy(leader = Some(address))

    case Event(ZkQueryLeadership, zkClusterData) =>
      log.info("[leader] leadership query answered:{} to:{}", zkClusterData.leader, sender().path)
      zkClusterData.leader.foreach(address => sender() ! ZkLeadership(address))
      stay

    case Event(ZkMembersChanged(members), zkClusterData) =>
      log.info("[leader] membership updated:{}", members)

      if(zkClusterData.members == members){
        //corner case, in which members weren't really changed, avoid redundant rebalances
        stay
      }
      else {
        val membersLeft = zkClusterData.members.diff(members)
        val partitionWithMemberLost = if(membersLeft.nonEmpty)
          zkClusterData.partitions.mapValues{partition => 
            partition.copy(members = partition.members.filterNot(membersLeft.contains))
          }
        else
          zkClusterData.partitions

        val rebalanced = rebalance(zkClusterData.partitions, partitionWithMemberLost, members)
        stay using zkClusterData.copy(members = members, partitions = rebalanced)
      }

    case Event(ZkQueryPartition(partitionKey, notification, sizeOpt, props, _), zkClusterData) =>
      log.info("[leader] partition query: {} with expected size {}, current snapshot: {}",
        keyToPath(partitionKey),
        sizeOpt,
        zkClusterData.partitions.map{case (k, v) => keyToPath(k) -> v}
      )
      // There are couple cases regarding the current memory snapshot and the sizeOpt
      // 1. partition not available && sizeOpt not empty => rebalance using sizeOpt.get
      // 2. partition not available && sizeOpt empty => PartitionNotFound
      // 3. partition available && sizeOpt empty => reply the parition
      // 4. partition available && partition.members.size != min(clusterSize, expectedSize) => rebalance using sizeOpt.getOrElse(expectedSize)
      // 5. partition available && partition.members.size == min(clusterSize, expectedSize, sizeOpt.getOrElse(expectedSize)) 
      //  1) sizeOpt.get == expectedSize => reply the partition
      //  2) sizeOpt.get != expectedSize => resize & reply the partition
      zkClusterData.partitions.get(partitionKey) match {
        case Some(existence @ ZkPartitionData(key, servants, originalExpectedSize, _)) 
          if servants.size == Math.min(zkClusterData.members.size, sizeOpt.getOrElse(originalExpectedSize))=> // 3, 5.1, 5.2
          log.info("[leader] partition exists:{} -> {}", keyToPath(partitionKey), servants)
          sender() ! ZkPartition(partitionKey, servants, partitionKey, notification)
          if (sizeOpt.nonEmpty && sizeOpt.get != originalExpectedSize) {
            zkPartitionsManager ! ZkResizePartition(key, sizeOpt.get)
            stay using zkClusterData.copy(
              partitions = zkClusterData.partitions
                + (key -> existence.copy(expectedSize = sizeOpt.getOrElse(originalExpectedSize)))
            )
          }else stay
        case None if sizeOpt.isEmpty => // 2
          log.info("[leader] partition does not exists:{}", keyToPath(partitionKey))
          sender() ! ZkPartitionNotFound(partitionKey)
          stay
        case partitionOpt => // 1, 4
          log.info("[leader] partition creation or resize:{}", keyToPath(partitionKey))
          val expectedSize = sizeOpt getOrElse partitionOpt.get.expectedSize
          val rebalanced = rebalance(
            zkClusterData.partitions,
            zkClusterData.partitions + 
              (partitionKey -> ZkPartitionData(partitionKey, expectedSize = expectedSize, props = props)),
            zkClusterData.members
          )
          sender() ! ZkPartition(
            partitionKey,
            rebalanced.get(partitionKey).map(_.members).getOrElse(Set.empty[Address]),
            partitionKey,
            notification
          )
          stay using zkClusterData.copy(partitions = rebalanced)
      }

    case Event(ZkPartitionsChanged(_, changes), zkClusterData) =>
      log.debug("[leader] I don't really care about changes {} because my snapshot {}",
        changes.map { case (key, members) => keyToPath(key) -> members},
        zkClusterData.partitions.map { case (key, members) => keyToPath(key) -> members}
      )
      stay

    case Event(remove @ ZkRemovePartition(partitionKey), zkClusterData) =>
      log.info("[leader] remove partition:{} forwarded to partition manager", keyToPath(partitionKey))
      zkPartitionsManager forward remove
      val newPartitions = zkClusterData.partitions - partitionKey
      notifyPartitionDiffs(zkClusterData.partitions, newPartitions)("leader")
      stay using zkClusterData.copy(partitions = newPartitions)
  })

  onTransition {
    case ZkClusterUninitialized -> ZkClusterActiveAsFollower =>
      //unstash all messages uninitialized state couldn't handle
      unstashAll

    case ZkClusterUninitialized -> ZkClusterActiveAsLeader =>
      //unstash all messages uninitialized state couldn't handle
      unstashAll
  }

  private[cluster] def rebalance(current: Map[ByteString, ZkPartitionData],
                                 base: Map[ByteString, ZkPartitionData],
                                 members:Set[Address]): Map[ByteString, ZkPartitionData] = {
    if (base.isEmpty) return Map.empty
    //spareLeader only when there're more than 1 VMs in the cluster
    val candidates = if(rebalanceLogic.spareLeader && members.size > 1) 
      members.filterNot{candidate => stateData.leader.exists(candidate == _)} 
    else 
      members
    
    val plan = rebalanceLogic.rebalance(
      rebalanceLogic.compensate(
        base.map{case(key, value) => key -> value.members},
        candidates.toSeq, 
        (key) => base.get(key).get.expectedSize
      ), 
      members
    )
    
    log.info("[leader] rebalance planned as:{}", plan.map{case (key, members) => keyToPath(key) -> members})
    val rebalanced = base map {
      case (key, value) => key -> value.copy(members = plan.getOrElse(key, value.members))
    }       
    notifyPartitionDiffs(current, rebalanced)("leader")
    
    val updates = rebalanced filterNot {
      case (key, value) if current.get(key).nonEmpty => value == current(key)
      case _ => false
    }
    
    if (updates.nonEmpty) zkPartitionsManager ! ZkRebalance(updates)

    rebalanced
  }

  private[cluster] def notifyPartitionDiffs(originalPartitions: Map[ByteString, ZkPartitionData],
                                            changes: Map[ByteString, ZkPartitionData])
                                           (role: String = "follower") = {
    val partitionDiffs = changes map {
      case(partitionKey, ZkPartitionData(_, newMembers, _, _)) =>
        val originalMembers = originalPartitions.get(partitionKey).map(_.members).getOrElse(Set.empty[Address])
        val onBoardMembers = newMembers diff originalMembers
        val dropOffMembers = originalMembers diff newMembers
        if (onBoardMembers.nonEmpty || dropOffMembers.nonEmpty)
          Some(ZkPartitionDiff(partitionKey, onBoardMembers, dropOffMembers))
        else 
          None
    } collect {
      case Some(diff) => diff
    } toList

    if (partitionDiffs.nonEmpty) {
      log.debug("[{}] notify {} about the the partition changes {}",
        role,
        whenPartitionUpdated,
        partitionDiffs.map(diff => (pathToKey(diff.partitionKey), diff.onBoardMembers, diff.dropOffMembers))
      )
      whenPartitionUpdated foreach (_ ! partitionDiffs)
    }
  }
}
