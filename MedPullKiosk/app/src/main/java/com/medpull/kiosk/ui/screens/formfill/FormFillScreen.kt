package com.medpull.kiosk.ui.screens.formfill

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import com.medpull.kiosk.R
import com.medpull.kiosk.data.models.FieldType
import com.medpull.kiosk.data.models.FormField
import com.medpull.kiosk.ui.components.ActivityTracker
import com.medpull.kiosk.ui.components.InteractivePdfViewer
import com.medpull.kiosk.ui.screens.ai.AiChatDialog
import com.medpull.kiosk.utils.SessionManager
import java.io.File

/**
 * Form fill screen — full-screen interactive PDF viewer with translation overlays.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormFillScreen(
    sessionManager: SessionManager,
    onNavigateBack: () -> Unit,
    onExport: (String) -> Unit = {},
    viewModel: FormFillViewModel = hiltViewModel()
) {
    ActivityTracker(sessionManager = sessionManager)

    val state by viewModel.state.collectAsState()
    var showAiChat by remember { mutableStateOf(false) }

    // Zoom/pan state — owned here so both gestures and buttons can modify it
    var userScale by remember(state.currentPage) { mutableFloatStateOf(1f) }
    var userOffsetX by remember(state.currentPage) { mutableFloatStateOf(0f) }
    var userOffsetY by remember(state.currentPage) { mutableFloatStateOf(0f) }

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
                    // Full-screen interactive PDF viewer
                    InteractivePdfViewer(
                        pdfFile = File(state.form!!.originalFileUri),
                        currentPage = state.currentPage,
                        fields = state.fields,
                        showOverlays = state.showFieldOverlays,
                        userScale = userScale,
                        userOffsetX = userOffsetX,
                        userOffsetY = userOffsetY,
                        onTransformChanged = { scale, offsetX, offsetY ->
                            userScale = scale
                            userOffsetX = offsetX
                            userOffsetY = offsetY
                        },
                        onFieldClick = { field ->
                            // Only open dialog for non-checkbox fields
                            if (field.fieldType != FieldType.CHECKBOX) {
                                viewModel.selectField(field)
                            }
                        },
                        onCheckboxToggle = { field ->
                            val current = field.value
                            val newValue = if (current == "true" || current == "checked") "" else "true"
                            viewModel.updateFieldValue(field.id, newValue)
                        },
                        onPageCountLoaded = { count ->
                            viewModel.setTotalPages(count)
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    // Zoom +/- buttons (bottom-end)
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 16.dp, bottom = 80.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SmallFloatingActionButton(
                            onClick = { userScale = (userScale * 1.5f).coerceAtMost(5f) },
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Zoom in")
                        }
                        SmallFloatingActionButton(
                            onClick = { userScale = (userScale / 1.5f).coerceAtLeast(0.5f) },
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Icon(Icons.Default.Remove, contentDescription = "Zoom out")
                        }
                    }

                    // Page navigation controls (only for multi-page PDFs)
                    if (state.totalPages > 1) {
                        PageNavigationBar(
                            currentPage = state.currentPage,
                            totalPages = state.totalPages,
                            onPreviousPage = { viewModel.setCurrentPage(state.currentPage - 1) },
                            onNextPage = { viewModel.setCurrentPage(state.currentPage + 1) },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 16.dp)
                        )
                    }
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

    // Field input dialog (non-checkbox fields only)
    state.selectedField?.let { field ->
        if (field.fieldType != FieldType.CHECKBOX) {
            FieldInputDialog(
                field = field,
                onDismiss = { viewModel.clearFieldSelection() },
                onSave = { value ->
                    viewModel.updateFieldValue(field.id, value)
                }
            )
        }
    }

    // AI Chat Dialog
    if (showAiChat) {
        AiChatDialog(
            onDismiss = { showAiChat = false },
            language = state.userLanguage,
            formContext = state.form?.fileName,
            formFields = state.fields
        )
    }
}

@Composable
private fun PageNavigationBar(
    currentPage: Int,
    totalPages: Int,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 4.dp,
        shadowElevation = 4.dp
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            IconButton(
                onClick = onPreviousPage,
                enabled = currentPage > 0
            ) {
                Icon(Icons.Default.ChevronLeft, contentDescription = "Previous page")
            }

            Text(
                text = "Page ${currentPage + 1} of $totalPages",
                style = MaterialTheme.typography.bodyMedium
            )

            IconButton(
                onClick = onNextPage,
                enabled = currentPage < totalPages - 1
            ) {
                Icon(Icons.Default.ChevronRight, contentDescription = "Next page")
            }
        }
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
