import ManagedSettings
import Foundation

/// Handles taps on the shield card.
///
/// The extension is short-lived and sandboxed, so it cannot run Face ID itself.
/// It writes an unlock request into the shared App Group and closes; the host app
/// reads that request on next launch, authenticates, and lifts the shield.
class ShieldActionExtension: ShieldActionDelegate {

    private let groupID = "group.com.sabid.applock"

    override func handle(action: ShieldAction,
                         for application: ApplicationToken,
                         completionHandler: @escaping (ShieldActionResponse) -> Void) {
        switch action {
        case .primaryButtonPressed:
            requestUnlock()
            // .defer keeps the shield up; the host app removes it after Face ID.
            completionHandler(.defer)
        case .secondaryButtonPressed:
            completionHandler(.close)
        @unknown default:
            completionHandler(.close)
        }
    }

    override func handle(action: ShieldAction,
                         for webDomain: WebDomainToken,
                         completionHandler: @escaping (ShieldActionResponse) -> Void) {
        completionHandler(action == .primaryButtonPressed ? .defer : .close)
    }

    private func requestUnlock() {
        guard let defaults = UserDefaults(suiteName: groupID) else { return }
        defaults.set(Date().timeIntervalSince1970, forKey: "pendingUnlockRequest")
    }
}
