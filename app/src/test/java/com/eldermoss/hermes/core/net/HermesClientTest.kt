package com.eldermoss.hermes.core.net

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HermesClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: HermesClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = HermesClient(config = { ServerConfig(server.url("/").toString(), "enrich", "pw") })
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `login discovers provider and stores cookie`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"auth_providers":["basic"]}"""))
        server.enqueue(
            MockResponse().setBody("""{"ok":true}""")
                .addHeader("Set-Cookie", "hermes_session=abc123; Path=/; HttpOnly")
        )
        val err = client.login()
        assertNull(err)
        assertEquals("hermes_session=abc123", client.currentCookie())

        val status = server.takeRequest()
        assertEquals("/api/status", status.path)
        val login = server.takeRequest()
        assertEquals("/auth/password-login", login.path)
        assertTrue(login.body.readUtf8().contains("\"provider\":\"basic\""))
    }

    @Test
    fun `login reports bad credentials`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"auth_providers":["basic"]}"""))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"Invalid credentials"}"""))
        assertEquals("Invalid username or password", client.login())
    }

    @Test
    fun `api call retries once after a 401`() = runBlocking {
        // 1. /api/sessions -> 401 (cookie expired)
        server.enqueue(MockResponse().setResponseCode(401))
        // 2. provider discovery during re-login
        server.enqueue(MockResponse().setBody("""{"auth_providers":["basic"]}"""))
        // 3. fresh login
        server.enqueue(
            MockResponse().setBody("""{"ok":true}""").addHeader("Set-Cookie", "hermes_session=fresh; Path=/")
        )
        // 4. retried call succeeds
        server.enqueue(MockResponse().setBody("""{"sessions":[{"id":"s1","title":"t"}],"total":1}"""))

        val page = client.getJson("/api/sessions?limit=40")
        val sessions = page.jsonObject["sessions"]!!.jsonArray
        assertEquals("s1", sessions[0].jsonObject["id"]?.jsonPrimitive?.content)

        val first = server.takeRequest()
        assertNull(first.getHeader("Cookie"))
        server.takeRequest() // status
        server.takeRequest() // login
        val retried = server.takeRequest()
        assertEquals("hermes_session=fresh", retried.getHeader("Cookie"))
    }

    @Test
    fun `ws ticket is parsed`() = runBlocking {
        client.setCookie("hermes_session=abc123")
        server.enqueue(MockResponse().setBody("""{"ticket":"tkn","ttl_seconds":30}"""))
        assertEquals("tkn", client.wsTicket())
        val req = server.takeRequest()
        assertEquals("/api/auth/ws-ticket", req.path)
        assertEquals("hermes_session=abc123", req.getHeader("Cookie"))
    }

    @Test
    fun `probe reads status without auth`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"version":"0.23.2","gateway_running":true,"auth_required":true,"auth_providers":["basic"]}"""
            )
        )
        val probe = client.probe()
        assertEquals("0.23.2", probe.version)
        assertTrue(probe.gatewayRunning)
        assertTrue(probe.authRequired)
    }
}
