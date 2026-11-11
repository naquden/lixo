import SwiftUI
import UIKit
import FirebaseCore
import FirebaseAppCheck
import ComposeApp

/// Release App Check provider factory. App Check enforcement stays off for now, same as Android
/// 030 §2a.
final class LixoAppCheckProviderFactory: NSObject, AppCheckProviderFactory {
    func createProvider(with app: FirebaseApp) -> AppCheckProvider? {
        // TODO(doc/ios.md § App Check): return AppAttestProvider(app:) once iosApp.entitlements
        // has com.apple.developer.devicecheck.appattest-environment; without it attestation
        // always fails.
        return DeviceCheckProvider(app: app)
    }
}

class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        // Initialize Firebase FIRST before any state or view controllers touch Firebase. The App
        // Check provider must be installed before FirebaseApp.configure() (doc/plans/042 §6).
        if FirebaseApp.app() == nil {
#if DEBUG
            AppCheck.setAppCheckProviderFactory(AppCheckDebugProviderFactory())
#else
            AppCheck.setAppCheckProviderFactory(LixoAppCheckProviderFactory())
#endif
            FirebaseApp.configure()
        }
        return true
    }

    // Handle Universal Links for Firebase Email Link Authentication
    // (https://firebase.google.com/docs/auth/ios/email-link-auth) and `lixo://friend/<code>`.
    func application(
        _ application: UIApplication,
        continue userActivity: NSUserActivity,
        restorationHandler: @escaping ([UIUserActivityRestoring]?) -> Void
    ) -> Bool {
        guard userActivity.activityType == NSUserActivityTypeBrowsingWeb,
              let incomingURL = userActivity.webpageURL else {
            return false
        }
        return MainViewControllerKt.handleIncomingUrl(url: incomingURL.absoluteString)
    }

    // Handle the custom `lixo://` URL scheme (friend deep link) and the Email Link Auth fallback.
    func application(
        _ app: UIApplication,
        open url: URL,
        options: [UIApplication.OpenURLOptionsKey: Any] = [:]
    ) -> Bool {
        return MainViewControllerKt.handleIncomingUrl(url: url.absoluteString)
    }
}

@main
struct iOSApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var delegate

    var body: some Scene {
        WindowGroup {
            ComposeContentView()
                .ignoresSafeArea(.all)
                .onContinueUserActivity(NSUserActivityTypeBrowsingWeb) { userActivity in
                    if let incomingURL = userActivity.webpageURL {
                        _ = MainViewControllerKt.handleIncomingUrl(url: incomingURL.absoluteString)
                    }
                }
                .onOpenURL { url in
                    _ = MainViewControllerKt.handleIncomingUrl(url: url.absoluteString)
                }
        }
    }
}

struct ComposeContentView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
