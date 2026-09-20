package org.example.privacy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.example.auth.AuthenticatedUser;
import org.example.entity.DatasetMetadata;
import org.example.entity.RegisteredDataset;
import org.example.exception.RegistrationException;
import org.example.mapper.DatasetRegistrationMapper;
import org.example.privacy.PrivacyComputeModels.InputSnapshotRecord;
import org.example.privacy.PrivacyComputeModels.InputSpec;
import org.example.privacy.PrivacyComputeModels.DatasetOwnershipRecord;
import org.example.privacy.PrivacyComputeModels.JobSpec;
import org.example.privacy.PrivacyComputeModels.ParticipantSpec;
import org.example.privacy.PrivacyComputeModels.TemplateDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Resolves caller references to immutable, catalog-owned dataset version metadata. */
@Component
public class PrivacyJobSpecResolver {
    private final DatasetRegistrationMapper datasets;
    private final PrivacyComputeMapper privacyMapper;
    private final ObjectMapper objectMapper;

    @Autowired
    public PrivacyJobSpecResolver(DatasetRegistrationMapper datasets, PrivacyComputeMapper privacyMapper,
                                  ObjectMapper objectMapper) {
        this.datasets = datasets;
        this.privacyMapper = privacyMapper;
        this.objectMapper = objectMapper;
    }

    /** Compatibility constructor used only by legacy unit tests and stored-spec utilities. */
    PrivacyJobSpecResolver(DatasetRegistrationMapper datasets, ObjectMapper objectMapper) {
        this(datasets, null, objectMapper);
    }

    public ResolvedSpec resolve(JobSpec request, TemplateDefinition template, AuthenticatedUser initiator) {
        if (request == null) throw RegistrationException.invalid("PRIVACY_SPEC_REQUIRED", "request body is required");
        if (template == null) throw RegistrationException.invalid("PRIVACY_TEMPLATE_UNKNOWN", "unknown privacy template");
        if (initiator == null || initiator.getUserId() == null) {
            throw RegistrationException.invalid("PRIVACY_PRINCIPAL_REQUIRED", "authenticated user is required");
        }
        if (request.getSecurityProfile() != null
                && !template.getSecurityProfile().equals(request.getSecurityProfile())) {
            throw RegistrationException.invalid("SECURITY_PROFILE_MISMATCH",
                    "securityProfile must match the selected template; downgrade is forbidden");
        }
        if (request.getParticipants() != null && !request.getParticipants().isEmpty()) {
            throw RegistrationException.invalid("PARTICIPANTS_SERVER_MANAGED",
                    "participants and runtime parties are resolved from dataset ownership");
        }
        List<InputSpec> supplied = request.getInputs();
        if (supplied == null || supplied.size() != template.getParticipantCount()) {
            throw RegistrationException.invalid("INPUT_COUNT_MISMATCH",
                    "template requires exactly " + template.getParticipantCount() + " dataset inputs");
        }
        Map<String, InputSpec> bySlot = new LinkedHashMap<>();
        for (InputSpec input : supplied) {
            if (input == null || blank(input.getSlotId()) || input.getDatasetId() == null) {
                throw RegistrationException.invalid("INPUT_INVALID", "slotId and datasetId are required");
            }
            String slot = input.getSlotId().trim().toUpperCase();
            if (bySlot.put(slot, input) != null) {
                throw RegistrationException.invalid("INPUT_SLOT_DUPLICATE", "duplicate slotId: " + slot);
            }
        }
        LinkedHashMap<String, String> runtimeRoles = new LinkedHashMap<>(template.getRequiredRoles());
        List<String> runtimeParties = new ArrayList<>(runtimeRoles.keySet());
        Set<String> expectedSlots = new LinkedHashSet<>();
        for (int i = 0; i < runtimeParties.size(); i++) expectedSlots.add("P" + i);
        if (!bySlot.keySet().equals(expectedSlots)) {
            throw RegistrationException.invalid("INPUT_SLOTS_MISMATCH", "inputs must be exactly " + expectedSlots);
        }

        JobSpec normalized = new JobSpec();
        normalized.setTemplateId(template.getTemplateId());
        normalized.setSecurityProfile(template.getSecurityProfile());
        normalized.setTimeoutSeconds(resolveTimeout(request.getTimeoutSeconds(), template));
        // Providers operate on A/B/C runtime slots. The control plane alone releases the result to the initiator.
        normalized.setResultRecipients(Collections.singletonList(runtimeParties.get(0)));
        List<InputSnapshotRecord> snapshots = new ArrayList<>();
        Set<Long> domains = new HashSet<>();
        Set<Long> datasetsSeen = new HashSet<>();
        for (int i = 0; i < runtimeParties.size(); i++) {
            String slot = "P" + i;
            String party = runtimeParties.get(i);
            InputSpec input = bySlot.get(slot);
            if (!datasetsSeen.add(input.getDatasetId())) {
                throw RegistrationException.invalid("DATASET_DUPLICATE", "a dataset can be selected only once");
            }
            DatasetOwnershipRecord ownership = privacyMapper == null ? null
                    : privacyMapper.findDatasetOwnership(input.getDatasetId());
            validateOwnership(ownership, input.getDatasetId());
            if (!domains.add(ownership.getOwnerDomainId())) {
                throw RegistrationException.invalid("PARTICIPANT_DOMAIN_DUPLICATE",
                        "each input must belong to a different collaboration domain");
            }
            ParticipantSpec source = new ParticipantSpec();
            source.setDatasetId(String.valueOf(input.getDatasetId()));
            source.setDatasetVersion(input.getDatasetVersion());
            source.setFields(input.getFields());
            ResolvedParticipant resolved = resolveParticipant(party, runtimeRoles.get(party), source);
            resolved.participant.setSlotId(slot);
            resolved.participant.setOwnerUserId(ownership.getOwnerUserId());
            resolved.participant.setOwnerUsername(ownership.getOwnerUsername());
            resolved.participant.setOwnerDomainId(ownership.getOwnerDomainId());
            resolved.participant.setOwnerDomainCode(ownership.getOwnerDomainCode());
            resolved.snapshot.setSlotId(slot);
            resolved.snapshot.setOwnerUserId(ownership.getOwnerUserId());
            resolved.snapshot.setOwnerDomainId(ownership.getOwnerDomainId());
            normalized.getParticipants().add(resolved.participant);
            snapshots.add(resolved.snapshot);
            InputSpec frozenInput = new InputSpec();
            frozenInput.setSlotId(slot);
            frozenInput.setDatasetId(input.getDatasetId());
            frozenInput.setDatasetVersion(resolved.participant.getDatasetVersion());
            frozenInput.setFields(resolved.participant.getFields());
            normalized.getInputs().add(frozenInput);
        }
        normalized.setEnginePolicy(resolveEnginePolicy(request.getEnginePolicy(), template,
                normalized.getParticipants(), normalized.getResultRecipients()));
        String specJson = canonicalJson(normalized);
        return new ResolvedSpec(normalized, snapshots, specJson, sha256(specJson));
    }

