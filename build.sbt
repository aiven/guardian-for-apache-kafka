import com.jsuereth.sbtpgp.PgpKeys.publishSigned
import com.lightbend.paradox.apidoc.ApidocPlugin.autoImport.apidocRootPackage

ThisBuild / scalaVersion         := "2.13.8"
ThisBuild / organization         := "aiven.io"
ThisBuild / organizationName     := "Aiven"
ThisBuild / organizationHomepage := Some(url("https://aiven.io/"))

ThisBuild / resolvers += "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"

val akkaVersion                = "2.6.19"
val akkaHttpVersion            = "10.2.9"
val alpakkaKafkaVersion        = "3.0.0"
val kafkaClientsVersion        = "3.1.0"
val alpakkaVersion             = "3.0.4"
val futilesVersion             = "2.0.2"
val quillJdbcMonixVersion      = "3.7.2"
val postgresqlJdbcVersion      = "42.3.4"
val scalaLoggingVersion        = "3.9.4"
val logbackClassicVersion      = "1.2.11"
val declineVersion             = "2.2.0"
val pureConfigVersion          = "0.17.1"
val scalaTestVersion           = "3.2.12"
val scalaTestScalaCheckVersion = "3.2.12.0-RC2"
val akkaStreamsJson            = "0.8.3"
val diffxVersion               = "0.7.1"
val testContainersVersion      = "0.40.7"
val testContainersJavaVersion  = "1.17.1"
val scalaCheckVersion          = "1.15.5-1-SNAPSHOT"
val scalaCheckOpsVersion       = "2.8.1"
val enumeratumVersion          = "1.7.0"
val organizeImportsVersion     = "0.6.0"

val flagsFor12 = Seq(
  "-Xlint:_",
  "-Ywarn-infer-any",
  "-Ywarn-adapted-args", // Warn if an argument list is modified to match the receiver
  "-Ywarn-inaccessible",
  "-Ywarn-infer-any",
  "-language:existentials",
  "-opt-inline-from:<sources>",
  "-opt:l:method"
)

val flagsFor13 = Seq(
  "-Xlint:_",
  "-Xfatal-warnings",
  "-Ywarn-unused",
  "-Xlint:adapted-args",
  "-Wconf:cat=unused:info",
  "-language:existentials",
  "-opt-inline-from:<sources>",
  "-opt:l:method"
)

val librarySettings = Seq(
  crossScalaVersions := List("2.12.15", "2.13.8"),
  scalacOptions ++= {
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, n)) if n == 13 =>
        flagsFor13
      case Some((2, n)) if n == 12 =>
        flagsFor12
    }
  }
)

val cliSettings = Seq(
  publishArtifact := false,
  scalacOptions ++= Seq(
    "-opt-inline-from:**", // See https://www.lightbend.com/blog/scala-inliner-optimizer
    "-opt:l:method"
  ) ++ flagsFor13,
  publish / skip       := true,
  publishLocal / skip  := true,
  publishSigned / skip := true,
  rpmVendor            := "Aiven",
  rpmLicense           := Some("ASL 2.0")
)

val baseName = "guardian-for-apache-kafka"

lazy val guardian = project
  .in(file("."))
  .enablePlugins(ScalaUnidocPlugin)
  .disablePlugins(SitePlugin)
  .aggregate(
    core,
    coreCli,
    coreS3,
    coreGCS,
    coreBackup,
    backupS3,
    backupGCS,
    cliBackup,
    coreCompaction,
    compactionS3,
    compactionGCS,
    cliCompaction,
    coreRestore,
    restoreS3,
    restoreGCS,
    cliRestore
  )
  .settings(
    publish / skip     := true,
    crossScalaVersions := List() // workaround for https://github.com/sbt/sbt/issues/3465
  )

