package com.nexcompress.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.nexcompress.app.ads.AdManager
import com.nexcompress.app.ui.AppViewModelProvider
import com.nexcompress.app.ui.CompressionViewModel
import com.nexcompress.app.ui.home.HomeScreen
import com.nexcompress.app.ui.images.ImagesScreen
import com.nexcompress.app.ui.mergepdf.MergePdfScreen
import com.nexcompress.app.ui.pdfconfig.PdfConfigScreen
import com.nexcompress.app.ui.pdfpages.PdfPageEditorScreen
import com.nexcompress.app.ui.pdftoimage.PdfToImageConfigScreen
import com.nexcompress.app.ui.processing.ProcessingScreen
import com.nexcompress.app.ui.protectpdf.ProtectPdfScreen
import com.nexcompress.app.ui.results.ResultsScreen
import com.nexcompress.app.ui.annotatepdf.AnnotatePdfScreen
import com.nexcompress.app.ui.splitpdf.SplitPdfScreen
import com.nexcompress.app.ui.txttopdf.TxtToPdfConfigScreen
import com.nexcompress.app.ui.util.findActivity

/**
 * Orchestrates the four core views. The [CompressionViewModel] is scoped to the
 * host Activity so Config -> Processing -> Results all share one job state.
 */
@Composable
fun NexCompressNavGraph(
    navController: NavHostController,
    adManager: AdManager,
    modifier: Modifier = Modifier
) {
    val activity = LocalContext.current.findActivity()
    val compressionViewModel: CompressionViewModel = viewModel(
        viewModelStoreOwner = activity,
        factory = AppViewModelProvider.Factory
    )

    NavHost(
        navController = navController,
        startDestination = Destinations.HOME,
        modifier = modifier
    ) {
        // Screen 1 — Primary Feature Dashboard
        composable(Destinations.HOME) {
            HomeScreen(
                compressionViewModel = compressionViewModel,
                onOpenPdfConfig = { navController.navigate(Destinations.PDF_CONFIG) },
                onOpenImages = { navController.navigate(Destinations.IMAGES) },
                onOpenPdfToImageConfig = { navController.navigate(Destinations.PDF_TO_IMAGE_CONFIG) },
                onOpenTxtToPdfConfig = { navController.navigate(Destinations.TXT_TO_PDF_CONFIG) },
                onOpenPdfPages = { navController.navigate(Destinations.PDF_PAGES) },
                onOpenMergePdf = { navController.navigate(Destinations.MERGE_PDF) },
                onOpenSplitPdf = { navController.navigate(Destinations.SPLIT_PDF) },
                onOpenProtectPdf = { navController.navigate(Destinations.PROTECT_PDF) },
                onOpenAnnotatePdf = { navController.navigate(Destinations.ANNOTATE_PDF) },
                onOpenProcessing = { navController.navigate(Destinations.PROCESSING) }
            )
        }

        // Screen 2a — PDF Configuration Control
        composable(Destinations.PDF_CONFIG) {
            PdfConfigScreen(
                viewModel = compressionViewModel,
                onBack = { navController.popBackStack() },
                onStartProcessing = { navController.navigate(Destinations.PROCESSING) }
            )
        }

        // PDF → Images conversion config
        composable(Destinations.PDF_TO_IMAGE_CONFIG) {
            PdfToImageConfigScreen(
                viewModel = compressionViewModel,
                onBack = { navController.popBackStack() },
                onStartProcessing = { navController.navigate(Destinations.PROCESSING) }
            )
        }

        // Unified Images tool — convert / edit / images→PDF
        composable(Destinations.IMAGES) {
            ImagesScreen(
                viewModel = compressionViewModel,
                onBack = { navController.popBackStack() },
                onStartProcessing = { navController.navigate(Destinations.PROCESSING) }
            )
        }

        // TXT → PDF conversion config
        composable(Destinations.TXT_TO_PDF_CONFIG) {
            TxtToPdfConfigScreen(
                viewModel = compressionViewModel,
                onBack = { navController.popBackStack() },
                onStartProcessing = { navController.navigate(Destinations.PROCESSING) }
            )
        }

        // PDF page editor — reorder / rotate / delete
        composable(Destinations.PDF_PAGES) {
            PdfPageEditorScreen(
                viewModel = compressionViewModel,
                onBack = { navController.popBackStack() },
                onStartProcessing = { navController.navigate(Destinations.PROCESSING) }
            )
        }

        // Merge PDFs
        composable(Destinations.MERGE_PDF) {
            MergePdfScreen(
                viewModel = compressionViewModel,
                onBack = { navController.popBackStack() },
                onStartProcessing = { navController.navigate(Destinations.PROCESSING) }
            )
        }

        // Split / extract PDF
        composable(Destinations.SPLIT_PDF) {
            SplitPdfScreen(
                viewModel = compressionViewModel,
                onBack = { navController.popBackStack() },
                onStartProcessing = { navController.navigate(Destinations.PROCESSING) }
            )
        }

        // Protect / unlock PDF
        composable(Destinations.PROTECT_PDF) {
            ProtectPdfScreen(
                viewModel = compressionViewModel,
                onBack = { navController.popBackStack() },
                onStartProcessing = { navController.navigate(Destinations.PROCESSING) }
            )
        }

        // Annotate PDF (markup editor)
        composable(Destinations.ANNOTATE_PDF) {
            AnnotatePdfScreen(
                viewModel = compressionViewModel,
                onBack = { navController.popBackStack() },
                onStartProcessing = { navController.navigate(Destinations.PROCESSING) }
            )
        }

        // Screen 3 — Processing Status Overlay (+ interstitial bridge to Screen 4)
        composable(Destinations.PROCESSING) {
            ProcessingScreen(
                viewModel = compressionViewModel,
                adManager = adManager,
                onComplete = {
                    navController.navigate(Destinations.RESULTS) {
                        // Collapse Config + Processing; land on a clean [Home, Results] stack.
                        popUpTo(Destinations.HOME) { inclusive = false }
                        launchSingleTop = true
                    }
                },
                onError = { navController.popBackStack() }
            )
        }

        // Screen 4 — Processing Results & Analytics
        composable(Destinations.RESULTS) {
            ResultsScreen(
                viewModel = compressionViewModel,
                onHome = {
                    compressionViewModel.clear()
                    navController.navigate(Destinations.HOME) {
                        popUpTo(Destinations.HOME) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }
    }
}
