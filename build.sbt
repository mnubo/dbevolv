lazy val root = (project in file("."))
  .enablePlugins(MnuboLibraryPlugin)
  .settings(publish := { })
  .aggregate(tool, plugin)

lazy val tool = (project in file("tool"))
  .enablePlugins(MnuboLibraryPlugin)
  .settings(
    name := "dbschemas",
    libraryDependencies ++= Seq(
      "com.mnubo"               %  "app-util"               % "[1.0.180,)" changing(),
      "com.mnubo"               %  "test-utils"             % "[1.0.248,)" changing() excludeAll (
        ExclusionRule("com.mnubo", "app-util"),
        ExclusionRule("org.slf4j", "slf4j-log4j12"),
        ExclusionRule("com.sun.jmx", "jmxri"),
        ExclusionRule("com.sun.jdmk", "jmxtools")
        ) changing(),
      "com.datastax.cassandra"  %  "cassandra-driver-core"  % "2.1.4",
      "org.elasticsearch"       %  "elasticsearch"          % "1.5.2" % "provided",
      "mysql"                   %  "mysql-connector-java"   % "5.1.35" % "provided",
      "joda-time"               %  "joda-time"              % "2.7",
      "io.spray"                %% "spray-json"             % "1.3.1",
      "com.github.scopt"        %% "scopt"                  % "3.3.0"
    )
  )

lazy val plugin = (project in file("plugin"))
  .enablePlugins(MnuboLibraryPlugin)
  .settings(scriptedSettings: _*)
  .settings(
    name := "dbschemas-sbt-plugin",
    sbtPlugin := true,
    libraryDependencies ++= Seq(
      "com.typesafe"            %  "config"                 % "1.2.1"
    ),
    addSbtPlugin("se.marcuslonnberg" % "sbt-docker" % "1.0.0"),
    addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.13.0"),
    addSbtPlugin("com.github.gseitz" % "sbt-release" % "0.8.5"),
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



