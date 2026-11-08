package com.autotrade.exec.app;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.autotrade.account.BrokerAccount;
import com.autotrade.account.BrokerType;
import com.autotrade.execution.BrokerSessions;
import com.autotrade.execution.KotakDryRunAdapter;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The day's Kotak Neo session of an account for the execution worker's reads (live adapters plan, step C): the file
 * {@code kotak-<id>.token} {@link KotakNeoLogin} wrote at the login ({@code token}, {@code sid}, {@code baseUrl},
 * {@code expiresAt}) and the consumer key from the account's broker app ({@code api_key_ref}, the secret store). The
 * token is {@link BrokerSessions.Access#accessToken()}, the sid and base URL go in {@link BrokerSessions.Access#extra()}.
 * An expired or missing file is no session. Nothing here is logged.
 */
@Component
class KotakSessionSource implements BrokerSessions {

    private static final Logger log = LoggerFactory.getLogger(KotakSessionSource.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;
    private final BrokerSecrets secrets;

    KotakSessionSource(DataSource dataSource, KiteLoginProperties props, BrokerLoginProperties brokers) {
        this(dataSource, new BrokerSecrets(Path.of(props.secretsDir()), Path.of(props.sessionsDir()),
                Path.of(brokers.storeDir())));
    }

    KotakSessionSource(DataSource dataSource, BrokerSecrets secrets) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.secrets = secrets;
    }

    @Override
    public Optional<Access> access(BrokerAccount account) {
        if (account.brokerType() != BrokerType.KOTAK_NEO) {
            return Optional.empty();
        }
        try {
            String file = secrets.readToken("kotak", account.id());
            if (file == null) {
                return Optional.empty();
            }
            JsonNode session = JSON.readTree(file);
            String token = session.path("token").asString(""), sid = session.path("sid").asString("");
            String base = session.path("baseUrl").asString(""), expires = session.path("expiresAt").asString("");
            if (token.isEmpty() || sid.isEmpty() || base.isEmpty() || expires.isEmpty()
                    || !Instant.parse(expires).isAfter(Instant.now())) {
                return Optional.empty();
            }
            if (session.has("accountId") && session.path("accountId").asLong(-1) != account.id()) {
                return Optional.empty();                    // another account's file: never used
            }
            List<String> keyRef = jdbc.queryForList("select p.api_key_ref from acct.broker_account a join "
                    + "acct.broker_credential_profile p on p.id = a.credential_profile_id where a.id = ? "
                    + "and p.status = 'ACTIVE' and p.broker_type = 'KOTAK_NEO'", String.class, account.id());
            if (keyRef.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new Access(secrets.read(keyRef.getFirst()), token,
                    Map.of(KotakDryRunAdapter.SID, sid, KotakDryRunAdapter.BASE_URL, base)));
        } catch (RuntimeException e) {
            log.warn("account {}: Kotak session not readable ({})", account.id(), e.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
