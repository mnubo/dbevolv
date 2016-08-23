addSbtPlugin("com.mnubo" % "dbevolv-sbt-plugin" % System.getProperty("plugin.version"))

libraryDependencies ++= Seq(
  "com.mnubo" %% "dbevolv" % System.getProperty("plugin.version"),
  "com.datastax.cassandra" % "cassandra-driver-core" % "3.0.0",
  "org.apache.commons" % "commons-io" % "1.3.2"
)
