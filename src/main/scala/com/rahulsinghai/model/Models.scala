package com.rahulsinghai.model

import com.amazonaws.services.ec2.model.InstanceType

import scala.collection.immutable

final case class InstanceToCreate(imageId: String, instanceType: InstanceType, minCount: Int = 1, maxCount: Int = 1, associatePublicIpAddress: Boolean = false, subnetId: String, groups: String, nameTag: String)
final case class InstancesToCreate(instances: immutable.Seq[InstanceToCreate])
final case class SubnetToCreate(cidr: String, vpcId: String, nameTag: String)
final case class VpcToCreate(cidr: String, nameTag: String)
