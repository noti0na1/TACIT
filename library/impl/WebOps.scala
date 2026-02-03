package library

import java.net.{URI, HttpURLConnection}
import language.experimental.captureChecking

object WebOps:
  def httpGet(url: String)(using net: Network): String =
    val uri = URI(url)
    net.validateHost(uri.getHost)
    val conn = uri.toURL.openConnection().asInstanceOf[HttpURLConnection]
    try
      conn.setRequestMethod("GET")
      conn.setConnectTimeout(10000)
      conn.setReadTimeout(10000)
      val is = conn.getInputStream
      try String(is.readAllBytes())
      finally is.close()
    finally conn.disconnect()

  def httpPost(url: String, body: String, contentType: String)(using net: Network): String =
    val uri = URI(url)
    net.validateHost(uri.getHost)
    val conn = uri.toURL.openConnection().asInstanceOf[HttpURLConnection]
    try
      conn.setRequestMethod("POST")
      conn.setDoOutput(true)
      conn.setRequestProperty("Content-Type", contentType)
      conn.setConnectTimeout(10000)
      conn.setReadTimeout(10000)
      val os = conn.getOutputStream
      try os.write(body.getBytes)
      finally os.close()
      val is = conn.getInputStream
      try String(is.readAllBytes())
      finally is.close()
    finally conn.disconnect()
