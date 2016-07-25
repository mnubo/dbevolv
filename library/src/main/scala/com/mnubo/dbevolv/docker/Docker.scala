package com.mnubo
package dbevolv.docker

import java.io.File

import com.mnubo.dbevolv.util.Logging

import scala.io.Source
import scala.sys.process._
import scala.util.Try

object Docker extends Logging {
  private val HostParseRegex = """\d+\.[0-9\.]+""".r
  private val hostVar = System.getenv("DOCKER_HOST")

  val dockerHost =
    if (hostVar != null)
      HostParseRegex.findFirstIn(hostVar).get
    else
      "localhost"

  lazy val isInContainer =
    Try(new File("/.dockerinit").exists() || new File("/.dockerenv").exists() || fileContent("/proc/1/cgroup").contains("/docker/"))
      .toOption
      .getOrElse(false)

  val userHome = System.getProperty("user.home")
  val dockerExecutableLocation = "which docker".!!.trim

  log.info(s"Docker detected in $dockerExecutableLocation on $dockerHost. User home is $userHome.")

  private def fileContent(path: String) =
    Source.fromFile(path).getLines().mkString("\n")
}
