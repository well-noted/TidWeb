import SwiftUI
import WebKit

struct ContentView: View {
    @EnvironmentObject private var viewModel: WikiViewModel
    @State private var showingFilePicker = false
    @State private var showingSettings = false
    
    var body: some View {
        NavigationView {
            ZStack {
                if let wiki = viewModel.currentWiki {
                    WikiWebView(wiki: wiki)
                        .edgesIgnoringSafeArea(.all)
                } else {
                    VStack(spacing: 20) {
                        Image(systemName: "doc.text")
                            .font(.system(size: 60))
                        Text("Welcome to TidWeb")
                            .font(.title)
                        Text("Open a TiddlyWiki file to get started")
                            .foregroundColor(.secondary)
                        Button("Open File") {
                            showingFilePicker = true
                        }
                        .buttonStyle(.borderedProminent)
                    }
                }
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button {
                        showingFilePicker = true
                    } label: {
                        Image(systemName: "doc.badge.plus")
                    }
                }
                
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        showingSettings = true
                    } label: {
                        Image(systemName: "gear")
                    }
                }
            }
            .sheet(isPresented: $showingFilePicker) {
                DocumentPicker { url in
                    viewModel.loadWiki(from: url)
                }
            }
            .sheet(isPresented: $showingSettings) {
                SettingsView()
            }
        }
    }
}

struct WikiWebView: UIViewRepresentable {
    let wiki: WikiInstance
    
    func makeUIView(context: Context) -> WKWebView {
        let webView = WKWebView()
        webView.navigationDelegate = context.coordinator
        
        if let data = wiki.data {
            webView.load(data, mimeType: "text/html", characterEncodingName: "UTF-8", baseURL: wiki.url)
        } else {
            webView.load(URLRequest(url: wiki.url))
        }
        
        return webView
    }
    
    func updateUIView(_ uiView: WKWebView, context: Context) {}
    
    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }
    
    class Coordinator: NSObject, WKNavigationDelegate {
        var parent: WikiWebView
        
        init(_ parent: WikiWebView) {
            self.parent = parent
        }
        
        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            // Handle navigation completion
        }
        
        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
            // Handle navigation errors
        }
    }
}

struct DocumentPicker: UIViewControllerRepresentable {
    let onPick: (URL) -> Void
    
    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.html])
        picker.delegate = context.coordinator
        return picker
    }
    
    func updateUIViewController(_ uiViewController: UIDocumentPickerViewController, context: Context) {}
    
    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }
    
    class Coordinator: NSObject, UIDocumentPickerDelegate {
        let parent: DocumentPicker
        
        init(_ parent: DocumentPicker) {
            self.parent = parent
        }
        
        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            guard let url = urls.first else { return }
            parent.onPick(url)
        }
    }
} 