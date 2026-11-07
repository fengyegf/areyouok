package com.catmoecircle.areyouok.gateway.transport

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OkHttp 实现层的 `/v1/models`、`/v1/permissions`、`/v1/sessions/{id}/{model,permission}`。
 *
 * 用 OkHttp `Interceptor` 直接回放响应（测试类路径没有 MockWebServer，也不改 build.gradle），
 * 覆盖：真实目录形状解析、旧形状兜底、请求 path/body、HTTP 错误信封与 200+`ok:false` 信封。
 */
class OkHttpGatewayApiTest {

    private val endpoint = GatewayEndpoint(baseUrl = "http://127.0.0.1:7788", name = "PC")

    private fun apiReturning(
        code: Int,
        body: String,
        requests: MutableList<Request>? = null,
    ): OkHttpGatewayApi {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                requests?.add(request)
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("test")
                    .body(body.toResponseBody(JSON_MEDIA_TYPE))
                    .build()
            }
            .build()
        return OkHttpGatewayApi(client, { endpoint })
    }

    @Test
    fun `models parses real dsh catalog envelope`() = runTest {
        val requests = mutableListOf<Request>()
        val api = apiReturning(
            200,
            """{"ok":true,"catalog":{
                "default":{"provider":"opencode-go","model":"deepseek-v4.1-flash","reasoningEffort":"max"},
                "routableProviders":["opencode-go"],
                "groups":[{"id":"opencode-go","name":"opencode-go","models":[
                    {"id":"deepseek-v4.1-flash","name":"DeepSeek V4.1 Flash",
                     "reasoning":{"efforts":[{"id":"high","name":"High"}],"defaultEffort":"high"}}]}],
                "failures":[]}}""",
            requests,
        )

        val catalog = api.models()

        assertEquals("/v1/models", requests.single().url.encodedPath)
        assertEquals("opencode-go", catalog.default?.provider)
        val model = catalog.models.single()
        assertEquals("opencode-go", model.provider)
        assertEquals("deepseek-v4.1-flash", model.modelId)
        assertEquals("DeepSeek V4.1 Flash", model.displayName)
        assertEquals("high", model.defaultEffort)
    }

    @Test
    fun `models returns empty catalog instead of throwing on unrecognized payload`() = runTest {
        // 形状不认识 / ok:false / catalog 是字符串：一律空目录，UI 隐藏入口而不是崩溃。
        assertTrue(apiReturning(200, """{"ok":true,"catalog":"oops"}""").models().isEmpty)
        assertTrue(apiReturning(200, """{"ok":false}""").models().isEmpty)
        assertTrue(apiReturning(200, """{"ok":true}""").models().isEmpty)
        // 更早的网关直接返回裸数组。
        assertEquals(1, apiReturning(200, """[{"id":"m1","label":"Bare"}]""").models().models.size)
    }

    @Test
    fun `permission catalog parses options and defaults`() = runTest {
        val requests = mutableListOf<Request>()
        val api = apiReturning(
            200,
            """{"ok":true,"catalog":{"options":[
                {"value":"read-only","name":"只读"},
                {"value":"workspace-write","name":"工作区写入"},
                {"value":"danger-full-access","name":"完全访问"}],
                "defaultOptions":[{"value":"workspace-write","name":"工作区写入"}],"defaultPreset":"workspace-write"}}""",
            requests,
        )

        val catalog = api.permissionCatalog()

        assertEquals("/v1/permissions", requests.single().url.encodedPath)
        assertEquals(3, catalog.options.size)
        assertEquals("danger-full-access", catalog.options.last().value)
        assertEquals("完全访问", catalog.displayName("danger-full-access"))
        assertEquals("workspace-write", catalog.defaultPreset)
    }

    @Test
    fun `permission catalog surfaces http error envelope`() = runTest {
        // 网关还没实现 /v1/permissions 时是 404 + 错误信封（实测）→ GatewayException not_found。
        val api = apiReturning(
            404,
            """{"ok":false,"error":{"code":"not_found","message":"no such endpoint"}}""",
        )

        val error = runCatching { api.permissionCatalog() }.exceptionOrNull()

        assertTrue("expected GatewayException, got $error", error is GatewayException)
        error as GatewayException
        assertEquals(GatewayException.CODE_NOT_FOUND, error.code)
        assertEquals(404, error.httpStatus)
        assertEquals("资源不存在或已结束", error.userMessage())
        assertEquals("no such endpoint", error.message)
    }

    @Test
    fun `select permission posts preset and decodes result`() = runTest {
        val requests = mutableListOf<Request>()
        val api = apiReturning(
            200,
            """{"ok":true,"sessionId":"s1","preset":"danger-full-access",
                "result":{"kind":"success","text":"permission updated"}}""",
            requests,
        )

        val result = api.selectPermission("s1", "danger-full-access")

        assertEquals("/v1/sessions/s1/permission", requests.single().url.encodedPath)
        val body = requests.single().bodyText()
        assertTrue("unexpected body: $body", body.contains("\"preset\":\"danger-full-access\""))
        assertEquals("s1", result.sessionId)
        assertEquals("danger-full-access", result.preset)
        assertEquals("success", result.result?.kind)
    }

    @Test
    fun `select permission throws on 200 ok false envelope`() = runTest {
        // 部分端点沿用 DSH 的「HTTP 200 + ok:false」风格，客户端必须翻成 GatewayException。
        val api = apiReturning(
            200,
            """{"ok":false,"error":{"code":"invalid_request","message":"unknown permission preset"}}""",
        )

        val error = runCatching { api.selectPermission("s1", "nope") }.exceptionOrNull()

        assertTrue("expected GatewayException, got $error", error is GatewayException)
        error as GatewayException
        assertEquals(GatewayException.CODE_INVALID_REQUEST, error.code)
        assertEquals("unknown permission preset", error.message)
    }

    @Test
    fun `select model posts provider model and optional reasoning effort`() = runTest {
        val requests = mutableListOf<Request>()
        val api = apiReturning(200, """{"ok":true}""", requests)

        api.selectModel("s1", "opencode-go", "deepseek-v4.1-flash", "max")

        assertEquals("/v1/sessions/s1/model", requests.single().url.encodedPath)
        val body = requests.single().bodyText()
        assertTrue("unexpected body: $body", body.contains("\"provider\":\"opencode-go\""))
        assertTrue("unexpected body: $body", body.contains("\"model\":\"deepseek-v4.1-flash\""))
        assertTrue("unexpected body: $body", body.contains("\"reasoningEffort\":\"max\""))
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** 读出请求体文本（OkHttp 的 RequestBody 是一次性流）。 */
        private fun Request.bodyText(): String {
            val requestBody = body ?: return ""
            val buffer = okio.Buffer()
            requestBody.writeTo(buffer)
            return buffer.readUtf8()
        }
    }
}
