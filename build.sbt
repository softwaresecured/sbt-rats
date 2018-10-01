sbtPlugin := true

name := "sbt-rats"

version in ThisBuild := "2.5.0"

organization in ThisBuild := "org.bitbucket.inkytonik.sbt-rats"

// Scala compiler settings

scalaVersion := "2.12.6"

scalacOptions ++= Seq ("-deprecation", "-feature", "-unchecked")

scalaCompilerBridgeSource := {
  val sv = appConfiguration.value.provider.id.version
  ("org.scala-sbt" % "compiler-interface" % sv % "component").sources
}

// sbt settings

sbtVersion in Global := "1.2.3"

crossSbtVersions := Vector ("1.2.3", "0.13.17")

// Interactive settings

logLevel := Level.Info

shellPrompt := {
    state =>
        Project.extract(state).currentRef.project + " " + version.value +
            " " + (sbtVersion in pluginCrossBuild).value + " " +
            scalaVersion.value + "> "
}

// Dependencies

libraryDependencies ++= Seq (
    "com.googlecode.kiama" %% "kiama" % "1.8.0",
    "xtc" % "rats" % "2.4.0"
)

// Publishing

import bintray.Keys._

bintrayPublishSettings

licenses += ("BSD New", url (s"https://bitbucket.org/inkytonik/${name.value}/src/master/LICENSE"))

publishMavenStyle := false

repository in bintray := "sbt-plugins"

bintrayOrganization in bintray := None

vcsUrl in bintray := Some (s"https://bitbucket.org/inkytonik/${name.value}")
