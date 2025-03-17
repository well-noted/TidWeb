import SwiftUI

@main
struct TidWebApp: App {
    @StateObject private var wikiViewModel = WikiViewModel()
    
    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(wikiViewModel)
        }
    }
} 