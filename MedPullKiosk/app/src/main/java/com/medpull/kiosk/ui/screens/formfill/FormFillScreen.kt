package com.medpull.kiosk.ui.screens.formfill

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import com.medpull.kiosk.R
import com.medpull.kiosk.data.models.FieldType
import com.medpull.kiosk.data.models.FormField
import com.medpull.kiosk.ui.components.ActivityTracker
import com.medpull.kiosk.ui.components.SimplePdfViewer
import com.medpull.kiosk.ui.screens.ai.AiChatDialog
import com.medpull.kiosk.utils.SessionManager
import java.io.File

/**
 * Form fill screen with PDF viewer and field overlays
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormFillScreen(
    sessionManager: SessionManager,
    onNavigateBack: () -> Unit,
    onExport: (String) -> Unit = {},
    viewModel: FormFillViewModel = hiltViewModel()
) {
    // Track activity for session management
    ActivityTracker(sessionManager = sessionManager)

    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var showAiChat by remember { mutableStateOf(false) }

    // Handle navigation back
    LaunchedEffect(state.shouldNavigateBack) {
        if (state.shouldNavigateBack) {
            onNavigateBack()
            viewModel.resetNavigation()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = state.form?.fileName ?: "Form",
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (state.fields.isNotEmpty()) {
                            Text(
                                text = "${state.completionPercentage.toInt()}% Complete",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { viewModel.saveAndExit() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Toggle field overlays
                    IconButton(onClick = { viewModel.toggleFieldOverlays() }) {
                        Icon(
                            imageVector = if (state.showFieldOverlays) {
                                Icons.Default.Visibility
                            } else {
                                Icons.Default.VisibilityOff
                            },
                            contentDescription = "Toggle overlays"
                        )
                    }

                    // Export button (if form is complete)
                    if (state.completionPercentage >= 100f) {
                        IconButton(onClick = { state.form?.let { onExport(it.id) } }) {
                            Icon(Icons.Default.FileDownload, contentDescription = "Export")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        floatingActionButton = {
            if (!state.isLoading && state.form != null) {
                FloatingActionButton(
                    onClick = { showAiChat = true },
                    containerColor = MaterialTheme.colorScheme.tertiary
                ) {
                    Icon(
                        imageVector = Icons.Default.SmartToy,
                        contentDescription = stringResource(R.string.ai_copilot)
                    )
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                state.isLoading -> {
                    LoadingState()
                }
                state.form == null -> {
                    ErrorState(message = state.error ?: "Form not found")
                }
                else -> {
                    FormContent(
                        pdfFile = File(state.form!!.originalFileUri),
                        fields = state.fields,
                        currentPage = state.currentPage,
                        showFieldOverlays = state.showFieldOverlays,
                        onFieldClick = { viewModel.selectField(it) }
                    )
                }
            }

            // Progress indicator at bottom
            if (!state.isLoading && state.form != null) {
                LinearProgressIndicator(
                    progress = { state.completionPercentage / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Error snackbar
            state.error?.let { error ->
                Snackbar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    action = {
                        TextButton(onClick = { viewModel.clearError() }) {
                            Text(stringResource(R.string.ok))
                        }
                    }
                ) {
                    Text(error)
                }
            }
        }
    }

    // Field input dialog
    state.selectedField?.let { field ->
        FieldInputDialog(
            field = field,
            onDismiss = { viewModel.clearFieldSelection() },
            onSave = { value ->
                viewModel.updateFieldValue(field.id, value)
            }
        )
    }

    // AI Chat Dialog
    if (showAiChat) {
        AiChatDialog(
            onDismiss = { showAiChat = false },
            language = "en", // TODO: Get from user preferences
            formContext = state.form?.fileName
        )
    }
}

@Composable
private fun LoadingState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.loading),
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
private fun ErrorState(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun FormContent(
    pdfFile: File,
    fields: List<FormField>,
    currentPage: Int,
    showFieldOverlays: Boolean,
    onFieldClick: (FormField) -> Unit
) {
    Row(modifier = Modifier.fillMaxSize()) {
        // PDF Viewer (70% width)
        Box(
            modifier = Modifier
                .weight(0.7f)
                .fillMaxHeight()
                .padding(8.dp)
        ) {
            SimplePdfViewer(
                pdfFile = pdfFile,
                page = currentPage,
                modifier = Modifier.fillMaxSize()
            )

            // Field overlays (disabled for now due to coordinate mapping complexity)
            // Will be enhanced in future iteration
        }

        // Fields List (30% width)
        Card(
            modifier = Modifier
                .weight(0.3f)
                .fillMaxHeight()
                .padding(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.form_fields_label),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Divider()

                Spacer(modifier = Modifier.height(8.dp))

                if (fields.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No fields detected",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                } else {
                    FieldsList(
                        fields = fields,
                        onFieldClick = onFieldClick
                    )
                }
            }
        }
    }
}

@Composable
private fun FieldsList(
    fields: List<FormField>,
    onFieldClick: (FormField) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(fields, key = { it.id }) { field ->
            FieldCard(
                field = field,
                onClick = { onFieldClick(field) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FieldCard(
    field: FormField,
    onClick: () -> Unit
) {
    val isFilled = !field.value.isNullOrBlank()

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isFilled) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status indicator
            Icon(
                imageVector = if (isFilled) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (isFilled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Field info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = field.translatedText ?: field.fieldName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2
                )
                if (field.value != null) {
                    Text(
                        text = field.value!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        maxLines = 1
                    )
                }
            }

            // Edit icon
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = "Edit",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun FieldInputDialog(
    field: FormField,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var value by remember { mutableStateOf(field.value ?: "") }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Text(
                    text = field.translatedText ?: field.fieldName,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                if (field.originalText != null && field.translatedText != null) {
                    Text(
                        text = field.originalText!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                }

                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.enter_value)) },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = when (field.fieldType) {
                            FieldType.NUMBER -> KeyboardType.Number
                            FieldType.DATE -> KeyboardType.Number
                            else -> KeyboardType.Text
                        }
                    ),
                    singleLine = field.fieldType != FieldType.TEXT
                )

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel))
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(onClick = { onSave(value) }) {
                        Text(stringResource(R.string.save))
                    }
                }
            }
        }
    }
}
