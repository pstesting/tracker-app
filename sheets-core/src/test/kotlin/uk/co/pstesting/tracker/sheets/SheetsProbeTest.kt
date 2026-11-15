package uk.co.pstesting.tracker.sheets

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

private class MemoryJournal : ProbeJournal {
    var value: PendingProbe? = null
    var failSave = false
    override fun read() = value
    override fun save(probe: PendingProbe) {
        if (failSave) throw ProbeFailure("Recovery storage unavailable.")
        value = probe
    }
    override fun clear() { value = null }
}

private class FakeSheets : SheetsTransport {
    val schema = JSONObject(requireNotNull(javaClass.getResourceAsStream("/sheets-schema.json")).bufferedReader().readText())
    val sheets = mutableMapOf(1 to "Guide", 2 to "WorkoutPlan", 3 to "Sessions", 4 to "SessionExercises", 5 to "Sets")
    val grids = mutableMapOf<Int, JSONArray>()
    val calls = mutableListOf<Triple<String, String, JSONObject?>>()
    var lostWriteResponse = false
    var rejectWrite = false
    var badReadback = false
    var badMarker = false
    var deleteFails = false
    var lostDeleteResponse = false
    var textWeight = false
    var reverseHeaders = false
    var version = "1.0"
    var badPlanHeader = false
    var duplicateHeader = false
    var duplicatePlanId = false
    var missingWeight = false

    override fun request(method: String, path: String, body: JSONObject?): JSONObject {
        calls.add(Triple(method, path, body))
        if (method == "POST") {
            val requests = requireNotNull(body).getJSONArray("requests")
            if (requests.getJSONObject(0).has("addSheet") && rejectWrite) throw ProbeFailure("Write refused.")
            for (i in 0 until requests.length()) {
                val request = requests.getJSONObject(i)
                when {
                    request.has("addSheet") -> request.getJSONObject("addSheet").getJSONObject("properties").let { sheets[it.getInt("sheetId")] = it.getString("title") }
                    request.has("updateCells") -> {
                        val cells = request.getJSONObject("updateCells")
                        val rows = cells.getJSONArray("rows")
                        val data = JSONArray()
                        for (r in 0 until rows.length()) {
                            val values = rows.getJSONObject(r).getJSONArray("values")
                            val row = JSONArray()
                            for (c in 0 until values.length()) {
                                val value = values.getJSONObject(c).getJSONObject("userEnteredValue")
                                row.put(if (value.has("numberValue")) value.get("numberValue") else value.get("stringValue"))
                            }
                            data.put(row)
                        }
                        grids[cells.getJSONObject("start").getInt("sheetId")] = data
                    }
                    request.has("deleteSheet") -> {
                        if (deleteFails) throw ProbeFailure("Delete refused.")
                        sheets.remove(request.getJSONObject("deleteSheet").getInt("sheetId"))
                        if (lostDeleteResponse) throw ProbeFailure("Delete response lost.")
                    }
                }
            }
            if (requests.getJSONObject(0).has("addSheet") && lostWriteResponse) throw ProbeFailure("Write response lost.")
            return JSONObject()
        }
        if (!path.contains("/values/")) return JSONObject().put("sheets", JSONArray(sheets.map { (id, title) ->
            JSONObject().put("properties", JSONObject().put("sheetId", id).put("title", title))
        }))
        val range = URLDecoder.decode(path.substringAfter("/values/").substringBefore("?"), "UTF-8")
        if (range == "'Guide'!B4") return JSONObject().put("values", JSONArray().put(JSONArray().put(version)))
        val name = range.substringAfter("'").substringBefore("'")
        if (name == "WorkoutPlan") {
            val headers = schema.getJSONObject("tables").getJSONObject(name).getJSONArray("headers")
            val h = (0 until headers.length()).map { headers.getString(it) }.let { if (reverseHeaders) it.reversed() else it }.toMutableList()
            if (badPlanHeader) h[0] = "Unknown"
            if (duplicateHeader) h[0] = h[1]
            fun row(person: String, id: String) = JSONArray(h.map { header ->
                when (header) {
                    "Person" -> person
                    "Workout" -> "Example workout"
                    "Pair" -> "A"
                    "Exercise" -> "Example exercise"
                    "Starting weight (kg)" -> if (missingWeight) JSONObject.NULL else 10
                    "Weight basis" -> "Combined"
                    "Equipment" -> "Dumbbell"
                    "Repetition mode" -> "Per side"
                    "Working sets" -> 3
                    "Target reps" -> 12
                    "Default increase (kg)" -> 1
                    "Progression" -> "3 x 12"
                    "Active" -> true
                    "Plan ID" -> id
                    "Workout ID" -> "example_workout"
                    "Exercise ID" -> "example_exercise"
                    else -> ""
                }
            })
            return JSONObject().put("values", JSONArray().put(JSONArray(h)).put(row("Example A", "plan_a")).put(row("Example B", if (duplicatePlanId) "plan_a" else "plan_b")))
        }
        if (name in listOf("Sessions", "SessionExercises", "Sets")) {
            return JSONObject().put("values", JSONArray().put(schema.getJSONObject("tables").getJSONObject(name).getJSONArray("headers")))
        }
        val id = sheets.entries.first { it.value == name }.key
        val grid = JSONArray(requireNotNull(grids[id]).toString())
        if (range.endsWith("A2:B2")) {
            val marker = grid.getJSONArray(1)
            return JSONObject().put("values", JSONArray().put(JSONArray().put("TEST ONLY").put(if (badMarker) "wrong-run" else marker.get(1))))
        }
        if (textWeight) grid.getJSONArray(1).put(4, "6")
        if (badReadback) grid.getJSONArray(2).put(3, "Left")
        return JSONObject().put("values", grid)
    }
}

