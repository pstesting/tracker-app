package uk.co.pstesting.tracker.sheets

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.UUID

class ProbeFailure(message: String) : Exception(message)

fun spreadsheetId(input: String): String {
    val value = input.trim()
    val id = if (value.startsWith("https://")) {
        val uri = runCatching { URI(value) }.getOrNull()
        if (uri?.host != "docs.google.com") throw ProbeFailure("Use a Google Sheets link or workbook ID.")
        Regex("^/spreadsheets/d/([A-Za-z0-9_-]+)(?:/.*)?$").matchEntire(uri.path)?.groupValues?.get(1)
            ?: throw ProbeFailure("The Google Sheets link is incomplete.")
    } else value
    if (!Regex("[A-Za-z0-9_-]{10,}").matches(id)) throw ProbeFailure("Enter a valid workbook ID or Google Sheets link.")
    return id
}

interface SheetsTransport {
    fun request(method: String, path: String, body: JSONObject? = null): JSONObject
}

/** Fixed API origin, no redirects, no token logging and no automatic application retries. */
class GoogleSheetsTransport(private val token: String) : SheetsTransport {
    override fun request(method: String, path: String, body: JSONObject?): JSONObject {
        require(method == "GET" || method == "POST")
        val connection = URI("https://sheets.googleapis.com/v4/spreadsheets/$path").toURL()
            .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                // Streaming mode prevents the client replaying a POST after a connection failure.
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            if (status !in 200..299) throw ProbeFailure(when (status) {
                401 -> "Google access expired. Reconnect and try again."
                403 -> "Google refused access. Check Editor sharing, Sheets API and consent settings."
                404 -> "Workbook not found. Check its link and the selected Google account."
                429 -> "Google's request allowance was reached. Wait before trying again."
                else -> "Google Sheets returned HTTP $status. Try again after checking the connection."
            })
            return connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        } catch (error: ProbeFailure) {
            throw error
        } catch (_: Exception) {
            // Do not surface arbitrary response bodies, tokens, URLs or workbook content.
            throw ProbeFailure("Connection interrupted or response unreadable. A write may still have completed.")
        } finally {
            connection.disconnect()
        }
    }
}

