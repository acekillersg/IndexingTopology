package indexingTopology.util;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Output;
import indexingTopology.DataSchema;
import indexingTopology.config.TopologyConfig;
import indexingTopology.filesystem.FileSystemHandler;
import indexingTopology.filesystem.HdfsFileSystemHandler;
import indexingTopology.filesystem.LocalFileSystemHandler;
import indexingTopology.exception.UnsupportedGenericException;
import javafx.util.Pair;
import org.apache.storm.task.OutputCollector;
import org.apache.storm.tuple.Values;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;


/**
 * Created by acelzj on 1/3/17.
 */
public class IndexerCopy {

    private ArrayBlockingQueue<Pair> pendingQueue;

    private ArrayBlockingQueue<Pair> inputQueue;

    private BTree indexedData;

    private IndexingRunnable indexingRunnable;

    private List<Thread> indexingThreads;

    private TemplateUpdater templateUpdater;

    private Thread inputProcessingThread;

    private final static int numberOfIndexingThreads = 3;

    private AtomicLong executed;

    private int numTuples;

    private MemChunk chunk;

    private int chunkId;

    private long start;

    private String indexField;

    private Kryo kryo;

    private Double minIndexValue = Double.MAX_VALUE;
    private Double maxIndexValue = Double.MIN_VALUE;

    private Long minTimestamp = Long.MAX_VALUE;
    private Long maxTimestamp = Long.MIN_VALUE;

    private DataSchema schema;

    long queryId = 0;

    private OutputCollector collector;

    private int taskId;

    private Semaphore processQuerySemaphore;

    private Map<Domain, BTree> domainToBTreeMapping;

    public IndexerCopy(int taskId, ArrayBlockingQueue<Pair> inputQueue, BTree indexedData, MemChunk chunk, String indexedField, DataSchema schema) {
        pendingQueue = new ArrayBlockingQueue<>(1024);

        this.inputQueue = inputQueue;

        this.indexedData = indexedData;

        templateUpdater = new TemplateUpdater(TopologyConfig.BTREE_OREDER);

        start = System.currentTimeMillis();

        inputProcessingThread = new Thread(new InputProcessingRunnable());

        inputProcessingThread.start();

        executed = new AtomicLong(0);

        numTuples = 0;

        chunkId = 0;

        queryId = 0;

        indexingThreads = new ArrayList<>();

        this.chunk = chunk;

        this.indexField = indexedField;

        this.schema = schema;

        this.processQuerySemaphore = new Semaphore(1);

        kryo = new Kryo();
        kryo.register(BTree.class, new KryoTemplateSerializer());
        kryo.register(BTreeLeafNode.class, new KryoLeafNodeSerializer());

        this.collector = collector;

        this.taskId = taskId;

        domainToBTreeMapping = new HashMap<>();

        createIndexingThread();

        Thread queryThread = new Thread(new QueryRunnable());
        queryThread.start();
    }

