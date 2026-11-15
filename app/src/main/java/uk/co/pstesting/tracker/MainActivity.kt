package uk.co.pstesting.tracker

import android.accounts.Account
import android.accounts.AccountManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.AccountPicker
import com.google.android.gms.common.api.Scope

private const val SHEETS_SCOPE = "https://www.googleapis.com/auth/spreadsheets"

class MainActivity : ComponentActivity() {
    private val model: ConnectionViewModel by viewModels()

    private val chooseAccount = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val name = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
        if (result.resultCode == RESULT_OK && !name.isNullOrBlank()) model.selectAccount(name)
        else model.authorisationFailed("Account selection cancelled. You can choose an account when ready.")
    }

    private val consent = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode != RESULT_OK) {
            model.authorisationFailed("Google access was not granted. No connection check was started.")
        } else {
            try { accept(Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(result.data)) }
            catch (_: Exception) { model.authorisationFailed("Google access could not be completed. Check the app's OAuth setup and try again.") }
        }
    }

    private fun accept(result: AuthorizationResult) {
        val token = result.accessToken
        if (token.isNullOrBlank() || result.grantedScopes.none { it.scopeUri == SHEETS_SCOPE }) {
            model.authorisationFailed("Spreadsheet access was not granted. Select the check again when ready.")
        } else model.authorised(token)
    }

    private fun check(action: CheckAction) {
        if (!model.prepare(action)) return
        val request = AuthorizationRequest.builder()
            .setAccount(Account(requireNotNull(model.accountName), "com.google"))
            .setRequestedScopes(listOf(Scope(SHEETS_SCOPE)))
            .build()
        Identity.getAuthorizationClient(this).authorize(request)
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    val intent = result.pendingIntent
                    if (intent == null) model.authorisationFailed("Google consent could not be opened. Try again.")
                    else try { consent.launch(IntentSenderRequest.Builder(intent.intentSender).build()) }
                    catch (_: Exception) { model.authorisationFailed("Google consent could not be opened. Try again.") }
                } else accept(result)
            }
            .addOnFailureListener { model.authorisationFailed("Google access failed. Check the package name, signing SHA-1, test users and Sheets API setup.") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var confirmWrite by remember { mutableStateOf(false) }
                var confirmClear by remember { mutableStateOf(false) }
                Scaffold { insets ->
                    Surface(Modifier.fillMaxSize().padding(insets)) {
                        Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text("Tracker App", style = MaterialTheme.typography.headlineLarge)
                            Text("Google Sheets connection", style = MaterialTheme.typography.titleLarge)
                            Text("ET-0 prototype · Workout logging follows in the next increments.")
                            Text("Use the ET-0 test copy. Google will ask to view and edit spreadsheets your selected account can access.")
                            OutlinedTextField(value = model.workbook, onValueChange = model::updateWorkbook,
                                label = { Text("Test workbook link or ID") }, enabled = !model.busy, modifier = Modifier.fillMaxWidth())
                            Text(model.accountName?.let { "Google account: $it" } ?: "No Google account selected")
                            OutlinedButton(onClick = {
                                try {
                                    chooseAccount.launch(AccountPicker.newChooseAccountIntent(AccountPicker.AccountChooserOptions.Builder()
                                        .setAllowableAccountsTypes(listOf("com.google"))
                                        .setAlwaysShowAccountPicker(true).build()))
                                } catch (_: Exception) { model.authorisationFailed("Google account selection is unavailable. Check Google Play services on this phone.") }
                            }, enabled = !model.busy) { Text("Choose Google account") }
                            Button(onClick = { check(CheckAction.READ) }, enabled = !model.busy && model.accountName != null) { Text("Check read access") }
                            Button(onClick = { confirmWrite = true }, enabled = !model.busy && model.accountName != null && model.pending == null && !model.recoveryBlocked) {
                                Text("Check temporary write and cleanup")
                            }
                            if (model.pending != null) {
                                Text("An earlier temporary-tab check needs attention: ${model.pending?.title}")
                                Text("Recovery uses the workbook from that check, even if the link above has changed.")
                                OutlinedButton(onClick = { check(CheckAction.RECOVER) }, enabled = !model.busy && model.accountName != null) { Text("Recover temporary tab") }
                            }
                            if (model.canClearRecovery) {
                                TextButton(onClick = { confirmClear = true }, enabled = !model.busy) { Text("I have inspected the workbook") }
                            }
                            if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(model.status)
                            Text("Results stay on this phone. Google access tokens are used only for the current check.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (confirmWrite) AlertDialog(onDismissRequest = { confirmWrite = false },
                    title = { Text("Test this workbook?") },
                    text = { Text("This creates one temporary tab, checks example left/right results, then deletes that tab. Workout history is not changed. Confirm that the link points to your test copy.") },
                    confirmButton = { TextButton(onClick = { confirmWrite = false; check(CheckAction.WRITE) }) { Text("Run check") } },
                    dismissButton = { TextButton(onClick = { confirmWrite = false }) { Text("Cancel") } })
                if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
                    title = { Text("Clear the recovery record?") },
                    text = { Text("Only continue after checking that ${model.pending?.title} is absent from its test workbook. Clearing this record cannot cancel a write still in progress.") },
                    confirmButton = { TextButton(onClick = { confirmClear = false; model.clearRecoveryAfterInspection() }) { Text("Clear record") } },
                    dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } })
            }
        }
    }
}