class SheetsProbeTest {
    private val workbook = "example_workbook_id"

    @Test fun `accepts workbook IDs and official Sheets links only`() {
        assertEquals(workbook, spreadsheetId(" https://docs.google.com/spreadsheets/d/$workbook/edit?usp=sharing "))
        assertEquals(workbook, spreadsheetId(workbook))
        assertFailsWith<ProbeFailure> { spreadsheetId("https://example.com/spreadsheets/d/$workbook/edit") }
        assertFailsWith<ProbeFailure> { spreadsheetId("../escape") }
    }

    @Test fun `read check validates workbook without writes`() {
        val fake = FakeSheets()
        val result = SheetsProbe(fake, MemoryJournal()).run(workbook, false)
        assertTrue(result.passed)
        assertEquals(2, result.workoutRows)
        assertFalse(fake.calls.any { it.first == "POST" })
    }

    @Test fun `column reordering preserves header based lookup`() {
        val fake = FakeSheets().apply { reverseHeaders = true }
        assertTrue(SheetsProbe(fake, MemoryJournal()).run(workbook, false).passed)
    }

    @Test fun `schema mismatch stops before any mutation`() {
        val fake = FakeSheets().apply { version = "2.0" }
        val result = SheetsProbe(fake, MemoryJournal()).run(workbook, true)
        assertFalse(result.passed)
        assertFalse(fake.calls.any { it.first == "POST" })
    }

    @Test fun `missing and duplicate headings fail read checks`() {
        for (duplicate in listOf(false, true)) {
            val fake = FakeSheets().apply { badPlanHeader = !duplicate; duplicateHeader = duplicate }
            assertFalse(SheetsProbe(fake, MemoryJournal()).run(workbook, false).passed)
        }
    }

    @Test fun `missing weights are not converted to zero`() {
        val fake = FakeSheets().apply { missingWeight = true }
        assertFalse(SheetsProbe(fake, MemoryJournal()).run(workbook, false).passed)
    }

    @Test fun `duplicate assignment IDs fail read checks`() {
        val fake = FakeSheets().apply { duplicatePlanId = true }
        assertFalse(SheetsProbe(fake, MemoryJournal()).run(workbook, false).passed)
    }

    @Test fun `write round trips numeric side results and verifies cleanup`() {
        val fake = FakeSheets()
        val journal = MemoryJournal()
        val result = SheetsProbe(fake, journal).run(workbook, true)
        assertTrue(result.passed)
        assertTrue(result.writePassed)
        assertEquals(Cleanup.REMOVED, result.cleanup)
        assertEquals(5, fake.sheets.size)
        assertNull(journal.value)
        val posts = fake.calls.filter { it.first == "POST" }
        assertEquals(2, posts.size)
        assertTrue(posts[0].third!!.getJSONArray("requests").getJSONObject(0).has("addSheet"))
        assertFalse(posts.any { it.third.toString().contains("appendCells") })
    }

