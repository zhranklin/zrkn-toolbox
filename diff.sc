#!/bin/bash
ARGS=""; for a in "$@"; do ARGS="$ARGS,$(printf '%s' "$a"|base64 -w 0)"; done; ARGS="$ARGS" exec amm3 "$0";
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
import scala.collection.mutable
//以下仅为IDEA需要
import $ivy.`io.circe::circe-core:0.14.1`
import $ivy.`io.circe:circe-optics_2.13:0.14.1`
import $ivy.`io.circe::circe-parser:0.14.1`
import $ivy.`io.circe::circe-yaml:0.14.1`

import ammonite.ops.ShelloutException
import com.fasterxml.jackson.databind.node.{ArrayNode, JsonNodeFactory, ObjectNode, TextNode, MissingNode}
import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.flipkart.zjsonpatch.{DiffFlags, JsonDiff}
import com.github.difflib.text.DiffRowGenerator
import io.circe.Json
import zrkn.op._
import org.springframework.util.AntPathMatcher
import com.fasterxml.jackson.databind.node.NullNode
import java.util.regex.Matcher
import scala.language.dynamics

import scala.jdk.CollectionConverters._
//interp.preConfigureCompiler(ctx => ctx.setSetting(ctx.settings.language, List("experimental.fewerBraces")))

object c:
  extension (s: String)
    def unescapePath: String = s.replaceAll("~0", "~").replaceAll("~1", "/")

  import org.fusesource.jansi.Ansi.{Color, ansi}
  import org.fusesource.jansi.Ansi.Attribute._
  val MARK_DELETE_LINE = "<DELETE_LINE>"
  val MARK_MODIFY_LINE = "<MODIFY_LINE>"
  val MARK_ADD_LINE = "<INSERT_LINE>"
  val TAG_DELETE = "<DELETE>"
  val TAG_ADD = "<ADD>"
  val TAG_END = "<RESET>"
  val MARK_DELETE_FIELD = "<DELETE_FIELD>"
  val MARK_ADD_FIELD = "<ADD_FIELD>"
  val DELETE = ansi().fg(Color.RED).a(STRIKETHROUGH_ON).a(INTENSITY_BOLD).toString
  val ADD = ansi().fg(Color.GREEN).a(INTENSITY_BOLD).toString
  val RESET = ansi().reset().toString
  val MINUS = ansi().render("@|red -|@").toString
  val PLUS = ansi().render("@|green +|@").toString
  val TILDE = ansi().render("@|yellow ~|@").toString
import c.unescapePath

val jsonMapper = new ObjectMapper()
val pathMatcher = new AntPathMatcher()
val diffRowGenerator = DiffRowGenerator.create()
  .showInlineDiffs(true)
  .inlineDiffByWord(true)
  .oldTag(f => if (f) c.TAG_DELETE else c.TAG_END)
  .newTag(f => if (f) c.TAG_ADD else c.TAG_END)
  .mergeOriginalRevised(true)
  .lineNormalizer(identity)
  .build()

def getValue(obj: JsonNode, path: String): Option[JsonNode] =
  var cur = obj
  path.split("/").dropRight(1).drop(1)
    .foreach { pp =>
      val p = pp.replaceAll("~1", "/")
      cur match
        case c: ArrayNode if p.toIntOption.nonEmpty =>
          cur = c.get(p.toInt)
        case c: ObjectNode =>
          cur = c.get(p)
        case _ =>
          return None
    }
  val last = path.split("/").last
  Option(cur).flatMap(c => Option(c.get(last)))
end getValue

trait ValueMatcher:
  val newNode = JsonNodeFactory.instance.pojoNode _
  def matches(node: JsonNode, basePath: String, value: JsonNode): Boolean
  def jsonEquals(v1: JsonNode, v2: JsonNode) =
    jsonMapper.readTree(v1.toString) == (jsonMapper.readTree(v2.toString))

def exact(v: Any) = new ValueMatcher:
  val expect = newNode(v)
  def matches(node: JsonNode, basePath: String, value: JsonNode) = jsonEquals(expect, value)

def ref(path: String) = new ValueMatcher:
  def matches(node: JsonNode, basePath: String, value: JsonNode) =
    var p = path
    if !p.startsWith("./") && !p.startsWith("/") then
      p = "./" + p
    p = p
      .replaceAll("^./", basePath + "/")
      .replaceAll("[^/]+/\\.\\./", "")
    getValue(node, p).exists(jsonEquals(_, value))
  end matches

