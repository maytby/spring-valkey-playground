package org.maymichael.services;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.maymichael.data.*;
import org.springframework.data.redis.core.PipelinedRedisKeyValueAdapter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.core.mapping.RedisMappingContext;
import org.springframework.stereotype.Service;
import org.springframework.util.StopWatch;

import java.nio.charset.StandardCharsets;
import java.util.*;

@Slf4j
@Service
public class DataService {

    public enum SaveStrategy {
        CRUD,
        ADAPTER,
        PIPELINED_ADAPTER,
    }

    public enum SerializerType {
        RAW,
        KRYO,
        BASE64,
    }

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    TransactionRepository transactionRepository;
    @Autowired
    TransactionValueRepository transactionValueRepository;
    @Autowired
    TransactionKeyRepository transactionKeyRepository;

    @Autowired
    private PipelinedRedisKeyValueAdapter redisKeyValueAdapter;

    @Autowired
    private RedisKeyValueTemplate redisKeyValueTemplate;

    @Autowired
    private RedisTemplate<?, ?> redisTemplate;

    @Autowired
    private RedisMappingContext redisMappingContext;

    private String resolveKeySpace(Class<?> type) {
        return redisMappingContext.getRequiredPersistentEntity(type).getKeySpace();
    }

    private List<byte[]> dataSet;

    public List<byte[]> createDataSet(int size) {
        synchronized (this) {
            if (dataSet == null) {
                dataSet = new ArrayList<>();
            }
            if (dataSet.size() < size) {
                for (int i = dataSet.size(); i < size; ++i) {
                    var rs = RandomStringUtils.insecure().nextAlphanumeric(1_000_00, 3_000_00);
                    var bd = rs.getBytes(StandardCharsets.UTF_8);
                    dataSet.add(bd);
                }
            }
            return dataSet;
        }
    }

    public void clearDataSet() {
        synchronized (this) {
            dataSet.clear();
        }
    }

    private String buildRedisQuery(Class<?> clazz, String query) {
        return resolveKeySpace(clazz) + ":" + query;
    }

    public List<TransactionValue> saveData(String tid, int numItems, SaveStrategy strategy, SerializerType serializerType) {
        var tvList = new ArrayList<TransactionValue>();
        var totalSize = 0;
        StopWatch sw = new StopWatch();
        sw.start("create dataset");
        var binaryDataSet = createDataSet(numItems);
        sw.stop();
        log.info("data set creation time: duration={}ms id={}", sw.lastTaskInfo().getTimeMillis(), tid);
        for (int i = 0; i < numItems; i++) {
            var something = i % 2 == 0 ? "even" : "odd";
            var bd = binaryDataSet.get(i);
            var tv = TransactionValue.builder()
                    .tid(tid)
                    .something(something)
                    .binaryData(serializerType == SerializerType.KRYO ? BinaryData.builder().data(bd).build() : null)
                    .binaryDataRaw(serializerType == SerializerType.RAW ? BinaryDataRaw.builder().data(bd).build() : null)
                    .binaryDataBase64(serializerType == SerializerType.BASE64 ? BinaryDataBase64.builder().data(bd).build() : null)
                    .build();
            totalSize += bd.length;
            tvList.add(tv);
        }
        sw.start("save");
        switch (strategy) {
            case CRUD:
                // crud
                transactionValueRepository.saveAll(tvList);
                break;
            case ADAPTER:
                // not pipelined ( should be the same as crud )
                redisKeyValueTemplate.execute(adapter -> {
                    // but by parallelizing this loop we can speed up quite a bit
                    tvList.parallelStream().forEach(tv -> {
                        adapter.put(tv.getId(), tv, resolveKeySpace(tv.getClass()));
                    });
                    return null;
                });
                break;
            case PIPELINED_ADAPTER:
                // pipelined
                // faster the more items are processed
                // since we reuse the same connection for all items in a pipeline
                // downside: can only create, updates are not possible
                redisTemplate.executePipelined((RedisCallback<?>) connection -> {
                    // can NOT parallelize this, since connection is not thread safe!
                    tvList.forEach(tv -> {
                        redisKeyValueAdapter.putOnConnection(connection, tv.getId(), tv);
                    });
                    return null;
                });
                break;
        }
        sw.stop();
        log.info("save time: duration={}ms totalData=\"{}\" serializer={} strategy={} id={}", sw.lastTaskInfo().getTimeMillis(),
                FileUtils.byteCountToDisplaySize(totalSize), serializerType.name(), strategy.name(), tid);
        return tvList;
    }

