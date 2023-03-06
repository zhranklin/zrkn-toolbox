#!/usr/bin/env amm
import $ivy.`com.lihaoyi::requests:0.8.0`
import $ivy.`com.lihaoyi::upickle:3.0.0-M1`
import $ivy.`com.zhranklin:scala-tricks_2.13:0.2.1`
import $ivy.`com.lihaoyi::os-lib:0.9.0`
import $ivy.`com.lihaoyi:ammonite-ops_2.13:2.4.0`
import $ivy.`org.yaml:snakeyaml:1.17`
import $ivy.`org.jsoup:jsoup:1.15.4`
import zrkn.op._
import Pipe._
//import RegexOpsContext._
import os.proc
import mainargs.Leftover

val FLAC_FORMAT = "audio0-flac"
val FORMAT_FHD = "wv*[height=1080][fps>40] / wv*[height=1080][vcodec*=hvc]  / bv*[height=1080]"
val FORMAT_UHD = "bv"

type BaseArgs = List[String]
type VideoId = String

import java.util.regex.Pattern
def groups(str: String) = "\\(".r.findAllIn(str).size - """\(\?([idmsuxU=>!:]|<[!=])""".r.findAllIn(str).size
class Interped(sc: StringContext) {
  def unapplySeq(s: String): Option[Seq[String]] = {
    val parts = sc.parts
    val tail = parts.tail.map(s => if (s.startsWith("(")) s else "(.*)" + s)
    val pattern = Pattern.compile(parts.head + tail.mkString)
    var groupCount = groups(parts.head)
    val usedGroup = tail.map(part => {
      val ret = groupCount
      groupCount += groups(part)
      ret
    })
    val m = pattern matcher s
    if (m.matches()) {
      Some(usedGroup.map(i => m.group(i + 1)))
    } else None
  }
}
extension (sc: StringContext) def rr: Interped = new Interped(sc)

@main
def info(id: Int): Unit =
  //curl -Haccept-language:zh-CN,zh\;q=0.9 'https://api.digitalconcerthall.com/v2/concert/'$i --compressed > $i.work.json
  //println(requests.get(s"https://api.digitalconcerthall.com/v2/concert/$i", ))
  println(ujson.read(requests.get(s"https://api.digitalconcerthall.com/v2/season/$id")).obj("_links").obj("concert").arr.map(_.obj("href").str).toList.toString)
end info

@main
def download(args: Leftover[String]) = {
  cd(oPath("/Users/zhranklin/bpodl"))
//  cd(oPath("/Volumes/Fast SSD/2019-2020"))
  args.value.foreach { id =>
    try {
      id match {
        case rr"""$id-$item""" =>
          processLink(s"https://www.digitalconcerthall.com/zh/concert/$id", item)
        case id =>
          processLink(s"https://www.digitalconcerthall.com/zh/concert/$id", "")
      }
    } catch {
      case e: Exception => e.printStackTrace()
    }
  }
}

@main
def list(id: Int): Unit =
  println(ujson.read(requests.get(s"https://api.digitalconcerthall.com/v2/season/$id")).obj("_links").obj("concert").arr.map(_.obj("href").str).toList.toString)
end list

def processLink(link: String, item: String): Unit = {
  implicit val args: BaseArgs = ydlBaseArgs(link, item)
  try {
    println(s"\n\n\n\n\n\n\n\n\n############### processing: $link")
    try {
      downloadFlac
    } catch {
      case e: Exception =>
    }
    val fileNames = downloadVideo
    transformVideo(fileNames.split("\n").toList.map(_.trim))
  } catch {
    case e: Exception =>
      e.printStackTrace()
  }
}

def downloadFlac(implicit args: BaseArgs): Unit = {
  val cmd = args.head :: "-f" :: FLAC_FORMAT :: "-o" :: "%(id)s.flac" :: args.tail
  val fn = getFileName(cmd).trim
  println(s"flac file names are:\n$fn")
  os.proc(cmd).call(cwd = wd, stdin = os.Inherit, stdout = os.Inherit, stderr = os.Inherit)
}

def downloadVideo(implicit args: BaseArgs): String = {
  def downloadVideo(format: String, fnTmpl: String)(implicit args: BaseArgs): String = {
    val cmd = args.head :: "-f" :: format :: "-o" :: fnTmpl :: args.tail
    val fn = getFileName(cmd).trim
    println(s"video file names are:\n$fn")
    os.proc(cmd).call(cwd = wd, stdin = os.Inherit, stdout = os.Inherit, stderr = os.Inherit)
    fn
  }
  downloadVideo(FORMAT_FHD, "%(id)s %(fps)sfps,[FORMAT] %(playlist_title)s - %(title)s.mp4")
}

def transformVideo(files: List[String])(implicit args: BaseArgs): Unit = {
  files.foreach { src =>
    println(s"src: $src.")
    val (id, fps) = src match {
      case rr"""$id([-0-9]+) $fps([\d.]+)fps,.*\.mp4""" => (id, fps.toDouble.toInt)
    }
    val format = if (fps == 50) "[FHD,HiRes]" else s"[FHD,$fps,HiRes]"
    val target = os.read(wd/"titles.txt")
      .split("\n")
      .find(_.startsWith(s"$id "))
      .map(_.trim)
      .getOrElse(src.replaceAll(" [\\d.]+fps,", " "))
      .replace("[FORMAT]", format)
    println(s"ffmpeg transforming to: $target")
    val ffmpegResult = os.proc("ffmpeg",
      "-i", src, "-i", s"$id.flac",
      "-map", "0:0", "-map", "1:0",
      "-c:v", "copy", "-c:a", "alac",
      target
    ).call(cwd = wd)
    if (ffmpegResult.exitCode != 0) {
      println(s"ffmpeg error with code ${ffmpegResult.exitCode}:")
      println(ffmpegResult.err)
    } else {
      println("ffmpeg transformed successfully.")
      os.remove(wd/src)
    }
  }
}

def ydlBaseArgs(link: String, item: String): BaseArgs = List("yt-dlp",
  "--wait-for-video", "240",
//  "--no-continue",

  "--fragment-retries", "3", "--retries", "20",
  "--proxy", "http://127.0.0.1:58591",
  "--username", "chigou79@outlook.com", "--password", "rmhme3",
  "-N", "3",
  "--downloader", "aria2c",

) ::: Some(item).filter(_.nonEmpty).map(List("--playlist-items", _)).getOrElse(Nil) ::: List("--", link)

def getFileName(args: List[String]) = new Pipe(args.head :: "--get-filename" :: args.tail) | callText
