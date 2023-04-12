#!/usr/bin/env amm3
//Ammonite 2.5.4
//scala 3.1.0
import $ivy.`com.lihaoyi::requests:0.8.0`
import $ivy.`com.lihaoyi:upickle_2.13:2.0.0`
import $ivy.`com.zhranklin:scala-tricks_2.13:0.2.1`
import $ivy.`com.lihaoyi::os-lib:0.9.0`
import $ivy.`com.lihaoyi:ammonite-ops_2.13:2.4.0`
import $ivy.`org.yaml:snakeyaml:1.17`
import $ivy.`org.jsoup:jsoup:1.15.4`
import zrkn.op._
import Pipe._
//import RegexOpsContext._
import os.proc
import mainargs.{Leftover, arg, main}

val FLAC_FORMAT = "audio0-flac"
val FORMAT_FHD = "wv*[height=1080][fps>40] / wv*[height=1080][vcodec*=hvc]  / bv*[height=1080]"
val FORMAT_UHD = "bv"
val REQUESTS_PROXY = ("127.0.0.1", 58591)

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

def info(id: String): List[String] =
  import scala.util.Try
  extension (j: ujson.Value) def s: String = scala.util.Try(j.str).getOrElse("")
  val info = ujson.read(requests.get(s"https://api.digitalconcerthall.com/v2/concert/$id", headers = Map("accept-language" -> "zh-CN,zh;q=0.9"), proxy = REQUESTS_PROXY)).obj
  info("_embedded").obj("work").arr.map(_.obj).map { c =>
    val links = c("_links").obj
    val artists =
      Try(links("artist").arr.toList)
        .map(_.filter(_.obj("role").obj("type").s != "composer").map(a => s"${a.obj("name").s} ${a.obj("role").obj("name").s}".replaceAll(" $", "")).mkString(", "))
        .filter(_.nonEmpty).map(a => s" - $a").getOrElse("")
    s"${c("id").str} [FORMAT] ${links("concert").arr(0).obj("title").s} - ${c("name_composer").s}${c("title").s}$artists.mp4"
  }
  .toList
end info

import java.time._
case class Work(id: String, title: String, composer: String, duration: Int, artists: List[(String, Option[String])]):
  override def toString(): String = s"""----节目 ${id.replaceAll(".*-", "")} ${duration/60}:${String.format("%02d",duration%60)}----
标题:
  $title
艺术家:
  $composer 作曲
${artists.map{case (n, r) => s"$n${r.map(" "+_).getOrElse("")}"}.mkString("  ", "\n  ", "\n")}"""