    public void saveBigData(int numItems, SaveStrategy strategy, SerializerType serializerType) {
        var t = Transaction.builder().build();
        log.info("saveBigData request received - numItems={} strategy={} id={}", numItems, strategy.name(), t.getId());
        transactionRepository.save(t);
        var tvList = saveData(t.getId(), numItems, strategy, serializerType);
        StopWatch sw = new StopWatch();
        sw.start("read");
        var results = stringRedisTemplate.executePipelined((RedisCallback<?>) con -> {
            con.setCommands().sMembers(buildRedisQuery(TransactionValue.class, "tid:" + t.getId()).getBytes(StandardCharsets.UTF_8));
            return null;
        });
        assert results.size() == 1;
        var valuesForTid = getAndCheckData(results, tvList);
        sw.stop();
        log.info("read time: duration={}ms serializer={} id={}", sw.lastTaskInfo().getTimeMillis(), serializerType.name(), t.getId());

        var totalTransactions = transactionValueRepository.count();
        log.info("totalTransactions={} values={} serializer={} id={}", totalTransactions, valuesForTid.size(), serializerType.name(), t.getId());
    }

    public List<TransactionValue> getDataForId(String tid) {
        return transactionValueRepository.findAllByTid(tid);
    }

    private Set<String> getAndCheckData(List<Object> results, List<TransactionValue> tvList) {
        @SuppressWarnings("unchecked") var valuesForTid = (Set<String>) results.getFirst();

        valuesForTid.forEach(tvid -> {
            var tvd = transactionValueRepository.findById(tvid);
            var match = tvList.stream().filter(tv -> Objects.equals(tv.getId(), tvid)).findFirst();
            assert match.isPresent();
            assert tvd.isPresent();
            assert match.get().getBinaryData().getData().length == tvd.get().getBinaryData().getData().length;
            assert new String(match.get().getBinaryData().getData(), StandardCharsets.UTF_8).equals(new String(tvd.get().getBinaryData().getData(), StandardCharsets.UTF_8));
        });
        return valuesForTid;
    }

    public void testMassGet() {
        var t = Transaction.builder().build();
        transactionRepository.save(t);
        for (int i = 0; i < 10; i++) {
            var something = i % 2 == 0 ? "even" : "odd";
            var tv = TransactionValue.builder()
                    .tid(t.getId())
                    .something(something)
                    .build();
            transactionValueRepository.save(tv);
        }
        for (int i = 0; i < 5; i++) {
            transactionKeyRepository.save(TransactionKey.builder()
                    .tid(t.getId())
                    .build());
        }

        var results = stringRedisTemplate.executePipelined((RedisCallback<?>) con -> {
            con.setCommands().sMembers(buildRedisQuery(TransactionValue.class, "tid:" + t.getId()).getBytes(StandardCharsets.UTF_8));
            con.setCommands().sMembers(buildRedisQuery(TransactionKey.class, "tid:" + t.getId()).getBytes(StandardCharsets.UTF_8));
            return null;
        });
        assert results.size() == 2;
        var valuesForTid = (Set<String>) results.get(0);
        var keysForTid = (Set<String>) results.get(1);

        log.info("results={} values={} keys={}", results.size(), valuesForTid.size(), keysForTid.size());

    }
}
