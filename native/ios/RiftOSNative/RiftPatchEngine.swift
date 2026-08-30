import Foundation

final class RiftPatchEngine {
    private struct PatchEnvelope: Decodable {
        let format: String?
        let version: Int
        let title: String?
        let targetRepo: String?
        let targetBranch: String?
        let baseSnapshotSHA256: String?
        let createdAt: String?
        let changes: [PatchChange]

        enum CodingKeys: String, CodingKey {
            case format, version, title, changes
            case targetRepo = "target_repo"
            case targetBranch = "target_branch"
            case baseSnapshotSHA256 = "base_snapshot_sha256"
            case createdAt = "created_at"
        }
    }

    private struct PatchChange: Decodable {
        let action: String
        let path: String
        let newPath: String?
        let content: String?
        let baseSHA256: String?
        let hasBaseSHA256: Bool
        let reason: String?

        enum CodingKeys: String, CodingKey {
            case action, path, content, reason
            case newPath = "new_path"
            case baseSHA256 = "base_sha256"
        }

        init(from decoder: Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            var normalized = try container.decode(String.self, forKey: .action).lowercased()
            if normalized == "rename" { normalized = "move" }
            action = normalized
            path = try container.decode(String.self, forKey: .path)
            newPath = try container.decodeIfPresent(String.self, forKey: .newPath)
            content = try container.decodeIfPresent(String.self, forKey: .content)
            hasBaseSHA256 = container.contains(.baseSHA256)
            baseSHA256 = try container.decodeIfPresent(String.self, forKey: .baseSHA256)
            reason = try container.decodeIfPresent(String.self, forKey: .reason)
        }
    }

    private struct BackupEntry: Codable {
        let path: String
        let existed: Bool
        let backupPath: String?
    }

    private struct HistoryRecord: Codable {
        let id: String
        let title: String
        let appliedAt: String
        let patchCreatedAt: String?
        let targetRepo: String?
        let targetBranch: String?
        let changes: Int
        let backups: [BackupEntry]
    }

    enum PatchError: LocalizedError {
        case invalid(String)
        case conflict(String)

        var errorDescription: String? {
            switch self {
            case .invalid(let message), .conflict(let message): return message
            }
        }
    }