object alwaysTrue extends ValueMatcher:
  def matches(node: JsonNode, basePath: String, value: JsonNode) = true

val ignoredAnnotations = Set(
  "deployment.kubernetes.io/revision",
  "kubectl.kubernetes.io/last-applied-configuration",
  "autoscaling.alpha.kubernetes.io/conditions",
  "autoscaling.alpha.kubernetes.io/current-metrics",
)

val blackList = List(
  "/spec/template/metadata/labels/release",
  "/spec/selector/matchLabels/release",
  "/**/checksum~1config-volume*",
  "/metadata/labels/release",
  //"/metadata/labels/*istio.io*",
  "/metadata/managedFields",
  "/metadata/finalizers",
  "/metadata/creationTimestamp",
  "/metadata/generation",
  "/metadata/resourceVersion",
  "/metadata/selfLink",
  "/metadata/uid",
  "/spec/template/metadata/creationTimestamp",
  "/spec/template/metadata/generation",
  "/spec/template/metadata/resourceVersion",
  "/spec/template/metadata/selfLink",
  "/spec/template/metadata/uid",
  "/apiVersion",
  "/status",
  "/webhooks/*/clientConfig/caBundle",
)

val defaults = List(
  "Deployment" -> "/spec/progressDeadlineSeconds" -> exact(600),
  "Deployment" -> "/spec/revisionHistoryLimit" -> exact(10),
  "Deployment" -> "/spec/strategy/type" -> exact("RollingUpdate"),
  "Deployment" -> "/spec/template/spec/serviceAccount" -> ref("../serviceAccountName"),
  "Deployment" -> "/spec/template/spec/containers/*/imagePullPolicy" -> exact("IfNotPresent"),
  "Deployment" -> "/spec/template/spec/containers/*/ports/*/protocol" -> exact("TCP"),
  "Deployment" -> "/spec/template/spec/containers/*/*Probe/failureThreshold" -> exact(3),
  "Deployment" -> "/spec/template/spec/containers/*/*Probe/periodSeconds" -> exact(3),
  "Deployment" -> "/spec/template/spec/containers/*/*Probe/timeoutSeconds" -> exact(3),
  "Deployment" -> "/spec/template/spec/containers/*/*Probe/http*/scheme" -> exact("HTTP"),
  "Deployment" -> "/spec/template/spec/containers/*/*/successThreshold" -> exact(1),
  "Deployment" -> "/spec/template/spec/containers/*/terminationMessagePath" -> exact("/dev/termination-log"),
  "Deployment" -> "/spec/template/spec/containers/*/terminationMessagePolicy" -> exact("File"),
  "Deployment" -> "/spec/template/spec/dnsPolicy" -> exact("ClusterFirst"),
  "Deployment" -> "/spec/template/spec/restartPolicy" -> exact("Always"),
  "Deployment" -> "/spec/template/spec/schedulerName" -> exact("default-scheduler"),
  "Deployment" -> "/spec/template/spec/securityContext" -> exact(new java.util.HashMap()),
  "Deployment" -> "/spec/template/spec/terminationGracePeriodSeconds" -> exact(30),
  "Deployment" -> "/spec/template/spec/volumes/*/*/defaultMode" -> exact(420),
  "Deployment" -> "/metadata/annotations/deployment.kubernetes.io~1revision" -> alwaysTrue,
  "Service" -> "/spec/clusterIP" -> alwaysTrue,
  "Service" -> "/spec/ports/*/protocol" -> exact("TCP"),
  "Service" -> "/spec/sessionAffinity" -> exact("None"),
  "Service" -> "/spec/type" -> exact("ClusterIP"),
  "Service" -> "/spec/ports/*/targetPort" -> ref("../port"),
  "ServiceAccount" -> "/secrets" -> alwaysTrue,
  "MutatingWebhookConfiguration" -> "/webhooks/*/clientConfig/service/port" -> exact(443),
  "MutatingWebhookConfiguration" -> "/webhooks/*/matchPolicy" -> exact("Exact"),
  "MutatingWebhookConfiguration" -> "/webhooks/*/objectSelector" -> exact(new java.util.ArrayList),
  "MutatingWebhookConfiguration" -> "/webhooks/*/reinvocationPolicy" -> exact("Never"),
  "MutatingWebhookConfiguration" -> "/webhooks/*/rules/*/scope" -> exact("*"),
  "MutatingWebhookConfiguration" -> "/webhooks/*/timeoutSeconds" -> exact(30),
)

