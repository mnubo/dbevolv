resolvers += "Mnubo release repository" at "http://artifactory.mtl.mnubo.com:8081/artifactory/libs-release-local/"

addSbtPlugin("com.mnubo" % "mnubo-sbt-plugin" % "[1.0.0,)" changing())

libraryDependencies <+= (sbtVersion) { sv =>
  "org.scala-sbt" % "scripted-plugin" % sv
}
