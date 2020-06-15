package com.rahulsinghai.actor

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, Behavior, SupervisorStrategy }
import com.amazonaws.AmazonServiceException
import com.amazonaws.services.ec2.model._
import com.amazonaws.services.ec2.AmazonEC2
import com.rahulsinghai.model.InstanceToCreate

import scala.jdk.CollectionConverters._
import scala.util.{ Failure, Success, Try }

object EC2Actor {

  // actor protocol
  sealed trait EC2Response
  final case class EC2FailureResponse(t: Throwable) extends EC2Response
  final case class EC2SuccessResponse(description: String) extends EC2Response

  sealed trait EC2Command
  case class CreateEC2Instance(instanceToCreate: InstanceToCreate, replyTo: ActorRef[EC2Response]) extends EC2Command
  case class StartEC2Instance(instanceId: String, replyTo: ActorRef[EC2Response]) extends EC2Command
  case class RebootEC2Instance(instanceId: String, replyTo: ActorRef[EC2Response]) extends EC2Command
  case class StopEC2Instance(instanceId: String, replyTo: ActorRef[EC2Response]) extends EC2Command

  def apply(ec2Client: AmazonEC2): Behavior[EC2Command] =
    Behaviors
      .supervise(Behaviors.supervise(behaviour(ec2Client, None)).onFailure[IllegalStateException](SupervisorStrategy.resume)) // Ignore the failure and process the next message, instead:
      .onFailure[AmazonServiceException](SupervisorStrategy.resume)

  private def behaviour(ec2Client: AmazonEC2, instanceIdOption: Option[String]): Behavior[EC2Command] =
    Behaviors.receive { (context, message) =>
      message match {
        case CreateEC2Instance(_, replyTo) if instanceIdOption.isDefined =>
          val description: String = s"EC2 instance already exist with instanceId: ${instanceIdOption.getOrElse("")}."
          context.log.info(description)
          replyTo ! EC2SuccessResponse(description)
          Behaviors.same

        case CreateEC2Instance(instanceToCreate, replyTo) if instanceIdOption.isEmpty =>
          val runInstancesRequest: RunInstancesRequest = new RunInstancesRequest().withImageId(instanceToCreate.imageId)
            .withInstanceType(instanceToCreate.instanceType)
            .withMinCount(instanceToCreate.minCount)
            .withMaxCount(instanceToCreate.maxCount)
            .withNetworkInterfaces(new InstanceNetworkInterfaceSpecification()
              .withAssociatePublicIpAddress(instanceToCreate.associatePublicIpAddress)
              .withDeviceIndex(0)
              .withSubnetId(instanceToCreate.subnetId)
              .withGroups(instanceToCreate.groups))

          Try(ec2Client.runInstances(runInstancesRequest)) match {
            case Success(runInstancesResult) =>
              val instance: Instance = runInstancesResult.getReservation.getInstances.get(0)
              val instanceId: String = instance.getInstanceId

              val describeInstanceRequest: DescribeInstancesRequest = new DescribeInstancesRequest().withInstanceIds(instanceId)
              var describeInstanceResult: DescribeInstancesResult = ec2Client.describeInstances(describeInstanceRequest)
              var instanceOption: Option[Instance] = describeInstanceResult
                .getReservations.asScala.toList
                .flatMap(_.getInstances.asScala.toList)
                .headOption
              var state: Option[String] = instanceOption.flatMap(x => Option(x.getState.getName))

              // Wait for instance to get up running
              val checkInterval: Long = 5000L
              while (state.contains(InstanceStateName.Pending.toString)) {
                Thread.sleep(checkInterval)

                describeInstanceResult = ec2Client.describeInstances(describeInstanceRequest)
                instanceOption = describeInstanceResult
                  .getReservations.asScala.toList
                  .flatMap(_.getInstances.asScala.toList)
                  .headOption
                state = instanceOption.flatMap(x => Option(x.getState.getName))
              }

              // Getting EC2 private IP
              val privateIP: String = instanceOption.flatMap(x => Option(x.getPrivateIpAddress)).getOrElse("")

              // Getting EC2 public IP
              val publicIP: String = instanceOption.flatMap(x => Option(x.getPublicIpAddress)).getOrElse("")

              // Setting up the tags for the instance
              val createTagsRequest: CreateTagsRequest = new CreateTagsRequest()
                .withResources(instanceId)
                .withTags(new Tag("Name", instanceToCreate.nameTag))
              ec2Client.createTags(createTagsRequest)

              val description: String = s"Successfully created EC2 instance: ${instanceToCreate.nameTag} with instanceId: $instanceId, private IP: $privateIP, public IP: $publicIP based on AMI: ${instanceToCreate.imageId}."
              context.log.info(description)
              replyTo ! EC2SuccessResponse(description)
              behaviour(ec2Client, Some(instanceId))

            case Failure(f) =>
              replyTo ! EC2FailureResponse(f)
              Behaviors.same
          }

        case StartEC2Instance(instanceId, replyTo) =>
          // Starting the Instance
          val startInstancesRequest: StartInstancesRequest = new StartInstancesRequest().withInstanceIds(instanceId)
          Try(ec2Client.startInstances(startInstancesRequest)) match {
            case Success(startInstancesResult) =>
              val description: String = s"Start EC2 Instance with instanceId: $instanceId result: ${startInstancesResult.toString}"
              context.log.info(description)
              replyTo ! EC2SuccessResponse(description)
              Behaviors.same

            case Failure(f) =>
              replyTo ! EC2FailureResponse(f)
              Behaviors.same
          }

        case RebootEC2Instance(instanceId, replyTo) =>
          val rebootInstancesRequest: RebootInstancesRequest = new RebootInstancesRequest()
            .withInstanceIds(instanceId)

          Try(ec2Client.rebootInstances(rebootInstancesRequest)) match {
            case Success(rebootInstancesResult) =>
              val description: String = s"Reboot EC2 Instance (instanceId: $instanceId) result: ${rebootInstancesResult.toString}"
              context.log.info(description)
              replyTo ! EC2SuccessResponse(description)
              Behaviors.same

            case Failure(f) =>
              replyTo ! EC2FailureResponse(f)
              Behaviors.same
          }

        case StopEC2Instance(instanceId, replyTo) =>
          val stopInstancesRequest: StopInstancesRequest = new StopInstancesRequest()
            .withInstanceIds(instanceId)

          Try(ec2Client.stopInstances(stopInstancesRequest)) match {
            case Success(stopInstancesResult) =>
              val stoppedInstanceName: String = ec2Client.stopInstances(stopInstancesRequest)
                .getStoppingInstances
                .get(0)
                .getPreviousState
                .getName

              val description: String = s"Stop EC2 Instance (instanceId: $instanceId), name: $stoppedInstanceName, result: ${stopInstancesResult.toString}"
              context.log.info(description)
              replyTo ! EC2SuccessResponse(description)
              Behaviors.same

            case Failure(f) =>
              replyTo ! EC2FailureResponse(f)
              Behaviors.same
          }
      }
    }
}
