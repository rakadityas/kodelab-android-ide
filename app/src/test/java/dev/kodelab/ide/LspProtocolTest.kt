package dev.kodelab.ide

import dev.kodelab.ide.lsp.LspClient
import dev.kodelab.ide.lsp.LspFraming
import dev.kodelab.ide.lsp.LspRpc
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class LspProtocolTest {

    @Test fun `encode produces content-length header and body`() {
        val bytes = LspFraming.encode("""{"a":1}""")
        val text = String(bytes, StandardCharsets.UTF_8)
        assertEquals("Content-Length: 7\r\n\r\n{\"a\":1}", text)
    }

    @Test fun `decode a single framed message`() {
        val d = LspFraming.Decoder()
        val msgs = d.feed(LspFraming.encode("""{"hello":"world"}"""))
        assertEquals(listOf("""{"hello":"world"}"""), msgs)
    }

    @Test fun `decode two messages in one buffer`() {
        val d = LspFraming.Decoder()
        val buf = LspFraming.encode("""{"n":1}""") + LspFraming.encode("""{"n":2}""")
        assertEquals(listOf("""{"n":1}""", """{"n":2}"""), d.feed(buf))
    }

    @Test fun `decode across partial reads`() {
        val d = LspFraming.Decoder()
        val full = LspFraming.encode("""{"k":"vvvv"}""")
        val a = full.copyOfRange(0, 10)
        val b = full.copyOfRange(10, full.size)
        assertTrue(d.feed(a).isEmpty())        // header incomplete
        assertEquals(listOf("""{"k":"vvvv"}"""), d.feed(b))
    }

    @Test fun `utf8 body length counted in bytes`() {
        val d = LspFraming.Decoder()
        val payload = """{"s":"café→"}"""   // multibyte chars
        val msgs = d.feed(LspFraming.encode(payload))
        assertEquals(listOf(payload), msgs)
    }

    @Test fun `classifies request, response and notification`() {
        assertEquals(LspRpc.Kind.REQUEST, LspRpc.parse("""{"id":1,"method":"initialize"}""").kind)
        assertEquals(LspRpc.Kind.RESPONSE, LspRpc.parse("""{"id":1,"result":{}}""").kind)
        assertEquals(LspRpc.Kind.NOTIFICATION, LspRpc.parse("""{"method":"textDocument/publishDiagnostics"}""").kind)
        assertEquals(LspRpc.Kind.ERROR, LspRpc.parse("""{"id":1,"error":{"code":-1}}""").kind)
    }

    @Test fun `parse extracts id and method`() {
        val inc = LspRpc.parse("""{"id":42,"method":"textDocument/completion","params":{"x":1}}""")
        assertEquals(42, inc.id)
        assertEquals("textDocument/completion", inc.method)
        assertEquals(1, inc.params?.optInt("x"))
    }

    @Test fun `notification has no id`() {
        assertNull(LspRpc.parse("""{"method":"initialized","params":{}}""").id)
    }

    @Test fun `request and notification builders are well formed`() {
        val req = LspRpc.parse(LspRpc.request(7, "initialize", org.json.JSONObject().put("k", "v")))
        assertEquals(LspRpc.Kind.REQUEST, req.kind)
        assertEquals(7, req.id)
        val note = LspRpc.parse(LspRpc.notification("exit", null))
        assertEquals(LspRpc.Kind.NOTIFICATION, note.kind)
        assertNull(note.id)
    }

    @Test fun `garbage payload is unknown, not a crash`() {
        assertEquals(LspRpc.Kind.UNKNOWN, LspRpc.parse("not json").kind)
    }

    @Test fun `parse publishDiagnostics params into typed diagnostics`() {
        val params = JSONObject(
            """
            {
              "uri": "file:///w/main.kt",
              "diagnostics": [
                { "range": { "start": {"line":2,"character":4}, "end": {"line":2,"character":9} },
                  "severity": 1, "message": "unresolved reference", "source": "kotlin" }
              ]
            }
            """.trimIndent(),
        )
        val pub = LspClient.parseDiagnostics(params)
        assertEquals("file:///w/main.kt", pub.uri)
        val d = pub.diagnostics.single()
        assertEquals(2, d.startLine)
        assertEquals(4, d.startChar)
        assertEquals(9, d.endChar)
        assertEquals(1, d.severity)
        assertEquals("unresolved reference", d.message)
        assertEquals("kotlin", d.source)
    }
}
