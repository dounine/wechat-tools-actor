package com.dounine.jb.tools.util

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.charset.{Charset, CharsetDecoder}
import java.nio.{ByteBuffer, CharBuffer}
import java.util.zip.{GZIPInputStream, GZIPOutputStream}

object ZipUtil {

  def byteBufferToString(buffer: ByteBuffer): String = {
    try {
      var charBuffer: CharBuffer = null
      val charset: Charset = Charset.forName("ISO-8859-1")
      val decoder: CharsetDecoder = charset.newDecoder
      charBuffer = decoder.decode(buffer)
      buffer.flip
      charBuffer.toString
    } catch {
      case ex: Exception =>
        ex.printStackTrace()
        throw ex
    }
  }

  def unzip(msg: String): String = {
    val inputStream: GZIPInputStream = new GZIPInputStream(new ByteArrayInputStream(msg.getBytes("ISO-8859-1")))
    scala.io.Source.fromInputStream(inputStream).mkString
  }

  def zip(msg: String): String = {
    val outputStream: ByteArrayOutputStream = new ByteArrayOutputStream()
    val zs: GZIPOutputStream = new GZIPOutputStream(outputStream)
    zs.write(msg.getBytes("ISO-8859-1"))
    zs.close()
    outputStream.toString("ISO-8859-1")
  }

}
