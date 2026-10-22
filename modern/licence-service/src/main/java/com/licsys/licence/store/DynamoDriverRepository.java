package com.licsys.licence.store;

import com.licsys.licence.config.LicsysProperties;
import com.licsys.licence.domain.Driver;
import com.licsys.licence.domain.HistoryEntry;
import com.licsys.licence.domain.LicenceClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Persists drivers in the {@code licence-drivers} table (pk {@code driverId}). */
@Repository
@ConditionalOnProperty(name = "licsys.aws.enabled", havingValue = "true", matchIfMissing = true)
public class DynamoDriverRepository implements DriverRepository {

    private final DynamoDbClient dynamo;
    private final String table;

    public DynamoDriverRepository(DynamoDbClient dynamo, LicsysProperties props) {
        this.dynamo = dynamo;
        this.table = props.table();
    }

    @Override
    public void save(Driver d) {
        dynamo.putItem(b -> b.tableName(table).item(toItem(d)));
    }

    @Override
    public Optional<Driver> findById(String driverId) {
        Map<String, AttributeValue> item = dynamo.getItem(b -> b.tableName(table)
                .key(key(driverId)).consistentRead(true)).item();
        return item == null || item.isEmpty() ? Optional.empty() : Optional.of(fromItem(item));
    }

    @Override
    public List<Driver> findAll() {
        List<Driver> out = new ArrayList<>();
        dynamo.scanPaginator(ScanRequest.builder().tableName(table).consistentRead(true).build())
                .items().forEach(i -> out.add(fromItem(i)));
        out.sort(Comparator.comparing(Driver::driverId));
        return out;
    }

    @Override
    public boolean updateDemeritPoints(String driverId, int points) {
        try {
            dynamo.updateItem(b -> b.tableName(table).key(key(driverId))
                    .updateExpression("SET demeritPoints = :p")
                    .conditionExpression("attribute_exists(driverId)")
                    .expressionAttributeValues(Map.of(":p", n(points))));
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        }
    }

    @Override
    public boolean applyProgression(String driverId, LicenceClass from, LicenceClass to, LocalDate runDate,
                                    HistoryEntry entry) {
        try {
            dynamo.updateItem(b -> b.tableName(table).key(key(driverId))
                    .updateExpression("SET licenceClass = :to, classIssuedDate = :date, suspendedDays = :zero, "
                            + "history = list_append(if_not_exists(history, :empty), :entry)")
                    .conditionExpression("licenceClass = :from")
                    .expressionAttributeValues(Map.of(
                            ":to", s(to.name()),
                            ":from", s(from.name()),
                            ":date", s(runDate.toString()),
                            ":zero", n(0),
                            ":empty", AttributeValue.fromL(List.of()),
                            ":entry", AttributeValue.fromL(List.of(historyItem(entry))))));
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        }
    }

    @Override
    public int deleteAll() {
        int n = 0;
        for (Map<String, AttributeValue> item : dynamo.scanPaginator(ScanRequest.builder().tableName(table)
                .projectionExpression("driverId").build()).items()) {
            dynamo.deleteItem(b -> b.tableName(table).key(Map.of("driverId", item.get("driverId"))));
            n++;
        }
        return n;
    }

    private static Map<String, AttributeValue> key(String driverId) {
        return Map.of("driverId", s(driverId));
    }

    private static Map<String, AttributeValue> toItem(Driver d) {
        Map<String, AttributeValue> m = new HashMap<>();
        m.put("driverId", s(d.driverId()));
        m.put("name", s(d.name()));
        if (d.dob() != null) {
            m.put("dob", s(d.dob().toString()));
        }
        m.put("licenceClass", s(d.licenceClass().name()));
        m.put("classIssuedDate", s(d.classIssuedDate().toString()));
        if (d.p1IssuedDate() != null) {
            m.put("p1IssuedDate", s(d.p1IssuedDate().toString()));
        }
        m.put("suspendedDays", n(d.suspendedDays()));
        m.put("demeritPoints", n(d.demeritPoints()));
        m.put("history", AttributeValue.fromL(d.history().stream().map(DynamoDriverRepository::historyItem).toList()));
        return m;
    }

    private static AttributeValue historyItem(HistoryEntry h) {
        return AttributeValue.fromM(Map.of(
                "fromClass", s(h.fromClass().name()),
                "toClass", s(h.toClass().name()),
                "effectiveDate", s(h.effectiveDate().toString()),
                "rule", s(h.rule()),
                "reason", s(h.reason())));
    }

    private static Driver fromItem(Map<String, AttributeValue> m) {
        List<HistoryEntry> history = new ArrayList<>();
        if (m.containsKey("history")) {
            for (AttributeValue v : m.get("history").l()) {
                Map<String, AttributeValue> h = v.m();
                history.add(new HistoryEntry(
                        LicenceClass.valueOf(h.get("fromClass").s()),
                        LicenceClass.valueOf(h.get("toClass").s()),
                        LocalDate.parse(h.get("effectiveDate").s()),
                        h.get("rule").s(),
                        h.get("reason").s()));
            }
        }
        return new Driver(
                m.get("driverId").s(),
                m.get("name").s(),
                date(m.get("dob")),
                LicenceClass.valueOf(m.get("licenceClass").s()),
                date(m.get("classIssuedDate")),
                date(m.get("p1IssuedDate")),
                Integer.parseInt(m.get("suspendedDays").n()),
                Integer.parseInt(m.get("demeritPoints").n()),
                history);
    }

    private static LocalDate date(AttributeValue v) {
        return v == null || v.s() == null ? null : LocalDate.parse(v.s());
    }

    private static AttributeValue s(String v) {
        return AttributeValue.fromS(v);
    }

    private static AttributeValue n(int v) {
        return AttributeValue.fromN(Integer.toString(v));
    }
}
