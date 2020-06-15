package com.rahulsinghai.route

import java.security.SecureRandom

import akka.http.scaladsl.coding.Gzip
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.RouteResult.{ Complete, Rejected }
import akka.http.scaladsl.server._
import akka.pattern.AskTimeoutException
import akka.stream.scaladsl.Flow
import akka.util.ByteString
import ch.megard.akka.http.cors.scaladsl.CorsDirectives._
import ch.megard.akka.http.cors.scaladsl.settings.CorsSettings
import com.rahulsinghai.conf.AWSToolkitConfig
import com.typesafe.scalalogging.StrictLogging
import org.apache.commons.codec.binary.Hex

import scala.concurrent.ExecutionContext
import scala.util.{ Failure, Success, Try }

trait RouteWrapper extends StrictLogging {

  implicit val ec: ExecutionContext

  def generateSecretToken: String = {
    val secRandom: SecureRandom = new SecureRandom()

    val result = new Array[Byte](32)
    secRandom.nextBytes(result)
    Hex.encodeHexString(result)
  }

  val rand: String = generateSecretToken

  /**
   * This function is called whenever a request is received.
   * It extracts RequestContext and ClientIP.
   * It then calls onRequest method which returns a function of type `Try[RouteResult => Unit]` (which is populated in variable onDone).
   * Then it executes the route and maps its result.
   * For both cases of Complete and Rejected, first onDone method is called, which does all the logging and then the
   *   entity/rejected object is returned, which is further handled by ResponseHandler/RejectionHandler.
   *
   * @param onRequest is a function that accepts RequestContext and RemoteAddress and returns a function `Try[RouteResult => Unit]`.
   *                  It is called by this method when a request starts, yielding another function (called onDone in the code).
   *                  onDone now points to the function `Try[RouteResult => Unit]`.
   *                  When the request completes, onDone is called with the final result, allowing to log and bulletin metrics.
   */
  def aroundRequest[T](onRequest: (RequestContext, RemoteAddress) => Try[RouteResult] => Unit): Directive0 = {
    extractRequestContext.flatMap { ctx =>
      extractClientIP.flatMap { ip =>
        val onDone = onRequest(ctx, ip)

        // Execute the route and map its result
        mapRouteResult {
          case c @ Complete(response) =>
            Complete(response.mapEntity { entity =>
              if (entity.isKnownEmpty()) {
                // On an empty entity, `transformDataBytes` unsets `isKnownEmpty`.
                // Call onDone right away, since there's no significant amount of data to send, anyway.
                onDone(Success(c))
                entity
              } else {
                entity.transformDataBytes(Flow[ByteString].watchTermination() {
                  case (m, f) =>
                    f.map(_ => c).onComplete(onDone)
                    m
                })
              }
            })
          case r: Rejected =>
            onDone(Success(r))
            r
        }
      }
    }
  }

  /**
   * When this function is called, it executes the statements mentioned in its first 3 lines.
   * It then returns a function of type `Try[RouteResult => Unit]`
   *
   * @param ctx a RequestContext
   * @param ip a RemoteAddress
   * @return a function of type `Try[RouteResult => Unit]`
   */
  def idAndTimeRequest(ctx: RequestContext, ip: RemoteAddress): Try[RouteResult] => Unit = {
    val requestTimestamp = System.currentTimeMillis
    val id: String = requestTimestamp.toString + rand
    logger.info(s"id: $id, Request received: [${ctx.request.method.name} ${ctx.request.uri}], remote address: [${ip.toOption.getOrElse("")}]")

    // A function of type: Try[RouteResult] => Unit
    // This function does not play with RouteResult and simply prints log messages.
    // There are three possible outcomes of a request:
    //   1. the request completes successfully, Success(Complete(response)) is passed to onDone
    //   2. the request is rejected (e.g. because of a non-matching inner route), then Success(Rejected(rejections)) is passed
    //   3. producing the response body fails, and hence the request fails as well: Failure is passed to onDone
    {
      case Success(Complete(resp)) =>
        val elapsedTime: Long = System.currentTimeMillis - requestTimestamp
        logger.info(s"id: $id, Responding with: [${resp.status.intValue()}], Took: ${elapsedTime}ms.")
      case Success(Rejected(_)) =>
        logger.error(s"id: $id, Request rejected: [${ctx.request.method.name} ${ctx.request.uri}], remote address: [${ip.toOption.getOrElse("")}]")
      case Failure(_) =>
        logger.error(s"id: $id, Request failed: [${ctx.request.method.name} ${ctx.request.uri}], remote address: [${ip.toOption.getOrElse("")}]")
    }
  }

  implicit def routeExceptionHandler: ExceptionHandler = ExceptionHandler {
    case ex: AskTimeoutException =>
      complete {
        StatusCodes.ServiceUnavailable -> s"Internal actor is not responding within ${AWSToolkitConfig.akkaHttpServerRequestTimeout} seconds.\n${ex.getLocalizedMessage}"
      }
    case ex: Exception =>
      complete {
        StatusCodes.InternalServerError -> s"${ex.getLocalizedMessage}"
      }
  }

  implicit def myRejectionHandler: RejectionHandler =
    RejectionHandler.default
      .mapRejectionResponse {
        case res @ HttpResponse(_, _, ent: HttpEntity.Strict, _) =>
          // since all Akka default rejection responses are Strict this will handle all rejections
          val message = ent.data.decodeString(ent.contentType.charsetOption.getOrElse(HttpCharsets.`UTF-8`).value).replaceAll("\"", """\"""")

          // we copy the response in order to keep all headers and status code, wrapping the message as hand rolled JSON
          // you could the entity using your favourite marshalling library (e.g. spray json or anything else)
          logger.error(s"Responding with: [${res.status.intValue()}], Reason: $message]")
          res.copy(entity = HttpEntity(ContentTypes.`application/json`, s"""{"rejection": "$message"}"""))

        case x => x // pass through all other types of responses
      }

  def wrapRoutes(dsl: Route): Route = Route.seal(
    cors(CorsSettings.defaultSettings.withAllowedOrigins(AWSToolkitConfig.corsAllowedOrigins).withAllowedMethods(AWSToolkitConfig.corsAllowedMethods)) {
      aroundRequest(idAndTimeRequest)
      dsl
    }
  )

  def wrapRoutesWithGzip(dsl: Route): Route = Route.seal(
    cors(CorsSettings.defaultSettings.withAllowedOrigins(AWSToolkitConfig.corsAllowedOrigins).withAllowedMethods(AWSToolkitConfig.corsAllowedMethods)) {
      aroundRequest(idAndTimeRequest) {
        encodeResponseWith(Gzip)(dsl)
      }
    }
  )
}
