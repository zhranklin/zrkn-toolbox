#!/usr/local/bin/amm3
//Ammonite 2.5.4
//scala 3.1.2
import $ivy.`io.circe:circe-core_2.13:0.13.0`
import $ivy.`io.circe:circe-parser_2.13:0.13.0`
import $ivy.`io.circe:circe-generic_2.13:0.13.0`
import $ivy.`io.circe:circe-optics_2.13:0.13.0`
import $ivy.`org.springframework:spring-web:5.2.6.RELEASE`
import $ivy.`org.springframework:spring-core:5.2.6.RELEASE`
import $ivy.`com.lihaoyi:requests_2.13:0.8.0`


import org.springframework.http.MediaType

import java.net.URI
import org.springframework.http.{HttpMethod, RequestEntity}
import org.springframework.web.client.RestTemplate
import io.circe._
import io.circe.syntax._
import org.springframework.util.{LinkedMultiValueMap, MultiValueMap}
import org.springframework.http.HttpHeaders

import java.text.SimpleDateFormat
import java.time.format.DateTimeFormatter
import java.time._
import java.util.TimeZone
import scala.util.{Failure, Success, Try}

class Interped(sc: StringContext) {
  import java.util.regex.Pattern
  private def groups(str: String) = "\\(".r.findAllIn(str).size - """\(\?([idmsuxU=>!:]|<[!=])""".r.findAllIn(str).size
  def unapplySeq(s: CharSequence): Option[Seq[String]] = {
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
    }
    else None
  }
}
extension (sc: StringContext)
def rr: Interped = new Interped(sc)

val tokenFile = os.pwd / ("toodledo-token.json")
val clientId = "zhranklin"
val secret = "api5f631a741453a"
val client = new RestTemplate
val URL_API = "https://api.toodledo.com/3"
val URL_ACCOUNT = s"$URL_API/account"
val URL_TASKS = s"$URL_API/tasks"
type MM = MultiValueMap[String, String]

def trim(str: String, length: Int) = if (str.length <= length) str else str.take(length - 3) + "..."
abstract class ApiRequest[T](val uri: String, val httpMethod: HttpMethod, val withAuth: Boolean) {
  def post(url: String, body: String) = requests.post(url = url, headers = baseHeaders, data = body).text
  def get(url: String) = requests.get(url = url, headers = baseHeaders).text
  def multiMap(kvs: (String, String)*): MM = {
    val result = new LinkedMultiValueMap[String, String]
    kvs.foreach(kv => result.add(kv._1, kv._2))
    result
  }
  protected def builder =
    RequestEntity.method(httpMethod, URI.create(uri))
      .headers { (h: HttpHeaders) =>
        h.setContentType(MediaType.APPLICATION_FORM_URLENCODED)
        if (withAuth) h.setBasicAuth(clientId, secret);
      }
  protected def request: RequestEntity[T]
  protected def req: String = ""
  def execute: String = {
    val res = req
    postExec(res)
    res
  }
  def json: Json = io.circe.parser.parse(execute).getOrElse(Json.Null)
  def postExec(resp: String): Unit = {}
}

case class GetToken(code: String) extends ApiRequest[String](uri = s"$URL_ACCOUNT/token.php", HttpMethod.POST, true) {
  def request: RequestEntity[String] = builder
    .body(s"grant_type=authorization_code&code=$code&vers=3&os=7")
  override def postExec(resp: String): Unit = {
    println(s"fetch token: $resp")
    os.write.over(tokenFile, io.circe.parser.parse(resp).getOrElse(Json.Null).asObject.get.+:("time" := ""+System.currentTimeMillis()).asJson.toString())
  }
}

case class RefreshToken(refreshToken: String) extends ApiRequest[String](uri = s"$URL_ACCOUNT/token.php", HttpMethod.POST, true) {
  def request: RequestEntity[String] = builder
    .body(s"grant_type=refresh_token&refresh_token=$refreshToken&vers=3&os=7")
  override def postExec(resp: String): Unit = {
    println(s"refresh token: $resp")
    os.write.over(tokenFile, io.circe.parser.parse(resp).getOrElse(Json.Null).asObject.get.+:("time" := ""+System.currentTimeMillis()).asJson.toString())
  }
}


def genBody(data: (String, String)*) = data
  .toMap
  .mapValues(v => java.net.URLEncoder.encode(v, java.nio.charset.StandardCharsets.UTF_8.toString))
  .map { case (k, v) => s"$k=$v" }
  .mkString("&")

def baseHeaders = Map("content-type" -> MediaType.APPLICATION_FORM_URLENCODED_VALUE)

case class AddTasks(tasks: List[Map[String, Json]], token: String) extends ApiRequest[MM](s"$URL_TASKS/add.php", HttpMethod.POST, false) {
  protected def request: RequestEntity[MM] = null
  override def postExec(resp: String): Unit = println(trim(resp, 150))
  override def req: String = post(url = s"$URL_TASKS/add.php", body = genBody(
    "access_token" -> token,
    "tasks" -> tasks.asJson.printWith(io.circe.Printer.spaces2),
    "fields" -> "context,status,priority,tag"
  ))
}

case class EditTasks(tasks: List[Map[String, Json]], token: String) extends ApiRequest[MM](s"$URL_TASKS/edit.php", HttpMethod.POST, false) {
  def request: RequestEntity[MM] = builder
    .body(multiMap(
      "access_token" -> token,
      "tasks" -> tasks.asJson.printWith(io.circe.Printer.spaces2),
      "fields" -> "duedate,duedatemod")
    )
  override def postExec(resp: String): Unit = println(trim(resp, 150))
}

