import SwiftUI

struct SettingsView: View {
    @EnvironmentObject private var viewModel: WikiViewModel
    @Environment(\.dismiss) private var dismiss
    
    var body: some View {
        NavigationView {
            Form {
                Section("Appearance") {
                    Toggle("Dark Mode", isOn: $viewModel.isDarkMode)
                    Toggle("Show Frame", isOn: $viewModel.isFrameVisible)
                }
                
                Section("Storage") {
                    Button("Clear Cache") {
                        // Implement cache clearing
                    }
                    
                    Button("Clear History") {
                        // Implement history clearing
                    }
                }
                
                Section("About") {
                    HStack {
                        Text("Version")
                        Spacer()
                        Text("1.0.0")
                            .foregroundColor(.secondary)
                    }
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Done") {
                        dismiss()
                    }
                }
            }
        }
    }
} 