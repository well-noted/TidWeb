import SwiftUI
import WebKit
import Combine

class WikiViewModel: ObservableObject {
    @Published var currentWiki: WikiInstance?
    @Published var allWikis: [WikiInstance] = []
    @Published var isDarkMode: Bool = false
    @Published var isFrameVisible: Bool = true
    @Published var faviconMap: [String: UIImage] = [:]
    @Published var isLoading: Bool = false
    @Published var errorMessage: String?
    
    private var cancellables = Set<AnyCancellable>()
    private let fileManager = FileManager.default
    private let cacheManager = CacheManager()
    private let themeManager = ThemeManager()
    
    init() {
        setupTheme()
        loadSavedWikis()
    }
    
    private func setupTheme() {
        isDarkMode = themeManager.isDarkModeEnabled
    }
    
    private func loadSavedWikis() {
        // Load saved wiki instances from UserDefaults or other storage
        if let data = UserDefaults.standard.data(forKey: "savedWikis"),
           let wikis = try? JSONDecoder().decode([WikiInstance].self, from: data) {
            allWikis = wikis
            currentWiki = wikis.first
        }
    }
    
    func loadWiki(from url: URL) {
        isLoading = true
        
        // Check if wiki is already cached
        if let cachedWiki = cacheManager.getCachedWiki(for: url) {
            currentWiki = cachedWiki
            isLoading = false
            return
        }
        
        // Download and process wiki
        URLSession.shared.dataTask(with: url) { [weak self] data, response, error in
            guard let self = self,
                  let data = data,
                  error == nil else {
                DispatchQueue.main.async {
                    self?.errorMessage = error?.localizedDescription
                    self?.isLoading = false
                }
                return
            }
            
            // Process wiki data and create instance
            let wiki = WikiInstance(url: url, data: data)
            DispatchQueue.main.async {
                self.currentWiki = wiki
                self.allWikis.append(wiki)
                self.saveWikis()
                self.isLoading = false
            }
        }.resume()
    }
    
    private func saveWikis() {
        if let data = try? JSONEncoder().encode(allWikis) {
            UserDefaults.standard.set(data, forKey: "savedWikis")
        }
    }
    
    func toggleTheme() {
        isDarkMode.toggle()
        themeManager.isDarkModeEnabled = isDarkMode
    }
    
    func toggleFrameVisibility() {
        isFrameVisible.toggle()
    }
} 