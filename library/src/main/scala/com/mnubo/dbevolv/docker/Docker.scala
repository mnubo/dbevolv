package com.mnubo
package dbevolv.docker

import java.io.File

import com.fasterxml.jackson.databind.ObjectMapper
import com.mnubo.dbevolv.util.Logging
import com.spotify.docker.client.DefaultDockerClient
import com.spotify.docker.client.messages.AuthConfig

import scala.collection.JavaConverters._
import scala.io.Source
import scala.util.Try

class Docker(targetRegistry: Option[String]) extends Logging with AutoCloseable {
  private val HostParseRegex = """\d+\.[0-9\.]+""".r
  private val RepositoryRegex = """(([^/]+)/)?[^:]+(.+)?""".r
  private val hostVar = System.getenv("DOCKER_HOST")
  private val jsonParser = new ObjectMapper()

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

  log.info(s"Docker detected on $dockerHost. User home is $userHome.")

  private def fileContent(path: String) =
    Source.fromFile(path).getLines().mkString("\n")

  private val auths: Map[String, AuthConfig] =
    if (existsAsFile(s"$userHome/.docker/config.json")) {
      val auths = jsonParser
        .readTree(new File(s"$userHome/.docker/config.json"))
        .get("auths")

      if (auths != null)
        auths
          .fieldNames()
          .asScala
          .map(serverAddress => serverAddress -> AuthConfig.fromDockerConfig(serverAddress).build())
          .toMap
      else
        Map.empty[String, AuthConfig]
    }
    else
      Map.empty[String, AuthConfig]

  def authFor(repositoryName: String) =
    (for {
      regexMatch <- RepositoryRegex.findFirstMatchIn(repositoryName)
      serverName <- Option(regexMatch.group(2))
      auth <- auths.get(serverName)
    } yield auth).orNull

  val client = {
    targetRegistry
      .map { serverUrl =>
        require(auths.contains(serverUrl), s"Oups, cannot find Docker authorization for $serverUrl. Authentication available for: ${auths.keys.mkString(", ")}")
        DefaultDockerClient.fromEnv.authConfig(auths(serverUrl)).build
      }
      .getOrElse(DefaultDockerClient.fromEnv.build)
  }

  def images =
    client
      .listImages()
      .asScala
      .flatMap(_.repoTags().asScala)

  def removeImage(id: String) =
    client.removeImage(id, true, false)

  def push(tag: String) =
    client.push(tag)

  private def existsAsFile(path: String) = {
    val f = new File(path)
    f.exists && f.isFile
  }

  override def close() =
    client.close()
}
