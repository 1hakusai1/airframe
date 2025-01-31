/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package wvlet.airframe.http.finagle

import com.twitter.finagle.http.{Method, Request, Response}
import com.twitter.util.Future
import wvlet.airframe.codec.MessageCodec
import wvlet.airframe.control.Resource
import wvlet.airframe.http.HttpAccessLogWriter.JSONHttpAccessLogWriter
import wvlet.airframe.http._
import wvlet.airframe.http.finagle.filter.HttpAccessLogFilter
import wvlet.airframe.newDesign
import wvlet.airframe.surface.secret
import wvlet.airspec.AirSpec
import wvlet.log.Logger
import wvlet.log.io.IOUtil

import java.io.File

/**
  */
object HttpAccessLogTest extends AirSpec {

  trait MyService {
    @Endpoint(path = "/user/:id")
    def user(id: Int) = {
      s"hello user:${id}"
    }

    @Endpoint(method = HttpMethod.DELETE, path = "/user/:id")
    def deleteUser(id: Int): String = {
      throw new IllegalStateException(s"failed to search user:${id}")
    }

    @Endpoint(path = "/user/:id/profile")
    def profile(id: Int): String = {
      if (id == 0) {
        throw Http.serverException(HttpStatus.Forbidden_403)
      } else {
        throw Http.serverException(HttpStatus.Unauthorized_401, new IllegalStateException("failed to read profile"))
      }
    }

    @Endpoint(path = "/test")
    def requestArgTest(
        p1: String,
        req1: HttpMessage.Request,
        req2: Request,
        context: FinagleBackend.Context
    ): String = {
      "test"
    }

    @Endpoint(method = HttpMethod.POST, path = "/test-secret")
    def secretArg(@secret password: String, userData: UserData): String = "test"
  }

  case class UserData(id: Int, @secret pii: String)

  trait AddExtraHeaderFilter extends FinagleFilter {
    override def apply(
        request: Request,
        context: Context
    ): Future[Response] = {
      request.headerMap.put("X-Trace-Id", "012345")
      context(request)
    }
  }

  private val router =
    Router
      .add[AddExtraHeaderFilter]
      .andThen[MyService]

  private val inMemoryLogWriter = HttpAccessLogWriter.inMemoryLogWriter