    private void validateOwnership(DatasetOwnershipRecord ownership, Long datasetId) {
        if (ownership == null || ownership.getOwnerUserId() == null || ownership.getOwnerDomainId() == null) {
            throw RegistrationException.conflict("DATASET_OWNER_REQUIRED",
                    "dataset " + datasetId + " has no assigned owner and collaboration domain");
        }
        if (!Boolean.TRUE.equals(ownership.getOwnerEnabled())) {
            throw RegistrationException.conflict("DATASET_OWNER_DISABLED", "dataset owner is disabled");
        }
        if (!Boolean.TRUE.equals(ownership.getDomainEnabled())) {
            throw RegistrationException.conflict("DATASET_DOMAIN_DISABLED", "dataset owner domain is disabled");
        }
    }

    public ResolvedSpec resolve(JobSpec request, TemplateDefinition template, String initiator) {
        if (request == null) throw RegistrationException.invalid("PRIVACY_SPEC_REQUIRED", "request body is required");
        if (template == null) throw RegistrationException.invalid("PRIVACY_TEMPLATE_UNKNOWN", "unknown privacy template");
        if (blank(initiator)) throw RegistrationException.invalid("PRIVACY_PRINCIPAL_REQUIRED",
                "authenticated privacy party is required");
        if (request.getSecurityProfile() != null
                && !template.getSecurityProfile().equals(request.getSecurityProfile())) {
            throw RegistrationException.invalid("SECURITY_PROFILE_MISMATCH",
                    "securityProfile must match the selected template; downgrade is forbidden");
        }

        List<ParticipantSpec> supplied = request.getParticipants();
        if (supplied == null || supplied.size() != template.getParticipantCount()) {
            throw RegistrationException.invalid("PARTICIPANT_COUNT_MISMATCH",
                    "template requires exactly " + template.getParticipantCount() + " participants");
        }
        Map<String, ParticipantSpec> byParty = new LinkedHashMap<>();
        for (ParticipantSpec participant : supplied) {
            if (participant == null || blank(participant.getPartyId())) {
                throw RegistrationException.invalid("PARTICIPANT_INVALID", "partyId is required");
            }
            String party = participant.getPartyId().trim().toUpperCase();
            if (byParty.put(party, participant) != null) {
                throw RegistrationException.invalid("PARTICIPANT_DUPLICATE", "duplicate partyId: " + party);
            }
        }
        if (!byParty.keySet().equals(template.getRequiredRoles().keySet())) {
            throw RegistrationException.invalid("PARTICIPANTS_MISMATCH",
                    "participants must be exactly " + template.getRequiredRoles().keySet());
        }
        String normalizedInitiator = initiator.trim().toUpperCase();
        if (!byParty.containsKey(normalizedInitiator)) {
            throw new RegistrationException(org.springframework.http.HttpStatus.FORBIDDEN,
                    "PRINCIPAL_NOT_PARTICIPANT", "initiator must be a task participant");
        }

        JobSpec normalized = new JobSpec();
        normalized.setTemplateId(template.getTemplateId());
        normalized.setSecurityProfile(template.getSecurityProfile());
        normalized.setTimeoutSeconds(resolveTimeout(request.getTimeoutSeconds(), template));
        normalized.setResultRecipients(resolveRecipients(request.getResultRecipients(), normalizedInitiator,
                byParty.keySet()));

        List<InputSnapshotRecord> snapshots = new ArrayList<>();
        for (Map.Entry<String, String> required : template.getRequiredRoles().entrySet()) {
            ParticipantSpec source = byParty.get(required.getKey());
            if (!required.getValue().equals(source.getRole())) {
                throw RegistrationException.invalid("PARTICIPANT_ROLE_MISMATCH",
                        required.getKey() + " role must be " + required.getValue());
            }
            ResolvedParticipant value = resolveParticipant(required.getKey(), required.getValue(), source);
            normalized.getParticipants().add(value.participant);
            snapshots.add(value.snapshot);
        }
        normalized.setEnginePolicy(resolveEnginePolicy(request.getEnginePolicy(), template,
                normalized.getParticipants(), normalized.getResultRecipients()));

        String specJson = canonicalJson(normalized);
        return new ResolvedSpec(normalized, snapshots, specJson, sha256(specJson));
    }

