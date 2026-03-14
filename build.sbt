import Dependencies._

name := "forex"
version := "1.0.1"

scalaVersion := "2.13.12"
scalacOptions ++= Seq(
  "-deprecation",
  "-encoding",
  "utf-8",
  "-explaintypes",
  "-feature",
  "-language:existentials",
  "-language:experimental.macros",
  "-language:higherKinds",
  "-language:implicitConversions",
  "-unchecked",
  "-Xcheckinit",
  "-Xfatal-warnings",
  "-Xlint:adapted-args",
  "-Xlint:delayedinit-select",
  "-Xlint:inaccessible",
  "-Xlint:infer-any",
  "-Xlint:missing-interpolator",
  "-Xlint:nullary-unit",
  "-Xlint:option-implicit",
  "-Xlint:package-object-classes",
  "-Xlint:poly-implicit-overload",
  "-Xlint:private-shadow",
  "-Xlint:stars-align",
  "-Xlint:type-parameter-shadow",
  "-Xlog-reflective-calls",
  "-Ywarn-dead-code",
  "-Ywarn-value-discard",
  "-Xlint:constant",
  "-Ywarn-extra-implicit",
  "-Ywarn-macros:after",
  "-Ywarn-unused:implicits",
  "-Ywarn-unused:imports",
  "-Ywarn-numeric-widen",
  "-Ywarn-unused:locals",
  "-Ywarn-unused:patvars",
  "-Ywarn-unused:implicits",
  "-Ycache-plugin-class-loader:last-modified",
  "-Ycache-macro-class-loader:last-modified"
)

resolvers += "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"

libraryDependencies ++= Seq(
  compilerPlugin(Libraries.kindProjector),
  Libraries.cats,
  Libraries.catsEffect,
  Libraries.fs2,
  Libraries.http4sDsl,
  Libraries.http4sServer,
  Libraries.http4sClient,
  Libraries.http4sCirce,
  Libraries.circeCore,
  Libraries.circeGeneric,
  Libraries.circeGenericExt,
  Libraries.circeParser,
  Libraries.pureConfig,
  Libraries.resilience4jCb,
  Libraries.logback,
  Libraries.scalaTest    % Test,
  Libraries.scalaCheck   % Test,
  Libraries.catsScalaCheck % Test
)

enablePlugins(ScalafmtPlugin)
