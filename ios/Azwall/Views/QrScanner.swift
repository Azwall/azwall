import SwiftUI
import VisionKit

/// Full-screen camera that reads one QR code and returns its text: Apple's DataScannerViewController (VisionKit),
/// on the device, nothing leaves it. iOS asks for the camera the first time (NSCameraUsageDescription).
struct QrScanner: View {
    var hint = "Point at an Aztec address QR code"
    let onResult: (String) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ZStack(alignment: .topLeading) {
            if DataScannerViewController.isSupported && DataScannerViewController.isAvailable {
                ScannerView { text in
                    onResult(text)
                    dismiss()
                }
                .ignoresSafeArea()
            } else {
                Text("The camera is not available on this device.").foregroundStyle(.white).frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            Button { dismiss() } label: { Image(systemName: "xmark").font(.title2).foregroundStyle(.white).padding() }
                .accessibilityLabel("Close")
        }
        .background(.black)
        .overlay(alignment: .bottom) { Text(hint).foregroundStyle(.white).padding(48) }
    }
}

private struct ScannerView: UIViewControllerRepresentable {
    let onResult: (String) -> Void

    func makeUIViewController(context: Context) -> DataScannerViewController {
        let scanner = DataScannerViewController(recognizedDataTypes: [.barcode(symbologies: [.qr])], isHighlightingEnabled: true)
        scanner.delegate = context.coordinator
        try? scanner.startScanning()
        return scanner
    }

    func updateUIViewController(_ controller: DataScannerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(onResult: onResult) }

    @MainActor
    final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        let onResult: (String) -> Void
        private var done = false

        init(onResult: @escaping (String) -> Void) {
            self.onResult = onResult
        }

        func dataScanner(_ scanner: DataScannerViewController, didAdd items: [RecognizedItem], allItems: [RecognizedItem]) {
            guard !done else { return }
            for case .barcode(let code) in items {
                guard let text = code.payloadStringValue else { continue }
                done = true
                scanner.stopScanning()
                onResult(text)
                return
            }
        }
    }
}
