package com.rahulsinghai.actor

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, Behavior, SupervisorStrategy }
import com.amazonaws.AmazonServiceException
import com.amazonaws.auth.AWSStaticCredentialsProvider
import com.amazonaws.services.imagebuilder.model.{ ListImagesRequest, ListImagesResult }
import com.amazonaws.services.imagebuilder.{ AWSimagebuilder, AWSimagebuilderClientBuilder }
import com.rahulsinghai.conf.AWSToolkitConfig

import scala.jdk.CollectionConverters._

object ImageBuilderActor {

  // actor protocol
  sealed trait AMIResponse
  final case class AMISuccessResponse(description: String) extends AMIResponse
  final case class AMIFailureResponse(t: Throwable) extends AMIResponse

  sealed trait ImageBuilderCommand
  case class ListImages(owner: String, replyTo: ActorRef[AMISuccessResponse]) extends ImageBuilderCommand
  case class CreateNewImage(replyTo: ActorRef[AMISuccessResponse]) extends ImageBuilderCommand

  // Set up the Amazon image builder client
  val awsImageBuilderClient: AWSimagebuilder = AWSimagebuilderClientBuilder.standard()
    .withCredentials(new AWSStaticCredentialsProvider(AWSToolkitConfig.awsCredentials))
    .withRegion(AWSToolkitConfig.region)
    .build

  def apply(): Behavior[ImageBuilderCommand] =
    Behaviors
      .supervise(Behaviors.supervise(behaviour()).onFailure[IllegalStateException](SupervisorStrategy.resume)) // Ignore the failure and process the next message, instead:
      .onFailure[AmazonServiceException](SupervisorStrategy.resume)

  private def behaviour(): Behavior[ImageBuilderCommand] =
    Behaviors.receive { (context, message) =>
      message match {
        case ListImages(owner, replyTo) =>
          val l: ListImagesResult = awsImageBuilderClient.listImages(new ListImagesRequest().withOwner(owner))
          val images: String = l.getImageVersionList.asScala.map(_.toString).toList.mkString(", ")
          val description: String = s"These AMIs exist: [$images] owned by $owner."
          context.log.info(description)
          replyTo ! AMISuccessResponse(description)
          Behaviors.same

        case CreateNewImage(replyTo) =>
          Behaviors.same
      }
    }
}
