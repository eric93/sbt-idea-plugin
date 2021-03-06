package org.jetbrains.sbt
package tasks

import java.net.URI
import java.nio.file.FileSystems.newFileSystem
import java.nio.file._
import java.nio.file.attribute.BasicFileAttributes
import java.util

import org.jetbrains.sbtidea.Keys.PackagingMethod._
import org.jetbrains.sbtidea.Keys.PackagingMethod
import org.jetbrains.sbtidea.tasks.{NoOpClassShader, ShadePattern, ShadingPackager}
import sbt.Def.Classpath
import sbt.Keys.{TaskStreams, moduleID}
import sbt.jetbrains.apiAdapter._
import sbt.{File, ModuleID, ProjectRef, UpdateReport, _}

import scala.collection.mutable

object PluginPackager {

  type Mapping = (File, File, Seq[ShadePattern]) // from, to, shading
  type Mappings = Seq[Mapping]

  private implicit def MappingOrder[A <: Mapping]: Ordering[A] = Ordering.by(x=> x._1 -> x._2) // order by target jar file

  def artifactMappings(rootProject: ProjectRef,
                       outputDir: File,
                       projectsData: Seq[ProjectData],
                       buildDependencies: BuildDependencies,
                       streams: TaskStreams): Mappings = {

    def mkProjectData(projectData: ProjectData): ProjectData = {
      if (projectData.thisProject == rootProject && !projectData.packageMethod.isInstanceOf[Standalone]) {
        projectData.copy(packageMethod = Standalone())
      } else projectData
    }

    val projectMap    = projectsData.iterator.map(x => x.thisProject -> mkProjectData(x)).toMap
    val revProjectMap = projectsData.flatMap(x => buildDependencies.classpathRefs(x.thisProject).map(_ -> x.thisProject))

    def findProjectRef(project: Project): Option[ProjectRef] = projectMap.find(_._1.project == project.id).map(_._1)

    def walk(ref: ProjectRef, queue: Seq[ProjectRef]): Seq[ProjectRef] = {
      val data = projectMap(ref)
      if (!queue.contains(ref)) {
        val newQueue = queue :+ ref
        val direct = buildDependencies.classpathRefs(ref).foldLeft(newQueue) { case (q, r) => walk(r, q) }
        val additional = data.additionalProjects.flatMap(findProjectRef).foldLeft(direct) { case (q, r) => walk(r, q) }
        additional
      } else { queue }
    }

    def buildStructure(ref: ProjectRef): Mappings = {
      val artifactMap = new mutable.TreeSet[(File, File)]()

      def findParentToMerge(ref: ProjectRef): ProjectRef = projectMap.getOrElse(ref,
        throw new RuntimeException(s"Project $ref has no associated ProjectData")) match {
          case ProjectData(p, _, _, _, _, _, _, _, _, _: Standalone, _) => p
          case ProjectData(_, _, _, _, _, _, _, _, _, _: Skip, _)       => null
          case _ =>
            val xx = revProjectMap.filter(_._1 == ref).map(_._2).map(findParentToMerge).filter(_ != null)
            if (xx.size > 1) throw new RuntimeException(s"Multiple parents found for $ref: $xx")
            if (xx.isEmpty) throw new RuntimeException(s"No parents found for $ref")
            xx.head
      }

      val ProjectData(_,
                      cp,
                      definedDeps,
                      _,
                      assembleLibraries,
                      productDirs,
                      report,
                      libMapping,
                      additionalMappings,
                      method,
                      shadePatterns) = projectMap(ref)

      implicit val scalaVersion: ProjectScalaVersion = ProjectScalaVersion(definedDeps.find(_.name == "scala-library"))

      val resolver              = new TransitiveDeps(report, "compile")
      val mappings              = libMapping.map(x => x._1.key -> x._2).toMap
      val resolvedLibsNoEvicted = buildModuleIdMap(cp)
      val resolvedLibs          = updateWithEvictionMappings(resolvedLibsNoEvicted, resolver.evicted)
      val transitiveDeps        = definedDeps
        .filter(_.configurations.isEmpty)
        .map(_.key)
        .flatMap(resolver.collectTransitiveDeps)
      val processedLibs = transitiveDeps.map(m => m -> resolvedLibs.get(m))
        .map {
          case x@(mod, None) => streams.log.warn(s"couldn't resolve dependency jar: $mod"); x
          case other => other
        }.collect {
        case (mod, Some(file)) if !mappings.contains(mod)                 => file -> outputDir / mkRelativeLibPath(file)
        case (mod, Some(file)) if mappings.getOrElse(mod, None).isDefined => file -> outputDir / mappings(mod).get
      }

      val targetJar = method match {
        case Skip() => None
        case DepsOnly(targetPath) =>
          Some(outputDir / targetPath)
        case MergeIntoParent() =>
          val parent = findParentToMerge(ref)
          val parentFile = mkProjectJarPath(parent)
          productDirs.foreach { artifactMap += _ -> outputDir / parentFile }
          Some(outputDir / parentFile)
        case MergeIntoOther(project) =>
          val parent = findParentToMerge(findProjectRef(project)
            .getOrElse(throw new RuntimeException(s"Couldn't resolve project $project")))
          val otherFile = mkProjectJarPath(parent)
          productDirs.map { artifactMap += _ -> outputDir/ otherFile }
          Some(outputDir/ otherFile)
        case Standalone("") =>
          val file = outputDir / mkProjectJarPath(ref)
          productDirs.foreach { artifactMap += _ -> file }
          Some(file)
        case Standalone(targetPath) =>
          val file = outputDir / targetPath
          productDirs.foreach { artifactMap += _ -> file }
          Some(file)
      }

      targetJar match {
        case Some(file) if assembleLibraries =>
          artifactMap ++= processedLibs.map { case (in, _) => in -> file }
        case _ =>
          artifactMap ++= processedLibs
      }

      artifactMap ++= additionalMappings.map { case (from, to) => from -> outputDir / fixPaths(to) }

      artifactMap.map{case (a, b) => (a, b, shadePatterns)}.toSeq
    }

    streams.log.info("traversing dependency graph")
    val queue       = walk(rootProject, Seq.empty).reverse
    streams.log.info(s"built processing queue: ${queue.map(_.project)}")
    streams.log.info(s"building mappings")
    val structures  = queue.map(buildStructure)
    val result      = new mutable.TreeSet[Mapping]()
    structures.foreach(result ++= _)
    streams.log.info(s"finished building structure: got ${result.size} mappings")

    result.toSeq
  }

