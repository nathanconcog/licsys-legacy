package licsys.registration.aws;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import licsys.registration.domain.Renewal;
import licsys.registration.domain.RenewalStore;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;

@Repository
public class DynamoRenewalStore implements RenewalStore {

    private static final String PK = "renewalId";

    private final DynamoDbClient dynamo;
    private final String table;

    public DynamoRenewalStore(DynamoDbClient dynamo, @Value("${licsys.registration.table}") String table) {
        this.dynamo = dynamo;
        this.table = table;
    }

    @Override
    public void save(Renewal r) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(PK, s(r.renewalId()));
        item.put("plate", s(r.plate()));
        item.put("vehicleType", s(r.vehicleType()));
        if (r.cylinders() != null) {
            item.put("cylinders", AttributeValue.fromN(r.cylinders().toString()));
        }
        item.put("concession", AttributeValue.fromBool(r.concession()));
        item.put("termMonths", AttributeValue.fromN(Integer.toString(r.termMonths())));
        item.put("total", s(r.total()));
        item.put("lines", AttributeValue.fromL(r.lines().stream()
                .map(l -> AttributeValue.fromM(Map.of("label", s(l.label()), "amount", s(l.amount()),
                        "rule", s(l.rule()))))
                .toList()));
        item.put("rule", s(r.rule()));
        item.put("reason", s(r.reason()));
        item.put("renewedAt", s(r.renewedAt()));
        dynamo.putItem(b -> b.tableName(table).item(item));
    }

    @Override
    public List<Renewal> findAll() {
        List<Renewal> out = new ArrayList<>();
        dynamo.scanPaginator(ScanRequest.builder().tableName(table).build())
                .items().forEach(item -> out.add(toRenewal(item)));
        return out;
    }

    @Override
    public int deleteAll() {
        int deleted = 0;
        for (Map<String, AttributeValue> item : dynamo
                .scanPaginator(ScanRequest.builder().tableName(table).projectionExpression(PK).build()).items()) {
            dynamo.deleteItem(b -> b.tableName(table).key(Map.of(PK, item.get(PK))));
            deleted++;
        }
        return deleted;
    }

    private static Renewal toRenewal(Map<String, AttributeValue> item) {
        AttributeValue cylinders = item.get("cylinders");
        return new Renewal(
                item.get(PK).s(),
                item.get("plate").s(),
                item.get("vehicleType").s(),
                cylinders == null ? null : Integer.valueOf(cylinders.n()),
                item.get("concession").bool(),
                Integer.parseInt(item.get("termMonths").n()),
                item.get("total").s(),
                item.get("lines").l().stream()
                        .map(AttributeValue::m)
                        .map(m -> new Renewal.Line(m.get("label").s(), m.get("amount").s(), m.get("rule").s()))
                        .toList(),
                item.get("rule").s(),
                item.get("reason").s(),
                item.get("renewedAt").s());
    }

    private static AttributeValue s(String value) {
        return AttributeValue.fromS(value);
    }
}