    private void terminateIndexingThreads() {
        try {
            indexingRunnable.setInputExhausted();
            for (Thread thread : indexingThreads) {
                thread.join();
            }
            indexingThreads.clear();
            indexingRunnable = new IndexingRunnable();
//            System.out.println("All the indexing threads are terminated!");
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void createIndexingThread() {
        createIndexingThread(numberOfIndexingThreads);
    }

    private void createIndexingThread(int n) {
        if(indexingRunnable == null) {
            indexingRunnable = new IndexingRunnable();
        }
        for(int i = 0; i < n; i++) {
            Thread indexThread = new Thread(indexingRunnable);
            indexThread.start();
//            System.out.println(String.format("Thread %d is created!", indexThread.getId()));
            indexingThreads.add(indexThread);
        }
    }

    public BTree getIndexedData() {
        return indexedData;
    }


    class InputProcessingRunnable implements Runnable {

        @Override
        public void run() {

            ArrayList<Pair> drainer = new ArrayList<>();

            while (true) {

//                if (executed.get() >= TopologyConfig.NUM_TUPLES_TO_CHECK_TEMPLATE) {
//                    if (indexedData.getSkewnessFactor() >= TopologyConfig.REBUILD_TEMPLATE_PERCENTAGE) {
//                        while (!pendingQueue.isEmpty()) {
//                            try {
//                                Thread.sleep(1);
//                            } catch (InterruptedException e) {
//                                e.printStackTrace();
//                            }
//                        }
//
//                        terminateIndexingThreads();
//
//                        long start = System.currentTimeMillis();
//
//                        try {
//                            processQuerySemaphore.acquire();
//                        } catch (InterruptedException e) {
//                            e.printStackTrace();
//                        }
//
//                        indexedData = templateUpdater.createTreeWithBulkLoading(indexedData);
//
//                        System.out.println("Time used to update template " + (System.currentTimeMillis() - start));
//
//                        processQuerySemaphore.release();
//
//
//                        System.out.println("New tree has been built");
//
//                        executed.set(0L);
//
//                        createIndexingThread();
//                    }
//                }

                if (numTuples >= TopologyConfig.NUMBER_TUPLES_OF_A_CHUNK) {
                    while (!pendingQueue.isEmpty()) {
                        try {
                            Thread.sleep(1);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }

                    terminateIndexingThreads();

                    FileSystemHandler fileSystemHandler = null;
                    String fileName = null;

                    writeTreeIntoChunk();
//
                    try {
                        if (TopologyConfig.HDFSFlag) {
                            fileSystemHandler = new HdfsFileSystemHandler(TopologyConfig.dataDir);
                        } else {
                            fileSystemHandler = new LocalFileSystemHandler(TopologyConfig.dataDir);
                        }
                        fileName =  "taskId" + taskId + "chunk" + chunkId;
                        fileSystemHandler.writeToFileSystem(chunk, "/", fileName);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }

//                    Pair keyRange = new Pair(minIndexValue, maxIndexValue);
//                    Pair timeStampRange = new Pair(minTimeStamp, maxTimeStamp);

//                    Domain domain = new Domain(maxTimeStamp, maxTimeStamp, minIndexValue, maxIndexValue);

//                    domainToBTreeMapping.put(domain, indexedData);

//                    System.out.println("a chunk has been full");

//                    indexedData = indexedData.clone();

//                    indexedData.clearPayload();
                    createNewTemplate();

                    executed.set(0L);

                    createIndexingThread();

//                    domainToBTreeMapping.remove(domain);

                    start = System.currentTimeMillis();

                    numTuples = 0;

                    ++chunkId;
                }

//                try {
//                    Pair pair = inputQueue.take();
//
//                    pendingQueue.put(pair);
//
//                    ++numTuples;
//                } catch (InterruptedException e) {
//                    e.printStackTrace();
//                }
                inputQueue.drainTo(drainer, 256);

                for (Pair pair: drainer) {
                    try {
                        pendingQueue.put(pair);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }

                numTuples += drainer.size();

                drainer.clear();

            }
        }
    }

    private void createNewTemplate() {
        indexedData = new BTree(TopologyConfig.BTREE_OREDER);
    }

    class IndexingRunnable implements Runnable {

        boolean inputExhausted = false;

        public void setInputExhausted() {
            inputExhausted = true;
        }


        Long startTime;
        AtomicInteger threadIndex = new AtomicInteger(0);

        Object syn = new Object();

        @Override
        public void run() {
            boolean first = false;
            synchronized (syn) {
                if (startTime == null) {
                    startTime = System.currentTimeMillis();
                    first = true;
                }
            }
            long localCount = 0;
            ArrayList<Pair> drainer = new ArrayList<Pair>();
            while (true) {
                try {
//                        Pair pair = queue.poll(1, TimeUnit.MILLISECONDS);
//                        if (pair == null) {
//                        if(!first)
//                            Thread.sleep(100);

                    pendingQueue.drainTo(drainer, 256);

//                        Pair pair = queue.poll(10, TimeUnit.MILLISECONDS);
                    if (drainer.size() == 0) {
                        if (inputExhausted)
                            break;
                        else
                            continue;
                    }

                    for (Pair pair : drainer) {
                        localCount++;
                        final Double indexValue = (Double) pair.getKey();
//                            final Integer offset = (Integer) pair.getValue();
                        final byte[] serializedTuple = (byte[]) pair.getValue();
//                            System.out.println("insert");
                        indexedData.insert(indexValue, serializedTuple);
//                            indexedData.insert(indexValue, offset);
                    }

                    executed.addAndGet(drainer.size());

                    drainer.clear();

                } catch (UnsupportedGenericException e) {
                    e.printStackTrace();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
//            if(first) {
//                System.out.println(String.format("Index throughput = %f tuple / s", executed.get() / (double) (System.currentTimeMillis() - startTime) * 1000));
//                System.out.println("Thread execution time: " + (System.currentTimeMillis() - startTime) + " ms.");
//            }
//                System.out.println("Indexing thread " + Thread.currentThread().getId() + " is terminated with " + localCount + " tuples processed!");
        }
    }


    class QueryRunnable implements Runnable {
        @Override
        public void run() {
            while (true) {

//                Long queryId = (Long) pair.getKey();
//
//                Pair keyRange = (Pair) pair.getValue();
//
//                Double leftKey = (Double) keyRange.getKey();
//
//                Double rightKey = (Double) keyRange.getValue();
//
//                List<byte[]> serializedTuples = null;
//
//                if (leftKey.compareTo(rightKey) == 0) {
//                    serializedTuples = indexedData.searchTuples(leftKey);
//                } else {
//                    serializedTuples = indexedData.search(leftKey, rightKey);
//                }

                try {
                    processQuerySemaphore.acquire();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }


                List<byte[]> serializedTuples = null;
                serializedTuples = indexedData.searchRange(0.0, 1000.0);

                for (int i = 0; i < serializedTuples.size(); ++i) {
                    Values deserializedTuple = null;
                    try {
                        deserializedTuple = schema.deserialize(serializedTuples.get(i));
                        deserializedTuple = schema.deserialize(serializedTuples.get(i));
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    System.out.println(deserializedTuple);
                }

                processQuerySemaphore.release();

                System.out.println(queryId + " query has been finished!!!");

                ++queryId;
            }
        }
    }


    private void writeTreeIntoChunk() {
        Output output = new Output(65000000, 20000000);

        byte[] leavesInBytes = indexedData.serializeLeaves();

        kryo.writeObject(output, indexedData);

        byte[] bytes = output.toBytes();

        int lengthOfTemplate = bytes.length;

        output = new Output(4);

        output.writeInt(lengthOfTemplate);

        byte[] lengthInBytes = output.toBytes();

        chunk = MemChunk.createNew(leavesInBytes.length + 4 + lengthOfTemplate);

        chunk.write(lengthInBytes );

        chunk.write(bytes);

        chunk.write(leavesInBytes);
    }
}