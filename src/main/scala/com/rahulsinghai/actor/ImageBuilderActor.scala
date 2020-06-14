package com.rahulsinghai.actor

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, Behavior }
import com.amazonaws.auth.AWSStaticCredentialsProvider
import com.amazonaws.services.imagebuilder.model.{ ListImagesRequest, ListImagesResult }
import com.amazonaws.services.imagebuilder.{ AWSimagebuilder, AWSimagebuilderClientBuilder }
import com.rahulsinghai.conf.AWSToolkitConfig
import com.typesafe.scalalogging.StrictLogging

import scala.jdk.CollectionConverters._

object ImageBuilderActor extends StrictLogging {

  // actor protocol
  final case class AMIActionPerformed(description: String)

  sealed trait ImageBuilderCommand
  case class ListImages(replyTo: ActorRef[AMIActionPerformed]) extends ImageBuilderCommand
  case class CreateNewImage(replyTo: ActorRef[AMIActionPerformed]) extends ImageBuilderCommand

  // Set up the Amazon image builder client
  val awsImageBuilderClient: AWSimagebuilder = AWSimagebuilderClientBuilder.standard()
    .withCredentials(new AWSStaticCredentialsProvider(AWSToolkitConfig.awsCredentials))
    .withRegion(AWSToolkitConfig.region)
    .build

  def apply(): Behavior[ImageBuilderCommand] = behaviour()

  private def behaviour(): Behavior[ImageBuilderCommand] =
    Behaviors.receiveMessage {
      case ListImages(replyTo) =>
        val l: ListImagesResult = awsImageBuilderClient.listImages(new ListImagesRequest().withOwner("Self"))
        replyTo ! AMIActionPerformed(l.getImageVersionList.asScala.map(_.toString).toList.mkString(", "))
        Behaviors.same

      case CreateNewImage(replyTo) =>
        Behaviors.same
    }
}
