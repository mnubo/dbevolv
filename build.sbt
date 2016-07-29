import scala.xml.Group

val fixedJerseyVersion = "2.22.2"

val commonSettings = Seq(
  version := "1.0.7",

  organization := "com.mnubo",

  scalaVersion := "2.10.6",

  scalacOptions ++= Seq("-unchecked", "-deprecation", "-optimize", "-feature"),

  javacOptions ++= Seq("-target", "1.6", "-source", "1.6"),

  homepage := Some(new URL("https://github.com/mnubo/dbevolv")),

  startYear := Some(2016),

  licenses := Seq(("Apache-2.0", new URL("http://www.apache.org/licenses/LICENSE-2.0"))),

  pomExtra <<= (pomExtra, name, description) {(pom, name, desc) => pom ++ Group(
    <scm>
      <url>http://github.com/mnubo/dbevolv</url>
      <connection>scm:git:git://github.com/mnubo/dbevolv.git</connection>
    </scm>
      <developers>
        <developer>
          <id>jletroui</id>
          <name>Julien Letrouit</name>
          <url>http://julienletrouit.com/</url>
        </developer>
        <developer>
          <id>lemieud</id>
          <name>David Lemieux</name>
          <url>https://github.com/lemieud</url>
        </developer>
      </developers>
  )},

  sonatypeProfileName := "mnuboci",

  resolvers ++= Seq(Opts.resolver.sonatypeSnapshots, Opts.resolver.sonatypeReleases),

  pomIncludeRepository := { _ => false },

  publishMavenStyle := true,

  publishTo := {
    val nexus = "https://oss.sonatype.org/"
    if (isSnapshot.value)
      Some("snapshots" at nexus + "content/repositories/snapshots")
    else
      Some("releases"  at nexus + "service/local/staging/deploy/maven2")
  },

  pomIncludeRepository := { _ => false }
)

lazy val root = (project in file("."))
  .settings(
    publishArtifact := false,
    publish := {},
    publishLocal := {}
  )
  .aggregate(library, plugin)

lazy val library = (project in file("library"))
  .settings(commonSettings: _*)
  .settings(
    name := "dbevolv",

    libraryDependencies ++= Seq(
      "com.datastax.cassandra"  %  "cassandra-driver-core"  % "3.0.0" % "provided",
      "org.elasticsearch"       %  "elasticsearch"          % "1.5.2" % "provided",
      "mysql"                   %  "mysql-connector-java"   % "5.1.35" % "provided",
      "com.typesafe"            %  "config"                 % "1.2.1",
      "com.spotify"             %  "docker-client"          % "5.0.2",
      "org.glassfish.jersey.core" % "jersey-client" % fixedJerseyVersion, // Fixes https://github.com/spotify/docker-client/issues/405
      "org.glassfish.jersey.media" % "jersey-media-json-jackson" % fixedJerseyVersion, // Fixes https://github.com/spotify/docker-client/issues/405
      "org.glassfish.jersey.connectors" % "jersey-apache-connector" % fixedJerseyVersion, // Fixes https://github.com/spotify/docker-client/issues/405
      "joda-time"               %  "joda-time"              % "2.7",
      "io.spray"                %% "spray-json"             % "1.3.1",
      "com.github.scopt"        %% "scopt"                  % "3.3.0",
      "ch.qos.logback"          %  "logback-classic"        % "1.1.3",

      "org.scalatest"           %% "scalatest"              % "2.2.6" % "test"
    ),

    assemblyMergeStrategy in assembly := {
      case "META-INF/io.netty.versions.properties" => MergeStrategy.first
      case x => (assemblyMergeStrategy in assembly).value(x)
    }
  )


lazy val plugin = (project in file("plugin"))
  .settings(commonSettings: _*)
  .settings(scriptedSettings: _*)
  .settings(
    name := "dbevolv-sbt-plugin",

    sbtPlugin := true,

    libraryDependencies ++= Seq(
      "com.typesafe" % "config" % "1.2.1"
    ),

    addSbtPlugin("se.marcuslonnberg" % "sbt-docker" % "1.3.0"),
    addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.14.3"),
    addSbtPlugin("com.github.gseitz" % "sbt-release" % "1.0.3"),

    // Put the version of the plugin in the resources, so that the plugin has access to it from the code
    resourceGenerators in Compile <+= (resourceManaged in Compile, version) map { (dir, v) =>
      val file = dir / "version.txt"
      IO.write(file, v)
      Seq(file)
    },

    scriptedLaunchOpts := { scriptedLaunchOpts.value ++
      Seq("-Xmx1024M", "-XX:MaxPermSize=256M", "-Dplugin.version=" + version.value)
    },

    scriptedBufferLog := false
  )

  val MnuboNexus = "http://artifactory.mtl.mnubo.com:8081/artifactory"
  val MnuboThirdParties = "Mnubo third parties" at s"$MnuboNexus/repo"



