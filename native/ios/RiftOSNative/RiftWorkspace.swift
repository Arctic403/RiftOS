import Foundation
import CryptoKit

final class RiftWorkspace {
    static let shared = RiftWorkspace()

    enum WorkspaceError: LocalizedError {
        case invalidPath(String)
        case protectedPath(String)
        case outsideWorkspace(String)
        case missing(String)
        case expectedFile(String)
        case expectedDirectory(String)

        var errorDescription: String? {
            switch self {
            case .invalidPath(let path): return "Invalid workspace path: \(path)"
            case .protectedPath(let path): return "RiftOS metadata is protected from workspace edits: \(path)"
            case .outsideWorkspace(let path): return "Path escapes the RiftOS workspace: \(path)"
            case .missing(let path): return "Workspace item does not exist: \(path)"
            case .expectedFile(let path): return "Expected a file: \(path)"
            case .expectedDirectory(let path): return "Expected a directory: \(path)"
            }
        }
    }

    let rootURL: URL
    private let fm = FileManager.default

    private init() {
        let documents = fm.urls(for: .documentDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
        rootURL = documents.appendingPathComponent("RiftWorkspace", isDirectory: true)
        try? ensureLayout()
    }

    func ensureLayout() throws {
        try fm.createDirectory(at: rootURL, withIntermediateDirectories: true)
        for path in ["projects", "downloads", "documents", "patches", ".rift", ".rift/history"] {
            try fm.createDirectory(at: rootURL.appendingPathComponent(path, isDirectory: true), withIntermediateDirectories: true)
        }

        let metadataURL = rootURL.appendingPathComponent(".rift/workspace.json")
        if !fm.fileExists(atPath: metadataURL.path) {
            let metadata: [String: Any] = [
                "format": "riftos-native-workspace",
                "version": 1,
                "created_at": ISO8601DateFormatter().string(from: Date()),
                "folders": ["projects", "downloads", "documents", "patches"]
            ]
            let data = try JSONSerialization.data(withJSONObject: metadata, options: [.prettyPrinted, .sortedKeys])
            try data.write(to: metadataURL, options: .atomic)
        }
    }

    func normalize(_ raw: String, allowEmpty: Bool = true) throws -> String {
        let path = raw.replacingOccurrences(of: "\\", with: "/").trimmingCharacters(in: .whitespacesAndNewlines)
        if path.isEmpty {
            if allowEmpty { return "" }
            throw WorkspaceError.invalidPath(raw)
        }
        guard !path.hasPrefix("/"), !path.contains("\0") else { throw WorkspaceError.invalidPath(raw) }

        let pieces = path.split(separator: "/", omittingEmptySubsequences: true).map(String.init)
        guard !pieces.contains("."), !pieces.contains("..") else { throw WorkspaceError.invalidPath(raw) }
        let normalized = pieces.joined(separator: "/")
        if normalized.isEmpty && !allowEmpty { throw WorkspaceError.invalidPath(raw) }
        return normalized
    }

    func assertEditable(_ relativePath: String) throws {
        let path = try normalize(relativePath, allowEmpty: false)
        if path == ".rift" || path.hasPrefix(".rift/") {
            throw WorkspaceError.protectedPath(path)
        }
    }

    func url(for relativePath: String, allowRoot: Bool = true) throws -> URL {
        let relative = try normalize(relativePath, allowEmpty: allowRoot)
        let candidate = relative.isEmpty ? rootURL : rootURL.appendingPathComponent(relative)
        let standardizedRoot = rootURL.standardizedFileURL
        let standardized = candidate.standardizedFileURL
        guard isInside(standardized, root: standardizedRoot) else {
            throw WorkspaceError.outsideWorkspace(relativePath)
        }
        if relative.isEmpty { return standardizedRoot }

        // Resolve any existing symlinks in the parent and candidate so a mounted or
        // malicious workspace cannot redirect reads/writes outside the sandbox.
        let resolvedRoot = standardizedRoot.resolvingSymlinksInPath()
        let resolvedParent = standardized.deletingLastPathComponent().resolvingSymlinksInPath()
        guard isInside(resolvedParent, root: resolvedRoot) else {
            throw WorkspaceError.outsideWorkspace(relativePath)
        }
        if fm.fileExists(atPath: standardized.path) {
            let resolvedCandidate = standardized.resolvingSymlinksInPath()
            guard isInside(resolvedCandidate, root: resolvedRoot) else {
                throw WorkspaceError.outsideWorkspace(relativePath)
            }
        }
        return standardized
    }

    private func isInside(_ candidate: URL, root: URL) -> Bool {
        let candidatePath = candidate.path
        let rootPath = root.path
        return candidatePath == rootPath || candidatePath.hasPrefix(rootPath + "/")
    }

    func info() throws -> [String: Any] {
        try ensureLayout()
        let values = try rootURL.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
        let rows = try list(path: "", recursive: true, includeHidden: false)
        let files = rows.filter { ($0["kind"] as? String) == "file" }
        let bytes = files.reduce(Int64(0)) { $0 + Int64($1["size"] as? Int ?? 0) }
        return [
            "name": "RiftWorkspace",
            "kind": "native-sandbox",
            "writable": true,
            "visibleInFiles": true,
            "fileCount": files.count,
            "bytes": bytes,
            "availableCapacity": values.volumeAvailableCapacityForImportantUsage ?? 0,
            "folders": ["projects", "downloads", "documents", "patches"]
        ]
    }

    func list(path: String, recursive: Bool, includeHidden: Bool) throws -> [[String: Any]] {
        let base = try url(for: path)
        var isDirectory: ObjCBool = false
        guard fm.fileExists(atPath: base.path, isDirectory: &isDirectory) else {
            throw WorkspaceError.missing(path)
        }
        guard isDirectory.boolValue else { throw WorkspaceError.expectedDirectory(path) }

        let keys: [URLResourceKey] = [.isDirectoryKey, .fileSizeKey, .contentModificationDateKey, .isSymbolicLinkKey]
        var rows: [[String: Any]] = []
        let options: FileManager.DirectoryEnumerationOptions = includeHidden ? [.skipsPackageDescendants] : [.skipsHiddenFiles, .skipsPackageDescendants]

        if recursive {
            guard let enumerator = fm.enumerator(at: base, includingPropertiesForKeys: keys, options: options) else { return [] }
            for case let item as URL in enumerator {
                rows.append(descriptor(item))
            }
        } else {
            let children = try fm.contentsOfDirectory(at: base, includingPropertiesForKeys: keys, options: includeHidden ? [] : [.skipsHiddenFiles])
            rows = children.map(descriptor)
        }
        return rows.sorted { String(describing: $0["path"] ?? "") < String(describing: $1["path"] ?? "") }
    }

    func stat(path: String) throws -> [String: Any]? {
        let item = try url(for: path)
        guard fm.fileExists(atPath: item.path) else { return nil }
        return descriptor(item)
    }

    func readText(path: String) throws -> String {
        let item = try url(for: path, allowRoot: false)
        var isDirectory: ObjCBool = false
        guard fm.fileExists(atPath: item.path, isDirectory: &isDirectory) else { throw WorkspaceError.missing(path) }
        guard !isDirectory.boolValue else { throw WorkspaceError.expectedFile(path) }
        return try String(contentsOf: item, encoding: .utf8)
    }

    @discardableResult
    func writeText(path: String, text: String, protectMetadata: Bool = true) throws -> [String: Any] {
        if protectMetadata { try assertEditable(path) }
        let item = try url(for: path, allowRoot: false)
        try fm.createDirectory(at: item.deletingLastPathComponent(), withIntermediateDirectories: true)
        try text.write(to: item, atomically: true, encoding: .utf8)
        return descriptor(item)
    }

    func makeDirectory(path: String, protectMetadata: Bool = true) throws {
        if protectMetadata { try assertEditable(path) }
        let item = try url(for: path, allowRoot: false)
        try fm.createDirectory(at: item, withIntermediateDirectories: true)
    }

    func remove(path: String, protectMetadata: Bool = true) throws {
        if protectMetadata { try assertEditable(path) }
        let item = try url(for: path, allowRoot: false)
        if fm.fileExists(atPath: item.path) { try fm.removeItem(at: item) }
    }

    func move(from sourcePath: String, to destinationPath: String, protectMetadata: Bool = true) throws {
        if protectMetadata {
            try assertEditable(sourcePath)
            try assertEditable(destinationPath)
        }
        let source = try url(for: sourcePath, allowRoot: false)
        let destination = try url(for: destinationPath, allowRoot: false)
        guard fm.fileExists(atPath: source.path) else { throw WorkspaceError.missing(sourcePath) }
        guard !fm.fileExists(atPath: destination.path) else {
            throw NSError(domain: "RiftWorkspace", code: 9, userInfo: [NSLocalizedDescriptionKey: "Move destination already exists: \(destinationPath)"])
        }
        try fm.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true)
        try fm.moveItem(at: source, to: destination)
    }

