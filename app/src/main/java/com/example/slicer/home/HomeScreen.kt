package com.example.slicer.home

import com.example.slicer.core.BatchFileResult
import com.example.slicer.core.DEFAULT_OUTPUT_FOLDER_DISPLAY_NAME
import com.example.slicer.core.DEFAULT_OUTPUT_FOLDER_URI
import com.example.slicer.core.DEFAULT_QUALITY
import com.example.slicer.core.FULL_SIZE
import com.example.slicer.core.ProcessingDecision
import com.example.slicer.core.SlicerScreen
import com.example.slicer.core.DEFAULT_VIDEO_SHORT_SIDE
import com.example.slicer.core.VideoQuality
import com.example.slicer.storage.OutputFolderAccess
import com.example.slicer.storage.clearOutputFolderUri
import com.example.slicer.storage.loadBitmapForPreview
import com.example.slicer.storage.loadSavedOutputFolderUri
import com.example.slicer.storage.listMediaInFolder
import com.example.slicer.storage.loadSavedMaxDimension
import com.example.slicer.storage.loadSavedQuality
import com.example.slicer.storage.loadSavedVideoQuality
import com.example.slicer.storage.loadSavedVideoShortSide
import com.example.slicer.storage.saveMaxDimension
import com.example.slicer.storage.saveVideoQuality
import com.example.slicer.storage.saveVideoShortSide
import com.example.slicer.storage.markWelcomeScreenSeen
import com.example.slicer.storage.outputFolderAccess
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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
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
import com.example.slicer.ui.theme.slicerPageBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun SlicerHomeScreen(
    modifier: Modifier = Modifier,
    welcomeEntranceReady: Boolean = true,
    welcomeLogoReady: Boolean = true,
    welcomeLogoStartBounds: Rect? = null,
    onWelcomeLogoPositioned: ((Rect) -> Unit)? = null
) {
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

    var presentedBatchId by rememberSaveable { mutableStateOf<String?>(null) }

    // The finished run whose celebration has already played, so going back to results stays calm.
    var celebratedBatchId by rememberSaveable { mutableStateOf<String?>(null) }
    var compareSourceBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var compareOutputBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var compareOriginalImage by remember { mutableStateOf<ImageBitmap?>(null) }
    var compareCompressedImage by remember { mutableStateOf<ImageBitmap?>(null) }
    var isCompareLoading by remember { mutableStateOf(false) }
    var compareError by remember { mutableStateOf<String?>(null) }
    var selectedResultForComparison by remember { mutableStateOf<BatchFileResult?>(null) }
    var notificationTitle by remember { mutableStateOf("") }
    var notificationMessage by remember { mutableStateOf<String?>(null) }
    var isNotificationVisible by remember { mutableStateOf(false) }
    var maxDimension by rememberSaveable { mutableIntStateOf(FULL_SIZE) }
    var videoQuality by rememberSaveable { mutableStateOf(VideoQuality.Balanced) }
    var videoShortSide by rememberSaveable { mutableIntStateOf(DEFAULT_VIDEO_SHORT_SIDE) }
    val scope = rememberCoroutineScope()

    fun notify(title: String, message: String) {
        notificationTitle = title
        notificationMessage = message
        isNotificationVisible = true
    }

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

        // Decode both sides at once; the screen waits for the slower one, not the sum.
        val (originalBitmap, compressedBitmap) = coroutineScope {
            val original = async(Dispatchers.IO) { context.loadBitmapForPreview(result.inputUri) }
            val compressed = async(Dispatchers.IO) { result.outputUri?.let { context.loadBitmapForPreview(it) } }
            original.await() to compressed.await()
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
                "Original file access is no longer available after source cleanup."
            compressedBitmap == null -> "Could not load the processed file."
            result.decision == ProcessingDecision.Failed -> "No comparison available for this result."
            else -> null
        }
    }

    LaunchedEffect(Unit) {
        quality = context.loadSavedQuality()
        maxDimension = context.loadSavedMaxDimension()
        videoQuality = context.loadSavedVideoQuality()
        videoShortSide = context.loadSavedVideoShortSide()
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
            durableState.isProcessing || durableState.needsDestination -> {
                presentedBatchId = durableState.batchId
                currentScreen = SlicerScreen.Processing
            }
            // Only show completed results for a run opened in this task, not old database history.
            durableState.isTerminal &&
                (currentScreen == SlicerScreen.Processing || presentedBatchId == durableState.batchId) -> {
                presentedBatchId = durableState.batchId
                currentScreen = SlicerScreen.Results
            }
        }
    }

    LaunchedEffect(durableState.error) {
        durableState.error?.let { error ->
            status = error
            currentScreen = SlicerScreen.Quality
            homeViewModel.clearError()
        }
    }

    LaunchedEffect(maxDimension, didLoadPreferences) {
        if (didLoadPreferences) context.saveMaxDimension(maxDimension)
    }

    LaunchedEffect(videoQuality, videoShortSide, didLoadPreferences) {
        if (didLoadPreferences) {
            context.saveVideoQuality(videoQuality)
            context.saveVideoShortSide(videoShortSide)
        }
    }

    LaunchedEffect(quality, didLoadPreferences) {
        if (didLoadPreferences) {
            // Restarts on every slider step, so dragging saves once when it settles.
            delay(300)
            context.saveQuality(quality)
        }
    }

    BackHandler(enabled = currentScreen == SlicerScreen.Quality) {
        currentScreen = SlicerScreen.Setup
    }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        status = null
        homeViewModel.validateImageSelection(uris)
        homeViewModel.dismissCurrentBatch()
        selectedResultForComparison = null
        currentScreen = SlicerScreen.Setup
    }

    val sourceFolderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri == null) return@rememberLauncherForActivityResult
        val granted = runCatching {
            context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.isSuccess
        if (!granted) {
            notify("Folder not added", "Slicer couldn't keep access to that folder. Try another one.")
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val media = withContext(Dispatchers.IO) {
                runCatching { context.listMediaInFolder(treeUri) }.getOrDefault(emptyList())
            }
            if (media.isEmpty()) {
                notify("Nothing to compress", "That folder has no photos or videos directly inside it.")
                return@launch
            }
            if (media.size > MAX_SELECTED_IMAGES) {
                notify("Folder added", "Only the first $MAX_SELECTED_IMAGES of ${media.size} files were added.")
            }
            status = null
            homeViewModel.validateImageSelection(media)
            homeViewModel.dismissCurrentBatch()
            selectedResultForComparison = null
            currentScreen = SlicerScreen.Setup
        }
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
        homeViewModel.startBatch(files, folder, qualityValue, maxDimension, videoQuality, videoShortSide)
    }

    fun startProcessing() {
        selectedResultForComparison = null
        val folder = outputFolder
        val files = selectedImages.toList()
        if (files.isEmpty()) {
            status = "No files selected."
            return
        }

        startPersistedBatch(files, folder, quality)
    }

    AnimatedContent(
        targetState = currentScreen == SlicerScreen.Welcome,
        transitionSpec = { fadeIn(tween(320, delayMillis = 80)) togetherWith fadeOut(tween(200)) },
        label = "welcome-to-app"
    ) { showWelcome ->
        if (showWelcome) {
            WelcomeScreen(
                entranceReady = welcomeEntranceReady,
                logoReady = welcomeLogoReady,
                logoStartBounds = welcomeLogoStartBounds,
                onLogoPositioned = onWelcomeLogoPositioned,
                onContinue = {
                    context.markWelcomeScreenSeen()
                    currentScreen = SlicerScreen.Setup
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            )
        } else {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .slicerPageBackground()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    shape = MaterialTheme.shapes.medium,
                    color = Color.Transparent
                ) {
                    AnimatedContent(
                        targetState = currentScreen,
                        modifier = Modifier.fillMaxSize().padding(20.dp),
                        transitionSpec = {
                            // Moving deeper slides in from the right; going back slides in from the left.
                            val direction = if (targetState.depth > initialState.depth) 1 else -1
                            val enter = slideInHorizontally(tween(340, easing = FastOutSlowInEasing)) { direction * it / 6 } +
                                fadeIn(tween(240, delayMillis = 70))
                            val exit = slideOutHorizontally(tween(260, easing = FastOutSlowInEasing)) { -direction * it / 10 } +
                                fadeOut(tween(140))
                            enter togetherWith exit
                        },
                        label = "screen"
                    ) { screen ->
                        when (screen) {
                    SlicerScreen.Setup -> {
                        Column(
                            modifier = Modifier
                                .testTag("slicer-setup-screen")
                                .fillMaxSize()
                        ) {
                            BatchSetupContent(
                                selectedImages = selectedImages,
                                selectedVideos = selectionState.selectedVideos,
                                isValidating = selectionState.isValidating,
                                validatedCount = selectionState.validatedCount,
                                validationTotal = selectionState.totalCount,
                                controlsEnabled = !isProcessing,
                                onSelectImages = { imagePicker.launch(arrayOf("image/*", "video/*")) },
                                onSelectFolder = { sourceFolderPicker.launch(null) },
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
                                         .padding(top = 16.dp),
                                     verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Text(
                                        text = if (selectionState.isValidating) {
                                            "Validating ${selectionState.validatedCount} of ${selectionState.totalCount}"
                                        } else if (selectedImages.isEmpty()) {
                                            "Select up to $MAX_SELECTED_IMAGES photos or videos"
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
                                             .height(56.dp),
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
                                .fillMaxSize()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight()
                                    .verticalScroll(rememberScrollState())
                                    .padding(bottom = 76.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                if (selectionState.hasImages) {
                                    QualityStepContent(
                                        quality = quality,
                                        controlsEnabled = !isProcessing,
                                        onQualityChange = { quality = it },
                                        title = if (selectionState.hasVideos) "Photo quality" else "Compression quality"
                                    )

                                    ImageSizeSection(
                                        maxDimension = maxDimension,
                                        controlsEnabled = !isProcessing,
                                        onMaxDimensionChange = { maxDimension = it }
                                    )
                                }

                                if (selectionState.hasVideos) {
                                    VideoSettingsSection(
                                        quality = videoQuality,
                                        shortSide = videoShortSide,
                                        controlsEnabled = !isProcessing,
                                        onQualityChange = { videoQuality = it },
                                        onShortSideChange = { videoShortSide = it }
                                    )
                                }

                                DestinationSection(
                                    outputFolder = outputFolder,
                                    controlsEnabled = !isProcessing,
                                    onSelectOutput = { folderPicker.launch(null) },
                                    onForgetOutput = {
                                        outputFolder = DEFAULT_OUTPUT_FOLDER_URI
                                        context.clearOutputFolderUri()
                                        status = null
                                        notify("Destination updated", "Reset to $DEFAULT_OUTPUT_FOLDER_DISPLAY_NAME.")
                                    }
                                )

                                status?.let { NoticeText(message = it) }

                                Text(
                                    text = buildList {
                                        if (selectionState.hasImages) {
                                            add(
                                                "HEIC, PNG and other non-JPEG images are copied unchanged, " +
                                                    "and so are JPEGs that can't get any smaller."
                                            )
                                        }
                                        if (selectionState.hasVideos) {
                                            add(
                                                "Videos are saved as MP4. Videos that can't be converted " +
                                                    "or get no smaller are copied unchanged."
                                            )
                                        }
                                    }.joinToString(" "),
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
                                .fillMaxSize()
                        ) {
                            CompareScreen(
                                beforeImage = compareOriginalImage,
                                afterImage = compareCompressedImage,
                                isGeneratingComparison = isCompareLoading,
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
                                            status = "Cancelling..."
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
                                                currentScreen = SlicerScreen.Compare
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        celebrate = durableState.batchId != null &&
                                            celebratedBatchId != durableState.batchId,
                                        onCelebrated = { celebratedBatchId = durableState.batchId }
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
                                Text("Choose more files")
                            }
                        }
                    }

                            // Welcome is shown by the outer branch.
                            SlicerScreen.Welcome -> Unit
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
                                    text = notificationTitle,
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
}

/** Order of the main flow, used to pick the slide direction between screens. */
private val SlicerScreen.depth: Int
    get() = when (this) {
        SlicerScreen.Welcome -> 0
        SlicerScreen.Setup -> 1
        SlicerScreen.Quality -> 2
        SlicerScreen.Processing -> 3
        SlicerScreen.Results -> 4
        SlicerScreen.Compare -> 5
    }

@Preview(showBackground = true)
@Composable
private fun SlicerHomeScreenPreview() {
    SlicerTheme {
        SlicerHomeScreen()
    }
}
