addSbtPlugin("com.mnubo" % "dbevolv-sbt-plugin" % System.getProperty("plugin.version"))

libraryDependencies ++= Seq(
  "com.mnubo" %% "dbevolv" % System.getProperty("plugin.version"),
  "org.elasticsearch" % "elasticsearch" % "2.3.5",
  "org.elasticsearch.plugin" %  "delete-by-query" % "2.3.5",
  "org.apache.commons" % "commons-io" % "1.3.2"
)
