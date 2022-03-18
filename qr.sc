#!/bin/bash
ARGS=""; for a in "$@"; do ARGS="$ARGS,$(printf '%s' "$a"|base64 -w 0)"; done; ARGS="$ARGS" exec amm "$0";
!#
val args = System.getenv("ARGS").split(",").toList.drop(1).map(a => String(java.util.Base64.getDecoder().decode(a)))

import $ivy.`com.zhranklin:scala-tricks_2.13:0.2.1`
import $ivy.`com.lihaoyi:ammonite-ops_2.13:2.4.0-23-76673f7f`
import $ivy.`com.flipkart.zjsonpatch:zjsonpatch:0.4.11`
import $ivy.`com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.8.11`
import $ivy.`com.lihaoyi::os-lib:0.8.0`
import $ivy.`org.springframework:spring-core:5.1.7.RELEASE`
import $ivy.`org.fusesource.jansi:jansi:2.2.0`
import $ivy.`io.github.java-diff-utils:java-diff-utils:4.5`
import $ivy.`com.github.scopt::scopt:4.0.1`

import java.io.File
//以下仅为IDEA需要
import $ivy.`io.circe::circe-core:0.14.1`
import $ivy.`io.circe:circe-optics_2.13:0.14.1`
import $ivy.`io.circe::circe-parser:0.14.1`
import $ivy.`io.circe::circe-yaml:0.14.1`

import ammonite.ops.ShelloutException
import com.fasterxml.jackson.databind.node.{ArrayNode, JsonNodeFactory, ObjectNode, TextNode}
import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.flipkart.zjsonpatch.{DiffFlags, JsonDiff}
import com.github.difflib.text.DiffRowGenerator
import io.circe.Json
import zrkn.op._
import org.springframework.util.AntPathMatcher

import scala.jdk.CollectionConverters._

if (args(0) == "write") {
  val fileNamePrefix = args(1)
  val gzip = os.proc("gzip").spawn(stdin = os.Inherit)
  val base64 = os.proc("base64", "-w", "256").call(stdin = gzip.stdout).out.text()
  val groups = base64.split("\n").toList.filter(_.nonEmpty).grouped(10)
  groups
    .zipWithIndex
    .map {
      case (lines, i) => (s"###$i/${groups.size}\n${lines.mkString("\n")}", i)
    }
    .foreach {
      case (txt, i) => os.proc("qrencode", "-8", "-o", f"$fileNamePrefix%s$i%03d.png").call(stdin = txt, stdout = os.Inherit, stderr = os.Inherit)
    }
} else if (args(0) == "read") {
  val videoFile = args(1)
  val time = System.currentTimeMillis() / 1000
  val dir = os.root/"tmp"/s"qr-$time"
  os.makeDir(dir)
  os.proc("ffmpeg", "-i", videoFile, "-format", "image2", "-r", "10", dir/"df%03d.png").call(stdout = os.Inherit, stderr = os.Inherit)
  var totalOpt: Option[Int] = None
  val base64Parts = os.list(dir).filter(_.ext == "png")
    .flatMap { png =>
      val result = os.proc("zbarimg", png).call(stderr = os.Inherit, check = false)
      if (result.exitCode == 0) {
        val txt = result.out.text()
        import zrkn.op.RegexOpsContext._
        val lines = txt.split("\n").filter(_.nonEmpty).toList
        val i = lines.head match {
          case rr"QR-Code:i=$i(\d+),.*" => i.toInt
          case rr"QR-Code:###$i(\d+)/$t(\d+)" =>
            totalOpt = Some(t.toInt)
            i.toInt
        }
        println(s"Got text from QR Code: $i")
        Some(i, lines.tail.mkString("\n"))
      } else {
        None
      }
    }
    .toMap
  val total = totalOpt.getOrElse(base64Parts.keys.max)
  val missedParts = (0 until total).toList.diff(base64Parts.keys.toList)
  if (missedParts.nonEmpty) {
    println(s"Error: missed parts: ${missedParts.mkString(", ")}")
  } else {
    val base64 = base64Parts.values.mkString("\n")
    val decodeP = os.proc("base64", "-d").spawn(stdin = base64)
    val outF = os.Path(videoFile)/os.up/"result.file"
    os.proc("gunzip").call(stdin = decodeP.stdout, stdout = outF)
    println(s"File is output at $outF")
  }
}
