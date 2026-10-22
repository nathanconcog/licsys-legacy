package licsys.demerit.aws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import licsys.demerit.app.LedgerConflictException;
import licsys.demerit.app.LedgerRepository;
import licsys.demerit.domain.DriverLedger;
import licsys.demerit.domain.LicenceClass;
import licsys.demerit.domain.Offence;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DeleteRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

/**
 * DynamoDB table {@code demerit-ledger} (pk {@code driverId}): one item per driver holding the class projection,
 * starting balance, current points and the offence history (JSON list).
 */
@Repository
public class DynamoLedgerRepository implements LedgerRepository {

    private static final TypeReference<List<Offence>> OFFENCES = new TypeReference<>() {
    };

    private final DynamoDbClient dynamo;
    private final ObjectMapper json;
    private final String table;

    public DynamoLedgerRepository(DynamoDbClient dynamo, ObjectMapper json, @Value("${licsys.table}") String table) {
        this.dynamo = dynamo;
        this.json = json;
        this.table = table;
    }

    @Override
    public Optional<DriverLedger> find(String driverId) {
        Map<String, AttributeValue> item = dynamo.getItem(r -> r.tableName(table).consistentRead(true)
                .key(Map.of("driverId", s(driverId)))).item();
        if (item == null || item.isEmpty()) {
            return Optional.empty();
        }
        try {
            List<Offence> offences = json.readValue(str(item, "offences", "[]"), OFFENCES);
            String cls = str(item, "licenceClass", null);
            String eff = str(item, "classEffectiveDate", null);
            return Optional.of(new DriverLedger(driverId, str(item, "name", null),
                    cls == null ? null : LicenceClass.valueOf(cls), eff == null ? null : LocalDate.parse(eff),
                    item.containsKey("registered") && item.get("registered").bool(),
                    Integer.parseInt(item.get("startingPoints").n()), offences,
                    Long.parseLong(item.get("version").n())));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt offences for " + driverId, e);
        }
    }

    @Override
    public void save(DriverLedger ledger) {
        long expected = ledger.version();
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("driverId", s(ledger.driverId()));
        if (ledger.name() != null) {
            item.put("name", s(ledger.name()));
        }
        if (ledger.licenceClass() != null) {
            item.put("licenceClass", s(ledger.licenceClass().name()));
            item.put("limit", n(ledger.licenceClass().demeritLimit()));
        }
        if (ledger.classEffectiveDate() != null) {
            item.put("classEffectiveDate", s(ledger.classEffectiveDate().toString()));
        }
        item.put("registered", AttributeValue.fromBool(ledger.registered()));
        item.put("startingPoints", n(ledger.startingPoints()));
        item.put("points", n(ledger.points()));
        item.put("offenceCount", n(ledger.offences().size()));
        try {
            item.put("offences", s(json.writeValueAsString(ledger.offences())));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        item.put("version", n(expected + 1));
        item.put("updatedAt", s(Instant.now().toString()));

        PutItemRequest.Builder put = PutItemRequest.builder().tableName(table).item(item);
        if (expected == 0) {
            put.conditionExpression("attribute_not_exists(driverId)");
        } else {
            put.conditionExpression("version = :v").expressionAttributeValues(Map.of(":v", n(expected)));
        }
        try {
            dynamo.putItem(put.build());
        } catch (ConditionalCheckFailedException e) {
            throw new LedgerConflictException(ledger.driverId());
        }
        ledger.setVersion(expected + 1);
    }

    @Override
    public int deleteAll() {
        int deleted = 0;
        Map<String, AttributeValue> startKey = null;
        do {
            ScanResponse page = dynamo.scan(ScanRequest.builder().tableName(table).projectionExpression("driverId")
                    .exclusiveStartKey(startKey).build());
            List<WriteRequest> batch = new ArrayList<>();
            for (Map<String, AttributeValue> key : page.items()) {
                batch.add(WriteRequest.builder().deleteRequest(DeleteRequest.builder().key(key).build()).build());
                if (batch.size() == 25) {
                    deleted += flush(batch);
                }
            }
            deleted += flush(batch);
            startKey = page.hasLastEvaluatedKey() && !page.lastEvaluatedKey().isEmpty() ? page.lastEvaluatedKey() : null;
        } while (startKey != null);
        return deleted;
    }

    private int flush(List<WriteRequest> batch) {
        if (batch.isEmpty()) {
            return 0;
        }
        int n = batch.size();
        Map<String, List<WriteRequest>> pending = Map.of(table, new ArrayList<>(batch));
        while (!pending.isEmpty()) {
            pending = dynamo.batchWriteItem(BatchWriteItemRequest.builder().requestItems(pending).build())
                    .unprocessedItems();
        }
        batch.clear();
        return n;
    }

    private static AttributeValue s(String v) {
        return AttributeValue.fromS(v);
    }

    private static AttributeValue n(long v) {
        return AttributeValue.fromN(Long.toString(v));
    }

    private static String str(Map<String, AttributeValue> item, String key, String fallback) {
        AttributeValue v = item.get(key);
        return v == null || v.s() == null ? fallback : v.s();
    }
}
