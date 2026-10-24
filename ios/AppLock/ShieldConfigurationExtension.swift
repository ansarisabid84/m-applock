import ManagedSettings
import ManagedSettingsUI
import UIKit

/// Styles the card iOS shows in place of a shielded app.
///
/// This is the entire surface area available: an icon, two lines of text and two
/// buttons. There is no way to add a PIN pad here — the extension cannot present
/// custom views, only fill in this fixed template.
class ShieldConfigurationExtension: ShieldConfigurationDataSource {

    private var shared: ShieldConfiguration {
        ShieldConfiguration(
            backgroundBlurStyle: .systemUltraThinMaterialDark,
            backgroundColor: UIColor(red: 0.055, green: 0.067, blue: 0.086, alpha: 0.94),
            icon: UIImage(systemName: "lock.shield.fill"),
            title: .init(text: "Locked by AppLock", color: .white),
            subtitle: .init(text: "Unlock with Face ID to continue", color: UIColor(white: 0.68, alpha: 1)),
            primaryButtonLabel: .init(text: "Unlock", color: UIColor(red: 0.05, green: 0.06, blue: 0.08, alpha: 1)),
            primaryButtonBackgroundColor: UIColor(red: 0.78, green: 0.60, blue: 0.27, alpha: 1),
            secondaryButtonLabel: .init(text: "Not now", color: UIColor(white: 0.68, alpha: 1))
        )
    }

    override func configuration(shielding application: Application) -> ShieldConfiguration { shared }
    override func configuration(shielding webDomain: WebDomain) -> ShieldConfiguration { shared }
    override func configuration(shielding application: Application,
                                in category: ActivityCategory) -> ShieldConfiguration { shared }
    override func configuration(shielding webDomain: WebDomain,
                                in category: ActivityCategory) -> ShieldConfiguration { shared }
}
