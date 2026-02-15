// val scala3Version = "3.8.1"
val scala3Version = "3.8.2-RC3"

lazy val lib = project
  .in(file("library"))
  .settings(
    scalaVersion := scala3Version,
    Compile / unmanagedSourceDirectories := Seq(
      baseDirectory.value,
      baseDirectory.value / "impl"
    ),
    Compile / unmanagedSources / excludeFilter :=
      "*.test.scala" || "project.scala" || "README.md",
    libraryDependencies += "com.openai" % "openai-java" % "4.21.0",
    scalacOptions ++= Seq(
      "-language:experimental.captureChecking",
      "-language:experimental.modularity",
      "-deprecation", "-feature", "-unchecked",
      "-Yexplicit-nulls", "-Wsafe-init"
    )
  )

lazy val root = project
  .in(file("."))
  .dependsOn(lib)
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
      "-language:experimental.modularity",
      // "-Wall",
    ),

    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core" % "0.14.14",
      "io.circe" %% "circe-generic" % "0.14.14",
      "io.circe" %% "circe-parser" % "0.14.14",
      "com.github.scopt" %% "scopt" % "4.1.1-M3",
      "org.scala-lang" %% "scala3-compiler" % scala3Version,
      "org.scala-lang" %% "scala3-repl" % scala3Version,
      "org.scalameta" %% "munit" % "1.2.2" % Test,
    ),

    // Bundle Interface.scala source as a classpath resource so show_interface can serve it
    Compile / resourceGenerators += Def.task {
      val src = (lib / baseDirectory).value / "Interface.scala"
      val dst = (Compile / resourceManaged).value / "Interface.scala"
      IO.copyFile(src, dst)
      Seq(dst)
    }.taskValue,

    // Enable forking for the REPL execution
    fork := true,
    // Connect stdin to the forked process (needed for MCP stdio communication)
    run / connectInput := true,
    
    // Assembly settings for creating a fat JAR
    assembly / mainClass := Some("SafeExecMCP"),
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "services", _*) => MergeStrategy.concat
      case PathList("META-INF", "MANIFEST.MF")  => MergeStrategy.discard
      case PathList("META-INF", x) if x.endsWith(".SF")
        || x.endsWith(".DSA") || x.endsWith(".RSA") => MergeStrategy.discard
      case PathList("META-INF", _*)              => MergeStrategy.first
      case "module-info.class"                   => MergeStrategy.discard
      case x if x.endsWith(".tasty") => MergeStrategy.first
      case x =>
        val oldStrategy = (assembly / assemblyMergeStrategy).value
        oldStrategy(x)
    }
  )