case class DocID(id: String, `tpe`: String = "Yaml Doc"):
  override def toString: String = s"$tpe $id"
class GVK(val kind: String, val name: String, val namespace: String) extends DocID(s"$kind/$name${if(namespace.isEmpty)""else s".$namespace"}", "K8s Resource")
case class YamlDoc(yaml: String, tree: JsonNode, obj: Json, id: DocID)

trait Changes:
  def sorting: String

trait SourceDatabase:
  def get(id: DocID): Option[YamlDoc]

case class NewResource(doc: YamlDoc, show: Boolean) extends Changes:
  import c._, doc.id
  override def toString = s"---\n$ADD# New $id${if (show) s"\n${doc.yaml}" else ""}$RESET"
  override def sorting = s"3$id"
case class RemovedResource(doc: YamlDoc, show: Boolean) extends Changes:
  import c._, doc.id
  override def toString = s"---\n$DELETE# Removed $id${if (show) s"\n${doc.yaml}" else ""}$RESET"
  override def sorting = s"2$id"
case class DiffResource(id: DocID, changes: ObjectNode) extends Changes:
  import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator.Feature._
  override def toString =
    val factory = new YAMLFactory()
      .disable(WRITE_DOC_START_MARKER)
      .enable(MINIMIZE_QUOTES)
    s"---\n# Diffs in $id\n${new ObjectMapper(factory).writeValueAsString(changes)}"
  end toString
  override def sorting = s"1$id"
object YamlDoc:
  def k8sResource(yaml: String) = apply(yaml, true, None)
  def apply(yaml: String, k8s: Boolean, index: Option[Int]): Option[YamlDoc] =
    if (yaml.trim.isEmpty) return None
    import scala.util
    util.Try {
      val tree = new ObjectMapper(new YAMLFactory).readTree(yaml)
      Option(tree.get("metadata")).flatMap(i => Option(i.get("annotations"))).filter(_.isInstanceOf[ObjectNode]).foreach { i =>
        ignoredAnnotations.foreach(i.asInstanceOf[ObjectNode].remove)
      }
      treeizeNodes(tree)
      val obj = io.circe.yaml.parser.parse(new ObjectMapper(new YAMLFactory).writeValueAsString(tree)) match {
        case Left(value) =>
          throw value.underlying
        case Right(value) =>
          value
      }
      val id = if (k8s) {
        import io.circe.optics.JsonPath.root
        val name = root.metadata.name.string.getOption(obj).get
        val namespace = root.metadata.namespace.string.getOption(obj).getOrElse("")
        val kind = root.kind.string.getOption(obj).get
        new GVK(kind, name, namespace)
      } else DocID(index.get.toString)
      YamlDoc(yaml, tree, obj, id)
    } .recoverWith[YamlDoc] { t =>
        io.circe.yaml.parser.parse(yaml) match
          case Right(Json.False) =>
          case _ =>
            println(yaml)
            t.printStackTrace();
        util.Failure(t)
      }.toOption
//      .filterNot(_.isInstanceOf[NullNode])
      .filterNot(_.tree.isInstanceOf[MissingNode])
  end apply

object FromK8s extends SourceDatabase:
  def get(id: DocID): Option[YamlDoc] = id match {
    case gvk: GVK => get(gvk)
    case _ => None
  }
  def get(gvk: GVK): Option[YamlDoc] =
    import gvk._
    try
      YamlDoc.k8sResource(ammonite.ops.%%("bash", "-c", s"kubectl get $kind -oyaml $name ${if (namespace.isEmpty) "" else s"-n $namespace"}")(wd).out.string)
    catch
      case e: ShelloutException if e.result.err.string.contains("(NotFound)")
        || e.result.err.string.contains("doesn't have a resource type") => None
  end get

class FromYaml(src: String, isK8s: Boolean) extends SourceDatabase:
  lazy val sourceObjs = src.split("(\n|^)---\\s*(\n|$)")
    .zipWithIndex
    .flatMap{ case (y, i) => YamlDoc.apply(y, isK8s, Some(i))}
    .groupBy(_.id)
    .view
    .mapValues(_.head)
  def get(id: DocID) = sourceObjs.get(id)

