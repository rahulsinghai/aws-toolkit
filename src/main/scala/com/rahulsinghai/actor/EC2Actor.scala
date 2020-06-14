package com.rahulsinghai.actor

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import com.amazonaws.auth.AWSStaticCredentialsProvider
import com.amazonaws.services.ec2.model._
import com.amazonaws.services.ec2.{AmazonEC2, AmazonEC2ClientBuilder}
import com.amazonaws.util.EC2MetadataUtils
import com.rahulsinghai.conf.AWSToolkitConfig
import com.rahulsinghai.model.{ActionPerformed, CreateEC2InstanceResponse, InstanceToCreate}
import com.typesafe.scalalogging.StrictLogging

import scala.jdk.CollectionConverters._

object EC2Actor extends StrictLogging {

  // actor protocol
  sealed trait EC2Command
  case class CreateEC2Instance(instanceToCreate: InstanceToCreate, replyTo: ActorRef[CreateEC2InstanceResponse]) extends EC2Command
  // CreateEC2Instance("ami-777777", "t2.micro", 1, 1, true, subnet-77777, sg-77777, "test-ec2")
  case class StartEC2Instance(instanceId: String, replyTo: ActorRef[ActionPerformed]) extends EC2Command
  case class RebootEC2Instance(instanceId: String, replyTo: ActorRef[ActionPerformed]) extends EC2Command
  case class StopEC2Instance(instanceId: String, replyTo: ActorRef[ActionPerformed]) extends EC2Command

  // Set up the Amazon ec2 client
  val ec2Client: AmazonEC2 = AmazonEC2ClientBuilder.standard()
    .withCredentials(new AWSStaticCredentialsProvider(AWSToolkitConfig.awsCredentials))
    .withRegion(AWSToolkitConfig.region)
    .build

  def apply(): Behavior[EC2Command] = behaviour()

  private def behaviour(): Behavior[EC2Command] =
    Behaviors.receiveMessage {
      case CreateEC2Instance(instanceToCreate, replyTo) =>
        val runInstancesRequest: RunInstancesRequest = new RunInstancesRequest().withImageId(instanceToCreate.imageId)
          .withInstanceType(instanceToCreate.instanceType)
          .withMinCount(instanceToCreate.minCount)
          .withMaxCount(instanceToCreate.maxCount)
          .withNetworkInterfaces(new InstanceNetworkInterfaceSpecification()
            .withAssociatePublicIpAddress(instanceToCreate.associatePublicIpAddress)
            .withDeviceIndex(0)
            .withSubnetId(instanceToCreate.subnetId)
            .withGroups(instanceToCreate.groups)
          )

        val runInstancesResult: RunInstancesResult = ec2Client.runInstances(runInstancesRequest)

        val instance: Instance = runInstancesResult.getReservation.getInstances.get(0)
        val instanceId: String = instance.getInstanceId

        // Getting EC2 private IP
        val privateIP: String = EC2MetadataUtils.getInstanceInfo.getPrivateIp

        // Getting EC2 public IP
        val publicIP: String = ec2Client.describeInstances(new DescribeInstancesRequest()
          .withInstanceIds(instanceId))
          .getReservations.asScala.toList
          .flatMap(_.getInstances.asScala.toList)
          .headOption
          .flatMap(x => Option(x.getPublicIpAddress))
          .getOrElse("")

        // Setting up the tags for the instance
        val createTagsRequest: CreateTagsRequest = new CreateTagsRequest()
          .withResources(instanceId)
          .withTags(new Tag("Name", instanceToCreate.nameTag))
        ec2Client.createTags(createTagsRequest)

        val description: String = s"Successfully created EC2 instance: ${instanceToCreate.nameTag} with instanceId: $instanceId, private IP: $privateIP, public IP: $publicIP based on AMI: ${instanceToCreate.imageId}."
        logger.info(description)
        replyTo ! CreateEC2InstanceResponse(instanceId, description)
        Behaviors.same

      case StartEC2Instance(instanceId, replyTo) =>
        // Starting the Instance
        val startInstancesRequest: StartInstancesRequest = new StartInstancesRequest().withInstanceIds(instanceId)
        ec2Client.startInstances(startInstancesRequest)

        val description: String = s"EC2 Instance with instanceId: $instanceId started!"
        replyTo ! ActionPerformed(description)
        Behaviors.same

      case RebootEC2Instance(instanceId, replyTo) =>
        val rebootInstancesRequest: RebootInstancesRequest = new RebootInstancesRequest()
          .withInstanceIds(instanceId)

        val rebootInstancesResult: RebootInstancesResult = ec2Client.rebootInstances(rebootInstancesRequest)
        val description: String = s"Reboot EC2 Instance (instanceId: $instanceId) result: ${rebootInstancesResult.toString}"
        logger.info(description)
        replyTo ! ActionPerformed(description)
        Behaviors.same

      case StopEC2Instance(instanceId, replyTo) =>
        val stopInstancesRequest: StopInstancesRequest = new StopInstancesRequest()
          .withInstanceIds(instanceId)

        val stoppedInstanceName: String = ec2Client.stopInstances(stopInstancesRequest)
          .getStoppingInstances
          .get(0)
          .getPreviousState
          .getName

        val description: String = s"Stop EC2 Instance (instanceId: $instanceId) name: $stoppedInstanceName"
        logger.info(description)
        replyTo ! ActionPerformed(description)
        Behaviors.same
    }
}