  private def fixPaths(str: String): String = System.getProperty("os.name") match {
    case os if os.startsWith("Windows") => str.replace('/', '\\')
    case _ => str.replace('\\', '/')
  }

  private def buildModuleIdMap(cp: Classpath)(implicit scalaVersion: ProjectScalaVersion): Map[ModuleKey, File] = (for {
    jarFile <- cp
    moduleId <- jarFile.get(moduleID.key)
  } yield { moduleId.key -> jarFile.data }).toMap

  private def updateWithEvictionMappings(cpNoEvicted: Map[ModuleKey, File], evicted: Seq[ModuleKey]): Map[ModuleKey, File] = {
    val evictionSubstitutes = evicted
      .map(ev => ev -> cpNoEvicted.find(entry => entry._1 ~== ev).map(_._2)
        .getOrElse(throw new RuntimeException(s"Can't resolve eviction for $ev")))
    cpNoEvicted ++ evictionSubstitutes
  }

  def zipDirectory(root: File, out: File): Unit = manyToJar(Seq(root), out, new NoOpClassShader())

  def packageArtifact(structure: Mappings, streams: TaskStreams): Unit = {
    implicit val stream: TaskStreams = streams
    val grouped             = structure.groupBy(_._2)
    val (overrides, normal) = grouped.partition(_._1.toString.contains("!/"))
    val incremental         = normal.filterNot {
      case (_, mappings) => mappings.forall {case (from, to, _) => from.isFile && from.lastModified() <= to.lastModified()}
    }

    if (normal.size != incremental.size)
      stream.log.info(s"filtered ${normal.size-incremental.size} jars - files not changed")

    incremental.keys.foreach(IO.delete)
    incremental.foreach {
      case (to, Seq((from, _, rules))) if to.name.endsWith("jar") && from.name.endsWith("jar") =>
        timed(s"copyJar: $to", {
            IO.copy(Seq(from -> to))
        })
      case (to, mappings) if to.name.endsWith("jar")  =>
        if (!to.getParentFile.exists())
          to.getParentFile.mkdirs()
        timed(s"packageJar(${mappings.size}): $to", {
          val rules = mappings.flatMap(_._3).distinct
          val packager = if (rules.nonEmpty) new ShadingPackager(rules) else new NoOpClassShader
          manyToJar(mappings.map(_._1), to, packager)
        })
      case (to, mapping) =>
        timed(s"copyDir: $to", {
          mapping.foreach {
            case (from, to1, _) if from.isDirectory => IO.copyDirectory(from, to1)
            case (from, to1, _) => IO.copy(Seq(from -> to1))
          }
        })
      case other => streams.log.warn(s"wtf: $other")
    }

    streams.log.info("start processing overrides")
    overrides.foreach {
      case (to, mapping) if to.toString.contains("jar!/")  =>
        timed(s"patchJar: $to", {
          val (outJar, jarRoot) = getPathInJar(to)
          manyToJar(mapping.map(_._1), outJar, new NoOpClassShader(), jarRoot)
        })
      case other => streams.log.warn(s"wtf: $other")
    }
  }

