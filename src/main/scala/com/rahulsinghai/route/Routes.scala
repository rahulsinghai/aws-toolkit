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
import akka.util.Timeout
import com.amazonaws.services.ec2.model.InstanceType
import com.rahulsinghai.actor.EC2Actor._
import com.rahulsinghai.actor.ImageBuilderActor.{AMIActionPerformed, ListImages}
import com.rahulsinghai.actor.SubnetActor.{CreateSubnet, SubnetFailureResponse, SubnetResponse, SubnetSuccessResponse}
import com.rahulsinghai.actor.VPCActor.{CreateVpc, VPCFailureResponse, VPCResponse, VPCSuccessResponse}
import com.rahulsinghai.actor.{EC2Actor, ImageBuilderActor, SubnetActor, VPCActor}
import com.rahulsinghai.conf.AWSToolkitConfig
import com.rahulsinghai.model._
import com.rahulsinghai.util.ApiMessages
import com.typesafe.scalalogging.StrictLogging

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import scala.xml.Elem

class Routes(imageBuilderActor: ActorRef[ImageBuilderActor.ImageBuilderCommand], ec2Actor: ActorRef[EC2Actor.EC2Command],
             vpcActor: ActorRef[VPCActor.VPCCommand], subnetActor: ActorRef[SubnetActor.SubnetCommand])(implicit val system: ActorSystem[_])
  extends RouteWrapper with ApiMessages with JsonSupport with StrictLogging {

  // Required by RouteWrapper
  implicit val ec: ExecutionContext = system.executionContext

  // If ask takes more time than this to complete the request is failed
  private implicit val timeout: Timeout = AWSToolkitConfig.akkaHttpServerRequestTimeout

  def listAMIs(): Future[AMIActionPerformed] = imageBuilderActor.ask(ListImages)

  def createSubnet(subnetToCreate: SubnetToCreate): Future[SubnetSuccessResponse] =
    subnetActor.ask[SubnetResponse](CreateSubnet(subnetToCreate, _)).flatMap {
      case SubnetFailureResponse(throwable)          => Future.failed(throwable) // https://doc.akka.io/docs/akka/current/typed/interaction-patterns.html#request-response-with-ask-from-outside-an-actor
      case subnetSuccessResponse: SubnetSuccessResponse => Future.successful(subnetSuccessResponse)
    }

  def createVPC(vpcToCreate: VpcToCreate): Future[VPCSuccessResponse] =
    vpcActor.ask[VPCResponse](CreateVpc(vpcToCreate, _)).flatMap {
      case VPCFailureResponse(throwable)          => Future.failed(throwable) // https://doc.akka.io/docs/akka/current/typed/interaction-patterns.html#request-response-with-ask-from-outside-an-actor
      case vpcSuccessResponse: VPCSuccessResponse => Future.successful(vpcSuccessResponse)
    }

  def createEC2Instance(instanceToCreate: InstanceToCreate): Future[EC2SuccessResponse] =
    ec2Actor.ask[EC2Response](CreateEC2Instance(instanceToCreate, _)).flatMap {
      case EC2FailureResponse(throwable)          => Future.failed(throwable) // https://doc.akka.io/docs/akka/current/typed/interaction-patterns.html#request-response-with-ask-from-outside-an-actor
      case ec2SuccessResponse: EC2SuccessResponse => Future.successful(ec2SuccessResponse)
    }

  def startEC2Instance(instanceId: String): Future[EC2SuccessResponse] =
    ec2Actor.ask[EC2Response](StartEC2Instance(instanceId, _)).flatMap {
      case EC2FailureResponse(throwable)          => Future.failed(throwable)
      case ec2SuccessResponse: EC2SuccessResponse => Future.successful(ec2SuccessResponse)
    }

  def rebootEC2Instance(instanceId: String): Future[EC2SuccessResponse] =
    ec2Actor.ask[EC2Response](RebootEC2Instance(instanceId, _)).flatMap {
      case EC2FailureResponse(throwable)          => Future.failed(throwable)
      case ec2SuccessResponse: EC2SuccessResponse => Future.successful(ec2SuccessResponse)
    }

  def stopEC2Instance(instanceId: String): Future[EC2SuccessResponse] =
    ec2Actor.ask[EC2Response](StopEC2Instance(instanceId, _)).flatMap {
      case EC2FailureResponse(throwable)          => Future.failed(throwable)
      case ec2SuccessResponse: EC2SuccessResponse => Future.successful(ec2SuccessResponse)
    }

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
                Try(HttpResponse(StatusCodes.OK, entity = HttpEntity("pong")))
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
                    val listAMIsFuture: Future[AMIActionPerformed] = listAMIs()
                    onComplete(listAMIsFuture) {
                      case Success(actionPerformed: AMIActionPerformed) =>
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
            }
          }
        )
      } ~
      pathPrefix("vpc") {
        concat(
          path("createVpc") {
            concat(
              parameters(("cidr" ? "10.0.0.0/28", "nameTag" ? "awsToolkitExVpc")) { (cidr, nameTag) =>
                cache(lfuRouteCache, keyerFunction)(
                  get {
                    val vpcResponseFuture: Future[VPCSuccessResponse] = createVPC(VpcToCreate(cidr, nameTag))
                    onComplete(vpcResponseFuture) {
                      case Success(vpcSuccessResponse: VPCSuccessResponse) =>
                        logger.info(vpcSuccessResponse.description)
                        complete((StatusCodes.OK, vpcSuccessResponse))
                      case Failure(t: Throwable) =>
                        val failureMessage: String = s"VPC creation failed!\n${t.getLocalizedMessage}"
                        logger.error(failureMessage, t)
                        complete((StatusCodes.InternalServerError, failureMessage))
                    }
                  }
                )
              }
            )
          }
        )
      } ~
      pathPrefix("subnet") {
        concat(
          path("createSubnet") {
            concat(
              parameters(("cidr" ? "10.0.0.0/28", "vpcId", "nameTag" ? "awsToolkitExSubnet")) { (cidr, vpcId, nameTag) =>
                cache(lfuRouteCache, keyerFunction)(
                  get {
                    val subnetResponseFuture: Future[SubnetSuccessResponse] = createSubnet(SubnetToCreate(cidr, vpcId, nameTag))
                    onComplete(subnetResponseFuture) {
                      case Success(subnetSuccessResponse: SubnetSuccessResponse) =>
                        logger.info(subnetSuccessResponse.description)
                        complete((StatusCodes.OK, subnetSuccessResponse))
                      case Failure(t: Throwable) =>
                        val failureMessage: String = s"Subnet creation failed!\n${t.getLocalizedMessage}"
                        logger.error(failureMessage, t)
                        complete((StatusCodes.InternalServerError, failureMessage))
                    }
                  }
                )
              }
            )
          }
        )
      } ~
      pathPrefix("ec2") {
        concat(
          path("createInstance") {
            concat(
              parameters(("imageId" ? "ami-032598fcc7e9d1c7a", "instanceType" ? "t2.micro", "minCount" ? 1, "maxCount" ? 1, "associatePublicIpAddress" ? false, "subnetId" ? "subnet-07ed435c416b9e9bf", "groups" ? "sg-0c11c7bc3be82e337", "nameTag" ? "awsToolkitExInst")) { (imageId, instanceType, minCount, maxCount, associatePublicIpAddress, subnetId, groups, nameTag) =>
                cache(lfuRouteCache, keyerFunction)(
                  get {
                    val eC2ResponseFuture: Future[EC2SuccessResponse] = createEC2Instance(InstanceToCreate(imageId, InstanceType.fromValue(instanceType), minCount, maxCount, associatePublicIpAddress, subnetId, groups, nameTag))
                    onComplete(eC2ResponseFuture) {
                      case Success(ec2SuccessResponse: EC2SuccessResponse) =>
                        logger.info(ec2SuccessResponse.description)
                        complete((StatusCodes.OK, ec2SuccessResponse))
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
                    val actionPerformedFuture: Future[EC2SuccessResponse] = startEC2Instance(instanceId)
                    onComplete(actionPerformedFuture) {
                      case Success(actionPerformed: EC2SuccessResponse) =>
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
                    val actionPerformedFuture: Future[EC2SuccessResponse] = stopEC2Instance(instanceId)
                    onComplete(actionPerformedFuture) {
                      case Success(actionPerformed: EC2SuccessResponse) =>
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
                    val actionPerformedFuture: Future[EC2SuccessResponse] = rebootEC2Instance(instanceId)
                    onComplete(actionPerformedFuture) {
                      case Success(actionPerformed: EC2SuccessResponse) =>
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
          <li>Create VPC: <a href="/vpc/createVpc?cidr=10.0.0.0%2F28&amp;nameTag=awsToolkitExVPC">/vpc/createVpc?cidr=10.0.0.0%2F28&amp;nameTag=awsToolkitExVPC</a></li>
        </ul>
        <br/>
        <ul>
          <li>Create Subnet: <a href="/subnet/createSubnet?cidr=10.0.0.0%2F28&amp;vpcId=vpc-044ece86f611fba17&amp;nameTag=awsToolkitExSubnet">/subnet/createSubnet?cidr=10.0.0.0%2F28&amp;vpcId=vpc-044ece86f611fba17&amp;nameTag=awsToolkitExSubnet</a></li>
        </ul>
        <br/>
        <ul>
          <li>Create EC2 instance: <a href="/ec2/createInstance?imageId=ami-032598fcc7e9d1c7a&amp;instanceType=t2.micro&amp;minCount=1&amp;maxCount=1&amp;associatePublicIpAddress=false&amp;subnetId=subnet-07ed435c416b9e9bf&amp;groups=sg-0c11c7bc3be82e337&amp;nameTag=awsToolkitExInst">/ec2/createInstance?imageId=ami-032598fcc7e9d1c7a&amp;instanceType=t2.micro&amp;minCount=1&amp;maxCount=1&amp;associatePublicIpAddress=true&amp;subnetId=subnet-07ed435c416b9e9bf&amp;groups=sg-0c11c7bc3be82e337&amp;nameTag=awsToolkitExInst</a></li>
          <li>Start EC2 instance: <a href="/ec2/startInstance?instanceId=i-1234567890abcdef0">/ec2/startInstance?instanceId=i-1234567890abcdef0</a></li>
          <li>Stop EC2 instance: <a href="/ec2/stopInstance?instanceId=i-1234567890abcdef0">/ec2/stopInstance?instanceId=i-1234567890abcdef0</a></li>
          <li>Reboot EC2 instance: <a href="/ec2/rebootInstance?instanceId=i-1234567890abcdef0">/ec2/rebootInstance?instanceId=i-1234567890abcdef0</a></li>
        </ul>
      </body>
    </html>
}