lazy val core = project
  .in(file("core"))
  .settings(
    librarySettings,
    name := s"$baseName-core",
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-actor"        % akkaVersion % Provided,
      "com.typesafe.akka" %% "akka-stream"       % akkaVersion % Provided,
      "com.typesafe.akka" %% "akka-stream-kafka" % alpakkaKafkaVersion,
      // Ideally we shouldn't be explicitly providing a kafka-clients version and instead getting the version
      // transitively from akka-streams-kafka however there isn't a nice way to extract a transitive dependency
      // for usage in linking to documentation.
      "org.apache.kafka"            % "kafka-clients"                  % kafkaClientsVersion,
      "com.typesafe.scala-logging" %% "scala-logging"                  % scalaLoggingVersion,
      "com.github.pureconfig"      %% "pureconfig"                     % pureConfigVersion,
      "ch.qos.logback"              % "logback-classic"                % logbackClassicVersion,
      "org.mdedetrich"             %% "akka-stream-circe"              % akkaStreamsJson,
      "com.markatta"               %% "futiles"                        % futilesVersion,
      "com.typesafe.akka"          %% "akka-actor"                     % akkaVersion                % Test,
      "com.typesafe.akka"          %% "akka-stream"                    % akkaVersion                % Test,
      "org.scalatest"              %% "scalatest"                      % scalaTestVersion           % Test,
      "org.scalatestplus"          %% "scalacheck-1-15"                % scalaTestScalaCheckVersion % Test,
      "org.mdedetrich"             %% "scalacheck"                     % scalaCheckVersion          % Test,
      "com.rallyhealth"            %% "scalacheck-ops_1-15"            % scalaCheckOpsVersion       % Test,
      "com.softwaremill.diffx"     %% "diffx-scalatest-must"           % diffxVersion               % Test,
      "com.typesafe.akka"          %% "akka-stream-testkit"            % akkaVersion                % Test,
      "com.typesafe.akka"          %% "akka-http-testkit"              % akkaHttpVersion            % Test,
      "com.dimafeng"               %% "testcontainers-scala-scalatest" % testContainersVersion      % Test,
      "com.dimafeng"               %% "testcontainers-scala-kafka"     % testContainersVersion      % Test,
      "org.testcontainers"          % "kafka"                          % testContainersJavaVersion  % Test
    )
  )

lazy val coreCli = project
  .in(file("core-cli"))
  .settings(
    publish / skip       := true,
    publishLocal / skip  := true,
    publishSigned / skip := true,
    scalacOptions ++= Seq(
      "-opt-inline-from:**", // See https://www.lightbend.com/blog/scala-inliner-optimizer
      "-opt:l:method"
    ) ++ flagsFor13,
    name := s"$baseName-core-cli",
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-actor"  % akkaVersion,
      "com.typesafe.akka" %% "akka-stream" % akkaVersion,
      "com.typesafe.akka" %% "akka-slf4j"  % akkaVersion,
      "com.monovore"      %% "decline"     % declineVersion,
      "com.beachape"      %% "enumeratum"  % enumeratumVersion
    )
  )
  .dependsOn(core)

lazy val coreS3 = project
  .in(file("core-s3"))
  .settings(
    librarySettings,
    name := s"$baseName-s3",
    libraryDependencies ++= Seq(
      "com.lightbend.akka" %% "akka-stream-alpakka-s3" % alpakkaVersion,
      // Ordinarily this would be in Test scope however if its not then a lower version of akka-http-xml which has a
      // security vulnerability gets resolved in Compile scope
      "com.typesafe.akka" %% "akka-http-xml"   % akkaHttpVersion,
      "org.scalatest"     %% "scalatest"       % scalaTestVersion           % Test,
      "org.scalatestplus" %% "scalacheck-1-15" % scalaTestScalaCheckVersion % Test
    )
  )
  .dependsOn(core % "compile->compile;test->test")

