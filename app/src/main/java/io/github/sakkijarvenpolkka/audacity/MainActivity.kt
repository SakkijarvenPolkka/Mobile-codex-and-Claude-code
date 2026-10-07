/*
 * Audacity Android port — the single activity (edge-to-edge). The engine
 * lives in AudacityApp; dialogs and flows live in AppViewModel. This class
 * only fulfils requests that need an Activity: SAF pickers, the record
 * permission, and links.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.net.toUri
import io.github.sakkijarvenpolkka.audacity.editor.AudacityTheme
import io.github.sakkijarvenpolkka.audacity.ui.AppRoot

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) vm.handleIntent(intent)
        setContent {
            val prefs by vm.uiPrefs.state.collectAsState()
            AudacityTheme(prefs.theme) {
                HostRequests(vm)
                AppRoot(vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        vm.handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        vm.onResume()
    }

    /** ACTION_CREATE_DOCUMENT with a MIME type chosen per request. */
    private class CreateDocumentContract : ActivityResultContract<Pair<String, String>, Uri?>() {
        override fun createIntent(context: Context, input: Pair<String, String>): Intent =
            Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(input.second)
                .putExtra(Intent.EXTRA_TITLE, input.first)

        override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
            if (resultCode == RESULT_OK) intent?.data else null
    }

    @Composable
    private fun HostRequests(vm: AppViewModel) {
        val openMany = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            vm.onDocumentsPicked(vm.pendingOpenPurpose, uris)
        }
        val openOne = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            vm.onDocumentsPicked(vm.pendingOpenPurpose, listOfNotNull(uri))
        }
        val create = rememberLauncherForActivityResult(CreateDocumentContract()) { uri ->
            val purpose = vm.pendingCreatePurpose
            vm.pendingCreatePurpose = null
            if (purpose != null) vm.onDocumentCreated(purpose, uri)
        }
        val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            vm.onRecordPermissionResult(granted, vm.pendingRecordNewTrack)
        }
        LaunchedEffect(vm) {
            vm.requests.collect { r ->
                try {
                    when (r) {
                        is HostRequest.OpenDocuments -> {
                            vm.pendingOpenPurpose = r.purpose
                            if (r.multiple) openMany.launch(r.mimeTypes.toTypedArray()) else openOne.launch(r.mimeTypes.toTypedArray())
                        }
                        is HostRequest.CreateDocument -> {
                            vm.pendingCreatePurpose = r.purpose
                            create.launch(r.suggestedName to r.mimeType)
                        }
                        is HostRequest.RecordPermission -> {
                            vm.pendingRecordNewTrack = r.newTrack
                            permission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                        is HostRequest.OpenUrl -> startActivity(Intent(Intent.ACTION_VIEW, r.url.toUri()))
                        HostRequest.AppLanguageSettings -> if (Build.VERSION.SDK_INT >= 33) {
                            startActivity(Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", packageName, null)))
                        }
                    }
                } catch (e: ActivityNotFoundException) {
                    vm.message(R.string.msg_no_app_for_action)
                }
            }
        }
    }
}
