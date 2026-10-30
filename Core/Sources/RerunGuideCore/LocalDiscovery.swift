import Foundation

/// LAN server discovery over UDP broadcast, each server's own protocol (none advertises Bonjour reliably):
/// Jellyfin/Emby answer "who is JellyfinServer?" / "who is EmbyServer?" on 7359 with JSON {Address, Id, Name};
/// Plex GDM answers "M-SEARCH * HTTP/1.1" on 32414 with an HTTP-style header block (Name, Port, Resource-Identifier).
/// Never throws: no entitlement (sendto EPERM), no reply, or no network → []. BSD sockets on purpose: NWConnection
/// cannot send to 255.255.255.255, and Jellyfin only answers the broadcast.
public enum LocalDiscovery {
    public static func find(kind: ServerKind) async -> [PlexServer] {
        let port: UInt16, probe: String
        switch kind {
        case .jellyfin: (port, probe) = (7359, "who is JellyfinServer?")
        case .emby: (port, probe) = (7359, "who is EmbyServer?")
        case .plex: (port, probe) = (32414, "M-SEARCH * HTTP/1.1\r\n\r\n")
        case .demo: return []
        }
        // Blocking socket calls on a GCD thread, not the cooperative pool.
        return await withCheckedContinuation { c in
            DispatchQueue.global(qos: .utility).async { c.resume(returning: scan(kind: kind, port: port, probe: probe, timeout: 1.5)) }
        }
    }

    private static func scan(kind: ServerKind, port: UInt16, probe: String, timeout: Double) -> [PlexServer] {
        let fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        guard fd >= 0 else { return [] }
        defer { close(fd) }
        var on: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_BROADCAST, &on, socklen_t(MemoryLayout<Int32>.size))
        var to = sockaddr_in()
        to.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        to.sin_family = sa_family_t(AF_INET)
        to.sin_port = port.bigEndian
        to.sin_addr.s_addr = 0xffffffff   // INADDR_BROADCAST (the macro's cast keeps it out of Swift)
        let sent = withUnsafePointer(to: &to) { p in
            p.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                Array(probe.utf8).withUnsafeBufferPointer { b in sendto(fd, b.baseAddress, b.count, 0, sa, socklen_t(MemoryLayout<sockaddr_in>.size)) }
            }
        }
        guard sent >= 0 else { return [] }   // EPERM without com.apple.developer.networking.multicast
        var found: [String: PlexServer] = [:]
        var buf = [UInt8](repeating: 0, count: 4096)
        let deadline = Date.now.addingTimeInterval(timeout)
        while true {
            let ms = Int32(deadline.timeIntervalSinceNow * 1000)
            guard ms > 0 else { break }
            var pfd = pollfd(fd: fd, events: Int16(POLLIN), revents: 0)
            guard poll(&pfd, 1, ms) > 0 else { break }
            var from = sockaddr_in()
            var len = socklen_t(MemoryLayout<sockaddr_in>.size)
            let n = withUnsafeMutablePointer(to: &from) { p in
                p.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in recvfrom(fd, &buf, buf.count, 0, sa, &len) }
            }
            guard n >= 0 else { break }   // an error stays queued, so poll would spin on it
            let data = Data(buf[0..<n]), ip = String(cString: inet_ntoa(from.sin_addr))
            if let s = kind == .plex ? plex(data, ip: ip) : jellyfin(data, ip: ip) { found[s.id] = s }
        }
        return Array(found.values)
    }

    private struct Reply: Decodable { let Address: String; let Id: String; let Name: String }

    /// The URL always points at the host the reply came from, never at an address the reply names (as the Plex path does),
    /// so a reply can't send the sign-in to some other server; any LAN device can still pose as one. A reply naming an IP
    /// keeps its scheme, port and path (a server in Docker names its container IP); one naming a DNS name, a reverse
    /// proxy's published URL, falls back to plain HTTP at the sender on the named HTTP port, or the default 8096.
    static func jellyfin(_ data: Data, ip: String) -> PlexServer? {
        guard let r = try? JSONDecoder().decode(Reply.self, from: data), let named = URLComponents(string: r.Address),
              let host = named.host, !host.isEmpty,
              let scheme = named.scheme?.lowercased(), scheme == "http" || scheme == "https" else { return nil }
        let namesIP = host.contains(":") || host.range(of: #"^\d{1,3}(\.\d{1,3}){3}$"#, options: .regularExpression) != nil
        var c = URLComponents()
        c.scheme = namesIP ? scheme : "http"; c.host = ip; c.percentEncodedPath = named.percentEncodedPath
        c.port = namesIP ? named.port : scheme == "http" ? named.port ?? 8096 : 8096
        guard let url = c.url else { return nil }
        return PlexServer(id: r.Id, name: r.Name, baseURL: url, accessToken: "")
    }

    static func plex(_ data: Data, ip: String) -> PlexServer? {
        let lines = String(decoding: data, as: UTF8.self).split(whereSeparator: \.isNewline)
        guard lines.first?.contains("200 OK") == true else { return nil }
        var h: [String: String] = [:]
        for line in lines.dropFirst() {
            guard let i = line.firstIndex(of: ":") else { continue }
            h[line[..<i].trimmingCharacters(in: .whitespaces)] = line[line.index(after: i)...].trimmingCharacters(in: .whitespaces)
        }
        guard let id = h["Resource-Identifier"], let port = UInt16(h["Port"] ?? "32400"), let url = URL(string: "http://\(ip):\(port)") else { return nil }
        return PlexServer(id: id, name: h["Name"] ?? ip, baseURL: url, accessToken: "")
    }
}
