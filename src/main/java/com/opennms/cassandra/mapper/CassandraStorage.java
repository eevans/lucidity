package com.opennms.cassandra.mapper;


import static com.datastax.driver.core.querybuilder.QueryBuilder.batch;
import static com.datastax.driver.core.querybuilder.QueryBuilder.eq;
import static com.datastax.driver.core.querybuilder.QueryBuilder.insertInto;
import static com.datastax.driver.core.querybuilder.QueryBuilder.select;
import static com.datastax.driver.core.querybuilder.QueryBuilder.set;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Throwables.propagate;
import static com.opennms.cassandra.mapper.Schema.ENTITY;
import static com.opennms.cassandra.mapper.Schema.ID;
import static com.opennms.cassandra.mapper.Schema.INDEX;
import static com.opennms.cassandra.mapper.Schema.joinColumnName;
import static java.lang.String.format;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetAddress;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.Statement;
import com.datastax.driver.core.querybuilder.Batch;
import com.datastax.driver.core.querybuilder.Insert;
import com.datastax.driver.core.querybuilder.QueryBuilder;
import com.datastax.driver.core.querybuilder.Update;
import com.google.common.base.Optional;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.collect.Sets.SetView;


// FIXME: Consider something from Guava for cache.
// FIXME: Wrap java driver exceptions in something (don't expose to consumers of this API).
// FIXME: Reflection errors shouldn't be propagated as RuntimeExceptions (use custom exception).
// FIXME: Cache schemas?
// FIXME: Should create() set the generated ID on the object?
// FIXME: create() should fail if ID set?
// FIXME: Support collection types (hint: parametization).
// FIXME: delete() should remove from instance cache as well.

public class CassandraStorage implements Storage {

    private final Session m_session;
    private ConcurrentMap<Integer, Record> m_objectCache = new ConcurrentHashMap<>();

    public CassandraStorage(String host, int port, String keyspace) {

        checkNotNull(host, "Cassandra hostname");
        checkNotNull(port, "Cassandra port number");
        checkNotNull(keyspace, "Cassandra keyspace");

        Cluster cluster = Cluster.builder().withPort(port).addContactPoint(host).build();
        m_session = cluster.connect(keyspace);
        // m_session = cluster.connect();

    }

    private Schema getSchema(Object object) {
        return Schema.fromClass(object.getClass());
    }

    @Override
    public UUID create(Object object) {

        checkNotNull(object, "object argument");
        checkArgument(
                object.getClass().isAnnotationPresent(ENTITY),
                String.format("%s not annotated with @%s", getClass().getSimpleName(), ENTITY.getCanonicalName()));

        Schema schema = getSchema(object);
        
        checkArgument(
                schema.getIDValue(object) == null,
                String.format("property annotated with @%s must be null", ID.getCanonicalName()));

        // Object persistence (incl. indices)
        UUID id = UUID.randomUUID();
        Batch batch = batch();
        Insert insertStatement = insertInto(schema.getTableName()).value(schema.getIDName(), id);

        for (Entry<String, Field> entry : schema.getColumns().entrySet()) {
            String columnName = entry.getKey();
            Field f = entry.getValue();
            
            insertStatement.value(columnName, schema.getColumnValue(columnName, object));
            
            if (entry.getValue().isAnnotationPresent(Schema.INDEX)) {
                String tableName = format("%s_%s_idx", schema.getTableName(), columnName);
                batch.add(
                        insertInto(tableName)
                            .value(columnName, Util.getFieldValue(f, object))
                            .value(format("%s_id", schema.getTableName()), id)
                );
            }
        }
        
        batch.add(insertStatement);
        
        // One-to-Many relationship persistence
        for (Map.Entry<Field, Schema> entry : schema.getOneToManys().entrySet()) {
            Schema s = entry.getValue();

            Object relations = Util.getFieldValue(entry.getKey(), object);

            if (relations == null) {
                continue;
            }

            for (Object item : (Collection<?>) relations) {
                UUID relationID = (UUID) s.getIDValue(item);

                if (relationID == null) {
                    throw new IllegalStateException(
                            "encountered relation with null ID property (entity not persisted?)");
                }

                String joinTable = format("%s_%s", schema.getTableName(), s.getTableName());

                batch.add(
                        insertInto(joinTable)
                            .value(joinColumnName(schema.getTableName()), id)
                            .value(joinColumnName(s.getTableName()), relationID)
                );
            }

        }

        m_session.execute(batch);

        return id;
    }

