package com.mnubo
package dbevolv.docker

import com.mnubo.app_util.Logging

import scala.sys.process._

object Docker extends Logging {
  private val HostParseRegex = """\d+\.[0-9\.]+""".r
  private val hostVar = System.getenv("DOCKER_HOST")

  val dockerHost =
    if (hostVar != null)
      HostParseRegex.findFirstIn(hostVar).get
    else
      "localhost"

  val userHome = System.getProperty("user.home")
  val dockerExecutableLocation = "which docker".!!.trim

  log.info(s"Docker detected in $dockerExecutableLocation on $dockerHost. User home is $userHome.")
}
