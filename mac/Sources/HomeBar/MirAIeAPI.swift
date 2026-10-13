import Foundation

struct MirAIeTokens: Codable {
    var accessToken: String
    var refreshToken: String
    var userId: String
    var expiresAt: Date
}

struct MirAIeCredentials: Codable {
    var login: String
    var password: String
}

enum MirAIeError: LocalizedError {
    case http(Int, String)
    case notLoggedIn
    case badResponse

    var errorDescription: String? {
        switch self {
        case .http(let code, let body): return "HTTP \(code): \(body.prefix(200))"
        case .notLoggedIn: return "Not signed in"
        case .badResponse: return "Unexpected server response"
        }
    }
}

/// REST client for the MirAIe cloud (see PROTOCOL.md §1).
actor MirAIeAPI {
    static let authBase = URL(string: "https://auth.miraie.in/simplifi/v1/")!
    static let apiBase = URL(string: "https://app.miraie.in/simplifi/v1/")!
    static let clientId = "292d99d7-5623-4157-8a71-846ee3ff5dbf"

    private(set) var tokens: MirAIeTokens?
    private var credentials: MirAIeCredentials?
    private let session: URLSession

    init() {
        let cfg = URLSessionConfiguration.default
        cfg.timeoutIntervalForRequest = 30
        session = URLSession(configuration: cfg)
        tokens = Keychain.load(MirAIeTokens.self, account: "tokens")
        credentials = Keychain.load(MirAIeCredentials.self, account: "credentials")
    }

    var isLoggedIn: Bool { tokens != nil }
    var userId: String? { tokens?.userId }

    // MARK: Auth

    func login(_ login: String, password: String) async throws {
        let creds = MirAIeCredentials(login: Self.normalize(login), password: password)
        try await performLogin(creds)
        credentials = creds
        Keychain.save(creds, account: "credentials")
    }

    func logout() {
        tokens = nil
        credentials = nil
        Keychain.delete(account: "tokens")
        Keychain.delete(account: "credentials")
    }

    /// Mobile numbers without a country code get +91 (MirAIe is India-only).
    static func normalize(_ login: String) -> String {
        let trimmed = login.trimmingCharacters(in: .whitespaces)
        if trimmed.contains("@") || trimmed.hasPrefix("+") { return trimmed }
        let digits = trimmed.filter(\.isNumber)
        return digits.count == 10 ? "+91" + digits : trimmed
    }

    private func performLogin(_ creds: MirAIeCredentials) async throws {
        var body: [String: Any] = [
            "clientId": Self.clientId,
            "password": creds.password,
            "scope": "an_\(DispatchTime.now().uptimeNanoseconds % 1_000_000_000)",
        ]
        body[creds.login.contains("@") ? "email" : "mobile"] = creds.login
        let json = try await postJSON(Self.authBase.appendingPathComponent("userManagement/login"), body)
        try storeTokens(json)
    }

    private func refresh() async throws {
        guard let refreshToken = tokens?.refreshToken else { throw MirAIeError.notLoggedIn }
        let json = try await postJSON(Self.authBase.appendingPathComponent("userManagement/tokenRefresh"),
                                      ["clientId": Self.clientId, "token": refreshToken])
        try storeTokens(json)
    }

    private func storeTokens(_ json: [String: Any]) throws {
        guard let access = json["accessToken"] as? String else { throw MirAIeError.badResponse }
        let expiresIn = (json["expiresIn"] as? Double) ?? Double(json["expiresIn"] as? String ?? "") ?? 3600
        let t = MirAIeTokens(
            accessToken: access,
            refreshToken: (json["refreshToken"] as? String) ?? tokens?.refreshToken ?? "",
            userId: (json["userId"] as? String) ?? tokens?.userId ?? "",
            expiresAt: Date().addingTimeInterval(expiresIn)
        )
        tokens = t
        Keychain.save(t, account: "tokens")
    }

    /// Refresh token, falling back to a full re-login with stored credentials.
    func renewSession() async throws {
        do {
            try await refresh()
        } catch {
            guard let creds = credentials else { throw MirAIeError.notLoggedIn }
            try await performLogin(creds)
        }
    }

    func validAccessToken() async throws -> String {
        guard let t = tokens else { throw MirAIeError.notLoggedIn }
        if t.expiresAt.timeIntervalSinceNow < 120 { try await renewSession() }
        guard let token = tokens?.accessToken else { throw MirAIeError.notLoggedIn }
        return token
    }

    // MARK: Requests

    /// Authenticated GET against the API base; retries once after renewing on 401.
    func get(_ path: String) async throws -> Any {
        guard let json = try await request("GET", path) else { throw MirAIeError.badResponse }
        return json
    }

    /// Authenticated request against the API base (`path` relative to `/simplifi/v1/`). `body` is a
    /// JSON object/array. Returns the decoded JSON, or nil for an empty response body (Completable
    /// endpoints). Retries once after renewing the session on 401.
    @discardableResult
    func request(_ method: String, _ path: String, query: [String: String] = [:], body: Any? = nil) async throws -> Any? {
        var comps = URLComponents(url: Self.apiBase.appendingPathComponent(path), resolvingAgainstBaseURL: false)!
        if !query.isEmpty {
            comps.queryItems = query.sorted { $0.key < $1.key }.map { URLQueryItem(name: $0.key, value: $0.value) }
        }
        guard let url = comps.url else { throw MirAIeError.badResponse }
        for attempt in 0..<2 {
            var req = URLRequest(url: url)
            req.httpMethod = method
            req.setValue("Bearer \(try await validAccessToken())", forHTTPHeaderField: "Authorization")
            req.setValue("application/json", forHTTPHeaderField: "Accept")
            if let body {
                req.setValue("application/json", forHTTPHeaderField: "Content-Type")
                req.httpBody = try JSONSerialization.data(withJSONObject: body)
            }
            let (data, resp) = try await session.data(for: req)
            let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
            if code == 401 && attempt == 0 {
                try await renewSession()
                continue
            }
            guard (200..<300).contains(code) else {
                throw MirAIeError.http(code, String(decoding: data, as: UTF8.self))
            }
            if data.isEmpty { return nil }
            return try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])
        }
        throw MirAIeError.badResponse
    }

    private func postJSON(_ url: URL, _ body: [String: Any]) async throws -> [String: Any] {
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try JSONSerialization.data(withJSONObject: body)
        let (data, resp) = try await session.data(for: req)
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(code) else {
            throw MirAIeError.http(code, String(decoding: data, as: UTF8.self))
        }
        guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw MirAIeError.badResponse
        }
        return json
    }
}