    private ResolvedParticipant resolveParticipant(String party, String role, ParticipantSpec source) {
        if (blank(source.getDatasetId()) || blank(source.getDatasetVersion())) {
            throw RegistrationException.invalid("DATASET_VERSION_REQUIRED",
                    party + " must select an explicit datasetId and datasetVersion");
        }
        RegisteredDataset dataset = resolveDataset(source.getDatasetId().trim(), source.getDatasetVersion().trim());
        if (dataset == null) {
            throw RegistrationException.notFound("DATASET_NOT_FOUND",
                    "registered dataset was not found for " + party);
        }
        if (!"ACTIVE".equals(dataset.getStatus())) {
            throw RegistrationException.conflict("DATASET_NOT_ACTIVE",
                    "dataset is not ACTIVE for " + party);
        }
        if (!source.getDatasetVersion().trim().equals(dataset.getDatasetVersion())) {
            throw RegistrationException.conflict("DATASET_VERSION_CHANGED",
                    "selected dataset version no longer matches the catalog for " + party);
        }
        DatasetMetadata metadata = datasets.findDatasetMetadata(dataset.getDatasetId());
        String authority = metadata == null ? null : normalizeSha256(metadata.getDigestValue());
        if (metadata == null || !"SHA-256".equalsIgnoreCase(metadata.getDigestAlgorithm())
                || authority == null || metadata.getAuthoritativeSizeBytes() == null
                || metadata.getAuthoritativeSizeBytes() < 0) {
            throw RegistrationException.conflict("DATASET_AUTHORITY_MISSING",
                    "dataset version has no authoritative SHA-256 and size for " + party);
        }
        if (!blank(source.getDatasetSha256())
                && !authority.equals(normalizeSha256(source.getDatasetSha256()))) {
            throw RegistrationException.conflict("DATASET_DIGEST_CHANGED",
                    "client dataset digest is stale for " + party + "; refresh the dataset selection");
        }
        if (blank(metadata.getSchemaJson())) {
            throw RegistrationException.conflict("DATASET_SCHEMA_MISSING",
                    "dataset version has no frozen schema for " + party);
        }
        JsonNode catalogSchema = readTree(metadata.getSchemaJson(), "invalid dataset schema for " + party);
        List<Map<String, Object>> frozenSchema = normalizeCsvSchema(catalogSchema, party);
        String schemaJson = canonicalJson(frozenSchema);
        String schemaDigest = sha256(schemaJson);
        List<String> fields = normalizeFields(source.getFields(), party);
        validateFields(frozenSchema, fields, party);

        ParticipantSpec participant = new ParticipantSpec();
        participant.setPartyId(party);
        participant.setRole(role);
        participant.setDatasetId(String.valueOf(dataset.getDatasetId()));
        participant.setDatasetVersion(dataset.getDatasetVersion());
        participant.setDatasetSha256(authority);
        participant.setAuthoritativeSizeBytes(metadata.getAuthoritativeSizeBytes());
        participant.setSchemaDigest(schemaDigest);
        participant.setFrozenSchema(frozenSchema);
        participant.setFields(fields);

        InputSnapshotRecord snapshot = new InputSnapshotRecord();
        snapshot.setPartyId(party);
        snapshot.setDatasetId(dataset.getDatasetId());
        snapshot.setDatasetCode(dataset.getDatasetCode());
        snapshot.setDatasetVersion(dataset.getDatasetVersion());
        snapshot.setDigestAlgorithm("SHA-256");
        snapshot.setDigestValue(authority);
        snapshot.setSizeBytes(metadata.getAuthoritativeSizeBytes());
        snapshot.setSchemaJson(schemaJson);
        snapshot.setSchemaDigest(schemaDigest);
        snapshot.setFieldsJson(canonicalJson(fields));
        return new ResolvedParticipant(participant, snapshot);
    }

