package com.eldermoss.hermes.core.data

import com.eldermoss.hermes.core.net.ApiError
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MemRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repo: MemRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val url = server.url("/").toString()
        repo = MemRepository(memsUrl = { url }, token = { "sekrit" })
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `search sends bearer token and parses records`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """[{"id":1,"text":"alpha","category":"fact","importance":3,
                    "confidence":1.0,"source":"user statement","status":"active",
                    "semantic_similarity":0.5}]"""
            )
        )
        val records = repo.search("alpha")
        assertEquals(1, records.size)
        assertEquals(1, records[0].id)
        assertEquals(0.5, records[0].semanticSimilarity!!, 1e-9)

        val req = server.takeRequest()
        assertEquals("Bearer sekrit", req.getHeader("Authorization"))
        assertEquals("/api/search?q=alpha", req.path)
    }

    @Test
    fun `add posts the write body`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"id":7,"text":"new memory","category":"fact","importance":3,
                    "confidence":1.0,"source":"app edit","status":"active"}"""
            )
        )
        val record = repo.add(MemRepository.MemWrite(text = "new memory", source = "app edit"))
        assertEquals(7, record.id)
        val req = server.takeRequest()
        assertEquals("/api/mems/add", req.path)
        assertTrue(req.body.readUtf8().contains("new memory"))
    }

    @Test
    fun `http errors surface as ApiError`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))
        try {
            repo.list()
            throw AssertionError("expected ApiError")
        } catch (e: ApiError) {
            assertEquals(401, e.code)
        }
    }
}
