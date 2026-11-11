package se.lixo.app.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureMetadataOutput
import platform.AVFoundation.AVCaptureMetadataOutputObjectsDelegateProtocol
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMetadataMachineReadableCodeObject
import platform.AVFoundation.AVMetadataObjectTypeQRCode
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.darwin.NSObject
import platform.UIKit.UIAction
import platform.UIKit.UIApplication
import platform.UIKit.UIButton
import platform.UIKit.UIButtonTypeSystem
import platform.UIKit.UIColor
import platform.UIKit.UIControlEventTouchUpInside
import platform.UIKit.UIControlStateNormal
import platform.UIKit.UIModalPresentationFullScreen
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_get_main_queue

/**
 * AVFoundation QR scanner (doc/plans/042 §10): a modal full-screen camera view with an "Avbryt"
 * button. Returns [QrScanResult.Unavailable] without a camera (e.g. the simulator), without
 * camera permission, or if the capture session fails to set up, matching Android's
 * degrade-to-typed-code behaviour. The capture session is built and validated BEFORE presenting
 * the view controller: UIKit ignores a dismiss during an in-progress presentation, so a failure
 * detected only in `viewDidLoad` would leave `scan()` hanging forever behind a black modal.
 */
internal class IosQrScanner : QrScanner {

    @OptIn(ExperimentalForeignApi::class)
    override suspend fun scan(): QrScanResult {
        // No camera (the simulator) must short-circuit before any dispatch to Main or any
        // permission call: the test binary has no NSCameraUsageDescription, and a permission
        // call would abort it (TCC). It also must run before withContext(Dispatchers.Main),
        // since a runBlocking { ... } caller (iosTest) on the main thread would deadlock on the
        // dispatch_async used by Darwin's Main dispatcher.
        val device = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo)
            ?: return QrScanResult.Unavailable

        return withContext(Dispatchers.Main) {
            val authorized = when (AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)) {
                AVAuthorizationStatusAuthorized -> true
                AVAuthorizationStatusNotDetermined -> suspendCancellableCoroutine { cont ->
                    AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { granted ->
                        cont.resume(granted)
                    }
                }
                else -> false
            }
            if (!authorized) return@withContext QrScanResult.Unavailable

            val top = topViewController() ?: return@withContext QrScanResult.Unavailable
            val capture = buildQrCapture(device) ?: return@withContext QrScanResult.Unavailable

            suspendCancellableCoroutine<QrScanResult> { cont ->
                var resumed = false
                val controller = QrScannerViewController(capture) { result ->
                    if (resumed) return@QrScannerViewController
                    resumed = true
                    cont.resume(result)
                }
                controller.modalPresentationStyle = UIModalPresentationFullScreen
                top.presentViewController(controller, animated = true, completion = null)
                cont.invokeOnCancellation {
                    controller.dismissViewControllerAnimated(true, completion = null)
                }
            }
        }
    }

    private fun topViewController(): UIViewController? {
        val scene = UIApplication.sharedApplication.connectedScenes
            .filterIsInstance<UIWindowScene>()
            .firstOrNull() ?: return null
        var top = scene.windows
            .filterIsInstance<UIWindow>()
            .firstOrNull { it.isKeyWindow() }
            ?.rootViewController ?: return null
        while (true) {
            top = top.presentedViewController ?: break
        }
        return top
    }
}

/** A validated, ready-to-run capture session, built before any view controller is presented. */
private class QrCapture(val session: AVCaptureSession, val output: AVCaptureMetadataOutput)

/**
 * Builds and validates the capture session for [device]: input/output wiring and the QR
 * metadata type. Returns null on any failure (e.g. the camera is in use by another app), so the
 * caller can degrade to [QrScanResult.Unavailable] before presenting anything.
 */
@OptIn(ExperimentalForeignApi::class)
private fun buildQrCapture(device: AVCaptureDevice): QrCapture? {
    val session = AVCaptureSession()

    val input = try {
        AVCaptureDeviceInput.deviceInputWithDevice(device, error = null)
    } catch (_: Throwable) {
        null
    }
    if (input == null || !session.canAddInput(input)) return null
    session.addInput(input)

    val output = AVCaptureMetadataOutput()
    if (!session.canAddOutput(output)) return null
    session.addOutput(output)
    // metadataObjectTypes may only be set AFTER the output is added to the session; setting it
    // earlier throws an ObjC exception, which crashes the process.
    output.metadataObjectTypes = listOf(AVMetadataObjectTypeQRCode)

    return QrCapture(session, output)
}

/**
 * Full-screen modal camera view over an already-built, already-validated [capture]. Setup
 * failures are detected before presentation (in [buildQrCapture]), because UIKit ignores a
 * dismiss during an in-progress presentation and `scan()` would hang.
 */
@OptIn(ExperimentalForeignApi::class)
private class QrScannerViewController(
    private val capture: QrCapture,
    private val onResult: (QrScanResult) -> Unit
) : UIViewController(nibName = null, bundle = null) {

    private var previewLayer: AVCaptureVideoPreviewLayer? = null
    private var cancelButton: UIButton? = null
    private val metadataDelegate = MetadataDelegate { raw -> finish(QrScanResult.Success(raw)) }
    private var finished = false

    private fun finish(result: QrScanResult) {
        if (finished) return
        finished = true
        capture.session.stopRunning()
        dismissViewControllerAnimated(true) { onResult(result) }
    }

    override fun viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor.blackColor

        // metadataDelegate is held weakly by setMetadataObjectsDelegate, so it is kept as a
        // strong field on this view controller.
        capture.output.setMetadataObjectsDelegate(metadataDelegate, queue = dispatch_get_main_queue())

        val layer = AVCaptureVideoPreviewLayer(session = capture.session)
        layer.videoGravity = AVLayerVideoGravityResizeAspectFill
        view.layer.addSublayer(layer)
        previewLayer = layer

        val button = UIButton.buttonWithType(UIButtonTypeSystem).apply {
            setTitle("Avbryt", forState = UIControlStateNormal)
            setTitleColor(UIColor.whiteColor, forState = UIControlStateNormal)
            addAction(
                UIAction.actionWithHandler { finish(QrScanResult.Cancelled) },
                forControlEvents = UIControlEventTouchUpInside
            )
        }
        view.addSubview(button)
        cancelButton = button

        dispatch_async(dispatch_get_global_queue(0L, 0uL)) { capture.session.startRunning() }
    }

    override fun viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.setFrame(view.bounds)
        cancelButton?.let { button ->
            button.sizeToFit()
            val viewSize = view.bounds.useContents { size }
            val buttonSize = button.frame().useContents { size }
            button.setFrame(
                platform.CoreGraphics.CGRectMake(
                    (viewSize.width - buttonSize.width) / 2.0,
                    viewSize.height - buttonSize.height - 48.0,
                    buttonSize.width,
                    buttonSize.height
                )
            )
        }
    }
}

private class MetadataDelegate(
    private val onCode: (String) -> Unit
) : NSObject(), AVCaptureMetadataOutputObjectsDelegateProtocol {
    private var delivered = false

    override fun captureOutput(
        output: AVCaptureOutput,
        didOutputMetadataObjects: List<*>,
        fromConnection: AVCaptureConnection
    ) {
        if (delivered) return
        val code = didOutputMetadataObjects
            .filterIsInstance<AVMetadataMachineReadableCodeObject>()
            .firstOrNull { it.stringValue != null }
            ?: return
        delivered = true
        onCode(code.stringValue ?: return)
    }
}
