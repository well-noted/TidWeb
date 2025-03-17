import Foundation

struct WikiInstance: Codable, Identifiable {
    let id: UUID
    let url: URL
    let title: String
    let lastAccessed: Date
    let data: Data?
    
    init(url: URL, data: Data? = nil) {
        self.id = UUID()
        self.url = url
        self.title = url.lastPathComponent
        self.lastAccessed = Date()
        self.data = data
    }
    
    enum CodingKeys: String, CodingKey {
        case id
        case url
        case title
        case lastAccessed
    }
    
    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(UUID.self, forKey: .id)
        url = try container.decode(URL.self, forKey: .url)
        title = try container.decode(String.self, forKey: .title)
        lastAccessed = try container.decode(Date.self, forKey: .lastAccessed)
        data = nil
    }
    
    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encode(url, forKey: .url)
        try container.encode(title, forKey: .title)
        try container.encode(lastAccessed, forKey: .lastAccessed)
    }
} 