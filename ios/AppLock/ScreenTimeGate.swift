import FamilyControls
import ManagedSettings
import DeviceActivity
import SwiftUI

/// Owns the Screen Time authorisation and the set of shielded apps.
///
/// Note what is *absent*: any way to read which apps the user picked. `selection`
/// holds opaque tokens. We can shield them and count them; we cannot name them.
@MainActor
final class ScreenTimeGate: ObservableObject {

    @Published var selection = FamilyActivitySelection()
    @Published var isAuthorized = false
    @Published var authorizationError: String?

    private let store = ManagedSettingsStore(named: .init("applock.primary"))

    func requestAuthorization() async {
        do {
            // .individual = this device's own user. .child requires a Family Sharing
            // parent device and will fail on a standalone install.
            try await AuthorizationCenter.shared.requestAuthorization(for: .individual)
            isAuthorized = true
        } catch {
            isAuthorized = false
            authorizationError = "Screen Time access was declined. AppLock cannot shield apps without it."
        }
    }

    /// Applies the shield. Takes effect immediately and survives reboot.
    func applyShield() {
        store.shield.applications = selection.applicationTokens.isEmpty
            ? nil : selection.applicationTokens
        store.shield.applicationCategories = selection.categoryTokens.isEmpty
            ? nil : .specific(selection.categoryTokens)
    }

    /// Lifts the shield for a grace period after a successful Face ID unlock.
    func grantTemporaryAccess(minutes: Int) {
        store.shield.applications = nil
        store.shield.applicationCategories = nil

        Task {
            try? await Task.sleep(nanoseconds: UInt64(minutes) * 60 * 1_000_000_000)
            // A background task is not guaranteed to survive; DeviceActivitySchedule
            // is the durable way to re-arm. See scheduleReshield().
            applyShield()
        }
    }

    /// Durable re-arm: the system wakes the monitor extension at the interval end.
    func scheduleReshield(afterMinutes minutes: Int) {
        let now = Calendar.current.dateComponents([.hour, .minute], from: Date())
        let end = Calendar.current.dateComponents(
            [.hour, .minute],
            from: Date().addingTimeInterval(TimeInterval(minutes * 60))
        )
        let schedule = DeviceActivitySchedule(
            intervalStart: now, intervalEnd: end, repeats: false
        )
        try? DeviceActivityCenter().startMonitoring(.init("applock.grace"), during: schedule)
    }

    func removeShield() {
        store.shield.applications = nil
        store.shield.applicationCategories = nil
    }
}

/// Apple's picker is mandatory — a custom list is impossible without bundle IDs.
struct AppPickerView: View {
    @ObservedObject var gate: ScreenTimeGate
    @State private var pickerPresented = false

    var body: some View {
        VStack(spacing: 16) {
            Text("\(gate.selection.applicationTokens.count) apps shielded")
                .font(.headline)
            Button("Choose apps") { pickerPresented = true }
                .familyActivityPicker(isPresented: $pickerPresented, selection: $gate.selection)
            Button("Apply") { gate.applyShield() }
                .buttonStyle(.borderedProminent)
        }
        .task { await gate.requestAuthorization() }
    }
}