def treeizeNodes(node: JsonNode): Unit =
  node match
    case node: ObjectNode =>
      node.fields().asScala
        .flatMap { kv =>
          kv.getValue match
            case v: TextNode =>
              io.circe.yaml.parser.parse(v.textValue()).toOption
                .filter(_.isObject)
                .map { _ =>
                  (kv.getKey, new ObjectMapper(new YAMLFactory).readTree(v.textValue()))
                }
            case v: ObjectNode =>
              treeizeNodes(v)
              None
            case _ => None
        }
        .foreach { case (k, v) =>
          node.put(k, v)
        }
    case _ =>
end treeizeNodes

import ammonite.ops._
import DiffFlags._
val DIFF_FLAGS = java.util.EnumSet.of(OMIT_MOVE_OPERATION, OMIT_COPY_OPERATION, ADD_ORIGINAL_VALUE_ON_REPLACE)
def generateDiffText(from: JsonNode, to: JsonNode) =
  val splitNode = (_: JsonNode) match
    case n: TextNode => n.textValue().split("\n").toList.asJava
    case n => n.toString.split("\n").toList.asJava
  diffRowGenerator.generateDiffRows(splitNode(from), splitNode(to)).asScala
    .flatMap { l =>
      import c._, l._
      import com.github.difflib.text.DiffRow.Tag
      val mode = getTag match
        case Tag.INSERT => MARK_ADD_LINE
        case Tag.DELETE => MARK_DELETE_LINE
        case Tag.CHANGE => MARK_MODIFY_LINE
        case Tag.EQUAL => ""
      List(s"$mode$getOldLine")
    }
    .mkString("\n")
end generateDiffText

def shouldIgnore(target: YamlDoc, path: String, value: JsonNode): Boolean = {
  if (!target.id.isInstanceOf[GVK])
    false
  else {
    val ignored = path == "/metadata/annotations" && value.isInstanceOf[ObjectNode] &&
      value.asInstanceOf[ObjectNode].fieldNames().asScala.toList.forall(ignoredAnnotations.contains)
    ignored || matchDefault(target.id.asInstanceOf[GVK], path, value, target.tree)
  }
}
def doDiff(config: Args): Unit =
  val targetDocs = new FromYaml(read(config.target), config.isK8s).sourceObjs.values
//  println(targetDocs.toList)
  targetDocs
    .flatMap { target =>
      config.source.get(target.id) match
        case None => Some(NewResource(target, config.showNew))
        case Some(source) =>
          val result = JsonNodeFactory.instance.objectNode()
          JsonDiff.asJson(source.tree, target.tree, DIFF_FLAGS)
            .asInstanceOf[ArrayNode].elements().asScala.toList
            .foreach { n =>
              import c._
              val path = n.get("path").asText()
              val value = n.get("value")
              import com.fasterxml.jackson.databind.node.JsonNodeType._
              n.get("op").asText() match
                case "remove" =>
                  if (shouldIgnore(target, path, value))
                    setValue(result, s"$path$MARK_DELETE_FIELD", value)
                case "add" =>
                  setValue(result, s"$path$MARK_ADD_FIELD", value)
                case "replace" => (n.get("fromValue"), value) match
                  case (from: TextNode, to: TextNode) if List(from, to).exists(_.textValue().contains("\n")) =>
                    val diffText = generateDiffText(from, to)
                    //                    println(diffText.split("\n").map("###" + _).mkString("\n"))
                    val processed = stripMultiLineDiff(processCrossLineDiff(diffText))
                    //                    println(processed.split("\n").map("$$$" + _).mkString("\n"))
                    setValue(result, path, JsonNodeFactory.instance.textNode(processed))
                  case (from, to)
                    if from.getNodeType == to.getNodeType && !Set(ARRAY, OBJECT, POJO).contains(from.getNodeType) =>
                    val rawdiff = generateDiffText(from, to)
                    val o = rawdiff.replaceAll(s"^$MARK_DELETE_LINE|^$MARK_ADD_LINE|^$MARK_MODIFY_LINE", "")
                    val mode = rawdiff.replaceAll(s"(^$MARK_DELETE_LINE|^$MARK_ADD_LINE|^$MARK_MODIFY_LINE|).*", "$1")
                    val dir = path.split("/").dropRight(1).mkString("/")
                    val key = path.split("/").last
                    setValue(result, s"$dir/$mode$key", JsonNodeFactory.instance.textNode(o))
                  case (from, to) =>
                    setValue(result, s"$path$MARK_DELETE_FIELD", from)
                    setValue(result, s"$path$MARK_ADD_FIELD", to)
            }
          Some(result).filterNot(_.isEmpty()).map(DiffResource(target.id, _))
    }
    .toList
    .++ {
      config.source match {
        case ydb: FromYaml =>
          ydb.sourceObjs
            .keySet.toSet
            .diff(targetDocs.map(_.id).toSet)
            .map(ydb.sourceObjs.apply)
            .map(doc => RemovedResource(doc, config.showRemoved))
        case _ => Nil
      }
    }
    .sortBy(_.sorting)
    .foreach { res =>
      import c._
      var indent = 0
      var lineTemplate = " $1$2"
      res.toString.split("\n").foreach { line =>
        val ind = line.replaceAll("^( *(- )?).*", "$1").length
        if (line.contains(s"$MARK_DELETE_FIELD:"))
          indent = ind
          lineTemplate = s"$MINUS$$1$DELETE$$2$RESET"
        else if (line.contains(s"$MARK_ADD_FIELD:"))
          indent = ind
          lineTemplate = s"$PLUS$$1$ADD$$2$RESET"
        else if (ind <= indent && line.trim.nonEmpty)
          indent = 0
          lineTemplate = " $1$2"
        if (config.debug.beforeRender)
          println(line + "<before render>")
        println {
          if (line.isEmpty) ""
          else if (lineTemplate == " $1$2") line
            .replaceAll(s"^.(\\s*)$MARK_DELETE_LINE(.*)", s"$MINUS$$1$$2")
            .replaceAll(s"^.(\\s*)$MARK_ADD_LINE(.*)", s"$PLUS$$1$$2")
            .replaceAll(s"^.(\\s*)$MARK_MODIFY_LINE(.*)", s"$TILDE$$1$$2")
            .replaceAll(TAG_DELETE, DELETE)
            .replaceAll(TAG_ADD, ADD)
            .replaceAll(TAG_END, RESET)
          else line
            .replaceAll(s"($MARK_DELETE_FIELD|$MARK_ADD_FIELD):", ":")
            .replaceAll(".( *)(.*)", lineTemplate)
        }
      }
      print(RESET)
    }
