package indexingTopology.bolt;

import indexingTopology.data.PartialQueryResult;
import indexingTopology.util.*;
import javafx.util.Pair;
import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.topology.base.BaseRichBolt;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;
import com.google.common.util.concurrent.AtomicDouble;
import indexingTopology.config.TopologyConfig;
import indexingTopology.metadata.FilePartitionSchemaManager;
import indexingTopology.metadata.FileMetaData;
import indexingTopology.streams.Streams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;

/**
 * Created by acelzj on 11/15/16.
 */
abstract public class QueryCoordinator<T extends Number & Comparable<T>> extends BaseRichBolt {

    private OutputCollector collector;

    private FilePartitionSchemaManager filePartitionSchemaManager;

    private Semaphore concurrentQueriesSemaphore;

    private static final int MAX_NUMBER_OF_CONCURRENT_QUERIES = 5;

    private long queryId;

    private transient List<Integer> queryServers;

    private List<Integer> indexServers;

    private transient Map<Integer, Long> indexTaskToTimestampMapping;

    private ArrayBlockingQueue<SubQuery> taskQueue;

    private transient Map<Integer, ArrayBlockingQueue<SubQuery>> taskIdToTaskQueue;

    private BalancedPartition balancedPartition;

    private BalancedPartition balancedPartitionToBeDeleted;

    private int numberOfPartitions;

    private T lowerBound;
    private T upperBound;

    private AtomicDouble minIndexValue;
    private AtomicDouble maxIndexValue;

    protected LinkedBlockingQueue<Query> pendingQueue;

    private Map<Long, Pair> queryIdToStartTime;

    private int totalQueries;


    private static final Logger LOG = LoggerFactory.getLogger(QueryCoordinator.class);

    public QueryCoordinator(T lowerBound, T upperBound) {
        this.lowerBound = lowerBound;
        this.upperBound = upperBound;
    }

    public void prepare(Map map, TopologyContext topologyContext, OutputCollector outputCollector) {
        collector = outputCollector;

        concurrentQueriesSemaphore = new Semaphore(MAX_NUMBER_OF_CONCURRENT_QUERIES);
        queryId = 0;
        filePartitionSchemaManager = new FilePartitionSchemaManager();

        taskQueue = new ArrayBlockingQueue<>(TopologyConfig.TASK_QUEUE_CAPACITY);

        queryServers = topologyContext.getComponentTasks("ChunkScannerBolt");

//        System.out.println("query sever ids " + queryServers);
        taskIdToTaskQueue = new HashMap<Integer, ArrayBlockingQueue<SubQuery>>();
//        queryServers = new ArrayList<Integer>();
//        for (String componentId : componentIds) {
//            queryServers.addAll(topologyContext.getComponentTasks(componentId));
//        }
        createTaskQueues(queryServers);

        
//        componentIds = topologyContext.getThisTargets().get(Streams.BPlusTreeQueryStream).keySet();
//        indexServers = new ArrayList<Integer>();
//        for (String componentId : componentIds) {
//            indexServers.addAll(topologyContext.getComponentTasks(componentId));
//        }
        indexServers = topologyContext.getComponentTasks("IndexerBolt");


        minIndexValue = new AtomicDouble(2000);
        maxIndexValue = new AtomicDouble(0);


        queryIdToStartTime = new HashMap<>();

        setTimestamps();

        numberOfPartitions = queryServers.size();
        balancedPartition = new BalancedPartition(numberOfPartitions, lowerBound, upperBound);

        balancedPartitionToBeDeleted = null;

        pendingQueue = new LinkedBlockingQueue<>();

        createQueryHandlingThread();
    }