private fun encoded(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
private fun quoted(value: String) = "'${value.replace("'", "''")}'"
private fun JSONArray.list(): List<Any?> = (0 until length()).map { opt(it).takeUnless { v -> v == JSONObject.NULL } }
private fun JSONObject.array(key: String) = optJSONArray(key) ?: JSONArray()

data class PendingProbe(val workbookId: String, val sheetId: Int, val title: String, val runId: String)

/** save must finish durably before returning; only non-secret recovery identifiers are stored. */
interface ProbeJournal {
    fun read(): PendingProbe?
    fun save(probe: PendingProbe)
    fun clear()
}

enum class Cleanup { NOT_NEEDED, REMOVED, NOT_OBSERVED, NEEDS_ATTENTION }
data class CheckReport(
    val readPassed: Boolean = false,
    val workoutRows: Int = 0,
    val writePassed: Boolean = false,
    val cleanup: Cleanup = Cleanup.NOT_NEEDED,
    val error: String? = null,
    val recoveryTitle: String? = null,
) {
    val passed: Boolean get() = error == null && cleanup in setOf(Cleanup.NOT_NEEDED, Cleanup.REMOVED)
}

class WorkbookContract(private val schema: JSONObject) {
    companion object {
        fun bundled(): WorkbookContract = WorkbookContract(JSONObject(
            requireNotNull(WorkbookContract::class.java.getResourceAsStream("/sheets-schema.json"))
                .bufferedReader().use { it.readText() }
        ))
    }

    fun validate(transport: SheetsTransport, id: String): Int {
        fun values(range: String) = transport.request("GET", "$id/values/${encoded(range)}?valueRenderOption=UNFORMATTED_VALUE")
            .array("values")
        val version = values(schema.getString("version_cell")).optJSONArray(0)?.opt(0)?.toString()
        if (version != schema.getString("schema_version")) throw ProbeFailure("Workbook schema version is missing or unsupported. Use the ET-0 workbook.")
        val tables = schema.getJSONObject("tables")
        var count = 0
        for (name in tables.keys()) {
            val grid = values(if (name == "WorkoutPlan") quoted(name) else "${quoted(name)}!1:1")
            val headers = grid.optJSONArray(0)?.list()?.map { it?.toString()?.trim().orEmpty() }.orEmpty()
            val required = tables.getJSONObject(name).getJSONArray("headers").list().map { it.toString() }
            if (headers.filter { it.isNotEmpty() }.distinct().size != headers.count { it.isNotEmpty() } || !headers.containsAll(required)) {
                throw ProbeFailure("$name has missing or duplicate column headings.")
            }
            if (name != "WorkoutPlan") continue
            val ids = mutableSetOf<String>()
            val contexts = mutableMapOf<String, List<Any?>>()
            for (index in 1 until grid.length()) {
                val row = grid.getJSONArray(index).list()
                if (row.all { it == null || it == "" }) continue
                fun cell(header: String) = row.getOrNull(headers.indexOf(header))
                fun invalid(field: String): Nothing = throw ProbeFailure("WorkoutPlan row ${index + 1}: check $field.")
                for (field in listOf("Person", "Workout", "Exercise", "Plan ID", "Workout ID", "Exercise ID")) {
                    if ((cell(field) as? String).isNullOrBlank()) invalid(field)
                }
                for (field in listOf("Plan ID", "Workout ID", "Exercise ID")) {
                    if (!Regex("[A-Za-z0-9_-]+").matches(cell(field).toString())) invalid(field)
                }
                if (!ids.add(cell("Plan ID").toString())) invalid("duplicate Plan ID")
                for (field in listOf("Starting weight (kg)", "Default increase (kg)")) {
                    val number = cell(field) as? Number ?: invalid(field)
                    if (!number.toDouble().isFinite() || number.toDouble() < 0) invalid(field)
                }
                for (field in listOf("Working sets", "Target reps")) {
                    val number = (cell(field) as? Number)?.toDouble() ?: invalid(field)
                    if (!number.isFinite() || number < 1 || number % 1.0 != 0.0) invalid(field)
                }
                if (cell("Active") !is Boolean) invalid("Active checkbox")
                val fields = mapOf("Weight basis" to "weight_basis", "Equipment" to "equipment", "Repetition mode" to "repetition_mode", "Progression" to "progression")
                for ((header, key) in fields) {
                    if (cell(header) !in schema.getJSONObject("enums").getJSONArray(key).list()) invalid(header)
                }
                val countValue = cell("Equipment count")
                val equipmentCount = (countValue as? Number)?.toDouble()
                if (countValue != null && countValue != "" && (equipmentCount == null || !equipmentCount.isFinite() || equipmentCount < 0 || equipmentCount % 1.0 != 0.0)) invalid("Equipment count")
                if (cell("Weight basis") == "Per implement" && (equipmentCount == null || equipmentCount < 1)) invalid("Equipment count")
                if (cell("Equipment") == "Barbell" && cell("Weight basis") != "Combined") invalid("barbell combined weight")
                if (cell("Weight basis") == "Bodyweight" && ((cell("Starting weight (kg)") as Number).toDouble() != 0.0 || cell("Progression") != "Manual" || cell("Equipment") != "Bodyweight")) invalid("bodyweight settings")
                if (cell("Progression") == "3 x 12" && ((cell("Working sets") as Number).toInt() != 3 || (cell("Target reps") as Number).toInt() != 12 || (cell("Default increase (kg)") as Number).toDouble() <= 0)) invalid("3 x 12 settings")
                val context = listOf(cell("Equipment"), cell("Weight basis"), cell("Repetition mode"))
                val previous = contexts.put(cell("Exercise ID").toString(), context)
                if (previous != null && previous != context) invalid("Exercise ID used with different equipment or load conventions")
                count++
            }
        }
        if (count == 0) throw ProbeFailure("WorkoutPlan has no exercise rows.")
        return count
    }
}

class SheetsProbe(
    private val transport: SheetsTransport,
    private val journal: ProbeJournal,
    private val contract: WorkbookContract = WorkbookContract.bundled(),
) {
    private fun metadata(id: String): List<JSONObject> = transport.request("GET", "$id?fields=${encoded("sheets(properties(sheetId,title))")}")
        .array("sheets").list().map { (it as JSONObject).getJSONObject("properties") }

    private fun values(id: String, range: String) = transport.request("GET", "$id/values/${encoded(range)}?valueRenderOption=UNFORMATTED_VALUE").array("values")

    fun run(input: String, write: Boolean): CheckReport {
        var report = CheckReport()
        var pending: PendingProbe? = null
        try {
            val id = spreadsheetId(input)
            if (write && journal.read() != null) throw ProbeFailure("Resolve the earlier temporary-tab check before starting another write.")
            val sheets = metadata(id)
            val rows = contract.validate(transport, id)
            report = report.copy(readPassed = true, workoutRows = rows)
            if (!write) return report
            val run = UUID.randomUUID().toString()
            val probe = PendingProbe(id, (1..Int.MAX_VALUE).random(), "ET0_probe_$run", run)
            if (sheets.any { it.getInt("sheetId") == probe.sheetId || it.getString("title") == probe.title }) throw ProbeFailure("Temporary tab identity collision. No write attempted.")
            journal.save(probe)
            pending = probe
            val data = testValues(run)
            transport.request("POST", "$id:batchUpdate", JSONObject().put("requests", JSONArray()
                .put(JSONObject().put("addSheet", JSONObject().put("properties", JSONObject().put("sheetId", probe.sheetId).put("title", probe.title))))
                .put(JSONObject().put("updateCells", JSONObject()
                    .put("start", JSONObject().put("sheetId", probe.sheetId).put("rowIndex", 0).put("columnIndex", 0))
                    .put("rows", JSONArray(data.map { row -> JSONObject().put("values", JSONArray(row.map { value ->
                        JSONObject().put("userEnteredValue", JSONObject().put(if (value is Number) "numberValue" else "stringValue", value))
                    })) }))
                    .put("fields", "userEnteredValue")))))
            val back = values(id, "${quoted(probe.title)}!A1:F5")
            if (!matches(back, data)) throw ProbeFailure("Temporary results did not match, including numeric weights and left/right sides.")
            report = report.copy(writePassed = true)
        } catch (error: Exception) {
            report = report.copy(error = safeError(error))
        } finally {
            if (pending != null) {
                val cleanup = recover(pending)
                report = report.copy(cleanup = cleanup.cleanup, recoveryTitle = cleanup.recoveryTitle,
                    error = report.error ?: cleanup.error)
            }
        }
        return report
    }

    fun recover(probe: PendingProbe = journal.read() ?: throw ProbeFailure("No temporary tab needs recovery.")): CheckReport {
        return try {
            val sheets = metadata(probe.workbookId)
            val owned = sheets.find { it.getInt("sheetId") == probe.sheetId && it.getString("title") == probe.title }
            if (owned == null) {
                // The original POST could still be in flight. Keep the durable journal.
                CheckReport(cleanup = Cleanup.NOT_OBSERVED, recoveryTitle = probe.title,
                    error = "Temporary tab not observed. Inspect the test workbook for ${probe.title} before clearing the recovery record.")
            } else {
                val marker = values(probe.workbookId, "${quoted(probe.title)}!A2:B2")
                if (!matches(marker, listOf(listOf("TEST ONLY", probe.runId)))) throw ProbeFailure("Temporary tab ownership could not be verified; nothing was deleted.")
                transport.request("POST", "${probe.workbookId}:batchUpdate", JSONObject().put("requests", JSONArray()
                    .put(JSONObject().put("deleteSheet", JSONObject().put("sheetId", probe.sheetId)))))
                if (metadata(probe.workbookId).any { it.getInt("sheetId") == probe.sheetId }) throw ProbeFailure("Temporary tab deletion was not confirmed.")
                journal.clear()
                CheckReport(cleanup = Cleanup.REMOVED)
            }
        } catch (error: Exception) {
            CheckReport(cleanup = Cleanup.NEEDS_ATTENTION, recoveryTitle = probe.title,
                error = "Cleanup needs attention. ${safeError(error)} Check ${probe.title} in the test workbook.")
        }
    }

    private fun safeError(error: Exception) = if (error is ProbeFailure) error.message else "The connection check could not finish. Try again after checking Google access."

    private fun matches(actual: JSONArray, expected: List<List<Any>>): Boolean {
        if (actual.length() != expected.size) return false
        return expected.indices.all { rowIndex ->
            val row = actual.optJSONArray(rowIndex) ?: return@all false
            row.length() == expected[rowIndex].size && expected[rowIndex].indices.all { column ->
                val a = row.opt(column)
                val e = expected[rowIndex][column]
                if (e is Number) a is Number && a.toDouble() == e.toDouble() else a == e
            }
        }
    }

    private fun testValues(run: String): List<List<Any>> = listOf(
        listOf("TEST ONLY", "Run ID", "Person ID", "Side", "Weight (kg)", "Reps"),
        listOf("TEST ONLY", run, "example_a", "Left", 6, 12),
        listOf("TEST ONLY", run, "example_a", "Right", 6, 11),
        listOf("TEST ONLY", run, "example_b", "Left", 3, 12),
        listOf("TEST ONLY", run, "example_b", "Right", 3, 12),
    )
}
