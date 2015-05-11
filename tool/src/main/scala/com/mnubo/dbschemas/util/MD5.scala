package com.mnubo.dbschemas.util

import java.io.File
import java.nio.file.{Paths, Files}
import java.security.MessageDigest

import com.mnubo.app_util.Logging
import com.mnubo.dbschemas.{Statement, ClassStatement, StatementFiles}

object MD5 extends Logging {
  def forBytes(bytes: Array[Byte]) =
    MessageDigest
      .getInstance("MD5")
      .digest(bytes)
      .map("%02x".format(_))
      .mkString

  def forStatementFile(version: String, stmtType: String) = {
    val stmtFile = StatementFiles.findStatementFile(version, stmtType)

    forStatements(
      stmtFile,
      StatementFiles.parseStatements(stmtFile)
    )
  }

  def forStatements(stmtFile: File, stmts: Seq[Statement]) = {
    val classFiles =
      stmts
        .flatMap {
          case cs: ClassStatement =>
            cs.sourceFile
          case _ =>
            None
        }
        .sorted

    val bytes = (classFiles :+ stmtFile.getPath)
      .map(f => Files.readAllBytes(Paths.get(f)))
      .reduce(_ ++ _)

    forBytes(bytes)
  }
}
