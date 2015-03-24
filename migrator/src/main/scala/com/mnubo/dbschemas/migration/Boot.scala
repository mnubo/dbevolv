package com.mnubo
package dbschemas.migration

import java.io.{PrintStream, FileInputStream, InputStreamReader, File}

import com.google.gson.{JsonObject, JsonElement, JsonParser}

import collection.JavaConverters._

object Boot extends App {
  val parser = new JsonParser
  val Ingestion = "ingestion"
  val Enrichment = "enrichment"
  val Analytics = "analytics"
  val GlobalConfig = "mnuboglobalconfig"
  val TableSpec = """([a-z_\-0-9\.]+)"""
  val CreateTable1Regex = s"""(?i)create table if not exists $TableSpec .+""".r
  val CreateTable2Regex = s"""(?i)create table $TableSpec .+""".r
  val UpdateRegex = s"""(?i)update $TableSpec .+""".r
  val TruncateRegex = s"""(?i)truncate $TableSpec;""".r
  val AlterTableRegex = s"""(?i)alter table $TableSpec .+""".r
  val InsertIntoRegex = s"""(?i)insert into $TableSpec .+""".r
  val DropTable1Regex = s"""(?i)drop table $TableSpec;""".r
  val DropTable2Regex = s"""(?i)drop table if exists $TableSpec;""".r
  val DropTable3Regex = s"""(?i)drop $TableSpec;""".r
  val DeleteRegex = s"""(?i)delete from $TableSpec .+""".r

  val migrationLegacyFiles = new File("../mnubo-main/platform/schemamanager/app/src/main/resources/mnuboSchemas")
    .listFiles()
    .toSeq
    .filter(_.getName.endsWith(".json"))
    .sortBy(_.getName)

  val projectTableMapping = Map(
    "rta_per_state" -> Analytics,
    "objects_per_state" -> Analytics,
    "object_state" -> Analytics,
    "rta_numeric_results" -> Analytics,
    "rta_groupby_results" -> Analytics,
    "analytics_objects_with_imported_attributes" -> Enrichment
  ).withDefaultValue(Ingestion)

  migrationLegacyFiles.foreach { migrationFile =>
    val json = using(new InputStreamReader(new FileInputStream(migrationFile)))(parser.parse)
    val upgradeScript = getScript(json.getAsJsonObject, "UpgradeDelta")
    val downgradeScript = getScript(json.getAsJsonObject, "DowngradeDelta")
    var schemaScripts = Map(Ingestion -> Migration(), Enrichment -> Migration(), Analytics -> Migration(), GlobalConfig -> Migration())

    def splitInProjects(script: Seq[String], addCommand: (Migration, String) => Migration) =
      script.foreach { cmd =>
        val tableName = parseTableName(cmd)
        val project =
          if (tableName.isEmpty)
            Ingestion
          else if (tableName.startsWith("mnuboglobalconfig."))
            GlobalConfig
          else
            projectTableMapping(tableName)

        val script = schemaScripts(project)
        schemaScripts = schemaScripts.updated(project, addCommand(script, cmd))
      }

    splitInProjects(upgradeScript, _ addUpgrade _)
    splitInProjects(downgradeScript, _ addDowngrade _)

    val version = migrationFile.getName.replace("_MnuboSchema.json", "")
    println("Version " + version)
    schemaScripts
      .filterNot(_._2.isEmpty)
      .foreach { case (name, s) =>
        s.print(name)

        if (args.isEmpty) {
          val projectname = s"cassandra-$name"
          val migrationPath = new File(s"../$projectname/migrations/$version")
          if (!migrationPath.exists()) migrationPath.mkdirs()
          s.export(migrationPath)
        }
      }

  }

  def parseTableName(cmd: String) = cmd match {
    case CreateTable1Regex(table) => table
    case CreateTable2Regex(table) => table
    case UpdateRegex(table) => table
    case TruncateRegex(table) => table
    case AlterTableRegex(table) => table
    case InsertIntoRegex(table) => table
    case DropTable1Regex(table) => table
    case DropTable2Regex(table) => table
    case DropTable3Regex(table) => table
    case DeleteRegex(table) => table
    case clazz if clazz.startsWith("@@") => ""
    case _ => throw new Exception(s"Cannot parse command '$cmd'")
  }

  def getScript(json: JsonObject, sectionName: String) =
    getSQLCommands(json, sectionName, "Tables") ++
    getSQLCommands(json, sectionName, "Records") ++
    getSQLCommands(json, sectionName, "Classes").map("@@" + _)

  def getSQLCommands(json: JsonObject, sectionName: String, subSectionName: String) =
    if (json.has(sectionName) && json.get(sectionName).getAsJsonObject.has(subSectionName))
      json.get(sectionName).getAsJsonObject
        .get(subSectionName).getAsJsonArray
        .iterator().asScala
        .toSeq
        .map(_.getAsString + ";")
    else if (sectionName == "UpgradeDelta" && json.has(subSectionName)) // The damn 0.0.0.1 migration script has no UpgradeDelta / DowngradeDelta, it has the Tables subsection directly at the json root.
      json.get(subSectionName).getAsJsonArray
        .iterator().asScala
        .toSeq
        .map(_.getAsString + ";")
    else
      Nil
}

case class Migration(upgrade: Seq[String] = Nil, downgrade: Seq[String] = Nil) {
  def addUpgrade(cmd: String) = copy(upgrade = upgrade :+ cmd)
  def addDowngrade(cmd: String) = copy(downgrade = downgrade :+ cmd)

  def isEmpty = upgrade.isEmpty && downgrade.isEmpty

  def print(name: String) = {
    println(name + ":")
    println("  Upgrade:")
    upgrade.foreach(cmd => println("    " + cmd))
    println("  Downgrade:")
    downgrade.foreach(cmd => println("    " + cmd))
    println()
  }

  def export(dir: File) = {
    if (!upgrade.isEmpty)
      using(new PrintStream(new File(dir, "upgrade.cql"))) { p =>
        upgrade.foreach(p.println)
      }
    if (!downgrade.isEmpty)
      using(new PrintStream(new File(dir, "downgrade.cql"))) { p =>
        downgrade.foreach(p.println)
      }
  }
}