    @Test fun `journal failure prevents mutation`() {
        val fake = FakeSheets()
        val result = SheetsProbe(fake, MemoryJournal().apply { failSave = true }).run(workbook, true)
        assertFalse(result.passed)
        assertFalse(fake.calls.any { it.first == "POST" })
    }

    @Test fun `lost write response reconciles without retrying the mutation`() {
        val fake = FakeSheets().apply { lostWriteResponse = true }
        val journal = MemoryJournal()
        val result = SheetsProbe(fake, journal).run(workbook, true)
        assertFalse(result.passed)
        assertEquals(Cleanup.REMOVED, result.cleanup)
        assertNull(journal.value)
        assertEquals(1, fake.calls.count { it.third?.getJSONArray("requests")?.getJSONObject(0)?.has("addSheet") == true })
    }

    @Test fun `numeric text and wrong side both fail round trip`() {
        for (numericText in listOf(true, false)) {
            val fake = FakeSheets().apply { textWeight = numericText; badReadback = !numericText }
            val result = SheetsProbe(fake, MemoryJournal()).run(workbook, true)
            assertFalse(result.writePassed)
            assertEquals(Cleanup.REMOVED, result.cleanup)
        }
    }

    @Test fun `ownership mismatch prevents deletion and retains recovery record`() {
        val fake = FakeSheets().apply { badMarker = true }
        val journal = MemoryJournal()
        val result = SheetsProbe(fake, journal).run(workbook, true)
        assertEquals(Cleanup.NEEDS_ATTENTION, result.cleanup)
        assertNotNull(journal.value)
        assertFalse(fake.calls.any { it.third?.getJSONArray("requests")?.getJSONObject(0)?.has("deleteSheet") == true })
    }

    @Test fun `cleanup can resume with a new probe instance after restart`() {
        val fake = FakeSheets().apply { deleteFails = true }
        val journal = MemoryJournal()
        val first = SheetsProbe(fake, journal).run(workbook, true)
        assertTrue(first.writePassed)
        assertFalse(first.passed)
        assertNotNull(journal.value)
        fake.deleteFails = false
        val recovery = SheetsProbe(fake, journal).recover()
        assertTrue(recovery.passed)
        assertEquals(Cleanup.REMOVED, recovery.cleanup)
        assertNull(journal.value)
    }

    @Test fun `unobserved mutation remains uncertain and blocks a new write`() {
        val fake = FakeSheets().apply { rejectWrite = true }
        val journal = MemoryJournal()
        val first = SheetsProbe(fake, journal).run(workbook, true)
        assertEquals(Cleanup.NOT_OBSERVED, first.cleanup)
        assertNotNull(journal.value)
        val before = fake.calls.size
        assertFalse(SheetsProbe(fake, journal).run(workbook, true).passed)
        assertEquals(before, fake.calls.size)
    }

    @Test fun `same title with different ID is never deleted`() {
        val fake = FakeSheets().apply { deleteFails = true }
        val journal = MemoryJournal()
        SheetsProbe(fake, journal).run(workbook, true)
        val pending = requireNotNull(journal.value)
        fake.sheets.remove(pending.sheetId)
        fake.sheets[99] = pending.title
        fake.deleteFails = false
        val result = SheetsProbe(fake, journal).recover()
        assertEquals(Cleanup.NOT_OBSERVED, result.cleanup)
        assertEquals(pending.title, fake.sheets[99])
    }

    @Test fun `lost delete response never claims confirmed cleanup`() {
        val fake = FakeSheets().apply { lostDeleteResponse = true }
        val journal = MemoryJournal()
        val result = SheetsProbe(fake, journal).run(workbook, true)
        assertEquals(Cleanup.NEEDS_ATTENTION, result.cleanup)
        assertNotNull(journal.value)
    }

    @Test fun `unexpected errors cannot leak response content`() {
        val transport = object : SheetsTransport {
            override fun request(method: String, path: String, body: JSONObject?): JSONObject = error("sensitive token or workbook contents")
        }
        val result = SheetsProbe(transport, MemoryJournal()).run(workbook, false)
        assertFalse(result.toString().contains("sensitive"))
        assertFalse(result.passed)
    }
}