    private RegisteredDataset resolveDataset(String id, String version) {
        if (id.matches("[0-9]{1,18}")) {
            try {
                return datasets.findDatasetById(Long.valueOf(id));
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return datasets.findDatasetByCodeAndVersion(id, version);
    }

    private List<String> resolveRecipients(List<String> requested, String initiator, Set<String> parties) {
        List<String> input = requested == null || requested.isEmpty()
                ? Collections.singletonList(initiator) : requested;
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String item : input) {
            String value = item == null ? "" : item.trim().toUpperCase();
            if (!parties.contains(value)) {
                throw RegistrationException.invalid("RESULT_RECIPIENT_INVALID",
                        "result recipient must be a participant: " + value);
            }
            result.add(value);
        }
        return new ArrayList<>(result);
    }

    private int resolveTimeout(Integer requested, TemplateDefinition template) {
        int value = requested == null ? template.getMaxTimeoutSeconds() : requested;
        if (value < 1 || value > template.getMaxTimeoutSeconds()) {
            throw RegistrationException.invalid("TIMEOUT_INVALID",
                    "timeoutSeconds must be between 1 and " + template.getMaxTimeoutSeconds());
        }
        return value;
    }

    private Map<String, Object> resolveEnginePolicy(Map<String, Object> requested,
                                                     TemplateDefinition template,
                                                     List<ParticipantSpec> participants,
                                                     List<String> recipients) {
        Map<String, Object> input = requested == null ? Collections.emptyMap() : requested;
        Set<String> allowed = allowedPolicy(template.getTemplateId());
        for (String key : input.keySet()) {
            if (!allowed.contains(key)) {
                throw RegistrationException.invalid("ENGINE_POLICY_KEY_FORBIDDEN",
                        "enginePolicy key is not allowed for this template: " + key);
            }
        }
        String id = template.getTemplateId();
        Map<String, Object> result = new LinkedHashMap<>();
        if ("secure-sum-3p-v1".equals(id)) {
            return result;
        }
        if ("private-stats-3p-v1".equals(id)) {
            result.put("scale", integer(input.get("scale"), 4, 0, 8, "scale"));
            return result;
        }
        if ("private-threshold-3p-v1".equals(id)) {
            String program = string(input.get("programId"), "topic4_private_threshold_100", "programId");
            if (!"topic4_private_threshold_100".equals(program)) {
                throw policyInvalid("programId must name the pre-registered threshold program");
            }
            int threshold = integer(input.get("threshold"), 100, 100, 100, "threshold");
            int scale = integer(input.get("scale"), 1, 1, 1, "scale");
            result.put("programId", program);
            result.put("threshold", threshold);
            result.put("scale", scale);
            return result;
        }
        if (id.startsWith("psi-")) {
            List<String> keys = stringList(input.get("keyColumns"), commonBoundFields(participants),
                    1, 4, "keyColumns");
            requireColumnsForEveryParticipant(keys, participants, "keyColumns");
            String defaultMode = recipients.size() == 1 ? "RECEIVER_ONLY" : "ALL_PARTIES";
            String mode = string(input.get("outputMode"), defaultMode, "outputMode").toUpperCase();
            if (!("RECEIVER_ONLY".equals(mode) || "ALL_PARTIES".equals(mode))) {
                throw policyInvalid("outputMode must be RECEIVER_ONLY or ALL_PARTIES");
            }
            if ("RECEIVER_ONLY".equals(mode)) {
                if (recipients.size() != 1) throw policyInvalid(
                        "RECEIVER_ONLY requires exactly one result recipient");
                if ("psi-2p-v1".equals(id) && !"A".equals(recipients.get(0))) {
                    throw policyInvalid("psi-2p receiver-only output must be delivered to party A");
                }
            } else if (!new LinkedHashSet<>(recipients).equals(participantIds(participants))) {
                throw policyInvalid("ALL_PARTIES requires every participant as a result recipient");
            }
            result.put("keyColumns", keys);
            result.put("outputMode", mode);
            return result;
        }
        if ("pir-keyword-2p-v1".equals(id)) {
            if (!(recipients.size() == 1 && "A".equals(recipients.get(0)))) {
                throw policyInvalid("PIR results may be delivered only to client party A");
            }
            ParticipantSpec client = participant(participants, "A");
            ParticipantSpec server = participant(participants, "B");
            String query = csvColumn(string(input.get("queryColumn"), firstField(client), "queryColumn"),
                    "queryColumn");
            requireColumns(Collections.singletonList(query), client, "queryColumn");
            requireColumns(Collections.singletonList(query), server, "queryColumn");
            List<String> defaultValues = new ArrayList<>();
            for (String field : server.getFields()) if (!query.equals(field)) defaultValues.add(field);
            if (defaultValues.isEmpty()) defaultValues.addAll(server.getFields());
            List<String> values = stringList(input.get("valueColumns"), defaultValues,
                    1, 1, "valueColumns");
            requireColumns(values, server, "valueColumns");
            result.put("queryColumn", query);
            result.put("valueColumns", values);
            return result;
        }
        if ("he-paillier-2p-v1".equals(id)) {
            String operation = string(input.get("operation"), "ADD", "operation").toUpperCase();
            if (!("ADD".equals(operation) || "PLAINTEXT_MULTIPLY".equals(operation)
                    || "DOT_PRODUCT".equals(operation))) {
                throw policyInvalid("operation must be ADD, PLAINTEXT_MULTIPLY, or DOT_PRODUCT");
            }
            result.put("operation", operation);
            result.put("scale", integer(input.get("scale"), 1, 1, 1000000, "scale"));
            return result;
        }
        if (id.startsWith("hfl-")) {
            String label = csvColumn(string(input.get("labelColumn"), "label", "labelColumn"),
                    "labelColumn");
            if (!"label".equals(label)) {
                throw policyInvalid("this registered HFL model requires labelColumn=label");
            }
            requireColumnsForEveryParticipant(Collections.singletonList(label), participants, "labelColumn");
            List<String> features = stringList(input.get("featureColumns"),
                    Arrays.asList("x1", "x2"), 2, 2, "featureColumns");
            if (!features.equals(Arrays.asList("x1", "x2"))) {
                throw policyInvalid("this registered HFL model requires featureColumns=[x1,x2]");
            }
            requireColumnsForEveryParticipant(features, participants, "featureColumns");
            result.put("labelColumn", label);
            result.put("featureColumns", features);
            result.put("epochs", integer(input.get("epochs"), 1, 1, 5, "epochs"));
            result.put("learningRate", decimal(input.get("learningRate"), 0.05d,
                    0.05d, 0.05d, "learningRate"));
            result.put("seed", integer(input.get("seed"), 20260919,
                    20260919, 20260919, "seed"));
            return result;
        }
        if (id.startsWith("vfl-")) {
            ParticipantSpec labelOwner = participant(participants, "A");
            String label = string(input.get("labelColumn"),
                    labelOwner.getFields().contains("label") ? "label" : lastField(labelOwner),
                    "labelColumn");
            requireColumns(Collections.singletonList(label), labelOwner, "labelColumn");
            List<String> defaultFeatures = uniqueBoundFields(participants);
            defaultFeatures.remove(label);
            List<String> features = stringList(input.get("featureColumns"), defaultFeatures,
                    1, 256, "featureColumns");
            if (features.contains(label)) throw policyInvalid("featureColumns cannot contain labelColumn");
            requireColumnsInAnyParticipant(features, participants, "featureColumns");
            result.put("labelColumn", label);
            result.put("featureColumns", features);
            result.put("epochs", integer(input.get("epochs"), 1, 1, 5, "epochs"));
            result.put("learningRate", decimal(input.get("learningRate"), 0.1d,
                    0.000001d, 1.0d, "learningRate"));
            result.put("seed", integer(input.get("seed"), 20260919, 0, Integer.MAX_VALUE, "seed"));
            return result;
        }
        return result;
    }

    private Set<String> allowedPolicy(String template) {
        if ("private-stats-3p-v1".equals(template)) return set("scale");
        if ("private-threshold-3p-v1".equals(template)) return set("programId", "threshold", "scale");
        if (template.startsWith("psi-")) return set("keyColumns", "outputMode");
        if ("pir-keyword-2p-v1".equals(template)) return set("queryColumn", "valueColumns");
        if ("he-paillier-2p-v1".equals(template)) return set("operation", "scale");
        if (template.startsWith("hfl-") || template.startsWith("vfl-")) {
            return set("labelColumn", "featureColumns", "epochs", "learningRate", "seed");
        }
        return Collections.emptySet();
    }

    private String string(Object value, String defaultValue, String field) {
        Object selected = value == null ? defaultValue : value;
        if (!(selected instanceof String) || blank((String) selected)
                || ((String) selected).trim().length() > 128) {
            throw policyInvalid(field + " must be a non-empty string");
        }
        return ((String) selected).trim();
    }

    private int integer(Object value, int defaultValue, int minimum, int maximum, String field) {
        Object selected = value == null ? defaultValue : value;
        if (!(selected instanceof Number)) throw policyInvalid(field + " must be an integer");
        double numeric = ((Number) selected).doubleValue();
        long exact = ((Number) selected).longValue();
        if (!Double.isFinite(numeric) || numeric != (double) exact || exact < minimum || exact > maximum) {
            throw policyInvalid(field + " must be between " + minimum + " and " + maximum);
        }
        return (int) exact;
    }

    private double decimal(Object value, double defaultValue, double minimum, double maximum, String field) {
        Object selected = value == null ? defaultValue : value;
        if (!(selected instanceof Number)) throw policyInvalid(field + " must be numeric");
        double result = ((Number) selected).doubleValue();
        if (!Double.isFinite(result) || result < minimum || result > maximum) {
            throw policyInvalid(field + " must be between " + minimum + " and " + maximum);
        }
        return result;
    }

    private List<String> stringList(Object value, List<String> defaultValue, int minimum,
                                    int maximum, String field) {
        Object selected = value == null ? defaultValue : value;
        if (!(selected instanceof List)) throw policyInvalid(field + " must be an array of column names");
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (Object item : (List<?>) selected) {
            if (!(item instanceof String) || blank((String) item)
                    || !((String) item).trim().matches("[A-Za-z_][A-Za-z0-9_]{0,63}")) {
                throw policyInvalid(field + " contains an invalid column name");
            }
            if (!result.add(((String) item).trim())) throw policyInvalid(field + " contains duplicates");
        }
        if (result.size() < minimum || result.size() > maximum) {
            throw policyInvalid(field + " must contain between " + minimum + " and " + maximum + " columns");
        }
        return new ArrayList<>(result);
    }

    private String csvColumn(String value, String field) {
        if (!value.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")) {
            throw policyInvalid(field + " must be a CSV column identifier");
        }
        return value;
    }

    private List<String> commonBoundFields(List<ParticipantSpec> participants) {
        List<String> result = new ArrayList<>(participants.get(0).getFields());
        for (int i = 1; i < participants.size(); i++) result.retainAll(participants.get(i).getFields());
        if (result.size() > 4) return new ArrayList<>(result.subList(0, 4));
        return result;
    }

    private List<String> uniqueBoundFields(List<ParticipantSpec> participants) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (ParticipantSpec participant : participants) result.addAll(participant.getFields());
        return new ArrayList<>(result);
    }

