package com.rahulsinghai

import java.net.InetSocketAddress

import akka.actor.typed.{ ActorRef, ActorSystem, Behavior }
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Route
import com.amazonaws.auth.AWSStaticCredentialsProvider
import com.amazonaws.services.ec2.{ AmazonEC2, AmazonEC2ClientBuilder }
import com.rahulsinghai.actor.{ EC2Actor, ImageBuilderActor, SubnetActor, VPCActor }
import com.rahulsinghai.conf.AWSToolkitConfig
import com.rahulsinghai.route.Routes

import scala.concurrent.Future
import scala.util.{ Failure, Success }

//#main-class
object AWSToolkitApp {

  /**
   * Starts HTTP server
   * @param routes Routes to listen to
   * @param system Actor system
   */
  private def startHttpServer(routes: Route, system: ActorSystem[_]): Unit = {
    // Akka HTTP still needs a classic ActorSystem to start
    implicit val classicSystem: akka.actor.ActorSystem = system.toClassic
    import system.executionContext

    val futureBinding: Future[Http.ServerBinding] = Http().bindAndHandle(routes, AWSToolkitConfig.akkaHttpServerHttpInterface, AWSToolkitConfig.akkaHttpServerHttpPort)
    futureBinding.onComplete {
      case Success(binding) =>
        val address: InetSocketAddress = binding.localAddress
        system.log.info("Server online at http://{}:{}/", address.getHostString, address.getPort)
      case Failure(ex) =>
        system.log.error("Failed to bind HTTP endpoint, terminating system", ex)
        system.terminate()
    }
  }

  def main(args: Array[String]): Unit = {
    //#server-bootstrapping
    val rootBehavior: Behavior[Nothing] = Behaviors.setup[Nothing] { context =>
      // Set up the Amazon ec2 client
      val ec2Client: AmazonEC2 = AmazonEC2ClientBuilder.standard()
        .withCredentials(new AWSStaticCredentialsProvider(AWSToolkitConfig.awsCredentials))
        .withRegion(AWSToolkitConfig.region)
        .build

      val imageBuilderActor = context.spawn(ImageBuilderActor(), "ImageBuilderActor")
      //context.watch(imageBuilderActor)  // We don't want to terminate if any exception occurs in Child actors

      val ec2Actor: ActorRef[EC2Actor.EC2Command] = context.spawn(EC2Actor(ec2Client), "EC2Actor")
      val vpcActor: ActorRef[VPCActor.VPCCommand] = context.spawn(VPCActor(ec2Client), "VPCActor")
      val subnetActor: ActorRef[SubnetActor.SubnetCommand] = context.spawn(SubnetActor(ec2Client, vpcActor), "SubnetActor")

      val routes: Routes = new Routes(imageBuilderActor, ec2Actor, vpcActor, subnetActor)(context.system)
      startHttpServer(routes.routes, context.system)

      Behaviors.empty
    }

    ActorSystem[Nothing](rootBehavior, "AWSToolkitApp")
  }
}