lazy val coreGCS = project
  .in(file("core-gcs"))
  .settings(
    librarySettings,
    name := s"$baseName-gcs",
    libraryDependencies ++= Seq(
      "com.lightbend.akka" %% "akka-stream-alpakka-google-cloud-storage" % alpakkaVersion,
      // Ordinarily this would be in Test scope however if its not then a lower version of akka-http-spray-json which
      // has a security vulnerability gets resolved in Compile scope
      "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpVersion,
      "org.scalatest"     %% "scalatest"            % scalaTestVersion           % Test,
      "org.scalatestplus" %% "scalacheck-1-15"      % scalaTestScalaCheckVersion % Test
    )
  )
  .dependsOn(core % "compile->compile;test->test")

lazy val coreBackup = project
  .in(file("core-backup"))
  .settings(
    librarySettings,
    name := s"$baseName-core-backup"
  )
  .dependsOn(core % "compile->compile;test->test")

lazy val backupS3 = project
  .in(file("backup-s3"))
  .settings(
    librarySettings,
    Test / fork := true,
    name        := s"$baseName-backup-s3"
  )
  .dependsOn(coreS3 % "compile->compile;test->test", coreBackup % "compile->compile;test->test")

lazy val backupGCS = project
  .in(file("backup-gcs"))
  .settings(
    librarySettings,
    Test / fork := true,
    name        := s"$baseName-backup-gcs"
  )
  .dependsOn(coreGCS % "compile->compile;test->test", coreBackup % "compile->compile;test->test")

lazy val cliBackup = project
  .in(file("cli-backup"))
  .settings(
    cliSettings,
    name                 := s"$baseName-cli-backup",
    executableScriptName := "guardian-backup"
  )
  .dependsOn(coreCli   % "compile->compile;test->test",
             backupS3  % "compile->compile;test->test",
             backupGCS % "compile->compile;test->test"
  )
  .enablePlugins(JavaAppPackaging)

lazy val coreCompaction = project
  .in(file("core-compaction"))
  .settings(
    librarySettings,
    name := s"$baseName-core-compaction",
    libraryDependencies ++= Seq(
      "org.postgresql" % "postgresql" % postgresqlJdbcVersion
    )
  )
  .dependsOn(core)

lazy val compactionS3 = project
  .in(file("compaction-s3"))
  .settings(
    librarySettings,
    name := s"$baseName-compaction-s3"
  )
  .dependsOn(coreS3, coreCompaction)

lazy val compactionGCS = project
  .in(file("compaction-gcs"))
  .settings(
    librarySettings,
    name := s"$baseName-compaction-gcs"
  )
  .dependsOn(coreGCS, coreCompaction)

lazy val cliCompaction = project
  .in(file("cli-compaction"))
  .settings(
    cliSettings,
    name                 := s"$baseName-cli-compaction",
    executableScriptName := "guardian-compaction"
  )
  .dependsOn(coreCli, compactionS3, compactionGCS)
  .enablePlugins(JavaAppPackaging)

lazy val coreRestore = project
  .in(file("core-restore"))
  .settings(
    librarySettings,
    name := s"$baseName-core-restore"
  )
  .dependsOn(core % "compile->compile;test->test")
  .dependsOn(coreBackup % "test->test")

lazy val restoreS3 = project
  .in(file("restore-s3"))
  .settings(
    librarySettings,
    name := s"$baseName-restore-s3"
  )
  .dependsOn(coreRestore % "compile->compile;test->test", coreS3 % "compile->compile;test->test")
  .dependsOn(backupS3 % "test->compile")

lazy val restoreGCS = project
  .in(file("restore-gcs"))
  .settings(
    librarySettings,
    name := s"$baseName-restore-gcs"
  )
  .dependsOn(coreRestore % "compile->compile;test->test", coreGCS % "compile->compile;test->test")

lazy val cliRestore = project
  .in(file("cli-restore"))
  .settings(
    cliSettings,
    name                 := s"$baseName-cli-restore",
    executableScriptName := "guardian-restore"
  )
  .dependsOn(coreCli    % "compile->compile;test->test",
             restoreS3  % "compile->compile;test->test",
             restoreGCS % "compile->compile;test->test"
  )
  .enablePlugins(JavaAppPackaging)

def binaryVersion(key: String): String = key.substring(0, key.lastIndexOf('.'))

