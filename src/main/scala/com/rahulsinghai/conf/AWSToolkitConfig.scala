package com.rahulsinghai.conf

import java.util.concurrent.TimeUnit

import akka.http.scaladsl.model.HttpMethod
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model.headers.HttpOrigin
import akka.util.Timeout
import ch.megard.akka.http.cors.scaladsl.model.HttpOriginMatcher
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.regions.Regions
import com.typesafe.config.{ Config, ConfigFactory }
import com.typesafe.scalalogging.StrictLogging

import scala.concurrent.duration.{ Duration, FiniteDuration }
import scala.jdk.CollectionConverters._
import scala.util.Try

object AWSToolkitConfig extends StrictLogging {

  def getPotentiallyInfiniteDuration(durationString: String): FiniteDuration = {
    val durationObject = durationString match {
      case "infinite" => Duration(Integer.MAX_VALUE, TimeUnit.DAYS)
      case x          => Duration(x)
    }
    FiniteDuration(durationObject.toMillis, TimeUnit.MILLISECONDS)
  }

  private lazy val httpMethodMap: Map[String, HttpMethod] = Map("CONNECT" -> CONNECT, "DELETE" -> DELETE, "GET" -> GET, "HEAD" -> HEAD,
    "OPTIONS" -> OPTIONS, "PATCH" -> PATCH, "POST" -> POST, "PUT" -> PUT, "TRACE" -> TRACE)

  lazy val config: Config = ConfigFactory.load

  private lazy val awsAccessKey = Try(config.getString("aws.accessKey")).getOrElse("")
  private lazy val awsSecretKey = Try(config.getString("aws.secretKey")).getOrElse("")
  lazy val awsCredentials = new BasicAWSCredentials(awsAccessKey, awsSecretKey)

  private lazy val regionName = Try(config.getString("aws.region")).getOrElse("eu-west-2")
  lazy val region: Regions = Regions.fromName(regionName)

  private val akkaHttpServerConfig: Config = config.getConfig("akka.http.server")

  lazy val akkaHttpServerHttpInterface: String = Try(akkaHttpServerConfig.getString("interface")).getOrElse("0.0.0.0")
  lazy val akkaHttpServerHttpPort: Int = Try(akkaHttpServerConfig.getInt("port")).getOrElse(8098)
  lazy val akkaHttpServerPassword: String = Try(akkaHttpServerConfig.getString("password")).getOrElse("")
  lazy val akkaHttpServerRequestTimeout: Timeout = Timeout(Try(getPotentiallyInfiniteDuration(akkaHttpServerConfig.getString("request-timeout"))).getOrElse(FiniteDuration(30, TimeUnit.SECONDS)))

  lazy val corsAllowedOrigins: HttpOriginMatcher = Try(HttpOriginMatcher(HttpOrigin(config.getString("cors.allowed-origins")))).getOrElse(HttpOriginMatcher.*)
  lazy val corsAllowedMethods: List[HttpMethod] = Try(config.getStringList("cors.allowed-methods").asScala.toList
    .map(y => httpMethodMap.getOrElse(y.toUpperCase, GET))).getOrElse(List(GET))
}
