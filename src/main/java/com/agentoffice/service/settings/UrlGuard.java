package com.agentoffice.service.settings;

import com.agentoffice.exception.AppException;
import com.agentoffice.exception.ErrorCode;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Chặn SSRF cho địa chỉ AI/Hermes do người dùng nhập: chỉ http/https, không user:pass@, và MỌI địa chỉ IP mà tên máy phân giải ra
 * phải thuộc mạng tin cậy: loopback, mạng riêng (10/8, 172.16/12, 192.168/16), CGNAT 100.64/10 (Tailscale, NetBird), IPv6 ULA fc00::/7.
 * Link-local (169.254.x.x: metadata đám mây), any-local, multicast bị chặn tuyệt đối. Tên máy khác phải nằm trong agentoffice.settings.allowed-hosts.
 * ponytail: kiểm lúc lưu, không kiểm lại lúc gọi nên DNS đổi sau đó (rebinding) không bị bắt; chỉ chủ mới đặt được địa chỉ.
 */
@Component
public class UrlGuard {
    private final Set<String> allowedHosts;

    public UrlGuard(@Value("${agentoffice.settings.allowed-hosts:}") String allowed) {
        this.allowedHosts = Arrays.stream(allowed.split(",")).map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }

    /** Ném VALIDATION (tiếng Việt, nêu tên trường) nếu địa chỉ không an toàn; trả về địa chỉ đã cắt khoảng trắng và dấu / cuối. */
    public String check(String field, String url) {
        String u = url == null ? "" : url.trim();
        URI uri;
        try {
            uri = URI.create(u);
        } catch (IllegalArgumentException e) {
            throw bad(field, "không phải địa chỉ hợp lệ");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) throw bad(field, "phải bắt đầu bằng http:// hoặc https://");
        if (uri.getUserInfo() != null) throw bad(field, "không được chứa tên đăng nhập/mật khẩu trong địa chỉ");
        String host = uri.getHost();
        if (host == null || host.isBlank()) throw bad(field, "thiếu tên máy");
        InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw bad(field, "không phân giải được tên máy \"" + host + "\"");
        }
        boolean allowlisted = allowedHosts.contains(host.toLowerCase(Locale.ROOT));
        for (InetAddress a : addrs) {
            if (a.isAnyLocalAddress() || a.isMulticastAddress() || a.isLinkLocalAddress())
                throw bad(field, "địa chỉ " + a.getHostAddress() + " bị chặn (link-local/metadata/multicast)");
            if (!allowlisted && !trusted(a))
                throw bad(field, "địa chỉ " + a.getHostAddress() + " nằm ngoài mạng nội bộ; muốn dùng hãy thêm \"" + host + "\" vào AI_ALLOWED_HOSTS trong .env");
        }
        return u.replaceAll("/+$", "");
    }

    static boolean trusted(InetAddress a) {
        if (a.isLoopbackAddress() || a.isSiteLocalAddress()) return true;
        byte[] b = a.getAddress();
        if (b.length == 4) return (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 0x40;   // 100.64.0.0/10
        return (b[0] & 0xFE) == 0xFC;                                              // fc00::/7
    }

    private static AppException bad(String field, String why) {
        return new AppException(ErrorCode.VALIDATION, field + ": " + why);
    }
}