  private def manyToJar(input: Seq[File], out: File, shadingPackager: ShadingPackager, jarRoot: String = ""): Unit = {
    val env = new util.HashMap[String, String]()
    env.put("create", String.valueOf(Files.notExists(out.toPath)))
    val jarFs     = newFileSystem(URI.create("jar:" + out.toPath.toUri), env)
    try     { input.foreach(i => zip(i, jarFs, jarRoot, shadingPackager)) }
    finally { jarFs.close() }
  }

  private def getPathInJar(output: File): (File, String) = output.getPath.split("!/") match {
    case Array(jarFile, path) => file(jarFile) -> path
    case _                    => output        -> ""
  }

  private def zip(input: File, output: FileSystem, jarRoot: String, shadingPackager: ShadingPackager): Unit = {
    if (!input.exists()) return
    val env = new util.HashMap[String, String]()
    val inputFS = if (input.getName.endsWith(".jar")) Some(newFileSystem(URI.create("jar:" + input.toPath.toUri), env)) else None
    val inputPath = inputFS.map(_.getPath("/")).getOrElse(input.toPath)

    try {
      Files.walkFileTree(inputPath, new SimpleFileVisitor[Path]() {
        override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
          val newPathInJar = jarRoot.nonEmpty match {
            case true if jarRoot.endsWith("/") => output.getPath(jarRoot, file.getFileName.toString) // copy file to dir inside a jar
            case true => output.getPath(jarRoot) // copy file to file inside a jar
            case false =>
              val path = output.getPath(inputPath.relativize(file).toString)
              if (path.toString.isEmpty)
                output.getPath(file.getFileName.toString)
              else path
          }
          if (newPathInJar.getParent != null) Files.createDirectories(newPathInJar.getParent)
          shadingPackager.applyShading(file, newPathInJar)
          FileVisitResult.CONTINUE
        }
      })
    } finally {
      inputFS.foreach(_.close())
    }
  }

  private def timed[T](msg: String, f: => T)(implicit streams: TaskStreams): T = {
    val start = System.currentTimeMillis()
    val res = f
    streams.log.info(s"(${System.currentTimeMillis() - start}ms) $msg")
    res
  }

  case class ProjectData(thisProject: ProjectRef,
                         cp: Classpath,
                         definedDeps: Seq[ModuleID],
                         additionalProjects: Seq[Project],
                         assembleLibraries: Boolean,
                         productDirs: Seq[File],
                         report: UpdateReport,
                         libMapping: Seq[(ModuleID, Option[String])],
                         additionalMappings: Seq[(File, String)],
                         packageMethod: PackagingMethod,
                         shadePatterns: Seq[ShadePattern])


  /**
    * Extract only key-relevant parts of the ModuleId, so that mappings succeed even if they contain extra attributes
    */
  implicit class ModuleIdExt(val moduleId: ModuleID) extends AnyVal {

    def key(implicit scalaVersion: ProjectScalaVersion): ModuleKey = {
      val versionSuffix = moduleId.crossVersion match {
        case _:CrossVersion.Binary if scalaVersion.isDefined =>
          "_" + CrossVersion.binaryScalaVersion(scalaVersion.str)
        case _ => ""
      }
      ModuleKey(
        moduleId.organization % (moduleId.name + versionSuffix) % moduleId.revision,
        moduleId.extraAttributes
          .map    { case (k, v) => k.stripPrefix("e:") -> v }
          .filter { case (k, _) => k == "scalaVersion" || k == "sbtVersion" })
    }

  }

  case class ModuleKey(id:ModuleID, attributes: Map[String,String]){
    def ~==(other: ModuleKey): Boolean = id.organization == other.id.organization && id.name == other.id.name
    override def hashCode(): Int = id.organization.hashCode
    override def equals(o: scala.Any): Boolean = o match {
      case ModuleKey(_id, _attributes) =>
        id.organization.equals(_id.organization) &&
          (id.name == _id.name || id.name.matches(_id.name)) &&
          (id.revision == _id.revision || id.revision.matches(_id.revision)) &&
          attributes == _attributes
      case _ => false
    }

    override def toString: String = s"$id[${if (attributes.nonEmpty) attributes.toString else ""}]"
  }

  class TransitiveDeps(report: UpdateReport, configuration: String)(implicit scalaVersion: ProjectScalaVersion) {
    val structure: Map[ModuleKey, Seq[ModuleKey]] = buildTransitiveStructure()
    val evicted:   Seq[ModuleKey]                 = report.configurations
      .find(_.configuration.toString().contains(configuration))
      .map (_.details.flatMap(_.modules)
          .filter(m => m.evicted && m.evictedReason.get == "latest-revision")
        .map(_.module.key)
      ).getOrElse(Seq.empty)

    private def buildTransitiveStructure(): Map[ModuleKey, Seq[ModuleKey]] = {
      report.configurations.find(_.configuration.toString().contains(configuration)) match {
        case Some(conf) =>
          val edges = conf.modules.flatMap(m => m.callers.map(caller => caller.caller.key -> m.module.key))
          edges.foldLeft(Map[ModuleKey, Seq[ModuleKey]]()) {
            case (map, (caller, mod)) if caller.id.name.startsWith("temp-resolve") => map + (mod -> Seq.empty) // top level dependency
            case (map, (caller, mod)) => map + (caller -> (map.getOrElse(caller, Seq()) :+ mod))
          }
        case None => Map.empty
      }
    }

    def collectTransitiveDeps(moduleID: ModuleKey): Set[ModuleKey] = {
      val deps = structure.getOrElse(moduleID, Seq.empty)
      (deps ++ deps.flatMap(collectTransitiveDeps) :+ moduleID).toSet
    }
  }

  case class ProjectScalaVersion(libModule: Option[ModuleID]) {
    def isDefined = libModule.isDefined
    def str = libModule.map(_.revision).getOrElse("")
  }

  private def mkProjectJarPath(project: ProjectReference): String = s"lib/${extractName(project)}.jar"

  private def mkRelativeLibPath(lib: File) = s"lib/${lib.getName}"

  private def extractName(project: ProjectReference): String = {
    val str = project.toString
    val commaIdx = str.indexOf(',')
    str.substring(commaIdx+1, str.length-1)
  }
}
