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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The day's Groww session of a member's account for the execution worker's reads (live adapters plan, step C): the file
 * {@code groww-<id>.token} {@link GrowwLogin} wrote at the member's approval login ({@code ucc}, {@code accessToken},
 * {@code accountId}, {@code expiresAt}: the token's own expiry, else the next 06:00 IST) and the API key from the
 * account's active GROWW broker app ({@code api_key_ref}, the secret store). The bearer token is
 * {@link BrokerSessions.Access#accessToken()}; nothing else is needed for Groww's calls.
 * <p>
 * Only this account's own session file is read, and it must name this account and the Groww client (UCC) pinned on it
 * ({@code external_broker_user_id}, which the login also checked). An expired, missing or foreign file is no session.
 * Nothing here is logged but the account and the failure's class.
 */
@Component
class GrowwSessionSource implements BrokerSessions {

    private static final Logger log = LoggerFactory.getLogger(GrowwSessionSource.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;
    private final BrokerSecrets secrets;

    @org.springframework.beans.factory.annotation.Autowired      // two constructors: Spring must be told which
    GrowwSessionSource(DataSource dataSource, KiteLoginProperties props, BrokerLoginProperties brokers) {
        this(dataSource, new BrokerSecrets(Path.of(props.secretsDir()), Path.of(props.sessionsDir()),
                Path.of(brokers.storeDir())));
    }

    GrowwSessionSource(DataSource dataSource, BrokerSecrets secrets) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.secrets = secrets;
    }

    @Override
    public Optional<Access> access(BrokerAccount account) {
        if (account.brokerType() != BrokerType.GROWW) {
            return Optional.empty();
        }
        try {
            String file = secrets.readToken("groww", account.id());
            if (file == null) {
                return Optional.empty();
            }
            JsonNode session = JSON.readTree(file);
            String token = session.path("accessToken").asString(""), user = session.path("ucc").asString("");
            String expires = session.path("expiresAt").asString("");
            if (token.isEmpty() || user.isEmpty() || expires.isEmpty() || !Instant.parse(expires).isAfter(Instant.now())) {
                return Optional.empty();
            }
            if (session.path("accountId").asLong(-1) != account.id()) {
                return Optional.empty();                    // another account's file (or none named): never used
            }
            List<Map<String, Object>> app = jdbc.queryForList("select p.api_key_ref, a.external_broker_user_id from "
                    + "acct.broker_account a join acct.broker_credential_profile p on p.id = a.credential_profile_id "
                    + "where a.id = ? and p.status = 'ACTIVE' and p.broker_type = 'GROWW'", account.id());
            if (app.isEmpty()) {
                return Optional.empty();
            }
            Object client = app.getFirst().get("external_broker_user_id");
            if (client == null || !user.equalsIgnoreCase(client.toString())) {
                return Optional.empty();                    // not the Groww client (UCC) pinned on this account
            }
            return Optional.of(new Access(secrets.read((String) app.getFirst().get("api_key_ref")), token));
        } catch (RuntimeException e) {
            log.warn("account {}: Groww session not readable ({})", account.id(), e.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