lazy val docs = project
  .enablePlugins(ParadoxPlugin, ParadoxSitePlugin, PreprocessPlugin, GhpagesPlugin)
  .settings(
    Compile / paradox / name     := "Guardian for Apache Kafka",
    publish / skip               := true,
    makeSite                     := makeSite.dependsOn(LocalRootProject / ScalaUnidoc / doc).value,
    previewPath                  := (Paradox / siteSubdirName).value,
    paradoxTheme                 := Some(builtinParadoxTheme("generic")),
    apidocRootPackage            := "io.aiven.guardian",
    Preprocess / siteSubdirName  := s"api/${projectInfoVersion.value}",
    Preprocess / sourceDirectory := (LocalRootProject / ScalaUnidoc / unidoc / target).value,
    git.remoteRepo               := scmInfo.value.get.connection.replace("scm:git:", ""),
    paradoxGroups                := Map("Language" -> Seq("Scala")),
    paradoxProperties ++= Map(
      "akka.version"                     -> akkaVersion,
      "akka-http.version"                -> akkaHttpVersion,
      "akka-streams-json.version"        -> akkaStreamsJson,
      "pure-config.version"              -> pureConfigVersion,
      "decline.version"                  -> declineVersion,
      "scala-logging.version"            -> scalaLoggingVersion,
      "extref.akka.base_url"             -> s"https://doc.akka.io/api/akka/${binaryVersion(akkaVersion)}/%s",
      "extref.akka-docs.base_url"        -> s"https://doc.akka.io/docs/akka/${binaryVersion(akkaVersion)}/%s",
      "extref.akka-stream-json.base_url" -> s"https://github.com/mdedetrich/akka-streams-json",
      "extref.alpakka.base_url"          -> s"https://doc.akka.io/api/alpakka/${binaryVersion(alpakkaVersion)}/%s",
      "extref.alpakka-docs.base_url"     -> s"https://docs.akka.io/docs/alpakka/${binaryVersion(alpakkaVersion)}/%s",
      "extref.alpakka-kafka-docs.base_url" -> s"https://docs.akka.io/docs/alpakka-kafka/${binaryVersion(alpakkaVersion)}/%s",
      "extref.kafka-docs.base_url" -> s"https://kafka.apache.org/${binaryVersion(kafkaClientsVersion).replace(".", "")}/%s",
      "extref.pureconfig.base_url"          -> s"https://pureconfig.github.io/docs/",
      "extref.scalatest.base_url"           -> s"https://www.scalatest.org/scaladoc/$scalaTestVersion/org/scalatest/%s",
      "scaladoc.io.aiven.guardian.base_url" -> s"/guardian-for-apache-kafka/${(Preprocess / siteSubdirName).value}/"
    )
  )

ThisBuild / homepage := Some(url("https://github.com/aiven/guardian-for-apache-kafka"))

ThisBuild / scmInfo := Some(
  ScmInfo(url("https://github.com/aiven/guardian-for-apache-kafka"),
          "scm:git:git@github.com:aiven/guardian-for-apache-kafka.git"
  )
)

ThisBuild / startYear := Some(2021)

ThisBuild / developers := List(
  Developer("jlprat", "Josep Prat", "josep.prat@aiven.io", url("https://github.com/jlprat")),
  Developer("mdedetrich", "Matthew de Detrich", "matthew.dedetrich@aiven.io", url("https://github.com/mdedetrich")),
  Developer("reta", "Andriy Redko", "andriy.redko@aiven.io", url("https://github.com/reta")),
  Developer("RyanSkraba", "Ryan Skraba", "ryan.skraba@aiven.io", url("https://github.com/RyanSkraba"))
)

maintainer := "matthew.dedetrich@aiven.io"

ThisBuild / licenses += ("Apache-2.0", url("https://opensource.org/licenses/Apache-2.0"))

// This is currently causing problems, see https://github.com/djspiewak/sbt-github-actions/issues/74
ThisBuild / githubWorkflowUseSbtThinClient := false

