/*******************************************************************************
 * Copyright 2012 Apigee Corporation
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package org.usergrid.persistence.cassandra;

import static me.prettyprint.cassandra.service.FailoverPolicy.ON_FAIL_TRY_ALL_AVAILABLE;
import static me.prettyprint.hector.api.factory.HFactory.createColumn;
import static me.prettyprint.hector.api.factory.HFactory.createDefaultConsistencyLevelPolicy;
import static me.prettyprint.hector.api.factory.HFactory.createMultigetSliceQuery;
import static me.prettyprint.hector.api.factory.HFactory.createMutator;
import static me.prettyprint.hector.api.factory.HFactory.createRangeSlicesQuery;
import static me.prettyprint.hector.api.factory.HFactory.createSliceQuery;
import static me.prettyprint.hector.api.factory.HFactory.createVirtualKeyspace;
import static org.apache.commons.collections.MapUtils.getIntValue;
import static org.apache.commons.collections.MapUtils.getString;
import static org.usergrid.persistence.Schema.DICTIONARY_ID_SETS;
import static org.usergrid.persistence.cassandra.ApplicationCF.ENTITY_DICTIONARIES;
import static org.usergrid.persistence.cassandra.ApplicationCF.ENTITY_ID_SETS;
import static org.usergrid.persistence.cassandra.CassandraPersistenceUtils.addDeleteToMutator;
import static org.usergrid.persistence.cassandra.CassandraPersistenceUtils.addInsertToMutator;
import static org.usergrid.persistence.cassandra.CassandraPersistenceUtils.batchExecute;
import static org.usergrid.persistence.cassandra.CassandraPersistenceUtils.buildSetIdListMutator;
import static org.usergrid.persistence.cassandra.CassandraPersistenceUtils.key;
import static org.usergrid.utils.ConversionUtils.bytebuffer;
import static org.usergrid.utils.ConversionUtils.bytebuffers;
import static org.usergrid.utils.ConversionUtils.string;
import static org.usergrid.utils.ConversionUtils.uuid;
import static org.usergrid.utils.JsonUtils.mapToFormattedJsonString;
import static org.usergrid.utils.MapUtils.asMap;
import static org.usergrid.utils.MapUtils.filter;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import me.prettyprint.cassandra.connection.HConnectionManager;
import me.prettyprint.cassandra.serializers.ByteBufferSerializer;
import me.prettyprint.cassandra.serializers.BytesArraySerializer;
import me.prettyprint.cassandra.serializers.DynamicCompositeSerializer;
import me.prettyprint.cassandra.serializers.LongSerializer;
import me.prettyprint.cassandra.serializers.StringSerializer;
import me.prettyprint.cassandra.serializers.UUIDSerializer;
import me.prettyprint.cassandra.service.CassandraHostConfigurator;
import me.prettyprint.cassandra.service.ThriftKsDef;
import me.prettyprint.hector.api.Cluster;
import me.prettyprint.hector.api.Keyspace;
import me.prettyprint.hector.api.Serializer;
import me.prettyprint.hector.api.beans.ColumnSlice;
import me.prettyprint.hector.api.beans.DynamicComposite;
import me.prettyprint.hector.api.beans.HColumn;
import me.prettyprint.hector.api.beans.OrderedRows;
import me.prettyprint.hector.api.beans.Row;
import me.prettyprint.hector.api.beans.Rows;
import me.prettyprint.hector.api.ddl.ColumnDefinition;
import me.prettyprint.hector.api.ddl.ColumnFamilyDefinition;
import me.prettyprint.hector.api.ddl.KeyspaceDefinition;
import me.prettyprint.hector.api.factory.HFactory;
import me.prettyprint.hector.api.mutation.Mutator;
import me.prettyprint.hector.api.query.ColumnQuery;
import me.prettyprint.hector.api.query.CountQuery;
import me.prettyprint.hector.api.query.MultigetSliceQuery;
import me.prettyprint.hector.api.query.QueryResult;
import me.prettyprint.hector.api.query.RangeSlicesQuery;
import me.prettyprint.hector.api.query.SliceQuery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.usergrid.locking.LockManager;
import org.usergrid.utils.JsonUtils;

public class CassandraService {

	public static String SYSTEM_KEYSPACE = "Usergrid";

	public static String STATIC_APPLICATION_KEYSPACE = "Usergrid_Applications";

	public static final boolean USE_VIRTUAL_KEYSPACES = true;

	public static final String APPLICATIONS_CF = "Applications";
	public static final String PROPERTIES_CF = "Properties";

	public static final int DEFAULT_COUNT = 1000;
	public static final int ALL_COUNT = 100000;
	public static final int INDEX_ENTRY_LIST_COUNT = 1000;
	public static final int DEFAULT_SEARCH_COUNT = 10000;

	public static final int RETRY_COUNT = 5;

	public static final String DEFAULT_APPLICATION = "default-app";
	public static final String MANAGEMENT_APPLICATION = "management";

	public static final UUID MANAGEMENT_APPLICATION_ID = new UUID(0, 1);
	public static final UUID DEFAULT_APPLICATION_ID = new UUID(0, 16);

	private static final Logger logger = LoggerFactory
			.getLogger(CassandraService.class);

	private static final Logger db_logger = LoggerFactory
			.getLogger(CassandraService.class.getPackage().getName() + ".DB");

	Cluster cluster;
	CassandraHostConfigurator chc;
	Properties properties;
	LockManager lockManager;

	private Keyspace systemKeyspace;

	public static final StringSerializer se = new StringSerializer();
	public static final ByteBufferSerializer be = new ByteBufferSerializer();
	public static final UUIDSerializer ue = new UUIDSerializer();
	public static final BytesArraySerializer bae = new BytesArraySerializer();
	public static final DynamicCompositeSerializer dce = new DynamicCompositeSerializer();
	public static final LongSerializer le = new LongSerializer();

	public static final UUID NULL_ID = new UUID(0, 0);

	public CassandraService(Properties properties, Cluster cluster,
			CassandraHostConfigurator cassandraHostConfigurator,
			LockManager lockManager) {
		this.properties = properties;
		this.cluster = cluster;
		chc = cassandraHostConfigurator;
		this.lockManager = lockManager;
		db_logger.info("" + cluster.getKnownPoolHosts(false));
	}

	public void init() throws Exception {
		HFactory.getOrCreateCluster(cluster.getName(), chc);

		Map<String, String> accessMap = new HashMap<String, String>();
		accessMap.put("username", properties.getProperty("cassandra.username"));
		accessMap.put("password", properties.getProperty("cassandra.password"));
		systemKeyspace = HFactory.createKeyspace(SYSTEM_KEYSPACE, cluster,
				createDefaultConsistencyLevelPolicy(),
				ON_FAIL_TRY_ALL_AVAILABLE, accessMap);
	}

	public Cluster getCluster() {
		return cluster;
	}

	public void setCluster(Cluster cluster) {
		this.cluster = cluster;
	}

	public CassandraHostConfigurator getCassandraHostConfigurator() {
		return chc;
	}

	public void setCassandraHostConfigurator(CassandraHostConfigurator chc) {
		this.chc = chc;
	}

	public Properties getProperties() {
		return properties;
	}

	public void setProperties(Properties properties) {
		this.properties = properties;
	}

	public Map<String, String> getPropertiesMap() {
		if (properties != null) {
			return asMap(properties);
		}
		return null;
	}

	public LockManager getLockManager() {
		return lockManager;
	}

	public void setLockManager(LockManager lockManager) {
		this.lockManager = lockManager;
	}

	/**
	 * @param applicationId
	 * @return keyspace for application UUID
	 */
	public static String keyspaceForApplication(UUID applicationId) {
		if (USE_VIRTUAL_KEYSPACES) {
			return STATIC_APPLICATION_KEYSPACE;
		} else {
			return "Application_" + applicationId.toString().replace('-', '_');
		}
	}

	public static UUID prefixForApplication(UUID applicationId) {
		if (USE_VIRTUAL_KEYSPACES) {
			return applicationId;
		} else {
			return null;
		}
	}

	public Keyspace getKeyspace(String keyspace, UUID prefix) {
		Map<String, String> accessMap = new HashMap<String, String>();
		accessMap.put("username", properties.getProperty("cassandra.username"));
		accessMap.put("password", properties.getProperty("cassandra.password"));
		Keyspace ko = null;
		if (USE_VIRTUAL_KEYSPACES && (prefix != null)) {
			ko = createVirtualKeyspace(keyspace, prefix, ue, cluster,
					createDefaultConsistencyLevelPolicy(),
					ON_FAIL_TRY_ALL_AVAILABLE, accessMap);
		} else {
			ko = HFactory.createKeyspace(keyspace, cluster,
					createDefaultConsistencyLevelPolicy(),
					ON_FAIL_TRY_ALL_AVAILABLE, accessMap);
		}
		return ko;
	}

	public Keyspace getApplicationKeyspace(UUID applicationId) {
		assert applicationId != null;
		Keyspace ko = getKeyspace(keyspaceForApplication(applicationId),
				prefixForApplication(applicationId));
		return ko;
	}

	/**
	 * The Usergrid_Applications keyspace directly
	 * 
	 * @return
	 */
	public Keyspace getUsergridApplicationKeyspace() {
		return getKeyspace(STATIC_APPLICATION_KEYSPACE, null);
	}

	public Keyspace getSystemKeyspace() {
		return systemKeyspace;
	}

	public void createKeyspace(String keyspace,
			List<ColumnFamilyDefinition> cf_defs) {

		logger.info("Creating keyspace: " + keyspace);

		String strategy_class = getString(properties,
				"cassandra.keyspace.strategy",
				"org.apache.cassandra.locator.SimpleStrategy");
		logger.info("Using strategy: " + strategy_class);

		int replication_factor = getIntValue(properties,
				"cassandra.keyspace.replication", 1);
		logger.info("Using replication (may be overriden by strategy options): "
				+ replication_factor);

		try {
			ThriftKsDef ks_def = (ThriftKsDef) HFactory
					.createKeyspaceDefinition(keyspace, strategy_class,
							replication_factor,
							new ArrayList<ColumnFamilyDefinition>());

			@SuppressWarnings({ "unchecked", "rawtypes" })
			Map<String, String> strategy_options = filter((Map) properties,
					"cassandra.keyspace.strategy.options.", true);
			if (strategy_options.size() > 0) {
				logger.info("Strategy options: "
						+ mapToFormattedJsonString(strategy_options));
				ks_def.setStrategyOptions(strategy_options);
			}

			cluster.addKeyspace(ks_def);
		} catch (Throwable e) {
			logger.error("Exception while creating keyspace, " + keyspace
					+ " - probably already exists", e);
		}

		boolean delay_configuration = (!"org.apache.cassandra.locator.SimpleStrategy"
				.equals(strategy_class)) || (replication_factor > 1);

		if (delay_configuration) {
			logger.info("Waiting 10s after keyspace creation");
			try {
				Thread.sleep(10000);
			} catch (InterruptedException e) {
			}
		}

		if (cf_defs != null) {
			for (ColumnFamilyDefinition cfDef : cf_defs) {
				try {
					cluster.addColumnFamily(cfDef);
				} catch (Throwable e) {
					logger.error(
							"Exception while creating CF, " + cfDef.getName()
									+ " - probably already exists", e);
				}
				if (delay_configuration) {
					try {
						logger.info("Waiting 2s after CF creation");
						Thread.sleep(2000);
					} catch (InterruptedException e) {
					}
				}
			}
		}
		if (delay_configuration) {
			try {
				logger.info("Waiting 5s before continuing setup");
				Thread.sleep(5000);
			} catch (InterruptedException e) {
			}
		}
	}

	public void checkKeyspaces() {

		List<KeyspaceDefinition> ksDefs = null;
		try {
			ksDefs = cluster.describeKeyspaces();
		} catch (Exception e) {
			db_logger.error("Unable to describe keyspaces", e);
		}

		if (ksDefs != null) {
			for (KeyspaceDefinition ksDef : ksDefs) {
				logger.info(ksDef.getName().toString());
			}
		}
	}

	public void logKeyspaces() {

		List<KeyspaceDefinition> ksDefs = null;
		try {
			ksDefs = cluster.describeKeyspaces();
		} catch (Exception e) {
			db_logger.error("Unable to describe keyspaces", e);
		}

		if (ksDefs != null) {
			for (KeyspaceDefinition ksDef : ksDefs) {
				System.out.println("Keyspace: " + ksDef.getName().toString());
				for (ColumnFamilyDefinition cf : ksDef.getCfDefs()) {
					System.out.println("  CF: " + cf.getName());

					System.out.println("    id: " + cf.getId());
					System.out.println("    comment: " + cf.getComment());
					System.out.println("    rowCacheSize: "
							+ cf.getRowCacheSize());
					System.out.println("    keyCacheSize: "
							+ cf.getKeyCacheSize());
					System.out.println("    readRepairChance: "
							+ cf.getReadRepairChance());
					System.out.println("    gcGraceSeconds: "
							+ cf.getGcGraceSeconds());
					System.out.println("    minCompactionThreshold: "
							+ cf.getMinCompactionThreshold());
					System.out.println("    maxCompactionThreshold: "
							+ cf.getMaxCompactionThreshold());
					System.out.println("    rowCacheSavePeriodInSeconds: "
							+ cf.getRowCacheSavePeriodInSeconds());
					System.out.println("    keyCacheSavePeriodInSeconds: "
							+ cf.getKeyCacheSavePeriodInSeconds());
					System.out.println("    memtableFlushAfterMins: "
							+ cf.getMemtableFlushAfterMins());
					System.out.println("    memtableThroughputInMb: "
							+ cf.getMemtableThroughputInMb());
					System.out.println("    memtableOperationsInMillions: "
							+ cf.getMemtableOperationsInMillions());
					System.out.println("    keyspaceName: "
							+ cf.getKeyspaceName());
					System.out.println("    comparatorType: "
							+ cf.getColumnType());
					System.out.println("    columnType: "
							+ JsonUtils.mapToJsonString(cf.getColumnType()));
					System.out.println("    subComparatorType: "
							+ JsonUtils.mapToJsonString(cf
									.getSubComparatorType()));
					System.out.println("    keyValidationClass: "
							+ cf.getKeyValidationClass());
					System.out.println("    columnMetadata:");
					for (ColumnDefinition column : cf.getColumnMetadata()) {
						System.out.println("      name: "
								+ string(column.getName()));
						System.out.println("        indexName: "
								+ column.getIndexName());
						System.out.println("        validationClass: "
								+ column.getValidationClass());
						System.out.println("        indexType: "
								+ column.getIndexType());
					}
					System.out.println("    defaultValidationClass: "
							+ cf.getDefaultValidationClass());
					System.out.println("    replicateOnWrite: "
							+ cf.isReplicateOnWrite());

				}
			}
		}
	}

	/**
	 * Gets the columns.
	 * 
	 * @param keyspace
	 *            the keyspace
	 * @param columnFamily
	 *            the column family
	 * @param key
	 *            the key
	 * @return columns
	 * @throws Exception
	 *             the exception
	 */
	public <N, V> List<HColumn<N, V>> getAllColumns(Keyspace ko,
			Object columnFamily, Object key, Serializer<N> nameSerializer,
			Serializer<V> valueSerializer) throws Exception {

		if (db_logger.isInfoEnabled()) {
			db_logger.info("getColumns cf=" + columnFamily + " key=" + key);
		}

		SliceQuery<ByteBuffer, N, V> q = createSliceQuery(ko, be,
				nameSerializer, valueSerializer);
		q.setColumnFamily(columnFamily.toString());
		q.setKey(bytebuffer(key));
		q.setRange(null, null, false, ALL_COUNT);
		QueryResult<ColumnSlice<N, V>> r = q.execute();
		ColumnSlice<N, V> slice = r.get();
		List<HColumn<N, V>> results = slice.getColumns();

		if (db_logger.isInfoEnabled()) {
			if (results == null) {
				db_logger.info("getColumns returned null");
			} else {
				db_logger.info("getColumns returned " + results.size()
						+ " columns");
			}
		}

		return results;
	}

	public List<HColumn<String, ByteBuffer>> getAllColumns(Keyspace ko,
			Object columnFamily, Object key) throws Exception {
		return getAllColumns(ko, columnFamily, key, se, be);
	}

	public Set<String> getAllColumnNames(Keyspace ko, Object columnFamily,
			Object key) throws Exception {
		List<HColumn<String, ByteBuffer>> columns = getAllColumns(ko,
				columnFamily, key);
		Set<String> set = new LinkedHashSet<String>();
		for (HColumn<String, ByteBuffer> column : columns) {
			set.add(column.getName());
		}
		return set;
	}

	/**
	 * Gets the columns.
	 * 
	 * @param keyspace
	 *            the keyspace
	 * @param columnFamily
	 *            the column family
	 * @param key
	 *            the key
	 * @param start
	 *            the start
	 * @param finish
	 *            the finish
	 * @param count
	 *            the count
	 * @param reversed
	 *            the reversed
	 * @return columns
	 * @throws Exception
	 *             the exception
	 */
	public List<HColumn<ByteBuffer, ByteBuffer>> getColumns(Keyspace ko,
			Object columnFamily, Object key, Object start, Object finish,
			int count, boolean reversed) throws Exception {

		if (db_logger.isInfoEnabled()) {
			db_logger.info("getColumns cf=" + columnFamily + " key=" + key
					+ " start=" + start + " finish=" + finish + " count="
					+ count + " reversed=" + reversed);
		}

		SliceQuery<ByteBuffer, ByteBuffer, ByteBuffer> q = createSliceQuery(ko,
				be, be, be);
		q.setColumnFamily(columnFamily.toString());
		q.setKey(bytebuffer(key));

		ByteBuffer start_bytes = null;
		if (start instanceof DynamicComposite) {
			start_bytes = ((DynamicComposite) start).serialize();
		} else if (start instanceof List) {
			start_bytes = DynamicComposite.toByteBuffer((List<?>) start);
		} else {
			start_bytes = bytebuffer(start);
		}

		ByteBuffer finish_bytes = null;
		if (finish instanceof DynamicComposite) {
			finish_bytes = ((DynamicComposite) finish).serialize();
		} else if (finish instanceof List) {
			finish_bytes = DynamicComposite.toByteBuffer((List<?>) finish);
		} else {
			finish_bytes = bytebuffer(finish);
		}

		/*
		 * if (reversed) { q.setRange(finish_bytes, start_bytes, reversed,
		 * count); } else { q.setRange(start_bytes, finish_bytes, reversed,
		 * count); }
		 */
		q.setRange(start_bytes, finish_bytes, reversed, count);
		QueryResult<ColumnSlice<ByteBuffer, ByteBuffer>> r = q.execute();
		ColumnSlice<ByteBuffer, ByteBuffer> slice = r.get();
		List<HColumn<ByteBuffer, ByteBuffer>> results = slice.getColumns();

		if (db_logger.isInfoEnabled()) {
			if (results == null) {
				db_logger.info("getColumns returned null");
			} else {
				db_logger.info("getColumns returned " + results.size()
						+ " columns");
			}
		}

		return results;
	}

	public Map<ByteBuffer, List<HColumn<ByteBuffer, ByteBuffer>>> multiGetColumns(
			Keyspace ko, Object columnFamily, List<?> keys, Object start,
			Object finish, int count, boolean reversed) throws Exception {

		if (db_logger.isInfoEnabled()) {
			db_logger.info("multiGetColumns cf=" + columnFamily + " keys="
					+ keys + " start=" + start + " finish=" + finish
					+ " count=" + count + " reversed=" + reversed);
		}

		MultigetSliceQuery<ByteBuffer, ByteBuffer, ByteBuffer> q = createMultigetSliceQuery(
				ko, be, be, be);
		q.setColumnFamily(columnFamily.toString());
		q.setKeys(bytebuffers(keys));

		ByteBuffer start_bytes = null;
		if (start instanceof DynamicComposite) {
			start_bytes = ((DynamicComposite) start).serialize();
		} else if (start instanceof List) {
			start_bytes = DynamicComposite.toByteBuffer((List<?>) start);
		} else {
			start_bytes = bytebuffer(start);
		}

		ByteBuffer finish_bytes = null;
		if (finish instanceof DynamicComposite) {
			finish_bytes = ((DynamicComposite) finish).serialize();
		} else if (finish instanceof List) {
			finish_bytes = DynamicComposite.toByteBuffer((List<?>) finish);
		} else {
			finish_bytes = bytebuffer(finish);
		}

		q.setRange(start_bytes, finish_bytes, reversed, count);
		QueryResult<Rows<ByteBuffer, ByteBuffer, ByteBuffer>> r = q.execute();
		Rows<ByteBuffer, ByteBuffer, ByteBuffer> rows = r.get();

		Map<ByteBuffer, List<HColumn<ByteBuffer, ByteBuffer>>> results = new LinkedHashMap<ByteBuffer, List<HColumn<ByteBuffer, ByteBuffer>>>();
		for (Row<ByteBuffer, ByteBuffer, ByteBuffer> row : rows) {
			results.put(row.getKey(), row.getColumnSlice().getColumns());
		}

		return results;
	}

	/**
	 * Gets the columns.
	 * 
	 * @param keyspace
	 *            the keyspace
	 * @param columnFamily
	 *            the column family
	 * @param keys
	 *            the keys
	 * @return map of keys to columns
	 * @throws Exception
	 *             the exception
	 */
	public <K, N, V> Rows<K, N, V> getRows(Keyspace ko, Object columnFamily,
			List<K> keys, Serializer<K> keySerializer,
			Serializer<N> nameSerializer, Serializer<V> valueSerializer)
			throws Exception {

		if (db_logger.isInfoEnabled()) {
			db_logger.info("getColumns cf=" + columnFamily + " keys=" + keys);
		}

		MultigetSliceQuery<K, N, V> q = createMultigetSliceQuery(ko,
				keySerializer, nameSerializer, valueSerializer);
		q.setColumnFamily(columnFamily.toString());
		q.setKeys(keys);
		q.setRange(null, null, false, ALL_COUNT);
		QueryResult<Rows<K, N, V>> r = q.execute();
		Rows<K, N, V> results = r.get();

		if (db_logger.isInfoEnabled()) {
			if (results == null) {
				db_logger.info("getColumns returned null");
			} else {
				db_logger.info("getColumns returned " + results.getCount()
						+ " columns");
			}
		}

		return results;
	}

	/**
	 * Gets the columns.
	 * 
	 * @param keyspace
	 *            the keyspace
	 * @param columnFamily
	 *            the column family
	 * @param key
	 *            the key
	 * @param columnNames
	 *            the column names
	 * @return columns
	 * @throws Exception
	 *             the exception
	 */
	@SuppressWarnings("unchecked")
	public <N, V> List<HColumn<N, V>> getColumns(Keyspace ko,
			Object columnFamily, Object key, Set<String> columnNames,
			Serializer<N> nameSerializer, Serializer<V> valueSerializer)
			throws Exception {

		if (db_logger.isInfoEnabled()) {
			db_logger.info("getColumns cf=" + columnFamily + " key=" + key
					+ " names=" + columnNames);
		}

		SliceQuery<ByteBuffer, N, V> q = createSliceQuery(ko, be,
				nameSerializer, valueSerializer);
		q.setColumnFamily(columnFamily.toString());
		q.setKey(bytebuffer(key));
		// q.setColumnNames(columnNames.toArray(new String[0]));
		q.setColumnNames((N[]) nameSerializer.fromBytesSet(
				se.toBytesSet(new ArrayList<String>(columnNames))).toArray());

		QueryResult<ColumnSlice<N, V>> r = q.execute();
		ColumnSlice<N, V> slice = r.get();
		List<HColumn<N, V>> results = slice.getColumns();

		if (db_logger.isInfoEnabled()) {
			if (results == null) {
				db_logger.info("getColumns returned null");
			} else {
				db_logger.info("getColumns returned " + results.size()
						+ " columns");
			}
		}

		return results;
	}

	/**
	 * Gets the columns.
	 * 
	 * @param keyspace
	 *            the keyspace
	 * @param columnFamily
	 *            the column family
	 * @param keys
	 *            the keys
	 * @param columnNames
	 *            the column names
	 * @return map of keys to columns
	 * @throws Exception
	 *             the exception
	 */
	@SuppressWarnings("unchecked")
	public <K, N, V> Rows<K, N, V> getRows(Keyspace ko, Object columnFamily,
			List<K> keys, Set<String> columnNames, Serializer<K> keySerializer,
			Serializer<N> nameSerializer, Serializer<V> valueSerializer)
			throws Exception {

		if (db_logger.isInfoEnabled()) {
			db_logger.info("getColumns cf=" + columnFamily + " keys=" + keys
					+ " names=" + columnNames);
		}

		MultigetSliceQuery<K, N, V> q = createMultigetSliceQuery(ko,
				keySerializer, nameSerializer, valueSerializer);
		q.setColumnFamily(columnFamily.toString());
		q.setKeys(keys);
		q.setColumnNames((N[]) nameSerializer.fromBytesSet(
				se.toBytesSet(new ArrayList<String>(columnNames))).toArray());
		QueryResult<Rows<K, N, V>> r = q.execute();
		Rows<K, N, V> results = r.get();

		if (db_logger.isInfoEnabled()) {
			if (results == null) {
				db_logger.info("getColumns returned null");
			} else {
				db_logger.info("getColumns returned " + results.getCount()
						+ " columns");
			}
		}

		return results;
	}

	/**
	 * Gets the column.
	 * 
	 * @param keyspace
	 *            the keyspace
	 * @param columnFamily
	 *            the column family
	 * @param key
	 *            the key
	 * @param column
	 *            the column
	 * @return column
	 * @throws Exception
	 *             the exception
	 */
	public <N, V> HColumn<N, V> getColumn(Keyspace ko, Object columnFamily,
			Object key, N column, Serializer<N> nameSerializer,
			Serializer<V> valueSerializer) throws Exception {

		if (db_logger.isInfoEnabled()) {
			db_logger.info("getColumn cf=" + columnFamily + " key=" + key
					+ " column=" + column);
		}

		/*
		 * ByteBuffer column_bytes = null; if (column instanceof List) {
		 * column_bytes = Composite.serializeToByteBuffer((List<?>) column); }
		 * else { column_bytes = bytebuffer(column); }
		 */

		ColumnQuery<ByteBuffer, N, V> q = HFactory.createColumnQuery(ko, be,
				nameSerializer, valueSerializer);
		QueryResult<HColumn<N, V>> r = q.setKey(bytebuffer(key))
				.setName(column).setColumnFamily(columnFamily.toString())
				.execute();
		HColumn<N, V> result = r.get();

		if (db_logger.isInfoEnabled()) {
			if (result == null) {
				db_logger.info("getColumn returned null");
			}
		}

		return result;
	}

	public <N, V> ColumnSlice<N, V> getColumns(Keyspace ko,
			Object columnFamily, Object key, N[] columns,
			Serializer<N> nameSerializer, Serializer<V> valueSerializer)
			throws Exception {

		if (db_logger.isInfoEnabled()) {
			db_logger.info("getColumn cf=" + columnFamily + " key=" + key
					+ " column=" + columns);
		}

		/*
		 * ByteBuffer column_bytes = null; if (column instanceof List) {
		 * column_bytes = Composite.serializeToByteBuffer((List<?>) column); }
		 * else { column_bytes = bytebuffer(column); }
		 */

		SliceQuery<ByteBuffer, N, V> q = HFactory.createSliceQuery(ko, be,
				nameSerializer, valueSerializer);
		QueryResult<ColumnSlice<N, V>> r = q.setKey(bytebuffer(key))
				.setColumnNames(columns)
				.setColumnFamily(columnFamily.toString()).execute();
		ColumnSlice<N, V> result = r.get();

		if (db_logger.isInfoEnabled()) {
			if (result == null) {
				db_logger.info("getColumn returned null");
			}
		}

		return result;
	}

	public HColumn<String, ByteBuffer> getColumn(Keyspace ko,
			Object columnFamily, Object key, String column) throws Exception {
		return getColumn(ko, columnFamily, key, column, se, be);
	}

	/**
	 * Sets the column.
	 * 
	 * @param keyspace
	 *            the keyspace
	 * @param columnFamily
	 *            the column family
	 * @param key
	 *            the key
	 * @param columnName
	 *            the column name
	 * @param columnValue
	 *            the column value
	 * @throws Exception
	 *             the exception
	 */
	public void setColumn(Keyspace ko, Object columnFamily, Object key,
			Object columnName, Object columnValue) throws Exception {

		if (db_logger.isInfoEnabled()) {
			db_logger.info("setColumn cf=" + columnFamily + " key=" + key
					+ " name=" + columnName + " value=" + columnValue);
		}

		ByteBuffer name_bytes = null;
		if (columnName instanceof List) {
			name_bytes = DynamicComposite.toByteBuffer((List<?>) columnName);
		} else {
			name_bytes = bytebuffer(columnName);
		}

		ByteBuffer value_bytes = null;
		if (columnValue instanceof List) {
			value_bytes = DynamicComposite.toByteBuffer((List<?>) columnValue);
		} else {
			value_bytes = bytebuffer(columnValue);
		}

		Mutator<ByteBuffer> m = createMutator(ko, be);
		m.insert(bytebuffer(key), columnFamily.toString(),
				createColumn(name_bytes, value_bytes, be, be));

	}

	/**
	 * Sets the columns.
	 * 
	 * @param keyspace
	 *            the keyspace
	 * @param columnFamily
	 *            the column family
	 * @param key
	 *            the key
	 * @param map
	 *            the map
	 * @throws Exception
	 *             the exception
	 */
	public void setColumns(Keyspace ko, Object columnFamily, byte[] key,
			Map<?, ?> map) throws Exception {

		if (db_logger.isInfoEnabled()) {
			db_logger.info("setColumns cf=" + columnFamily + " key=" + key
					+ " map=" + map);
		}

		Mutator<ByteBuffer> m = createMutator(ko, be);
		long timestamp = createTimestamp();

		for (Object name : map.keySet()) {
			Object value = map.get(name);
			if (value != null) {

				ByteBuffer name_bytes = null;
				if (name instanceof List) {
					name_bytes = DynamicComposite.toByteBuffer((List<?>) name);
				} else {
					name_bytes = bytebuffer(name);
				}

				ByteBuffer value_bytes = null;
				if (value instanceof List) {
					value_bytes = DynamicComposite
							.toByteBuffer((List<?>) value);
				} else {
					value_bytes = bytebuffer(value);
				}

				m.addInsertion(
						bytebuffer(key),
						columnFamily.toString(),
						createColumn(name_bytes, value_bytes, timestamp, be, be));

			}
		}
		batchExecute(m, CassandraService.RETRY_COUNT);
	}

	/**
	 * Create a timestamp based on the TimeResolution set to the cluster.
	 * 
	 * @return a timestamp
	 */
	public long createTimestamp() {
		return chc.getClockResolution().createClock();
	}

	/**
	 * Delete column.
	 * 
	 * @param keyspace
	 *            the keyspace
	 * @param columnFamily
	 *            the column family
	 * @param key
	 *            the key
	 * @param column
	 *            the column
	 * @throws Exception
	 *             the exception
	 */
	public void deleteColumn(Keyspace ko, Object columnFamily, Object key,
			Object column) throws Exception {

		if (db_logger.isInfoEnabled()) {
			db_logger.info("deleteColumn cf=" + columnFamily + " key=" + key
					+ " name=" + column);
		}

		Mutator<ByteBuffer> m = createMutator(ko, be);
		m.delete(bytebuffer(key), columnFamily.toString(), bytebuffer(column),
				be);
	}

	/**
	 * Gets the row keys.
	 * 
	 * @param keyspace
	 *            the keyspace
	 * @param columnFamily
	 *            the column family
	 * @return set of keys
	 * @throws Exception
	 *             the exception
	 */
	public <K> Set<K> getRowKeySet(Keyspace ko, Object columnFamily,
			Serializer<K> keySerializer) throws Exception {

		if (db_logger.isInfoEnabled()) {
			db_logger.info("getRowKeys cf=" + columnFamily);
		}

		RangeSlicesQuery<K, ByteBuffer, ByteBuffer> q = createRangeSlicesQuery(
				ko, keySerializer, be, be);
		q.setColumnFamily(columnFamily.toString());
		q.setKeys(null, null);
		q.setColumnNames(new ByteBuffer[0]);
		QueryResult<OrderedRows<K, ByteBuffer, ByteBuffer>> r = q.execute();
		OrderedRows<K, ByteBuffer, ByteBuffer> rows = r.get();

		Set<K> results = new LinkedHashSet<K>();
		for (Row<K, ByteBuffer, ByteBuffer> row : rows) {
			results.add(row.getKey());
		}

		if (db_logger.isInfoEnabled()) {
			{
				db_logger.info("getRowKeys returned " + results.size()
						+ " rows");
			}
		}

		return results;
	}

	/**
	 * Gets the row keys as uui ds.
	 * 
	 * @param keyspace
	 *            the keyspace
	 * @param columnFamily
	 *            the column family
	 * @return list of row key UUIDs
	 * @throws Exception
	 *             the exception
	 */
	public <K> List<K> getRowKeyList(Keyspace ko, Object columnFamily,
			Serializer<K> keySerializer) throws Exception {

		RangeSlicesQuery<K, ByteBuffer, ByteBuffer> q = createRangeSlicesQuery(
				ko, keySerializer, be, be);
		q.setColumnFamily(columnFamily.toString());
		q.setKeys(null, null);
		q.setColumnNames(new ByteBuffer[0]);
		QueryResult<OrderedRows<K, ByteBuffer, ByteBuffer>> r = q.execute();
		OrderedRows<K, ByteBuffer, ByteBuffer> rows = r.get();

		List<K> list = new ArrayList<K>();
		for (Row<K, ByteBuffer, ByteBuffer> row : rows) {
			list.add(row.getKey());
			// K uuid = row.getKey();
			// if (uuid != UUIDUtils.zeroUUID) {
			// list.add(uuid);
			// }
		}

		return list;
	}

	/**
	 * Delete row.
	 * 
	 * @param keyspace
	 *            the keyspace
	 * @param columnFamily
	 *            the column family
	 * @param key
	 *            the key
	 * @throws Exception
	 *             the exception
	 */
	public void deleteRow(Keyspace ko, final Object columnFamily,
			final Object key) throws Exception {

		if (db_logger.isInfoEnabled()) {
			db_logger.info("deleteRow cf=" + columnFamily + " key=" + key);
		}

		createMutator(ko, be).addDeletion(bytebuffer(key),
				columnFamily.toString()).execute();
	}

	public void deleteRow(Keyspace ko, final Object columnFamily,
			final String key) throws Exception {

		if (db_logger.isInfoEnabled()) {
			db_logger.info("deleteRow cf=" + columnFamily + " key=" + key);
		}

		createMutator(ko, se).addDeletion(key, columnFamily.toString())
				.execute();
	}

	/**
	 * Delete row.
	 * 
	 * @param keyspace
	 *            the keyspace
	 * @param columnFamily
	 *            the column family
	 * @param key
	 *            the key
	 * @param timestamp
	 *            the timestamp
	 * @throws Exception
	 *             the exception
	 */
	public void deleteRow(Keyspace ko, final Object columnFamily,
			final Object key, final long timestamp) throws Exception {

		if (db_logger.isInfoEnabled()) {
			db_logger.info("deleteRow cf=" + columnFamily + " key=" + key
					+ " timestamp=" + timestamp);
		}

		createMutator(ko, be).addDeletion(bytebuffer(key),
				columnFamily.toString(), timestamp).execute();
	}

	/**
	 * Gets the id list.
	 * 
	 * @param keyspace
	 *            the keyspace
	 * @param columnFamily
	 *            the column family
	 * @param key
	 *            the key
	 * @param start
	 *            the start
	 * @param finish
	 *            the finish
	 * @param count
	 *            the count
	 * @param use_column_value
	 *            the use_column_value
	 * @param reversed
	 *            the reversed
	 * @param compositeResults
	 *            the composite results
	 * @param composite_offset
	 *            the composite_offset
	 * @return list of columns as UUIDs
	 * @throws Exception
	 *             the exception
	 */
	public List<UUID> getIdList(Keyspace ko, Object key, Object start,
			Object finish, int count, boolean reversed) throws Exception {

		if (count <= 0) {
			count = DEFAULT_COUNT;
		}
		List<UUID> ids = new ArrayList<UUID>();
		if (NULL_ID.equals(start)) {
			start = null;
		}
		List<HColumn<ByteBuffer, ByteBuffer>> results = getColumns(ko,
				ENTITY_ID_SETS, key, start, finish, count, reversed);

		if (results != null) {
			for (HColumn<ByteBuffer, ByteBuffer> result : results) {
				ByteBuffer bytes = result.getName();
				ids.add(uuid(bytes));
			}
		}

		return ids;

	}

	public Map<UUID, UUID> getIdPairList(Keyspace ko, Object key, Object start,
			Object finish, int count, boolean reversed) throws Exception {

		if (count <= 0) {
			count = DEFAULT_COUNT;
		}
		Map<UUID, UUID> ids = new LinkedHashMap<UUID, UUID>();
		if (NULL_ID.equals(start)) {
			start = null;
		}
		List<HColumn<ByteBuffer, ByteBuffer>> results = getColumns(ko,
				ENTITY_ID_SETS, key, start, finish, count, reversed);

		if (results != null) {
			for (HColumn<ByteBuffer, ByteBuffer> result : results) {
				ids.put(uuid(result.getName()), uuid(result.getValue()));
			}
		}

		return ids;

	}

	public int countColumns(Keyspace ko, Object columnFamily, Object key)
			throws Exception {

		CountQuery<ByteBuffer, ByteBuffer> cq = HFactory.createCountQuery(ko,
				be, be);
		cq.setColumnFamily(columnFamily.toString());
		cq.setKey(bytebuffer(key));
		cq.setRange(ByteBuffer.allocate(0), ByteBuffer.allocate(0), 100000000);
		QueryResult<Integer> r = cq.execute();
		if (r == null) {
			return 0;
		}
		return r.get();
	}

	/**
	 * Sets the id list.
	 * 
	 * @param keyspace
	 *            the keyspace
	 * @param targetId
	 *            the target id
	 * @param columnFamily
	 *            the column family
	 * @param keyPrefix
	 *            the key prefix
	 * @param keySuffix
	 *            the key suffix
	 * @param keyIds
	 *            the key ids
	 * @param setColumnValue
	 *            the set column value
	 * @throws Exception
	 *             the exception
	 */
	public void setIdList(Keyspace ko, UUID targetId, String keyPrefix,
			String keySuffix, List<UUID> keyIds) throws Exception {
		long timestamp = createTimestamp();
		Mutator<ByteBuffer> batch = createMutator(ko, be);
		batch = buildSetIdListMutator(batch, targetId,
				ENTITY_ID_SETS.toString(), keyPrefix, keySuffix, keyIds,
				timestamp);
		batchExecute(batch, CassandraService.RETRY_COUNT);
	}

	public Mutator<ByteBuffer> batchUpdateIdSet(Mutator<ByteBuffer> batch,
			UUID entityId, String setName, UUID elementValue,
			boolean removeFromSet, long timestamp) throws Exception {

		if (elementValue != null) {
			if (!removeFromSet) {
				// Set the new value
				addInsertToMutator(batch, ENTITY_ID_SETS,
						key(entityId, setName), elementValue,
						ByteBuffer.allocate(0), timestamp);

				addInsertToMutator(batch, ENTITY_DICTIONARIES,
						key(entityId, DICTIONARY_ID_SETS), setName, null,
						timestamp);
			} else {
				addDeleteToMutator(batch, ENTITY_ID_SETS,
						key(entityId, setName), timestamp);
			}
		}

		return batch;
	}

	public void deleteIdSet(UUID applicationId, UUID entityId, String setName)
			throws Exception {
		Keyspace ko = getApplicationKeyspace(applicationId);
		deleteRow(ko, ENTITY_ID_SETS, key(entityId, setName));
	}

	public void addToIdSet(UUID applicationId, UUID entityId, String setName,
			UUID elementValue) throws Exception {

		if (elementValue == null) {
			return;
		}

		long timestamp = createTimestamp();
		Mutator<ByteBuffer> batch = createMutator(
				getApplicationKeyspace(applicationId), be);

		batch = batchUpdateIdSet(batch, entityId, setName, elementValue, false,
				timestamp);

		batchExecute(batch, CassandraService.RETRY_COUNT);
	}

	public void removeFromIdSet(UUID applicationId, UUID entityId,
			String setName, UUID elementValue) throws Exception {

		long timestamp = createTimestamp();
		Mutator<ByteBuffer> batch = createMutator(
				getApplicationKeyspace(applicationId), be);

		batch = batchUpdateIdSet(batch, entityId, setName, elementValue, true,
				timestamp);

		batchExecute(batch, CassandraService.RETRY_COUNT);
	}

	public List<UUID> getIdSet(UUID applicationId, UUID entityId, String setName)
			throws Exception {
		return getIdList(getApplicationKeyspace(applicationId),
				key(entityId, setName), null, null, 0, false);
	}

	boolean clusterUp = false;

	public void startClusterHealthCheck() {

		ScheduledExecutorService executorService = Executors
				.newSingleThreadScheduledExecutor();
		executorService.scheduleWithFixedDelay(new Runnable() {
			@Override
			public void run() {
				if (cluster != null) {
					HConnectionManager connectionManager = cluster
							.getConnectionManager();
					if (connectionManager != null) {
						clusterUp = !connectionManager.getHosts().isEmpty();
					}
				}
			}
		}, 1, 5, TimeUnit.SECONDS);

	}

}
