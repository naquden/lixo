import SwiftUI
import UIKit
import FirebaseCore
import ComposeApp

class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        // Initialize Firebase FIRST before any state or view controllers touch Firebase
        if FirebaseApp.app() == nil {
            FirebaseApp.configure()
        }
        return true
    }

    // Handle Universal Links for Firebase Email Link Authentication (https://firebase.google.com/docs/auth/ios/email-link-auth)
    func application(
        _ application: UIApplication,
        continue userActivity: NSUserActivity,
        restorationHandler: @escaping ([UIUserActivityRestoring]?) -> Void
    ) -> Bool {
        guard userActivity.activityType == NSUserActivityTypeBrowsingWeb,
              let incomingURL = userActivity.webpageURL else {
            return false
        }
        return MainViewControllerKt.handleIncomingEmailSignInLink(url: incomingURL.absoluteString)
    }

    // Handle custom URL scheme fallback for Firebase Email Link Authentication
    func application(
        _ app: UIApplication,
        open url: URL,
        options: [UIApplication.OpenURLOptionsKey: Any] = [:]
    ) -> Bool {
        return MainViewControllerKt.handleIncomingEmailSignInLink(url: url.absoluteString)
    }
}

@main
struct iOSApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var delegate

    init() {
        // Initialize Firebase FIRST before any state or view controllers touch Firebase
        if FirebaseApp.app() == nil {
            FirebaseApp.configure()
        }
    }

    var body: some Scene {
        WindowGroup {
            ComposeContentView()
                .ignoresSafeArea(.all)
                .onContinueUserActivity(NSUserActivityTypeBrowsingWeb) { userActivity in
                    if let incomingURL = userActivity.webpageURL {
                        _ = MainViewControllerKt.handleIncomingEmailSignInLink(url: incomingURL.absoluteString)
                    }
                }
                .onOpenURL { url in
                    _ = MainViewControllerKt.handleIncomingEmailSignInLink(url: url.absoluteString)
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
