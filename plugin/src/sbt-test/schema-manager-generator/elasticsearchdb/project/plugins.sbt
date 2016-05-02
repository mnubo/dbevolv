resolvers ++= Seq(
  "Mnubo release repository" at "http://artifactory.mtl.mnubo.com:8081/artifactory/libs-release-local/",
  "Mnubo third parties repository" at "http://artifactory.mtl.mnubo.com:8081/artifactory/repo/"
)

addSbtPlugin("com.mnubo" % "dbschemas-sbt-plugin" % System.getProperty("plugin.version"))

libraryDependencies ++= Seq(
  "com.mnubo"               %  "docker-utils"             % "[1.0.332,)" changing() excludeAll (
    ExclusionRule("org.joda", "joda-convert"),
    ExclusionRule("org.slf4j", "slf4j-log4j12")
  )
)
