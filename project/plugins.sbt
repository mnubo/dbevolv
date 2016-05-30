addSbtPlugin("org.xerial.sbt"     % "sbt-sonatype"  % "1.1")
addSbtPlugin("com.jsuereth"       % "sbt-pgp"       % "1.0.0")
addSbtPlugin("com.timushev.sbt"   % "sbt-updates"   % "0.1.10")
addSbtPlugin("se.marcuslonnberg"  % "sbt-docker"    % "1.3.0")
addSbtPlugin("com.eed3si9n"       % "sbt-assembly"  % "0.14.3")

scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature")

libraryDependencies <+= (sbtVersion) { sv =>
  "org.scala-sbt" % "scripted-plugin" % sv
}
