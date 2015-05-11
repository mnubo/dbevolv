resolvers ++= Seq(
  "Mnubo release repository" at "http://artifactory.mtl.mnubo.com:8081/artifactory/libs-release-local/",
  "Mnubo third parties repository" at "http://artifactory.mtl.mnubo.com:8081/artifactory/repo/"
)

addSbtPlugin("com.mnubo" % "dbschemas-sbt-plugin" % System.getProperty("plugin.version"))

libraryDependencies ++= Seq(
  "com.mnubo"   %  "test-utils"             % "[1.0.61,)",
  "commons-io"   %  "commons-io"              % "2.4",
  "mysql"       %  "mysql-connector-java"   % "5.1.35"
)