end doDiff

def setValue(obj: ObjectNode, path: String, value: JsonNode): Unit =
  if (pathMatches(blackList, path)) return
  var cur = obj
  path.split("/").dropRight(1).drop(1)
    .foreach{ pp =>
      val p = pp.unescapePath
      if (cur.get(p) == null)
        cur.put(p, JsonNodeFactory.instance.objectNode())
      cur = cur.get(p).asInstanceOf[ObjectNode]
    }
  cur.put(path.split("/").last.unescapePath, value)
end setValue

def pathMatches(patterns: List[String], path: String): Boolean =
  import c._
  val curPath = path.replaceAll(s"$MARK_DELETE_FIELD$$|$MARK_ADD_FIELD$$", "")
    .replaceAll(s"/$MARK_DELETE_LINE|/$MARK_ADD_LINE|/$MARK_MODIFY_LINE", "/")
  val ret = patterns.exists(pt => pathMatcher.`match`(pt, curPath))
  ret
end pathMatches

def matchDefault(gvk: GVK, path: String, value: JsonNode, node: JsonNode): Boolean =
  defaults.exists { case kind -> pt -> expect =>
    (kind == "*" || kind == gvk.kind) &&
      pathMatcher.`match`(pt, path) &&
      expect.matches(node, path, value)
  }
end matchDefault

val txt = """###<MODIFY_LINE>    nsf.skiff.netease.com/namespace: {{ .DeploymentMeta.Namespace<ADD> <RESET>}}
###    {{- end }}
###<MODIFY_LINE>    <DELETE>prometheus<RESET><ADD>feature<RESET>.<DELETE>io/stats-job-name: "istio-mesh"
###<MODIFY_LINE>    security.<RESET>istio.io/<DELETE>tlsMode: {{ index .ObjectMeta.Labels `security.istio.io/tlsMode` | default "istio"  | quote }}
###<MODIFY_LINE>    service.istio.io/canonical<RESET><ADD>detailed<RESET>-<DELETE>name:<RESET><ADD>stats:<RESET> <DELETE>{{ index .ObjectMeta.Labels `service.istio.io/canonical-name` | default (index .ObjectMeta.Labels `app.kubernetes.io/name`) | default (index .ObjectMeta.Labels `app`) | default .DeploymentMeta.Name  | quote }}<RESET><ADD>"enabled"<RESET>
###<MODIFY_LINE>    <DELETE>service.istio.io/canonical-revision: <RESET>{{<DELETE> index .ObjectMeta.Labels `service.istio.io/canonical<RESET>-<DELETE>revision`<RESET> <DELETE>|<RESET><ADD>end<RESET> <DELETE>default (index .ObjectMeta.Labels `app.kubernetes.io/version`) | default (index .ObjectMeta.Labels `version`) | default "latest"  | quote <RESET>}}
"""

