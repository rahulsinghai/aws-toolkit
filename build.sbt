lazy val akkaHttpCorsV               = "1.0.0"
lazy val akkaHttpV                   = "10.1.12"
lazy val akkaV                       = "2.6.6"
lazy val awsJavaSdkV                 = "1.11.799"
lazy val jacksonV                    = "2.11.0"  // Make sure to explicitly add jackson-module-scala dependency with corresponding X.Y.Z version
lazy val log4jOverSlf4jV             = "1.7.26"
lazy val logbackV                    = "1.2.3"
lazy val logstashLogbackEncoderV     = "6.4"
lazy val scalatestV                  = "3.1.0"
lazy val scalaLoggingV               = "3.9.2"
lazy val scalaXmlV                   = "2.0.0-M1"

enablePlugins(BuildInfoPlugin)

enablePlugins(GitBranchPrompt)

lazy val root = (project in file(".")).
  settings(
    inThisBuild(List(
      organization     := "com.rahulsinghai",
      scalaVersion     := "2.13.2"
    )),
    name := "aws-toolkit",
    version := "1.0.0-SNAPSHOT",
    Compile / scalacOptions ++= Seq(
      "-deprecation",
      "-encoding", "UTF-8",
      "-feature",
      "-nobootcp",
      "-explaintypes",
      "-unchecked",
      "-language:higherKinds",
      "-language:implicitConversions",
      "-Xfatal-warnings",
      "-Xlint:-stars-align,_",
      "-Ywarn-dead-code"
    ),
    Compile / console / scalacOptions --= Seq("-Ywarn-dead-code"),
    Test / scalacOptions --= Seq("-Ywarn-dead-code"),
    Test / fork := true, // If you only want to run tests sequentially, but in a different JVM, you can achieve this
    test in assembly := {},
    testForkedParallel in Test := true,  // If our tests are properly designed, and can run independently, we can execute all of them in parallel by adding an SBT definition file
    git.useGitDescribe := true,
    mainClass in assembly := Some("com.singhaiukltd.AWSToolkitApp"),
    assemblyJarName in assembly := "aws-toolkit.jar",
    assemblyMergeStrategy in assembly := {
      case PathList(ps @ _*) if ps.last endsWith "BaseDateTime.class" => MergeStrategy.first
      case "application.conf"                                         => MergeStrategy.concat
      case x =>
        val oldStrategy = (assemblyMergeStrategy in assembly).value
        oldStrategy(x)
    },
    libraryDependencies ++= Seq(
      "com.typesafe.scala-logging"    %% "scala-logging"                % scalaLoggingV,
      "com.typesafe.akka"             %% "akka-slf4j"                   % akkaV,                    // SLF4J API facade for all kind of logging
      "ch.qos.logback"                %  "logback-classic"              % logbackV,                 // Logback implementation for SLF4J API
      "org.slf4j"                     %  "log4j-over-slf4j"             % log4jOverSlf4jV excludeAll ExclusionRule(organization = "org.slf4j"),          // Replaces all log4j classes with log4j-over-slf4j classes, that forward all logs to SLF4J. https://www.slf4j.org/legacy.html#log4j-over-slf4j
      "net.logstash.logback"          %  "logstash-logback-encoder"     % logstashLogbackEncoderV,  // Logback implementation for SLF4J API with logstash JSON encoding
      "com.fasterxml.jackson.core"    %  "jackson-databind"             % jacksonV,                 // Required for JSON appender
      "com.fasterxml.jackson.module"  %% "jackson-module-scala"         % jacksonV,                 // Mandatory to specify version compatible with jackson-databind

      "com.typesafe.akka"             %% "akka-http"                    % akkaHttpV,
      "com.typesafe.akka"             %% "akka-http-caching"            % akkaHttpV,
      "ch.megard"                     %% "akka-http-cors"               % akkaHttpCorsV,
      "com.typesafe.akka"             %% "akka-http-spray-json"         % akkaHttpV,
      "com.typesafe.akka"             %% "akka-actor-typed"             % akkaV,
      "com.typesafe.akka"             %% "akka-stream"                  % akkaV,
      "org.scala-lang.modules"        %% "scala-xml"                    % scalaXmlV,

      "com.amazonaws"                 % "aws-java-sdk-bom"              % awsJavaSdkV pomOnly(),
      "com.amazonaws"                 % "aws-java-sdk-imagebuilder"     % awsJavaSdkV,
      "com.amazonaws"                 % "aws-java-sdk-ec2"              % awsJavaSdkV,
      "com.amazonaws"                 % "aws-java-sdk-networkmanager"   % awsJavaSdkV,

      "com.typesafe.akka"             %% "akka-actor-testkit-typed"     % akkaV       % Test,
      "com.typesafe.akka"             %% "akka-http-testkit"            % akkaHttpV   % Test,
      "org.scalatest"                 %% "scalatest"                    % scalatestV  % Test
    ),
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoPackage := "singhai.uk.ltd.akkahttpwebservice.build"
  )

packageOptions in (Compile, packageBin) +=  {
  import java.util.jar.Manifest
  import java.util.jar.Attributes.Name
  val manifest = new Manifest
  val mainAttributes = manifest.getMainAttributes
  mainAttributes.put(new Name("Git-Version"), git.gitDescribedVersion.value.getOrElse("Unknown-git-version"))
  mainAttributes.put(new Name("Git-Uncommitted-Changes"), git.gitUncommittedChanges.value.toString)
  Package.JarManifest( manifest )
}

buildInfoKeys ++= Seq[BuildInfoKey](
  "applicationOwner" -> organization.value,
  BuildInfoKey.action("buildTime") { System.currentTimeMillis },
  BuildInfoKey.action("gitVersion") { git.gitDescribedVersion.value.getOrElse("Unknown-git-version") },
  BuildInfoKey.action("releasedVersion") { git.gitUncommittedChanges.value.toString }
)

buildInfoOptions += BuildInfoOption.ToJson
