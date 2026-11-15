package uk.co.pstesting.tracker

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import uk.co.pstesting.tracker.sheets.CheckReport
import uk.co.pstesting.tracker.sheets.Cleanup
import uk.co.pstesting.tracker.sheets.GoogleSheetsTransport
import uk.co.pstesting.tracker.sheets.PendingProbe
import uk.co.pstesting.tracker.sheets.ProbeFailure
import uk.co.pstesting.tracker.sheets.ProbeJournal
import uk.co.pstesting.tracker.sheets.SheetsProbe
import uk.co.pstesting.tracker.sheets.spreadsheetId

enum class CheckAction { READ, WRITE, RECOVER }

class ConnectionViewModel(app: Application, private val state: SavedStateHandle) : AndroidViewModel(app) {
    private val preferences = app.getSharedPreferences("et0_connection", 0)
    private val journal = object : ProbeJournal {
        override fun read(): PendingProbe? {
            val value = preferences.getString("pending_probe", null) ?: return null
            return try {
                val json = JSONObject(value)
                PendingProbe(json.getString("workbookId"), json.getInt("sheetId"), json.getString("title"), json.getString("runId"))
            } catch (_: Exception) {
                throw ProbeFailure("The recovery record is unreadable. Inspect the test workbook before clearing app storage.")
            }
        }
        override fun save(probe: PendingProbe) {
            val json = JSONObject().put("workbookId", probe.workbookId).put("sheetId", probe.sheetId)
                .put("title", probe.title).put("runId", probe.runId)
            if (!preferences.edit().putString("pending_probe", json.toString()).commit()) {
                throw ProbeFailure("Could not save the recovery record. No write was attempted.")
            }
        }
        override fun clear() {
            if (!preferences.edit().remove("pending_probe").commit()) throw ProbeFailure("Could not clear the recovery record.")
        }
    }

    var workbook by mutableStateOf(state.get<String>("workbook") ?: preferences.getString("workbook", "").orEmpty())
        private set
    var accountName by mutableStateOf(state.get<String>("account"))
        private set
    var busy by mutableStateOf(false)
        private set
    var status by mutableStateOf("Choose a Google account and paste the ET-0 test workbook link.")
        private set
    var pending by mutableStateOf<PendingProbe?>(null)
        private set
    var recoveryBlocked by mutableStateOf(false)
        private set
    var canClearRecovery by mutableStateOf(false)
        private set

    init { refreshRecovery() }

    fun updateWorkbook(value: String) {
        workbook = value
        state["workbook"] = value
        preferences.edit().putString("workbook", value).apply()
    }

    fun selectAccount(name: String) {
        accountName = name
        state["account"] = name
        status = "Google account selected. Choose a connection check."
    }

    fun prepare(action: CheckAction): Boolean {
        if (busy) return false
        if (accountName == null) { status = "Choose a Google account first."; return false }
        try {
            if (action != CheckAction.RECOVER) spreadsheetId(workbook)
            if (action == CheckAction.WRITE && (pending != null || recoveryBlocked)) throw ProbeFailure("Resolve the earlier temporary-tab check first.")
            if (action == CheckAction.RECOVER && pending == null) throw ProbeFailure("No temporary tab needs recovery.")
        } catch (error: ProbeFailure) {
            status = error.message.orEmpty()
            return false
        }
        state["action"] = action.name
        busy = true
        status = "Requesting Google access…"
        return true
    }

    fun authorisationFailed(message: String) {
        busy = false
        state.remove<String>("action")
        status = message
    }

    fun authorised(token: String) {
        val action = state.get<String>("action")?.let { runCatching { CheckAction.valueOf(it) }.getOrNull() }
        state.remove<String>("action")
        if (action == null) { authorisationFailed("Google access returned without an active check. Select the check again."); return }
        val input = workbook
        busy = true
        status = "Checking the test workbook…"
        viewModelScope.launch {
            try {
                val report = withContext(Dispatchers.IO) {
                    val probe = SheetsProbe(GoogleSheetsTransport(token), journal)
                    if (action == CheckAction.RECOVER) probe.recover() else probe.run(input, action == CheckAction.WRITE)
                }
                showReport(report, action)
            } catch (_: Exception) {
                status = "The check was interrupted. Inspect the recovery status before trying another write."
            } finally {
                busy = false
                refreshRecovery()
            }
        }
    }

    private fun showReport(report: CheckReport, action: CheckAction) {
        canClearRecovery = report.cleanup == Cleanup.NOT_OBSERVED
        status = buildList {
            if (report.readPassed) add("Read passed: ${report.workoutRows} exercise entries validated.")
            if (report.writePassed) add("Write/read passed: numeric weights and both sides matched.")
            if (report.cleanup == Cleanup.REMOVED) add("Temporary tab removed and deletion confirmed.")
            if (report.error != null) add(report.error)
            else if (action == CheckAction.READ) add("No cells were changed.")
        }.joinToString("\n\n")
    }

    fun clearRecoveryAfterInspection() {
        if (!canClearRecovery || busy) return
        try {
            journal.clear()
            canClearRecovery = false
            status = "Recovery record cleared after your workbook inspection. You can run a new check."
            refreshRecovery()
        } catch (error: ProbeFailure) { status = error.message.orEmpty() }
    }

    private fun refreshRecovery() {
        try {
            pending = journal.read()
            recoveryBlocked = false
        } catch (error: ProbeFailure) {
            recoveryBlocked = true
            status = error.message.orEmpty()
        }
    }
}
