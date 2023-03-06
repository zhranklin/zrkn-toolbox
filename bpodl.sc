//#!/usr/bin/env amm
import $ivy.`com.zhranklin::scala-tricks:0.2.1`
import $ivy.`com.lihaoyi::os-lib:0.8.0`
import $ivy.`com.lihaoyi::geny:0.6.2`
import $ivy.`com.lihaoyi::ammonite-ops:2.2.0`
import $ivy.`org.yaml:snakeyaml:1.17`
import zrkn.op._
import Pipe._
import RegexOpsContext._
import os.proc

val FLAC_FORMAT = "audio0-flac"
val FORMAT_FHD = "wv*[height=1080][fps>40] / wv*[height=1080][vcodec*=hvc]  / bv*[height=1080]"
val FORMAT_UHD = "bv"

type BaseArgs = List[String]
type VideoId = String

@main
def main(args: String*) = {
//  cd(oPath("/Users/zhranklin/bpodl"))
//  cd(oPath("/Volumes/Fast SSD/2019-2020"))
  args.foreach { id =>
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
