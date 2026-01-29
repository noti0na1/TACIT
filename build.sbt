val scala3Version = "3.8.1"

lazy val root = project
  .in(file("."))
  .settings(
    name := "SafeExecMCP",
    version := "0.1.0-SNAPSHOT",

    scalaVersion := scala3Version,

    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      // "-source:future",
      "-Yexplicit-nulls",
      "-Wunused:all",
      "-Wsafe-init",
      // "-Wall",
    ),

    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core" % "0.14.14",
      "io.circe" %% "circe-generic" % "0.14.14",
      "io.circe" %% "circe-parser" % "0.14.14",
      "org.scala-lang" %% "scala3-compiler" % scala3Version,
      "org.scala-lang" %% "scala3-repl" % scala3Version,
      "org.scalameta" %% "munit" % "1.2.2" % Test,
    ),

    // Enable forking for the REPL execution
    fork := true,
    // Connect stdin to the forked process (needed for MCP stdio communication)
    run / connectInput := true,
    
    // Assembly settings for creating a fat JAR
    assembly / mainClass := Some("SafeExecMCP"),
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", xs @ _*) => MergeStrategy.discard
      case "module-info.class" => MergeStrategy.discard
      case x if x.endsWith(".tasty") => MergeStrategy.first
      case x =>
        val oldStrategy = (assembly / assemblyMergeStrategy).value
        oldStrategy(x)
    }
  )
