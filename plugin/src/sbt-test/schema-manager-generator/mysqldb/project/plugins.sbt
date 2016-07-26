resolvers ++= Seq(
  "Mnubo release repository" at "http://artifactory.mtl.mnubo.com:8081/artifactory/libs-release-local/",
  "Mnubo third parties repository" at "http://artifactory.mtl.mnubo.com:8081/artifactory/repo/"
)

addSbtPlugin("com.mnubo" % "dbevolv-sbt-plugin" % System.getProperty("plugin.version"))

libraryDependencies ++= Seq(
  "com.mnubo" %% "dbevolv" % System.getProperty("plugin.version"),
  "mysql" % "mysql-connector-java"   % "5.1.35",
  "org.apache.commons" % "commons-io" % "1.3.2"
)
