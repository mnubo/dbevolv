package com.mnubo
package dbschemas.docker

import java.net.ServerSocket

import com.mnubo.app_util.Logging
import com.mnubo.dbschemas.DatabaseDockerImage

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

  def run(dockerImage: DatabaseDockerImage): ContainerInfo = {
    import dockerImage._

    val hostPort = getAvailablePort

    val options = additionalOptions.map(_ + " ").getOrElse("")

    val container = execAndRead(s"docker run -dt -p $hostPort:$exposedPort $options$dockerImage")

    waitStarted(container, isStarted)

    ContainerInfo(container, hostPort)
  }

  def stop(container: String) =
    exec(s"docker stop $container")

  def start(container: String, isStarted: String => Boolean) = {
    val previousLog = execAndRead(s"docker logs $container")

    def isStartedOnNewLogs(logs: String) =
      isStarted(logs.replace(previousLog, ""))

    exec(s"docker start $container")

    waitStarted(container, isStartedOnNewLogs)
  }

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

  def exec(cmd: String) = {
    logInfo(cmd)
    if (0 != cmd.!)
      throw new Exception(s"Cannot execute [$cmd].")
  }

  def execAndRead(cmd: String) = {
    if (!cmd.startsWith("docker logs"))
      logInfo(cmd)
    cmd.!!
  }

  @tailrec
  private def waitStarted(container: String, isStarted: String => Boolean): Unit =
    if (!isStarted(execAndRead(s"docker logs $container"))) {
      Thread.sleep(100)
      waitStarted(container, isStarted)
    }

}

case class ContainerInfo(id: String, realPort: Int)
