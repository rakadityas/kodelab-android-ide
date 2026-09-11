package dev.kodelab.ide

import dev.kodelab.ide.workspace.SessionSnapshot
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionSnapshotTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `snapshot round-trips through json`() {
        val snap = SessionSnapshot(
            workspaceUri = "content://com.android.externalstorage.documents/tree/primary%3Adev",
            openFiles = listOf("content://x/doc/1", "file:///data/user/0/app/files/a.kt"),
            activeFile = "content://x/doc/1",
        )
        val decoded = json.decodeFromString(
            SessionSnapshot.serializer(),
            json.encodeToString(SessionSnapshot.serializer(), snap),
        )
        assertEquals(snap, decoded)
    }

    @Test
    fun `an empty snapshot restores nothing`() {
        val snap = SessionSnapshot()
        assertNull(snap.workspaceUri)
        assertNull(snap.activeFile)
        assertEquals(emptyList<String>(), snap.openFiles)
    }

    @Test
    fun `unknown fields from a newer build are ignored`() {
        val decoded = json.decodeFromString(
            SessionSnapshot.serializer(),
            """{"workspaceUri":"file:///w","openFiles":["file:///w/a.md"],"somethingNew":42}""",
        )
        assertEquals("file:///w", decoded.workspaceUri)
        assertEquals(listOf("file:///w/a.md"), decoded.openFiles)
    }
}
