package com.rahulsinghai.route

import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.http.caching.scaladsl.Cache
import akka.http.scaladsl.model.{StatusCodes, _}
import akka.http.scaladsl.server.Directives.{complete, parameters, _}
import akka.http.scaladsl.server.directives.CachingDirectives._
import akka.http.scaladsl.server.directives.Credentials
import akka.http.scaladsl.server.directives.PathDirectives.path
import akka.http.scaladsl.server.{RequestContext, Route, RouteResult}
import akka.http.scaladsl.unmarshalling.PredefinedFromStringUnmarshallers.CsvSeq
import akka.util.Timeout
import com.amazonaws.services.ec2.model.InstanceType
import com.rahulsinghai.actor.EC2Actor._
import com.rahulsinghai.actor.ImageBuilderActor.ListImages
import com.rahulsinghai.actor.{EC2Actor, ImageBuilderActor}
import com.rahulsinghai.conf.AWSToolkitConfig
import com.rahulsinghai.model._
import com.rahulsinghai.util.ApiMessages
import com.typesafe.scalalogging.StrictLogging

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}
import scala.xml.Elem

class Routes(imageBuilderActor: ActorRef[ImageBuilderActor.ImageBuilderCommand], ec2Actor: ActorRef[EC2Actor.EC2Command])(implicit val system: ActorSystem[_])
  extends RouteWrapper with ApiMessages with JsonSupport with StrictLogging {

  // Required by RouteWrapper
  implicit val ec: ExecutionContext = system.executionContext

  // If ask takes more time than this to complete the request is failed
  private implicit val timeout: Timeout = AWSToolkitConfig.akkaHttpServerRequestTimeout

  def listAMIs(): Future[ActionPerformed] = imageBuilderActor.ask(ListImages)

  def createEC2Instance(instanceToCreate: InstanceToCreate): Future[CreateEC2InstanceResponse] =
    ec2Actor.ask(CreateEC2Instance(instanceToCreate, _))

  def startEC2Instance(instanceId: String): Future[ActionPerformed] =
    ec2Actor.ask(StartEC2Instance(instanceId, _))

  def rebootEC2Instance(instanceId: String): Future[ActionPerformed] =
    ec2Actor.ask(RebootEC2Instance(instanceId, _))

  def stopEC2Instance(instanceId: String): Future[ActionPerformed] =
    ec2Actor.ask(StopEC2Instance(instanceId, _))

  // Akka HTTP caching. Default settings are defined in application.conf
  // Use the request's URI as the cache's key
  val keyerFunction: PartialFunction[RequestContext, Uri] = {
    case r: RequestContext =>
      r.request.uri
  }

  // Frequency-biased LFU cache will evict an entry which hasn’t been used recently or very often.
  val lfuRouteCache: Cache[Uri, RouteResult] = routeCache[Uri](system.classicSystem)

  val userPassAuthenticator: AuthenticatorPF[String] = {
    case p @ Credentials.Provided(id) if p.verify(AWSToolkitConfig.akkaHttpServerPassword) => id
  }

  //#all-routes
  val routes: Route = wrapRoutesWithGzip(
    ignoreTrailingSlash {
      pathEndOrSingleSlash {
        concat(
          cache(lfuRouteCache, keyerFunction)(
            get {
              complete {
                Try(HttpResponse(entity = HttpEntity(ContentTypes.`text/html(UTF-8)`, indexHtml.mkString)))
              }
            }
          )
        )
      } ~
      path("favicon.ico") {
        cache(lfuRouteCache, keyerFunction)(
          getFromResource("favicon.ico", MediaTypes.`image/x-icon`) // will look for the file favicon.ico inside your `resources` folder
        )
      } ~
      path("ping") {
        concat(
          get {
            cache(lfuRouteCache, keyerFunction) {
              complete {
                try {
                  HttpResponse(StatusCodes.OK, entity = HttpEntity("pong"))
                } catch {
                  case NonFatal(ex) =>
                    logger.error(ex.getMessage)
                    HttpResponse(StatusCodes.InternalServerError, entity = s"Error found")
                }
              }
            }
          }
        )
      } ~
      path("status") {
        concat(
          cache(lfuRouteCache, keyerFunction)(
            get {
              onComplete(getCurrentStatus) {
                case Success(value) => complete((StatusCodes.OK, HttpEntity(ContentTypes.`application/json`, value)))
                case Failure(ex)    => complete((StatusCodes.InternalServerError, s"An error occurred while fetching current status: ${ex.getMessage}"))
              }
            }
          )
        )
      } ~
      path("version") {
        concat(
          cache(lfuRouteCache, keyerFunction)(
            get {
              onComplete(getCurrentVersion) {
                case Success(value) => complete((StatusCodes.OK, HttpEntity(ContentTypes.`application/json`, value)))
                case Failure(ex)    => complete((StatusCodes.InternalServerError, s"An error occurred while fetching current version: ${ex.getMessage}"))
              }
            }
          )
        )
      } ~
      pathPrefix("ami") {
        concat(
          authenticateBasicPF(realm = "Big Data TLL user area", userPassAuthenticator) { _: String =>
            path("list") {
              concat(
                cache(lfuRouteCache, keyerFunction)(
                  get {
                    val listAMIsFuture: Future[ActionPerformed] = listAMIs()
                    onComplete(listAMIsFuture) {
                      case Success(actionPerformed: ActionPerformed) =>
                        logger.info(s"These AMIs exist: ${actionPerformed.description}")
                        complete((StatusCodes.OK, actionPerformed))
                      case Failure(t: Throwable) =>
                        val failureMessage: String = s"List images operation failed!\n${t.getLocalizedMessage}"
                        logger.error(failureMessage, t)
                        complete((StatusCodes.InternalServerError, failureMessage))
                    }
                  }
                )
              )
            } ~
            path("startInstance") {
              concat(
                parameter("instanceId") { instanceId =>
                  get {
                    val actionPerformedFuture: Future[ActionPerformed] = startEC2Instance(instanceId)
                    onComplete(actionPerformedFuture) {
                      case Success(actionPerformed: ActionPerformed) =>
                        logger.info(s"${actionPerformed.description}")
                        complete((StatusCodes.OK, actionPerformed))
                      case Failure(t: Throwable) =>
                        val failureMessage: String = s"EC2 instance with instanceId: $instanceId failed to start!\n${t.getLocalizedMessage}"
                        logger.error(failureMessage, t)
                        complete((StatusCodes.InternalServerError, failureMessage))
                    }
                  }
                }
              )
            } ~
            path("stopInstance") {
              concat(
                parameter("instanceId") { instanceId =>
                  get {
                    val actionPerformedFuture: Future[ActionPerformed] = stopEC2Instance(instanceId)
                    onComplete(actionPerformedFuture) {
                      case Success(actionPerformed: ActionPerformed) =>
                        logger.info(s"${actionPerformed.description}")
                        complete((StatusCodes.OK, actionPerformed))
                      case Failure(t: Throwable) =>
                        val failureMessage: String = s"EC2 instance with instanceId: $instanceId failed to stop!\n${t.getLocalizedMessage}"
                        logger.error(failureMessage, t)
                        complete((StatusCodes.InternalServerError, failureMessage))
                    }
                  }
                }
              )
            } ~
            path("rebootInstances") {
              concat(
                parameter("instanceId".as(CsvSeq[String])) { instanceId =>
                  get {
                    val actionPerformedFuture: Future[ActionPerformed] = rebootEC2Instance(instanceId.head)
                    onComplete(actionPerformedFuture) {
                      case Success(actionPerformed: ActionPerformed) =>
                        logger.info(s"${actionPerformed.description}")
                        complete((StatusCodes.OK, actionPerformed))
                      case Failure(t: Throwable) =>
                        val failureMessage: String = s"EC2 instance with instanceId: ${instanceId.head} failed to reboot!\n${t.getLocalizedMessage}"
                        logger.error(failureMessage, t)
                        complete((StatusCodes.InternalServerError, failureMessage))
                    }
                  }
                }
              )
            }
          }
        )
      } ~
      pathPrefix("ec2") {
        concat(
          path("createInstance") {
            concat(
              parameters(("imageId", "instanceType" ? "t2.micro", "minCount" ? 1, "maxCount" ? 1, "associatePublicIpAddress" ? true, "subnetId" ? "0.0.0.0", "groups" ? "", "nameTag" ? "")) { (imageId, instanceType, minCount, maxCount, associatePublicIpAddress, subnetId, groups, nameTag) =>
                cache(lfuRouteCache, keyerFunction)(
                  get {
                    val createEC2InstanceResponseFuture: Future[CreateEC2InstanceResponse] = createEC2Instance(InstanceToCreate(imageId, InstanceType.fromValue(instanceType), minCount, maxCount, associatePublicIpAddress, subnetId, groups, nameTag))
                    onComplete(createEC2InstanceResponseFuture) {
                      case Success(createEC2InstanceResponse: CreateEC2InstanceResponse) =>
                        logger.info(createEC2InstanceResponse.description)
                        complete((StatusCodes.OK, createEC2InstanceResponse))
                      case Failure(t: Throwable) =>
                        val failureMessage: String = s"EC2 instance creation failed!\n${t.getLocalizedMessage}"
                        logger.error(failureMessage, t)
                        complete((StatusCodes.InternalServerError, failureMessage))
                    }
                  }
                )
              }
            )
          } ~
          path("startInstance") {
            concat(
              parameter("instanceId") { instanceId =>
                cache(lfuRouteCache, keyerFunction)(
                  get {
                    val actionPerformedFuture: Future[ActionPerformed] = startEC2Instance(instanceId)
                    onComplete(actionPerformedFuture) {
                      case Success(actionPerformed: ActionPerformed) =>
                        logger.info(s"${actionPerformed.description}")
                        complete((StatusCodes.OK, actionPerformed))
                      case Failure(t: Throwable) =>
                        val failureMessage: String = s"EC2 instance with instanceId: $instanceId failed to start!\n${t.getLocalizedMessage}"
                        logger.error(failureMessage, t)
                        complete((StatusCodes.InternalServerError, failureMessage))
                    }
                  }
                )
              }
            )
          } ~
          path("stopInstance") {
            concat(
              parameter("instanceId") { instanceId =>
                cache(lfuRouteCache, keyerFunction)(
                  get {
                    val actionPerformedFuture: Future[ActionPerformed] = stopEC2Instance(instanceId)
                    onComplete(actionPerformedFuture) {
                      case Success(actionPerformed: ActionPerformed) =>
                        logger.info(s"${actionPerformed.description}")
                        complete((StatusCodes.OK, actionPerformed))
                      case Failure(t: Throwable) =>
                        val failureMessage: String = s"EC2 instance with instanceId: $instanceId failed to stop!\n${t.getLocalizedMessage}"
                        logger.error(failureMessage, t)
                        complete((StatusCodes.InternalServerError, failureMessage))
                    }
                  }
                )
              }
            )
          } ~
          path("rebootInstance") {
            concat(
              parameter("instanceId") { instanceId =>
                cache(lfuRouteCache, keyerFunction)(
                  get {
                    val actionPerformedFuture: Future[ActionPerformed] = rebootEC2Instance(instanceId)
                    onComplete(actionPerformedFuture) {
                      case Success(actionPerformed: ActionPerformed) =>
                        logger.info(s"${actionPerformed.description}")
                        complete((StatusCodes.OK, actionPerformed))
                      case Failure(t: Throwable) =>
                        val failureMessage: String = s"EC2 instance with instanceId: $instanceId failed to reboot!\n${t.getLocalizedMessage}"
                        logger.error(failureMessage, t)
                        complete((StatusCodes.InternalServerError, failureMessage))
                    }
                  }
                )
              }
            )
          }
        )
      }
    }
  )
  //#all-routes

  // Helpers //
  lazy val indexHtml: Elem =
    <html>
      <body>
        <h1>Welcome to <i>AWS toolkit</i>!</h1>
        <p>Defined resources:</p>
        <ul>
          <li><a href="/">Home page</a></li>
          <li><a href="/status">status</a></li>
          <li><a href="/version">version</a></li>
          <li><a href="/ping">ping</a></li>
        </ul>
        <br/>
        <ul>
          <li>List AMIs: <a href="/ami/list">/ami/list</a></li>
          <li>Create new AMI (idempotent): <a href="/ami/create?">/ami/create?</a></li>
        </ul>
        <br/>
        <ul>
          <li>Create EC2 instance: <a href="/ec2/createInstance?imageId=prod_dc1%26instanceType=t2.micro%26minCount=1%26maxCount=1%26associatePublicIpAddress=true%26subnetId=0.0.0.0%26groups=ss%26nameTag=awsToolkitExample1">/ec2/createInstance?imageId=prod_dc1%26instanceType=%26minCount=1%26maxCount=1%26associatePublicIpAddress=true%26subnetId=0.0.0.0%26groups=ss%26nameTag=prod_dc2</a></li>
          <li>Start EC2 instance: <a href="/ec2/startInstance?instanceId=i-1234567890abcdef0">/ec2/startInstance?instanceId=i-1234567890abcdef0</a></li>
          <li>Stop EC2 instance: <a href="/ec2/stopInstance?instanceId=i-1234567890abcdef0">/ec2/stopInstance?instanceId=i-1234567890abcdef0</a></li>
          <li>Reboot EC2 instance: <a href="/ec2/rebootInstance?instanceId=i-1234567890abcdef0">/ec2/rebootInstance?instanceId=i-1234567890abcdef0</a></li>
        </ul>
      </body>
    </html>
}