case class Concert(id: String, title: String, description: String, published: ZonedDateTime, begin: ZonedDateTime, end: ZonedDateTime, works: List[Work]):
  override def toString(): String = s"""
<html lang="en">
  <head>
    <meta charset="UTF-8">
    <title>HTML Elements – Capoverso</title>
    <link crossorigin="anonymous" rel="stylesheet" id="all-css-0-1" href="https://s0.wp.com/_static/??-eJxtT0kOwjAM/BCpqVCXC+IpKEmtkJJNcdKqvydILBXh5vF4FsMamPQuoUtgMwsmK+0I1iC9ZWS1we0HNZLoADuZMF59hBNaTzoh4zLphSesN5VBuqFFgpAFSB78gpE8UNpMfbqrOGMKXN5fGEL0Uy4J3sFVOwkKHUZddPR/rKzfvioXKDCqwkSEpe2aU9OCyNpMz19LpNEi8rh9O17sue2H8TQeh76bH9EYf6o=?cssminify=yes" type="text/css" media="all">
    <style type="text/css" id="custom-background-css">
    body.custom-background { background-color: #cccccc; background-image: url("https://s0.wp.com/wp-content/themes/pub/capoverso/img/capoverso-default-background2x.png"); background-position: left top; background-size: auto; background-repeat: repeat; background-attachment: scroll; }
    body {
      background: radial-gradient(280px 280px at 450px 270px, rgba(255, 255, 255, 0.2) 0%, rgba(255, 255, 255, 0.0) 100%) no-repeat;
    }
    body, .single .entry-header .entry-meta, .contact-form label span { color: #7C7C7C;}
    .site-title a, .site-title a:visited, input[type="submit"], .comments-link a, .comments-link a:hover, .comments-link a:active, .comments-link a:focus { color: #FFFFFF;}
    .main-navigation, .single .entry-header .entry-meta, .widget-title, input[type="text"], input[type="email"], input[type="url"], input[type="password"], input[type="search"] { border-color: #393939;}
    .main-navigation, .single .entry-header .entry-meta, .widget-title, input[type="text"], input[type="email"], input[type="url"], input[type="password"], input[type="search"] { border-color: rgba( 57, 57, 57, 0.2 );}
    </style>
  </head>

  <body class="page-template-default page page-id-25 custom-background customizer-styles-applied default-custom-background highlander-enabled highlander-light demo-site">
    <div id="page" class="hfeed site">
      <header class="entry-header">
        <h1 class="entry-title">HTML Elements</h1>	</header><!-- .entry-header -->

      <div class="entry-content">
        <h3>$id $title</h3>
=====概况=====
演出时间: ${begin.format(format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))} ~ ${end.format(format.DateTimeFormatter.ofPattern("HH:mm"))}
发布时间: ${published.format(format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))}

简介:
  $description

====节目单====

${works.mkString("\n")}
<p>Below is just about every <abbr title="HyperText Markup Language">HTML</abbr> element you might want to use in your blog posts. Check the source code to see the many embedded elements within paragraphs.</p>
<hr>
<h1>标题 1</h1>
<h2>标题 2</h2>
<h3>标题 3</h3>
<h4>标题 4</h4>
<h5>标题 5</h5>
<h6>标题 6</h6>
<hr>
<p>Lorem ipsum dolor sit amet, <a title="test link" href="#">test link</a> adipiscing elit. <strong>This is strong.</strong> Nullam dignissim convallis est. Quisque aliquam. <em>This is emphasized.</em> Donec faucibus. Nunc iaculis suscipit dui. 5<sup>3</sup> = 125. Water is H<sub>2</sub>O. Nam sit amet sem. Aliquam libero nisi, imperdiet at, tincidunt nec, gravida vehicula, nisl. <cite>The New York Times</cite> (That’s a citation). <span style="text-decoration:underline;">Underline.</span> Maecenas ornare tortor. Donec sed tellus eget sapien fringilla nonummy. Mauris a ante. Suspendisse quam sem, consequat at, commodo vitae, feugiat in, nunc. Morbi imperdiet augue quis tellus. <abbr title="HyperText Markup Language">HTML</abbr> and <abbr title="Cascading Style Sheets">CSS</abbr> are our tools. Mauris a ante. Suspendisse quam sem, consequat at, commodo vitae, feugiat in, nunc. Morbi imperdiet augue quis tellus. Praesent mattis, massa quis luctus fermentum, turpis mi volutpat justo, eu volutpat enim diam eget metus. To copy a file type <code>COPY <var>filename</var></code>.</p>
<p><del>Dinner’s at 5:00.</del><ins>Let’s make that 7.</ins></p>
<p>This <span style="text-decoration:line-through;">text</span> has been struck.</p>
<hr>
<h2>List Types</h2>
        <h3>Definition List</h3>
        <dl>
          <dt>作品</dt>
          <dd>简介aaa</dd>
          <dt>时间</dt>
          <dd><em>2022.01.01 20:00 ~ 22:00</em></dd>
        </dl>
        <h3>Ordered List</h3>
        <ol>
          <li>List Item 1</li>
          <li>List Item 2
            <ol>
              <li>Nested list item A</li>
              <li>Nested list item B</li>
            </ol>
          </li>
          <li>List Item 3</li>
        </ol>
        <h3>Unordered List</h3>
        <ul>
          <li>List Item 1</li>
          <li>List Item 2
            <ul>
              <li>Nested list item A</li>
              <li>Nested list item B</li>
            </ul>
          </li>
          <li>List Item 3</li>
        </ul>
        <hr>
        <h2>Table</h2>
        <table>
          <tbody>
            <tr>
              <th>Table Header 1</th>
              <th>Table Header 2</th>
              <th>Table Header 3</th>
            </tr>
            <tr>
              <td>Division 1</td>
              <td>Division 2</td>
              <td>Division 3</td>
            </tr>
            <tr class="even">
              <td>Division 1</td>
              <td>Division 2</td>
              <td>Division 3</td>
            </tr>
            <tr>
              <td>Division 1</td>
              <td>Division 2</td>
              <td>Division 3</td>
            </tr>
          </tbody>
        </table>
        <hr>
        <h2>Preformatted Text</h2>
        <p>Typographically, preformatted text is not the same thing as code. Sometimes, a faithful execution of the text requires preformatted text that may not have anything to do with code. For&nbsp;example:</p>
        <pre>“Beware the Jabberwock, my son!
    The jaws that bite, the claws that catch!
Beware the Jubjub bird, and shun
    The frumious Bandersnatch!”</pre>
        <h3>Code</h3>
        <p>Code can be presented inline, like <code>&lt;?php bloginfo('stylesheet_url'); ?&gt;</code>, or within a <code>&lt;pre&gt;</code> block.</p>
        <pre><code>#container { float: left; margin: 0 -240px 0 0; width: 100%; }</code></pre>
        <hr>
        <h2>Blockquotes</h2>
        <p>Let’s keep it simple.</p>
        <blockquote><p>Good afternoon, gentlemen. I am a HAL 9000 computer. I became operational at the H.A.L. plant in Urbana, Illinois on the 12th of January 1992. My instructor was Mr. Langley, and he taught me to sing a song. If you’d like to hear it I can sing it for you. <cite>— <a href="http://en.wikipedia.org/wiki/HAL_9000">HAL 9000</a></cite></p></blockquote>
        <p>And here’s a bit of trailing text.</p>
      </div><!-- .entry-content -->
    </div><!-- #page -->
  </body>
</html>
"""

