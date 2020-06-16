package com.rahulsinghai.actor

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, Behavior, SupervisorStrategy }
import com.amazonaws.AmazonServiceException
import com.amazonaws.services.ec2.AmazonEC2
import com.amazonaws.services.ec2.model._
import com.rahulsinghai.model.VpcToCreate

import scala.jdk.CollectionConverters._
import scala.util.{ Failure, Success, Try }

object VPCActor {
  // actor protocol
  sealed trait VPCResponse
  final case class VPCFailureResponse(t: Throwable) extends VPCResponse
  final case class VPCSuccessResponse(description: String) extends VPCResponse

  sealed trait VPCCommand
  case class CreateVpc(vpcToCreate: VpcToCreate, replyTo: ActorRef[VPCResponse]) extends VPCCommand
  case class GetVpcIdByNameTag(nameTag: String, replyTo: ActorRef[VPCResponse]) extends VPCCommand

  def apply(ec2Client: AmazonEC2): Behavior[VPCCommand] =
    Behaviors
      .supervise(Behaviors.supervise(behaviour(ec2Client, None)).onFailure[IllegalStateException](SupervisorStrategy.resume)) // Ignore the failure and process the next message, instead:
      .onFailure[AmazonServiceException](SupervisorStrategy.resume)

  private def behaviour(ec2Client: AmazonEC2, vpcIdOption: Option[String]): Behavior[VPCCommand] =
    Behaviors.receive { (context, message) =>
      message match {
        case GetVpcIdByNameTag(nameTag, replyTo) =>
          val tagFilter: Filter = new Filter().withName("tag:Name").withValues(nameTag)
          val describeVpcsRequest: DescribeVpcsRequest = new DescribeVpcsRequest().withFilters(tagFilter)
          Try(ec2Client.describeVpcs(describeVpcsRequest)) match {
            case Success(describeVpcsResult: DescribeVpcsResult) =>
              val vpcs: List[Vpc] = describeVpcsResult.getVpcs.asScala.toList
              if (vpcs.nonEmpty) {
                val vpcIdOption: Option[String] = vpcs.headOption.flatMap(x => Option(x.getVpcId))
                val description: String = s"VPC found with nameTag: $nameTag; has vpcId: ${vpcIdOption.getOrElse("")}."
                context.log.info(description)
                replyTo ! VPCSuccessResponse(vpcIdOption.getOrElse(""))
              } else {
                replyTo ! VPCFailureResponse(new Throwable(s"No VPC found with nameTag: $nameTag!"))
              }
              Behaviors.same

            case Failure(f) =>
              replyTo ! VPCFailureResponse(f)
              Behaviors.same
          }

        case CreateVpc(_, replyTo) if vpcIdOption.isDefined =>
          val description: String = s"VPC already exist with vpcId: ${vpcIdOption.getOrElse("")}."
          context.log.info(description)
          replyTo ! VPCSuccessResponse(description)
          Behaviors.same

        case CreateVpc(vpcToCreate, replyTo) if vpcIdOption.isEmpty =>
          // Check if VPC with same tag exists
          val tagFilter: Filter = new Filter().withName("tag:Name").withValues(vpcToCreate.nameTag)
          val describeVpcsRequest: DescribeVpcsRequest = new DescribeVpcsRequest().withFilters(tagFilter)
          Try(ec2Client.describeVpcs(describeVpcsRequest)) match {
            case Success(describeVpcsResult: DescribeVpcsResult) =>
              val vpcs: List[Vpc] = describeVpcsResult.getVpcs.asScala.toList
              if (vpcs.nonEmpty) {
                val vpcIdOption: Option[String] = vpcs.headOption.flatMap(x => Option(x.getVpcId))
                val description: String = s"VPC already exist with vpcId: ${vpcIdOption.getOrElse("")}."
                context.log.info(description)
                replyTo ! VPCSuccessResponse(description)
                behaviour(ec2Client, vpcIdOption)
              } else {
                // Create new VPC
                val createVpcRequest: CreateVpcRequest = new CreateVpcRequest()
                  .withCidrBlock(vpcToCreate.cidr)

                Try(ec2Client.createVpc(createVpcRequest)) match {
                  case Success(createVpcResult: CreateVpcResult) =>
                    val vpc = createVpcResult.getVpc
                    val vpcId: String = vpc.getVpcId

                    val describeVpcsRequest: DescribeVpcsRequest = new DescribeVpcsRequest().withFilters().withVpcIds(vpcId)
                    var describeVpcsResult: DescribeVpcsResult = ec2Client.describeVpcs(describeVpcsRequest)
                    var vpcOption: Option[Vpc] = describeVpcsResult.getVpcs.asScala.toList.headOption
                    var state: Option[String] = vpcOption.flatMap(x => Option(x.getState))

                    // Wait for Vpc to become available
                    val checkInterval: Long = 5000L
                    while (state.contains(VpcState.Pending.toString)) {
                      Thread.sleep(checkInterval)

                      describeVpcsResult = ec2Client.describeVpcs(describeVpcsRequest)
                      vpcOption = describeVpcsResult.getVpcs.asScala.toList.headOption
                      state = vpcOption.flatMap(x => Option(x.getState))
                    }

                    // Setting up the tags for the vpc
                    val createTagsRequest: CreateTagsRequest = new CreateTagsRequest()
                      .withResources(vpcId)
                      .withTags(new Tag("Name", vpcToCreate.nameTag))
                    ec2Client.createTags(createTagsRequest)

                    val description: String = s"Successfully created VPC: ${vpcToCreate.nameTag} with vpcId: $vpcId."
                    context.log.info(description)
                    replyTo ! VPCSuccessResponse(description)
                    behaviour(ec2Client, Some(vpcId))

                  case Failure(f) =>
                    replyTo ! VPCFailureResponse(f)
                    Behaviors.same
                }
              }

            case Failure(f) =>
              replyTo ! VPCFailureResponse(f)
              Behaviors.same
          }
      }
    }
}
