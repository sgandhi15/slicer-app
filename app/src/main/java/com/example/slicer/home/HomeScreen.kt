package com.example.slicer.home

import com.example.slicer.core.BatchFileResult
import com.example.slicer.core.DEFAULT_OUTPUT_FOLDER_DISPLAY_NAME
import com.example.slicer.core.DEFAULT_OUTPUT_FOLDER_URI
import com.example.slicer.core.DEFAULT_QUALITY
import com.example.slicer.core.JPEG_MIME_TYPES
import com.example.slicer.core.ProcessingDecision
import com.example.slicer.core.SlicerScreen
import com.example.slicer.storage.OutputFolderAccess
import com.example.slicer.storage.clearOutputFolderUri
import com.example.slicer.storage.loadBitmapForPreview
import com.example.slicer.storage.loadSavedOutputFolderUri
import com.example.slicer.storage.loadSavedQuality
import com.example.slicer.storage.markWelcomeScreenSeen
import com.example.slicer.storage.outputFolderAccess
import com.example.slicer.storage.readableName
import com.example.slicer.storage.saveOutputFolderUri
import com.example.slicer.storage.saveQuality
import com.example.slicer.storage.hasSeenWelcomeScreen
import com.example.slicer.telemetry.logEncoderRuntimeStatus