    public void execute(Tuple tuple) {
        if (tuple.getSourceStreamId().equals(Streams.FileInformationUpdateStream)) {
            String fileName = tuple.getString(0);
            TimeDomain timeDomain = (TimeDomain) tuple.getValueByField("timeDomain");
            KeyDomain keyDomain = (KeyDomain) tuple.getValueByField("keyDomain");

            filePartitionSchemaManager.add(new FileMetaData(fileName, ((T) keyDomain.getLowerBound()).doubleValue(),
                    ((T) keyDomain.getUpperBound()).doubleValue(), timeDomain.getStartTimestamp(), timeDomain.getEndTimestamp()));
        } else if (tuple.getSourceStreamId().equals(Streams.QueryFinishedStream)) {
            Long queryId = tuple.getLong(0);

//            Long start = queryIdToStartTime.get(queryId);
            Pair pair = queryIdToStartTime.get(queryId);


            if (pair != null) {
                Long start = (Long) pair.getKey();
                int fileSize = (Integer) pair.getValue();
//                LOG.info("query id " + queryId);
                LOG.info("Query time " + (System.currentTimeMillis() - start));
//                LOG.info("file size " + fileSize);
                queryIdToStartTime.remove(queryId);
            }

//            System.out.println("query id " + queryId + " has been finished!!!");
            concurrentQueriesSemaphore.release();

        } else if (tuple.getSourceStreamId().equals(Streams.FileSubQueryFinishStream)) {

            int taskId = tuple.getSourceTask();

//            /*task queue model
            sendSubqueryToTask(taskId);
//            */

            /*our method
            sendSubquery(taskId);
            */

        } else if (tuple.getSourceStreamId().equals(Streams.QueryGenerateStream)) {
            Query query = (Query) tuple.getValueByField("query");

            try {
                pendingQueue.put(query);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

        } else if (tuple.getSourceStreamId().equals(Streams.TimestampUpdateStream)) {
            int taskId = tuple.getIntegerByField("taskId");
            TimeDomain timeDomain = (TimeDomain) tuple.getValueByField("timeDomain");
            KeyDomain keyDomain = (KeyDomain) tuple.getValueByField("keyDomain");
            Long endTimestamp = timeDomain.getEndTimestamp();

            indexTaskToTimestampMapping.put(taskId, endTimestamp);

            T keyRangeLowerBound = (T) keyDomain.getLowerBound();
            T keyRangeUpperBound = (T) keyDomain.getUpperBound();

            if (balancedPartitionToBeDeleted != null) {
                updateStaleBalancedPartition(keyRangeLowerBound, keyRangeUpperBound);
                if (isPartitionDeletable()) {
                    balancedPartitionToBeDeleted = null;
                    collector.emit(Streams.EnableRepartitionStream, new Values("Partition can be enabled!"));
                }
            }

            collector.emitDirect(taskId, Streams.TreeCleanStream, new Values(keyDomain, timeDomain));

        } else if (tuple.getSourceStreamId().equals(Streams.IntervalPartitionUpdateStream)) {
//            Map<Integer, Integer> intervalToPartitionMapping = (Map) tuple.getValueByField("newIntervalPartition");
            BalancedPartition newBalancedPartition = (BalancedPartition) tuple.getValueByField("newIntervalPartition");
            balancedPartitionToBeDeleted = balancedPartition;
            balancedPartition = newBalancedPartition;
//            System.out.println("partition has been updated in decompostion bolt!!! ");
//            balancedPartition.setIntervalToPartitionMapping(intervalToPartitionMapping);
        } else if (tuple.getSourceStreamId().equals(Streams.PartialQueryResultDeliveryStream)) {
            long queryId = tuple.getLong(0);
            PartialQueryResult partialQueryResult = (PartialQueryResult) tuple.getValue(1);

            handlePartialQueryResult(queryId, partialQueryResult);

            // logic for handling results.
            System.out.println("Received a partialResult for " + queryId + " " + partialQueryResult.dataTuples.size() + " elements.");
            if(!partialQueryResult.getEOFFlag()) {
                collector.emit(Streams.PartialQueryResultReceivedStream, new Values(queryId));
            } else {
                System.out.println(String.format("All query results are collected for Query[%d] !!!!", queryId));
            }
        }
    }

    public abstract void handlePartialQueryResult(Long queryId, PartialQueryResult partialQueryResult);

    private boolean isPartitionDeletable() {
        Map<Integer, Integer> staleIntervalIdToPartitionIdMapping = balancedPartitionToBeDeleted.getIntervalToPartitionMapping();
        Map<Integer, Integer> intervalIdToPartitionIdMapping = balancedPartition.getIntervalToPartitionMapping();

        return staleIntervalIdToPartitionIdMapping.equals(intervalIdToPartitionIdMapping);
    }

    private void updateStaleBalancedPartition(T keyRangeLowerBound, T keyRangeUpperBound) {
        Map<Integer, Integer> intervalIdToPartitionIdMapping = balancedPartition.getIntervalToPartitionMapping();
        Integer startIntervalId = balancedPartition.getIntervalId(keyRangeLowerBound);
        Integer endIntervalId = balancedPartition.getIntervalId(keyRangeUpperBound);

        for (Integer intervalId = startIntervalId; intervalId <= endIntervalId; ++intervalId) {
            balancedPartitionToBeDeleted.getIntervalToPartitionMapping().put(intervalId, intervalIdToPartitionIdMapping.get(intervalId));
        }
    }

    public void declareOutputFields(OutputFieldsDeclarer outputFieldsDeclarer) {
        outputFieldsDeclarer.declareStream(Streams.FileSystemQueryStream, new Fields("subquery"));

        outputFieldsDeclarer.declareStream(Streams.BPlusTreeQueryStream, new Fields("subquery"));

        outputFieldsDeclarer.declareStream(
                Streams.FileSystemQueryInformationStream,
                new Fields("queryId", "numberOfFilesToScan"));

        outputFieldsDeclarer.declareStream(
                Streams.BPlusTreeQueryInformationStream,
                new Fields("queryId", "numberOfTasksToScan"));

        outputFieldsDeclarer.declareStream(Streams.TreeCleanStream, new Fields("keyDomain", "timeDomain"));

        outputFieldsDeclarer.declareStream(Streams.EnableRepartitionStream, new Fields("Repartition"));

        outputFieldsDeclarer.declareStream(Streams.PartialQueryResultReceivedStream, new Fields("queryId"));
    }

    private void handleQuery(Query<T> query) {
        generateSubQueriesOnTheInsertionServer(query);
        generateSubqueriesOnTheFileScanner(query);
    }

    private void generateSubqueriesOnTheFileScanner(Query<T> query) {
        Long queryId = query.getQueryId();
        T leftKey = query.leftKey;
        T rightKey = query.rightKey;
        Long startTimestamp = query.startTimestamp;
        Long endTimestamp = query.endTimestamp;

        List<String> fileNames = filePartitionSchemaManager.search(leftKey.doubleValue(), rightKey.doubleValue(), startTimestamp, endTimestamp);


//        if (fileNames.size() > 25) {
//            System.out.println("size " + fileNames.size());
//        }

        if (fileNames.size() < -1) {

            collector.emit(Streams.FileSystemQueryInformationStream, new Values(query, 0));
        } else {

            queryIdToStartTime.put(queryId, new Pair(System.currentTimeMillis(), fileNames.size()));

//            System.out.println("query id " + queryId);


            for (String fileName : fileNames) {
                SubQuery subQuery = new SubQueryOnFile(queryId, leftKey, rightKey, fileName, startTimestamp, endTimestamp, query.predicate, query.aggregator);
            //shuffle grouping
            sendSubqueriesByshuffleGrouping(subQuery);

//            /*task queue
//                try {
//                    taskQueue.put(subQuery);
//                } catch (InterruptedException e) {
//                    e.printStackTrace();
//                }
//              */

                /*our method
                putSubqueryToTaskQueues(subQuery);
                */
            }

//            /* task queue
//            sendSubqueriesFromTaskQueue();
//            */

            /*our method
                sendSubqueriesFromTaskQueues();
            */

            collector.emit(Streams.FileSystemQueryInformationStream, new Values(query, fileNames.size()));
        }
    }

    private void createQueryHandlingThread() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        Query query = pendingQueue.take();
                        concurrentQueriesSemaphore.acquire();
                        handleQuery(query);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }).start();
    }


    private void createTaskQueues(List<Integer> targetTasks) {
        for (Integer taskId : targetTasks) {
            ArrayBlockingQueue<SubQuery> taskQueue = new ArrayBlockingQueue<SubQuery>(TopologyConfig.TASK_QUEUE_CAPACITY);
            taskIdToTaskQueue.put(taskId, taskQueue);
        }
    }


    private void generateSubQueriesOnTheInsertionServer(Query<T> query) {
        int numberOfTasksToSearch = 0;
        Long queryId = query.queryId;
        T leftKey = query.leftKey;
        T rightKey = query.rightKey;
        Long startTimestamp = query.startTimestamp;
        Long endTimestamp = query.endTimestamp;


        List<Integer> partitionIdsInBalancedPartition = getPartitionIds(balancedPartition, leftKey, rightKey);
        List<Integer> partitionIdsInStalePartition = null;

        if (balancedPartitionToBeDeleted != null) {
            partitionIdsInStalePartition = getPartitionIds(balancedPartitionToBeDeleted, leftKey, rightKey);
        }

        List<Integer> partitionIds = (partitionIdsInStalePartition == null
                ? partitionIdsInBalancedPartition
                : mergePartitionIds(partitionIdsInBalancedPartition, partitionIdsInStalePartition));

        for (Integer partitionId : partitionIds) {
            Integer taskId = indexServers.get(partitionId);
            Long timestamp = indexTaskToTimestampMapping.get(taskId);
            if (timestamp <= endTimestamp && timestamp >= startTimestamp) {
                SubQuery subQuery = new SubQuery(query.getQueryId(), query.leftKey, query.rightKey, query.startTimestamp,
                        query.endTimestamp, query.predicate, query.aggregator);
                collector.emitDirect(taskId, Streams.BPlusTreeQueryStream, new Values(subQuery));
                ++numberOfTasksToSearch;
            }
        }

        collector.emit(Streams.BPlusTreeQueryInformationStream, new Values(queryId, numberOfTasksToSearch));
    }

    private List<Integer> mergePartitionIds(List<Integer> partitionIdsInBalancedPartition, List<Integer> partitionIdsInStalePartition) {
        List<Integer> partitionIds = new ArrayList<>();
        partitionIds.addAll(partitionIdsInBalancedPartition);

        for (Integer partitionId : partitionIdsInStalePartition) {
            if (!partitionIds.contains(partitionId)) {
                partitionIds.add(partitionId);
            }
        }

        return partitionIds;
    }

    private List<Integer> getPartitionIds(BalancedPartition balancedPartition, T leftKey, T rightKey) {
        Integer startPartitionId = balancedPartition.getPartitionId(leftKey);
        Integer endPartitionId = balancedPartition.getPartitionId(rightKey);

        List<Integer> partitionIds = new ArrayList<>();
        for (Integer partitionId = startPartitionId; partitionId <= endPartitionId; ++partitionId) {
            partitionIds.add(partitionId);
        }

        return partitionIds;
    }


    private void putSubqueryToTaskQueues(SubQuery subQuery) {
        String fileName = ((SubQueryOnFile) subQuery).getFileName();
        int index = Math.abs(fileName.hashCode()) % queryServers.size();
        Integer taskId = queryServers.get(index);
        ArrayBlockingQueue<SubQuery> taskQueue = taskIdToTaskQueue.get(taskId);
        try {
            taskQueue.put(subQuery);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        taskIdToTaskQueue.put(taskId, taskQueue);
    }


    private void sendSubqueriesFromTaskQueue() {
//        System.out.println(queryServers);
        for (Integer taskId : queryServers) {
//            System.out.println(taskId);
            SubQuery subQuery = taskQueue.poll();
            if (subQuery != null) {
//                System.out.println(taskId);
                collector.emitDirect(taskId, Streams.FileSystemQueryStream, new Values(subQuery));
//                System.out.println("query " + subQuery.getQueryId() + " has been sent to task " + taskId);
            }
        }
    }

    private void sendSubqueriesFromTaskQueues() {
        for (Integer taskId : queryServers) {
            ArrayBlockingQueue<SubQuery> taskQueue = taskIdToTaskQueue.get(taskId);
            SubQuery subQuery = taskQueue.poll();
            if (subQuery != null) {
                collector.emitDirect(taskId, Streams.FileSystemQueryStream
                        , new Values(subQuery));
            }
        }
    }


    private void sendSubqueriesByshuffleGrouping(SubQuery subQuery) {
        collector.emit(Streams.FileSystemQueryStream, new Values(subQuery));
    }


    private void sendSubqueryToTask(int taskId) {
        SubQuery subQuery = taskQueue.poll();

        if (subQuery != null) {
            collector.emitDirect(taskId, Streams.FileSystemQueryStream, new Values(subQuery));
        }
    }



    private void sendSubquery(int taskId) {
        ArrayBlockingQueue<SubQuery> taskQueue = taskIdToTaskQueue.get(taskId);

        SubQuery subQuery = taskQueue.poll();

        if (subQuery == null) {
            taskQueue = getLongestQueue();
            subQuery = taskQueue.poll();
        }

        if (subQuery != null) {
            collector.emitDirect(taskId, Streams.FileSystemQueryStream
                    , new Values(subQuery));
        }
    }

    private ArrayBlockingQueue<SubQuery> getLongestQueue() {
        List<ArrayBlockingQueue<SubQuery>> taskQueues
                = new ArrayList<ArrayBlockingQueue<SubQuery>>(taskIdToTaskQueue.values());

        Collections.sort(taskQueues, (taskQueue1, taskQueue2) -> Integer.compare(taskQueue2.size(), taskQueue1.size()));

        ArrayBlockingQueue<SubQuery> taskQueue = taskQueues.get(0);

        return taskQueue;
    }

    private void setTimestamps() {
        indexTaskToTimestampMapping = new HashMap<>();

        for (Integer taskId : indexServers) {
            indexTaskToTimestampMapping.put(taskId, System.currentTimeMillis());
        }
    }

}
