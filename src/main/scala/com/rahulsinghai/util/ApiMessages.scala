package com.rahulsinghai.util

import java.util.concurrent.TimeUnit

import akka.actor.typed.ActorSystem
import spray.json._

import scala.concurrent.Future
import scala.io.Source

trait ApiMessages {

  implicit val system: ActorSystem[_]

  final val DaysToMsecUnits: Seq[(TimeUnit, String)] = List((TimeUnit.DAYS, "d"), (TimeUnit.HOURS, "hr"), (TimeUnit.MINUTES, "min"), (TimeUnit.SECONDS, "sec"), (TimeUnit.MILLISECONDS, "ms"))
  final val HoursToMinUnits: Seq[(TimeUnit, String)] = List((TimeUnit.HOURS, "hr"), (TimeUnit.MINUTES, "min"))

  def humanReadableTime(timeInTimeUnit: Long, timeUnit: TimeUnit = TimeUnit.MILLISECONDS, expectedTimeUnits: Seq[(TimeUnit, String)] = DaysToMsecUnits): String = {
    val init = ("", timeInTimeUnit)
    expectedTimeUnits.foldLeft(init) {
      case (acc, next) =>
        val (human, rest) = acc
        val (unit, name) = next
        val res = unit.convert(rest, timeUnit)
        val str = if (res > 0)
          human + " " + res + " " + name
        else if (res == 0 && human == "" && unit == timeUnit)
          human + " " + res + " " + name
        else
          human
        val diff = rest - timeUnit.convert(res, unit)
        (str, diff)
    }._1.trim
  }

  def getCurrentStatus: Future[String] = Future {
    // non-blocking long lasting computation
    JsonParser(s"""{"service": "${system.name}", "version": "${getClass.getPackage.getImplementationVersion}", "host": "${java.net.InetAddress.getLocalHost.getCanonicalHostName}", "uptime": "${humanReadableTime(system.uptime, TimeUnit.SECONDS)}"}""").prettyPrint
  }(system.executionContext)

  def getCurrentVersion: Future[String] = Future {
    val s = Source.fromURL(getClass.getClassLoader.getResource("git.properties"))
    try {
      JsonParser(s.getLines.mkString).prettyPrint
    } finally{
      s.close
    }
  }(system.executionContext)
}
