package com.autotrade.exec.app;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.autotrade.account.BrokerAccount;
import com.autotrade.account.BrokerType;
import com.autotrade.execution.BrokerSessions;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The day's Kite session of an account for the execution worker's reads (multi-broker plan step K1): the app key from
 * the account's credential profile (the secret store) and the access token from the file {@link KiteLogin} wrote at the
 * login. An expired token (its {@code expiresAt} passed, or the file gone) is no session. Nothing here is logged.
 */
@Component
class KiteSessionSource implements BrokerSessions {

    private static final Logger log = LoggerFactory.getLogger(KiteSessionSource.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;
    private final BrokerSecrets secrets;

    KiteSessionSource(DataSource dataSource, KiteLoginProperties props) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.secrets = new BrokerSecrets(Path.of(props.secretsDir()), Path.of(props.sessionsDir()));
    }

    @Override
    public Optional<Access> access(BrokerAccount account) {
        if (account.brokerType() != BrokerType.ZERODHA) {
            return Optional.empty();
        }
        try {
            String file = secrets.readToken(account.id());
            if (file == null) {
                return Optional.empty();
            }
            JsonNode session = JSON.readTree(file);
            String token = session.path("accessToken").asString("");
            String expires = session.path("expiresAt").asString("");
            if (token.isEmpty() || expires.isEmpty() || !Instant.parse(expires).isAfter(Instant.now())) {
                return Optional.empty();
            }
            List<String> keyRef = jdbc.queryForList("select p.api_key_ref from acct.broker_account a join "
                    + "acct.broker_credential_profile p on p.id = a.credential_profile_id where a.id = ? "
                    + "and p.status = 'ACTIVE' and p.broker_type = 'ZERODHA'", String.class, account.id());
            if (keyRef.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new Access(secrets.read(keyRef.getFirst()), token));
        } catch (RuntimeException e) {
            log.warn("account {}: Kite session not readable ({})", account.id(), e.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