  test(
    "Record access logs",
    design = Finagle.server
      .withLoggingFilter(new HttpAccessLogFilter(httpAccessLogWriter = inMemoryLogWriter))
      .withRouter(router)
      .design
      .add(Finagle.client.noRetry.syncClientDesign)
  ) { client: FinagleSyncClient =>
    test("basic log entries") {
      val resp = client.get[String](
        "/user/1?session_id=xxx",
        { r: Request =>
          // Add a custom header
          r.headerMap.put("X-App-Version", "1.0")
          r
        }
      )
      resp shouldBe "hello user:1"

      val log = inMemoryLogWriter.getLogs.head
      debug(log)
      log.get("time") shouldBe defined
      log.get("method") shouldBe Some("GET")
      log.get("path") shouldBe Some("/user/1")
      log.get("uri") shouldBe Some("/user/1?session_id=xxx")
      log.get("query_string") shouldBe Some("session_id=xxx")
      log.get("request_size") shouldBe Some(0)
      log.get("remote_host") shouldBe defined
      log.get("remote_port") shouldBe defined
      log.get("response_time_ms") shouldBe defined
      log.get("status_code") shouldBe Some(200)
      log.get("status_code_name") shouldBe Some(HttpStatus.Ok_200.reason)
      // Custom headers
      log.get("x_app_version") shouldBe Some("1.0")

      // Headers added in a filter
      log.get("x_trace_id") shouldBe Some("012345")

      // RPC logs
      log.get("rpc_method") shouldBe Some("user")
      log.get("rpc_interface") shouldBe Some("wvlet.airframe.http.finagle.HttpAccessLogTest.MyService")
      log.get("rpc_class") shouldBe Some("wvlet.airframe.http.finagle.HttpAccessLogTest.MyService")
      log.get("rpc_args") shouldBe Some(Map("id" -> 1))
    }

    Logger("wvlet.airframe.http").suppressWarnings {
      test("exception logs") {
        warn("Start exception logging test")

        // Test exception logs
        inMemoryLogWriter.clear()
        val resp = client.sendSafe(Request(Method.Delete, "/user/0"))
        val log  = inMemoryLogWriter.getLogs.head
        debug(log)

        resp.statusCode shouldBe HttpStatus.InternalServerError_500.code
        log.get("exception") match {
          case Some(e: IllegalStateException) if e.getMessage.contains("failed to search user:0") =>
          // OK
          case _ =>
            fail("Can't find exception log")
        }
        log.get("exception_message").get.toString shouldBe "failed to search user:0"
      }

      test("Suppress regular HttpServerException log") {
        // Test exception logs
        inMemoryLogWriter.clear()
        val resp = client.sendSafe(Request("/user/0/profile"))
        val log  = inMemoryLogWriter.getLogs.head
        debug(log)

        resp.statusCode shouldBe HttpStatus.Forbidden_403.code
        log.get("exception") shouldBe empty
        log.get("exception_message") shouldBe empty
      }

      test("Report HttpServerException with cause") {
        // Test exception logs
        inMemoryLogWriter.clear()
        val resp = client.sendSafe(Request("/user/1/profile"))
        val log  = inMemoryLogWriter.getLogs.head
        debug(log)

        resp.statusCode shouldBe HttpStatus.Unauthorized_401.code
        log.get("exception") match {
          case Some(e: IllegalStateException) if e.getMessage.contains("failed to read profile") =>
          // OK
          case _ =>
            fail("Can't find exception log")
        }
        log.get("exception_message").get.toString shouldBe "failed to read profile"
      }

      test("Omit request context objects from logs") {
        inMemoryLogWriter.clear()
        val resp                  = client.sendSafe(Request("/test?p1=hello"))
        val log: Map[String, Any] = inMemoryLogWriter.getLogs.head
        debug(log)

        val args = log("rpc_args").asInstanceOf[Map[String, Any]]
        args.get("req1") shouldBe empty
        args.get("req2") shouldBe empty
        args.get("context") shouldBe empty
        args.get("p1") shouldBe Some("hello")
      }

      test("Hide @secret args") {
        inMemoryLogWriter.clear()
        val req = Request(Method.Post, "/test-secret")
        req.setContentString("""{"password":"(user password)", "userData":{"id":1,"pii":"(confidential data)"}}""")
        val resp                  = client.sendSafe(req)
        val log: Map[String, Any] = inMemoryLogWriter.getLogs.head
        debug(log)

        val args = log("rpc_args").asInstanceOf[Map[String, Any]]
        args.contains("password") shouldBe false
        args("userData").asInstanceOf[Map[String, Any]].contains("pii") shouldBe false
      }
    }
  }

  test(
    "JSON access log",
    design = newDesign.bind[Resource[File]].toInstance(Resource.newTempFile("target/http_access_log_test.json"))
  ) { file: Resource[File] =>
    test(
      "Write logs in JSON",
      design = Finagle.server
        .withRouter(router)
        .withLoggingFilter(
          new HttpAccessLogFilter(
            httpAccessLogWriter = new JSONHttpAccessLogWriter(HttpAccessLogConfig(fileName = file.get.getPath()))
          )
        )
        .designWithSyncClient
    ) { client: FinagleSyncClient =>
      val resp = client.get[String]("/user/2")
      resp shouldBe "hello user:2"
    }

    test("Read JSON logs") {
      // Read the JSON log file
      val json = IOUtil.readAsString(file.get)
      debug(json)

      // Parse the JSON log
      val log = MessageCodec.of[Map[String, Any]].fromJson(json)
      debug(log)
      log.get("time") shouldBe defined
      log.get("method") shouldBe Some("GET")
      log.get("path") shouldBe Some("/user/2")
      log.get("uri") shouldBe Some("/user/2")
      log.get("query_string") shouldBe empty
      log.get("request_size") shouldBe Some(0)
      log.get("remote_host") shouldBe defined
      log.get("remote_port") shouldBe defined
      log.get("response_time_ms") shouldBe defined
      log.get("status_code") shouldBe Some(200)
      log.get("status_code_name") shouldBe Some(HttpStatus.Ok_200.reason)

      log.get("rpc_method") shouldBe Some("user")
      log.get("rpc_interface") shouldBe Some("wvlet.airframe.http.finagle.HttpAccessLogTest.MyService")
      log.get("rpc_class") shouldBe Some("wvlet.airframe.http.finagle.HttpAccessLogTest.MyService")
      log.get("rpc_args") shouldBe Some(Map("id" -> 2))
    }
  }
}