@main
def description(id: String): Unit = println(adescription(id))
def adescription(id: String): Concert =
  import scala.util.Try
  extension (j: ujson.Value) def s: String = scala.util.Try(j.str).getOrElse("")
  def timeFromStamp(ts: Int) = ZonedDateTime.ofInstant(Instant.ofEpochSecond(ts), ZoneId.of("UTC+1")) // 德国当地时间
  import os._
  val json = ujson.read(os.read(os.pwd / "works" / s"$id.json")).obj
  val works = json("_embedded").obj("work").arr.map(_.obj).map { c =>
    val links = c("_links").obj
    val artists = Try(links("artist").arr.toList).getOrElse(Nil)
      .filter(_.obj("role").obj("type").s != "composer")
      .map(a => a.obj("name").s -> Some(a.obj("role").obj("name").s).filter(_.trim.nonEmpty))
    Work(c("id").str, c("title").s, c("name_composer").s, c("duration").num.toInt, artists)
  }
  .toList
  val d = json("date").obj
  Concert(json("id").str, json("metadata").obj("title").str, json("metadata").obj("description").str, timeFromStamp(d("published").num.toInt), timeFromStamp(d("begin").num.toInt),  timeFromStamp(d("end").num.toInt), works)
end adescription

@main
def download(args: Leftover[String]) = {
  val audioOnly: mainargs.Flag = mainargs.Flag(System.getenv().getOrDefault("AO", "false").toBoolean)
  val path = System.getenv("HOME") + "/bpodl"
  println(audioOnly)
  println(path)
  cd(oPath(path))
  args.value.foreach { id =>
    try {
      id match {
        case rr"""$id-$item""" =>
          processLink(s"https://www.digitalconcerthall.com/zh/concert/$id", item, audioOnly.value)
        case id =>
          processLink(s"https://www.digitalconcerthall.com/zh/concert/$id", "", audioOnly.value)
      }
    } catch {
      case e: Exception => e.printStackTrace()
    }
  }
}

@main
def list(season: Int, @arg(short = 'v') verbose: mainargs.Flag): Unit =
  if (verbose.value) {
    println(doListVerbose(season).mkString("\n"))
  } else {
    println(doList(season).mkString("\n"))
  }
end list

def doListVerbose(season: Int) = doList(season).flatMap(info)
def doList(season: Int) =
  ujson.read(requests.get(s"https://api.digitalconcerthall.com/v2/season/$season", proxy = REQUESTS_PROXY)).obj("_links").obj("concert").arr.map(_.obj("href").str).toList.map(_.replaceAll(".*/", ""))

def processLink(link: String, item: String, audioOnly: Boolean): Unit = {
  implicit val args: BaseArgs = ydlBaseArgs(link, item)
  try {
    println(s"\n\n\n\n\n\n\n\n\n############### processing: $link")
    try {
      downloadFlac
    } catch {
      case e: Exception =>
    }
    if (!audioOnly) {
      val fileNames = downloadVideo
      transformVideo(fileNames.split("\n").toList.map(_.trim))
    }
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
    val targetRaw = os.read(wd/"titles.txt")
      .split("\n")
      .find(_.startsWith(s"$id "))
      .map(_.trim)
      .getOrElse(src.replaceAll(" [\\d.]+fps,", " "))
      .replace("[FORMAT]", format)
    val target =
      if (targetRaw.length > 200)
        "RN"+targetRaw.replaceAll("\\.mp4$", "").take(200)+".mp4"
      else targetRaw
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
  "-N", "10",
  "--downloader", "aria2c",

) ::: Some(item).filter(_.nonEmpty).map(List("--playlist-items", _)).getOrElse(Nil) ::: List("--", link)

def getFileName(args: List[String]) = new Pipe(args.head :: "--get-filename" :: args.tail) | callText