case class GetTasks(comp: Int, token: String) extends ApiRequest[Void](s"$URL_TASKS/get.php?fields=duedate,duedatemod&comp=$comp&access_token=$token", HttpMethod.GET, false) {
  def request: RequestEntity[Void] = builder.build()
  override def postExec(resp: String): Unit = println(trim(resp, 150))
}

def getToken(code: String): String = {
  val token = Try {
    val token :: time :: refreshToken :: Nil = {
      val json = os.read(tokenFile)
      List("access_token", "time", "refresh_token").map(key =>
        io.circe.parser.parse(json).toOption.flatMap(_.\\(key).headOption).flatMap(_.asString).get
      )
    }
    if (System.currentTimeMillis() <= time.toLong + 3600 * 1000) token
    else {
      val json = RefreshToken(refreshToken).json
      json.\\("access_token").head.asString.get
    }
  } match {
    case Success(token) => token
    case Failure(e) =>
      println(e.getMessage)
      println("fetching new token by code")
      GetToken(code).json.\\("access_token").head.asString.get
  }
  token
}
@main
def main(code: String = "UNKNOWN"): Unit = {
  val str = os.read(os.root/"dev"/"stdin")
  val getId: String => Option[String] = {
    //case rr"$id((Task|Bug|Requirement|Objective|Subtask)-\d+) .*" => Some(id)
    case _ => None
  }
  println(s"How to get code:\n$URL_ACCOUNT/authorize.php?response_type=code&client_id=zhranklin&state=YourState&scope=basic%20tasks%20write")
  val token: String = getToken(code)
  val currentTasksResp = GetTasks(0, token).json
  val currentTasks = currentTasksResp.asArray.get.flatMap(_.asObject)
    .map(task => (task("title").flatMap(_.asString).flatMap(getId), task))
    .flatMap(tuple => tuple._1.map(t => (t, tuple._2)))
    .toMap
  //  println(currentTasks)
  val taskIds = currentTasksResp.\\("title").flatMap(_.asString).flatMap(getId).toSet

  import io.circe.optics.JsonPath
  println("Tasks parsed:")
  val tasks = JsonPath.root.result.arr.getOption(io.circe.parser.parse(str).getOrElse(Json.Null)).get
    .filter(entry => JsonPath.root.receipt.state.long.getOption(entry).get != 3)
    .map { entry =>
      import JsonPath._
      val tpe = entry.asObject.get.keys.filter(Set("task", "bug", "requirement", "objective").contains).head
      val typeUpper = s"${tpe.head.toUpper}${tpe.tail}"
      val id = root.selectDynamic(tpe).id.number.getOption(entry).get
      //转换成标准时区的中午12点(即东八区的20点)
      def processDuedate(t: Long) = {
        val zone = ZoneId.of("UTC+8")
        Instant.ofEpochMilli(t).atZone(zone).toLocalDate.atTime(20, 0).atZone(zone).toEpochSecond
      }
      val time = root.receipt.expectReleaseTime.long.getOption(entry)
        .filter(_ != 0)
        .map(t => Map("duedate" := processDuedate(t), "duedatemod" := 0))
        .getOrElse(Map())

      val res = Map(
        "title" := s"$typeUpper-$id ${root.receipt.name.string.getOption(entry).get}",
        "context" := 856389,
        "status" := Status.Planning,
        "priority" := 1,
        "tag" := "jira"
      ) ++ time
      println(res)
      res
    }
    .toList
  val tasksToAdd = tasks.filterNot(json => taskIds.contains(json("title").asString.flatMap(getId).get))
  println("##################\ntasks to add:")
  tasksToAdd.foreach(println)
  println("##################\ntasks remain in toodledo: " + taskIds.filterNot(id => tasks.map(_.apply("title").asString.flatMap(getId).get).contains(id)))

  println("##################\ntasks to update due date:")
  def stamp(t: Long) = if (t == 0) "" else Instant.ofEpochSecond(t).atZone(ZoneOffset.ofHours(8)).format(DateTimeFormatter.ofPattern("yy-MM-dd HH:mm:ss"))
  val tasksToEdit = currentTasks
    .flatMap {
      case (epId, currentTask) =>
        val newTask = tasks.find(t => t.get("title").flatMap(_.asString).flatMap(getId).contains(epId))
        val newDuedate = newTask.flatMap(_.get("duedate").flatMap(_.asNumber)).flatMap(_.toInt).getOrElse(0)
        val currentDuedate = currentTask("duedate").flatMap(_.asNumber).flatMap(_.toInt).getOrElse(0)
        val id = currentTask("id").flatMap(_.asNumber).flatMap(_.toLong).get
        (currentDuedate, newDuedate) match {
          case (cd, nd) if cd != nd =>
            import java.util.Date
            println(s"${stamp(cd)} -> ${stamp(nd)}/${currentTask("title").flatMap(_.asString).getOrElse("")}")
            Some(Map(
              "id" := id,
              "duedatemod" := 0,
              "duedate" := nd
            ))
          case (cd, nd) if cd == nd => None
        }
    }
    .toList
  if (tasksToEdit.nonEmpty) EditTasks(tasksToEdit, token).json

  if (tasksToAdd.nonEmpty) AddTasks(tasksToAdd, token).json
}

object Status {
  val None       = 0
  val NextAction = 1
  val Active     = 2
  val Planning   = 3
  val Delegated  = 4
  val Waiting    = 5
  val Hold       = 6
  val Postponed  = 7
  val Someday    = 8
  val Canceled   = 9
  val Reference  = 10
}