    @Override
    public void update(Object object) {

        Schema schema = getSchema(object);
        boolean needsUpdate = false;
        Record record = m_objectCache.get(System.identityHashCode(object));
        
        Update updateStatement = QueryBuilder.update(schema.getTableName());
        Batch batchStatement = batch();

        if (record != null) {
            
            for (String columnName : schema.getColumns().keySet()) {

                Object past, current;
                current = schema.getColumnValue(columnName, object);
                past = record.getColumns().get(columnName);

                if (current != null && !current.equals(past)) {
                    needsUpdate = true;
                    updateStatement.with(set(columnName, current));
                    
                    // Update index, if applicable
                    if (schema.getColumns().get(columnName).isAnnotationPresent(INDEX)) {
                        batchStatement.add(
                                QueryBuilder.update(format("%s_%s_idx", schema.getTableName(), columnName))
                                        .with(set(format("%s_id", schema.getTableName()), schema.getIDValue(object)))
                                        .where(eq(columnName, schema.getColumnValue(columnName, object)))
                        );
                    }
                }
            }
        }
        else {
            // XXX: Should we be doing this?
            for (Entry<String, Field> entry : schema.getColumns().entrySet()) {
                String columnName = entry.getKey();
                Field f = entry.getValue();
                
                needsUpdate = true;
                updateStatement.with(set(columnName, schema.getColumnValue(columnName, object)));
                
                // Add index
                if (f.isAnnotationPresent(Schema.INDEX)) {
                    String tableName = format("%s_%s_idx", schema.getTableName(), columnName);
                    batchStatement.add(
                            insertInto(tableName)
                                .value(columnName, Util.getFieldValue(f, object))
                                .value(format("%s_id", schema.getTableName()), schema.getIDValue(object))
                    );
                }
            }
        }

        updateStatement.where(eq(schema.getIDName(), schema.getIDValue(object)));

        if (needsUpdate) {
            batchStatement.add(updateStatement);
        }

        for (Map.Entry<Field, Schema> entry : schema.getOneToManys().entrySet()) {
            Field f = entry.getKey();
            Schema s = entry.getValue();

            Collection<?> past, current;
            current = (Collection<?>)Util.getFieldValue(f, object);
            past = (record != null) ? record.getOneToManys().get(f) : null;

            if (current == null) {
                current = Collections.emptySet();
            }
            
            if (past == null) {
                past = Collections.emptySet();
            }

            SetView<?> toInsert = Sets.difference(Sets.newHashSet(current), Sets.newHashSet(past));
            SetView<?> toRemove = Sets.difference(Sets.newHashSet(past), Sets.newHashSet(current));

            String joinTable = format("%s_%s", schema.getTableName(), s.getTableName());

            for (Object o : toInsert) {
                batchStatement.add(insertInto(joinTable).value(
                        joinColumnName(schema.getTableName()),
                        schema.getIDValue(object)).value(joinColumnName(s.getTableName()), s.getIDValue(o)));
            }

            for (Object o : toRemove) {
                batchStatement.add(QueryBuilder.delete().from(joinTable).where(
                        eq(joinColumnName(schema.getTableName()), schema.getIDValue(object))).and(
                        eq(joinColumnName(s.getTableName()), s.getIDValue(o))));
            }
        }

        m_session.execute(batchStatement);

    }

    @Override
    public <T> Optional<T> read(Class<T> cls, UUID id) {

        T instance;
        try {
            instance = cls.newInstance();
        }
        catch (InstantiationException | IllegalAccessException e) {
            throw propagate(e);    // Missing ctor?
        }

        Schema schema = getSchema(instance);
        Statement selectStatement = select().from(schema.getTableName()).where(eq(schema.getIDName(), id));
        ResultSet results = m_session.execute(selectStatement);
        Row row = results.one();

        checkState(results.isExhausted(), "query returned more than one row");
        if (row == null) return Optional.absent();

        Util.setFieldValue(schema.getIDField(), instance, row.getUUID(schema.getIDName()));

        for (String columnName : schema.getColumns().keySet()) {
            setColumn(instance, columnName, schema.getColumns().get(columnName), row);
        }
        
        for (Map.Entry<Field, Schema> entry : schema.getOneToManys().entrySet()) {
            
            Schema s = entry.getValue();
            Collection<Object> relations = Lists.newArrayList();
            String joinTable = format("%s_%s", schema.getTableName(), s.getTableName());
            Statement statement = select().from(joinTable).where(eq(joinColumnName(schema.getTableName()), id));

            for (Row r : m_session.execute(statement)) {
                UUID u = r.getUUID(joinColumnName(s.getTableName()));
                relations.add(read(s.getObjectType(), u));
            }

            Util.setFieldValue(entry.getKey(), instance, relations);

        }

        cacheObject(instance);

        return Optional.of(instance);
    }

