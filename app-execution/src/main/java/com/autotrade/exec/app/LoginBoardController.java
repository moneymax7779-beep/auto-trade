package com.autotrade.exec.app;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/execution/login-board}: today's broker sessions of the DRY_RUN / LIVE accounts (see {@link LoginBoard}).
 * Through the UI only the caller's tenants' accounts; the host's own call without the header (the off-site watchdog, over
 * IAP SSH) sees every account, as {@code /api/execution/status} does.
 */
@RestController
class LoginBoardController {

    private final LoginBoard board;
    private final Access access;

    LoginBoardController(LoginBoard board, Access access) {
        this.board = board;
        this.access = access;
    }

    @GetMapping("/api/execution/login-board")
    Map<String, Object> board(@RequestHeader(value = Access.HEADER, required = false) String subject) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        List<LoginBoard.Row> rows = board.rows();
        if (subject != null) {
            Access.User user = access.user(subject);
            rows = rows.stream().filter(r -> user.sees(r.tenantId())).toList();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("day", today.toString());
        out.put("tradingDay", board.tradingDay(today));
        out.put("accounts", rows.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("accountId", r.accountId());
            m.put("tenant", r.tenant());
            m.put("account", r.account());
            m.put("broker", r.broker());
            m.put("mode", r.mode());
            m.put("connection", r.connection());
            m.put("sessionUntil", r.sessionUntil() == null ? null : r.sessionUntil().toString());
            m.put("strategies", r.strategies());
            m.put("ready", r.ready());
            return m;
        }).toList());
        return out;
    }
}