    private let workspace: RiftWorkspace
    private let fm = FileManager.default
    private let decoder = JSONDecoder()
    private let encoder: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
        return encoder
    }()

    init(workspace: RiftWorkspace) {
        self.workspace = workspace
    }

    func preview(data: Data) throws -> [String: Any] {
        let patch = try decode(data)
        let rows = try validate(patch)
        return [
            "valid": true,
            "title": patch.title ?? "AI patch",
            "targetRepo": patch.targetRepo ?? NSNull(),
            "targetBranch": patch.targetBranch ?? NSNull(),
            "changes": rows
        ]
    }

    func apply(data: Data) throws -> [String: Any] {
        let patch = try decode(data)
        _ = try validate(patch)

        let id = UUID().uuidString.lowercased()
        let historyRoot = workspace.rootURL.appendingPathComponent(".rift/history/\(id)", isDirectory: true)
        let payloadRoot = historyRoot.appendingPathComponent("payload", isDirectory: true)
        try fm.createDirectory(at: payloadRoot, withIntermediateDirectories: true)

        let affected = try affectedPaths(patch)
        var backups: [BackupEntry] = []

        do {
            for path in affected {
                let source = try workspace.url(for: path, allowRoot: false)
                let exists = fm.fileExists(atPath: source.path)
                if exists {
                    let backup = payloadRoot.appendingPathComponent(path)
                    try fm.createDirectory(at: backup.deletingLastPathComponent(), withIntermediateDirectories: true)
                    try fm.copyItem(at: source, to: backup)
                    backups.append(BackupEntry(path: path, existed: true, backupPath: "payload/\(path)"))
                } else {
                    backups.append(BackupEntry(path: path, existed: false, backupPath: nil))
                }
            }

            for change in patch.changes {
                let path = try workspace.normalize(change.path, allowEmpty: false)
                switch change.action {
                case "write":
                    guard let content = change.content else { throw PatchError.invalid("Write is missing content: \(path)") }
                    try workspace.writeText(path: path, text: content)
                case "delete":
                    try workspace.remove(path: path)
                case "move":
                    guard let newPathRaw = change.newPath else { throw PatchError.invalid("Move is missing new_path: \(path)") }
                    let newPath = try workspace.normalize(newPathRaw, allowEmpty: false)
                    try workspace.move(from: path, to: newPath)
                    if let content = change.content {
                        try workspace.writeText(path: newPath, text: content)
                    }
                default:
                    throw PatchError.invalid("Unsupported patch action: \(change.action)")
                }
            }

            let record = HistoryRecord(
                id: id,
                title: String((patch.title ?? "AI patch").prefix(160)),
                appliedAt: ISO8601DateFormatter().string(from: Date()),
                patchCreatedAt: patch.createdAt,
                targetRepo: patch.targetRepo,
                targetBranch: patch.targetBranch,
                changes: patch.changes.count,
                backups: backups
            )
            try encoder.encode(record).write(to: historyRoot.appendingPathComponent("record.json"), options: .atomic)
            try data.write(to: historyRoot.appendingPathComponent("patch.json"), options: .atomic)

            return [
                "applied": true,
                "historyId": id,
                "title": record.title,
                "changes": patch.changes.count,
                "rollbackAvailable": true
            ]
        } catch {
            try? restore(backups: backups, historyRoot: historyRoot)
            try? fm.removeItem(at: historyRoot)
            throw error
        }
    }

    func history() throws -> [[String: Any]] {
        let root = workspace.rootURL.appendingPathComponent(".rift/history", isDirectory: true)
        let directories = try fm.contentsOfDirectory(at: root, includingPropertiesForKeys: [.contentModificationDateKey], options: [.skipsHiddenFiles])
        var rows: [[String: Any]] = []
        for directory in directories {
            let recordURL = directory.appendingPathComponent("record.json")
            guard let data = try? Data(contentsOf: recordURL), let record = try? decoder.decode(HistoryRecord.self, from: data) else { continue }
            rows.append([
                "id": record.id,
                "title": record.title,
                "appliedAt": record.appliedAt,
                "changes": record.changes,
                "targetRepo": record.targetRepo ?? NSNull(),
                "targetBranch": record.targetBranch ?? NSNull()
            ])
        }
        return rows.sorted { String(describing: $0["appliedAt"] ?? "") > String(describing: $1["appliedAt"] ?? "") }
    }

    func rollback(id requestedID: String?) throws -> [String: Any] {
        let id: String
        if let requestedID, !requestedID.isEmpty {
            id = requestedID
        } else {
            guard let latest = try history().first?["id"] as? String else {
                throw PatchError.invalid("No workspace patch history is available")
            }
            id = latest
        }

        guard id.range(of: "^[a-zA-Z0-9-]+$", options: .regularExpression) != nil else {
            throw PatchError.invalid("Invalid history id")
        }
        let historyRoot = workspace.rootURL.appendingPathComponent(".rift/history/\(id)", isDirectory: true)
        let recordURL = historyRoot.appendingPathComponent("record.json")
        let recordData = try Data(contentsOf: recordURL)
        let record = try decoder.decode(HistoryRecord.self, from: recordData)
        try restore(backups: record.backups, historyRoot: historyRoot)

        let rolledBackRoot = workspace.rootURL.appendingPathComponent(".rift/rolled-back", isDirectory: true)
        try fm.createDirectory(at: rolledBackRoot, withIntermediateDirectories: true)
        let destination = rolledBackRoot.appendingPathComponent("\(id)-\(Int(Date().timeIntervalSince1970))")
        if fm.fileExists(atPath: destination.path) { try fm.removeItem(at: destination) }
        try fm.moveItem(at: historyRoot, to: destination)

        return [
            "rolledBack": true,
            "historyId": id,
            "title": record.title,
            "changes": record.changes
        ]
    }

    private func decode(_ data: Data) throws -> PatchEnvelope {
        let patch: PatchEnvelope
        do { patch = try decoder.decode(PatchEnvelope.self, from: data) }
        catch { throw PatchError.invalid("Patch JSON is invalid: \(error.localizedDescription)") }

        if let format = patch.format, format != "riftcity-ai-patch" {
            throw PatchError.invalid("Unsupported patch format: \(format)")
        }
        guard patch.version == 1 || patch.version == 2 else { throw PatchError.invalid("Patch version must be 1 or 2") }
        guard !patch.changes.isEmpty else { throw PatchError.invalid("Patch has no changes") }
        guard patch.changes.count <= 500 else { throw PatchError.invalid("Patch exceeds the 500-change native limit") }
        return patch
    }

    private func validate(_ patch: PatchEnvelope) throws -> [[String: Any]] {
        var used: [String] = []
        var rows: [[String: Any]] = []

        for change in patch.changes {
            guard ["write", "delete", "move"].contains(change.action) else {
                throw PatchError.invalid("Unsupported patch action: \(change.action)")
            }
            if patch.version == 1 && change.action == "move" { throw PatchError.invalid("Move/rename requires patch version 2") }

            let path = try workspace.normalize(change.path, allowEmpty: false)
            try workspace.assertEditable(path)
            try assertNoOverlap(path, used: used)
            used.append(path)

            let sourceURL = try workspace.url(for: path, allowRoot: false)
            let exists = fm.fileExists(atPath: sourceURL.path)
            let actualHash = exists ? try workspace.sha256(path: path) : nil

            if change.hasBaseSHA256 {
                if change.baseSHA256 == nil {
                    if change.action != "write" {
                        throw PatchError.conflict("\(change.action) cannot use base_sha256:null: \(path)")
                    }
                    if exists { throw PatchError.conflict("Patch expected a new file, but it already exists: \(path)") }
                } else {
                    guard exists else { throw PatchError.conflict("Patch expected this file to exist: \(path)") }
                    if actualHash?.lowercased() != change.baseSHA256?.lowercased() {
                        throw PatchError.conflict("Workspace file changed since the patch was created: \(path)")
                    }
                }
            }

            if change.action == "write" {
                guard change.content != nil else { throw PatchError.invalid("Write is missing content: \(path)") }
            } else if !exists {
                throw PatchError.conflict("Patch source does not exist: \(path)")
            }

            var row: [String: Any] = [
                "action": change.action,
                "path": path,
                "exists": exists,
                "baseHashMatches": change.hasBaseSHA256 ? ((change.baseSHA256 == nil && !exists) || change.baseSHA256?.lowercased() == actualHash?.lowercased()) : NSNull()
            ]
            if let reason = change.reason { row["reason"] = reason }

            if change.action == "move" {
                guard let rawNewPath = change.newPath else { throw PatchError.invalid("Move is missing new_path: \(path)") }
                let newPath = try workspace.normalize(rawNewPath, allowEmpty: false)
                try workspace.assertEditable(newPath)
                guard newPath != path else { throw PatchError.invalid("Move source and destination are identical: \(path)") }
                try assertNoOverlap(newPath, used: used)
                used.append(newPath)
                let destination = try workspace.url(for: newPath, allowRoot: false)
                if fm.fileExists(atPath: destination.path) { throw PatchError.conflict("Move destination already exists: \(newPath)") }
                row["newPath"] = newPath
            }
            rows.append(row)
        }
        return rows
    }

    private func assertNoOverlap(_ path: String, used: [String]) throws {
        for other in used {
            if path == other || path.hasPrefix(other + "/") || other.hasPrefix(path + "/") {
                throw PatchError.invalid("Patch paths overlap: \(path) and \(other)")
            }
        }
    }

    private func affectedPaths(_ patch: PatchEnvelope) throws -> [String] {
        var paths: [String] = []
        for change in patch.changes {
            let path = try workspace.normalize(change.path, allowEmpty: false)
            paths.append(path)
            if change.action == "move", let newPath = change.newPath {
                paths.append(try workspace.normalize(newPath, allowEmpty: false))
            }
        }
        return Array(Set(paths)).sorted()
    }

    private func restore(backups: [BackupEntry], historyRoot: URL) throws {
        // Remove every currently affected path first, deepest-first, then restore
        // the exact pre-patch state from the transaction payload.
        for backup in backups.sorted(by: { $0.path.count > $1.path.count }) {
            let target = try workspace.url(for: backup.path, allowRoot: false)
            if fm.fileExists(atPath: target.path) { try fm.removeItem(at: target) }
        }
        for backup in backups.filter({ $0.existed }).sorted(by: { $0.path.count < $1.path.count }) {
            guard let relativeBackup = backup.backupPath else { continue }
            let source = historyRoot.appendingPathComponent(relativeBackup)
            let target = try workspace.url(for: backup.path, allowRoot: false)
            try fm.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
            try fm.copyItem(at: source, to: target)
        }
    }
}