    private void cacheObject(Object obj) {
        Schema schema = getSchema(obj);
        Record record = new Record(schema.getIDValue(obj));
        for (String columnName : schema.getColumns().keySet()) {
            record.putColumn(columnName, schema.getColumnValue(columnName, obj));
        }
        for (Field f : schema.getOneToManys().keySet()) {
            record.putOneToMany(f, Lists.newArrayList((Collection<?>)Util.getFieldValue(f, obj)));
        }
        m_objectCache.put(System.identityHashCode(obj), record);
    }

    private void setColumn(Object obj, String name, Field f, Row data) {

        try {
            if (f.getType().equals(Boolean.TYPE)) {
                f.set(obj, data.getBool(name));
            }
            else if (f.getType().equals(BigDecimal.class)) {
                f.set(obj, data.getDecimal(name));
            }
            else if (f.getType().equals(BigInteger.class)) {
                f.set(obj, data.getVarint(name));
            }
            else if (f.getType().equals(Date.class)) {
                f.set(obj, data.getDate(name));
            }
            else if (f.getType().equals(Double.TYPE)) {
                f.set(obj, data.getDouble(name));
            }
            else if (f.getType().equals(Float.TYPE)) {
                f.set(obj, data.getFloat(name));
            }
            else if (f.getType().equals(InetAddress.class)) {
                f.set(obj, data.getInet(name));
            }
            else if (f.getType().equals(Integer.TYPE)) {
                f.set(obj, data.getInt(name));
            }
            else if (f.getType().equals(List.class)) {
                // FIXME: ...
                throw new UnsupportedOperationException();
            }
            else if (f.getType().equals(Long.TYPE)) {
                f.set(obj, data.getLong(name));
            }
            else if (f.getType().equals(Map.class)) {
                // FIXME: ...
                throw new UnsupportedOperationException();
            }
            else if (f.getType().equals(Set.class)) {
                // FIXME: ...
                throw new UnsupportedOperationException();
            }
            else if (f.getType().equals(String.class)) {
                f.set(obj, data.getString(name));
            }
            else if (f.getType().equals(UUID.class)) {
                f.set(obj, data.getUUID(name));
            }
            else {
                throw new IllegalArgumentException(format("Unsupported field type %s", f.getType()));
            }
        }
        catch (IllegalArgumentException | IllegalAccessException e) {
            throw propagate(e);
        }

    }

    @Override
    public void delete(Object obj) {

        Schema schema = getSchema(obj);
        Batch batchStatement = batch(QueryBuilder.delete().from(schema.getTableName())
                .where(eq(schema.getIDName(), schema.getIDValue(obj))));

        // Remove index entries
        for (Entry<String, Field> entry : schema.getColumns().entrySet()) {
            String columnName = entry.getKey();
            Field f = entry.getValue();

            if (f.isAnnotationPresent(INDEX)) {
                String tableName = format("%s_%s_idx", schema.getTableName(), columnName);
                batchStatement.add(QueryBuilder.delete().from(tableName).where(eq(columnName, Util.getFieldValue(f, obj))));
            }
        }

        // Remove one-to-many relationships
        for (Schema s : schema.getOneToManys().values()) {
            String joinTable = format("%s_%s", schema.getTableName(), s.getTableName());
            batchStatement.add(QueryBuilder.delete().from(joinTable)
                    .where(eq(joinColumnName(schema.getTableName()), schema.getIDValue(obj))));
        }

        m_session.execute(batchStatement);

    }

    @Override
    public <T> Optional<T> read(Class<T> cls, String indexedName, Object value) {

        T instance;
        try {
            instance = cls.newInstance();
        }
        catch (InstantiationException | IllegalAccessException e) {
            throw propagate(e);    // Missing ctor?
        }

        Schema schema = getSchema(instance);
        Statement selectStatement = select(format("%s_id", schema.getTableName())).from(format("%s_%s_idx", schema.getTableName(), indexedName)).where(eq(indexedName, value));
        ResultSet results = m_session.execute(selectStatement);
        Row row = results.one();

        checkState(results.isExhausted(), "query returned more than one row");
        if (row == null) Optional.absent();
        
        return read(cls, row.getUUID(format("%s_id", schema.getTableName())));
    }

}
