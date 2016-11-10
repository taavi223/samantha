name := """server"""

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayJava)

scalaVersion := "2.11.6"

resolvers += Resolver.mavenLocal

libraryDependencies ++= Seq(
  javaJdbc,
  cache,
  javaWs,
  "org.scala-lang" % "scala-compiler" % scalaVersion.value,
  "junit" % "junit" % "4.11",
  "org.elasticsearch" % "elasticsearch" % "2.2.0",
  "it.unimi.dsi" % "fastutil" % "7.0.11",
  "com.google.guava" % "guava" % "19.0",
  "org.apache.pdfbox" % "pdfbox" % "2.0.2",
  "org.apache.pdfbox" % "pdfbox-examples" % "2.0.2",
  "org.apache.commons" % "commons-math3" % "3.6.1",
  "com.fasterxml.jackson.dataformat" % "jackson-dataformat-csv" % "2.7.0",
  "biz.paluch.redis" % "lettuce" % "4.2.2.Final",
  "ml.dmlc" % "xgboost4j" % "0.7"
)

// Play provides two styles of routers, one expects its actions to be injected, the
// other, legacy style, accesses its actions statically.
routesGenerator := InjectedRoutesGenerator