import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.example.slicer.ui.theme.SlicerButton as Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import com.example.slicer.ui.theme.SlicerOutlinedButton as OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.compose.collectAsLazyPagingItems
import com.example.slicer.ui.theme.SlicerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@Composable
fun SlicerHomeScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val homeViewModel: HomeViewModel = viewModel()
    val durableState by homeViewModel.uiState.collectAsStateWithLifecycle()
    val selectionState by homeViewModel.selectionState.collectAsStateWithLifecycle()
    val selectedImages = selectionState.selectedImages
    var outputFolder by remember { mutableStateOf(DEFAULT_OUTPUT_FOLDER_URI) }
    var quality by rememberSaveable { mutableIntStateOf(DEFAULT_QUALITY) }
    var status by rememberSaveable { mutableStateOf<String?>(null) }
    var didLoadPreferences by rememberSaveable { mutableStateOf(false) }
    var currentScreen by remember {
        mutableStateOf(
            if (context.hasSeenWelcomeScreen()) {
                SlicerScreen.Setup
            } else {
                SlicerScreen.Welcome
            }
        )
    }

    var comparePosition by rememberSaveable { mutableFloatStateOf(0.5f) }
    var compareSourceBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var compareOutputBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var compareOriginalImage by remember { mutableStateOf<ImageBitmap?>(null) }
    var compareCompressedImage by remember { mutableStateOf<ImageBitmap?>(null) }
    var isCompareLoading by remember { mutableStateOf(false) }
    var compareError by remember { mutableStateOf<String?>(null) }
    var selectedResultForComparison by remember { mutableStateOf<BatchFileResult?>(null) }
    var notificationMessage by remember { mutableStateOf<String?>(null) }
    var isNotificationVisible by remember { mutableStateOf(false) }

    val isProcessing = durableState.isProcessing || durableState.isStarting
    val currentProgress = durableState.progress

    LaunchedEffect(notificationMessage, isNotificationVisible) {
        if (isNotificationVisible) {
            delay(5_000)
            isNotificationVisible = false
        }
    }

    LaunchedEffect(currentScreen, selectedResultForComparison) {
        isCompareLoading = false
        compareOriginalImage = null
        compareCompressedImage = null
        compareError = null

        if (currentScreen != SlicerScreen.Compare) {
            compareSourceBitmap?.recycle()
            compareSourceBitmap = null
            compareOutputBitmap?.recycle()
            compareOutputBitmap = null
            return@LaunchedEffect
        }

        val result = selectedResultForComparison ?: run {
            isCompareLoading = false
            compareError = "No result selected for comparison."
            return@LaunchedEffect
        }

        isCompareLoading = true

        val oldCompareSourceBitmap = compareSourceBitmap
        val oldCompareOutputBitmap = compareOutputBitmap

        val originalBitmap = withContext(Dispatchers.IO) {
            context.loadBitmapForPreview(result.inputUri)
        }
        val compressedBitmap = withContext(Dispatchers.IO) {
            result.outputUri?.let { context.loadBitmapForPreview(it) }
        }

        if (!isActive) {
            originalBitmap?.recycle()
            compressedBitmap?.recycle()
            return@LaunchedEffect
        }

        oldCompareSourceBitmap?.takeIf { it != originalBitmap }?.recycle()
        oldCompareOutputBitmap?.takeIf { it != compressedBitmap }?.recycle()

        compareSourceBitmap = originalBitmap
        compareOutputBitmap = compressedBitmap
        compareOriginalImage = originalBitmap?.asImageBitmap()
        compareCompressedImage = compressedBitmap?.asImageBitmap()
        isCompareLoading = false

        compareError = when {
            originalBitmap == null ->
                "Original image access is no longer available after source cleanup."
            compressedBitmap == null -> "Could not load processed image."
            result.decision == ProcessingDecision.Failed -> "No comparison available for this result."
            else -> null
        }
    }

    LaunchedEffect(Unit) {
        quality = context.loadSavedQuality()
        val restoredOutputFolder = context.loadSavedOutputFolderUri()
        outputFolder = restoredOutputFolder
        status = null
        didLoadPreferences = true

        logEncoderRuntimeStatus()
    }

    LaunchedEffect(durableState.batchId, durableState.batchState, currentScreen, didLoadPreferences) {
        if (!didLoadPreferences || currentScreen == SlicerScreen.Welcome || currentScreen == SlicerScreen.Compare) {
            return@LaunchedEffect
        }
        when {
            durableState.isProcessing -> currentScreen = SlicerScreen.Processing
            durableState.needsDestination -> currentScreen = SlicerScreen.Processing
            durableState.isTerminal -> currentScreen = SlicerScreen.Results
        }
    }

    LaunchedEffect(durableState.error) {
        durableState.error?.let { error ->
            status = error
            currentScreen = SlicerScreen.Quality
            homeViewModel.clearError()
        }
    }

    val isCompareScreen = currentScreen == SlicerScreen.Compare
    val hasBottomDockedButton = currentScreen == SlicerScreen.Setup ||
        currentScreen == SlicerScreen.Quality ||
        currentScreen == SlicerScreen.Compare ||
        currentScreen == SlicerScreen.Processing ||
        currentScreen == SlicerScreen.Results
    val contentScrollState = rememberScrollState()

    val screenContentModifier = Modifier
        .fillMaxSize()
        .padding(horizontal = 20.dp, vertical = 20.dp)
        .let { base ->
            if (hasBottomDockedButton) base else base.verticalScroll(contentScrollState)
        }

    val contentArrangement = if (isCompareScreen) {
        Arrangement.SpaceBetween
    } else {
        Arrangement.spacedBy(16.dp)
    }

    LaunchedEffect(quality, didLoadPreferences) {
        if (didLoadPreferences) {
            context.saveQuality(quality)
        }
    }

    BackHandler(enabled = currentScreen == SlicerScreen.Quality) {
        currentScreen = SlicerScreen.Setup
    }

    val jpegPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        status = null
        homeViewModel.validateImageSelection(uris)
        homeViewModel.dismissCurrentBatch()
        selectedResultForComparison = null
        currentScreen = SlicerScreen.Setup
    }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val persistenceGranted = runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                true
            }.getOrNull() == true

            val folderAccess = context.outputFolderAccess(uri)

            if (persistenceGranted && folderAccess == OutputFolderAccess.Writable) {
                outputFolder = uri
                context.saveOutputFolderUri(uri)
                if (durableState.needsDestination) {
                    status = "Resuming batch with the new output folder..."
                    homeViewModel.replaceDestination(uri)
                } else {
                    status = "Output folder selected and saved."
                }
            } else if (persistenceGranted && folderAccess == OutputFolderAccess.TemporarilyUnavailable) {
                outputFolder = uri
                context.saveOutputFolderUri(uri)
                status = "Selected folder is temporarily unavailable. Try again when its provider is ready."
            } else {
                outputFolder = DEFAULT_OUTPUT_FOLDER_URI
                context.clearOutputFolderUri()
                status = if (folderAccess == OutputFolderAccess.PermissionMissing) {
                    "Could not use selected folder. Please choose another location."
                } else {
                    "Output folder selected for this session, but persistence could not be saved. " +
                        "Please reselect on next app open."
                }
            }
            selectedResultForComparison = null
            if (!durableState.needsDestination) homeViewModel.dismissCurrentBatch()
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }

    val canStart = selectedImages.isNotEmpty() && !selectionState.isValidating && !isProcessing

    fun startPersistedBatch(files: List<Uri>, folder: Uri, qualityValue: Int) {
        status = "Preparing batch..."
        currentScreen = SlicerScreen.Processing
        homeViewModel.startBatch(files, folder, qualityValue)
    }

    fun startProcessing() {
        selectedResultForComparison = null
        val folder = outputFolder
        val files = selectedImages.toList()
        if (files.isEmpty()) {
            status = "No images selected."
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        startPersistedBatch(files, folder, quality)
    }

    if (currentScreen == SlicerScreen.Welcome) {
        WelcomeScreen(
            onContinue = {
                context.markWelcomeScreenSeen()
                currentScreen = SlicerScreen.Setup
            }
        )
    } else {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {
                AppHeader()

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.background
            ) {
                Column(
                    modifier = screenContentModifier,
                    verticalArrangement = contentArrangement
                ) {
                    when (currentScreen) {
                SlicerScreen.Setup -> {
                    Column(
                        modifier = Modifier
                            .testTag("slicer-setup-screen")
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        BatchSetupContent(
                            selectedImages = selectedImages,
                            isValidating = selectionState.isValidating,
                            validatedCount = selectionState.validatedCount,
                            validationTotal = selectionState.totalCount,
                            controlsEnabled = !isProcessing,
                            onSelectImages = { jpegPicker.launch(JPEG_MIME_TYPES) },
                            onRemoveImage = { uri ->
                                homeViewModel.removeSelectedImage(uri)
                                selectedResultForComparison = null
                                status = null
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        )

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding(),
                            color = MaterialTheme.colorScheme.background
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = if (selectionState.isValidating) {
                                        "Validating ${selectionState.validatedCount} of ${selectionState.totalCount}"
                                    } else if (selectedImages.isEmpty()) {
                                        "Select up to $MAX_SELECTED_IMAGES images"
                                    } else if (selectedImages.size == 1) {
                                        "1 file selected"
                                    } else {
                                        "${selectedImages.size} files selected"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.align(Alignment.CenterHorizontally)
                                )
                                Button(
                                    onClick = { currentScreen = SlicerScreen.Quality },
                                    enabled = canStart,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(52.dp),
                                    shape = MaterialTheme.shapes.small
                                ) {
                                    Text("Continue to quality")
                                }
                            }
                        }
                    }
                }

                SlicerScreen.Quality -> {
                    Box(
                        modifier = Modifier
                            .testTag("slicer-quality-screen")
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState())
                                .padding(bottom = 76.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            QualityStepContent(
                                quality = quality,
                                controlsEnabled = !isProcessing,
                                onQualityChange = { quality = it }
                            )

                            DestinationSection(
                                outputFolder = outputFolder,
                                controlsEnabled = !isProcessing,
                                onSelectOutput = { folderPicker.launch(null) },
                                onForgetOutput = {
                                    outputFolder = DEFAULT_OUTPUT_FOLDER_URI
                                    context.clearOutputFolderUri()
                                    status = null
                                    notificationMessage =
                                        "Reset to $DEFAULT_OUTPUT_FOLDER_DISPLAY_NAME."
                                    isNotificationVisible = true
                                }
                            )

                            status?.let { NoticeText(message = it) }

                            Text(
                                text = "Images that cannot be made smaller are copied unchanged.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Surface(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .navigationBarsPadding(),
                            color = MaterialTheme.colorScheme.background
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                com.example.slicer.ui.theme.SlicerTextButton(
                                    onClick = { currentScreen = SlicerScreen.Setup },
                                    enabled = !isProcessing,
                                    modifier = Modifier
                                        .weight(0.7f)
                                        .height(52.dp)
                                ) {
                                    Text("Back")
                                }

                                Button(
                                    onClick = { startProcessing() },
                                    enabled = canStart,
                                    modifier = Modifier
                                        .weight(1.3f)
                                        .height(52.dp),
                                    shape = MaterialTheme.shapes.small
                                ) {
                                    Text("Start compression")
                                }
                            }
                        }

                    }
                }

                SlicerScreen.Compare -> {
                    Column(
                        modifier = Modifier
                            .testTag("slicer-compare-screen")
                            .fillMaxWidth()
                            .fillMaxHeight()
                            .weight(1f)
                    ) {
                    CompareScreen(
                        comparePosition = comparePosition,
                        beforeImage = compareOriginalImage,
                        afterImage = compareCompressedImage,
                        isGeneratingComparison = isCompareLoading,
                        onCompareChange = {
                                comparePosition = it
                            },
                            onBack = {
                                selectedResultForComparison = null
                                currentScreen = SlicerScreen.Results
                            },
                            controlsEnabled = !isProcessing,
                            notice = compareError
                        )
                    }
                }

                SlicerScreen.Processing -> {
                    Box(
                        modifier = Modifier
                            .testTag("slicer-processing-screen")
                            .fillMaxWidth()
                            .fillMaxHeight()
                    ) {
                        Column(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(bottom = 76.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            ProcessingPanel(progress = currentProgress)
                        }

                        Surface(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .navigationBarsPadding(),
                            color = MaterialTheme.colorScheme.background
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                durableState.status?.let { message ->
                                    Text(
                                        text = message,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.align(Alignment.CenterHorizontally)
                                    )
                                }
                                if (durableState.needsDestination) {
                                    Button(
                                        onClick = { folderPicker.launch(null) },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(52.dp),
                                        shape = MaterialTheme.shapes.small
                                    ) {
                                        Text("Choose new output folder")
                                    }
                                }
                                if (durableState.canResume) {
                                    Button(
                                        onClick = { homeViewModel.resumeBatch() },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(52.dp),
                                        shape = MaterialTheme.shapes.small
                                    ) {
                                        Text("Resume processing")
                                    }
                                }
                                OutlinedButton(
                                    onClick = {
                                        homeViewModel.cancelBatch()
                                        status = "Cancelling after the current image..."
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(52.dp),
                                    shape = MaterialTheme.shapes.small
                                ) {
                                    Text("Cancel")
                                }
                            }
                        }
                    }
                }

                SlicerScreen.Results -> {
                    val batchResults = homeViewModel.resultPages.collectAsLazyPagingItems()
                    Box(
                        modifier = Modifier
                            .testTag("slicer-results-screen")
                            .fillMaxWidth()
                            .fillMaxHeight()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight()
                                .padding(bottom = 72.dp)
                        ) {
                            durableState.status?.let { NoticeText(message = it) }
                            if (currentProgress.completed > 0) {
                                BatchResultsPanel(
                                    results = batchResults,
                                    summary = currentProgress,
                                    totalInputBytes = durableState.totalInputBytes,
                                    totalOutputBytes = durableState.totalOutputBytes,
                                    totalSavedBytes = durableState.totalSavedBytes,
                                    onResultClick = { result ->
                                        if (result.decision != ProcessingDecision.Failed &&
                                            result.decision != ProcessingDecision.Cancelled && result.outputUri != null
                                        ) {
                                            selectedResultForComparison = result
                                            comparePosition = 0.5f
                                            currentScreen = SlicerScreen.Compare
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            } else {
                                NoticeText("No results yet.")
                            }
                        }

                        Button(
                            onClick = {
                                homeViewModel.clearImageSelection()
                                homeViewModel.dismissCurrentBatch()
                                currentScreen = SlicerScreen.Setup
                            },
                            enabled = true,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .height(52.dp),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text("Choose more images")
                        }
                    }
                }

                        else -> {
                            // Welcome screen handled separately in the outer branch.
                        }
                    }

                    if (!hasBottomDockedButton) {
                        status?.let {
                            NoticeText(message = it)
                        }
                    }
                }
            }
            }

            AnimatedVisibility(
                visible = isNotificationVisible && notificationMessage != null,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut()
            ) {
                notificationMessage?.let { message ->
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        shadowElevation = 6.dp
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "Destination updated",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

}

@Preview(showBackground = true)
@Composable
private fun SlicerHomeScreenPreview() {
    SlicerTheme {
        SlicerHomeScreen()
    }
}
