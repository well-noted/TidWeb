import Foundation

class ThemeManager {
    private let defaults = UserDefaults.standard
    private let darkModeKey = "isDarkModeEnabled"
    
    var isDarkModeEnabled: Bool {
        get {
            defaults.bool(forKey: darkModeKey)
        }
        set {
            defaults.set(newValue, forKey: darkModeKey)
        }
    }
    
    init() {
        // Set default theme based on system appearance
        if !defaults.object(forKey: darkModeKey) {
            isDarkModeEnabled = UITraitCollection.current.userInterfaceStyle == .dark
        }
    }
} 