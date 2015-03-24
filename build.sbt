lazy val root = (project in file("."))
  .enablePlugins(MnuboLibraryPlugin)
  .settings(publish := { })
  .aggregate(tool, plugin)

lazy val tool = (project in file("tool"))
  .enablePlugins(MnuboLibraryPlugin)
  .settings(
    name := "dbschemas",
    libraryDependencies ++= Seq(
      "com.datastax.cassandra"  %  "cassandra-driver-core"  % "2.1.4",
      "com.mnubo"               %  "app-util"               % "[1.0.0,)",
      "com.github.docker-java"  %  "docker-java"            % "1.0.0",
      "com.github.scopt"        %% "scopt"                  % "3.3.0"
    )
  )

lazy val migrator = (project in file("migrator"))
  .enablePlugins(MnuboLibraryPlugin)
  .settings(
    name := "migrator",
    publish := {}, // We don't want to publish this guy
    libraryDependencies ++= Seq(
      "com.mnubo"               %  "app-util"               % "[1.0.0,)",
      "com.google.code.gson"    %  "gson"                   % "2.3.1"
    )
  )

lazy val plugin = (project in file("plugin"))
  .enablePlugins(MnuboLibraryPlugin)
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
    }
  )
  .dependsOn(tool)



