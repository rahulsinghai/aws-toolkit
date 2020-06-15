package com.rahulsinghai.actor

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import com.amazonaws.AmazonServiceException
import com.amazonaws.services.ec2.AmazonEC2
import com.amazonaws.services.ec2.model._
import com.rahulsinghai.actor.VPCActor.VPCCommand
import com.rahulsinghai.model.SubnetToCreate

import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

object SubnetActor {
  // actor protocol
  sealed trait SubnetResponse
  final case class SubnetFailureResponse(t: Throwable) extends SubnetResponse
  final case class SubnetSuccessResponse(description: String) extends SubnetResponse

  sealed trait SubnetCommand
  case class CreateSubnet(subnetToCreate: SubnetToCreate, replyTo: ActorRef[SubnetResponse]) extends SubnetCommand

  def apply(ec2Client: AmazonEC2, vpcActor: ActorRef[VPCCommand]): Behavior[SubnetCommand] =
    Behaviors
      .supervise(Behaviors.supervise(behaviour(ec2Client, vpcActor, None)).onFailure[IllegalStateException](SupervisorStrategy.resume)) // Ignore the failure and process the next message, instead:
      .onFailure[AmazonServiceException](SupervisorStrategy.resume)

  private def behaviour(ec2Client: AmazonEC2, vpcActor: ActorRef[VPCCommand], subnetIdOption: Option[String]): Behavior[SubnetCommand] =
    Behaviors.receive { (context, message) =>
      message match {
        case CreateSubnet(_, replyTo) if subnetIdOption.isDefined =>
          val description: String = s"Subnet already exist with subnetId: ${subnetIdOption.getOrElse("")}."
          context.log.info(description)
          replyTo ! SubnetSuccessResponse(description)
          Behaviors.same

        case CreateSubnet(subnetToCreate, replyTo) if subnetIdOption.isEmpty =>

          // Check if Subnet with same tag exists
          val tagFilter: Filter = new Filter().withName("tag:Name").withValues(subnetToCreate.nameTag)
          val describeSubnetsRequest: DescribeSubnetsRequest = new DescribeSubnetsRequest().withFilters(tagFilter)
          Try(ec2Client.describeSubnets(describeSubnetsRequest)) match {
            case Success(describeSubnetsResult: DescribeSubnetsResult) =>

              val subnets: List[Subnet] = describeSubnetsResult.getSubnets.asScala.toList
              if(subnets.nonEmpty) {
                // Subnet already exists
                val subnetIdOption: Option[String] = subnets.headOption.flatMap(x => Option(x.getSubnetId))
                val description: String = s"Subnet already exist with subnetId: ${subnetIdOption.getOrElse("")}."
                context.log.info(description)
                replyTo ! SubnetSuccessResponse(description)
                behaviour(ec2Client, vpcActor, subnetIdOption)
              } else {
                // Create new Subnet
                val createSubnetRequest: CreateSubnetRequest = new CreateSubnetRequest()
                  .withCidrBlock(subnetToCreate.cidr)
                  .withVpcId(subnetToCreate.vpcId)

                Try(ec2Client.createSubnet(createSubnetRequest)) match {
                  case Success(createSubnetResult: CreateSubnetResult) =>
                    val subnet = createSubnetResult.getSubnet
                    val subnetId: String = subnet.getSubnetId

                    val describeSubnetsRequest: DescribeSubnetsRequest = new DescribeSubnetsRequest().withFilters().withSubnetIds(subnetId)
                    var describeSubnetsResult: DescribeSubnetsResult = ec2Client.describeSubnets(describeSubnetsRequest)
                    var subnetOption: Option[Subnet] = describeSubnetsResult.getSubnets.asScala.toList.headOption
                    var state: Option[String] = subnetOption.flatMap(x => Option(x.getState))

                    // Wait for Subnet to become available
                    val checkInterval: Long = 5000L
                    while (state.contains(SubnetState.Pending.toString)) {
                      Thread.sleep(checkInterval)

                      describeSubnetsResult = ec2Client.describeSubnets(describeSubnetsRequest)
                      subnetOption = describeSubnetsResult.getSubnets.asScala.toList.headOption
                      state = subnetOption.flatMap(x => Option(x.getState))
                    }

                    // Setting up the tags for the subnet
                    val createTagsRequest: CreateTagsRequest = new CreateTagsRequest()
                      .withResources(subnetId)
                      .withTags(new Tag("Name", subnetToCreate.nameTag))
                    ec2Client.createTags(createTagsRequest)

                    val description: String = s"Successfully created Subnet: ${subnetToCreate.nameTag} with subnetId: $subnetId."
                    context.log.info(description)
                    replyTo ! SubnetSuccessResponse(description)
                    behaviour(ec2Client, vpcActor, Some(subnetId))

                  case Failure(f) =>
                    replyTo ! SubnetFailureResponse(f)
                    Behaviors.same
                }
              }

            case Failure(f) =>
              replyTo ! SubnetFailureResponse(f)
              Behaviors.same
          }
      }
    }
}
