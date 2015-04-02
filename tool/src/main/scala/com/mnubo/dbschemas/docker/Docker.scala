package com.mnubo
package dbschemas.docker

import java.net.ServerSocket

import com.mnubo.app_util.Logging

import scala.annotation.tailrec
import scala.sys.process._

object Docker extends Logging {
  private val HostParseRegex = """\d+\.[0-9\.]+""".r
  private val hostVar = System.getenv("DOCKER_HOST")

  val dockerHost =
    if (hostVar != null)
      HostParseRegex.findFirstIn(hostVar).get
    else
      "localhost"

  private def getAvailablePort =
    using(new ServerSocket(0))(_.getLocalPort)

  def run(dockerImage: String, exposedPort: Int, isStarted: String => Boolean): ContainerInfo = {
    val hostPort = getAvailablePort

    exec(s"docker pull $dockerImage")

    val container = execAndRead(s"docker run -dt -p $hostPort:$exposedPort $dockerImage")

    @tailrec
    def waitStarted: Unit =
      if (!isStarted(execAndRead(s"docker logs $container"))) {
        Thread.sleep(100)
        waitStarted
      }

    waitStarted

    ContainerInfo(container, hostPort)
  }

  def stop(container: String) =
    exec(s"docker stop $container")

  def commit(container: String, repository: String, tag: String): String = {
    val imageId =
      execAndRead(s"docker commit $container $repository:$tag")

    exec(s"docker tag -f $imageId $repository:latest")

    imageId
  }

  def push(repository: String) =
    exec(s"docker push $repository")

  def remove(container: String) =
    exec(s"docker rm $container")

  def removeImage(image: String) =
    exec(s"docker rmi $image")

  def exec(cmd: String) =
    if (0 !=  cmd.!)
      throw new Exception(s"Cannot execute [$cmd].")

  def execAndRead(cmd: String) =
    cmd.!!
}

case class ContainerInfo(id: String, realPort: Int)
