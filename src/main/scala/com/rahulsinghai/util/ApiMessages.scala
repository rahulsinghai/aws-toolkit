package com.rahulsinghai.util

import java.util.concurrent.TimeUnit

import akka.actor.typed.ActorSystem
import spray.json._

import scala.concurrent.Future
import scala.io.Source

trait ApiMessages {

  implicit val system: ActorSystem[_]

  final val daysToMsecUnits: Seq[(TimeUnit, String)] = List((TimeUnit.DAYS, "d"), (TimeUnit.HOURS, "hr"), (TimeUnit.MINUTES, "min"), (TimeUnit.SECONDS, "sec"), (TimeUnit.MILLISECONDS, "ms"))
  final val hoursToMinUnits: Seq[(TimeUnit, String)] = List((TimeUnit.HOURS, "hr"), (TimeUnit.MINUTES, "min"))

  def humanReadableTime(timeInTimeUnit: Long, timeUnit: TimeUnit = TimeUnit.MILLISECONDS, expectedTimeUnits: Seq[(TimeUnit, String)] = daysToMsecUnits): String = {
    val init: (String, Long) = ("", timeInTimeUnit)
    val humanReadableTimeTuple: (String, Long) = expectedTimeUnits.foldLeft(init) {
      case (acc, next) =>
        val (human, rest) = acc
        val (unit, name) = next
        val res = unit.convert(rest, timeUnit)
        val str = res match {
          case i if i > 0 =>
            human + " " + res + " " + name
          case i if res == 0 && human == "" && unit == timeUnit =>
            human + " " + res + " " + name
          case _ =>
            human
        }
        val diff = rest - timeUnit.convert(res, unit)
        (str, diff)
    }
    val humanReadableTime = humanReadableTimeTuple._1.trim
    humanReadableTime
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
