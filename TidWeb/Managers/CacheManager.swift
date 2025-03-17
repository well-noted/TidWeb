import Foundation

class CacheManager {
    private let fileManager = FileManager.default
    private let cacheDirectory: URL
    
    init() {
        let paths = fileManager.urls(for: .cachesDirectory, in: .userDomainMask)
        cacheDirectory = paths[0].appendingPathComponent("TidWebCache")
        createCacheDirectoryIfNeeded()
    }
    
    private func createCacheDirectoryIfNeeded() {
        do {
            try fileManager.createDirectory(at: cacheDirectory, withIntermediateDirectories: true)
        } catch {
            print("Error creating cache directory: \(error)")
        }
    }
    
    func cacheWiki(_ wiki: WikiInstance) {
        guard let data = wiki.data else { return }
        
        let fileURL = cacheDirectory.appendingPathComponent(wiki.id.uuidString)
        do {
            try data.write(to: fileURL)
        } catch {
            print("Error caching wiki: \(error)")
        }
    }
    
    func getCachedWiki(for url: URL) -> WikiInstance? {
        // Find cached file by URL
        do {
            let files = try fileManager.contentsOfDirectory(at: cacheDirectory, includingPropertiesForKeys: nil)
            for file in files {
                if let data = try? Data(contentsOf: file) {
                    let wiki = WikiInstance(url: url, data: data)
                    return wiki
                }
            }
        } catch {
            print("Error reading cache: \(error)")
        }
        return nil
    }
    
    func clearCache() {
        do {
            let files = try fileManager.contentsOfDirectory(at: cacheDirectory, includingPropertiesForKeys: nil)
            for file in files {
                try fileManager.removeItem(at: file)
            }
        } catch {
            print("Error clearing cache: \(error)")
        }
    }
} 