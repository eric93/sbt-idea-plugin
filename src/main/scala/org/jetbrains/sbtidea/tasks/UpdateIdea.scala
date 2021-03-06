package org.jetbrains.sbtidea.tasks

import java.io.IOException
import java.util.zip.ZipFile

import org.jetbrains.sbtidea.Keys._
import sbt.Keys._
import sbt._


object UpdateIdea {

  def apply(baseDir: File, edition: IdeaEdition, build: String,
            downloadSources: Boolean,
            externalPlugins: Seq[IdeaPlugin],
            streams: TaskStreams): Unit = {
    try {
      doUpdate(baseDir, IdeaEdition.Community, build, downloadSources = true, externalPlugins, streams)
    } catch {
      case e: sbt.TranslatedException if e.getCause.isInstanceOf[java.io.FileNotFoundException] =>
        val newBuild = build.split('.').init.mkString(".") + "-EAP-CANDIDATE-SNAPSHOT"
        streams.log.warn(s"Failed to download IDEA $build, trying $newBuild")
        IO.deleteIfEmpty(Set(baseDir))
        doUpdate(baseDir, IdeaEdition.Community, newBuild, downloadSources = true, Seq.empty, streams)
    }
  }

  def doUpdate(baseDir: File, edition: IdeaEdition, build: String,
            downloadSources: Boolean,
            externalPlugins: Seq[IdeaPlugin],
            streams: TaskStreams): Unit = {
    implicit val log: Logger = streams.log

    if (baseDir.isDirectory)
      log.debug(s"Skip downloading and unpacking IDEA because $baseDir exists")
    else
      downloadIdeaBinaries(baseDir, edition, build)

    if (downloadSources) {
      val sourcesFile = baseDir / "sources.zip"
      if (sourcesFile.isFile)
        log.debug(s"Skip downloading IDEA sources because $sourcesFile exists")
      else
        downloadIdeaSources(sourcesFile, build)
    }

    val externalPluginsDir = baseDir / "externalPlugins"
    downloadExternalPlugins(externalPluginsDir, externalPlugins, edition, build)
    movePluginsIntoRightPlace(externalPluginsDir, externalPlugins)
  }

  private def downloadIdeaBinaries(baseDir: File, edition: IdeaEdition, build: String)(implicit log: Logger): Unit = {
    val repositoryUrl = getRepositoryForBuild(build)
    val ideaUrl = url(s"$repositoryUrl/${edition.name}/$build/${edition.name}-$build.zip")
    val ideaZipFile = baseDir.getParentFile / s"${edition.name}-$build.zip"
    downloadOrFail(ideaUrl, ideaZipFile)
    unpack(ideaZipFile, baseDir)
  }

  private def downloadIdeaSources(sourcesFile: File, build: String)(implicit log: Logger): Unit = {
    val repositoryUrl = getRepositoryForBuild(build)
    val ideaSourcesJarUrl = url(s"$repositoryUrl/ideaIC/$build/ideaIC-$build-sources.jar")
    val ideaSourcesZipUrl = url(s"$repositoryUrl/ideaIC/$build/ideaIC-$build-sources.zip")
    downloadOrLog(ideaSourcesJarUrl, sourcesFile)
    downloadOrLog(ideaSourcesZipUrl, sourcesFile)
  }

  private def getRepositoryForBuild(build: String): String = {
    val repository = if (build.endsWith("SNAPSHOT")) "snapshots" else "releases"
    s"https://www.jetbrains.com/intellij-repository/$repository/com/jetbrains/intellij/idea"
  }

  private def downloadExternalPlugins(baseDir: File, plugins: Seq[IdeaPlugin], edition: IdeaEdition, build: String)(implicit log: Logger): Unit =
    plugins.foreach { plugin =>
      val pluginDir = baseDir / plugin.name
      if (pluginDir.isDirectory)
        log.debug(s"Skip downloading ${plugin.name} external plugin because $pluginDir exists")
      else
        plugin match {
          case IdeaPlugin.Zip(pluginName, pluginUrl) =>
            val pluginZipFile = baseDir / s"$pluginName.zip"
            downloadOrFail(pluginUrl, pluginZipFile)
            unpack(pluginZipFile, baseDir / pluginName)
          case IdeaPlugin.Jar(pluginName, pluginUrl) =>
            downloadOrFail(pluginUrl, baseDir / s"$pluginName.jar")
          case IdeaPlugin.Id(pluginName, id, channel) =>
            val chanStr = channel.map(c=>s"&channel=$c").getOrElse("")
            val urlStr = s"https://plugins.jetbrains.com/pluginManager?action=download&id=$id$chanStr&build=${edition.shortname}-$build"
            val file = baseDir / s"$pluginName.plg"
            downloadOrFail(new URL(urlStr), file)
            if (new ZipFile(file).entries().nextElement().getName == s"$pluginName/") // zips have a single folder in root with the same name as the plugin
              unpack(file, baseDir / pluginName)
        }
    }

  private def movePluginsIntoRightPlace(externalPluginsDir: File, plugins: Seq[IdeaPlugin]): Unit =
    plugins.foreach { plugin =>
      val pluginDir = externalPluginsDir / plugin.name
      val childDirs = listDirectories(pluginDir)
      if (childDirs.forall(_.getName != "lib")) {
        val dirThatContainsLib = childDirs.find(d => listDirectories(d).exists(_.getName == "lib"))
        dirThatContainsLib.foreach { dir =>
          IO.copyDirectory(dir, pluginDir)
          IO.delete(dir)
        }
      }
    }

  private def listDirectories(dir: File): Seq[File] =
    Option(dir.listFiles).toSeq.flatten.filter(_.isDirectory)

  private def downloadOrFail(from: URL, to: File)(implicit log: Logger): Unit =
    if (to.isFile) {
      log.debug(s"Skip downloading $from because $to exists")
    } else {
      log.info(s"Downloading $from to $to")
      download(from, to)
    }

  private def downloadOrLog(from: URL, to: File)(implicit log: Logger): Unit =
    try {
      downloadOrFail(from, to)
    } catch {
      case exc: IOException =>
        log.warn(s"Abort downloading $from because of exception: ${exc.getMessage}")
    }

  private def unpack(from: File, to: File)(implicit log: Logger): Unit = {
    log.info(s"Unpacking $from to $to")
    sbt.IO.unzip(from, to)
  }

  private def download(from: sbt.URL, to: File): Unit = {
    import sbt.jetbrains.apiAdapter._
    Using.urlInputStream(from) { inputStream =>
       IO.transfer(inputStream, to)
     }
  }

}