    private LinkedHashSet<String> participantIds(List<ParticipantSpec> participants) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (ParticipantSpec participant : participants) result.add(participant.getPartyId());
        return result;
    }

    private ParticipantSpec participant(List<ParticipantSpec> participants, String partyId) {
        for (ParticipantSpec participant : participants) {
            if (partyId.equals(participant.getPartyId())) return participant;
        }
        throw policyInvalid("required participant " + partyId + " is missing");
    }

    private String firstField(ParticipantSpec participant) {
        if (participant.getFields() == null || participant.getFields().isEmpty()) {
            throw policyInvalid("participant has no bound fields");
        }
        return participant.getFields().get(0);
    }

    private String lastField(ParticipantSpec participant) {
        if (participant.getFields() == null || participant.getFields().isEmpty()) {
            throw policyInvalid("participant has no bound fields");
        }
        return participant.getFields().get(participant.getFields().size() - 1);
    }

    private void requireColumnsForEveryParticipant(List<String> columns,
                                                    List<ParticipantSpec> participants, String field) {
        for (ParticipantSpec participant : participants) requireColumns(columns, participant, field);
    }

    private void requireColumnsInAnyParticipant(List<String> columns,
                                                List<ParticipantSpec> participants, String field) {
        for (String column : columns) {
            boolean found = false;
            for (ParticipantSpec participant : participants) {
                if (participant.getFields().contains(column)) { found = true; break; }
            }
            if (!found) throw policyInvalid(field + " column is not bound by any participant: " + column);
        }
    }

    private void requireColumns(List<String> columns, ParticipantSpec participant, String field) {
        for (String column : columns) {
            if (!participant.getFields().contains(column)) {
                throw policyInvalid(field + " column is not bound for party "
                        + participant.getPartyId() + ": " + column);
            }
        }
    }

    private RegistrationException policyInvalid(String message) {
        return RegistrationException.invalid("ENGINE_POLICY_VALUE_INVALID", message);
    }

    private List<String> normalizeFields(List<String> values, String party) {
        if (values == null || values.isEmpty()) {
            throw RegistrationException.invalid("FIELD_BINDING_REQUIRED",
                    party + " must bind at least one dataset field");
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String field : values) {
            String value = field == null ? "" : field.trim();
            if (value.isEmpty() || value.length() > 128 || !value.matches("[A-Za-z0-9_.-]+")) {
                throw RegistrationException.invalid("FIELD_BINDING_INVALID",
                        "invalid field binding for " + party);
            }
            if (!result.add(value)) {
                throw RegistrationException.invalid("FIELD_BINDING_DUPLICATE",
                        "duplicate field binding for " + party + ": " + value);
            }
        }
        return new ArrayList<>(result);
    }

    private void validateFields(List<Map<String, Object>> schema, List<String> fields, String party) {
        Set<String> available = new HashSet<>();
        for (Map<String, Object> column : schema) available.add(String.valueOf(column.get("name")));
        if (available.isEmpty()) {
            throw RegistrationException.conflict("DATASET_SCHEMA_UNSUPPORTED",
                    "dataset schema does not expose named fields for " + party);
        }
        for (String field : fields) {
            if (!available.contains(field)) {
                throw RegistrationException.invalid("FIELD_NOT_IN_SCHEMA",
                        "field is not present in the frozen schema for " + party + ": " + field);
            }
        }
    }

    /**
     * Converts supported catalog schema shapes to the runner's frozen CSV contract:
     * a non-empty ordered array of column objects, each with a unique name.
     */
    private List<Map<String, Object>> normalizeCsvSchema(JsonNode schema, String party) {
        JsonNode columns = schema;
        if (schema != null && schema.isObject()) {
            if (schema.path("columns").isArray()) columns = schema.path("columns");
            else if (schema.path("fields").isArray()) columns = schema.path("fields");
            else if (schema.path("properties").isObject()) {
                List<Map<String, Object>> result = new ArrayList<>();
                List<String> names = new ArrayList<>();
                schema.path("properties").fieldNames().forEachRemaining(names::add);
                Collections.sort(names);
                for (String name : names) {
                    Map<String, Object> column = objectMap(schema.path("properties").get(name));
                    column.put("name", name);
                    result.add(column);
                }
                return validateNormalizedSchema(result, party);
            } else if (schema.path("name").isTextual()) {
                columns = objectMapper.createArrayNode().add(schema);
            }
        }
        if (columns == null || !columns.isArray()) {
            throw RegistrationException.conflict("DATASET_SCHEMA_UNSUPPORTED",
                    "dataset schema is not a named CSV column array for " + party);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (JsonNode item : columns) {
            Map<String, Object> column;
            if (item.isTextual()) {
                column = new LinkedHashMap<>();
                column.put("name", item.asText());
            } else if (item.isObject()) {
                column = objectMap(item);
            } else {
                throw RegistrationException.conflict("DATASET_SCHEMA_UNSUPPORTED",
                        "dataset schema contains a column without a name for " + party);
            }
            result.add(column);
        }
        return validateNormalizedSchema(result, party);
    }

    private List<Map<String, Object>> validateNormalizedSchema(List<Map<String, Object>> columns, String party) {
        if (columns.isEmpty()) {
            throw RegistrationException.conflict("DATASET_SCHEMA_UNSUPPORTED",
                    "dataset schema has no named CSV columns for " + party);
        }
        Set<String> names = new HashSet<>();
        for (Map<String, Object> column : columns) {
            Object rawName = column.get("name");
            String name = rawName == null ? "" : String.valueOf(rawName).trim();
            if (name.isEmpty() || !names.add(name)) {
                throw RegistrationException.conflict("DATASET_SCHEMA_UNSUPPORTED",
                        "dataset schema has blank or duplicate CSV column names for " + party);
            }
            column.put("name", name);
        }
        return columns;
    }

    private Map<String, Object> objectMap(JsonNode node) {
        if (node == null || node.isNull()) return new LinkedHashMap<>();
        return objectMapper.convertValue(node, new TypeReference<LinkedHashMap<String, Object>>() { });
    }

    private void collectFieldNames(JsonNode node, String parentName, Set<String> output) {
        if (node == null) return;
        if (node.isObject()) {
            JsonNode name = node.get("name");
            if (name != null && name.isTextual()) output.add(name.asText());
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if ("properties".equals(parentName) || "tensors".equals(parentName)) output.add(field.getKey());
                collectFieldNames(field.getValue(), field.getKey(), output);
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                if (item.isTextual() && ("fields".equals(parentName) || "columns".equals(parentName))) {
                    output.add(item.asText());
                } else collectFieldNames(item, parentName, output);
            }
        }
    }

    private JsonNode readTree(String value, String message) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception ex) {
            throw RegistrationException.conflict("DATASET_SCHEMA_INVALID", message);
        }
    }

    public String canonicalJson(Object value) {
        try {
            ObjectMapper canonical = objectMapper.copy();
            canonical.configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);
            canonical.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
            return canonical.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("unable to canonicalize privacy metadata", ex);
        }
    }

    public String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : digest) result.append(String.format("%02x", item & 0xff));
            return result.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private String normalizeSha256(String value) {
        if (blank(value)) return null;
        String normalized = value.trim().toLowerCase();
        if (normalized.startsWith("sha256:")) normalized = normalized.substring(7);
        return normalized.matches("[0-9a-f]{64}") ? normalized : null;
    }

    private Set<String> set(String... values) {
        return new LinkedHashSet<>(Arrays.asList(values));
    }

    private boolean blank(String value) { return value == null || value.trim().isEmpty(); }

    public static class ResolvedSpec {
        private final JobSpec spec;
        private final List<InputSnapshotRecord> snapshots;
        private final String specJson;
        private final String specDigest;

        ResolvedSpec(JobSpec spec, List<InputSnapshotRecord> snapshots,
                     String specJson, String specDigest) {
            this.spec = spec;
            this.snapshots = snapshots;
            this.specJson = specJson;
            this.specDigest = specDigest;
        }
        public JobSpec getSpec() { return spec; }
        public List<InputSnapshotRecord> getSnapshots() { return snapshots; }
        public String getSpecJson() { return specJson; }
        public String getSpecDigest() { return specDigest; }
    }

    private static class ResolvedParticipant {
        private final ParticipantSpec participant;
        private final InputSnapshotRecord snapshot;
        private ResolvedParticipant(ParticipantSpec participant, InputSnapshotRecord snapshot) {
            this.participant = participant;
            this.snapshot = snapshot;
        }
    }
}