    func copyItem(from source: URL, to destinationPath: String) throws -> [String: Any] {
        try assertEditable(destinationPath)
        let destination = try url(for: destinationPath, allowRoot: false)
        guard !fm.fileExists(atPath: destination.path) else {
            throw NSError(domain: "RiftWorkspace", code: 10, userInfo: [NSLocalizedDescriptionKey: "Workspace destination already exists: \(destinationPath)"])
        }
        try fm.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true)
        try fm.copyItem(at: source, to: destination)
        return descriptor(destination)
    }

    func copyItemToExternal(path: String, destination: URL) throws {
        let source = try url(for: path, allowRoot: false)
        guard fm.fileExists(atPath: source.path) else { throw WorkspaceError.missing(path) }
        guard !fm.fileExists(atPath: destination.path) else {
            throw NSError(domain: "RiftWorkspace", code: 11, userInfo: [NSLocalizedDescriptionKey: "External destination already exists"])
        }
        try fm.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true)
        try fm.copyItem(at: source, to: destination)
    }

    func sha256(path: String) throws -> String? {
        let item = try url(for: path, allowRoot: false)
        var isDirectory: ObjCBool = false
        guard fm.fileExists(atPath: item.path, isDirectory: &isDirectory) else { return nil }
        guard !isDirectory.boolValue else { return nil }
        let data = try Data(contentsOf: item)
        return Self.sha256(data)
    }

    static func sha256(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    private func descriptor(_ item: URL) -> [String: Any] {
        let values = try? item.resourceValues(forKeys: [.isDirectoryKey, .fileSizeKey, .contentModificationDateKey, .isSymbolicLinkKey])
        let rootPath = rootURL.standardizedFileURL.path
        let itemPath = item.standardizedFileURL.path
        let relative = itemPath == rootPath ? "" : String(itemPath.dropFirst(min(itemPath.count, rootPath.count + 1)))
        return [
            "path": relative,
            "name": item.lastPathComponent,
            "kind": values?.isDirectory == true ? "directory" : "file",
            "size": values?.fileSize ?? 0,
            "modified": Int((values?.contentModificationDate?.timeIntervalSince1970 ?? 0) * 1000),
            "symlink": values?.isSymbolicLink == true
        ]
    }
}
