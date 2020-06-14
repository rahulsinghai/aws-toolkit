package com.rahulsinghai

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Route
import com.rahulsinghai.actor.{ EC2Actor, ImageBuilderActor }
import com.rahulsinghai.conf.AWSToolkitConfig
import com.rahulsinghai.route.Routes

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

    val futureBinding = Http().bindAndHandle(routes, AWSToolkitConfig.akkaHttpServerHttpInterface, AWSToolkitConfig.akkaHttpServerHttpPort)
    futureBinding.onComplete {
      case Success(binding) =>
        val address = binding.localAddress
        system.log.info("Server online at http://{}:{}/", address.getHostString, address.getPort)
      case Failure(ex) =>
        system.log.error("Failed to bind HTTP endpoint, terminating system", ex)
        system.terminate()
    }
  }

  def main(args: Array[String]): Unit = {
    //#server-bootstrapping
    val rootBehavior = Behaviors.setup[Nothing] { context =>
      val imageBuilderActor = context.spawn(ImageBuilderActor(), "ImageBuilderActor")
      context.watch(imageBuilderActor)

      val ec2Actor = context.spawn(EC2Actor(), "EC2Actor")
      context.watch(ec2Actor)

      val routes = new Routes(imageBuilderActor, ec2Actor)(context.system)
      startHttpServer(routes.routes, context.system)

      Behaviors.empty
    }

    ActorSystem[Nothing](rootBehavior, "AWSToolkitApp")
  }
}
