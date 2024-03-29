#!/usr/bin/env amm3

import $ivy.`org.apache.httpcomponents:httpclient:4.5.2`
import $ivy.`joda-time:joda-time:2.1`
import $ivy.`org.slf4j:slf4j-api:1.7.25`
import $cp.files.`nos.jar`
import com.netease.cloud.auth.{BasicCredentials, Credentials}
import com.netease.cloud.services.nos.NosClient
import com.netease.cloud.services.nos.model.{CompleteMultipartUploadRequest, CompleteMultipartUploadResult, InitiateMultipartUploadRequest, InitiateMultipartUploadResult, ListPartsRequest, PartETag, PartListing, UploadPartRequest}
import java.io.{ByteArrayInputStream, File, FileInputStream, InputStream}
import java.util

import mainargs.{main, arg, ParserForMethods, Leftover}
@main
def main(rest: Leftover[String]) =
  uploadFileToNOS(rest.value.head, rest.value.last)
end main


def uploadFileToNOS(filePath: String, nosFilePath: String): Unit = {
  val nosSecretKey: String = System.getenv("NOS_SK")
  val nosAccessKey: String = System.getenv("NOS_AK")
  val nosBucketName: String = System.getenv("NOS_BUCKET")
  val nosEndPoint: String = System.getenv("NOS_EP")
  val credentials: Credentials = new BasicCredentials(nosAccessKey, nosSecretKey)
  val nosClient: NosClient = new NosClient(credentials)
  nosClient.setEndpoint(nosEndPoint)
  val file: File = new File(filePath)
  if (nosClient.doesObjectExist(nosBucketName, nosFilePath, null)) println("file {} in NOS is already exist: " + nosFilePath)
  else {
    val is: FileInputStream = new FileInputStream(file)
    val initRequest: InitiateMultipartUploadRequest = new InitiateMultipartUploadRequest(nosBucketName, nosFilePath)
    val initResult: InitiateMultipartUploadResult = nosClient.initiateMultipartUpload(initRequest)
    val uploadId: String = initResult.getUploadId
    val buffSize: Int = 10485760 //分片大小为10MB
    val buffer: Array[Byte] = new Array[Byte](buffSize)
    Iterator.continually(is.read(buffer, 0, buffSize))
      .takeWhile(_ != -1)
      .zipWithIndex
      .foreach {
        case (readLen, i) =>
          val partStream: InputStream = new ByteArrayInputStream(buffer)
          nosClient.uploadPart(
            new UploadPartRequest()
              .withBucketName(nosBucketName)
              .withUploadId(uploadId)
              .withInputStream(partStream)
              .withKey(nosFilePath)
              .withPartSize(readLen)
              .withPartNumber(i+1)
          )
          println(s"Uploaded part ${i+1}")
      }
    val partETags: util.List[PartETag] = new util.ArrayList[PartETag]
    var nextMarker: Int = 0
    var truncated = true
    while (truncated) {
      val listPartsRequest: ListPartsRequest = new ListPartsRequest(nosBucketName, nosFilePath, uploadId)
      listPartsRequest.setPartNumberMarker(nextMarker)
      val partList: PartListing = nosClient.listParts(listPartsRequest)
      import scala.jdk.CollectionConverters._
      for (ps <- partList.getParts.asScala) {
        nextMarker += 1
        partETags.add(new PartETag(ps.getPartNumber, ps.getETag))
      }
      truncated = partList.isTruncated
    }
    val completeRequest: CompleteMultipartUploadRequest = new CompleteMultipartUploadRequest(nosBucketName, nosFilePath, uploadId, partETags)
    val completeResult: CompleteMultipartUploadResult = nosClient.completeMultipartUpload(completeRequest)
  }
}


import upickle.default.{ReadWriter => RW, macroRW}

case class Thing(myFieldA: Int, myFieldB: String)
object Thing{
  implicit val rw: RW[Thing] = macroRW
}
case class Big(i: Int, b: Boolean, str: String, c: Char, t: Thing)
object Big{
  implicit val rw: RW[Big] = macroRW
}