val MARKER_PT = java.util.regex.Pattern.compile(s"(?s)(${c.TAG_ADD}|${c.TAG_DELETE}).*?${c.TAG_END}")
// e.g.
// Change:
// <MODIFY_LINE>    ...<DELETE>io/stats-job-name: "istio-mesh"
// <MODIFY_LINE>    security.<RESET>istio.io/....
// To:
// <MODIFY_LINE>    ...<DELETE>io/stats-job-name: "istio-mesh"<RESET>
// <MODIFY_LINE>    <DELETE>security.<RESET>istio.io/....
def processCrossLineDiff(diff: String): String =
  val mth = MARKER_PT.matcher(diff)
  val b = new StringBuffer()
  while (mth.find()) {
    val matched = mth.group()
    val tagStart = mth.group(1)
    val replacement =
      if (matched.contains("\n"))
        matched.replaceAll(s"\n(${c.MARK_MODIFY_LINE})?", s"${c.TAG_END}$$0$tagStart")
      else Matcher.quoteReplacement(matched)
    mth.appendReplacement(b, replacement)
  }
  mth.appendTail(b)
  b.toString
end processCrossLineDiff

val CXT_LINES = 8
def stripMultiLineDiff(diff: String): String =
  import c._
  val lines = diff.linesWithSeparators.toList
  var hasSkipped = false
  val resultLines = mutable.ListBuffer[String]()
  val markedLines = lines.map(_.matches(s"(?s).*($MARK_DELETE_LINE|$MARK_MODIFY_LINE|$MARK_ADD_LINE).*"))
  markedLines
    .zipWithIndex
    .map{case (b, i) => b || markedLines.slice(i-CXT_LINES, i+CXT_LINES).contains(true)}
    .zip(lines)
    .foreach{ case (b, l) =>
      if (b) {
        resultLines.addOne(l)
        hasSkipped = false
      }
      else {
        if (!hasSkipped) {
          resultLines.addOne("<skipped...>\n")
          hasSkipped = true
        }
      }
    }
  resultLines.mkString("")
end stripMultiLineDiff

case class Args(source: SourceDatabase = FromK8s, target: Path = root/"dev"/"stdin", isK8s: Boolean = false, showNew: Boolean = false, showRemoved: Boolean = false, debugFlags: Set[String] = Set()):
  class Debug extends Dynamic {
    def selectDynamic(name: String): Boolean = debugFlags.contains(name)
  }
  val debug: Debug = new Debug

import scopt.OParser
val builder = OParser.builder[Args]
val parser1 = {
  import builder.{arg, _}
  OParser.sequence(
    programName("diff"),
    head("diff"),
    help('h', "help")
      .text("Show this help."),
    opt[Unit]("k8s")
      .text("Treat yaml docs as kubernetes resources.")
      .optional()
      .action((_, a) => a.copy(isK8s = true)),
    opt[Unit]("show-new")
      .text("Show complete yaml text of new yaml docs.")
      .optional()
      .action((_, a) => a.copy(showNew = true)),
    opt[Unit]("show-removed")
      .text("Show complete yaml text of removed yaml docs.")
      .optional()
      .action((_, a) => a.copy(showRemoved = true)),
    opt[String]('d', "debug")
      .hidden()
      .action((d, a) => a.copy(debugFlags = a.debugFlags.+(d))),
    arg[String]("source")
      .text("Source yaml file, specify \"<k8s>\" to fetch resource from kubernetes cluster, and default to be <k8s>.")
      .optional()
      .action((f, a) => a.copy(source = new FromYaml(read(oPath(f)), a.isK8s))),
    arg[String]("target")
      .text("Target yaml file, default to be stdin.")
      .optional()
      .action((f, a) => a.copy(target = if (f.equals("-")) root/"dev"/"stdin" else oPath(f))),
    checkConfig{
      case Args(_: FromK8s.type, _, false, _, _, _) => failure("You should add --k8s option when the source is kubernetes cluster.")
      case _ => success
    }
  )
}

OParser.parse(parser1, args, Args()) match {
  case Some(config) =>
    doDiff(config)
  case _ =>
}