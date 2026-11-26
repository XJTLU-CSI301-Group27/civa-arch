package edu.xjtlu.civa.server.household;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.xjtlu.civa.server.application.CanonicalRequestDigest;
import edu.xjtlu.civa.server.application.IdempotencyExecutor;
import edu.xjtlu.civa.server.config.JsonbCodec;
import java.io.IOException;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
class HouseholdConfiguration {
    @Bean HouseholdSettingsService householdSettingsService(JdbcTemplate jdbc, HouseholdReadService read, PlatformTransactionManager manager) { return new HouseholdSettingsService(jdbc, read, manager); }
    @Bean HouseholdMembershipService householdMembershipService(JdbcTemplate jdbc, PlatformTransactionManager manager) { return new HouseholdMembershipService(jdbc, manager); }
    @Bean HouseholdOwnershipService householdOwnershipService(JdbcTemplate jdbc, ObjectMapper json, IdempotencyExecutor executor) {
        return new HouseholdOwnershipService(jdbc, json, executor);
    }
    @Bean InvitationReceiptCipher invitationReceiptCipher(@org.springframework.beans.factory.annotation.Value("${civa.media-dir}") String mediaDir) throws IOException {
        return new InvitationReceiptCipher(java.nio.file.Path.of(mediaDir).resolve(".private/invitation-receipt.key"));
    }
    @Bean HouseholdInvitationService householdInvitationService(JdbcTemplate jdbc, IdempotencyExecutor executor, JsonbCodec json, InvitationReceiptCipher receipts, PlatformTransactionManager manager) {
        return new HouseholdInvitationService(jdbc, executor, json, receipts, manager);
    }
    @Bean org.springframework.boot.ApplicationRunner householdInvitationBackfill(HouseholdInvitationService invitations) { return args -> invitations.provisionExisting(); }
    @Bean HouseholdReadService householdReadService(JdbcTemplate jdbc) { return new HouseholdReadService(jdbc); }
    @Bean IdempotencyExecutor idempotencyExecutor(JdbcTemplate jdbc, PlatformTransactionManager manager) { return new IdempotencyExecutor(jdbc, manager); }
    @Bean HouseholdWriteService householdWriteService(JdbcTemplate jdbc, IdempotencyExecutor executor, JsonbCodec json, HouseholdInvitationService invitations) {
        return new HouseholdWriteService(jdbc, executor, json, Clock.systemUTC(), invitations::provisionLocked);
    }
    @Bean JsonNode contractSchemas(ObjectMapper json) throws IOException {
        JsonNode schemas = json.readTree(new ClassPathResource("contracts/openapi.json").getInputStream()).path("components").path("schemas");
        if (!schemas.isObject()) throw new IllegalStateException("OpenAPI schemas missing");
        return schemas;
    }
    @Bean CanonicalRequestDigest canonicalRequestDigest(ObjectMapper json, JsonNode contractSchemas) {
        return new CanonicalRequestDigest(json, contractSchemas);
    }
}