ThisBuild / githubWorkflowTargetBranches := Seq("main")

// Once we have branches per version, add the pattern here, see
// https://github.com/djspiewak/sbt-github-actions#integration-with-sbt-ci-release
ThisBuild / githubWorkflowPublishTargetBranches := Seq(RefPredicate.Equals(Ref.Branch("main")))

ThisBuild / githubWorkflowPublish := Seq(WorkflowStep.Sbt(List("docs/ghpagesPushSite")))
ThisBuild / githubWorkflowPublishPreamble := Seq(
  // Taken from https://github.com/actions/checkout/issues/13#issue-481453396
  WorkflowStep.Run(
    commands = List(
      "git config --global user.name \"$(git --no-pager log --format=format:'%an' -n 1)\"",
      "git config --global user.email \"$(git --no-pager log --format=format:'%ae' -n 1)\""
    )
  ),
  WorkflowStep.Use(
    ref = UseRef.Public("webfactory", "ssh-agent", "v0.5.4"),
    params = Map(
      "ssh-private-key" -> "${{ secrets.GH_PAGES_SSH_PRIVATE_KEY }}"
    )
  )
)

ThisBuild / githubWorkflowBuildPreamble := Seq(
  WorkflowStep.Sbt(List("scalafixAll --check"), name = Some("Linter: Scalafix checks"))
)

// Configuration needed for Scalafix
ThisBuild / scalafixDependencies += "com.github.liancheng" %% "organize-imports" % organizeImportsVersion

ThisBuild / scalafixScalaBinaryVersion := scalaBinaryVersion.value

ThisBuild / semanticdbEnabled := true

// See https://scalacenter.github.io/scalafix/docs/users/installation.html#sbt
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision

ThisBuild / githubWorkflowJavaVersions := List(JavaSpec.temurin("11"))

ThisBuild / githubWorkflowBuildPreamble ++= Seq(
  WorkflowStep.Use(
    UseRef.Public("aws-actions", "configure-aws-credentials", "v1"),
    name = Some("Configure AWS credentials"),
    params = Map(
      "role-to-assume" -> "arn:aws:iam::310017459104:role/aiven-guardian-github-action",
      "aws-region"     -> "us-west-2"
    )
  )
)

ThisBuild / githubWorkflowBuild := Seq(
  WorkflowStep.Sbt(
    List("clean", "coverage", "test"),
    name = Some("Build project"),
    env = Map(
      "ALPAKKA_S3_REGION_PROVIDER"          -> "default",
      "ALPAKKA_S3_AWS_CREDENTIALS_PROVIDER" -> "default"
    )
  ),
  WorkflowStep.Sbt(List("docs/makeSite"), name = Some("Compile docs"))
)

ThisBuild / githubWorkflowBuildPostamble ++= Seq(
  // See https://github.com/scoverage/sbt-coveralls#github-actions-integration
  WorkflowStep.Sbt(
    List("coverageReport", "coverageAggregate", "coveralls"),
    name = Some("Upload coverage data to Coveralls"),
    env = Map(
      "COVERALLS_REPO_TOKEN" -> "${{ secrets.GITHUB_TOKEN }}",
      "COVERALLS_FLAG_NAME"  -> "Scala ${{ matrix.scala }}"
    )
  )
)

dependencyCheckOutputDirectory := Some(baseDirectory.value / "dependency-check")
dependencyCheckSuppressionFile := Some(baseDirectory.value / "dependency-check" / "suppression.xml")

import ReleaseTransformations._

releaseCrossBuild := true
releaseProcess := Seq[ReleaseStep](
  checkSnapshotDependencies,
  inquireVersions,
  runClean,
  runTest,
  setReleaseVersion,
  commitReleaseVersion,
  tagRelease,
  releaseStepCommandAndRemaining("+publishSigned"),
  releaseStepCommand("sonatypeReleaseAll"),
  setNextVersion,
  commitNextVersion,
  pushChanges
)
