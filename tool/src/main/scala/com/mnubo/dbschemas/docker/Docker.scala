package com.mnubo
package dbschemas.docker

import java.io.{BufferedInputStream, BufferedReader, InputStream, InputStreamReader}
import java.net.ServerSocket

import com.github.dockerjava.api.model.{ExposedPort, PortBinding, Ports}
import com.github.dockerjava.core.{DockerClientBuilder, DockerClientConfig}
import com.mnubo.app_util.Logging
import scala.sys.process._

import scala.annotation.tailrec

object Docker extends Logging {
  private val HostParseRegex = """\d+\.[0-9\.]+""".r
  private val hostVar = System.getenv("DOCKER_HOST")

  private val config =
    if (hostVar != null)
      DockerClientConfig
        .createDefaultConfigBuilder()
        .build()
    else
      DockerClientConfig
        .createDefaultConfigBuilder()
        .withUri("unix:///var/run/docker.sock")
        .build()

  private val dockerClient = DockerClientBuilder
    .getInstance(config)
    .withServiceLoaderClassLoader(getClass.getClassLoader)
    .build()

  val dockerHost =
    if (hostVar != null)
      HostParseRegex.findFirstIn(hostVar).get
    else
      "localhost"

  private def getAvailablePort =
    using(new ServerSocket(0))(_.getLocalPort)

  def run(dockerImage: String, exposedPort: Int, isStarted: String => Boolean): ContainerInfo = {
    val hostPort = getAvailablePort

    if (0 != Seq("docker", "pull", dockerImage).!)
      throw new Exception(s"Cannot pull $dockerImage.")

    val container =
      Seq("docker", "run", "-dt", "-p", s"$hostPort:$exposedPort", dockerImage).!!.trim

    @tailrec
    def waitStarted: Unit = {
      val stream = dockerClient
        .logContainerCmd(container)
        .withStdErr()
        .withStdOut()
        .exec()

      val log = asString(stream)

      if (!isStarted(log)) {
        Thread.sleep(100)
        waitStarted
      }
    }

    waitStarted

    ContainerInfo(container, hostPort)
  }

  def stop(container: String): Unit =
    dockerClient
      .stopContainerCmd(container)
      .exec()

  def commit(container: String, repository: String, tag: String): String = {
    val imageId = dockerClient
      .commitCmd(container)
      .withRepository(repository)
      .withTag(tag)
      .exec()

    dockerClient
      .tagImageCmd(imageId, repository, "latest")
      .withForce(true)
      .exec()

    imageId
  }

  def push(repository: String): Unit =
    dockerClient
      .pushImageCmd(repository)
      .exec()

  def remove(container: String): Unit =
    if (0 != Seq("docker", "rm", container).!)
      throw new Exception(s"Cannot remove container $container.")

  def removeImage(image: String): Unit =
    if (0 != Seq("docker", "rmi", image).!)
      throw new Exception(s"Cannot remove image $image.")

  private def asString(stream: InputStream) =
    using(stream) { _ =>
      using(new BufferedReader(new InputStreamReader(new BufferedInputStream(stream)))) { br =>
        val buf = new StringBuilder
        var line = br.readLine()
        while (line != null) {
          buf.append(line).append("\n")
          line = br.readLine()
        }
        buf.toString
      }
    }

}

case class ContainerInfo(id: String, realPort: Int)
