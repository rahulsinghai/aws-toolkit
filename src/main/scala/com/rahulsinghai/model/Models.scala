package com.rahulsinghai.model

import com.amazonaws.services.ec2.model.InstanceType

import scala.collection.immutable

final case class ActionPerformed(description: String)
final case class CreateEC2InstanceResponse(instanceId: String, description: String)

final case class InstanceToCreate(imageId: String, instanceType: InstanceType, minCount: Int = 1, maxCount: Int = 1, associatePublicIpAddress: Boolean = true, subnetId: String, groups: String, nameTag: String)
final case class InstancesToCreate(instances: immutable.Seq[InstanceToCreate])
